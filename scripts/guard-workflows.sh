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
PY

echo "OK: workflow guard passed (${#FILES[@]} file(s))."
