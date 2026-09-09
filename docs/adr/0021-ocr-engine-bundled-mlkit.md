# ADR-0021 — OCR is bundled ML Kit, and Law 6's `INTERNET` ban is amended for it

- **Status:** Accepted
- **Date:** 2026-09-08
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. **Amends ADR-0010** (which reserved APK
  headroom for this model) and **amends Law 6** in `CLAUDE.md` §2.
- **Spec sections touched:** `SPEC.md` §5.3, §11, §16 Q2, Q10, Q18; `CLAUDE.md` §2 Law 6

## Context

P4 needs on-device text recognition. `SPEC.md` §11 already argued the shape of
the answer — bundled model, not the Play-Services-backed unbundled one, because
the unbundled variant fetches models over the network on first use and requires
Play Services, and both are incompatible with Law 6 and with sideloaded
`smsFull` distribution. It left two things open: the **measured** APK cost
(Q10), and whether to bundle Devanagari as well as Latin (Q2). Q18 additionally
recorded that the CI size gate measured the wrong artefact entirely, so the
budget had never actually been measured.

The measurement changed more than the numbers.

## What was measured

All figures are the **arm64-v8a release split** — R8 and resource shrinking on,
one ABI — which is the artefact §11's budget names. Built on the dev box.

| build | APK | Δ |
|---|---|---|
| baseline, no OCR | **4.82 MB** | — |
| + `com.google.mlkit:text-recognition` (bundled Latin) | **17.17 MB** | **+12.35** |
| + `text-recognition-devanagari` | **17.78 MB** | +0.61 |

