#!/usr/bin/env bash
# Loads (or, with --delete, removes) the sample blueprint set at sample-data/blueprints/*.json
# against a running toadie instance, using the blueprint API — there is no import UI for
# blueprints. Re-runnable: an already-loaded file is reported "exists, skipped", not an error.
#
# Env:
#   TOADIE_URL       default http://localhost:8081
#   TOADIE_EMAIL     default admin@toadie.local (the seed admin — mutations are ADMIN-only)
#   TOADIE_PASSWORD  default changeme
#
# Secrets never touch argv: the password comes from the environment only (no shell history),
# the login body is built by jq and piped to curl on stdin, and the bearer token rides a
# 0600 header file read with `-H @file` — so neither shows up in `ps`. Nothing is printed
# beyond the per-file outcome lines.
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

# Prints a friendly line instead of curl's bare exit code when the instance is unreachable.
request() {
  if ! curl -sS -o "$BODY" -w '%{http_code}' "$@"; then
    echo "Cannot reach $TOADIE_URL" >&2
    exit 1
  fi
}

problem() { jq -c '.detail // .' "$BODY" 2>/dev/null || cat "$BODY"; }

login() {
  local status
  status=$(jq -n --arg email "$TOADIE_EMAIL" --arg password "$TOADIE_PASSWORD" \
      '{email: $email, password: $password}' \
    | request -X POST "$TOADIE_URL/api/v1/login" -H 'Content-Type: application/json' --data-binary @-)
  local token
  token=$(jq -r '.token // empty' "$BODY" 2>/dev/null || true)
  if [ "$status" != "200" ] || [ -z "$token" ]; then
    echo "Login failed ($status): $(problem)" >&2
    exit 1
  fi
  (umask 077; printf 'Authorization: Bearer %s\n' "$token" > "$HEADERS")
}

sample_files() { printf '%s\n' "$SCRIPT_DIR"/[0-9][0-9]-*.json | sort; }

load() {
  local failed=0 file identifier status
  while IFS= read -r file; do
    identifier=$(jq -r '.identifier' "$file")
    status=$(request -X POST "$TOADIE_URL/api/v1/blueprints" -H @"$HEADERS" \
      -H 'Content-Type: application/json' --data-binary @"$file")
    case "$status" in
      201) echo "created $identifier" ;;
      409) echo "exists, skipped: $identifier" ;;
      *) echo "FAILED $identifier ($status): $(problem)" >&2; failed=1 ;;
    esac
  done < <(sample_files)
  [ "$failed" -eq 0 ] || exit 1
}

delete_set() {
  local status
  status=$(request "$TOADIE_URL/api/v1/blueprints" -H @"$HEADERS")
  [ "$status" = "200" ] || { echo "Cannot list blueprints ($status): $(problem)" >&2; exit 1; }
  local list
  list=$(cat "$BODY")
  local failed=0 file identifier id
  # Reverse dependency order: the highest-numbered file first, so a target is never deleted
  # while an earlier blueprint still relates/aggregates to it.
  while IFS= read -r file; do
    identifier=$(jq -r '.identifier' "$file")
    id=$(jq -r --arg id "$identifier" '.items[] | select(.identifier == $id) | .id' <<<"$list")
    if [ -z "$id" ]; then
      echo "not found, skipped: $identifier"
      continue
    fi
    status=$(request -X DELETE "$TOADIE_URL/api/v1/blueprints/$id" -H @"$HEADERS")
    case "$status" in
      204) echo "removed $identifier" ;;
      409) echo "not removed (still targeted by another blueprint): $identifier: $(problem)"; failed=1 ;;
      *) echo "FAILED $identifier ($status): $(problem)" >&2; failed=1 ;;
    esac
  done < <(sample_files | sort -r)
  [ "$failed" -eq 0 ] || exit 1
}

main() {
  login
  if [ "${1:-}" = "--delete" ]; then delete_set; else load; fi
}

main "$@"
