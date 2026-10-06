package bayern.kickner.kshort.routes

import bayern.kickner.kshort.auth.SessionGuard
import bayern.kickner.kshort.auth.UserSession
import bayern.kickner.kshort.auth.seeOther
import bayern.kickner.kshort.formatTimestamp
import bayern.kickner.kshort.link.CreateError
import bayern.kickner.kshort.link.Link
import bayern.kickner.kshort.link.LinkService
import bayern.kickner.kshort.link.ShortCodes
import bayern.kickner.kshort.link.TargetUrls
import bayern.kickner.kshort.link.Ttl
import bayern.kickner.kshort.web.Dashboard
import bayern.kickner.kshort.web.LinkView
import bayern.kickner.kshort.web.dashboardPage
import bayern.kickner.kshort.web.loginPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotnexlib.ResultOf2

/** The dashboard shows at most this many links, the newest first. */
private const val LIST_LIMIT = 500

/**
 * The start page and the actions on links, all at the root of the domain:
 *
 * - `GET /`: login button without session, the dashboard with session.
 * - `POST /_/links`: create a link (form fields `url`, `ttl`, `alias`).
 * - `POST /_/links/{code}/extend`: new lifetime `ttl`, counted from now. Revives an expired link as well.
 * - `POST /_/links/{code}/delete`: delete the link.
 *
 * Every POST needs the session's CSRF token (see [SessionGuard.authorizedForm]) and answers with
 * Post/Redirect/Get, only validation errors render the dashboard directly with status 400.
 *
 * @param baseUrl Public base URL without trailing slash, for the displayed short links.
 */
fun Route.dashboardRoutes(baseUrl: String, links: LinkService, guard: SessionGuard) {
    get("/") {
        val user = guard.currentUser(call) ?: return@get call.respondHtml { loginPage() }
        val query = call.request.queryParameters
        val notice = when (query["msg"]) {
            "extended" -> "Laufzeit wurde aktualisiert."
            "deleted" -> "Kurzlink wurde gelöscht."
            else -> null
        }
        val created = query["created"]?.let { links.findOwned(it, user.sub) }
        call.respondDashboard(baseUrl, links, user, created = created, notice = notice)
    }

    post("/_/links") {
        val (user, form) = guard.authorizedForm(call) ?: return@post
        val rawUrl = form["url"].orEmpty()
        val rawAlias = form["alias"].orEmpty()
        val ttl = Ttl.parse(form["ttl"])

        suspend fun fail(message: String) = call.respondDashboard(
            baseUrl, links, user,
            status = HttpStatusCode.BadRequest,
            error = message,
            prefillUrl = rawUrl.take(TargetUrls.MAX_LENGTH),
            prefillAlias = rawAlias.take(ShortCodes.MAX_ALIAS_LENGTH),
            prefillTtl = ttl ?: Ttl.DEFAULT,
        )

        if (ttl == null) return@post fail("Bitte eine gültige Laufzeit wählen.")
        when (val result = links.create(user.sub, rawUrl, ttl, rawAlias)) {
            is ResultOf2.Success -> call.seeOther("/?created=${result.value.code}")
            is ResultOf2.Failure -> fail(result.value.message())
        }
    }

    post("/_/links/{code}/extend") {
        val (user, form) = guard.authorizedForm(call) ?: return@post
        val code = call.parameters["code"].orEmpty()
        val ttl = Ttl.parse(form["ttl"]) ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (links.extend(code, user.sub, ttl).not()) return@post call.respond(HttpStatusCode.NotFound)
        call.seeOther("/?msg=extended")
    }

    post("/_/links/{code}/delete") {
        val (user, _) = guard.authorizedForm(call) ?: return@post
        val code = call.parameters["code"].orEmpty()
        if (links.delete(code, user.sub).not()) return@post call.respond(HttpStatusCode.NotFound)
        call.seeOther("/?msg=deleted")
    }
}

private suspend fun ApplicationCall.respondDashboard(
    baseUrl: String,
    links: LinkService,
    user: UserSession,
    status: HttpStatusCode = HttpStatusCode.OK,
    created: Link? = null,
    notice: String? = null,
    error: String? = null,
    prefillUrl: String = "",
    prefillAlias: String = "",
    prefillTtl: Ttl = Ttl.DEFAULT,
) {
    val now = links.now()
    val model = Dashboard(
        userName = user.name,
        csrf = user.csrf,
        links = links.list(user.sub, LIST_LIMIT).map { it.toView(baseUrl, now) },
        created = created?.toView(baseUrl, now),
        notice = notice,
        error = error,
        prefillUrl = prefillUrl,
        prefillAlias = prefillAlias,
        prefillTtl = prefillTtl,
    )
    // The page carries the CSRF token and personal data, no cache may keep it
    response.header(HttpHeaders.CacheControl, "no-store")
    respondHtml(status) { dashboardPage(model) }
}

private fun Link.toView(baseUrl: String, now: Long): LinkView {
    val expiry = expiresAt
    val expired = isExpired(now)
    val status = when {
        expiry == null -> "läuft nie ab"
        expired -> "abgelaufen am ${formatTimestamp(expiry)}"
        else -> "gültig bis ${formatTimestamp(expiry)}"
    }
    return LinkView(code, "$baseUrl/$code", url, hits, status, expired)
}

/** German text for the error box. */
private fun CreateError.message(): String = when (this) {
    is CreateError.InvalidUrl -> reason.message
    CreateError.InvalidAlias -> "Alias: 3 bis 40 Zeichen, nur a bis z, 0 bis 9 und Bindestrich, kein Bindestrich am Anfang oder Ende."
    is CreateError.AliasTaken -> "Der Alias „$alias“ ist bereits vergeben."
    CreateError.NoFreeCode -> "Es konnte kein freier Code erzeugt werden. Bitte erneut versuchen."
}