**The cost is one file and it is not the model.**
`libmlkit_google_ocr_pipeline.so` is **10.55 MB** — five times the whole model
asset set (1.84 MB), and by a wide margin the largest single item in the app
(SQLCipher's arm64 `.so` is 2.00 MB). §11's estimate of "several MB" was low by
roughly 4×.

**Devanagari is nearly free** because it reuses that same native pipeline and
adds only `.tflite` assets. §16 Q2 assumed ~2 MB; the real figure is 0.61 MB,
which makes the language question a corpus question rather than a budget one.

### Amendment, on the owner's instruction: which scripts are actually possible

Asked at P4 to add "Devanagari + Kannada + Hindi + Malayalam". Probed directly
against `dl.google.com` rather than assumed, at `16.0.1`:

| requested | artifact | result |
|---|---|---|
| Devanagari | `text-recognition-devanagari` | **resolves** — added |
| Hindi | `text-recognition-hindi` | **does not exist** |
| Kannada | `text-recognition-kannada` | **does not exist** |
| Malayalam | `text-recognition-malayalam` | **does not exist** |

**ML Kit's models are per *script*, not per language.** That single fact
resolves half the request and blocks the other half.

*Hindi is written in Devanagari*, so the Devanagari model **is** Hindi support —
and Marathi, Nepali, Sanskrit and Konkani along with it, from the same 0.61 MB.
There is no separate artifact to add and nothing missing.

**Kannada and Malayalam each have their own script, and ML Kit has no model for
either.** The complete set it ships is Latin, Chinese, Devanagari, Japanese and
Korean. This is a hard capability limit, not a configuration or budget one.

What that leaves, none of which is taken here:

- **A second engine for those two scripts.** Tesseract has `kan` and `mal`
  traineddata. It would reopen this ADR, add a native library on top of the
  10.55 MB already spent, and is materially worse on thermal receipts — which is
  the substrate that matters. A two-engine pipeline also doubles the surface the
  corpus has to characterise.
- **A cloud OCR API.** Rejected outright: it violates Law 6's *substantive* half,
  which this ADR was careful to leave intact. The permission is a side effect of
  a dependency; sending a user's receipt to a server would be the thing itself.
- **Wait for ML Kit.** Costs nothing and may never arrive.

**How much this matters is an empirical question the corpus answers.** Indian
retail receipts — including in Karnataka and Kerala — overwhelmingly print the
item block and the amounts in Latin script and Latin digits; regional script,
where it appears, is usually the shop name in the header. Since §12's gate
measures **item** recall, the practical cost may be near zero. It may not be.
`SPEC.md` §12's diversity floor should therefore gain a Kannada-bearing and a
Malayalam-bearing receipt, not because they can be recognised but because they
are what would show whether anything important is being lost.

**Both scripts run on every page, concurrently.** Sequential passes would make
the wall-clock cost their sum against §11's unmeasured 2.5 s budget; `async`
makes it roughly their max. Both models read Latin digits, so their outputs are
merged with an overlap rule (`RecognizedPage.merge`) — without it every amount
on an ordinary receipt would appear twice, which reads as a bill costing double
and is unit-tested off-device precisely because it is that consequential.

### The finding that was not being looked for

Bundled ML Kit **merges `android.permission.INTERNET`** into the release
manifest. Not the model downloader — the source is
`com.google.android.datatransport:transport-backend-cct`, Google's Clearcut
telemetry uploader, pulled in transitively by `mlkit:common` and
`vision-common`, which also registers a `TransportBackendDiscovery` service.

So the bundled path — chosen *because* it needs no network — ships an analytics
uploader and the permission to use it, into an app whose §1 privacy position and
Law 6 both say otherwise.

**Nothing in the build would have said so.** `restrictedPermissionCheck` and its
`ci.yml` mirror both read *source* manifests; this arrives in the *merged* one.
That gap is fixed separately and is the precondition for this ADR being
enforceable at all.

## Options considered

### Option A — Unbundled (`play-services-mlkit-text-recognition`)

| | |
|---|---|
| Summary | Models fetched from Play Services on first use |
| Cost | negligible APK |
| Risk | **Disqualified.** Requires `INTERNET` *for recognition itself*, and requires Play Services, so a sideloaded `smsFull` install on a de-Googled device cannot read a receipt at all. |

Not a trade-off, an elimination. Recorded because §11 tabled it and the reader's
next question is whether the measurement changed that.

### Option B — Bundled, telemetry dependency excluded

| | |
|---|---|
| Summary | `exclude(group = "com.google.android.datatransport")` |
| Cost | none in principle |
| Risk | **Tested: R8 aborts.** `mlkit_common` and `mlkit_vision_text_common` reference `TransportFactory`, `CCTDestination`, `TransportRuntime` and others directly. Reachable only by adding `-dontwarn` rules, which would then fail at runtime instead of at build time. |

### Option C — Bundled, `INTERNET` removed by the manifest merger

| | |
|---|---|
| Summary | `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>` |
| Cost | zero bytes |
| Risk | **Tested: works.** `aapt2` confirms the resulting APK declares no `INTERNET`. The telemetry code remains in the binary and its uploads fail. |

### Option D — Bundled, `INTERNET` accepted and Law 6 amended

| | |
|---|---|
| Summary | Ship as the dependency merges it; pin it deliberately |
| Cost | Law 6 is no longer true as written; `playSafe` inherits it and will need a Play data-safety declaration |
| Risk | The uploader can actually reach the network |

### Option E — A different OCR engine

Rejected in one line, because the reader will ask: there is no credible
on-device Latin + Devanagari receipt OCR for Android outside ML Kit. Tesseract
wrappers are unmaintained and materially worse on thermal receipts, and every
other candidate is a network API, which loses to Option A's disqualification
before it starts.

## Decision

**Bundle ML Kit text recognition (Latin now, Devanagari when the corpus shows it
is needed), accept the `INTERNET` permission that comes with it, amend Law 6
accordingly, and move the APK budget from 15 MB to 25 MB.**

Option D over Option C, on the owner's explicit instruction, after being shown
that C was tested and works.

**The argument that decided the engine** is Option A's disqualification, not the
size: an offline expense tracker that cannot read a receipt without a network
has lost the feature, not optimised it.

**The argument that decided the budget** is that §11 pre-authorised exactly this
outcome — "if measurement at P4 shows 15 MB is unreachable with the bundled
model, the budget moves, not the principle" — and 25 MB leaves P5 room rather
than parking the app on a new ceiling. The owner's stated tolerance is well
above this; the native library is `mmap`ed and demand-paged, so it costs install
size rather than the §11 150 MB PSS target.

**This is a close decision on the `INTERNET` half and reopening it is cheap.**
Option C is one line, verified, and costs nothing; the only thing between the
two is a judgement about whether a dormant uploader in the binary is worse than
the permission that lets it work. Recorded plainly so that a future reader — or
a future owner of a Play listing — can flip it without redoing any analysis.

### What is *not* amended

**Recognition is 100% on-device and that is structural, not a policy.** The
recogniser is in the APK: a 10.55 MB inference pipeline plus `.tflite` models.
A receipt image is never uploaded to be read. Law 6's substantive promise — "all
parsing, OCR and analytics are on-device" — survives intact. What is amended is
only the *mechanical* rule "no `INTERNET` permission in release", which was a
proxy for that promise and can no longer be one.

The distinction matters and must be preserved in how the app describes itself:
image processing is local by construction, and the residual network surface is
a third-party telemetry channel that Google documents as carrying API-usage
metadata rather than content. **That last clause is Google's documentation, not
our observation** — it has not been packet-captured here.

## Consequences

**What this makes easy.** Receipt OCR that works in airplane mode, on a
sideloaded install, on a device with no Play Services account. Devanagari for
+0.61 MB whenever the corpus asks for it.

**What this makes hard.** Law 6 can no longer be checked by grepping for a
permission name, which was its whole appeal. The replacement is a pinned merged
manifest plus a runtime test, both of which are more machinery than a grep. The
`playSafe` Play listing acquires a data-safety declaration it did not need.
And an unverified claim now sits in the dependency graph:
`mlkit:text-recognition` depends on `play-services-mlkit-text-recognition`,
`play-services-base` and `play-services-basement`, so §11's "bundled → requires
Play Services: no" is **unverified rather than wrong**, and only a de-Googled
device settles it.

**What we now have to maintain forever.** `EXPECTED_MERGED_PERMISSIONS`, which
must record `INTERNET` from the commit that adds ML Kit onward — and must not
quietly acquire a second entry. The ML Kit version pin in
`gradle/libs.versions.toml`, which is a Google binary blob and therefore a
CVE-tracking obligation the rest of this codebase deliberately avoided
(ADR-0010's "net new dependencies: zero" no longer holds).

**What would make us revisit this.** A measured arm64 release split above
25 MB. A de-Googled-device test showing the bundled path calls into Play
Services at runtime — that would reopen the *engine*, not just this ADR. Any
evidence that the telemetry channel carries more than usage metadata, which
should flip the decision to Option C the same day. Or a Play listing review that
objects to the declaration.

## Verification

- **`mergedPermissionCheck{SmsFull,PlaySafe}{Debug,Release}`** — pins the exact
  permission set in the packaged manifest, so `INTERNET` appearing is a
  deliberate act recorded in `EXPECTED_MERGED_PERMISSIONS`, and a *second*
  unexpected permission fails the build. Wired into `preMergeCheck`. Proven to
  fail in both directions (extra permission, and stale pin).
- **`OcrRunsWithoutNetworkTest`** — instrumented; runs recognition with the
  radio off and asserts extraction still succeeds. This is what turns
  "on-device" from a claim into a build-enforced property, and it is the test
  that would catch a future ML Kit version silently moving to a server. Lands
  with the first OCR code.
- **The APK size gate** (`ci.yml`) now builds and measures the arm64 release
  split against 25 MB, and fails when it finds no artefact.
