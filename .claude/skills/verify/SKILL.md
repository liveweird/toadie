---
name: verify
description: Drive the toadie SPA end-to-end with Playwright to observe a change working against the local dev stack. Use after nontrivial frontend/backend changes, before committing.
---

# Verifying changes end-to-end

## Handle

The surface is the SPA in a browser. Use the running local dev stack (preferred per project convention): `docker compose up postgres` + `./gradlew :server:run` + `cd web && npm run dev`, then drive `http://localhost:5174` (Vite serves the edited source with HMR; `/api` proxies to :8081). Check what's already up first: `lsof -nP -iTCP:8081 -iTCP:5174 -sTCP:LISTEN` — reuse a healthy stack, and remember stray `:server:run` JVMs squat :8081. Toadie's ports deliberately avoid Lettuce's (8080/5173/5432), so double-check WHICH app answers before concluding anything.

No Chrome-extension automation required: Playwright is installed in `e2e/node_modules`. A scratch script can import it directly:

```js
import { chromium } from "/<repo>/e2e/node_modules/playwright/index.mjs";
```

Chromium binaries are already installed (the e2e suite uses them).

## Drive recipe (gotchas that cost time)

- **Leftover sessions block the login form.** While `toadie.auth.*` localStorage keys exist, `RedirectIfAuthed` bounces `/login` to the home page and a `fill()` waits out the whole timeout. Clear first (the `e2e/tests/helpers.ts` trick): `await page.goto("/login"); await page.evaluate(() => localStorage.clear()); await page.goto("/login");`.
- **Mantine locators:** `getByLabel(/password/i)` is a strict-mode violation (matches the visibility-toggle button too). Use `getByRole("textbox", { name: ... })`.
- **Login:** seed admin `admin@toadie.local` / `changeme`. Keep logins to a minimum — the per-IP `/login` rate limit produces roaming 429s (though the dev stack lifts it to 1000/min; see below). Five consecutive FAILED logins for one email lock that account for 15 minutes (in-memory — restarting the server clears it).
- **Language probe:** switch via the header Language menu; the choice persists in `localStorage` (`toadie.lang`). (Unlike Lettuce, the language is not yet a server-stored per-user property — a hand-set `toadie.lang` + reload IS currently a valid probe; revisit when the Lettuce server-side language sync is ported.)
- **Lazy-route fill race (production bundle only):** after clicking a link to another SPA route, `waitForURL` passes while the OLD page is still rendered (React Router flips the URL before the lazy chunk mounts — instant in Vite dev, slow enough to bite against the built bundle). A locator that matches fields on both pages silently fills the old page's input, which then unmounts. Always `waitFor()` an element unique to the target page before filling.
- **Rate-limit self-interference:** `/login` and `/refresh` have per-IP token buckets (10/min — lifted to 1000/min in development mode — and 30/min). Curl "warm-up probes" against those endpoints eat the budget of the Playwright run that follows — probe readiness via `GET /` instead, or `docker restart toadie-app` to reset the in-memory buckets.

## Cleanup

The skeleton's API surface creates no records beyond sessions (`revoked_tokens` rows prune themselves), so there is usually nothing to clean. If a verification touched the database directly, remove the rows via psql: `docker compose exec postgres psql -U toadie toadie` (soft-delete users by setting `marked_as_deleted = true`, never `DELETE`). Use a recognizable marker like `verify.` in test emails so leftovers are findable. Never mutate the seed admin — if a probe changed its password, restore the V3 state (hash in `infra/db/Bootstrap.kt`, or `TestSeedState.restoreSeedAccounts()` from a test).
