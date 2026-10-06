package bayern.kickner.kshort.link

import bayern.kickner.klogger.infoLog
import kotnexlib.ResultOf2
import java.security.SecureRandom

/** Expected reasons why a link could not be created, returned instead of thrown. */
sealed interface CreateError {
    /** The target URL was rejected, [reason] says why. */
    data class InvalidUrl(val reason: UrlError) : CreateError

    /** The custom alias does not match the alias rules. */
    data object InvalidAlias : CreateError

    /** The custom alias exists already (possibly as an expired link within its grace period). */
    data class AliasTaken(val alias: String) : CreateError

    /** Five random codes in a row were taken. Practically impossible, but not an exception either. */
    data object NoFreeCode : CreateError
}

/**
 * Business logic on top of [LinkRepository]: validation, code generation and ownership checks.
 *
 * @param ownHost Host name of this service. Targets on it are rejected because they would loop.
 * @param clock Current time in Unix milliseconds, injectable for tests.
 * @param random Source of randomness for generated codes, injectable for tests.
 */
class LinkService(
    private val repository: LinkRepository,
    private val ownHost: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Creates a link from the raw form input of [owner]. A blank [rawAlias] gets a random code.
     *
     * @return the stored link, or why it could not be created.
     */
    suspend fun create(owner: String, rawUrl: String, ttl: Ttl, rawAlias: String): ResultOf2<Link, CreateError> {
        val url = when (val checked = TargetUrls.validate(rawUrl, ownHost)) {
            is ResultOf2.Success -> checked.value
            is ResultOf2.Failure -> return ResultOf2.Failure(CreateError.InvalidUrl(checked.value))
        }
        val now = clock()
        fun linkWith(code: String) = Link(code, url, owner, now, ttl.expiresAt(now), 0)

        val wantsAlias = rawAlias.isNotBlank()
        if (wantsAlias) {
            val alias = ShortCodes.normalizeAlias(rawAlias) ?: return ResultOf2.Failure(CreateError.InvalidAlias)
            val link = linkWith(alias)
            if (repository.insert(link).not()) return ResultOf2.Failure(CreateError.AliasTaken(alias))
            infoLog { "Link '$alias' created by $owner" }
            return ResultOf2.Success(link)
        }

        repeat(5) {
            val link = linkWith(ShortCodes.generate(random))
            if (repository.insert(link)) {
                infoLog { "Link '${link.code}' created by $owner" }
                return ResultOf2.Success(link)
            }
        }
        return ResultOf2.Failure(CreateError.NoFreeCode)
    }

    /** The redirect target of [code] right now. Counts the hit. */
    suspend fun resolve(code: String): Resolution =
        if (ShortCodes.isValidPathCode(code)) repository.resolve(code, clock()) else Resolution.NotFound

    /** Sets the lifetime of [code] to [ttl], counted from now. False if it is not a link of [owner]. */
    suspend fun extend(code: String, owner: String, ttl: Ttl): Boolean =
        ShortCodes.isValidPathCode(code) && repository.updateExpiry(code, owner, ttl.expiresAt(clock()))

    /** Deletes [code]. False if it is not a link of [owner]. */
    suspend fun delete(code: String, owner: String): Boolean {
        val deleted = ShortCodes.isValidPathCode(code) && repository.delete(code, owner)
        if (deleted) infoLog { "Link '$code' deleted by $owner" }
        return deleted
    }

    /** The link [code] of [owner], or null. */
    suspend fun findOwned(code: String, owner: String): Link? =
        if (ShortCodes.isValidPathCode(code)) repository.findOwned(code, owner) else null

    /** The newest [limit] links of [owner]. */
    suspend fun list(owner: String, limit: Int): List<Link> = repository.listByOwner(owner, limit)

    /** Deletes links whose expiry lies more than [graceMillis] in the past. Returns how many were deleted. */
    suspend fun purgeExpired(graceMillis: Long): Int = repository.purgeExpired(clock() - graceMillis)

    /** Current time of this service's clock, so callers compare against the same time source. */
    fun now(): Long = clock()
}
