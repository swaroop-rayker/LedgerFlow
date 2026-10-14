#!/usr/bin/env bash
# test-guard-version.sh — BUG52's regression test for guard-version.sh.
#
# guard-version.sh compared a tag build against the newest tag, which on a tag
# build is the tag being released, so every release stopped at its first step
# ("HEAD=1, v0.1.0=1", 2026-10-14). This builds throwaway repositories and runs
# the real script in each case that matters, so the fix cannot quietly regress.
# Runs locally (CLAUDE.md §11) and in CI's guards job.

set -euo pipefail

GUARD="$(cd "$(dirname "$0")" && pwd)/guard-version.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAIL=0

repo() {  # repo <name>: a fresh repository with versionCode=1 committed
  local dir="$WORK/$1"
  git init -q "$dir"
  git -C "$dir" config user.email test@example.invalid
  git -C "$dir" config user.name test
  printf 'versionCode=1\nversionName=0.1.0\n' > "$dir/version.properties"
  git -C "$dir" add version.properties
  git -C "$dir" commit -qm one
  echo "$dir"
}

expect() {  # expect <pass|fail> <description> <dir> [GITHUB_REF_NAME]
  local want="$1" what="$2" dir="$3" ref="${4:-}" got
  if (cd "$dir" && GITHUB_REF_NAME="$ref" bash "$GUARD" >/dev/null 2>&1); then got=pass; else got=fail; fi
  if [ "$got" = "$want" ]; then echo "  ok   $what"; else echo "  FAIL $what (wanted $want, got $got)"; FAIL=1; fi
}

echo "── guard-version.sh regression tests (BUG52) ─────────────────────"

# The bug: the first release, built from its own tag.
d=$(repo first-release); git -C "$d" tag v0.1.0
expect pass "a release built from its own tag is not compared with itself" "$d" v0.1.0

# The guard's purpose, unchanged: a later commit must raise versionCode.
d=$(repo not-raised); git -C "$d" tag v0.1.0
git -C "$d" commit -q --allow-empty -m two
expect fail "a later commit with the same versionCode still fails" "$d"

d=$(repo raised); git -C "$d" tag v0.1.0
printf 'versionCode=2\nversionName=0.2.0\n' > "$d/version.properties"; git -C "$d" commit -qam two
expect pass "a later commit with a higher versionCode passes" "$d"

# The second release, built from its tag, compared with the first.
git -C "$d" tag v0.2.0
expect pass "the second release, from its tag, is compared with the first" "$d" v0.2.0

d=$(repo second-not-raised); git -C "$d" tag v0.1.0
git -C "$d" commit -q --allow-empty -m two; git -C "$d" tag v0.2.0
expect fail "a second release that did not raise versionCode still fails" "$d" v0.2.0

[ "$FAIL" -eq 0 ] && echo "OK: guard-version.sh behaves (BUG52)." || echo "guard-version.sh regression tests FAILED."
exit "$FAIL"
