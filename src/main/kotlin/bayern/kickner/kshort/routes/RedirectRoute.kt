package bayern.kickner.kshort.routes

import bayern.kickner.kshort.link.LinkService
import bayern.kickner.kshort.link.Resolution
import bayern.kickner.kshort.web.messagePage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /{code}`: the public part, no login needed.
 *
 * Answers 302 to the target, 410 for an expired link and 404 for an unknown one. Deliberately 302 and never
 * 301: browsers cache a 301 forever, so an expired, extended or deleted link would keep its old behaviour and
 * the hit counter would miss visits. `Cache-Control: no-store` makes sure of that for proxies too.
 *
 * Also `GET /robots.txt`, which keeps crawlers out of everything.
 */
fun Route.redirectRoute(links: LinkService) {
    get("/robots.txt") {
        call.respondText("User-agent: *\nDisallow: /\n")
    }

    get("/{code}") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        when (val result = links.resolve(call.parameters["code"].orEmpty())) {
            is Resolution.Found -> call.respondRedirect(result.url, permanent = false)
            Resolution.Expired -> call.respondHtml(HttpStatusCode.Gone) {
                messagePage("Link abgelaufen", "Dieser Kurzlink ist abgelaufen und leitet nicht mehr weiter.")
            }

            Resolution.NotFound -> call.respondHtml(HttpStatusCode.NotFound) {
                messagePage("Link nicht gefunden", "Diesen Kurzlink gibt es nicht.")
            }
        }
    }
}
