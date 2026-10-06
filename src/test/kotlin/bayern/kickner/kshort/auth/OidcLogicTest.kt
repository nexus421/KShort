package bayern.kickner.kshort.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotnexlib.ResultOf2
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OidcLogicTest {
    private val issuer = "https://auth.example.de/application/o/kshort/"
    private val clientId = "kshort"
    private val nowSeconds = 1_800_000_000L
    private val now = nowSeconds * 1000

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun claims(
        iss: String = issuer,
        aud: String = "\"$clientId\"",
        exp: Long = nowSeconds + 300,
        nonce: String = "n1",
        extra: String = "",
    ) = json("""{"iss":"$iss","aud":$aud,"sub":"u-123","exp":$exp,"iat":$nowSeconds,"nonce":"$nonce"$extra}""")

    private fun validate(claims: JsonObject) = OidcLogic.validateIdToken(claims, issuer, clientId, "n1", now)

    @Test
    fun `pkce matches the example of rfc 7636`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals(43, Pkce.verifier().length)
    }

    @Test
    fun `a valid id token yields the user`() {
        val result = validate(claims(extra = ""","preferred_username":"max","email":"Max@Example.de","email_verified":true"""))

        val user = assertIs<ResultOf2.Success<OidcUser>>(result).value
        assertEquals("u-123", user.sub)
        assertEquals("max", user.username)
        assertEquals("max@example.de", user.email)
        assertEquals("max", user.displayName)
    }

    @Test
    fun `an unverified email is ignored`() {
        val user = assertIs<ResultOf2.Success<OidcUser>>(validate(claims(extra = ""","email":"max@example.de","email_verified":false"""))).value

        assertNull(user.email)
        assertEquals("u-123", user.displayName)
    }

    @Test
    fun `several audiences need a matching azp`() {
        assertIs<ResultOf2.Failure<String>>(validate(claims(aud = """["$clientId","other"]""")))
        assertIs<ResultOf2.Success<OidcUser>>(validate(claims(aud = """["$clientId","other"]""", extra = ""","azp":"$clientId"""")))
    }

    @Test
    fun `bad id tokens are rejected`() {
        assertIs<ResultOf2.Failure<String>>(validate(claims(iss = "https://evil.example")))
        assertIs<ResultOf2.Failure<String>>(validate(claims(aud = "\"other\"")))
        assertIs<ResultOf2.Failure<String>>(validate(claims(exp = nowSeconds - 500)))
        assertIs<ResultOf2.Failure<String>>(validate(claims(nonce = "x")))
        assertIs<ResultOf2.Failure<String>>(validate(claims(extra = ""","azp":"other"""")))
        assertIs<ResultOf2.Failure<String>>(OidcLogic.decodeIdTokenPayload("not-a-jwt"))
        assertIs<ResultOf2.Failure<String>>(OidcLogic.decodeIdTokenPayload("a.!!!.c"))
    }

    @Test
    fun `the clock skew tolerates a slightly expired token`() {
        assertIs<ResultOf2.Success<OidcUser>>(validate(claims(exp = nowSeconds - 60)))
    }

    @Test
    fun `the payload of a jws is decoded`() {
        val payload = base64Url("""{"sub":"abc"}""".toByteArray())

        val claims = assertIs<ResultOf2.Success<JsonObject>>(OidcLogic.decodeIdTokenPayload("h.$payload.s")).value
        assertEquals("\"abc\"", claims["sub"].toString())
    }

    @Test
    fun `discovery picks the token auth method and checks the issuer`() {
        val post = OidcLogic.parseDiscovery(
            json("""{"issuer":"$issuer","authorization_endpoint":"https://a/x","token_endpoint":"https://a/t","token_endpoint_auth_methods_supported":["client_secret_post"]}"""),
            issuer,
        )
        assertEquals(TokenAuthMethod.CLIENT_SECRET_POST, assertIs<ResultOf2.Success<ProviderMetadata>>(post).value.tokenAuthMethod)

        val noMethods = OidcLogic.parseDiscovery(json("""{"issuer":"$issuer","authorization_endpoint":"https://a/x","token_endpoint":"https://a/t"}"""), issuer)
        assertEquals(TokenAuthMethod.CLIENT_SECRET_BASIC, assertIs<ResultOf2.Success<ProviderMetadata>>(noMethods).value.tokenAuthMethod)

        val wrongIssuer = OidcLogic.parseDiscovery(json("""{"issuer":"https://other/","authorization_endpoint":"https://a/x","token_endpoint":"https://a/t"}"""), issuer)
        assertIs<ResultOf2.Failure<String>>(wrongIssuer)

        val plainHttp = OidcLogic.parseDiscovery(json("""{"issuer":"$issuer","authorization_endpoint":"http://a/x","token_endpoint":"https://a/t"}"""), issuer)
        assertIs<ResultOf2.Failure<String>>(plainHttp)
    }

    @Test
    fun `the authorization url is form encoded`() {
        val metadata = ProviderMetadata(issuer, "https://auth.example.de/authorize", "https://auth.example.de/token", TokenAuthMethod.CLIENT_SECRET_BASIC)

        val url = OidcLogic.authorizationUrl(metadata, clientId, "https://s.example.de/_/callback", "openid profile", "st", "no", "ch")

        assertEquals(
            "https://auth.example.de/authorize?response_type=code&client_id=kshort" +
                "&redirect_uri=https%3A%2F%2Fs.example.de%2F_%2Fcallback&scope=openid+profile" +
                "&state=st&nonce=no&code_challenge=ch&code_challenge_method=S256",
            url,
        )
    }

    @Test
    fun `basic auth form encodes id and secret first`() {
        val expected = "Basic " + Base64.getEncoder().encodeToString("a+b:p%3Aw".toByteArray())

        assertEquals(expected, OidcLogic.basicAuthHeader("a b", "p:w"))
    }

    @Test
    fun `the token response must contain an id token`() {
        assertEquals("t", assertIs<ResultOf2.Success<String>>(OidcLogic.idTokenFromTokenResponse(json("""{"id_token":"t"}"""))).value)
        assertIs<ResultOf2.Failure<String>>(OidcLogic.idTokenFromTokenResponse(json("""{"access_token":"a"}""")))
    }

    @Test
    fun `allowlist matches sub, username and verified email`() {
        val list = Allowlist(listOf("max", "Anna@Example.de", "sub-42"))

        assertTrue(list.isAllowed(OidcUser("x", "max", null, "Max")))
        assertTrue(list.isAllowed(OidcUser("x", null, "anna@example.de", "Anna")))
        assertTrue(list.isAllowed(OidcUser("sub-42", null, null, "S")))
        assertFalse(list.isAllowed(OidcUser("x", "moritz", "moritz@example.de", "M")))
        assertTrue(Allowlist(listOf("*")).isAllowed(OidcUser("x", null, null, "X")))
        assertFalse(Allowlist(listOf(" ")).isAllowed(OidcUser("x", null, null, "X")))
    }

    @Test
    fun `constant time comparison`() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
    }
}
