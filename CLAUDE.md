# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

KShort, a small self-hosted URL shortener: Ktor (CIO) serves a server-rendered page at the root of the domain,
OIDC login for the people allowed to create links, SQLite for storage, one fat JAR behind a reverse proxy.
**Read [README.md](README.md) first**, it documents configuration, endpoints and behaviour in full.

It follows the conventions of the `bayern.kickner` reference project
[DemoAiProject](https://github.com/nexus421/DemoAiProject) (toolchain, code style, documentation, structure of a
service) and is built like [KNot](https://github.com/nexus421/KNot). When in doubt, those two decide.

Entry point: [Main.kt](src/main/kotlin/bayern/kickner/kshort/Main.kt), which only wires the packages together:
`config` (JSON config, validation), `cli` (the `keys` command), `link` (domain, Exposed repository on SQLite, service),
`auth` (OIDC logic and client, allowlist, sessions, CSRF guard), `routes` (Ktor routes), `web` (HTML pages).
Static CSS and JS live in `src/main/resources/static`.

## Commands

Use the Gradle wrapper (`./gradlew`), not a system-installed Gradle.

```bash
./gradlew test          # all tests, including the end-to-end test against a fake IdP
./gradlew run           # development, reads ./config.json
./gradlew buildFatJar   # build/libs/kshort.jar
```

## Things that are easy to break

- Redirects are 302 and `Cache-Control: no-store`, never 301 (browsers cache 301 forever).
- Every POST goes through `SessionGuard.authorizedForm` (session plus CSRF token).
- No inline CSS or JS in the HTML, the Content-Security-Policy forbids it.
- The ID token signature is not verified on purpose, see the KDoc of `OidcLogic.validateIdToken`. Never accept
  an ID token from anywhere but the token endpoint.
- `slf4j.provider` is set in `main` on purpose, see the comment there.
- Database access only through Exposed's DSL in `LinkRepository`. Every write is one statement, keep it that way
  (see the class KDoc). Tests use `TempDatabase`, an in-memory SQLite does not work with Exposed.

Keep README.md and KDoc in sync with the code in the same change.
