# E2E (Playwright, blackbox)

Browser end-to-end tests that treat the app as a **blackbox**: they drive a real Chromium against
the whole stack (SPA + server + Flyway + Postgres) served single-origin at `http://localhost:8081`
by `docker compose`. This package is fully isolated from `web/` — it imports none of the app source
and only speaks HTTP/DOM.

## Run

```bash
cd e2e
npm install
npm run install:browsers      # one-time: download Chromium
npm test                      # brings the stack up (docker compose), runs specs, tears it down
```

- `global-setup.ts` starts `docker compose up -d --build` and waits for `:8081` — **unless a stack
  is already running there**, which it reuses (fast local iteration: keep `docker compose up` or a
  local `WEB_STATIC_DIR=… ./gradlew :server:run` going and just run `npm test`).
  `global-teardown.ts` only runs `docker compose down` if setup started the stack — the
  postgres volume is deliberately kept, so the local database (your own demo files included)
  survives e2e runs; `docker compose down -v` manually when you want a pristine one.
- Requires Docker. Override the target with `E2E_BASE_URL`. (8081, not 8080 — probing 8080 could
  happily "reuse" a running Lettuce.)

Two Docker-free static gates ride every spec change — run both before merging, like the web
package's lint/knip:

```bash
npm run typecheck             # tsc --noEmit — Playwright only TRANSPILES TS, it never checks it
npm run check:scenarios       # spec ↔ scenario parity: files exist, test() titles == headings
```

`check:scenarios` enforces the same-commit rule below mechanically (both directions, orphan
files included); `accessibility.spec.ts` is its one registered skip — the parameterized-title
carve-out in [`scenarios/README.md`](scenarios/README.md).

## Parallel execution

The suite runs on **4 workers by default** (`E2E_WORKERS` overrides; `E2E_WORKERS=1` restores
fully-serial behavior). The serial unit is the **spec file** (`fullyParallel: false` — a file's
tests may be order-dependent); different files run concurrently. That is only sound because
**every spec file owns its server-side state exclusively** — the standing rulebook, inherited
from Lettuce, that any new or edited spec must satisfy:

- Each spec's scenario file declares its **Owns** line (exclusive server-side state; "nothing —
  read-only" when applicable). Today: `auth`, `accessibility`, and `url-import` are read-only;
  `catalog-files`, `cross-check`, `kinds`, `render`, and `round-trip` each own throwaway files
  in unique namespaces; `users` owns its throwaway accounts — all deleted by their own spec.
- Seeded accounts are never mutated. The seed admin (`admin@toadie.local`) is a shared
  read-mostly actor: specs sign in as it but must not change its password, roles, or state — a
  future spec that needs a mutated account creates a throwaway.
- E2e-created entities carry a sweepable marker — every e2e-created file's name/namespace and
  every e2e-created user's email contains `e2e`, and nothing that must SURVIVE runs is ever
  named that way. Each spec deletes its own state, so the rule is currently satisfied by
  self-cleanup; port Lettuce's `sweep-residue` global-setup pass if aborted runs start leaving
  residue that self-cleanup misses.
- Artifacts must be unique-named and list asserts filter- or sort-anchored — never bare page-1
  assumptions. A future spec minting globally-visible state (banners, org-wide notifications)
  runs in its own dependent project phase, not in the parallel pool.

## What's covered

**Each spec's full design lives in its scenario file under [`scenarios/`](scenarios/README.md)** —
versioned natural-language test-design artifacts (actors, owned state, numbered steps, expected
outcomes). **A new or behaviorally changed test lands with its scenario file and its line below in
the same commit** — this list is the coverage map, the scenario file is the design.

- [`accessibility.spec.ts`](scenarios/accessibility.md) — axe WCAG A/AA smoke: login + the
  authenticated pages (`/`, `/catalog-files`, `/catalog-files/new`, `/catalog-files/import`,
  `/cross-check`, `/render`, `/users`); `color-contrast` consciously waived theme-wide.
- [`auth.spec.ts`](scenarios/auth.md) — login / logout / invalid credentials / guarded deep link.
- [`catalog-files.spec.ts`](scenarios/catalog-files.md) — the visual creator's CRUD journey:
  create with live YAML preview → filtered list → edit → download `catalog-info.yaml` → delete.
- [`cross-check.spec.ts`](scenarios/cross-check.md) — a dangling `component:` reference is
  flagged live in the editor and on the Cross-check page, then resolves once its target file
  is created.
- [`kinds.spec.ts`](scenarios/kinds.md) — the multi-kind editor journey: a Group (empty
  children), an API (pasted definition), and a Component whose owner/API references resolve
  live; kind badges on the list.
- [`render.spec.ts`](scenarios/render.md) — the relationship graph draws stored, missing, and
  external nodes for a throwaway namespace; toggling a relation family prunes its nodes.
- [`round-trip.spec.ts`](scenarios/round-trip.md) — the YAML round-trip: two pasted documents
  import as Created, export downloads them as one `---`-separated file, and re-importing the
  export reports every row Already exists (nothing overwritten).
- [`url-import.spec.ts`](scenarios/url-import.md) — fetch-from-URL through the real SSRF
  guard: a loopback URL is refused with the uniform public-https error (the happy network
  path deliberately stays server-tested, no external dependency in CI).
- [`users.spec.ts`](scenarios/users.md) — the account lifecycle: create via the one-time
  password reveal → the new user's limited view + self password change → promotion →
  deletion → the dead login; own-row protections on the admin's row.

Specs log in with the seeded admin (`admin@toadie.local`, password `changeme`), and use unique
content where they create any — so they don't depend on a clean database or absolute counts.

### Logging in

`helpers.login()` drives the **real login form** (clearing any leftover `toadie.auth.*`
localStorage session first — while one exists, `RedirectIfAuthed` bounces `/login` away and the
form never renders). Development stacks lift the per-IP login bucket
(`security.rateLimit.loginPerMinute`, 1000/min in development vs 10/min in production), so
form-driven logins aren't throttled. When the suite grows enough that per-spec form logins
dominate runtime, port Lettuce's API-minted-session fast path (write the `toadie.auth.*` keys the
SPA itself persists, falling back to the real form) — keep specs whose subject *is* a credential
on the real form driver.

## Deliberately not covered

- **Login lockout (429)** — five failed logins would lock the seed admin for 15 minutes in the
  shared database and poison the rest of the run. Covered by `LoginThrottleTest` /
  `LoginLockoutTest` (server).
- **Token refresh / expiry (clock-driven)** — real expiry needs clock control; covered by server
  tests (`RefreshTest`) and the `web/src/api/api.test.ts` unit tests. (Lettuce additionally
  covers the browser-side refresh behavior via `page.route` fault injection — port
  `error-handling.spec.ts` when the SPA grows surfaces worth failing.)
- **The authz matrix** — covered by the server tests (`GuardsTest`, `AnonymousAccessTest`); E2E
  asserts only user-visible consequences.
- **Dark-mode rendering** — the palette is theme-owned (`web/src/theme.ts`); no e2e asserts
  colors, and there is no visual-regression suite.
- **Responsive / cross-browser / visual automation** — the suite deliberately runs a single
  **Desktop Chrome (chromium) project** only, with no mobile project or screenshot comparison;
  layout relies on Mantine semantics plus the role/label-based locators every spec uses.
  Accessibility gets the `accessibility.spec.ts` axe smoke (structural rules only, with
  `color-contrast` consciously waived).

Reports/artifacts land in `playwright-report/` and `test-results/` (git-ignored).
