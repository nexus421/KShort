package bayern.kickner.kshort.web

import bayern.kickner.kshort.link.Ttl
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.title

/*
 * Server-rendered HTML with kotlinx.html: every text and attribute is escaped automatically. No inline CSS or
 * JavaScript, so the Content-Security-Policy can stay strict. The UI language is German, the users are.
 */

/**
 * One link as the dashboard shows it.
 *
 * @property shortUrl Full short URL, e.g. `https://s.example.de/Ab3xY9z`.
 * @property status Expiry text, e.g. `gültig bis 12.10.2026 21:40:00 CEST`.
 */
data class LinkView(
    val code: String,
    val shortUrl: String,
    val url: String,
    val hits: Long,
    val status: String,
    val expired: Boolean,
)

/**
 * Everything the dashboard renders.
 *
 * @property csrf Token of the session, goes into every form.
 * @property created The link just created, shown highlighted with a copy button.
 * @property notice Green info box. [error] is the red one.
 * @property prefillUrl Form values to keep after a validation error.
 */
data class Dashboard(
    val userName: String,
    val csrf: String,
    val links: List<LinkView>,
    val created: LinkView? = null,
    val notice: String? = null,
    val error: String? = null,
    val prefillUrl: String = "",
    val prefillAlias: String = "",
    val prefillTtl: Ttl = Ttl.DEFAULT,
)

/** Common page frame with stylesheet and script. */
fun HTML.layout(pageTitle: String, content: DIV.() -> Unit) {
    attributes["lang"] = "de"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        meta(name = "robots", content = "noindex")
        title(pageTitle)
        link(rel = "stylesheet", href = "/_/static/app.css")
        script(src = "/_/static/app.js") { defer = true }
    }
    body {
        div("wrap") { content() }
    }
}

/** Start page for visitors without session. [message] is shown as error box, e.g. after a refused login. */
fun HTML.loginPage(message: String? = null) = layout("Kurzlinks") {
    div("card center") {
        h1 { +"Kurzlinks" }
        p("muted") { +"Zum Erstellen von Kurzlinks bitte anmelden." }
        if (message != null) p("msg error") { +message }
        a(href = "/_/login", classes = "button") { +"Anmelden" }
    }
}

/** A simple page with heading, text and one link button, for errors and expired or unknown links. */
fun HTML.messagePage(heading: String, text: String, linkText: String = "Zur Startseite", linkHref: String = "/") =
    layout(heading) {
        div("card center") {
            h1 { +heading }
            p { +text }
            a(href = linkHref, classes = "button") { +linkText }
        }
    }

/** The page of a logged in user: create form plus the own links with extend and delete. */
fun HTML.dashboardPage(model: Dashboard) = layout("Kurzlinks") {
    div("topbar") {
        span("muted") { +"Angemeldet als ${model.userName}" }
        form(action = "/_/logout", method = FormMethod.post, classes = "inline") {
            csrfField(model.csrf)
            button(type = ButtonType.submit, classes = "link") { +"Abmelden" }
        }
    }

    model.notice?.let { p("msg ok") { +it } }
    model.error?.let { p("msg error") { +it } }

    model.created?.let { created ->
        div("card created") {
            p { +"Kurzlink erstellt:" }
            copyRow(created.shortUrl)
        }
    }

    div("card") {
        h1 { +"Neuen Kurzlink erstellen" }
        form(action = "/_/links", method = FormMethod.post) {
            csrfField(model.csrf)
            label {
                htmlFor = "url"
                +"Lange URL"
            }
            input(type = InputType.url, name = "url") {
                id = "url"
                required = true
                placeholder = "https://…"
                maxLength = "2048"
                value = model.prefillUrl
                attributes["autofocus"] = "autofocus"
            }
            div("row") {
                div("grow") {
                    label {
                        htmlFor = "ttl"
                        +"Gültig für"
                    }
                    ttlSelect(model.prefillTtl, selectId = "ttl")
                }
                div("grow") {
                    label {
                        htmlFor = "alias"
                        +"Eigener Alias (optional)"
                    }
                    input(type = InputType.text, name = "alias") {
                        id = "alias"
                        placeholder = "z. B. urlaub-2026"
                        maxLength = "40"
                        value = model.prefillAlias
                        attributes["autocapitalize"] = "none"
                        attributes["autocomplete"] = "off"
                    }
                }
            }
            button(type = ButtonType.submit, classes = "primary") { +"Kürzen" }
        }
    }

    h2 { +"Meine Links (${model.links.size})" }
    if (model.links.isEmpty()) p("muted") { +"Noch keine Links." }
    for (link in model.links) {
        div(if (link.expired) "card link expired" else "card link") {
            copyRow(link.shortUrl)
            div("target") {
                a(href = link.url, target = "_blank") {
                    rel = "noopener noreferrer"
                    +link.url
                }
            }
            p("muted small") { +"${link.status} · ${link.hits} Aufrufe" }
            div("actions") {
                form(action = "/_/links/${link.code}/extend", method = FormMethod.post, classes = "inline") {
                    csrfField(model.csrf)
                    ttlSelect(Ttl.DEFAULT)
                    button(type = ButtonType.submit) { +"Verlängern" }
                }
                form(action = "/_/links/${link.code}/delete", method = FormMethod.post, classes = "inline") {
                    attributes["data-confirm"] = "Kurzlink ${link.code} wirklich löschen?"
                    csrfField(model.csrf)
                    button(type = ButtonType.submit, classes = "danger") { +"Löschen" }
                }
            }
        }
    }
}

private fun FlowContent.csrfField(token: String) {
    input(type = InputType.hidden, name = "csrf") { value = token }
}

private fun FlowContent.ttlSelect(selected: Ttl, selectId: String? = null) {
    select {
        name = "ttl"
        if (selectId != null) id = selectId
        for (ttl in Ttl.entries) {
            option {
                value = ttl.name
                this.selected = ttl == selected
                +ttl.label
            }
        }
    }
}

private fun FlowContent.copyRow(url: String) {
    div("copyrow") {
        a(href = url, classes = "short") { +url }
        button(type = ButtonType.button, classes = "copy") {
            attributes["data-copy"] = url
            +"Kopieren"
        }
    }
}
