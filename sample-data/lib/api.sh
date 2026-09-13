#!/usr/bin/env bash
# Shared API helpers for blueprint and entity loaders.
# Secrets never touch argv: the password comes from the environment only (no shell
# history), the login body is built by jq and piped to curl on stdin, and the bearer
# token rides a 0600 header file read with `-H @file` — so neither shows up in `ps`.

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
  # The password reaches jq through its ENVIRONMENT (a per-command assignment, never --arg),
  # so it is absent from jq's argv; the loaders keep it a plain shell variable, hence the export here.
  status=$(TOADIE_PASSWORD="$TOADIE_PASSWORD" jq -n --arg email "$TOADIE_EMAIL" '{email: $email, password: $ENV.TOADIE_PASSWORD}' \
    | request -X POST "$TOADIE_URL/api/v1/login" -H 'Content-Type: application/json' --data-binary @-)
  local token
  token=$(jq -r '.token // empty' "$BODY" 2>/dev/null || true)
  if [ "$status" != "200" ] || [ -z "$token" ]; then
    echo "Login failed ($status): $(problem)" >&2
    exit 1
  fi
  (umask 077; printf 'Authorization: Bearer %s\n' "$token" > "$HEADERS")
}
