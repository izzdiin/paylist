package com.netter.paylist.migration

import com.netter.paylist.security.KeyValueStore
import com.netter.paylist.security.SecretResult
import com.netter.paylist.security.SecretVault
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one-time plaintext secret → Keystore migration (design §7.8,
 * file plan §C; Stage C, R23/R24). Runs at first v1.1 launch; idempotent
 * re-runs are safe.
 *
 * Per route, in crash-safe order:
 * 1. Read the plaintext secret from the raw routes JSON exactly once.
 * 2. Write it to the Keystore via [SecretVault].
 * 3. Verify by read-back (the vault read must equal the original).
 * 4. Delete the plaintext (rewrite the routes array without the
 *    `secret` key for the verified routes, one synchronous commit).
 * 5. Write the per-route marker in the dedicated `paylist_migration`
 *    preferences file.
 *
 * Marker rule (R24 §5, corrected): the marker is written only after
 * verification in the same run — either the full sequence with
 * read-back equality, or on a re-run where the plaintext is already
 * absent, a fresh Keystore read proving the entry is present AND
 * decryptable. Absence of plaintext alone never proves success: if
 * the plaintext is absent AND the Keystore entry is absent,
 * unreadable, or mismatched ⇒ NO marker; the route is marked
 * `DISABLED_MISSING_SECRET` (BLOCKED) with explicit recovery (the
 * operator re-enters the secret).
 *
 * `commit()` failure is not success: a failed plaintext-delete commit
 * ⇒ the plaintext is treated as still present, no marker is written,
 * and the delete is retried (on the next launch). The same applies to
 * the marker write itself. Migration state is one marker per route
 * id, independent of the routes/queue file.
 *
 * This is the ONLY file that references the plaintext secret keys
 * (asserted by the F44 static check): after the migration, no code
 * reads secrets from SharedPreferences.
 */
