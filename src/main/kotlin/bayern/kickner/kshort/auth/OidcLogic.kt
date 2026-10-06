package bayern.kickner.kshort.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotnexlib.ResultOf2
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** URL-safe Base64 without padding, the encoding of PKCE, JWT parts and all random tokens here. */
fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * A random URL-safe token, 256 bits by default. Used for `state`, `nonce`, the PKCE verifier and CSRF tokens.
 *
 * @param random Source of randomness, injectable for tests.
 */
fun randomToken(bytes: Int = 32, random: SecureRandom = SecureRandom()): String =
    base64Url(ByteArray(bytes).also(random::nextBytes))

/** Compares two secrets in constant time, so response timing reveals nothing about a partial match. */
fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

/** PKCE (RFC 7636) with the S256 method. */
object Pkce {
    /** A fresh code verifier: 32 random bytes, 43 characters, within the 43 to 128 the RFC demands. */
    fun verifier(random: SecureRandom = SecureRandom()): String = randomToken(32, random)

    /** The S256 code challenge for [verifier]. */
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
}

/** How the client authenticates at the token endpoint. */
enum class TokenAuthMethod { CLIENT_SECRET_BASIC, CLIENT_SECRET_POST }

/**
 * The parts of the IdP's discovery document KShort needs.
 *
 * @property issuer Exactly as configured, checked against the document.
 * @property tokenAuthMethod `client_secret_basic` if the IdP supports it (the spec default), else `client_secret_post`.
 */
data class ProviderMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val tokenAuthMethod: TokenAuthMethod,
)

/**
 * A successfully logged in user.
 *
 * @property sub Stable unique id at the IdP, used as owner of links.
 * @property username `preferred_username`, if the IdP sends one.
 * @property email Lower-cased address, only set if the IdP reports it as verified.
 * @property displayName Best available name for the page header.
 */
data class OidcUser(
    val sub: String,
    val username: String?,
    val email: String?,
    val displayName: String,
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() }?.doubleOrNull?.toLong()

private fun JsonObject.stringList(key: String): List<String>? = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    is JsonPrimitive -> if (value.isString) listOf(value.content) else null
    else -> null
}

private fun formEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

/**
 * Pure OpenID Connect logic without any network access: Authorization Code Flow with PKCE (S256), `state` and
 * `nonce`. Every function returns its failure as an English message for the log and never throws.
 */
object OidcLogic {
    /** URL of the discovery document of [issuer]. */
    fun discoveryUrl(issuer: String): String = issuer.trimEnd('/') + "/.well-known/openid-configuration"

    /** Parses the discovery document [json] and checks that it really belongs to [expectedIssuer]. */
    fun parseDiscovery(json: JsonObject, expectedIssuer: String): ResultOf2<ProviderMetadata, String> {
        val issuer = json.string("issuer") ?: return ResultOf2.Failure("discovery document has no 'issuer'")
        if (issuer != expectedIssuer) return ResultOf2.Failure("issuer mismatch: configured '$expectedIssuer', IdP reports '$issuer'")
        val authorization = json.string("authorization_endpoint")
            ?: return ResultOf2.Failure("discovery document has no 'authorization_endpoint'")
        val token = json.string("token_endpoint") ?: return ResultOf2.Failure("discovery document has no 'token_endpoint'")
        val bothHttps = authorization.startsWith("https://") && token.startsWith("https://")
        if (bothHttps.not()) return ResultOf2.Failure("OIDC endpoints must use https")

        val methods = json.stringList("token_endpoint_auth_methods_supported")
        val method = when {
            methods == null || "client_secret_basic" in methods -> TokenAuthMethod.CLIENT_SECRET_BASIC
            "client_secret_post" in methods -> TokenAuthMethod.CLIENT_SECRET_POST
            else -> return ResultOf2.Failure("IdP supports neither client_secret_basic nor client_secret_post: $methods")
        }
        return ResultOf2.Success(ProviderMetadata(issuer, authorization, token, method))
    }

