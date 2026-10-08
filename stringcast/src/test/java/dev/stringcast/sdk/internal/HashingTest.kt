package dev.stringcast.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashingTest {
    private val body = """{"projectId":"p","version":1,"language":"en","strings":{"a":"b"}}""".toByteArray()

    @Test fun base64MatchesJdk() {
        for (s in listOf("", "f", "fo", "foo", "foob", "fooba", "foobar")) {
            assertEquals(java.util.Base64.getEncoder().encodeToString(s.toByteArray()), Hashing.base64(s.toByteArray()))
        }
    }

    @Test fun verifiesBackendFormat() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        val b64 = "sha256-" + java.util.Base64.getEncoder().encodeToString(digest)
        val hex = "sha256-" + digest.joinToString("") { "%02x".format(it) }
        assertTrue(Hashing.verify(b64, body))
        assertTrue(Hashing.verify(hex, body))
        assertTrue(Hashing.verify(null, body))
        assertFalse(Hashing.verify(b64, body + 0x20))
    }
}
