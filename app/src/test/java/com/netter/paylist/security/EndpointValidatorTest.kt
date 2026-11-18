package com.netter.paylist.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** EndpointValidator tests (design §7.8): HTTPS-only for new URLs. */
class EndpointValidatorTest {

    @Test
    fun httpsIsValid() {
        assertEquals(
            EndpointValidation.Valid,
            EndpointValidator.validate("https://bill.netterhome.net/bayarQR/notify.php"),
        )
    }

    @Test
    fun httpsWithPortPathAndQueryIsValid() {
        assertEquals(
            EndpointValidation.Valid,
            EndpointValidator.validate("https://example.invalid:8443/a/b?x=1"),
        )
    }

    @Test
    fun httpIsLegacyFlagged() {
        assertEquals(
            EndpointValidation.LegacyHttp,
            EndpointValidator.validate("http://192.168.1.1/notify.php"),
        )
    }

    @Test
    fun unsupportedSchemeIsInvalid() {
        val result = EndpointValidator.validate("ftp://example.invalid/x")
        assertTrue(result is EndpointValidation.Invalid)
    }

    @Test
    fun emptyIsInvalid() {
        assertTrue(EndpointValidator.validate("") is EndpointValidation.Invalid)
        assertTrue(EndpointValidator.validate("   ") is EndpointValidation.Invalid)
    }

    @Test
    fun noSchemeIsInvalid() {
        assertTrue(EndpointValidator.validate("example.invalid/notify.php") is EndpointValidation.Invalid)
    }

    @Test
    fun credentialsInUrlAreInvalid() {
        val result = EndpointValidator.validate("https://user:pass@example.invalid/x")
        assertTrue(result is EndpointValidation.Invalid)
    }

    @Test
    fun missingHostIsInvalid() {
        assertTrue(EndpointValidator.validate("https:///notify.php") is EndpointValidation.Invalid)
    }

    @Test
    fun malformedIsInvalid() {
        assertTrue(EndpointValidator.validate("https://exa mple.invalid/") is EndpointValidation.Invalid)
    }
}
