package com.astratv.player.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHasher {
    fun create(pin: String): String {
        require(pin.matches(Regex("[0-9]{4,8}"))) { "Use a PIN of 4 to 8 digits." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(pin,salt))
    }
    fun verify(pin: String, stored: String): Boolean = runCatching {
        val parts = stored.split(':'); val salt = Base64.getDecoder().decode(parts[0])
        MessageDigest.isEqual(derive(pin,salt),Base64.getDecoder().decode(parts[1]))
    }.getOrDefault(false)
    private fun derive(pin: String,salt: ByteArray): ByteArray {
        val spec=PBEKeySpec(pin.toCharArray(),salt,120000,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
