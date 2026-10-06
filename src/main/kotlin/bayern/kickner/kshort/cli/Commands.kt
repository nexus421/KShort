package bayern.kickner.kshort.cli

import java.security.SecureRandom

/** AES-128 for the cookie encryption. */
private const val ENCRYPT_KEY_BYTES = 16

/** HMAC-SHA256 key with the full 256 bits. */
private const val SIGN_KEY_BYTES = 32

/**
 * A fresh `session` block for config.json with random keys, ready to paste.
 *
 * @param random Source of randomness, injectable for tests.
 */
fun generateSessionKeys(random: SecureRandom = SecureRandom()): String {
    fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).toHexString()
    return """
        |"session": {
        |  "encryptKeyHex": "${hex(ENCRYPT_KEY_BYTES)}",
        |  "signKeyHex": "${hex(SIGN_KEY_BYTES)}"
        |}
        """.trimMargin()
}
