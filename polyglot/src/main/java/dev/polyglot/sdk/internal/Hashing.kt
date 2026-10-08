package dev.polyglot.sdk.internal

import java.security.MessageDigest

internal object Hashing {
    /**
     * Verifies a manifest hash (`sha256-<base64>`, as produced by the backend; hex is accepted
     * too) against the raw downloaded bytes. A missing/unknown hash format is treated as valid.
     */
    fun verify(expected: String?, bytes: ByteArray): Boolean {
        if (expected.isNullOrBlank()) return true
        if (!expected.startsWith("sha256-", ignoreCase = true)) return true
        val want = expected.substring(7)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (want.equals(hex(digest), ignoreCase = true)) return true
        return want.trimEnd('=') == base64(digest).trimEnd('=')
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // java.util.Base64 is API 26+, android.util.Base64 isn't available on the JVM: tiny encoder.
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            sb.append(if (b1 < 0) '=' else ALPHABET[((b1 and 0xf) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            sb.append(if (b2 < 0) '=' else ALPHABET[b2 and 0x3f])
            i += 3
        }
        return sb.toString()
    }
}
