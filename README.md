# KShort

[![Tests](https://github.com/nexus421/KShort/actions/workflows/test.yml/badge.svg)](https://github.com/nexus421/KShort/actions/workflows/test.yml)
![Kotlin](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKShort%2Fmaster%2Fbuild.gradle.kts&search=kotlin%5C%28%22jvm%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Kotlin&logo=kotlin&logoColor=white&color=7F52FF)
![Ktor](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKShort%2Fmaster%2Fbuild.gradle.kts&search=id%5C%28%22io%5C.ktor%5C.plugin%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Ktor&logo=ktor&logoColor=white&color=087CFA)
![JDK](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKShort%2Fmaster%2Fbuild.gradle.kts&search=JavaLanguageVersion%5C.of%5C%28%28%5Cd%2B%29%5C%29&replace=%241%20%28Corretto%29&label=JDK&logo=openjdk&logoColor=white&color=ED8B00)

**K**otlin **Short**ener: a small self-hosted URL shortener. Trusted users log in through OpenID Connect and turn
long URLs into short ones with a lifetime of 7, 14, 30 or 365 days or unlimited. Everybody else can only open
the short links: KShort answers with a redirect to the original, or with an error page if the link has expired
or never existed.

KISS by design: one page at the root of the domain, server-rendered HTML, one SQLite file (through Exposed), one JSON config file,
one fat JAR, one systemd service. No anonymous shortening (an open shortener is a phishing magnet and gets the
domain blocklisted). TLS termination is left to a reverse proxy (e.g. Zoraxy, Caddy). KShort itself listens on
plain HTTP, by default only on `127.0.0.1`.

> **Disclaimer:** KShort is vibe coded, written largely with an AI assistant, and is a proof of concept rather than
> a hardened product. It has tests and a review behind it, but no independent security audit. Use it at your own
> risk, and read the code before you put it in front of the internet.

## Quick start

Requirements: JDK 25 to build (Amazon Corretto is the pinned toolchain, Gradle downloads it if missing), a Java 25
runtime on the machine that runs `kshort.jar`, and an OIDC provider (Authentik, Keycloak, Pocket ID, ...) with
a confidential client for KShort, see [Deployment](#deployment).

```bash
cp config.example.json config.json   # then fill in publicUrl, allowedUsers and the oidc block
./gradlew run --args="keys"          # prints a fresh "session" block, paste it into config.json
./gradlew run                        # development: reads ./config.json
./gradlew buildFatJar                # production: build/libs/kshort.jar
java -jar build/libs/kshort.jar config=/path/to/config.json
```

Tests: `./gradlew test`, including an end-to-end test of the whole login against a fake IdP. GitHub Actions
runs them on every push (`.github/workflows/test.yml`).

## Command line

| Argument        | Meaning                                                                                  |
|-----------------|------------------------------------------------------------------------------------------|
| `config=<path>` | Config file, default `config.json` in the working directory.                             |
| `keys`          | Prints a fresh `session` block with random keys and exits. Needs no config.               |

| Exit code | Meaning                                                                                                     |
|-----------|-------------------------------------------------------------------------------------------------------------|
| `0`       | `keys` printed the keys.                                                                                    |
| `1`       | The server could not start: the port is taken, the address is not assigned (yet) or the database could not be opened. systemd retries, see Deployment. |
| `78`      | The config file is missing or invalid (`EX_CONFIG`). systemd does not retry.                                |
| `143`     | The JVM's exit code after SIGTERM (`systemctl stop`). A clean stop, not a failure.                          |

## Configuration

One JSON file, read once at startup, so restart after a change. A syntax error is reported with its position,
validation problems all at once, and unknown keys are errors too, so a typo cannot silently fall back to a
default. Error messages never contain secret values. In every case KShort exits with code 78.

| Field          | Type           | Default            | Description                                                                                       |
|----------------|----------------|--------------------|---------------------------------------------------------------------------------------------------|
| `listenHost`   | String         | `"127.0.0.1"`      | Interface to bind to. Use a private or VPN address if the reverse proxy runs on another machine.  |
| `listenPort`   | Int            | `8080`             | Port to listen on.                                                                                |
| `publicUrl`    | String         | required           | Public base URL without path, e.g. `https://s.example.de`. Short links and the OIDC callback are built from it. |
| `databasePath` | String         | `"data/kshort.db"` | SQLite file. Parent directories are created.                                                      |
| `allowedUsers` | List\<String\> | required           | Who may create links: `sub`, `preferred_username` or verified e-mail. `["*"]` allows every user of the IdP. Only `sub` is guaranteed to be stable, so use `preferred_username` or e-mail only if users cannot change them at the IdP. |
| `oidc`         | Object         | required           | See below.                                                                                        |
| `session`      | Object         | required           | See below.                                                                                        |

`oidc`:

| Field          | Type   | Default                  | Description                                                                          |
|----------------|--------|--------------------------|--------------------------------------------------------------------------------------|
| `issuer`       | String | required                 | Exactly as the IdP's discovery document reports it, including a trailing slash if any. https only. |
| `clientId`     | String | required                 | Client id of the confidential client.                                                |
| `clientSecret` | String | required                 | Client secret. Never logged.                                                         |
| `scopes`       | String | `"openid profile email"` | Must contain `openid`.                                                               |

`session`, generated by `java -jar kshort.jar keys`:

| Field           | Type   | Default  | Description                                                     |
|-----------------|--------|----------|-----------------------------------------------------------------|
| `encryptKeyHex` | String | required | AES key for the session cookies, 16 (or 24, 32) bytes as hex.   |
| `signKeyHex`    | String | required | HMAC-SHA256 key for the session cookies, at least 32 bytes as hex. |

New keys log everybody out, nothing else is lost.

## Endpoints

Everything lives at the root of the domain. Internal paths start with `/_/`, which can never collide with a short
code (codes and aliases only consist of letters, digits and hyphens).

| Method and path                 | Auth            | Description                                                                 |
|---------------------------------|-----------------|-----------------------------------------------------------------------------|
| `GET /`                         | none or session | Login button without session, the dashboard with session.                   |
| `GET /{code}`                   | none            | `302` to the target, `410` if expired, `404` if unknown. `Cache-Control: no-store`. |
| `GET /_/login`                  | none            | Starts the OIDC login.                                                      |
| `GET /_/callback`               | none            | OIDC redirect URI, register exactly this at the IdP.                        |
| `POST /_/logout`                | session, CSRF   | Ends the local session.                                                     |
| `POST /_/links`                 | session, CSRF   | Creates a link. Form fields `url`, `ttl` (`D7`, `D14`, `D30`, `D365`, `FOREVER`), `alias` (optional). |
| `POST /_/links/{code}/extend`   | session, CSRF   | New lifetime `ttl`, counted from now. Also revives an expired link.         |
| `POST /_/links/{code}/delete`   | session, CSRF   | Deletes the link.                                                           |
| `GET /_/static/app.css`, `.js`  | none            | Stylesheet and the script for copy buttons and the delete confirmation.     |
| `GET /robots.txt`               | none            | Disallows everything.                                                       |

## Behaviour

**Links.** A generated code has 7 characters from Base62 without easily confused characters (`0`, `O`, `1`,
`l`, `I`). A custom alias has 3 to 40 characters `a-z`, `0-9` and `-` (not at either end) and is lower-cased.
Targets must be `http` or `https` with a real host, without `user@` part and not on KShort's own host. Every
user sees and manages only their own links (owner is the OIDC `sub`). Every redirect counts as a hit.

**Expiry.** An expired link answers `410 Gone` for 30 more days, then an hourly cleanup deletes it and it answers
`404`. Its code becomes free again only then. Extending sets the new lifetime from the current time.

**Login.** Authorization Code Flow with PKCE (S256), `state` and `nonce`. The ID token is taken only from the
token endpoint response, its claims (`iss`, `aud`, `azp`, `exp`, `nonce`) are validated. Its signature is not,
which OpenID Connect Core 1.0 section 3.1.3.7 allows for tokens received directly from the token endpoint over
TLS. A session lasts 7 days and lives in an AES-encrypted, HMAC-signed cookie (HttpOnly, SameSite=Lax, Secure on
https). The allowlist is checked at login and on every request. The discovery document is loaded on the first
login, so redirects keep working while the IdP is down.

**Security headers.** A strict Content-Security-Policy (no inline CSS or JS), `X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, which also keeps the referring page from
the redirect target. Forms carry a per-session CSRF token. Request bodies over 16 KiB are answered with `413`.

**Logging.** Everything goes to stdout and stderr (Klogger), which systemd forwards to the journal. Ktor's own
SLF4J output is routed into Klogger as well, at level WARN and above. Every timestamp uses the format
`dd.MM.yyyy HH:mm:ss z` in the host's time zone, e.g. `05.10.2026 21:40:12 CEST`. Secrets are never logged.

## Deployment

1. **IdP.** Create a confidential OIDC client (Authentik: Provider type "OAuth2/OpenID", client type
   "Confidential"). Redirect URI `https://s.example.de/_/callback`, scopes `openid profile email`. Copy issuer,
   client id and secret into the config. Restrict access at the IdP as well if it supports that.
2. **Server.**
   ```bash
   mkdir -p /root/kshort && cp build/libs/kshort.jar /root/kshort/
   chmod 711 /root   # the throwaway service user (DynamicUser) must be able to reach the jar
   # /root/kshort/config.json must exist with databasePath "/var/lib/kshort/kshort.db", chmod 600 it
   cp kshort.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now kshort
   journalctl -u kshort -f
   ```
3. **Reverse proxy.** Route the whole domain to `listenHost:listenPort` and terminate TLS there (Zoraxy, Caddy).
   KShort builds every absolute URL from `publicUrl` and reads no forwarded headers, so nothing else needs to be
   configured. Keep `listenHost` on an address only the proxy can reach.

Updating means replacing the jar and `systemctl restart kshort`. Backups: copy `/var/lib/kshort/` (with the
service stopped, or use `sqlite3 kshort.db ".backup copy.db"`).

## Not in scope (deliberately)

- Anonymous shortening, rate limiting of creation (only trusted users create links).
- Saving a copy of the target page. Possible later behind an `Archiver` interface (`monolith` or headless
  Chromium), postponed.
- Statistics beyond the hit counter, QR codes, an API, admin views of other users' links.
- RP-initiated logout at the IdP. Logout ends only the KShort session.
