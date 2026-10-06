package bayern.kickner.kshort.link

import bayern.kickner.kshort.TempDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkRepositoryTest {
    private val database = TempDatabase()
    private val repository = database.repository
    private val now = 1_800_000_000_000L

    @AfterTest
    fun close() = database.close()

    private fun link(code: String, owner: String = "alice", expiresAt: Long? = now + 100) =
        Link(code, "https://example.com/$code", owner, now, expiresAt, 0)

    @Test
    fun `a taken code cannot be inserted again`() = runBlocking<Unit> {
        assertTrue(repository.insert(link("abc")))
        assertFalse(repository.insert(link("abc", owner = "bob")))
        assertEquals("alice", repository.findOwned("abc", "alice")?.owner)
        assertNull(repository.findOwned("abc", "bob"))
    }

    @Test
    fun `resolve counts hits and respects the expiry`() = runBlocking<Unit> {
        repository.insert(link("abc"))
        repository.insert(link("forever", expiresAt = null))

        assertEquals(Resolution.Found("https://example.com/abc"), repository.resolve("abc", now))
        assertEquals(Resolution.Found("https://example.com/abc"), repository.resolve("abc", now + 99))
        assertEquals(Resolution.Expired, repository.resolve("abc", now + 100))
        assertEquals(Resolution.Found("https://example.com/forever"), repository.resolve("forever", Long.MAX_VALUE))
        assertEquals(Resolution.NotFound, repository.resolve("nope", now))
        assertEquals(2L, repository.findOwned("abc", "alice")?.hits)
    }

    @Test
    fun `only the owner changes or deletes`() = runBlocking<Unit> {
        repository.insert(link("abc"))

        assertFalse(repository.updateExpiry("abc", "bob", null))
        assertFalse(repository.delete("abc", "bob"))
        assertTrue(repository.updateExpiry("abc", "alice", null))
        assertNull(repository.findOwned("abc", "alice")?.expiresAt)
        assertTrue(repository.delete("abc", "alice"))
        assertEquals(Resolution.NotFound, repository.resolve("abc", now))
    }

    @Test
    fun `list is per owner, newest first, and purge only hits expired links`() = runBlocking<Unit> {
        repository.insert(link("a1", expiresAt = now - 10))
        repository.insert(link("a2", expiresAt = null).copy(createdAt = now + 1))
        repository.insert(link("b1", owner = "bob"))

        assertEquals(listOf("a2", "a1"), repository.listByOwner("alice", 100).map { it.code })
        assertEquals(listOf("a2"), repository.listByOwner("alice", 1).map { it.code })
        assertEquals(1, repository.purgeExpired(now))
        assertEquals(listOf("a2"), repository.listByOwner("alice", 100).map { it.code })
    }

    @Test
    fun `data survives reopening the file`() = runBlocking<Unit> {
        repository.insert(link("keep"))

        val reopened = LinkRepository.open(database.path)
        assertEquals("keep", reopened.findOwned("keep", "alice")?.code)
        reopened.close()
    }

    @Test
    fun `concurrent redirects count every hit`() = runBlocking<Unit> {
        repository.insert(link("busy", expiresAt = null))

        (1..200).map { async(Dispatchers.IO) { repository.resolve("busy", now) } }.awaitAll()

        assertEquals(200L, repository.findOwned("busy", "alice")?.hits)
    }
}
