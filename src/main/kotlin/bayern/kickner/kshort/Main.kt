package bayern.kickner.kshort

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.slf4j.slf4jBridge
import bayern.kickner.klogger.staticLog
import bayern.kickner.kshort.auth.Allowlist
import bayern.kickner.kshort.auth.OidcClient
import bayern.kickner.kshort.auth.SessionGuard
import bayern.kickner.kshort.auth.installSessions
import bayern.kickner.kshort.cli.generateSessionKeys
import bayern.kickner.kshort.config.AppConfig
import bayern.kickner.kshort.config.loadConfig
import bayern.kickner.kshort.link.LinkRepository
import bayern.kickner.kshort.link.LinkService
import bayern.kickner.kshort.routes.authRoutes
import bayern.kickner.kshort.routes.dashboardRoutes
import bayern.kickner.kshort.routes.redirectRoute
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import io.ktor.client.engine.cio.CIO as ClientCIO

private const val TAG = "Main"

/**
 * `EX_CONFIG` from sysexits.h, used when the config file is rejected. kshort.service lists it in
 * `RestartPreventExitStatus`: a restart cannot fix a broken config, so systemd must not loop.
 */
private const val EXIT_CONFIG_ERROR = 78

/**
 * The server could not start, e.g. the address could not be bound or the database could not be opened. A plain
 * failure, so systemd retries: an address that is not assigned yet at boot usually is a few seconds later.
 */
private const val EXIT_START_FAILED = 1

/** Forms are tiny. Anything bigger is a mistake or an attempt to exhaust memory, Ktor answers it with 413. */
private const val MAX_BODY_BYTES = 16L * 1024

/** Expired links stay this long (answered with 410 instead of 404), then the cleanup deletes them. */
private val PURGE_GRACE = 30.days

private const val CONTENT_SECURITY_POLICY =
    "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

/**
 * Format of every timestamp this project prints, e.g. `19.09.2026 18:40:12 CEST`. Pinned to [Locale.ENGLISH] so the
 * zone abbreviation stays the same on every host.
 */
internal val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z", Locale.ENGLISH)

/** [millis] (Unix milliseconds) in [timestampFormat], in the host's time zone. */
internal fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timestampFormat)

/** Version and build time for the start banner, e.g. `1.0.0 (built 05.10.2026 21:40:12 CEST)`. */
internal val appVersion: String = "${BuildConfig.VERSION} (built ${formatTimestamp(BuildConfig.BUILD_TIME)})"

/**
 * Entry point. Loads the config (`config=<path>`, default `config.json` in the working directory), opens the
 * database and serves until the process is stopped. A config problem ends the process with [EXIT_CONFIG_ERROR],
 * a failed start with [EXIT_START_FAILED].
 *
 * The bare word `keys` prints a fresh `session` block for the config instead (no config needed).
 */
fun main(args: Array<String>) {
    // Klogger is the SLF4J provider, so Ktor's warnings land in the same log. Named explicitly because the
    // v0.3.0 tag of Klogger lacks the ServiceLoader file, this way the bridge works with every 0.3.0 build
    System.setProperty("slf4j.provider", "bayern.kickner.klogger.slf4j.KloggerServiceProvider")
    KLogger.configure {
        logToConsole()
        minLevel = KLogger.Level.INFO
        slf4jBridge {
            minLevel = KLogger.Level.WARN
        }
    }

    // ArgsInterpreter only knows `key=value` pairs and `-flags`, a bare word is checked directly
    if (args.contains("keys")) {
        // Keys on stdout, hint on stderr, so the output can be redirected into a file
        System.err.println("Fresh session keys for config.json. Keep them secret:")
        println(generateSessionKeys())
        return
    }

    val arguments = ArgsInterpreter(args)
    val config = when (val result = loadConfig(arguments.getValue("config") ?: "config.json")) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { result.value }
            exitProcess(EXIT_CONFIG_ERROR)
        }
    }

    val repository = runCatching { LinkRepository.open(config.databasePath) }.getOrElse { error ->
        staticLog(KLogger.Level.ERROR, TAG) { "Could not open database ${config.databasePath}: ${error.message}" }
        exitProcess(EXIT_START_FAILED)
    }
    val http = HttpClient(ClientCIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
    }

    runCatching { kshortServer(config, repository, http).start(wait = true) }.onFailure { error ->
        staticLog(KLogger.Level.ERROR, TAG) { "Could not start on ${config.listenHost}:${config.listenPort}: ${error.rootCause().message}" }
        exitProcess(EXIT_START_FAILED)
    }
}

/**
 * Builds the HTTP server and logs the banner. Ktor's own shutdown hook stops the engine, which closes the HTTP
 * client and the database.
 */
fun kshortServer(
    config: AppConfig,
    repository: LinkRepository,
    http: HttpClient,
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    staticLog(KLogger.Level.INFO, TAG) {
        "KShort $appVersion listening on ${config.listenHost}:${config.listenPort}, public URL ${config.baseUrl}"
    }
    val links = LinkService(repository, config.publicHost)
    val oidc = OidcClient(config.oidc, "${config.baseUrl}/_/callback", http)

    return embeddedServer(CIO, host = config.listenHost, port = config.listenPort) {
        kshort(config, links, oidc)
        monitor.subscribe(ApplicationStopped) {
            http.close()
            repository.close()
        }
    }
}

/**
 * Ktor module wiring plugins and routes. Kept apart from the server setup so route tests can host it with a
 * fake IdP and an in-memory database.
 *
 * @param clock Current time in Unix milliseconds, must be the same clock [links] uses.
 */
fun Application.kshort(config: AppConfig, links: LinkService, oidc: OidcClient, clock: () -> Long = System::currentTimeMillis) {
    val allowlist = Allowlist(config.allowedUsers)
    val guard = SessionGuard(allowlist, clock)

    install(DefaultHeaders) {
        header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        // Also applies to the 302 of a short link: the target does not learn which page the visitor came from
        header("Referrer-Policy", "no-referrer")
    }
    install(RequestBodyLimit) {
        bodyLimit { MAX_BODY_BYTES }
    }
    installSessions(config)

    launch {
        while (isActive) {
            runCatching { links.purgeExpired(PURGE_GRACE.inWholeMilliseconds) }
                .onSuccess { removed -> if (removed > 0) staticLog(KLogger.Level.INFO, TAG) { "Purged $removed expired link(s)" } }
                .onFailure { staticLog(KLogger.Level.ERROR, TAG) { "Purging expired links failed: ${it.message}" } }
            delay(1.hours)
        }
    }

    routing {
        staticResources("/_/static", "static")
        authRoutes(oidc, allowlist, guard, clock)
        dashboardRoutes(config.baseUrl, links, guard)
        redirectRoute(links)
    }
}

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
