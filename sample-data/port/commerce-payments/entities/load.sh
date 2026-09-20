#!/usr/bin/env bash
# Loads (or, with --delete, removes) the sample entity set at sample-data/port/commerce-payments/entities/*.json
# against a running toadie instance, using the entity API — the blueprints/load.sh precedent, a
# scriptable, re-runnable alternative to the Import page (/ontology/import, v1.28.0 — see
# sample-data/port/commerce-payments/README.md "Through the Import page"). Run sample-data/port/commerce-payments/blueprints/load.sh FIRST:
# every entity names a blueprint, and the blueprint registry must already hold it.
#
# Env:
#   TOADIE_URL          default http://localhost:8081
#   TOADIE_EMAIL        default admin@toadie.local (any authenticated user may mutate entities,
#                       but the seed admin is always present)
#   TOADIE_PASSWORD     default changeme
#   TOADIE_SOURCE_BASE  default the public raw-GitHub base of this very directory (2.10.2) — every
#                       created entity gets `sourceUrl = $TOADIE_SOURCE_BASE/entities/<file>`, the
#                       URL of the ARRAY FILE it came from (the sync picker matches an entity by
#                       identifier inside an array), so a fresh load shows no SOURCE_MISSING rows
#                       and Sync from source works right away. Set to another base to point a
#                       fork/branch's checkout at itself, or to an EMPTY string to load completely
#                       source-less.
#
# Secrets never touch argv: the password comes from the environment only (no shell history),
# the login body is built by jq and piped to curl on stdin, and the bearer token rides a
# 0600 header file read with `-H @file` — so neither shows up in `ps`. Nothing is printed
# beyond the per-entity outcome lines.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOADIE_URL="${TOADIE_URL:-http://localhost:8081}"
TOADIE_EMAIL="${TOADIE_EMAIL:-admin@toadie.local}"
TOADIE_PASSWORD="${TOADIE_PASSWORD:-changeme}"
TOADIE_SOURCE_BASE="${TOADIE_SOURCE_BASE-https://raw.githubusercontent.com/liveweird/toadie/master/sample-data/port/commerce-payments}"

command -v curl >/dev/null || { echo "load.sh needs curl" >&2; exit 1; }
command -v jq >/dev/null || { echo "load.sh needs jq" >&2; exit 1; }

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT
HEADERS="$WORK/headers"
BODY="$WORK/response"

# Source the shared API helpers.
source "$SCRIPT_DIR/../../../lib/api.sh"

sample_files() { printf '%s\n' "$SCRIPT_DIR"/[0-9][0-9]-*.json | sort; }

load() {
  local failed=0 file entity identifier bp status
  while IFS= read -r file; do
    while IFS= read -r entity; do
      identifier=$(jq -r '.identifier' <<<"$entity")
      bp=$(jq -r '.blueprint' <<<"$entity")
      # sourceUrl is the ARRAY FILE's own URL (unless TOADIE_SOURCE_BASE is empty) — omitted,
      # never sent as null, so an empty base loads the sample completely source-less.
      if [ -n "$TOADIE_SOURCE_BASE" ]; then
        entity=$(jq -c --arg url "$TOADIE_SOURCE_BASE/entities/$(basename "$file")" '. + {sourceUrl: $url}' <<<"$entity")
      fi
      status=$(printf '%s' "$entity" \
        | request -X POST "$TOADIE_URL/api/v1/entities" -H @"$HEADERS" \
            -H 'Content-Type: application/json' --data-binary @-)
      case "$status" in
        201) echo "created $bp/$identifier" ;;
        409) echo "exists, skipped: $bp/$identifier" ;;
        *) echo "FAILED $bp/$identifier ($status): $(problem)" >&2; failed=1 ;;
      esac
    done < <(jq -c '.[]' "$file")
  done < <(sample_files)
  [ "$failed" -eq 0 ] || exit 1
}

# Resolves one entity's stored id by an exact-identifier match within its blueprint (the list
# endpoint's `q` is a substring filter, so the jq select narrows it to the byte-exact row).
resolve_id() {
  local bp="$1" identifier="$2" status list
  status=$(request "$TOADIE_URL/api/v1/entities?blueprint=$bp&q=$identifier&pageSize=100" -H @"$HEADERS")
  [ "$status" = "200" ] || { echo "FAILED to list $bp/$identifier ($status): $(problem)" >&2; return 1; }
  list=$(cat "$BODY")
  jq -r --arg id "$identifier" '.items[] | select(.identifier == $id) | .id' <<<"$list"
}

delete_set() {
  local failed=0 file entity identifier bp id status
  # Reverse dependency order: the highest-numbered file first, and each file's entities in
  # reverse creation order, so a relation target is never deleted while an earlier entity
  # still relates to it.
  while IFS= read -r file; do
    while IFS= read -r entity; do
      identifier=$(jq -r '.identifier' <<<"$entity")
      bp=$(jq -r '.blueprint' <<<"$entity")
      if ! id=$(resolve_id "$bp" "$identifier"); then
        failed=1
        continue
      fi
      if [ -z "$id" ]; then
        echo "not found, skipped: $bp/$identifier"
        continue
      fi
      status=$(request -X DELETE "$TOADIE_URL/api/v1/entities/$id" -H @"$HEADERS")
      case "$status" in
        204) echo "removed $bp/$identifier" ;;
        404) echo "not found, skipped: $bp/$identifier" ;;
        409) echo "not removed (still targeted by another entity): $bp/$identifier: $(problem)"; failed=1 ;;
        *) echo "FAILED $bp/$identifier ($status): $(problem)" >&2; failed=1 ;;
      esac
    done < <(jq -c 'reverse | .[]' "$file")
  done < <(sample_files | sort -r)
  [ "$failed" -eq 0 ] || exit 1
}

main() {
  if [ -n "$TOADIE_SOURCE_BASE" ]; then
    echo "source base: $TOADIE_SOURCE_BASE"
  else
    echo "no source references"
  fi
  login
  if [ "${1:-}" = "--delete" ]; then delete_set; else load; fi
}

main "$@"
