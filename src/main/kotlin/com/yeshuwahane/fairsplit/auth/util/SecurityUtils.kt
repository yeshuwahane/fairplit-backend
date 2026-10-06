package com.yeshuwahane.fairsplit.auth.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object SecurityUtils {
    private val secureRandom = SecureRandom()

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateSecureRandomToken(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
