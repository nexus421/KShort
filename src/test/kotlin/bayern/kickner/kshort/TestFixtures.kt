package bayern.kickner.kshort

import bayern.kickner.kshort.config.AppConfig
import bayern.kickner.kshort.config.OidcConfig
import bayern.kickner.kshort.config.SessionConfig
import bayern.kickner.kshort.link.LinkRepository
import java.io.File
import kotlin.io.path.createTempDirectory

internal const val TEST_ISSUER = "https://idp.test"
internal const val TEST_CLIENT_ID = "kshort-test"
internal const val TEST_CLIENT_SECRET = "client-secret-for-tests"

/** 05.10.2026 12:00:00 UTC, a fixed start for every test clock. */
internal const val TEST_START = 1_791_201_600_000L

/** A clock tests can move forward. Usable wherever a `() -> Long` clock is expected. */
internal class TestClock(var now: Long = TEST_START) : () -> Long {
    override fun invoke(): Long = now

    /** Moves the clock forward by [days] days. */
    fun advanceDays(days: Long) {
        now += days * 24 * 60 * 60 * 1000
    }
}

/**
 * A fresh SQLite database in its own temporary directory. An in-memory database does not work with Exposed,
 * because every transaction opens its own connection and would see an empty database. [close] removes the
 * directory including the WAL files.
 */
internal class TempDatabase : AutoCloseable {
    private val directory: File = createTempDirectory("kshort-test").toFile()

    /** Path of the database file inside the temporary directory. */
    val path: String = File(directory, "kshort.db").path

    /** A repository on this database. */
    val repository: LinkRepository = LinkRepository.open(path)

    override fun close() {
        repository.close()
        directory.deleteRecursively()
    }
}

internal fun testConfig(
    allowedUsers: List<String> = listOf("alice"),
    publicUrl: String = "http://localhost",
) = AppConfig(
    publicUrl = publicUrl,
    allowedUsers = allowedUsers,
    oidc = OidcConfig(TEST_ISSUER, TEST_CLIENT_ID, TEST_CLIENT_SECRET),
    session = SessionConfig(encryptKeyHex = "00112233445566778899aabbccddeeff", signKeyHex = "ab".repeat(32)),
)
