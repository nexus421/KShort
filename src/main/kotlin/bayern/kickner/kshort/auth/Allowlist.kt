package bayern.kickner.kshort.auth

/**
 * Decides who may create links. An entry matches the user's `sub`, `preferred_username` or verified e-mail
 * (case-insensitive). The single entry `"*"` allows every user of the IdP, which only makes sense when the IdP
 * itself already restricts access to this client.
 *
 * @param entries The `allowedUsers` from the config.
 */
class Allowlist(entries: List<String>) {
    private val allowAll = entries.any { it.trim() == "*" }
    private val values = entries.map { it.trim() }.filter { it.isNotEmpty() && it != "*" }.toSet()
    private val emails = values.filter { '@' in it }.map { it.lowercase() }.toSet()

    /** True if [user] matches at least one entry. */
    fun isAllowed(user: OidcUser): Boolean =
        allowAll ||
            user.sub in values ||
            (user.username != null && user.username in values) ||
            (user.email != null && user.email in emails)
}
