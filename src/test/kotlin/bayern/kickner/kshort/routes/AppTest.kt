package bayern.kickner.kshort.routes

import bayern.kickner.kshort.TEST_CLIENT_ID
import bayern.kickner.kshort.TEST_CLIENT_SECRET
import bayern.kickner.kshort.TEST_ISSUER
import bayern.kickner.kshort.TempDatabase
import bayern.kickner.kshort.TestClock
import bayern.kickner.kshort.auth.OidcClient
import bayern.kickner.kshort.auth.OidcLogic
import bayern.kickner.kshort.auth.Pkce
import bayern.kickner.kshort.auth.base64Url
import bayern.kickner.kshort.kshort
import bayern.kickner.kshort.link.LinkService
import bayern.kickner.kshort.link.UrlError
import bayern.kickner.kshort.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.parameters
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fake OIDC provider state: answers with an ID token for [username] and the nonce of the current login. The other
 * switches simulate a broken or differently configured IdP.
 */
private class FakeIdp(val clock: TestClock) {
    @Volatile var nonce: String = ""
    @Volatile var username: String = "alice"
    @Volatile var discoveryAvailable: Boolean = true
    @Volatile var authMethods: List<String>? = null
    @Volatile var tokenStatus: HttpStatusCode = HttpStatusCode.OK
    @Volatile var tokenNonce: String? = null
    @Volatile var lastTokenRequest: Parameters? = null
    @Volatile var lastAuthHeader: String? = null
}

/**
 * Hosts the whole app with a temporary database and a fake IdP at [TEST_ISSUER], and returns a client that
 * behaves like a browser (keeps cookies, does not follow redirects).
 */
private fun ApplicationTestBuilder.kshortApp(
    idp: FakeIdp,
    allowedUsers: List<String> = listOf("alice"),
    publicUrl: String = "http://localhost",
): HttpClient {
    externalServices {
        hosts(TEST_ISSUER) {
            routing {
                get("/.well-known/openid-configuration") {
                    if (idp.discoveryAvailable.not()) return@get call.respondText("down", status = HttpStatusCode.ServiceUnavailable)
                    val methods = idp.authMethods?.joinToString(",", ""","token_endpoint_auth_methods_supported":[""", "]") { "\"$it\"" }
                    call.respondText(
                        """{"issuer":"$TEST_ISSUER","authorization_endpoint":"$TEST_ISSUER/authorize","token_endpoint":"$TEST_ISSUER/token"""" +
                            methods.orEmpty() + "}",
                        ContentType.Application.Json,
                    )
                }
                post("/token") {
                    idp.lastTokenRequest = call.receiveParameters()
                    idp.lastAuthHeader = call.request.headers[HttpHeaders.Authorization]
                    if (idp.tokenStatus != HttpStatusCode.OK) {
                        return@post call.respondText("""{"error":"invalid_grant"}""", ContentType.Application.Json, idp.tokenStatus)
                    }
                    val nowSeconds = idp.clock() / 1000
                    val payload = """{"iss":"$TEST_ISSUER","aud":"$TEST_CLIENT_ID","sub":"sub-${idp.username}",""" +
                        """"preferred_username":"${idp.username}","exp":${nowSeconds + 300},"iat":$nowSeconds,"nonce":"${idp.tokenNonce ?: idp.nonce}"}"""
                    val idToken = "eyJhbGciOiJSUzI1NiJ9." + base64Url(payload.toByteArray()) + ".c2ln"
                    call.respondText("""{"access_token":"at","token_type":"Bearer","id_token":"$idToken"}""", ContentType.Application.Json)
                }
            }
        }
    }
    val config = testConfig(allowedUsers = allowedUsers, publicUrl = publicUrl)
    val database = TempDatabase()
    val links = LinkService(database.repository, config.publicHost, idp.clock)
    val oidc = OidcClient(config.oidc, "${config.baseUrl}/_/callback", createClient { })
    application {
        kshort(config, links, oidc, idp.clock)
        monitor.subscribe(ApplicationStopped) { database.close() }
    }
    return browser()
}

private fun ApplicationTestBuilder.browser(): HttpClient = createClient {
    followRedirects = false
    install(HttpCookies)
}

