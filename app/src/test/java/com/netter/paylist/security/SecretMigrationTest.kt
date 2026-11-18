package com.netter.paylist.security

import com.netter.paylist.migration.SecretMigration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SecretMigration tests (design §10.2): owns fixture F44 — the
 * one-time plaintext secret → Keystore migration (R23 as corrected by
 * R24). Runs on the plain JVM with in-memory stores + the fake crypto
 * backend.
 */
class SecretMigrationTest {

    private class Fixture(
        val routesStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        val markerStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        val vaultStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        val crypto: FakeVaultCrypto = FakeVaultCrypto(),
    ) {
        val vault = SecretVault(vaultStore, crypto)
        val migration = SecretMigration(routesStore, markerStore, vault)

        fun seedRoutes(vararg routes: JSONObject) {
            val arr = JSONArray()
            routes.forEach { arr.put(it) }
            routesStore.seed(SecretMigration.ROUTES_KEY, arr.toString())
        }

        fun routesJson(): JSONArray =
            JSONArray(routesStore.getString(SecretMigration.ROUTES_KEY) ?: "[]")

        fun routeById(id: String): JSONObject? {
            val arr = routesJson()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i)
                if (r != null && r.optString("id") == id) return r
            }
            return null
        }
    }

    private fun routeJson(id: String, secret: String?): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", "Route $id")
            put("packages", JSONArray(listOf("com.gojek.gopaymerchant")))
            put("url", "https://example.invalid/notify.php")
            if (secret != null) put("secret", secret)
            put("keywords", JSONArray(listOf("qris")))
            put("enabled", true)
        }

    @Test
    fun f44_upgradeFixture() {
        val f = Fixture()
        f.seedRoutes(routeJson("route-1", "alpha-secret"), routeJson("route-2", "beta-secret"))
        val report = f.migration.migrate()
        assertFalse(report.routesCorrupt)
        assertEquals(SecretMigration.RouteOutcome.Migrated, report.results["route-1"])
        assertEquals(SecretMigration.RouteOutcome.Migrated, report.results["route-2"])
        // Post-migration the prefs file contains no secret values.
        val raw = f.routesStore.getString(SecretMigration.ROUTES_KEY)!!
        assertFalse(raw.contains("alpha-secret"))
        assertFalse(raw.contains("beta-secret"))
        assertFalse(f.routeById("route-1")!!.has("secret"))
        assertFalse(f.routeById("route-2")!!.has("secret"))
        // Keystore read-back equals the original.
        assertEquals(
            "alpha-secret",
            (f.vault.secretFor("route-1") as SecretResult.Present).value,
        )
        assertEquals(
            "beta-secret",
            (f.vault.secretFor("route-2") as SecretResult.Present).value,
        )
        // Markers written.
        assertTrue(f.markerStore.contains(SecretMigration.markerKey("route-1")))
        assertTrue(f.markerStore.contains(SecretMigration.markerKey("route-2")))
    }

    @Test
    fun f44_secondLaunchPerformsZeroPlaintextSecretReads() {
        val f = Fixture()
        f.seedRoutes(routeJson("route-1", "alpha-secret"))
        f.migration.migrate()
        val encryptsAfterFirst = f.crypto.encryptCount
        assertEquals(1, encryptsAfterFirst)
        // Second launch: the routes file now carries DIFFERENT sentinel
        // values (as if an operator re-entered secrets out-of-band).
        // The migration must not read or migrate them after the marker.
        f.seedRoutes(routeJson("route-1", "SENTINEL-NEVER-READ"))
        val report = f.migration.migrate()
        assertEquals(SecretMigration.RouteOutcome.AlreadyMigrated, report.results["route-1"])
        assertEquals("no vault write on the second launch", encryptsAfterFirst, f.crypto.encryptCount)
        assertEquals(
            "the original secret survives; the sentinel was never read",
            "alpha-secret",
            (f.vault.secretFor("route-1") as SecretResult.Present).value,
        )
    }

    @Test
    fun f44_crashBetweenVaultWriteAndDeleteIsIdempotent() {
        val f = Fixture()
        f.seedRoutes(routeJson("route-1", "alpha-secret"), routeJson("route-2", "beta-secret"))
        // The plaintext-delete commit fails (crash window simulation).
        f.routesStore.failNextPut = true
        val first = f.migration.migrate()
        assertTrue(first.results["route-1"] is SecretMigration.RouteOutcome.Failed)
        assertTrue(first.results["route-2"] is SecretMigration.RouteOutcome.Failed)
        // No markers: the plaintext is treated as still present.
        assertFalse(f.markerStore.contains(SecretMigration.markerKey("route-1")))
        assertFalse(f.markerStore.contains(SecretMigration.markerKey("route-2")))
        // The plaintext is still in the file.
        val raw = f.routesStore.getString(SecretMigration.ROUTES_KEY)!!
        assertTrue(raw.contains("alpha-secret"))
        assertTrue(raw.contains("beta-secret"))
        // Re-run migrates idempotently: no loss, no duplicate.
        val second = f.migration.migrate()
        assertEquals(SecretMigration.RouteOutcome.Migrated, second.results["route-1"])
        assertEquals(SecretMigration.RouteOutcome.Migrated, second.results["route-2"])
        assertEquals(
            "alpha-secret",
            (f.vault.secretFor("route-1") as SecretResult.Present).value,
        )
        assertEquals(
            "beta-secret",
            (f.vault.secretFor("route-2") as SecretResult.Present).value,
        )
        assertEquals("one vault entry per route", 2, f.vaultStore.allKeys().size)
    }

    @Test
    fun f44_crashBetweenDeleteAndMarkerVerifiesBeforeMarking() {
        val f = Fixture()
        // Pre-state: plaintext already deleted, vault entry present, no marker.
        f.seedRoutes(routeJson("route-1", null))
        assertTrue(f.vault.putSecret("route-1", "alpha-secret"))
        val encryptsBefore = f.crypto.encryptCount
        val report = f.migration.migrate()
        assertEquals(SecretMigration.RouteOutcome.Migrated, report.results["route-1"])
        assertTrue(f.markerStore.contains(SecretMigration.markerKey("route-1")))
        assertEquals(
            "no re-put: the fresh Keystore read verified the entry",
            encryptsBefore,
            f.crypto.encryptCount,
        )
    }

    @Test
    fun f44_keystoreAbsentAfterDeleteIsBlocked() {
        val f = Fixture()
        // Pre-state: plaintext gone, no vault entry, no marker.
        f.seedRoutes(routeJson("route-1", null))
        val report = f.migration.migrate()
        assertEquals(SecretMigration.RouteOutcome.BlockedMissingSecret, report.results["route-1"])
        assertFalse("no marker on BLOCKED", f.markerStore.contains(SecretMigration.markerKey("route-1")))
        assertTrue(f.migration.isMissingSecret("route-1"))
    }

    @Test
    fun f44_keystoreUnreadableAfterDeleteIsBlocked() {
        val f = Fixture()
        f.seedRoutes(routeJson("route-1", null))
        assertTrue(f.vault.putSecret("route-1", "alpha-secret"))
        f.crypto.failDecrypt = true
        val report = f.migration.migrate()
        assertEquals(SecretMigration.RouteOutcome.BlockedMissingSecret, report.results["route-1"])
        assertFalse(f.markerStore.contains(SecretMigration.markerKey("route-1")))
    }

    @Test
    fun f44_multiRoutePartialMigration() {
        val f = Fixture()
        // route-1: already migrated (marker + vault entry).
        f.markerStore.seed(SecretMigration.markerKey("route-1"), "true")
        assertTrue(f.vault.putSecret("route-1", "alpha-secret"))
        val blobBefore = f.vaultStore.getString(SecretVault.keyFor("route-1"))
        val encryptsBefore = f.crypto.encryptCount
        // route-2: plaintext to migrate. route-3: empty secret.
        f.seedRoutes(
            routeJson("route-1", "alpha-secret"),
            routeJson("route-2", "beta-secret"),
            routeJson("route-3", ""),
        )
        val report = f.migration.migrate()
        assertEquals(SecretMigration.RouteOutcome.AlreadyMigrated, report.results["route-1"])
        assertEquals(SecretMigration.RouteOutcome.Migrated, report.results["route-2"])
        assertEquals(SecretMigration.RouteOutcome.NoSecret, report.results["route-3"])
        // Re-run scope limited to unmarked routes: the already-migrated
        // route's vault entry is untouched.
        assertEquals(encryptsBefore + 1, f.crypto.encryptCount)
        assertEquals(blobBefore, f.vaultStore.getString(SecretVault.keyFor("route-1")))
        assertTrue(f.markerStore.contains(SecretMigration.markerKey("route-2")))
        assertTrue(f.markerStore.contains(SecretMigration.markerKey("route-3")))
        // No vault entry for the empty-secret route.
        assertEquals(SecretResult.Missing, f.vault.secretFor("route-3"))
    }

    @Test
    fun f44_routesCorrupt() {
        val f = Fixture()
        f.routesStore.seed(SecretMigration.ROUTES_KEY, "{not json")
        val report = f.migration.migrate()
        assertTrue(report.routesCorrupt)
        assertTrue(report.results.isEmpty())
    }

    @Test
    fun f44_noRoutesIsEmptyReport() {
        val f = Fixture()
        val report = f.migration.migrate()
        assertFalse(report.routesCorrupt)
        assertTrue(report.results.isEmpty())
    }

    @Test
    fun f44_onlySecretMigrationReferencesPlaintextKeys() {
        // Static check: the plaintext secret JSON key ("secret") and
        // the Route.secret field must be referenced only by
        // SecretMigration.kt. The vault's own storage key ("secret:<id>")
        // is a ciphertext key, not a plaintext key, and must not match.
        val root = findSourceRoot()
            ?: throw AssertionError("source root not found — fail-closed")
        val keyLiteral = Regex("""\b(put|optString|getString|remove|contains)\s*\(\s*"secret"""")
        val fieldAccess = Regex("""\.\s*secret\b""")
        val violations = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.name == "SecretMigration.kt") return@forEach
            file.readLines().forEachIndexed { index, line ->
                if (keyLiteral.containsMatchIn(line) || fieldAccess.containsMatchIn(line)) {
                    violations.add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
                }
            }
        }
        assertTrue(
            "only SecretMigration.kt may reference the plaintext secret keys: $violations",
            violations.isEmpty(),
        )
    }

    /**
     * Locates `src/main/java` for the static check (mirrors CutoverTest).
     */
    private fun findSourceRoot(): File? {
        listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }?.let { return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val base = dir ?: break
            val appSrc = File(base, "app/src/main/java")
            if (appSrc.isDirectory) return appSrc
            val src = File(base, "src/main/java")
            if (src.isDirectory) return src
            dir = base.parentFile
        }
        return null
    }
}
