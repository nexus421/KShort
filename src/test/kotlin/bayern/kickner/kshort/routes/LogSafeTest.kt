package bayern.kickner.kshort.routes

import kotlin.test.Test
import kotlin.test.assertEquals

class LogSafeTest {

    @Test
    fun `control characters cannot start a forged log line`() {
        assertEquals("x?06.10.2026 ERROR/Main: fake?", logSafe("x\n06.10.2026 ERROR/Main: fake\r", 200))
    }

    @Test
    fun `long values are cut`() {
        assertEquals("abc", logSafe("abcdef", 3))
    }
}
