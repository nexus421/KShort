package bayern.kickner.kshort.cli

import bayern.kickner.kshort.config.SessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CommandsTest {

    @Test
    fun `generated session keys are valid and fresh`() {
        val first = Json.decodeFromString<SessionConfig>(Json.parseToJsonElement("{${generateSessionKeys()}}").jsonObject["session"].toString())
        val second = Json.decodeFromString<SessionConfig>(Json.parseToJsonElement("{${generateSessionKeys()}}").jsonObject["session"].toString())

        assertEquals(16, first.encryptKey.size)
        assertEquals(32, first.signKey.size)
        assertNotEquals(first.encryptKeyHex, second.encryptKeyHex)
    }
}
