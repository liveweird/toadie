# Frontend (`web/`)

Vite + React 19 + TypeScript SPA. The Gradle and npm toolchains are disjoint — never invoke npm from Gradle or vice versa. This is the Toadie **skeleton** frontend: shell + auth + the cross-cutting conventions; feature surfaces arrive later and should port Lettuce's building blocks (see "Not yet ported" at the bottom) rather than inventing new ones.

- Dev server: `cd web && npm run dev` (port **5174** — not Vite's 5173 default, so Toadie can run beside Lettuce). All backend routes live under the `/api/` namespace and Vite proxies the single `/api` subtree → `http://localhost:8081`. Any other path is served as `index.html` so React Router owns the SPA URL space and browser reloads don't collide with API routes.
- Production build: `cd web && npm run build` → static files in `web/dist`. In the Docker image these are baked in and served by the Ktor server itself (via `WEB_STATIC_DIR`; see `plugins/Routing.kt`), so production is single-origin and there is no Vite proxy — the SPA and `/api` share `http://localhost:8081`.
- Regenerate API types: `cd web && npm run gen:api`. Reads `server/src/main/resources/openapi/documentation.yaml` directly (no server needed) and writes `web/src/api/schema.ts`. Run this after editing the OpenAPI spec; commit the regenerated `schema.ts` in the same change.
- **Quality gates (the frontend counterpart of the backend's detekt)**: `npm run lint` carries `eslint-plugin-sonarjs` (recommended) plus core size/complexity backstops — zero-findings gate; rule tuning lives in `eslint.config.js` ONLY, one commented override per deliberate idiom, and any inline `eslint-disable` needs a justifying comment. `npm run knip` is the dead-code gate (unused files/exports/dependencies; `knip.json` ignores the generated `schema.ts`) — keep exports that only the declaring file uses un-exported, and delete what knip flags rather than ignoring it.
- **Build version stamp**: `vite.config.ts` injects `__APP_COMMIT__` (short sha, `+dirty` when the worktree has uncommitted changes) and `__APP_COMMIT_TIME__` (commit ISO timestamp) via `define`, declared in `src/vite-env.d.ts`. Env vars `GIT_SHA`/`GIT_COMMIT_TIME` override the local-git lookup — the Dockerfile's SPA stage sets them explicitly (its worktree never matches the index, so the dirty check would false-positive), and CI can do the same when building without `.git`. `src/components/VersionStamp.tsx` renders `v<APP_VERSION> · <sha> · <time>` at the bottom of the navbar (`App.tsx`) and under the login card (`components/AuthCard.tsx`).

## Layout conventions

- **Flat directories**: `pages/`, `components/`, `hooks/`, `utils/`, `api/`, `changelog/`, `locales/{en,pl}/`, `test/` — no deeper nesting, no per-feature folders (a feature contributes files into these).
- **Default exports for components/pages**, named exports for everything else; **no path aliases** — relative imports only.
- **Co-located tests**: `Foo.test.tsx` sits beside `Foo.tsx`; shared test scaffolding lives in `src/test/` (`setup.ts` forces `en`, `render.tsx` is the provider wrapper — it and every file-local `MantineProvider` must pass `env="test"`, or Select/Popover interaction silently fails under happy-dom; `http.ts` holds the fetch stubs).
- **CSS modules only where the theme can't express it**: styling belongs in `src/theme.ts` (Mantine `createTheme` — component `extend`s, defaultProps) with `src/theme.module.css` for the class-level parts; a per-component `*.module.css` is the exception, not the pattern.
- Pages are **lazy** (`React.lazy` in `App.tsx`); new routes register above the `path="*"` NotFound catch-all (LAST child, never feature-gated), and nav entries append to `NAV_ITEMS` (the `label` is a typed i18n key; `adminOnly?` gates admin leaves — the Lettuce feature/role-filter machinery slots back in here as features arrive).

## The typed API layer (`src/api/`)

The OpenAPI spec at `server/src/main/resources/openapi/documentation.yaml` is the contract between backend and frontend — hand-maintained, not auto-generated from routes. Swagger UI is at `http://localhost:8081/openapi` (dev). The layer is small hand-written modules:

- **`http.ts`** (transport): `authedFetch()` with the **single-flighted silent refresh** — on a 401 it exchanges the stored refresh token once (concurrent 401s share one in-flight `/refresh` call) and retries once; a DEFINITIVE rejection (no refresh token, or 401/403 from the server) clears the session and signs out, while a TRANSIENT failure (network/timeout/5xx/429/malformed body) keeps the session so a later retry can succeed — **never collapse the two** (a network blip must not erase a valid session). `ApiError` (status + parsed body; read RFC 7807 fields via the `detail`/`instance` getters, never hand-cast `err.body`), and the two standard wrapper shapes **`jsonRequest<T>()`/`voidRequest()`** every ordinary endpoint wrapper uses, plus **`buildQuery()`** — the query-string builder for future list wrappers (skips null/undefined/""; `false` and `0` ARE sent, so an omit-when-false param is passed as `value || undefined` at the call site). Reach for the raw `authedFetch`/`safeJson` primitives only when a wrapper genuinely inspects the Response. Every transport fetch carries `timeoutSignal()` (30 s, feature-detected for happy-dom) — a hung request rejects with a `TimeoutError` DOMException instead of pending forever; new `fetch` call sites in `api/` must attach it.
- **`session.ts`**: token/roles/userId storage under the `toadie.auth.*` localStorage keys, written by `persistSession(LoginResponse)` on login/refresh, and the render-time accessors (`isAdmin()`, `getUserId()`, …) — plain reads, no reactive store.
- **`auth.ts`**: login/logout (`logout()` is best-effort — the revoke POST may fail, `clearSession()` always runs, the function never throws).
- The types come from the generated `schema.ts`; a new endpoint's wrapper goes into its feature's module (a new feature area gets a new module — never a catch-all file). Avoid heavyweight client generators (Orval/Kiota) — the lightweight pairing of `openapi-typescript` (types only) + hand-written fetch is intentional.

`openapi-typescript` is installed with `--legacy-peer-deps` because its declared peer is TS `^5` while the scaffold uses TS 6; the generated output is compatible. If you re-`npm install` from scratch, use `npm install --legacy-peer-deps`.

## Error handling

- **ErrorBoundary** (`components/ErrorBoundary.tsx`): a page render crash must never white-screen the app. `RouteErrorBoundary` wraps the `<Outlet />` inside `AppShell.Main` (header/nav survive; keyed by `location.pathname`, so navigating anywhere recovers), and a plain `ErrorBoundary` in `main.tsx` is the last resort for shell crashes. Don't add per-page boundaries — the two mounts are the model.
- **Catch-all 404**: `pages/NotFound.tsx` is the LAST `path="*"` child of the Shell route. New routes go above it.
- **Chunk-load recovery**: `main.tsx` listens for `vite:preloadError` (a redeploy 404s the old hashed chunks) and reloads once, rate-limited via sessionStorage (`toadie.chunkReloadedAt`, max one reload/minute) so a genuinely missing chunk falls through to the ErrorBoundary instead of looping.
- **Query retry policy**: the `QueryClient` in `main.tsx` uses `shouldRetryQuery` (`api/http.ts`) — NEVER retry a 4xx (the answer won't change; retrying only delays the error UI), at most two retries for transient failures.
- **Messages**: never render `error.message` (it's the internal `API <status>` / the browser's "Failed to fetch") — map statuses to i18n keys via the shared mappers in `utils/saveError.ts`: `saveErrorMessage(err, t, keys)` for mutations (per-status keys + a `failed` fallback) and `loadErrorMessage(err, t)` for list loads. Errors render inline as `color="red" variant="light"` Alerts — never as toasts.
- The `@mantine/notifications` host is mounted in `main.tsx` (top-center, autoClose 2500, limit 3 — deliberately not in App, so unit tests never mount it). **Success toasts only, with fixed vocabulary only** (`showSuccessToast(t("<area>.toast.*"))` in `utils/toast.tsx` — teal, never user-entered values; errors stay inline).

## Shared list-page building blocks (the CatalogFiles.tsx template)

Every list page composes the same ported Lettuce blocks — copy `pages/CatalogFiles.tsx`, don't re-derive:

- **State**: filters in `useStoredState` (persisted under `toadie.viewSettings.<viewKey>.filter.*`, text filters debounced 300 ms — the DEBOUNCED value goes into the query key), sort/page/pageSize from `usePagedSort(initialSort, filterDeps, { key, sortFields })` with `SORT_FIELDS ... as const`.
- **Query**: `useQuery({ queryKey: ["<area>", page, pageSize, sortParam, ...filters], queryFn: list<Area>(...), placeholderData: keepPreviousData })`.
- **Chrome**: `FilterPanel` (collapsed by default, persisted, active-count badge) + `ClearableTextInput`; `SortHeader` in the `Table.Th`s; `TableLoadingRow` (`isLoading && !data`) / rows / `EmptyState` (`!isError`) triage in the tbody; a `color="red" variant="light"` Alert with `loadErrorMessage` ABOVE the table on error; `PaginationBar` below.
- **Delete**: `useDeleteConfirm` + `ConfirmDeleteModal` — the hook owns modal state and the success toast, the page owns `invalidateQueries`.
- Row action buttons carry interpolated aria-labels (`<area>.editAria` etc.) — unit tests and e2e locate by them; table tests query cells by **text**, not `cell` role names.

## Forms (the CreateCatalogFile/EditCatalogFile template)

- Shared vocabulary in `utils/<area>Form.ts`: the `<Area>FormValues` type, length constants mirroring the server's, a `<area>FormValidation(t)` factory (rules identical to the server's — keep them in sync), and `toRequest`/`fromResponse` mappers. The catalog form is **kind-aware**: `KIND_FIELDS`/`KIND_REQUIRED` mirror the server's per-kind tables, every rule no-ops for non-applicable fields (a kind switch leaves no stale errors), and `toCatalogFileRequest` strips fields foreign to the submitted kind while keeping Group `children` / User `memberOf` present-and-empty. The field block lives in `components/<Area>FormFields.tsx`; the pages own submit/error/navigation. **Reference pickers**: every ref field is an Autocomplete/TagsInput fed by `hooks/useCatalogIdentities.ts` (the pool loop over the list endpoint, 5-min cache, refreshed by `["catalogFiles", …]` invalidations) through the pure `utils/refSuggestions.ts` (per-field target kinds + `shortestRef` — the minimal form that still resolves; dependsOn/dependencyOf suggestions always kind-prefixed). Free text stays legal, and pool loading/failure degrades to plain inputs — never a red state.
- Edit prefills via **`form.initialize(...)` guarded by `!form.initialized` during render** — never a `useEffect`. Submit is a plain async fn with local `submitting`/`error` state wrapped by `form.onSubmit`; success → `invalidateQueries` (list + detail) → `showSuccessToast` → `navigate(list, { replace: true })`; failure → `saveErrorMessage` into an inline Alert.
- **Widths**: `Container size="sm"` for simple field forms (the Lettuce rule). The catalog editor is the sanctioned deviation: a document screen rendered as a full-width `Grid` — form (`md=7`) beside a sticky live-preview card (`md=5`, `components/YamlPreviewCard.tsx`). Reuse that split for future document editors.
- `utils/catalogYaml.ts` owns catalog-info.yaml rendering (canonical key order, empties omitted, `default` namespace implicit) + the `downloadYaml` Blob helper — client-side by design (the render pillar became the graph, not a combined YAML document; add server-side YAML only if an export need arrives).

## User management (`pages/Users|CreateUser|EditUser|ChangePassword.tsx`)

Passwords are generated client-side (`utils/password.ts`) and revealed exactly once: the modal sets `closeOnClickOutside={false} closeOnEscape={false}`, renders `components/RevealablePassword.tsx` (masked by default, eye toggle, copy), and closing drops the plaintext from state for good — the server never returns it. The admin Reset-password action reuses the same generate→PUT→reveal flow; the create page adds a client-only mailto onboarding draft (CRLF bodies per RFC 6068). `NavLeaf.adminOnly` gates the Users nav item (the Shell filters by `isAdmin()`); the pages `Navigate` non-admins away as the backstop.

## The render graph (`pages/RenderGraph.tsx`)

`@xyflow/react` (React Flow 12) + `@dagrejs/dagre` draw `GET /api/v1/catalog-files/graph`; both deps ride the lazy `/render` chunk — keep them out of eagerly-imported modules. The pure shaping lives in `utils/graphLayout.ts` (`filterGraph` relation-family filtering + orphaned-virtual-node pruning, `layoutGraph` dagre-LR → React Flow nodes/edges) and carries the unit-test coverage; **unit tests stub `@xyflow/react`** (happy-dom can't give it real DOM measurement — see `RenderGraph.test.tsx`'s `vi.mock`), and e2e exercises the real canvas. The custom node (`components/CatalogGraphNode.tsx`) styles by status through Mantine CSS vars only, and the canvas `colorMode` follows `useComputedColorScheme` — never hardcode colors there.

## Internationalization (i18n)

The SPA is **N-language by architecture** via react-i18next (`src/i18n.ts`); the shipped bundles are English (THE default and fallback everywhere) and Polish. All user-facing strings go through `const { t } = useTranslation()` / `<Trans>` — **no hardcoded UI text**. Conventions:

- **Resources** live in `src/locales/{en,pl}/<area>.json`, one file per area (`common`, `appShell`, `auth` today); `i18n.ts` merges them into a single `translation` namespace, so keys read `area.key` (e.g. `t("auth.signIn")`). Only EN is statically imported — its typed `en` tree is the key canon AND the runtime fallback; every other language is auto-discovered from `locales/<lang>/` via `import.meta.glob`. Bundles are eager on purpose; move non-EN to lazy loading when a 3rd language ships or a bundle grows large.
- **Keys are typed**: `src/i18next.d.ts` augments i18next's `CustomTypeOptions` with `typeof en` from `i18n.ts` (the `@public`-tagged export — knip can't trace the d.ts consumer), so every `t()` call and `i18nKey` is compile-checked against the EN tree — a typo or removed key fails `tsc`/`npm run build`. Fields holding a key are typed `ParseKeys` (from `i18next`), never `string` (the `NAV_ITEMS` `label` pattern); functions taking a translator take `TFunction`, never a hand-written `(key: string) => string`.
- **`common.*` is the shared source**: actions, field labels, shared vocabulary. Reuse it instead of duplicating; build Mantine `Select` option labels from `t()` at render so they translate.
- **Keep key parity vs EN for every shipped language** — a shipped bundle is all-or-nothing: every English key must exist in each `locales/<lang>/` (language-specific plural variants are expected), enforced by `locales/parity.test.ts` (auto-discovers language folders; also pins folders == `SUPPORTED_LANGUAGES`). Use i18next interpolation (`{{name}}`) and plural keys rather than string concatenation.
- **Adding a language** = a complete `locales/<lang>/` folder (every area file, parity-gated) + one code in `SUPPORTED_LANGUAGES` (`i18n.ts`) + one `NATIVE_LANGUAGE_NAMES` line — the Record type and the parity test each fail loudly on a missed step. (When server-composed per-user-language content is ported from Lettuce, its `LocalizedText` constructor arity becomes the server-side parity gate too.)
- **Polish voice convention** (inherited from Lettuce): inclusive slash forms, active/direct voice, same tense and meaning as the English; spell out irregular feminines in full. No impersonal/passive dodges.
- The language switcher is `components/LanguageSwitcher.tsx` (header) — a Mantine `Menu` of NATIVE language names (`NATIVE_LANGUAGE_NAMES`, deliberate constants: readable before switching, and `Intl.DisplayNames` yields lowercase forms); choice persists in `localStorage` (`toadie.lang`) and updates `<html lang>` (the `languageChanged` hook in `i18n.ts`).
- **Tests render English**: `src/test/setup.ts` imports `../i18n` and forces `en`, so text-based assertions match the EN resources.

## Theming

**The design language is theme-owned** (Lettuce's "clean enterprise SaaS" posture) — `src/theme.ts` + `src/theme.module.css` are the single source:

- The brand is the 10-stop **`toadie` amber tuple** (warm amber/brown toad scale) with `primaryShade: { light: 7, dark: 8 }` — deep, calm CTAs — and `autoContrast: true`. The brand amber is the interactive accent (buttons/links/active nav inherit it); **never reintroduce `color="blue"` actions**, and when semantic success states arrive, use **teal**, never stock `green` (which would impersonate a brand color).
- `theme.module.css` is scheme-aware **via `light-dark()`** throughout (tables card-framed on a quiet tinted canvas, white header/navbar surfaces, hairline borders) — new surface styling follows that pattern, not `[data-mantine-color-scheme]` selectors. Every `Table` inherits the card frame + hoverable rows + neutral compact header from the theme's `Table.extend` — don't add per-table frames.
- Soft diffuse `shadows` scale; tightened heading sizes (pages title themselves with `order={2}`).
- Inter is bundled (`@fontsource-variable/inter`, imported in `main.tsx`) so it loads same-origin and satisfies the CSP `font-src 'self'`; the system stack is the fallback.
- The logo SVGs (`public/logo-*.svg`, rendered by `components/BrandLogo.tsx`) are the brand mark. Restyle rule: keep aria-labels, roles, and real semantic elements stable — e2e and unit tests locate by role/name.

## App versioning

The app's only human-readable version is `APP_VERSION` in **`src/changelog/version.ts`** — its own tiny module so the shell's eager imports (VersionStamp) stay lean; the Gradle `1.0.0-SNAPSHOT` is unrelated. The Lettuce changelog page (`changelog/entries.ts`, the `/changelog` route, the "what's new" navbar dot) is **not yet ported** — when it is, a release becomes: the newest EN+PL entry at the top of `entries.ts` + the bump of this one literal, pinned by the `CHANGELOG[0].version === APP_VERSION` test.

## Not yet ported from Lettuce (port, don't reinvent)

When a feature needs one of these, port Lettuce's `web/` implementation and its `web/CLAUDE.md` section wholesale:

- **Per-user feature flags** (`hasFeature()`, nav/route/page gating in lockstep).
- **Changelog entries + page** (see "App versioning").
- **Link builders** (`utils/*Links.ts` — never hand-assemble app URLs once a second surface links to a screen) and the `safeBackParam` open-redirect guard for any future `?back=` param.
