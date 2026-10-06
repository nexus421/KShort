package bayern.kickner.kshort.auth

import bayern.kickner.kshort.web.messagePage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

/**
 * Access checks shared by all routes that need a logged in user.
 *
 * @param clock Current time in Unix milliseconds, injectable for tests.
 */
class SessionGuard(private val allowlist: Allowlist, private val clock: () -> Long) {

    /**
     * The session of [call], if it is still valid and its user is still on the allowlist. An expired or no
     * longer allowed session is cleared, so removing a user from `allowedUsers` (and restarting) takes effect at once.
     */
    fun currentUser(call: ApplicationCall): UserSession? {
        val session = call.sessions.get<UserSession>() ?: return null
        val valid = session.expiresAt > clock() && allowlist.isAllowed(session.toOidcUser())
        if (valid) return session
        call.sessions.clear<UserSession>()
        return null
    }

    /**
     * Common entry of every POST: requires a valid session and the session's CSRF token in the form. On failure
     * the response is sent here (303 to the start page without session, 403 with a wrong token) and null is
     * returned, so the route only has to `return`.
     */
    suspend fun authorizedForm(call: ApplicationCall): Pair<UserSession, Parameters>? {
        val user = currentUser(call)
        if (user == null) {
            call.seeOther("/")
            return null
        }
        val form = call.receiveParameters()
        val token = form["csrf"]
        val tokenValid = token != null && constantTimeEquals(token, user.csrf)
        if (tokenValid.not()) {
            call.respondHtml(HttpStatusCode.Forbidden) {
                messagePage("Formular veraltet", "Bitte die Seite neu laden und es erneut versuchen.")
            }
            return null
        }
        return user to form
    }
}

/** 303 See Other: after a POST the browser continues with a GET (Post/Redirect/Get). */
suspend fun ApplicationCall.seeOther(location: String) {
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode.SeeOther)
}
