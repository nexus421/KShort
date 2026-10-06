package bayern.kickner.kshort.link

import kotnexlib.ResultOf2
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun ok(input: String) = (TargetUrls.validate(input, "s.example.de") as? ResultOf2.Success)?.value
    private fun error(input: String) = (TargetUrls.validate(input, "s.example.de") as? ResultOf2.Failure)?.value

    @Test
    fun `ttl computes the expiry from now`() {
        assertEquals(now + 7 * day, Ttl.D7.expiresAt(now))
        assertEquals(now + 365 * day, Ttl.D365.expiresAt(now))
        assertNull(Ttl.FOREVER.expiresAt(now))
    }

    @Test
    fun `ttl parses only exact enum names`() {
        assertEquals(Ttl.D14, Ttl.parse("D14"))
        assertNull(Ttl.parse("d14"))
        assertNull(Ttl.parse(null))
        assertNull(Ttl.parse("D999"))
    }

    @Test
    fun `generated codes have the right shape`() {
        val random = SecureRandom()
        repeat(1000) {
            val code = ShortCodes.generate(random)
            assertEquals(ShortCodes.GENERATED_LENGTH, code.length)
            assertTrue(ShortCodes.isValidPathCode(code))
            assertTrue(code.none { it in "0O1lI" }, code)
        }
    }

    @Test
    fun `aliases are normalized and checked`() {
        assertEquals("urlaub-2026", ShortCodes.normalizeAlias("  Urlaub-2026 "))
        assertEquals("abc", ShortCodes.normalizeAlias("abc"))
        assertEquals("a".repeat(40), ShortCodes.normalizeAlias("a".repeat(40)))
        assertNull(ShortCodes.normalizeAlias("ab"))
        assertNull(ShortCodes.normalizeAlias("-abc"))
        assertNull(ShortCodes.normalizeAlias("abc-"))
        assertNull(ShortCodes.normalizeAlias("a_b"))
        assertNull(ShortCodes.normalizeAlias("äbc"))
        assertNull(ShortCodes.normalizeAlias("a".repeat(41)))
    }

    @Test
    fun `path codes reject everything that cannot be a link`() {
        assertTrue(ShortCodes.isValidPathCode("Ab3xY9z"))
        assertTrue(ShortCodes.isValidPathCode("mein-link"))
        assertFalse(ShortCodes.isValidPathCode("favicon.ico"))
        assertFalse(ShortCodes.isValidPathCode("a b"))
        assertFalse(ShortCodes.isValidPathCode("ab"))
        assertFalse(ShortCodes.isValidPathCode(""))
    }

    @Test
    fun `valid target urls are accepted and normalized to ascii`() {
        assertEquals("https://example.com/a?b=c#d", ok("  https://example.com/a?b=c#d "))
        assertEquals("http://example.com", ok("http://example.com"))
        assertEquals("HTTPS://Example.com/x", ok("HTTPS://Example.com/x"))
        assertEquals("https://de.wikipedia.org/wiki/Stra%C3%9Fe", ok("https://de.wikipedia.org/wiki/Straße"))
    }

    @Test
    fun `dangerous or broken target urls are rejected`() {
        assertEquals(UrlError.EMPTY, error("   "))
        assertEquals(UrlError.SCHEME, error("javascript:alert(1)"))
        assertEquals(UrlError.SCHEME, error("data:text/html,hi"))
        assertEquals(UrlError.SCHEME, error("ftp://example.com"))
        assertEquals(UrlError.SCHEME, error("example.com"))
        assertEquals(UrlError.WHITESPACE, error("https://example.com/a b"))
        assertEquals(UrlError.WHITESPACE, error("https://example.com/\r\nSet-Cookie:x"))
        assertEquals(UrlError.USERINFO, error("https://google.com@evil.example"))
        assertEquals(UrlError.HOST, error("https:///path"))
        assertEquals(UrlError.SELF, error("https://S.Example.de/abc"))
        assertEquals(UrlError.TOO_LONG, error("https://example.com/" + "a".repeat(2100)))
        assertEquals(UrlError.SYNTAX, error("https://exa<mple.com"))
    }

    @Test
    fun `a link without expiry never expires`() {
        val link = Link("abc", "https://example.com", "alice", now, null, 0)
        assertFalse(link.isExpired(Long.MAX_VALUE))
        assertTrue(link.copy(expiresAt = now).isExpired(now))
        assertFalse(link.copy(expiresAt = now + 1).isExpired(now))
    }
}
