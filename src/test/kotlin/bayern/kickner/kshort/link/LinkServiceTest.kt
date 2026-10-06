package bayern.kickner.kshort.link

import bayern.kickner.kshort.TempDatabase
import bayern.kickner.kshort.TestClock
import kotlinx.coroutines.runBlocking
import kotnexlib.ResultOf2
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkServiceTest {
    private val database = TempDatabase()
    private val clock = TestClock()
    private val service = LinkService(database.repository, ownHost = "s.example.de", clock = clock)

    @AfterTest
    fun close() = database.close()

    @Test
    fun `a link without alias gets a random code`() = runBlocking<Unit> {
        val link = assertIs<ResultOf2.Success<Link>>(service.create("alice", "https://example.com", Ttl.D7, "")).value

        assertEquals(ShortCodes.GENERATED_LENGTH, link.code.length)
        assertEquals(Resolution.Found("https://example.com"), service.resolve(link.code))
    }

    @Test
    fun `alias rules and duplicates are reported as errors`() = runBlocking<Unit> {
        assertIs<ResultOf2.Success<Link>>(service.create("alice", "https://example.com", Ttl.D7, "Mein-Link"))

        assertEquals(ResultOf2.Failure(CreateError.AliasTaken("mein-link")), service.create("bob", "https://example.org", Ttl.D7, "mein-link"))
        assertEquals(ResultOf2.Failure(CreateError.InvalidAlias), service.create("alice", "https://example.org", Ttl.D7, "a_b"))
        assertEquals(ResultOf2.Failure(CreateError.InvalidUrl(UrlError.SELF)), service.create("alice", "https://s.example.de/x", Ttl.D7, ""))
    }

    @Test
    fun `links expire and can be revived by extending`() = runBlocking<Unit> {
        val link = assertIs<ResultOf2.Success<Link>>(service.create("alice", "https://example.com", Ttl.D7, "")).value

        clock.advanceDays(7)
        assertEquals(Resolution.Expired, service.resolve(link.code))

        assertTrue(service.extend(link.code, "alice", Ttl.D30))
        assertEquals(Resolution.Found("https://example.com"), service.resolve(link.code))
    }

    @Test
    fun `only the owner may extend or delete`() = runBlocking<Unit> {
        val link = assertIs<ResultOf2.Success<Link>>(service.create("alice", "https://example.com", Ttl.D7, "")).value

        assertFalse(service.extend(link.code, "bob", Ttl.FOREVER))
        assertFalse(service.delete(link.code, "bob"))
        assertNull(service.findOwned(link.code, "bob"))
        assertTrue(service.delete(link.code, "alice"))
        assertEquals(Resolution.NotFound, service.resolve(link.code))
    }

    @Test
    fun `invalid path codes never reach the database`() = runBlocking<Unit> {
        assertEquals(Resolution.NotFound, service.resolve("favicon.ico"))
        assertFalse(service.delete("../x", "alice"))
    }

    @Test
    fun `purge removes links only after the grace period`() = runBlocking<Unit> {
        val link = assertIs<ResultOf2.Success<Link>>(service.create("alice", "https://example.com", Ttl.D7, "")).value
        val grace = 30L * 24 * 60 * 60 * 1000

        clock.advanceDays(8)
        assertEquals(0, service.purgeExpired(grace))
        clock.advanceDays(30)
        assertEquals(1, service.purgeExpired(grace))
        assertEquals(Resolution.NotFound, service.resolve(link.code))
    }
}
