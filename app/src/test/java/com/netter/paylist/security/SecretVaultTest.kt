package com.netter.paylist.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SecretVault tests (design §7.8, file plan §E): Keystore
 * invalidation → BLOCKED, no plaintext fallback. Runs on the plain
 * JVM against the fake crypto backend + in-memory store; the Android
 * Keystore backend itself is exercised on-device/instrumented runs,
 * not here.
 */
class SecretVaultTest {

    private fun vault(
        store: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        crypto: FakeVaultCrypto = FakeVaultCrypto(),
    ): SecretVault = SecretVault(store, crypto)

    @Test
    fun putAndGetRoundTrip() {
        val v = vault()
        assertTrue(v.putSecret("route-1", "s3cr3t-value"))
        val result = v.secretFor("route-1")
        assertTrue(result is SecretResult.Present)
        assertEquals("s3cr3t-value", (result as SecretResult.Present).value)
        assertTrue(v.hasSecret("route-1"))
    }

    @Test
    fun missingRouteIsMissing() {
        val v = vault()
        assertEquals(SecretResult.Missing, v.secretFor("no-such-route"))
        assertFalse(v.hasSecret("no-such-route"))
    }

    @Test
    fun deleteRemovesEntry() {
        val store = InMemoryKeyValueStore()
        val v = vault(store)
        assertTrue(v.putSecret("route-1", "s3cr3t"))
        assertTrue(v.deleteSecret("route-1"))
        assertEquals(SecretResult.Missing, v.secretFor("route-1"))
        assertFalse(v.hasSecret("route-1"))
    }

    @Test
    fun overwriteReplacesSecret() {
        val v = vault()
        assertTrue(v.putSecret("route-1", "first"))
        assertTrue(v.putSecret("route-1", "second"))
        assertEquals(
            "second",
            (v.secretFor("route-1") as SecretResult.Present).value,
        )
    }

    @Test
    fun keyInvalidationIsUnavailableNeverPlaintext() {
        val crypto = FakeVaultCrypto()
        val v = vault(crypto = crypto)
        assertTrue(v.putSecret("route-1", "s3cr3t"))
        crypto.failDecrypt = true
        val result = v.secretFor("route-1")
        assertTrue("invalidated key must surface as Unavailable", result is SecretResult.Unavailable)
        // No plaintext fallback: the secret is not returned in any form.
        assertFalse(result is SecretResult.Present)
    }

    @Test
    fun putFailureReturnsFalse() {
        val store = InMemoryKeyValueStore().apply { failNextPut = true }
        val v = vault(store)
        assertFalse(v.putSecret("route-1", "s3cr3t"))
        assertEquals(SecretResult.Missing, v.secretFor("route-1"))
    }

    @Test
    fun storedBlobIsNotPlaintext() {
        val store = InMemoryKeyValueStore()
        val v = vault(store)
        assertTrue(v.putSecret("route-1", "s3cr3t-value"))
        val stored = store.getString(SecretVault.keyFor("route-1"))!!
        assertFalse("ciphertext must not contain the secret", stored.contains("s3cr3t-value"))
    }

    @Test
    fun corruptBlobIsUnavailable() {
        val store = InMemoryKeyValueStore()
        store.seed(SecretVault.keyFor("route-1"), "!!!not-base64!!!")
        val v = vault(store)
        assertTrue(v.secretFor("route-1") is SecretResult.Unavailable)
    }

    @Test
    fun shortBlobIsUnavailable() {
        val store = InMemoryKeyValueStore()
        val short = java.util.Base64.getEncoder().encodeToString(ByteArray(5))
        store.seed(SecretVault.keyFor("route-1"), short)
        val v = vault(store)
        assertTrue(v.secretFor("route-1") is SecretResult.Unavailable)
    }
}
