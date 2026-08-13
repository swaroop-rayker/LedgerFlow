#!/usr/bin/env bash
# guard-version.sh — versionCode monotonicity gate (SPEC.md §15.6, BUG3)
#
# Android refuses to install an APK whose versionCode is <= the installed one,
# unless the user uninstalls first — which destroys local data. A non-monotonic
# versionCode therefore doesn't just fail to install; it pressures the user into
# an uninstall/reinstall cycle, which is exactly BUG1 + BUG3 compounding.
#
# This is the build-time half of the guard. The runtime half is AppVersionGuard
# (SPEC.md §8, BUG3), which blocks writes if it detects a downgrade on-device.

set -euo pipefail

VERSION_FILE="version.properties"
FAIL=0

echo "── versionCode guard ──────────────────────────────────────────────"

if [ ! -f "$VERSION_FILE" ]; then
  echo "::error::$VERSION_FILE not found. versionCode must live in a single committed file."
  exit 1
fi

CURRENT=$(grep -E '^versionCode=' "$VERSION_FILE" | cut -d= -f2 | tr -d '[:space:]')

if ! [[ "$CURRENT" =~ ^[0-9]+$ ]]; then
  echo "::error::versionCode in $VERSION_FILE is not an integer: '$CURRENT'"
  exit 1
fi

echo "HEAD versionCode: $CURRENT"

# Compare against the most recent release tag.
git fetch --tags --quiet 2>/dev/null || true
LAST_TAG=$(git tag -l 'v*.*.*' --sort=-v:refname | head -1 || true)

if [ -z "$LAST_TAG" ]; then
  echo "No prior release tag. Skipping comparison (first release)."
else
  PREV=$(git show "$LAST_TAG:$VERSION_FILE" 2>/dev/null \
         | grep -E '^versionCode=' | cut -d= -f2 | tr -d '[:space:]' || echo "0")
  echo "Last tag $LAST_TAG versionCode: $PREV"

  if [ "$CURRENT" -le "$PREV" ]; then
    echo "::error::versionCode must strictly increase. HEAD=$CURRENT, $LAST_TAG=$PREV."
    echo "  A non-increasing versionCode makes install-over-install impossible,"
    echo "  which forces an uninstall and destroys local data (BUG1/BUG3)."
    FAIL=1
  fi
fi

# On a tag build, the tag's semver must not regress either.
if [ -n "${GITHUB_REF_NAME:-}" ] && [[ "${GITHUB_REF_NAME}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  PREV_TAG=$(git tag -l 'v*.*.*' --sort=-v:refname | grep -v "^${GITHUB_REF_NAME}$" | head -1 || true)
  if [ -n "$PREV_TAG" ]; then
    NEWEST=$(printf '%s\n%s\n' "$PREV_TAG" "$GITHUB_REF_NAME" | sort -V | tail -1)
    if [ "$NEWEST" != "$GITHUB_REF_NAME" ]; then
      echo "::error::Tag $GITHUB_REF_NAME is older than $PREV_TAG. Refusing to release a rollback."
      FAIL=1
    fi
  fi
fi

if [ "$FAIL" -eq 0 ]; then
  echo "OK: versionCode guard passed."
fi
exit "$FAIL"
