package bayern.kickner.kshort.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File

/** The only table. Times are Unix milliseconds, `expires_at` is NULL for links that never expire. */
internal object LinkTable : Table("link") {
    val code = varchar("code", ShortCodes.MAX_ALIAS_LENGTH)
    val url = text("url")
    val owner = text("owner")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").nullable()
    val hits = long("hits").default(0)

    override val primaryKey = PrimaryKey(code)

    init {
        index("link_owner", false, owner, createdAt)
        index("link_expires", false, expiresAt)
    }
}

/**
 * SQLite storage of all links, with Exposed (DSL) on top of the `sqlite-jdbc` driver.
 *
 * Every call runs in its own short Exposed transaction on [Dispatchers.IO]. SQLite runs in WAL mode with a busy
 * timeout, so readers never block and concurrent writers wait instead of failing. Every write is a single
 * statement, so no transaction has to upgrade from reading to writing (the case where SQLite would fail
 * immediately instead of waiting).
 */
class LinkRepository private constructor(private val database: Database) : AutoCloseable {

    companion object {
        /**
         * Opens the SQLite file at [path] (created with missing parent directories if needed) and creates the
         * table and its indices if they do not exist yet. The pragmas travel in the JDBC URL, so the driver
         * applies them to every new connection.
         */
        fun open(path: String): LinkRepository {
            val file = File(path).absoluteFile
            file.parentFile?.mkdirs()
            val url = "jdbc:sqlite:${file.path}?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000"
            val database = Database.connect(url = url, driver = "org.sqlite.JDBC")
            transaction(database) { SchemaUtils.create(LinkTable) }
            return LinkRepository(database)
        }
    }

    private suspend fun <T> db(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { transaction(database) { block() } }

    /** Stores [link] with zero hits. False if its code is already taken, no matter by whom. */
    suspend fun insert(link: Link): Boolean = db {
        val statement = LinkTable.insertIgnore {
            it[code] = link.code
            it[url] = link.url
            it[owner] = link.owner
            it[createdAt] = link.createdAt
            it[expiresAt] = link.expiresAt
            it[hits] = 0
        }
        statement.insertedCount == 1
    }

    /**
     * Looks up [code] for a redirect at [now] and counts the hit if the link is valid. The hit is counted first
     * and only for a valid link, so the write lock is taken right away (see the class KDoc).
     */
    suspend fun resolve(code: String, now: Long): Resolution = db {
        val stillValid = LinkTable.expiresAt.isNull() or (LinkTable.expiresAt greater now)
        val counted = LinkTable.update({ (LinkTable.code eq code) and stillValid }) {
            it[hits] = hits + 1
        }
        val row = LinkTable.select(LinkTable.url).where { LinkTable.code eq code }.singleOrNull()
        when {
            row == null -> Resolution.NotFound
            counted == 1 -> Resolution.Found(row[LinkTable.url])
            else -> Resolution.Expired
        }
    }

    /** The link [code] if it belongs to [owner], otherwise null. */
    suspend fun findOwned(code: String, owner: String): Link? = db {
        LinkTable.selectAll()
            .where { (LinkTable.code eq code) and (LinkTable.owner eq owner) }
            .singleOrNull()
            ?.toLink()
    }

    /** The newest [limit] links of [owner], newest first. */
    suspend fun listByOwner(owner: String, limit: Int): List<Link> = db {
        LinkTable.selectAll()
            .where { LinkTable.owner eq owner }
            .orderBy(LinkTable.createdAt to SortOrder.DESC, LinkTable.code to SortOrder.ASC)
            .limit(limit)
            .map { it.toLink() }
    }

    /** Sets a new expiry (null for never). False if [code] does not exist or does not belong to [owner]. */
    suspend fun updateExpiry(code: String, owner: String, expiresAt: Long?): Boolean = db {
        val updated = LinkTable.update({ (LinkTable.code eq code) and (LinkTable.owner eq owner) }) {
            it[LinkTable.expiresAt] = expiresAt
        }
        updated == 1
    }

    /** Deletes [code]. False if it does not exist or does not belong to [owner]. */
    suspend fun delete(code: String, owner: String): Boolean = db {
        LinkTable.deleteWhere { (LinkTable.code eq code) and (LinkTable.owner eq owner) } == 1
    }

    /** Deletes every link that expired at or before [expiredBefore]. Returns how many were deleted. */
    suspend fun purgeExpired(expiredBefore: Long): Int = db {
        LinkTable.deleteWhere { LinkTable.expiresAt.isNotNull() and (LinkTable.expiresAt lessEq expiredBefore) }
    }

    /** Unregisters the database from Exposed. Connections are opened per transaction, none stays open. */
    override fun close() = TransactionManager.closeAndUnregister(database)
}

private fun ResultRow.toLink() = Link(
    code = this[LinkTable.code],
    url = this[LinkTable.url],
    owner = this[LinkTable.owner],
    createdAt = this[LinkTable.createdAt],
    expiresAt = this[LinkTable.expiresAt],
    hits = this[LinkTable.hits],
)
