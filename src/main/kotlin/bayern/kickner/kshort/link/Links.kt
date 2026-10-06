package bayern.kickner.kshort.link

import kotnexlib.ResultOf2
import java.net.URI
import java.security.SecureRandom

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * A stored short link.
 *
 * @property code Path segment of the short URL, either generated or a custom alias.
 * @property url Validated target URL, pure ASCII.
 * @property owner OIDC `sub` of the user who created the link.
 * @property createdAt Unix timestamp in milliseconds.
 * @property expiresAt Unix timestamp in milliseconds, null for a link that never expires.
 * @property hits Number of redirects served.
 */
data class Link(
    val code: String,
    val url: String,
    val owner: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val hits: Long,
) {
    /** True once [expiresAt] is reached. A link without expiry never expires. */
    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now
}

/** Outcome of looking up a short code for a redirect. */
sealed interface Resolution {
    /** The link exists and is valid, redirect to [url]. */
    data class Found(val url: String) : Resolution

    /** The link exists but has expired (kept for a grace period, answered with 410). */
    data object Expired : Resolution

    /** No such link (never existed or already purged). */
    data object NotFound : Resolution
}

/**
 * Lifetimes a user can choose. Only the computed expiry is stored, never the enum name, so this list can change
 * without a migration.
 *
 * @property label German label shown in the dropdown.
 * @property days Lifetime in days, null for unlimited.
 */
enum class Ttl(val label: String, val days: Long?) {
    D7("7 Tage", 7),
    D14("14 Tage", 14),
    D30("30 Tage", 30),
    D365("365 Tage", 365),
    FOREVER("Unbegrenzt", null);

    /** Expiry for a link created or extended at [now] (Unix milliseconds), null for unlimited. */
    fun expiresAt(now: Long): Long? = days?.let { now + it * DAY_MILLIS }

    companion object {
        /** Preselected in every dropdown. */
        val DEFAULT = D30

        /** The [Ttl] with exactly this enum name, or null. */
        fun parse(value: String?): Ttl? = entries.firstOrNull { it.name == value }
    }
}

/** Generation and validation of the path segment behind the domain. */
object ShortCodes {
    /** Base62 without characters that are easily confused (0 and O, 1 and l and I). */
    private const val ALPHABET = "23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"

    /** 57 characters to the power of 7 are about 1.9 trillion codes, collisions are practically impossible. */
    const val GENERATED_LENGTH = 7

    /** Maximum length of a custom alias. */
    const val MAX_ALIAS_LENGTH = 40

    /** Anything that can be a short code at all, generated or alias. Everything else is a 404 without a lookup. */
    private val PATH_CODE = Regex("^[A-Za-z0-9-]{3,40}$")

    /** Custom aliases: lower case letters, digits and hyphens, 3 to 40 characters, no hyphen at either end. */
    private val ALIAS = Regex("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$")

    /**
     * A fresh random code of [GENERATED_LENGTH] characters.
     *
     * @param random Source of randomness, injectable for tests.
     */
    fun generate(random: SecureRandom = SecureRandom()): String = buildString(GENERATED_LENGTH) {
        repeat(GENERATED_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** True if [value] has the shape of a short code. Cheap pre-check before any database access. */
    fun isValidPathCode(value: String): Boolean = PATH_CODE.matches(value)

    /** Trims and lower-cases [input]. Null if the result is not a valid alias. */
    fun normalizeAlias(input: String): String? = input.trim().lowercase().takeIf { ALIAS.matches(it) }
}

/**
 * Why a target URL was rejected. The message is German because it is shown to the user.
 *
 * @property message Text for the error box on the page.
 */
enum class UrlError(val message: String) {
    EMPTY("Bitte eine URL eingeben."),
    TOO_LONG("Die URL ist zu lang (maximal ${TargetUrls.MAX_LENGTH} Zeichen)."),
    WHITESPACE("Die URL enthält Leer- oder Steuerzeichen."),
    SYNTAX("Das ist keine gültige URL."),
    SCHEME("Nur http:// und https:// sind erlaubt."),
    USERINFO("URLs mit Benutzername oder Passwort (user@host) sind nicht erlaubt."),
    HOST("Die URL enthält keinen gültigen Hostnamen (Umlaut-Domains bitte in Punycode-Form)."),
    SELF("Links auf diesen Dienst selbst sind nicht erlaubt.")
}

/** Validation of target URLs. */
object TargetUrls {
    /** Upper bound for a target URL, generous for real links and small enough for every browser. */
    const val MAX_LENGTH = 2048

    /**
     * Validates [input] as redirect target.
     *
     * Only http and https with a real host are allowed, no user info (`https://google.com@evil.example` is a
     * classic phishing trick) and no link to this service itself ([ownHost]), which would loop. The result is pure
     * ASCII (non-ASCII characters in path and query get percent-encoded), so it is safe for the `Location` header.
     *
     * @return the normalized URL, or the reason it was rejected.
     */
    fun validate(input: String, ownHost: String): ResultOf2<String, UrlError> {
        val raw = input.trim()
        if (raw.isEmpty()) return ResultOf2.Failure(UrlError.EMPTY)
        if (raw.length > MAX_LENGTH) return ResultOf2.Failure(UrlError.TOO_LONG)
        val hasControlChars = raw.any { it.code < 0x21 || it.code == 0x7f }
        if (hasControlChars) return ResultOf2.Failure(UrlError.WHITESPACE)

        val uri = runCatching { URI(raw) }.getOrElse { return ResultOf2.Failure(UrlError.SYNTAX) }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return ResultOf2.Failure(UrlError.SCHEME)
        if (uri.rawUserInfo != null) return ResultOf2.Failure(UrlError.USERINFO)
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return ResultOf2.Failure(UrlError.HOST)
        if (host == ownHost.lowercase()) return ResultOf2.Failure(UrlError.SELF)

        val ascii = uri.toASCIIString()
        if (ascii.length > MAX_LENGTH) return ResultOf2.Failure(UrlError.TOO_LONG)
        return ResultOf2.Success(ascii)
    }
}
