package com.netter.paylist.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal synchronous key-value store (design §7.8). Production:
 * [SharedPreferencesKeyValueStore] (synchronous `commit()`); tests:
 * an in-memory fake. Every write returns whether it committed — a
 * `commit()` returning false is a failure, never success (§C marker
 * rule).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String): Boolean
    fun remove(key: String): Boolean
    fun contains(key: String): Boolean
}

/** [KeyValueStore] over Android SharedPreferences (synchronous commits). */
class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()
    override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
    override fun contains(key: String): Boolean = prefs.contains(key)
}

/** Thrown when the Keystore key is invalidated, unavailable, or the ciphertext fails authentication. */
class VaultKeyUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** One AES-GCM ciphertext: a fresh random IV per encryption. */
data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedBlob) return false
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
}

/**
 * The AES-GCM crypto backend. Production: [AndroidKeystoreCrypto]
 * (Android Keystore, one key for all route secrets, per-secret random
 * IV). Tests: an in-memory fake. The vault logic never depends on
 * Android directly, so it is unit-testable on the JVM.
 */
interface VaultCrypto {
    @Throws(VaultKeyUnavailableException::class)
    fun encrypt(plaintext: ByteArray): EncryptedBlob

    @Throws(VaultKeyUnavailableException::class)
    fun decrypt(blob: EncryptedBlob): ByteArray
}

/** [VaultCrypto] over the Android Keystore (AES/GCM/NoPadding). */
class AndroidKeystoreCrypto : VaultCrypto {
    companion object {
        const val KEY_ALIAS: String = "paylist_route_secret"
        private const val GCM_TAG_BITS: Int = 128
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = loadKeyStore()
        if (!ks.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }
        return ks.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun deleteKey() {
        try {
            val ks = loadKeyStore()
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Best effort: a stale invalidated key only blocks the next
            // putSecret, which reports failure instead of crashing.
        }
    }

    override fun encrypt(plaintext: ByteArray): EncryptedBlob {
        try {
            return encryptWith(getOrCreateKey(), plaintext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The key is dead (e.g. lock-screen change): drop it so a
            // fresh key is generated, then retry once. Old ciphertexts
            // encrypted under the dead key are unrecoverable — their
            // routes surface as Unavailable until the operator
            // re-enters the secret.
            deleteKey()
            try {
                return encryptWith(getOrCreateKey(), plaintext)
            } catch (e2: Exception) {
                throw VaultKeyUnavailableException("keystore encrypt failed", e2)
            }
        } catch (e: Exception) {
            throw VaultKeyUnavailableException("keystore encrypt failed", e)
        }
    }

    private fun encryptWith(key: SecretKey, plaintext: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedBlob(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, blob.iv),
            )
            return cipher.doFinal(blob.ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultKeyUnavailableException("keystore key invalidated", e)
        } catch (e: AEADBadTagException) {
            throw VaultKeyUnavailableException("ciphertext authentication failed", e)
        } catch (e: Exception) {
            throw VaultKeyUnavailableException("keystore decrypt failed", e)
        }
    }
}

/** The result of a secret lookup (design §7.8). */
sealed interface SecretResult {
    /** The route has a readable secret. */
    data class Present(val value: String) : SecretResult

    /** No vault entry for the route (never migrated, deleted, or restored without secrets). */
    data object Missing : SecretResult

    /** An entry exists but cannot be read (invalidated key, corrupt ciphertext). Never a plaintext fallback. */
    data class Unavailable(val reason: String) : SecretResult
}

/**
 * The read side of the vault: the only way delivery code obtains a
 * route secret. Implementations: [SecretVault] (production) and a fake
 * in tests. There is no plaintext-backed provider.
 */
fun interface SecretProvider {
    fun secretFor(routeId: String): SecretResult
}

/**
 * Android Keystore AES-GCM secret storage (design §7.8, F-P1-06/C17):
 * the SOLE production reader of route secrets after the one-time
 * migration. Ciphertext + IV live in the app-private `paylist_vault`
 * preferences file (Base64 of IV || ciphertext, one entry per route);
 * the Keystore key never leaves the Keystore and does not survive
 * backup/restore — a restored device has no readable secrets, by
 * design (P10 option C).
 *
 * Invalidated/unavailable keys surface as [SecretResult.Unavailable]
 * (BLOCKED, operator re-entry required); there is never a plaintext
 * fallback. No method here logs or returns a secret except
 * [secretFor], which returns it only to the authenticated transport
 * caller.
 */
class SecretVault(
    private val store: KeyValueStore,
    private val crypto: VaultCrypto,
) : SecretProvider {
    constructor(context: Context) : this(
        SharedPreferencesKeyValueStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        AndroidKeystoreCrypto(),
    )

    companion object {
        const val PREFS_NAME: String = "paylist_vault"

        /** Vault storage key for one route's ciphertext blob. */
        fun keyFor(routeId: String): String = "secret:$routeId"
    }

    /** Stores (or overwrites) a route secret. Returns false when encryption or the commit failed. */
    fun putSecret(routeId: String, secret: String): Boolean {
        return try {
            val blob = crypto.encrypt(secret.toByteArray(Charsets.UTF_8))
            val combined = blob.iv + blob.ciphertext
            store.putString(keyFor(routeId), Base64.getEncoder().encodeToString(combined))
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes a route's vault entry. Returns false when the commit failed. */
    fun deleteSecret(routeId: String): Boolean {
        return try {
            store.remove(keyFor(routeId))
        } catch (_: Exception) {
            false
        }
    }

    fun hasSecret(routeId: String): Boolean = store.contains(keyFor(routeId))

    override fun secretFor(routeId: String): SecretResult {
        val encoded = store.getString(keyFor(routeId)) ?: return SecretResult.Missing
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            // GCM IV is 12 bytes; anything shorter cannot be a valid blob.
            if (combined.size <= 12) {
                return SecretResult.Unavailable("corrupt vault entry")
            }
            val blob = EncryptedBlob(
                iv = combined.copyOfRange(0, 12),
                ciphertext = combined.copyOfRange(12, combined.size),
            )
            val plaintext = crypto.decrypt(blob)
            SecretResult.Present(String(plaintext, Charsets.UTF_8))
        } catch (e: VaultKeyUnavailableException) {
            SecretResult.Unavailable(e.message ?: "key unavailable")
        } catch (_: IllegalArgumentException) {
            SecretResult.Unavailable("corrupt vault entry")
        } catch (e: Exception) {
            SecretResult.Unavailable(e.message ?: "vault read failed")
        }
    }
}
