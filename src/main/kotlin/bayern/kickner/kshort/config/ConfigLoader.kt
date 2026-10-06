package bayern.kickner.kshort.config

import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.io.File
import java.net.URI

/**
 * Loads and validates the configuration file at [path].
 *
 * Unknown keys are errors, so a typo cannot silently fall back to a default. Every validation issue is collected
 * into one message, so a broken config needs only one restart to fix.
 *
 * @return the config, or a human-readable failure message that never contains the file content (it holds secrets).
 */
fun loadConfig(path: String): ResultOf2<AppConfig, String> {
    val file = File(path)
    if (file.exists().not()) return ResultOf2.Failure("Config file not found: ${file.absolutePath}")

    val text = runCatching { file.readText() }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be read: ${it.message}") }
    val config = runCatching { Json.decodeFromString<AppConfig>(text) }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be parsed: ${sanitize(it.message)}") }

    val issues = validate(config)
    if (issues.isNotEmpty()) return ResultOf2.Failure("Config file ${file.absolutePath} is invalid:\n" + issues.joinToString("\n") { "- $it" })

    return ResultOf2.Success(config)
}

/**
 * Keeps only the first line of a kotlinx.serialization message: the following lines are either a developer hint
 * or the offending document ("JSON input: ..."), which holds secrets.
 */
private fun sanitize(message: String?): String =
    message?.substringBefore("JSON input")?.lineSequence()?.first()?.trim()?.ifEmpty { null } ?: "unknown error"

/** All problems of [config] at once. Messages name fields, never their secret values. */
internal fun validate(config: AppConfig): List<String> {
    val issues = mutableListOf<String>()

    if (config.listenHost.isBlank()) issues += "listenHost must not be blank"
    if (config.listenPort !in 1..65535) issues += "listenPort must be between 1 and 65535"
    if (config.databasePath.isBlank()) issues += "databasePath must not be blank"

    val publicUri = runCatching { URI(config.baseUrl) }.getOrNull()
    val publicUrlValid = publicUri != null &&
        (publicUri.scheme == "https" || publicUri.scheme == "http") &&
        publicUri.host.isNullOrBlank().not() &&
        publicUri.rawPath.isNullOrEmpty() &&
        publicUri.rawQuery == null
    if (publicUrlValid.not()) issues += "publicUrl must be an http(s) URL without path, e.g. https://s.example.de"

    if (config.allowedUsers.none { it.isNotBlank() }) issues += "allowedUsers must not be empty (\"*\" allows every user of the IdP)"

    if (config.oidc.issuer.startsWith("https://").not()) issues += "oidc.issuer must be an https URL"
    if (config.oidc.clientId.isBlank()) issues += "oidc.clientId must not be blank"
    if (config.oidc.clientSecret.isBlank()) issues += "oidc.clientSecret must not be blank"
    if ("openid" !in config.oidc.scopes.split(' ')) issues += "oidc.scopes must contain 'openid'"

    val encryptKey = config.session.encryptKeyHex.decodeHexOrNull()
    if (encryptKey == null || encryptKey.size !in setOf(16, 24, 32)) issues += "session.encryptKeyHex must be 16, 24 or 32 bytes as hex (see 'keys' command)"
    val signKey = config.session.signKeyHex.decodeHexOrNull()
    if (signKey == null || signKey.size < 32) issues += "session.signKeyHex must be at least 32 bytes as hex (see 'keys' command)"

    return issues
}

private fun String.decodeHexOrNull(): ByteArray? = runCatching { hexToByteArray() }.getOrNull()
