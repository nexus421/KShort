package bayern.kickner.kshort.config

import bayern.kickner.kshort.cli.generateSessionKeys
import kotnexlib.ResultOf2
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigLoaderTest {

    private fun config(
        publicUrl: String = "https://s.example.de/",
        allowedUsers: String = """["max"]""",
        issuer: String = "https://auth.example.de/application/o/kshort/",
        scopes: String = "openid profile email",
        session: String = generateSessionKeys(),
        extra: String = "",
    ) = """
        {
          "publicUrl": "$publicUrl",
          "allowedUsers": $allowedUsers,
          "oidc": { "issuer": "$issuer", "clientId": "kshort", "clientSecret": "very-secret-value", "scopes": "$scopes" },
          $session
          $extra
        }
        """.trimIndent()

    private fun load(json: String): ResultOf2<AppConfig, String> {
        val file = File.createTempFile("kshort-config", ".json").apply { deleteOnExit() }
        file.writeText(json)
        return loadConfig(file.absolutePath)
    }

    @Test
    fun `loads a complete config with defaults`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(config())).value

        assertEquals("127.0.0.1", config.listenHost)
        assertEquals(8080, config.listenPort)
        assertEquals("data/kshort.db", config.databasePath)
        assertEquals("https://s.example.de", config.baseUrl)
        assertEquals("s.example.de", config.publicHost)
        assertTrue(config.secureCookies)
        assertEquals(16, config.session.encryptKey.size)
        assertEquals(32, config.session.signKey.size)
    }

    @Test
    fun `missing file is reported`() {
        val result = loadConfig("/nonexistent/kshort-config.json")

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "/nonexistent/kshort-config.json")
    }

    @Test
    fun `unknown keys are errors`() {
        val result = load(config(extra = """, "listenPrt": 9000"""))

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "listenPrt")
    }

    @Test
    fun `all validation problems are reported at once`() {
        val message = assertIs<ResultOf2.Failure<String>>(
            load(
                config(
                    publicUrl = "https://s.example.de/path",
                    allowedUsers = "[]",
                    issuer = "http://auth.example.de",
                    scopes = "profile",
                    session = """"session": { "encryptKeyHex": "abc", "signKeyHex": "00" }""",
                )
            )
        ).value

        assertContains(message, "publicUrl")
        assertContains(message, "allowedUsers")
        assertContains(message, "oidc.issuer")
        assertContains(message, "oidc.scopes")
        assertContains(message, "session.encryptKeyHex")
        assertContains(message, "session.signKeyHex")
    }

    @Test
    fun `secrets never appear in error messages`() {
        val message = assertIs<ResultOf2.Failure<String>>(load(config(allowedUsers = "[]"))).value
        val broken = assertIs<ResultOf2.Failure<String>>(load(config(extra = """, "oops": """))).value

        assertFalse("very-secret-value" in message)
        assertFalse("very-secret-value" in broken)
    }

    @Test
    fun `secrets are not part of toString`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(config())).value

        assertFalse("very-secret-value" in config.toString())
        assertFalse(config.session.encryptKeyHex in config.toString())
    }
}
