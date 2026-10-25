package it.fotofesta.tv.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpSupportTest {
    @Test
    fun `reads oauth error code and description`() {
        val error = httpError(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
        assertEquals(400, error.code)
        assertEquals("invalid_grant", error.oauthError)
        assertEquals("Token has been expired or revoked.", error.serverMessage)
    }

    @Test
    fun `reads google api error message`() {
        val error = httpError(401, """{"error":{"code":401,"message":"Request had invalid authentication credentials."}}""")
        assertNull(error.oauthError)
        assertEquals("Request had invalid authentication credentials.", error.message)
    }

    @Test
    fun `falls back when body is not json`() {
        val error = httpError(502, "<html>Bad gateway</html>")
        assertNull(error.serverMessage)
        assertEquals("HTTP 502", error.message)
    }

    @Test
    fun `parses protobuf durations`() {
        assertEquals(5L, parseProtoDurationSeconds("5s"))
        assertEquals(1L, parseProtoDurationSeconds("1.5s"))
        assertEquals(899L, parseProtoDurationSeconds("899.123456s"))
        assertNull(parseProtoDurationSeconds(""))
        assertNull(parseProtoDurationSeconds(null))
        assertNull(parseProtoDurationSeconds("-3s"))
    }
}
