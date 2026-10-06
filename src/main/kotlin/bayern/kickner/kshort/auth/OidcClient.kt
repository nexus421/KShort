package bayern.kickner.kshort.auth

import bayern.kickner.kshort.config.OidcConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotnexlib.ResultOf2

/**
 * Talks to the IdP. The discovery document is loaded on the first login and then cached, so redirects keep
 * working while the IdP is down, and a failed discovery is retried with the next login.
 *
 * @param redirectUri The callback URL registered at the IdP, `<publicUrl>/_/callback`.
 * @param http Client for the IdP. Tests pass one that talks to a fake IdP.
 */
class OidcClient(
    private val config: OidcConfig,
    private val redirectUri: String,
    private val http: HttpClient,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: ProviderMetadata? = null

    /** The IdP's metadata, from cache or freshly loaded. */
    suspend fun metadata(): ResultOf2<ProviderMetadata, String> {
        cached?.let { return ResultOf2.Success(it) }
        return mutex.withLock {
            cached?.let { return@withLock ResultOf2.Success(it) }
            fetchMetadata().also { if (it is ResultOf2.Success) cached = it.value }
        }
    }

    private suspend fun fetchMetadata(): ResultOf2<ProviderMetadata, String> {
        val url = OidcLogic.discoveryUrl(config.issuer)
        val response = runCatching { http.get(url) { accept(ContentType.Application.Json) } }
            .getOrElse { return ResultOf2.Failure("discovery request to $url failed: ${it.message}") }
        if (response.status.isSuccess().not()) return ResultOf2.Failure("discovery request to $url answered HTTP ${response.status.value}")
        val json = parseObject(response.bodyAsText()) ?: return ResultOf2.Failure("discovery document is not a JSON object")
        return OidcLogic.parseDiscovery(json, config.issuer)
    }

    /** The login URL at the IdP for one login attempt. */
    suspend fun authorizationUrl(state: String, nonce: String, codeChallenge: String): ResultOf2<String, String> =
        when (val metadata = metadata()) {
            is ResultOf2.Failure -> metadata
            is ResultOf2.Success -> ResultOf2.Success(
                OidcLogic.authorizationUrl(metadata.value, config.clientId, redirectUri, config.scopes, state, nonce, codeChallenge)
            )
        }

    /**
     * Finishes a login: exchanges [code] (with the PKCE [codeVerifier]) for tokens and validates the ID token
     * against [nonce].
     *
     * @param now Current time in Unix milliseconds, for the `exp` check.
     * @return the logged in user, or an English reason for the log.
     */
    suspend fun finishLogin(code: String, codeVerifier: String, nonce: String, now: Long): ResultOf2<OidcUser, String> {
        val metadata = when (val result = metadata()) {
            is ResultOf2.Success -> result.value
            is ResultOf2.Failure -> return result
        }
        val response = runCatching {
            http.submitForm(
                url = metadata.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("code_verifier", codeVerifier)
                    if (metadata.tokenAuthMethod == TokenAuthMethod.CLIENT_SECRET_POST) {
                        append("client_id", config.clientId)
                        append("client_secret", config.clientSecret)
                    }
                },
            ) {
                accept(ContentType.Application.Json)
                if (metadata.tokenAuthMethod == TokenAuthMethod.CLIENT_SECRET_BASIC)
                    header(HttpHeaders.Authorization, OidcLogic.basicAuthHeader(config.clientId, config.clientSecret))
            }
        }.getOrElse { return ResultOf2.Failure("token request failed: ${it.message}") }

        val body = response.bodyAsText()
        // The IdP's error response (error, error_description) contains no secrets and is the best hint for debugging
        if (response.status.isSuccess().not()) return ResultOf2.Failure("token endpoint answered HTTP ${response.status.value}: ${body.take(300)}")

        val json = parseObject(body) ?: return ResultOf2.Failure("token response is not a JSON object")
        val idToken = when (val result = OidcLogic.idTokenFromTokenResponse(json)) {
            is ResultOf2.Success -> result.value
            is ResultOf2.Failure -> return result
        }
        val claims = when (val result = OidcLogic.decodeIdTokenPayload(idToken)) {
            is ResultOf2.Success -> result.value
            is ResultOf2.Failure -> return result
        }
        return OidcLogic.validateIdToken(claims, metadata.issuer, config.clientId, nonce, now)
    }

    private fun parseObject(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
}
