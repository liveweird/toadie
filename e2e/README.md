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
  read-only" when applicable). Today: `auth`, `accessibility`, `url-import`, and `changelog`
  (device-local localStorage only) are read-only;
  `catalog-files`, `errors`, and `source-sync` own throwaway files (unique names in `default`); `kinds`,
  `render`, `round-trip`, and `hierarchy` own throwaway files in this RUN's namespaces (below); `users` owns
  its throwaway accounts; `i18n` owns its throwaway user (and ONLY that user's language —
  **seeded accounts must stay English**: every login applies the stored language to that
  session's UI, so a Polish seed admin would flip parallel specs mid-run);
  `namespaces` owns its throwaway dictionary entries and user;
  `lenses` owns its throwaway lenses, files, and users (see its own bullet below);
  `labels` owns its throwaway label, the one file carrying it, and its user; `annotations`
  owns its throwaway annotation key, the one file carrying it, and its user; `tags` owns its
  throwaway tag category, the one file carrying a tag, and its user; `types` owns the one
  value it appends to the Domain type dictionary, the one Domain file carrying it, and its
  user; `lifecycles` owns the one value it appends to the lifecycles dictionary, the one
  Component file carrying it, and its user; `password-reset` owns
  its throwaway account (its reset requests use unique per-run emails against the in-memory
  per-email throttle, and both tests together stay under the per-IP 5/min reset bucket);
  `mfa` owns its throwaway accounts and toggles ONLY their MFA flags (the seed admin's MFA
  flag is never touched — enabling it would make every spec's login demand a code) — all
  deleted by their own spec.
- **The namespaces dictionary is single-writer state.** Catalog writes accept only
  dictionary-defined namespaces, and the dictionary PUT is a whole-document replace — two
  parallel writers silently drop each other's entries. The writers are: **global-setup**
  (registers the four per-run namespaces `e2e-kns-*`/`e2e-rns-*`/`e2e-rtns-*`/`e2e-hns-*` before any
  worker starts and exposes them via `runNamespace()` in `helpers.ts`; global-teardown removes
  them) and **`namespaces.spec.ts`** (the ONLY in-run writer — it appends/removes its own
  unique entries through the real editor). A new spec needing a throwaway namespace adds a
  key to global-setup's minted set — it must NOT write the dictionary from a worker. The
  DEFAULT flag is doubly shared-critical: blank-namespace creates resolve against it, so no
  spec may flip which entry is flagged (`namespaces.spec` only asserts it; flipping is pinned
  server-/unit-side).
- **The lifecycles dictionary is single-writer state the same way** (a whole-document PUT):
  **global-setup does not touch it** — the V16/V22 seed provides the four values every
  spec's `pickLifecycle` relies on — and **`lifecycles.spec.ts` is the ONLY in-run writer**,
  appending and removing only its own unique `e2e-lc-*` value; no spec may remove or rename
  a seeded lifecycle.
- **The label registry is single-writer state too.** Catalog writes accept only registered
  labels, so a concurrently deleted/edited label breaks parallel specs' saves —
  **`labels.spec.ts` is the registry's ONLY in-run writer**, and it only ever creates and
  deletes its own unique `e2e-lbl-*` key. No other spec may apply labels to files without
  first moving label registration into global-setup (the run-namespace pattern). V22 seeds
  eight curated keys — no spec may edit or delete those either.
- **The lens store is per-run unique-name state.** Lenses are per-user content (private by
  default) and names are only unique per owner, so parallel specs cannot clash as long as
  every lens a spec saves carries a run-unique `e2e-lens-*` name and is deleted by its own
  spec — `lenses.spec.ts` follows exactly that and is the store's only writer.
- **The annotation-key registry follows the same rule.** Catalog writes accept only
  registered annotation keys — **`annotations.spec.ts` is that registry's ONLY in-run
  writer**, creating and deleting only its own unique `e2e-ann-*` key. No other spec may
  put annotations on files without first moving key registration into global-setup. V22
  seeds four `backstage.io/*` keys — no spec may edit or delete those either.
- **The tag-category registry follows the same rule.** Catalog writes accept only tags from
  registered categories — **`tags.spec.ts` is that registry's ONLY in-run writer**, creating
  and deleting only its own unique `e2e-tagcat-*` category. No other spec may put tags on
  files without first moving category registration into global-setup. V22 seeds four
  categories (Languages/Framework/Database/Events) — no spec may edit or delete those, and
  a new spec's tags must avoid theirs (one tag belongs to exactly one category).
- **The type registry follows it doubly.** Catalog writes accept only spec.type values from
  the file's kind's dictionary, and the dictionaries are per-kind SINGLETONS every spec's
  forms read (the V15/V22-seeded curated lists back every `pickType` call) —
  **`types.spec.ts` is the registry's ONLY in-run writer**, and even it only APPENDS its one
  unique `e2e-type-*` value to the Domain dictionary and removes it again; no spec may
  delete or replace a seeded dictionary.
- **The `group:default/platform` owner Group is a persistent test seed.** Catalog writes
  enforce reference RESOLUTION, so the shared `owner: group:default/platform` the specs'
  Component fixtures use must be stored — global-setup seeds it idempotently (201 or 409)
  and no spec may delete or rename it. It deliberately carries no `e2e` marker: it must
  survive runs (the volume's own demo files may reference it).
- Seeded accounts are never mutated. The seed admin (`admin@toadie.local`) is a shared
  read-mostly actor: specs sign in as it but must not change its password, roles, or state — a
  future spec that needs a mutated account creates a throwaway. The ONE sanctioned exception:
  `render.spec.ts` owns the admin's per-user graph LAYOUT document (pure view state no other
  spec reads) and restores the pristine default (Auto, no positions) before it ends.
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
  authenticated pages (`/`, `/files`, `/files/new`, `/files/import`,
  `/errors`, `/graph`, `/labels`, `/annotations`, `/tags`, `/types`, `/lifecycles`,
  `/namespaces`, `/users`, `/changelog`); `color-contrast` consciously waived theme-wide.
- [`auth.spec.ts`](scenarios/auth.md) — login / logout / invalid credentials / guarded deep link.
- [`annotations.spec.ts`](scenarios/annotations.md) — the annotation-key registry: modal
  validation → register a key (kinds only — values stay free) → edit → the regular user's
  read-only view → the editor's registry key Select with a free-text value on a new
  Component → cleanup; the registry's only in-run writer.
- [`catalog-files.spec.ts`](scenarios/catalog-files.md) — the visual creator's CRUD journey:
  create with live YAML preview → filtered list (name, the type/owner dropdown filters with
  owner-reference resolution, and the always-visible Kind pills as a visibility switch) →
  edit → Overwrite with YAML (diff, confirm, and the editor re-seeding so a later Save keeps
  the overwritten document) → download `catalog-info.yaml` → delete.
- [`changelog.spec.ts`](scenarios/changelog.md) — the what's-new dot on a fresh device
  leads to the changelog via the version stamp and clears once read (no language switching
  — it runs as the seed admin; see `i18n.spec.ts`).
- [`errors.spec.ts`](scenarios/errors.md) — an unresolved reference opens the
  Save-anyway modal (cancel and confirm paths); the waived save's finding shows on the
  Errors page and is repaired in the editor; deleting a referenced target creates a finding
  (hidden/restored by the References error-type pill), which recreating the target clears.
- [`history.spec.ts`](scenarios/history.md) — a file's change history on the editor: the
  creation entry, then an edit whose sentence names both changed fields while only the
  scalar gets a before/after line (free text is recorded as the bare fact).
- [`i18n.spec.ts`](scenarios/i18n.md) — the synced per-user language: a throwaway user
  switches to Polish, the choice survives a reload AND a wiped-device re-login (served from
  the stored value), and the admin's English flips it back; seeded accounts stay English.
- [`kinds.spec.ts`](scenarios/kinds.md) — the multi-kind editor journey: a Group (empty
  children), an API (pasted definition), and a Component whose owner/API references resolve
  live; kind badges on the list.
- [`hierarchy.spec.ts`](scenarios/hierarchy.md) — the Hierarchy view at `/`: a
  System ⊃ Component ⊃ subcomponent chain nests by most-specific placement, the Files
  filter panel selects what is SHOWN (a filtered-out container stops nesting its children,
  which fall flat to the root), branches collapse/expand, the tree rows carry the Files Operations menu (download, delete), and a
  deleted parent becomes a MISSING placeholder; throwaway files in this run's namespace.
- [`labels.spec.ts`](scenarios/labels.md) — the label registry: modal validation → create a
  label (values + kinds) → edit → the regular user's read-only view → the editor's
  registry-constrained label pickers on a new Component → the list's label/label-value
  filters (key presence, any-of values) → cleanup; the registry's only in-run writer.
- [`lenses.spec.ts`](scenarios/lenses.md) — Lenses: filter the Files list, save it as a
  named private lens, watch the Modified badge on divergence, apply the same lens on
  Hierarchy, Graph, and Errors, rename it public, delete it; throwaway unique-named files
  and lenses.
- [`lifecycles.spec.ts`](scenarios/lifecycles.md) — the lifecycles dictionary: seeded values
  → inline grammar validation → append a unique value → the regular user's read-only view →
  the editor's Lifecycle Select on a new Component → removal; the dictionary's only in-run
  writer (append-and-remove, the seeds survive).
- [`mfa.spec.ts`](scenarios/mfa.md) — email MFA + the flags surfaces: the /feature-flags
  row switch and per-user editor round-trip a throwaway user's MFA flag; an MFA-enabled
  account signs in through the emailed 6-digit code via Mailpit (skips itself without it).
- [`namespaces.spec.ts`](scenarios/namespaces.md) — the namespaces dictionary: inline grammar
  validation → append two entries → reorder → the regular user's read-only view → removal;
  append-only against the shared document.
- [`password-reset.spec.ts`](scenarios/password-reset.md) — the forgot-password flow:
  neutral confirmation + per-email throttle for unknown addresses; the full email roundtrip
  through the compose stack's Mailpit (new password works, old one is dead — skips itself
  without Mailpit).
- [`render.spec.ts`](scenarios/render.md) — the relationship graph draws stored and
  (deletion-orphaned) missing nodes for one per-attempt name stem, faced name + type, with the
  two-namespace canvas clustered inside labelled namespace frames and the unmatched shared
  owner group left out; toggling a relation family prunes the missing nodes it strands; the Manual layout mode drags a node, the position survives
  a reload (server-side per user), and Reset + Auto restore the pristine layout document.
- [`round-trip.spec.ts`](scenarios/round-trip.md) — the YAML round-trip: two pasted documents
  dry-run as Would-be-created (the Check button, nothing stored), import as Created, export
  downloads them as one `---`-separated file, and re-importing the export reports every row
  Already exists (nothing overwritten).
- [`source-sync.spec.ts`](scenarios/source-sync.md) — source references: a file created
  source-less is flagged on the Errors report (No source reference), setting the URL in the
  editor's Source section clears the flag and turns on the Last-sync column + the Sync
  operation, and the sync modal shows the SSRF guard's public-https error against a
  loopback URL with the overwrite disabled (the fetch→overwrite happy path deliberately
  stays server-/unit-tested).
- [`tags.spec.ts`](scenarios/tags.md) — the tag categories: modal validation → create a
  category (tags + kinds) → edit → the regular user's read-only view → the editor's grouped
  tags picker on a new Component → cleanup; the tag-category registry's only in-run writer.
- [`types.spec.ts`](scenarios/types.md) — the per-kind type dictionaries: seeded rows → modal
  validation → append a unique type to the Domain dictionary → the regular user's read-only
  view → the editor's Type Select on a new Domain file → restore; the type registry's only
  in-run writer (append-and-remove, the singletons themselves survive).
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
