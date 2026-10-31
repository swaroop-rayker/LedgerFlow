# ADR-0032 — The open-source licences list is generated at build time

- **Status:** Accepted (owner, 2026-10-31); applied the same day
- **Date:** 2026-10-31
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** —
- **Spec sections touched:** `SPEC.md` §9.3 (About); `docs/V2-PLAN.md` §3.8

## Context

Settings > About lists the open-source software LedgerFlow ships and each
licence (V2-PLAN §3.8). The list must match what is actually in the APK: about
a hundred transitive artifacts, which change with every dependency bump, in two
flavours. A list kept by hand drifts the first time a version moves.

CLAUDE.md §10 requires a new dependency to be proposed and approved, with its
size, maintenance and the reason the platform is not enough.

## Options

1. **AboutLibraries' Gradle plugin, build time only** (`com.mikepenz.aboutlibraries.plugin.android`).
   Reads the resolved runtime classpath and the POMs, and writes
   `res/raw/aboutlibraries.json` into `:app`. The runtime and Compose UI
   libraries are not used: the app reads the JSON itself and draws it with
   `LfProse`, so nothing from the plugin ships but that file.
2. **The `jk1` license-report plugin.** Also build time, but its model is
   Gradle configurations rather than Android variants, and its output is meant
   for a report rather than for an app to read.
3. **No dependency:** a hand-kept list with a test that fails when the shipped
   artifacts change. Free, but the test and the list are upkeep for every
   dependency bump.

## Decision

**Option 1** (owner, 2026-10-31), pinned to `15.3.0` (released 2026-10-13) in
`libs.versions.toml`, applied to `:app` only, with:

- `offlineMode = true`: the build never fetches licence texts or funding from
  the network, so it is the same offline, on CI and on the dev box. A licence
  with no text in its POM is shown by name and URL.
- `developers`, `funding`, `organization` and `scm` excluded from the JSON:
  About shows names, versions and licences, nothing else.
- A resource keep rule for `@raw/aboutlibraries`, which AGP 9's optimized
  resource shrinking would otherwise remove (the file is read by name).

The bundled fonts (Inter, IBM Plex Mono: OFL) and icons (Lucide: ISC) are not
Gradle artifacts; their licences are already in `assets/licenses/` and join
the list from there.

**As applied (2026-10-31).** Offline, the plugin writes names and URLs but no
licence texts: 158 libraries under six licences, 143 of them Apache 2.0. The
two standard texts the list uses, Apache 2.0 (from apache.org) and BSD
3-Clause (from SPDX), are bundled in `:feature:settings`'s assets and shown in
full; the others (the Android SDK licence, ML Kit's terms, SQLCipher's, a BSD
notice in CameraX) are shown by name and URL. The plugin gives a licence the
URL of the first library it met with it (Apache 2.0 arrived with OpenCV's), so
the app names Apache's canonical address itself.

## Consequences

- A dependency that adds a licence appears in About with no code change.
- The plugin is build tooling: it is not in the APK, and Law 6 is untouched
  (the app reads a bundled file; `offlineMode` keeps the build off the network
  too).
- On this dev box, Norton's TLS interception blocks the plugin's first download
  (as for every new dependency); it is fetched once with Norton's
  encrypted-connection scanning off, then served from Gradle's cache.
