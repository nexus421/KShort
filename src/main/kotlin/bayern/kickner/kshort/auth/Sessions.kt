package bayern.kickner.kshort.auth

import bayern.kickner.kshort.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable

/** A login is valid for seven days, then the user logs in again (one click while the IdP session lives). */
const val SESSION_MILLIS = 7L * 24 * 60 * 60 * 1000

/** Time the user has at the IdP between leaving and coming back to the callback. */
const val LOGIN_STATE_MILLIS = 10L * 60 * 1000

/**
 * Session of a logged in user, AES-encrypted and HMAC-signed in a cookie. Nothing is stored on the server, so a
 * restart keeps everybody logged in.
 *
 * @property sub Owner id of all links of this user.
 * @property name Display name for the page header.
 * @property csrf Per-session token every form must send back.
 * @property expiresAt Unix milliseconds. Checked on the server, the cookie's own max age is only a hint.
 */
@Serializable
data class UserSession(
    val sub: String,
    val username: String? = null,
    val email: String? = null,
    val name: String,
    val csrf: String,
    val expiresAt: Long,
) {
    /** The session as [OidcUser], for re-checking the allowlist on every request. */
    fun toOidcUser() = OidcUser(sub, username, email, name)
}

/**
 * Short-lived state of one login attempt between `/_/login` and `/_/callback`.
 *
 * @property verifier PKCE code verifier, the IdP only ever sees its hash.
 * @property createdAt Unix milliseconds, the state is rejected after [LOGIN_STATE_MILLIS].
 */
@Serializable
data class LoginState(
    val state: String,
    val nonce: String,
    val verifier: String,
    val createdAt: Long,
)

/** Installs both session cookies: HttpOnly, SameSite=Lax, Secure whenever the public URL is https. */
fun Application.installSessions(config: AppConfig) {
    install(Sessions) {
        cookie<UserSession>("kshort_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = config.secureCookies
            cookie.maxAgeInSeconds = SESSION_MILLIS / 1000
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerEncrypt(config.session.encryptKey, config.session.signKey))
            // Ktor's encrypt transformer MACs only the ciphertext, not the IV, so the first AES-CBC block could be
            // altered unnoticed. This outer HMAC covers the whole value including the IV (checked first on read)
            transform(SessionTransportTransformerMessageAuthentication(config.session.signKey))
        }
        cookie<LoginState>("kshort_login") {
            cookie.path = "/_/"
            cookie.httpOnly = true
            cookie.secure = config.secureCookies
            cookie.maxAgeInSeconds = LOGIN_STATE_MILLIS / 1000
            // Lax is required: the IdP sends the browser back with a top-level GET from another site
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerEncrypt(config.session.encryptKey, config.session.signKey))
            transform(SessionTransportTransformerMessageAuthentication(config.session.signKey))
        }
    }
}
