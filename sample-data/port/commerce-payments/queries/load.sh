#!/usr/bin/env bash
# Loads (or, with --delete, removes) the sample saved entity queries at
# sample-data/port/commerce-payments/queries/*.json against a running toadie instance, using the
# saved-query API (`/api/v1/entity-queries`, v2.1.0) — the entities/load.sh precedent. Each file is
# one request body (`name`, `visibility`, `query`); all are PUBLIC, so every user sees them in the
# query bar's picker on the Entity graph and Entity hierarchy canvases. Run
# sample-data/port/commerce-payments/blueprints/load.sh and entities/load.sh FIRST: the queries
# name sample blueprints and relations, and a query is only checked against the schema when it is
# applied, so loading them early would succeed but show diagnostics.
#
# Re-runnable: a name already held by one of the CALLER's active saved queries (matched
# case-insensitively) is a 409, reported "exists, skipped" — an edited query text in the file is
# NOT pushed over an existing row. `--delete` removes the caller's own rows of the same names
# (a saved query is creator-only mutable), so it only works as the user that loaded them.
#
# Env:
#   TOADIE_URL       default http://localhost:8081
#   TOADIE_EMAIL     default admin@toadie.local (the creator of the loaded queries)
#   TOADIE_PASSWORD  default changeme
# There is no TOADIE_SOURCE_BASE: a saved query has no source reference.
#
# Secrets never touch argv: the password comes from the environment only (no shell history),
# the login body is built by jq and piped to curl on stdin, and the bearer token rides a
# 0600 header file read with `-H @file` — so neither shows up in `ps`. Nothing is printed
# beyond the per-query outcome lines.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOADIE_URL="${TOADIE_URL:-http://localhost:8081}"
TOADIE_EMAIL="${TOADIE_EMAIL:-admin@toadie.local}"
TOADIE_PASSWORD="${TOADIE_PASSWORD:-changeme}"

command -v curl >/dev/null || { echo "load.sh needs curl" >&2; exit 1; }
command -v jq >/dev/null || { echo "load.sh needs jq" >&2; exit 1; }

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT
HEADERS="$WORK/headers"
BODY="$WORK/response"

# Source the shared API helpers.
# shellcheck source=../../../lib/api.sh
source "$SCRIPT_DIR/../../../lib/api.sh"

sample_files() { printf '%s\n' "$SCRIPT_DIR"/[0-9][0-9]-*.json | sort; }

load() {
  local failed=0 file name status
  while IFS= read -r file; do
    name=$(jq -r '.name' "$file")
    status=$(request -X POST "$TOADIE_URL/api/v1/entity-queries" -H @"$HEADERS" \
      -H 'Content-Type: application/json' --data-binary @"$file")
    case "$status" in
      201) echo "created $name" ;;
      409) echo "exists, skipped: $name" ;;
      *) echo "FAILED $name ($status): $(problem)" >&2; failed=1 ;;
    esac
  done < <(sample_files)
  [ "$failed" -eq 0 ] || exit 1
}

delete_set() {
  local status list failed=0 file name id removed kept
  status=$(request "$TOADIE_URL/api/v1/entity-queries" -H @"$HEADERS")
  [ "$status" = "200" ] || { echo "Cannot list saved queries ($status): $(problem)" >&2; exit 1; }
  list=$(cat "$BODY")
  while IFS= read -r file; do
    name=$(jq -r '.name' "$file")
    removed=0 kept=0
    # The list holds the caller's own rows plus everyone's PUBLIC ones, and a name is unique only
    # per owner — so try every case-insensitive match: the creator-only mutation rule answers a
    # foreign PUBLIC row with 403 (reported "owned by another user, kept"), and only the caller's own
    # row is removed.
    while IFS= read -r id; do
      [ -n "$id" ] || continue
      status=$(request -X DELETE "$TOADIE_URL/api/v1/entity-queries/$id" -H @"$HEADERS")
      case "$status" in
        204) echo "removed $name"; removed=1 ;;
        403) kept=1 ;;
        404) ;;
        *) echo "FAILED $name ($status): $(problem)" >&2; failed=1 ;;
      esac
    done < <(jq -r --arg name "$name" '.items[] | select((.name | ascii_downcase) == ($name | ascii_downcase)) | .id' <<<"$list")
    if [ "$removed" -eq 0 ]; then
      if [ "$kept" -eq 1 ]; then echo "owned by another user, kept: $name"; else echo "not found, skipped: $name"; fi
    fi
  done < <(sample_files)
  [ "$failed" -eq 0 ] || exit 1
}

main() {
  login
  if [ "${1:-}" = "--delete" ]; then delete_set; else load; fi
}

main "$@"
