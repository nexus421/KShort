package bayern.kickner.kshort.routes

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.kshort.auth.Allowlist
import bayern.kickner.kshort.auth.LOGIN_STATE_MILLIS
import bayern.kickner.kshort.auth.LoginState
import bayern.kickner.kshort.auth.OidcClient
import bayern.kickner.kshort.auth.Pkce
import bayern.kickner.kshort.auth.SESSION_MILLIS
import bayern.kickner.kshort.auth.SessionGuard
import bayern.kickner.kshort.auth.UserSession
import bayern.kickner.kshort.auth.constantTimeEquals
import bayern.kickner.kshort.auth.randomToken
import bayern.kickner.kshort.auth.seeOther
import bayern.kickner.kshort.web.loginPage
import bayern.kickner.kshort.web.messagePage
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.html.HTML
import kotnexlib.ResultOf2

private const val TAG = "AuthRoutes"

/**
 * The OIDC login (Authorization Code Flow with PKCE, `state` and `nonce`) and the logout.
 *
 * - `GET /_/login`: stores a fresh [LoginState] in a short-lived cookie and sends the browser to the IdP.
 * - `GET /_/callback`: checks `state`, exchanges the code, validates the ID token, checks the allowlist and
 *   creates the [UserSession].
 * - `POST /_/logout`: ends the local session. The IdP session stays, so the next login is one click.
 *
 * @param clock Current time in Unix milliseconds, injectable for tests.
 */
fun Route.authRoutes(oidc: OidcClient, allowlist: Allowlist, guard: SessionGuard, clock: () -> Long) {
    get("/_/login") {
        if (guard.currentUser(call) != null) return@get call.respondRedirect("/")

        val state = randomToken()
        val nonce = randomToken()
        val verifier = Pkce.verifier()
        when (val url = oidc.authorizationUrl(state, nonce, Pkce.challenge(verifier))) {
            is ResultOf2.Failure -> {
                staticLog(KLogger.Level.ERROR, TAG) { "Login impossible, OIDC discovery failed: ${url.value}" }
                call.respondHtml(HttpStatusCode.BadGateway) {
                    messagePage("Anmeldung nicht möglich", "Der Anmeldedienst ist gerade nicht erreichbar. Bitte später erneut versuchen.")
                }
            }

            is ResultOf2.Success -> {
                call.sessions.set(LoginState(state, nonce, verifier, clock()))
                call.respondRedirect(url.value)
            }
        }
    }

    get("/_/callback") {
        val query = call.request.queryParameters
        val login = call.sessions.get<LoginState>()
        call.sessions.clear<LoginState>()

        val idpError = query["error"]
        if (idpError != null) {
            staticLog(KLogger.Level.WARN, TAG) {
                "IdP reported '${logSafe(idpError, 100)}': ${logSafe(query["error_description"].orEmpty(), 200)}"
            }
            return@get call.respondHtml(HttpStatusCode.Unauthorized) { loginPage("Anmeldung abgebrochen oder abgelehnt.") }
        }

        val code = query["code"]
        val state = query["state"]
        if (login == null || code == null || state == null) return@get call.respondHtml(HttpStatusCode.BadRequest) { loginExpiredPage() }
        val stateValid = constantTimeEquals(state, login.state) && clock() - login.createdAt <= LOGIN_STATE_MILLIS
        if (stateValid.not()) return@get call.respondHtml(HttpStatusCode.BadRequest) { loginExpiredPage() }

        val user = when (val result = oidc.finishLogin(code, login.verifier, login.nonce, clock())) {
            is ResultOf2.Success -> result.value
            is ResultOf2.Failure -> {
                staticLog(KLogger.Level.WARN, TAG) { "OIDC login failed: ${result.value}" }
                return@get call.respondHtml(HttpStatusCode.BadGateway) {
                    messagePage("Anmeldung fehlgeschlagen", "Die Anmeldung konnte nicht abgeschlossen werden.", "Erneut anmelden", "/_/login")
                }
            }
        }

        if (allowlist.isAllowed(user).not()) {
            staticLog(KLogger.Level.WARN, TAG) { "Login refused, not on allowedUsers: sub=${user.sub} username=${user.username} email=${user.email}" }
            return@get call.respondHtml(HttpStatusCode.Forbidden) {
                messagePage("Kein Zugriff", "Dein Konto ist für diesen Dienst nicht freigeschaltet.")
            }
        }

        call.sessions.set(
            UserSession(
                sub = user.sub,
                username = user.username,
                email = user.email,
                name = user.displayName,
                csrf = randomToken(),
                expiresAt = clock() + SESSION_MILLIS,
            )
        )
        staticLog(KLogger.Level.INFO, TAG) { "Login of sub=${user.sub} username=${user.username}" }
        call.respondRedirect("/")
    }

    post("/_/logout") {
        guard.authorizedForm(call) ?: return@post
        call.sessions.clear<UserSession>()
        call.seeOther("/")
    }
}

private fun HTML.loginExpiredPage() =
    messagePage("Anmeldung abgelaufen", "Der Anmeldevorgang ist ungültig oder abgelaufen.", "Erneut anmelden", "/_/login")

/**
 * [value] cut to [maxLength] with every control character replaced by `?`. For anything a visitor controls, such
 * as query parameters, so a line break cannot forge an extra line in the log.
 */
internal fun logSafe(value: String, maxLength: Int): String =
    value.take(maxLength).map { if (it.isISOControl()) '?' else it }.joinToString("")