    /** The URL the browser is sent to for the login at the IdP. */
    fun authorizationUrl(
        metadata: ProviderMetadata,
        clientId: String,
        redirectUri: String,
        scopes: String,
        state: String,
        nonce: String,
        codeChallenge: String,
    ): String {
        val query = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to scopes,
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        ).joinToString("&") { (key, value) -> "${formEncode(key)}=${formEncode(value)}" }
        val separator = if ('?' in metadata.authorizationEndpoint) '&' else '?'
        return metadata.authorizationEndpoint + separator + query
    }

    /** `Authorization` header for `client_secret_basic`. RFC 6749 section 2.3.1 form-encodes both parts first. */
    fun basicAuthHeader(clientId: String, clientSecret: String): String {
        val raw = "${formEncode(clientId)}:${formEncode(clientSecret)}"
        return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    /** The `id_token` from a token endpoint response [json]. */
    fun idTokenFromTokenResponse(json: JsonObject): ResultOf2<String, String> =
        json.string("id_token")?.takeIf { it.isNotBlank() }?.let { ResultOf2.Success(it) }
            ?: ResultOf2.Failure("token response has no id_token (is the scope 'openid' set?)")

    /** Decodes the payload (claims) of [idToken] without looking at the signature, see [validateIdToken]. */
    fun decodeIdTokenPayload(idToken: String): ResultOf2<JsonObject, String> {
        val parts = idToken.split('.')
        if (parts.size != 3) return ResultOf2.Failure("ID token is not a JWS")
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8) }
            .getOrElse { return ResultOf2.Failure("ID token payload is not valid base64url") }
        return runCatching { Json.parseToJsonElement(payload).jsonObject }
            .fold({ ResultOf2.Success(it) }, { ResultOf2.Failure("ID token payload is not a JSON object") })
    }

    /**
     * Validates the claims of an ID token and extracts the user.
     *
     * The signature is deliberately not verified. The token comes straight from the IdP's token endpoint over
     * TLS, as answer to a request authenticated with client secret and PKCE. OpenID Connect Core 1.0, section
     * 3.1.3.7 item 6 allows the TLS server validation instead of a signature check for exactly this case. KShort
     * never accepts an ID token from anywhere else (browser, cookie or query).
     *
     * @param now Current time in Unix milliseconds.
     * @param clockSkewSeconds Tolerance for the `exp` check, as clocks of IdP and server may differ a little.
     */
    fun validateIdToken(
        claims: JsonObject,
        expectedIssuer: String,
        clientId: String,
        expectedNonce: String,
        now: Long,
        clockSkewSeconds: Long = 120,
    ): ResultOf2<OidcUser, String> {
        val issuer = claims.string("iss")
        if (issuer != expectedIssuer) return ResultOf2.Failure("ID token has the wrong issuer '$issuer'")

        val audience = claims.stringList("aud") ?: return ResultOf2.Failure("ID token has no 'aud'")
        if (clientId !in audience) return ResultOf2.Failure("ID token is not issued for this client")
        val authorizedParty = claims.string("azp")
        if (audience.size > 1 && authorizedParty == null) return ResultOf2.Failure("ID token has several audiences but no 'azp'")
        if (authorizedParty != null && authorizedParty != clientId) return ResultOf2.Failure("ID token has the wrong 'azp'")

        val expiresAt = claims.number("exp") ?: return ResultOf2.Failure("ID token has no 'exp'")
        val expired = (expiresAt + clockSkewSeconds) * 1000 <= now
        if (expired) return ResultOf2.Failure("ID token is expired")

        val nonce = claims.string("nonce") ?: return ResultOf2.Failure("ID token has no 'nonce'")
        if (constantTimeEquals(nonce, expectedNonce).not()) return ResultOf2.Failure("ID token nonce does not match")

        val sub = claims.string("sub")?.takeIf { it.isNotBlank() } ?: return ResultOf2.Failure("ID token has no 'sub'")
        val username = claims.string("preferred_username")?.takeIf { it.isNotBlank() }
        val emailVerified = (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull == true
        val email = claims.string("email")?.takeIf { emailVerified && it.isNotBlank() }?.lowercase()
        val name = claims.string("name")?.takeIf { it.isNotBlank() }
        return ResultOf2.Success(OidcUser(sub, username, email, displayName = name ?: username ?: email ?: sub))
    }
}