/** Runs the complete login against the fake IdP and returns the callback response. */
private suspend fun HttpClient.login(idp: FakeIdp): HttpResponse {
    val start = get("/_/login")
    assertEquals(HttpStatusCode.Found, start.status)
    val authorizationUrl = Url(start.headers[HttpHeaders.Location] ?: fail("no redirect to the IdP"))
    assertEquals("idp.test", authorizationUrl.host)
    assertEquals("S256", authorizationUrl.parameters["code_challenge_method"])
    assertEquals("http://localhost/_/callback", authorizationUrl.parameters["redirect_uri"])
    idp.nonce = authorizationUrl.parameters["nonce"] ?: fail("no nonce")
    return get("/_/callback?code=c0de&state=${authorizationUrl.parameters["state"]}")
}

private suspend fun HttpClient.csrf(): String {
    val html = get("/").bodyAsText()
    return Regex("""name="csrf" value="([^"]+)"""").find(html)?.groupValues?.get(1) ?: fail("no CSRF token in the dashboard")
}

private suspend fun HttpClient.createLink(csrf: String, url: String, ttl: String = "D7", alias: String = "") =
    submitForm(
        "/_/links",
        parameters {
            append("csrf", csrf)
            append("url", url)
            append("ttl", ttl)
            append("alias", alias)
        },
    )

private suspend fun HttpClient.postForm(path: String, csrf: String, vararg fields: Pair<String, String>) =
    submitForm(
        path,
        parameters {
            append("csrf", csrf)
            fields.forEach { (key, value) -> append(key, value) }
        },
    )

class AppTest {

    @Test
    fun `anonymous visitors see the login and security headers`() = testApplication {
        val client = kshortApp(FakeIdp(TestClock()))

        val root = client.get("/")

        assertEquals(HttpStatusCode.OK, root.status)
        assertContains(root.bodyAsText(), "/_/login")
        assertEquals("nosniff", root.headers["X-Content-Type-Options"])
        assertNotNull(root.headers["Content-Security-Policy"])
        assertEquals(HttpStatusCode.NotFound, client.get("/nope123").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/favicon.ico").status)
        assertEquals(HttpStatusCode.OK, client.get("/_/static/app.css").status)
        assertEquals(HttpStatusCode.OK, client.get("/_/static/app.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/robots.txt").status)
    }

    @Test
    fun `full flow from login to delete`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)

        val callback = client.login(idp)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/", callback.headers[HttpHeaders.Location])
        assertNotNull(idp.lastTokenRequest?.get("code_verifier"))
        assertEquals(OidcLogic.basicAuthHeader(TEST_CLIENT_ID, TEST_CLIENT_SECRET), idp.lastAuthHeader)

        val created = client.createLink(client.csrf(), "https://example.com/artikel?id=1")
        assertEquals(HttpStatusCode.SeeOther, created.status)
        val location = created.headers[HttpHeaders.Location] ?: fail("no Location header")
        assertTrue(location.startsWith("/?created="))
        val code = location.removePrefix("/?created=")
        assertContains(client.get(location).bodyAsText(), "http://localhost/$code")

        val redirect = client.get("/$code")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("https://example.com/artikel?id=1", redirect.headers[HttpHeaders.Location])
        assertEquals("no-store", redirect.headers[HttpHeaders.CacheControl])

        // Eight days later the 7-day link is gone (410), and so is the 7-day session
        idp.clock.advanceDays(8)
        assertEquals(HttpStatusCode.Gone, client.get("/$code").status)
        assertContains(client.get("/").bodyAsText(), "/_/login")
        assertEquals(HttpStatusCode.Found, client.login(idp).status)

        val csrf = client.csrf()
        assertEquals(HttpStatusCode.SeeOther, client.postForm("/_/links/$code/extend", csrf, "ttl" to "D30").status)
        assertEquals(HttpStatusCode.Found, client.get("/$code").status)

        assertEquals(HttpStatusCode.SeeOther, client.postForm("/_/links/$code/delete", csrf).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/$code").status)

        assertEquals(HttpStatusCode.SeeOther, client.postForm("/_/logout", csrf).status)
        assertContains(client.get("/").bodyAsText(), "/_/login")
    }

    @Test
    fun `posting without session redirects to the start page`() = testApplication {
        val client = kshortApp(FakeIdp(TestClock()))

        val response = client.createLink("whatever", "https://example.com")

        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `a wrong csrf token is rejected`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        client.login(idp)

        assertEquals(HttpStatusCode.Forbidden, client.createLink("wrong", "https://example.com").status)
        assertContains(client.get("/").bodyAsText(), "Noch keine Links")
    }

    @Test
    fun `validation errors keep the form and answer 400`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        client.login(idp)
        val csrf = client.csrf()

        val bad = client.createLink(csrf, "javascript:alert(1)")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertContains(bad.bodyAsText(), UrlError.SCHEME.message)

        assertEquals(HttpStatusCode.BadRequest, client.createLink(csrf, "http://localhost/abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.createLink(csrf, "https://example.com", ttl = "D999").status)
        assertEquals(HttpStatusCode.BadRequest, client.createLink(csrf, "https://example.com", alias = "a_b").status)

        assertEquals(HttpStatusCode.SeeOther, client.createLink(csrf, "https://example.com", alias = "Mein-Link").status)
        val duplicate = client.createLink(csrf, "https://example.org", alias = "mein-link")
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)
        assertContains(duplicate.bodyAsText(), "bereits vergeben")
        assertEquals("https://example.com", client.get("/mein-link").headers[HttpHeaders.Location])
    }

    @Test
    fun `users outside the allowlist are refused`() = testApplication {
        val idp = FakeIdp(TestClock()).apply { username = "mallory" }
        val client = kshortApp(idp)

        assertEquals(HttpStatusCode.Forbidden, client.login(idp).status)
        assertContains(client.get("/").bodyAsText(), "/_/login")
    }

    @Test
    fun `a callback with a foreign state is rejected before any token request`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        client.get("/_/login")

        assertEquals(HttpStatusCode.BadRequest, client.get("/_/callback?code=x&state=forged").status)
        assertNull(idp.lastTokenRequest)
    }

    @Test
    fun `other users cannot touch foreign links`() = testApplication {
        val idp = FakeIdp(TestClock())
        val alice = kshortApp(idp, allowedUsers = listOf("alice", "bob"))
        alice.login(idp)
        val code = alice.createLink(alice.csrf(), "https://example.com").headers[HttpHeaders.Location]
            ?.removePrefix("/?created=") ?: fail("link not created")

        val bob = browser()
        idp.username = "bob"
        assertEquals(HttpStatusCode.Found, bob.login(idp).status)
        val bobCsrf = bob.csrf()
        assertEquals(HttpStatusCode.NotFound, bob.postForm("/_/links/$code/delete", bobCsrf).status)
        assertEquals(HttpStatusCode.NotFound, bob.postForm("/_/links/$code/extend", bobCsrf, "ttl" to "FOREVER").status)
        assertEquals(HttpStatusCode.Found, bob.get("/$code").status)
        assertFalse(bob.get("/").bodyAsText().contains("http://localhost/$code"))
    }

    @Test
    fun `a session cookie with a modified IV is rejected`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        val setCookie = client.login(idp).headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .firstOrNull { it.startsWith("kshort_session=") } ?: fail("no session cookie")
        val cookie = setCookie.substringAfter("kshort_session=").substringBefore(';')

        // AES-CBC: flipping bits of the IV flips the same bits of the first plaintext block, {"sub":"sub-alic.
        // Byte 15 turns "sub-alice" into "sub-alixe", a different owner, if the IV is not authenticated.
        val flipped = cookie.substring(30, 32).toInt(16) xor ('c'.code xor 'x'.code)
        val tampered = cookie.substring(0, 30) + "%02x".format(flipped) + cookie.substring(32)
        val plain = createClient { followRedirects = false }
        suspend fun dashboardWith(value: String) =
            plain.get("/") { header(HttpHeaders.Cookie, "kshort_session=$value") }.bodyAsText()

        assertContains(dashboardWith(cookie), "Abmelden")
        assertContains(dashboardWith(tampered), "/_/login")
    }

    @Test
    fun `oversized forms are rejected with 413`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        client.login(idp)

        val response = client.createLink(client.csrf(), "https://example.com/" + "a".repeat(20_000))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `every page carries the complete security headers`() = testApplication {
        val client = kshortApp(FakeIdp(TestClock()))

        for (path in listOf("/", "/nope123")) {
            val response = client.get(path)
            val csp = response.headers["Content-Security-Policy"] ?: fail("no CSP on $path")
            for (directive in listOf("default-src 'none'", "script-src 'self'", "style-src 'self'", "frame-ancestors 'none'")) {
                assertContains(csp, directive)
            }
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
        }
    }

    @Test
    fun `cookies are secure, http only and same site lax with an https public url`() = testApplication {
        val idp = FakeIdp(TestClock())
        kshortApp(idp, publicUrl = "https://s.example.de")
        // The cookie storage of a client would not send Secure cookies over the plain HTTP of the test engine
        val plain = createClient { followRedirects = false }

        val start = plain.get("/_/login")
        val loginCookie = start.rawSetCookie("kshort_login")
        val authorizationUrl = Url(start.headers[HttpHeaders.Location] ?: fail("no redirect to the IdP"))
        idp.nonce = authorizationUrl.parameters["nonce"] ?: fail("no nonce")
        val callback = plain.get("/_/callback?code=c0de&state=${authorizationUrl.parameters["state"]}") {
            header(HttpHeaders.Cookie, loginCookie.substringBefore(';'))
        }

        assertEquals(HttpStatusCode.Found, callback.status)
        for (cookie in listOf(loginCookie, callback.rawSetCookie("kshort_session"))) {
            assertContains(cookie, "; Secure", ignoreCase = true)
            assertContains(cookie, "; HttpOnly", ignoreCase = true)
            assertContains(cookie, "SameSite=Lax")
        }
    }

    @Test
    fun `a login that took longer than ten minutes is rejected before any token request`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        val authorizationUrl = Url(client.get("/_/login").headers[HttpHeaders.Location] ?: fail("no redirect to the IdP"))

        idp.clock.now += 11 * 60 * 1000

        assertEquals(HttpStatusCode.BadRequest, client.get("/_/callback?code=c0de&state=${authorizationUrl.parameters["state"]}").status)
        assertNull(idp.lastTokenRequest)
    }

    @Test
    fun `an error reported by the IdP ends the login without a session`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        client.get("/_/login")

        val response = client.get("/_/callback?error=access_denied&error_description=denied")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(idp.lastTokenRequest)
        assertContains(client.get("/").bodyAsText(), "/_/login")
    }

    @Test
    fun `a failed token request creates no session`() = testApplication {
        val idp = FakeIdp(TestClock()).apply { tokenStatus = HttpStatusCode.BadRequest }
        val client = kshortApp(idp)

        assertEquals(HttpStatusCode.BadGateway, client.login(idp).status)
        assertContains(client.get("/").bodyAsText(), "/_/login")
    }

    @Test
    fun `an id token with the nonce of another login creates no session`() = testApplication {
        val idp = FakeIdp(TestClock()).apply { tokenNonce = "nonce-of-another-login" }
        val client = kshortApp(idp)

        assertEquals(HttpStatusCode.BadGateway, client.login(idp).status)
        assertContains(client.get("/").bodyAsText(), "/_/login")
    }

    @Test
    fun `login answers 502 while discovery fails and works once the IdP is back`() = testApplication {
        val idp = FakeIdp(TestClock()).apply { discoveryAvailable = false }
        val client = kshortApp(idp)

        val response = client.get("/_/login")
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertNull(response.headers[HttpHeaders.Location])

        idp.discoveryAvailable = true
        assertEquals(HttpStatusCode.Found, client.login(idp).status)
    }

    @Test
    fun `client_secret_post sends the credentials in the form instead of the header`() = testApplication {
        val idp = FakeIdp(TestClock()).apply { authMethods = listOf("client_secret_post") }
        val client = kshortApp(idp)

        assertEquals(HttpStatusCode.Found, client.login(idp).status)

        val form = idp.lastTokenRequest ?: fail("no token request")
        assertEquals(TEST_CLIENT_ID, form["client_id"])
        assertEquals(TEST_CLIENT_SECRET, form["client_secret"])
        assertNull(idp.lastAuthHeader)
    }

    @Test
    fun `the token request carries the verifier of the pkce challenge`() = testApplication {
        val idp = FakeIdp(TestClock())
        val client = kshortApp(idp)
        val authorizationUrl = Url(client.get("/_/login").headers[HttpHeaders.Location] ?: fail("no redirect to the IdP"))
        idp.nonce = authorizationUrl.parameters["nonce"] ?: fail("no nonce")

        client.get("/_/callback?code=c0de&state=${authorizationUrl.parameters["state"]}")

        val verifier = idp.lastTokenRequest?.get("code_verifier") ?: fail("no code_verifier")
        assertEquals(authorizationUrl.parameters["code_challenge"], Pkce.challenge(verifier))
    }
}

/** The raw `Set-Cookie` header of the cookie [name], with value and attributes. */
private fun HttpResponse.rawSetCookie(name: String): String =
    headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") } ?: fail("no cookie $name")
