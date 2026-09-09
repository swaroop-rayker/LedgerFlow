#!/usr/bin/env bash
# guard-corpus-order.sh — the OCR corpus is written before the extractor
#                         (SPEC.md §12, testdata/receipts/README.md)
#
# The rule
# --------
# Ground truth for a receipt is committed BEFORE the extractor that reads it is
# written or changed. This is the same rule CLAUDE.md §11 already states for the
# parser corpus -- "a real message that fails to parse becomes a fixture BEFORE
# the rule that handles it is written" -- and it exists because the alternative
# is undetectable afterwards.
#
# The failure it prevents is not carelessness, it is convenience. Running the
# extractor, dumping its output as the expected result and correcting what you
# happen to notice is by far the fastest way to write a fixture. It also
# silently blesses whatever the extractor already does as truth, which inflates
# recall by an unknown amount and leaves no trace. A corpus produced that way
# measures the extractor against its own opinions.
#
# What is actually checked
# ------------------------
# The fixtures themselves are NOT in this repository -- images and expected
# output live in a private store, because a real receipt carries a card tail and
# often a phone number, and the ground truth is the owner's shopping list
# (testdata/receipts/README.md). So the observable proxy here is
# `testdata/receipts/manifest.json`, which changes exactly when a fixture is
# added, removed, or has its ground truth edited.
#
# A single commit may therefore not touch BOTH:
#   - testdata/receipts/manifest.json   (the corpus changed)
#   - feature/ocr/**                    (the extractor changed)
#
# Per commit, not per branch: a branch that adds fixtures and then improves the
# extractor is exactly the intended workflow. What is banned is the two arriving
# together, where the order is unknowable.
#
# The escape hatch is deliberate and visible
# ------------------------------------------
# A commit message containing [corpus-override] passes, and prints loudly. Some
# changes genuinely touch both -- a rename, a schema change to the fixture
# format. Making the exception a marker in the commit message rather than a
# silent allowance means it survives in `git log` for whoever asks later.
#
# Run locally before pushing:  bash scripts/guard-corpus-order.sh

set -euo pipefail

MANIFEST="testdata/receipts/manifest.json"
EXTRACTOR="feature/ocr"
FAIL=0

if [ -n "${GITHUB_BASE_REF:-}" ]; then
  git fetch --no-tags --depth=50 origin "$GITHUB_BASE_REF" >/dev/null 2>&1 || true
  BASE="origin/$GITHUB_BASE_REF"
elif git rev-parse --verify origin/main >/dev/null 2>&1; then
  BASE="origin/main"
else
  echo "No base ref to compare against; skipping corpus-order guard."
  exit 0
fi

MERGE_BASE=$(git merge-base "$BASE" HEAD)

echo "── Corpus-order guard (base: $BASE) ───────────────────────────────"

COMMITS=$(git rev-list "$MERGE_BASE"..HEAD || true)

if [ -z "$COMMITS" ]; then
  echo "OK: no commits to check."
  exit 0
fi

CHECKED=0
for sha in $COMMITS; do
  CHECKED=$((CHECKED + 1))
  FILES=$(git show --pretty=format: --name-only "$sha" | grep -v '^$' || true)

  TOUCHES_CORPUS=0
  TOUCHES_EXTRACTOR=0
  echo "$FILES" | grep -qxF "$MANIFEST" && TOUCHES_CORPUS=1
  echo "$FILES" | grep -q "^$EXTRACTOR/" && TOUCHES_EXTRACTOR=1

  if [ "$TOUCHES_CORPUS" -eq 1 ] && [ "$TOUCHES_EXTRACTOR" -eq 1 ]; then
    SUBJECT=$(git log -1 --format=%s "$sha")
    if git log -1 --format=%B "$sha" | grep -q '\[corpus-override\]'; then
      echo "  NOTE: ${sha:0:8} touches both, allowed by [corpus-override]: $SUBJECT"
      continue
    fi
    echo "::error::${sha:0:8} changes the receipt corpus AND the extractor in one commit."
    echo "         $SUBJECT"
    echo ""
    echo "  Ground truth is committed before the extractor sees the receipt."
    echo "  Split this into two commits: the fixture first, the extractor after."
    echo ""
    echo "  If the two genuinely belong together (a rename, a fixture-format"
    echo "  change), put [corpus-override] in the commit message so the exception"
    echo "  is visible in git log rather than silent."
    FAIL=1
  fi
done

if [ "$FAIL" -ne 0 ]; then
  echo ""
  echo "Corpus-order guard FAILED."
  exit 1
fi

echo "OK: corpus-order guard passed ($CHECKED commit(s) checked)."
