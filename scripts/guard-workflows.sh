#!/usr/bin/env bash
# guard-workflows.sh — every GitHub Actions workflow parses (SPEC.md §8 BUG32)
#
# Why this exists
# ---------------
# From 2026-09-08 to 2026-09-23 CI did not run a single job. `4238611` wrote a
# `printf '...\n'` into ci.yml with the `\n` as a real newline, so the next line
# began at column 0 and ended the `run: |` block mid-script. GitHub will not load
# a workflow it cannot parse, and it says so in the quietest way it has: every
# push produced a run that "failed" in zero seconds with no jobs at all. From the
# outside that looks like an ordinary red CI, which is exactly how two weeks of
# it went unread.
#
# Why it runs locally, before a push
# ----------------------------------
# A check inside ci.yml cannot catch ci.yml being broken: the file that would
# run the check is the file that does not load. So this is one of the guards
# CLAUDE.md §11 says to run before pushing. It also runs in the `guards` job,
# where it can still catch the *other* workflows (release.yml) breaking.
#
# What is checked
# ---------------
# Each .github/workflows/*.yml:
#   - parses as YAML;
#   - is a mapping with a non-empty `jobs` mapping and a trigger (`on`, which
#     YAML 1.1 reads as the boolean key True — accepted either way);
#   - every job has `runs-on` or `uses`, and every step has `run` or `uses`.
# That is structure, not semantics: an expression GitHub rejects (`secrets` in
# a job-level `if`, say) still gets through. It is aimed at the failure that
# actually happened, and at the class it belongs to.
#
# Plus one semantic check, BUG45 (SPEC.md §8): ci.yml's screenshot and
# unit-test commands keep `--continue` and the arguments that stop a known red
# module from hiding the rest, and :feature:ocr still honours goldensOnly.
#
# A guard that cannot check must not pass
# ---------------------------------------
# It needs a Python with PyYAML. If none is found it FAILS rather than printing
# OK over nothing — this repository has recorded four guards that could not see
# the thing they checked.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

PY=""
for candidate in python3 python py; do
  # The Windows Store stub answers to `python3` and exits non-zero; asking for
  # the import is what tells a real interpreter from it.
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import yaml' >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done

if [ -z "$PY" ]; then
  echo "::error::guard-workflows: no Python with PyYAML found (tried python3, python, py)."
  echo "         Install it (pip install pyyaml) — this guard does not pass unchecked."
  exit 1
fi

