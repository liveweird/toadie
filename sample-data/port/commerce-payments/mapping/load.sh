#!/usr/bin/env bash
# Loads (or, with --delete, removes) the default Backstage conversion mapping at
# sample-data/port/commerce-payments/mapping/backstage.json against a running toadie instance, using
# the conversion-mapping API (`/api/v1/conversion-mappings`) — the queries/load.sh precedent.
# The file IS the mapping document (schemaVersion 1); this script only wraps it with a name. Run
# sample-data/port/commerce-payments/blueprints/load.sh FIRST: the mapping is validated against the
# CURRENT blueprints and registries when it is stored, and a blueprint it names that does not exist
# is a blocking finding (the request is refused with the full list).
#
# Re-runnable: a name already held by an active mapping is a 409, reported "exists, skipped" — an
# edited document in the file is NOT pushed over an existing row (replace it from the API, or
# --delete first). The mapping is ADMIN-only to write, so the default credentials must be an admin.
# --delete finds the mapping by name, case-insensitively for ASCII letters only (jq's ascii_downcase): a
# non-ASCII name must be given in exactly the stored case.
#
# Env:
#   TOADIE_URL           default http://localhost:8081
#   TOADIE_EMAIL         default admin@toadie.local
#   TOADIE_PASSWORD      default changeme
#   TOADIE_MAPPING_NAME  default "Backstage catalog" — the stored mapping's name
#
# Secrets never touch argv: the password comes from the environment only (no shell history),
# the login body is built by jq and piped to curl on stdin, and the bearer token rides a
# 0600 header file read with `-H @file` — so neither shows up in `ps`. Nothing is printed
# beyond the outcome line (and, on a refused document, the validator's findings).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOADIE_URL="${TOADIE_URL:-http://localhost:8081}"
TOADIE_EMAIL="${TOADIE_EMAIL:-admin@toadie.local}"
TOADIE_PASSWORD="${TOADIE_PASSWORD:-changeme}"
TOADIE_MAPPING_NAME="${TOADIE_MAPPING_NAME:-Backstage catalog}"
DOCUMENT="$SCRIPT_DIR/backstage.json"

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

load() {
  local status
  status=$(jq -c --arg name "$TOADIE_MAPPING_NAME" '{name: $name, document: .}' "$DOCUMENT" \
    | request -X POST "$TOADIE_URL/api/v1/conversion-mappings" -H @"$HEADERS" \
        -H 'Content-Type: application/json' --data-binary @-)
  case "$status" in
    201)
      echo "created $TOADIE_MAPPING_NAME"
      # Drift never blocks a write; surface it so a registry that disagrees with the mapping is noticed.
      jq -r '.drift[]? | "drift \(.code) \(.path): \(.message)"' "$BODY" >&2
      ;;
    409) echo "exists, skipped: $TOADIE_MAPPING_NAME" ;;
    400)
      echo "FAILED $TOADIE_MAPPING_NAME (400): $(problem)" >&2
      jq -r '.findings[]? | "  \(.code) \(.path): \(.message)"' "$BODY" >&2
      exit 1
      ;;
    *) echo "FAILED $TOADIE_MAPPING_NAME ($status): $(problem)" >&2; exit 1 ;;
  esac
}

delete_set() {
  local status id
  status=$(request "$TOADIE_URL/api/v1/conversion-mappings" -H @"$HEADERS")
  [ "$status" = "200" ] || { echo "Cannot list conversion mappings ($status): $(problem)" >&2; exit 1; }
  id=$(jq -r --arg name "$TOADIE_MAPPING_NAME" '.items[] | select((.name | ascii_downcase) == ($name | ascii_downcase)) | .id' "$BODY" | head -n 1)
  if [ -z "$id" ]; then echo "not found, skipped: $TOADIE_MAPPING_NAME"; return; fi
  status=$(request -X DELETE "$TOADIE_URL/api/v1/conversion-mappings/$id" -H @"$HEADERS")
  case "$status" in
    204) echo "removed $TOADIE_MAPPING_NAME" ;;
    404) echo "not found, skipped: $TOADIE_MAPPING_NAME" ;;
    *) echo "FAILED $TOADIE_MAPPING_NAME ($status): $(problem)" >&2; exit 1 ;;
  esac
}

main() {
  login
  if [ "${1:-}" = "--delete" ]; then delete_set; else load; fi
}

main "$@"
