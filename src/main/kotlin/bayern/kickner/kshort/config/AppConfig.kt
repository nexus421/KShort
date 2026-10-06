package bayern.kickner.kshort.config

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Root configuration, loaded once at startup from the JSON config file (see [loadConfig]).
 *
 * @property listenHost Interface the HTTP server binds to. The default keeps KShort reachable only from the local
 * machine, where the TLS-terminating reverse proxy is expected to run.
 * @property listenPort Port the HTTP server listens on.
 * @property publicUrl Public base URL without path, e.g. `https://s.example.de`. Short links and the OIDC
 * callback are built from it.
 * @property databasePath SQLite file. Parent directories are created.
 * @property allowedUsers Who may create links: `sub`, `preferred_username` or verified e-mail. `"*"` allows
 * every user of the IdP.
 * @property oidc The OIDC client registered at the IdP.
 * @property session Keys for the encrypted session cookies.
 */
@Serializable
data class AppConfig(
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 8080,
    val publicUrl: String,
    val databasePath: String = "data/kshort.db",
    val allowedUsers: List<String>,
    val oidc: OidcConfig,
    val session: SessionConfig,
) {
    /** [publicUrl] without trailing slash, the base for every absolute URL KShort builds. */
    val baseUrl: String get() = publicUrl.trim().trimEnd('/')

    /** Host part of [publicUrl]. Targets on this host are rejected because they would loop. */
    val publicHost: String get() = URI(baseUrl).host

    /** True if cookies get the `Secure` flag, i.e. the public URL is https. */
    val secureCookies: Boolean get() = baseUrl.startsWith("https://")
}

/**
 * The OIDC client (confidential, Authorization Code Flow with PKCE) registered at the IdP.
 *
 * @property issuer Exactly as the IdP reports it in its discovery document, including a trailing slash if any.
 * @property clientId Client id from the IdP.
 * @property clientSecret Client secret from the IdP. Never logged.
 * @property scopes Requested scopes, must contain `openid`.
 */
@Serializable
data class OidcConfig(
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    val scopes: String = "openid profile email",
) {
    override fun toString() = "OidcConfig(issuer='$issuer', clientId='$clientId', scopes='$scopes')"
}

/**
 * Keys for the session cookies. `java -jar kshort.jar keys` prints a fresh pair.
 *
 * @property encryptKeyHex AES key, 16 bytes as 32 hex characters.
 * @property signKeyHex HMAC-SHA256 key, at least 32 bytes as 64 hex characters.
 */
@Serializable
data class SessionConfig(
    val encryptKeyHex: String,
    val signKeyHex: String,
) {
    /** [encryptKeyHex] decoded. Only call after [loadConfig] validated it. */
    val encryptKey: ByteArray get() = encryptKeyHex.hexToByteArray()

    /** [signKeyHex] decoded. Only call after [loadConfig] validated it. */
    val signKey: ByteArray get() = signKeyHex.hexToByteArray()

    override fun toString() = "SessionConfig(keys hidden)"
}