shopt -s nullglob
FILES=(.github/workflows/*.yml .github/workflows/*.yaml)
if [ "${#FILES[@]}" -eq 0 ]; then
  echo "::error::guard-workflows: no workflow files found under .github/workflows."
  exit 1
fi

"$PY" - "${FILES[@]}" <<'PY'
import sys
import yaml

failed = False
for path in sys.argv[1:]:
    problems = []
    try:
        with open(path, encoding="utf-8") as f:
            doc = yaml.safe_load(f)
    except yaml.YAMLError as e:
        problems.append("does not parse: " + " ".join(str(e).split()))
        doc = None
    if doc is not None:
        if not isinstance(doc, dict):
            problems.append("is not a mapping")
        else:
            if "on" not in doc and True not in doc:
                problems.append("has no trigger ('on')")
            jobs = doc.get("jobs")
            if not isinstance(jobs, dict) or not jobs:
                problems.append("has no jobs")
            else:
                for name, job in jobs.items():
                    if not isinstance(job, dict):
                        problems.append(f"job '{name}' is not a mapping")
                        continue
                    if "runs-on" not in job and "uses" not in job:
                        problems.append(f"job '{name}' has neither runs-on nor uses")
                    for i, step in enumerate(job.get("steps") or []):
                        if not isinstance(step, dict) or ("run" not in step and "uses" not in step):
                            problems.append(f"job '{name}' step {i + 1} has neither run nor uses")
    if problems:
        failed = True
        for p in problems:
            print(f"::error::{path} {p}")
    else:
        print(f"  {path}: {len(doc['jobs'])} job(s)")

if failed:
    print("")
    print("Workflow guard FAILED. GitHub will not load a workflow like this; every")
    print("push would \"fail\" in zero seconds with no jobs run (BUG32).")
    sys.exit(1)

# BUG45: a job that stops at its first red module compares nothing after it.
# From 537fb17 the screenshot job stopped at ReceiptCorpusTest (red in CI until
# the private corpus reaches it) and never compared onboarding's or settings'
# goldens, while the unit-test jobs never ran :core:model:test at all. Each
# command below must keep the arguments that closed those holes.
REQUIRED = {
    "screenshot": ("verifyRoborazzi", ["--continue", "-Pledgerflow.goldensOnly"]),
    "unit-test": ("DebugUnitTest", [":core:model:test", "--continue"]),
}
CI = ".github/workflows/ci.yml"
with open(CI, encoding="utf-8") as f:
    ci_jobs = (yaml.safe_load(f) or {}).get("jobs") or {}
for job, (marker, args) in REQUIRED.items():
    runs = [s.get("run", "") for s in (ci_jobs.get(job) or {}).get("steps") or [] if isinstance(s, dict)]
    commands = [r for r in runs if marker in r]
    if not commands:
        failed = True
        print(f"::error::{CI} job '{job}' has no step running {marker} (BUG45)")
    for command in commands:
        for arg in args:
            if arg not in command.split():
                failed = True
                print(f"::error::{CI} job '{job}' runs {marker} without {arg} (BUG45)")

# The property is only worth passing if the build still reads it.
OCR = "feature/ocr/build.gradle.kts"
with open(OCR, encoding="utf-8") as f:
    ocr = f.read()
if 'gradleProperty("ledgerflow.goldensOnly")' not in ocr or "ReceiptCorpusTest" not in ocr:
    failed = True
    print(f"::error::{OCR} no longer excludes ReceiptCorpusTest under ledgerflow.goldensOnly (BUG45)")

# BUG46: release.yml runs only on a tag, and it shipped unsigned builds and
# named Gradle tasks that never existed, unseen, because nothing ran it. Every
# task it names must be dry-run (-m) by ci.yml's static-analysis job, and every
# command that builds or checks a release must require signing.
def gradle_commands(text):
    """Each ./gradlew invocation in a script, continuation lines joined, as tokens."""
    joined = []
    for line in text.splitlines():
        if joined and joined[-1].rstrip().endswith(chr(92)):
            joined[-1] = joined[-1].rstrip()[:-1] + " " + line
        else:
            joined.append(line)
    out = []
    for line in joined:
        tokens = line.split()
        if "./gradlew" in tokens:
            out.append(tokens[tokens.index("./gradlew") + 1:])
    return out

def scripts_of(jobs):
    for job in jobs.values():
        for step in (job or {}).get("steps") or []:
            if isinstance(step, dict):
                yield step.get("run", "") or ""
                yield ((step.get("with") or {}).get("script", "")) or ""

REL = ".github/workflows/release.yml"
with open(REL, encoding="utf-8") as f:
    rel_jobs = (yaml.safe_load(f) or {}).get("jobs") or {}
rel_commands = [c for s in scripts_of(rel_jobs) for c in gradle_commands(s)]
rel_tasks = {t for c in rel_commands for t in c if not t.startswith("-")}
dry_run = {t for s in scripts_of({"static-analysis": ci_jobs.get("static-analysis")})
           for c in gradle_commands(s) if "-m" in c for t in c if not t.startswith("-")}
if not rel_tasks:
    failed = True
    print(f"::error::{REL} names no Gradle task; the guard cannot see it (BUG46)")
for task in sorted(rel_tasks - dry_run):
    failed = True
    print(f"::error::{REL} runs '{task}', which ci.yml's static-analysis dry run (-m) does not resolve (BUG46)")
# Since 2026-10-02 the Galaxy Store upload is smsFull's arm64 split, so a
# command that builds smsFull's release needs -Pledgerflow.abiSplits. And AGP
# refuses to build an AAB with splits on, so a command that bundles must not
# have it -- which is why release.yml builds in two runs.
for c in rel_commands:
    if not any("Release" in t for t in c):
        continue
    joined = " ".join(c)
    if "-Pledgerflow.requireReleaseSigning" not in c:
        failed = True
        print(f"::error::{REL} builds a release without -Pledgerflow.requireReleaseSigning: {joined} (BUG46)")
    if "SmsFullRelease" in joined and "-Pledgerflow.abiSplits" not in c:
        failed = True
        print(f"::error::{REL} builds smsFull's release without -Pledgerflow.abiSplits, so no Galaxy APK: {joined}")
    if any(t.startswith("bundle") for t in c) and "-Pledgerflow.abiSplits" in c:
        failed = True
        print(f"::error::{REL} bundles with -Pledgerflow.abiSplits, which AGP refuses: {joined}")

APP = "app/build.gradle.kts"
with open(APP, encoding="utf-8") as f:
    app = f.read()
if 'gradleProperty("ledgerflow.requireReleaseSigning")' not in app or 'signingConfigs.findByName("release")' not in app:
    failed = True
    print(f"::error::{APP} no longer signs release from keystore.properties, or no longer honours requireReleaseSigning (BUG46)")

if failed:
    print("")
    print("Workflow guard FAILED (BUG45): a CI job would stop at its first red module,")
    print("or skip one, and report nothing about the rest.")
    sys.exit(1)
PY

echo "OK: workflow guard passed (${#FILES[@]} file(s))."
