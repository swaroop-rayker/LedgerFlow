#!/usr/bin/env bash
# guard-schema.sh — Room schema immutability gate (SPEC.md §15.5, BUG8)
#
# Rules enforced:
#   1. An already-committed schema JSON may never be MODIFIED. Once a schema
#      version ships, a released build's migration path depends on it. Editing
#      it retroactively makes that build's upgrade path a lie.
#   2. A NEW schema JSON requires a matching Migration class AND a test in the
#      same commit range.
#   3. fallbackToDestructiveMigration() may not appear outside dev source sets.
#
# Run locally before pushing:  bash scripts/guard-schema.sh

set -euo pipefail

SCHEMA_DIR="core/database/schemas"
FAIL=0

# Resolve the base commit to diff against.
if [ -n "${GITHUB_BASE_REF:-}" ]; then
  git fetch --no-tags --depth=50 origin "$GITHUB_BASE_REF" >/dev/null 2>&1 || true
  BASE="origin/$GITHUB_BASE_REF"
elif git rev-parse --verify origin/main >/dev/null 2>&1; then
  BASE="origin/main"
else
  echo "No base ref to compare against; skipping schema guard."
  exit 0
fi

MERGE_BASE=$(git merge-base "$BASE" HEAD)

echo "── Schema guard (base: $BASE) ─────────────────────────────────────"

# ── Rule 1: no modifications to existing schema files ──────────────────
MODIFIED=$(git diff --diff-filter=M --name-only "$MERGE_BASE"...HEAD -- "$SCHEMA_DIR" || true)
if [ -n "$MODIFIED" ]; then
  echo "::error::Committed Room schemas are IMMUTABLE. These were modified:"
  echo "$MODIFIED"
  echo ""
  echo "  If the schema needs to change, bump the DB version and add a NEW"
  echo "  schema JSON plus a Migration. Never edit a shipped one."
  FAIL=1
fi

# ── Rule 2: new schema requires a migration + a test ───────────────────
#
# v1 is exempt: it is created by Room's createAllTables(), not by a migration.
# Demanding a "Migration_0_1" would make the very first schema commit
# unpassable. Every version from v2 onward must carry both a Migration class
# and a MigrationTestHelper test.
#
# The test lookup matches the MIGRATION CLASS/CONSTANT NAME, not a loose
# "<prev>.*<new>" regex. The loose form matched any file containing those two
# digits in order — a copyright year, an API level, a timestamp — and so
# silently passed schemas that had no test at all. That is a false green on
# the BUG8 gate, which is the one gate this project cannot afford to fake.
MIGRATION_NAME_RE_TEMPLATE='Migration_?%s_(to_)?%s|MIGRATION_%s_%s'

ADDED=$(git diff --diff-filter=A --name-only "$MERGE_BASE"...HEAD -- "$SCHEMA_DIR" || true)
for f in $ADDED; do
  NEW_VER=$(basename "$f" .json)

  if ! [[ "$NEW_VER" =~ ^[0-9]+$ ]]; then
    echo "::error::Unexpected file in $SCHEMA_DIR: $f (expected <version>.json)"
    FAIL=1
    continue
  fi

  if [ "$NEW_VER" -eq 1 ]; then
    echo "New schema detected: v1 (initial) — no migration required."
    continue
  fi

  PREV_VER=$((NEW_VER - 1))
  echo "New schema detected: v${NEW_VER}"

  # shellcheck disable=SC2059
  MIGRATION_RE=$(printf "$MIGRATION_NAME_RE_TEMPLATE" \
                 "$PREV_VER" "$NEW_VER" "$PREV_VER" "$NEW_VER")

  if ! git grep -qI -E "$MIGRATION_RE" -- 'core/database/src/main' 2>/dev/null; then
    echo "::error::Schema v${NEW_VER} added with no Migration ${PREV_VER}->${NEW_VER} class."
    FAIL=1
  fi

  if ! git grep -qI -E "$MIGRATION_RE" -- 'core/database/src/androidTest' 2>/dev/null; then
    echo "::error::Schema v${NEW_VER} added with no migration test for ${PREV_VER}->${NEW_VER}."
    echo "  Every migration needs a MigrationTestHelper test that seeds v${PREV_VER}"
    echo "  data and asserts content equality at v${NEW_VER}. This is the BUG8 gate."
    FAIL=1
  fi
done

# ── Rule 3: destructive migration ban ──────────────────────────────────
#
# Matches the CALL form only (`fallbackToDestructiveMigration…(`), so that
# documentation and code comments may name the banned API without tripping
# the guard. Covers the Room variants: fallbackToDestructiveMigration(),
# …From(), …OnDowngrade().
#
# `.github/` is excluded because ci.yml necessarily contains this identifier
# in its own banned-API check. Without that exclusion the guard fails on a
# clean checkout containing zero Kotlin — it flags the workflow that calls it.
DESTRUCTIVE_RE='fallbackToDestructiveMigration[A-Za-z]*[[:space:]]*\('
if git grep -nI -E "$DESTRUCTIVE_RE" \
     -- ':!*/dev/*' ':!scripts/*' ':!.github/*' ':!docs/*' ':!*.md' 2>/dev/null; then
  echo "::error::fallbackToDestructiveMigration() is banned outside dev source sets (Law 4)."
  FAIL=1
fi

# ── Rule 4: schemas must actually be exported ──────────────────────────
if [ -d "$SCHEMA_DIR" ] && [ -z "$(find "$SCHEMA_DIR" -name '*.json' 2>/dev/null)" ]; then
  echo "::error::No exported schema JSONs found. Set room.schemaLocation and commit them."
  FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
  echo "OK: schema guard passed."
fi
exit "$FAIL"
