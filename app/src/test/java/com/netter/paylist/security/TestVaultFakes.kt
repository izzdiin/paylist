package com.netter.paylist.security

/**
 * Shared fakes for the vault/migration tests. No Robolectric: plain
 * JVM unit tests, so the Android Keystore and SharedPreferences are
 * replaced by in-memory fakes behind the production interfaces.
 */

/** In-memory [KeyValueStore] with failure hooks and call tracking. */
internal class InMemoryKeyValueStore : KeyValueStore {
    private val map = LinkedHashMap<String, String>()
    var failNextPut: Boolean = false
    var failAllPuts: Boolean = false
    var putCount: Int = 0
        private set
    var getCount: Int = 0
        private set

    override fun getString(key: String): String? {
        getCount++
        return map[key]
    }

    override fun putString(key: String, value: String): Boolean {
        putCount++
        if (failAllPuts || failNextPut) {
            failNextPut = false
            return false
        }
        map[key] = value
        return true
    }

    override fun remove(key: String): Boolean {
        if (failAllPuts || failNextPut) {
            failNextPut = false
            return false
        }
        map.remove(key)
        return true
    }

    override fun contains(key: String): Boolean = map.containsKey(key)

    /** All stored values (for the F44 "no secret values in prefs" scan). */
    fun allValues(): List<String> = map.values.toList()

    /** All stored keys. */
    fun allKeys(): Set<String> = map.keys.toSet()

    /** Direct seed, bypassing the failure hooks. */
    fun seed(key: String, value: String) {
        map[key] = value
    }
}

/**
 * Fake [VaultCrypto]: a reversible XOR transform (never stores
 * plaintext — the stored blob differs from the secret) with a
 * decrypt-failure mode that simulates an invalidated Keystore key.
 */
internal class FakeVaultCrypto : VaultCrypto {
    var failDecrypt: Boolean = false
    var encryptCount: Int = 0
        private set
    var decryptCount: Int = 0
        private set

    private fun xor(bytes: ByteArray): ByteArray =
        ByteArray(bytes.size) { i -> (bytes[i].toInt() xor 0x5A).toByte() }

    override fun encrypt(plaintext: ByteArray): EncryptedBlob {
        encryptCount++
        // Fixed IV: determinism is fine for a test fake (the production
        // backend uses a fresh random IV per encryption).
        return EncryptedBlob(ByteArray(12) { 0x01 }, xor(plaintext))
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        decryptCount++
        if (failDecrypt) {
            throw VaultKeyUnavailableException("simulated invalidated key")
        }
        return xor(blob.ciphertext)
    }
}
