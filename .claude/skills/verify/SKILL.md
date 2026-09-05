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

For authentication migrations or other changes that would alter the running workspace, use
a separate disposable Compose project instead: override ALL container names, published ports,
and the app image tag; confirm its database volume is separate, then point Playwright's
`E2E_BASE_URL` at it. Never run a migration against the user's dev volume merely to verify it.
Remove only that explicit test project's containers/network/volume afterwards.

## Drive recipe (gotchas that cost time)

- **Leftover sessions block the login form.** While `toadie.auth.*` localStorage keys exist, `RedirectIfAuthed` bounces `/login` to the home page and a `fill()` waits out the whole timeout. Clear first (the `e2e/tests/helpers.ts` trick): `await page.goto("/login"); await page.evaluate(() => localStorage.clear()); await page.goto("/login");`.
- **Mantine locators:** `getByLabel(/password/i)` is a strict-mode violation (matches the visibility-toggle button too). Use `getByRole("textbox", { name: ... })`.
- **Login:** seed admin `admin@toadie.local` / `changeme`. Keep logins to a minimum — the per-IP `/login` rate limit produces roaming 429s (though the dev stack lifts it to 1000/min; see below). Five consecutive FAILED logins for one email lock that account for 15 minutes (in-memory — restarting the server clears it).
- **Language probe:** switch via the header Language menu on a throwaway account. Language is stored both locally and on the user; login/refresh restores the server value, so setting `toadie.lang` alone is not a full persistence probe. Never change the seed admin's language during parallel tests.
- **Lazy-route fill race (production bundle only):** after clicking a link to another SPA route, `waitForURL` passes while the OLD page is still rendered (React Router flips the URL before the lazy chunk mounts — instant in Vite dev, slow enough to bite against the built bundle). A locator that matches fields on both pages silently fills the old page's input, which then unmounts. Always `waitFor()` an element unique to the target page before filling.
- **Rate-limit self-interference:** `/login` and `/refresh` have per-IP token buckets (10/min — lifted to 1000/min in development mode — and 30/min). Curl "warm-up probes" against those endpoints eat the budget of the Playwright run that follows — probe readiness via `GET /` instead, or `docker restart toadie-app` to reset the in-memory buckets.

## Cleanup

The app creates real users, catalog files, and registry records. Give fixtures recognizable
unique markers and remove them through their normal APIs (business entities soft-delete).
The e2e suite owns and cleans its per-run namespaces and throwaway records; any remaining
data on a disposable verification project is removed with that project's volume. Never delete
the development volume or mutate the seed admin. Expired auth session/blocklist rows are
pruned by the normal authentication paths; no manual production cleanup is needed for a probe.
