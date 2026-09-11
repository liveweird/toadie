#!/usr/bin/env bash
# Loads (or, with --delete, removes) the sample blueprint set at sample-data/blueprints/*.json —
# the eleven-blueprint baseline ontology (.claude/docs/ontology.md) — against a running toadie
# instance, using the blueprint API: a scriptable, re-runnable alternative to the Import page
# (/ontology/import, v1.28.0 — see sample-data/README.md "Through the Import page") for CI and
# local setup outside a browser session. An already-loaded file is reported "exists, skipped",
# not an error. The `_team`/`_user` system
# blueprints (V31) are never created or deleted here — they are seeded by migration, and a
# `_`-prefixed file is instead looked up by identifier and PUT to extend it with the sample
# ontology's shape ("extended <identifier>"); `--delete` always keeps them ("system, kept: <id>").
#
# TWO PASSES (phase 5, v1.27.0): some files declare `aggregationProperties` whose `target` names
# a blueprint that loads LATER (domain -> system, system -> service/workload) — the set is
# dependency-ordered only for RELATIONS, and an aggregation's target must already be an ACTIVE
# blueprint (`BlueprintService.requireTargetsExist`), same as Port's own tooling loading in two
# passes for the same reason. Pass 1 creates/extends every file with `aggregationProperties`
# stripped, in the existing numbered order; pass 2 PUTs the FULL file back onto every blueprint
# that actually declares `aggregationProperties`, once every target exists ("aggregations
# <identifier>"). `--delete` mirrors it backwards: the same forward-pointing aggregations would
# otherwise make every reverse-order DELETE a 409 (a target cannot go while something aggregates
# over it), so it first PUTs every non-system file that declares them with `aggregationProperties`
# stripped ("aggregations dropped <identifier>"), then removes the rows highest-numbered first.
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
  local failed=0 file identifier status id list stripped
  stripped="$WORK/stripped.json"

  # Pass 1: create/extend every file, in the existing numbered (dependency) order, with
  # aggregationProperties stripped — a forward-referencing target hasn't loaded yet.
  while IFS= read -r file; do
    identifier=$(jq -r '.identifier' "$file")
    jq 'del(.aggregationProperties)' "$file" > "$stripped"
    if [[ "$identifier" == _* ]]; then
      status=$(request "$TOADIE_URL/api/v1/blueprints" -H @"$HEADERS")
      if [ "$status" != "200" ]; then
        echo "FAILED $identifier ($status): $(problem)" >&2
        failed=1
        continue
      fi
      list=$(cat "$BODY")
      id=$(jq -r --arg id "$identifier" '.items[] | select(.identifier == $id) | .id' <<<"$list")
      if [ -z "$id" ]; then
        echo "FAILED $identifier (404): server predates V31?" >&2
        failed=1
        continue
      fi
      status=$(request -X PUT "$TOADIE_URL/api/v1/blueprints/$id" -H @"$HEADERS" \
        -H 'Content-Type: application/json' --data-binary @"$stripped")
      case "$status" in
        204) echo "extended $identifier" ;;
        *) echo "FAILED $identifier ($status): $(problem)" >&2; failed=1 ;;
      esac
      continue
    fi
    status=$(request -X POST "$TOADIE_URL/api/v1/blueprints" -H @"$HEADERS" \
      -H 'Content-Type: application/json' --data-binary @"$stripped")
    case "$status" in
      201) echo "created $identifier" ;;
      409) echo "exists, skipped: $identifier" ;;
      *) echo "FAILED $identifier ($status): $(problem)" >&2; failed=1 ;;
    esac
  done < <(sample_files)

  # Pass 2: every file that DECLARES aggregationProperties is PUT again with its full desired
  # shape, now that every target (including a forward-referenced one) exists.
  status=$(request "$TOADIE_URL/api/v1/blueprints" -H @"$HEADERS")
  if [ "$status" != "200" ]; then
    echo "FAILED to list blueprints for the aggregation pass ($status): $(problem)" >&2
    exit 1
  fi
  list=$(cat "$BODY")
  while IFS= read -r file; do
    jq -e '.aggregationProperties | length > 0' "$file" >/dev/null 2>&1 || continue
    identifier=$(jq -r '.identifier' "$file")
    id=$(jq -r --arg id "$identifier" '.items[] | select(.identifier == $id) | .id' <<<"$list")
    if [ -z "$id" ]; then
      echo "FAILED $identifier (404): missing after pass 1?" >&2
      failed=1
      continue
    fi
    status=$(request -X PUT "$TOADIE_URL/api/v1/blueprints/$id" -H @"$HEADERS" \
      -H 'Content-Type: application/json' --data-binary @"$file")
    case "$status" in
      204) echo "aggregations $identifier" ;;
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
  local failed=0 file identifier id stripped
  stripped="$WORK/stripped.json"
  # Pass 1: drop every aggregation first — its target loads LATER than its owner, so a plain
  # reverse-order delete would find the target still aggregated over (409) by an earlier file.
  while IFS= read -r file; do
    identifier=$(jq -r '.identifier' "$file")
    [[ "$identifier" == _* ]] && continue
    jq -e '.aggregationProperties | length > 0' "$file" >/dev/null 2>&1 || continue
    id=$(jq -r --arg id "$identifier" '.items[] | select(.identifier == $id) | .id' <<<"$list")
    [ -n "$id" ] || continue
    jq 'del(.aggregationProperties)' "$file" > "$stripped"
    status=$(request -X PUT "$TOADIE_URL/api/v1/blueprints/$id" -H @"$HEADERS" \
      -H 'Content-Type: application/json' --data-binary @"$stripped")
    case "$status" in
      204) echo "aggregations dropped $identifier" ;;
      *) echo "FAILED $identifier ($status): $(problem)" >&2; failed=1 ;;
    esac
  done < <(sample_files)
  # Pass 2: reverse dependency order — the highest-numbered file first, so a target is never
  # deleted while an earlier blueprint still relates to it.
  while IFS= read -r file; do
    identifier=$(jq -r '.identifier' "$file")
    if [[ "$identifier" == _* ]]; then
      echo "system, kept: $identifier"
      continue
    fi
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