class SecretMigration(
    private val routesStore: KeyValueStore,
    private val markerStore: KeyValueStore,
    private val vault: SecretVault,
) {
    companion object {
        /** The routes JSON array key in the `paylist` preferences file. */
        const val ROUTES_KEY: String = "routes"

        /** The dedicated migration-state preferences file. */
        const val MIGRATION_PREFS_NAME: String = "paylist_migration"

        /** Per-route migration marker key. */
        fun markerKey(routeId: String): String = "migrated:$routeId"

        /** Per-route missing-secret flag (DISABLED_MISSING_SECRET). */
        fun missingKey(routeId: String): String = "missing_secret:$routeId"
    }

    /** The outcome for one route. */
    sealed interface RouteOutcome {
        /** Migrated in this run (vault write + read-back + delete + marker). */
        data object Migrated : RouteOutcome

        /** Already migrated in an earlier run; untouched. */
        data object AlreadyMigrated : RouteOutcome

        /** The route had an empty secret: nothing to migrate, marker written. */
        data object NoSecret : RouteOutcome

        /** Plaintext gone, vault entry absent/unreadable: BLOCKED, no marker, recovery required. */
        data object BlockedMissingSecret : RouteOutcome

        /** A write, commit, or verification failed: no marker, retry on the next launch. */
        data class Failed(val reason: String) : RouteOutcome
    }

    /** The outcome of one [migrate] run. */
    data class MigrationReport(
        val results: Map<String, RouteOutcome>,
        /** True when the routes JSON itself is unparseable (nothing was processed). */
        val routesCorrupt: Boolean = false,
    )

    /** True when the route is durably marked DISABLED_MISSING_SECRET. */
    fun isMissingSecret(routeId: String): Boolean =
        markerStore.getString(missingKey(routeId)) == "true"

    /**
     * Runs one migration pass over all routes. Safe to re-run: already
     * migrated routes are skipped, and every crash window re-enters
     * cleanly (see the class KDoc).
     */
    fun migrate(): MigrationReport {
        val raw = routesStore.getString(ROUTES_KEY) ?: return MigrationReport(emptyMap())
        val routes = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return MigrationReport(emptyMap(), routesCorrupt = true)
        }
        val results = LinkedHashMap<String, RouteOutcome>()
        // Routes verified this run (vault write + read-back OK) whose
        // plaintext must be deleted in the batched rewrite.
        val verifiedForDelete = mutableListOf<String>()
        // Routes whose plaintext is already absent but whose vault
        // entry verified by fresh read: marker only, no rewrite.
        val verifiedForMarker = mutableListOf<String>()
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val routeId = route.optString("id")
            if (routeId.isEmpty()) {
                // A route without an id cannot be keyed in the vault;
                // leave it untouched (Route.fromJson assigns an id on
                // read, but the migration must not invent one).
                continue
            }
            if (markerStore.contains(markerKey(routeId))) {
                results[routeId] = RouteOutcome.AlreadyMigrated
                continue
            }
            val hasSecretKey = route.has("secret")
            val plaintext = route.optString("secret")
            if (!hasSecretKey) {
                // Plaintext already absent (crash between delete and
                // marker, or the route never had a secret key).
                when (val read = vault.secretFor(routeId)) {
                    is SecretResult.Present -> {
                        // Fresh Keystore read proves the entry is
                        // present AND decryptable: the marker may be
                        // written without re-migrating.
                        verifiedForMarker.add(routeId)
                        results[routeId] = RouteOutcome.Migrated
                    }
                    else -> {
                        // Plaintext gone and no readable vault entry:
                        // BLOCKED, no marker, explicit recovery.
                        markerStore.putString(missingKey(routeId), "true")
                        results[routeId] = RouteOutcome.BlockedMissingSecret
                    }
                }
                continue
            }
            if (plaintext.isEmpty()) {
                // Empty secret: nothing to migrate, but the stale key
                // is still removed in the batched rewrite.
                verifiedForDelete.add(routeId)
                results[routeId] = RouteOutcome.NoSecret
                continue
            }
            // Full sequence: vault write, then read-back verification.
            if (!vault.putSecret(routeId, plaintext)) {
                results[routeId] = RouteOutcome.Failed("vault write failed")
                continue
            }
            val readBack = vault.secretFor(routeId)
            if (readBack !is SecretResult.Present || readBack.value != plaintext) {
                results[routeId] = RouteOutcome.Failed("read-back mismatch")
                continue
            }
            verifiedForDelete.add(routeId)
            results[routeId] = RouteOutcome.Migrated
        }
        // Batched plaintext delete: rewrite the routes array without
        // the `secret` key for the verified routes, one commit.
        if (verifiedForDelete.isNotEmpty()) {
            val rewritten = JSONArray()
            for (i in 0 until routes.length()) {
                val route = routes.optJSONObject(i) ?: continue
                val copy = JSONObject(route.toString())
                if (copy.optString("id") in verifiedForDelete) {
                    copy.remove("secret")
                }
                rewritten.put(copy)
            }
            val deleted = routesStore.putString(ROUTES_KEY, rewritten.toString())
            if (!deleted) {
                // The delete did not commit: the plaintext is treated
                // as still present; no marker for any route in this
                // batch (a re-run retries the delete).
                for (routeId in verifiedForDelete) {
                    results[routeId] = RouteOutcome.Failed("plaintext delete failed")
                }
                // Marker-only routes are unaffected by the delete.
                writeMarkers(verifiedForMarker, results)
                return MigrationReport(results)
            }
        }
        // Markers, only after the delete committed (or when no delete
        // was needed). A failed marker commit leaves the route
        // unmarked; the next run re-verifies via fresh vault read.
        writeMarkers(verifiedForDelete + verifiedForMarker, results)
        return MigrationReport(results)
    }

    private fun writeMarkers(routeIds: List<String>, results: MutableMap<String, RouteOutcome>) {
        for (routeId in routeIds) {
            val marked = markerStore.putString(markerKey(routeId), "true")
            if (!marked) {
                results[routeId] = RouteOutcome.Failed("marker write failed")
            }
        }
    }
}
