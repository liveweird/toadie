import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";
import i18n from "../i18n";

// Every Mantine Transition is SYNCHRONOUS under test: `useTransition` skips its rAF→rAF→setTimeout
// chain and sets the status directly when the theme respects reduced motion AND the hook reports
// it (use-transition.mjs: `newTransitionDuration === 0`). `env="test"` alone does NOT do this — the
// `Transition` COMPONENT short-circuits its render under `env="test"`, but it calls `useTransition`
// unconditionally first, so the hook's timer chain still runs — and a Button loader's 150 ms
// Transition, or a Modal's, kept firing a state update after RTL's afterEach cleanup had torn
// down the happy-dom window ("ReferenceError: window is not defined" as a vitest Unhandled Error:
// CI on PRs #26, #40, #38, #63, each in a different test file). `renderWithProviders` and every
// file-local MantineProvider set `respectReducedMotion: true`; the OTHER half — the hook reporting
// reduced motion — is the `window.matchMedia` wrapper below, NOT the `vi.mock` of `@mantine/hooks`:
// vitest applies `vi.mock` to the test module graph it transforms, and `@mantine/core` is an
// EXTERNAL dependency that imports the real `@mantine/hooks` natively, so the mock never reached
// Mantine's own `useTransition` (2026-09-14 — pinned by `reducedMotion.test.tsx`). The real
// `useReducedMotion` is `useMediaQuery("(prefers-reduced-motion: reduce)")`, read from
// `window.matchMedia` in an effect, which the wrapper answers `matches: true`. The mock stays for
// app code that calls `useReducedMotion` directly (none today) — it is cheap and never wrong.
vi.mock("@mantine/hooks", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@mantine/hooks")>()),
  useReducedMotion: () => true,
}));

// Deterministic English in tests (the global i18n instance is shared by every test, including the
// many that render with their own inline providers — no per-test I18nextProvider needed).
void i18n.changeLanguage("en");

if (typeof globalThis.localStorage === "undefined") {
  const store = new Map<string, string>();
  const localStorageMock: Storage = {
    get length() {
      return store.size;
    },
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    getItem: (key: string) => (store.has(key) ? store.get(key)! : null),
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
  };
  Object.defineProperty(globalThis, "localStorage", {
    value: localStorageMock,
    configurable: true,
  });
  if (typeof window !== "undefined") {
    Object.defineProperty(window, "localStorage", {
      value: localStorageMock,
      configurable: true,
    });
  }
}

// `matchMedia`: answer "reduced motion" so Mantine's REAL `useReducedMotion` (inside the external
// `@mantine/core`, see the note above) reports true and every Transition completes synchronously;
// every other query delegates to happy-dom's own implementation (the colour-scheme manager's
// `prefers-color-scheme` keeps behaving as before). A missing implementation falls back to a
// never-matching stub, the pre-2026-09-14 shape.
const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";
if (typeof window !== "undefined") {
  const original = typeof window.matchMedia === "function" ? window.matchMedia.bind(window) : undefined;
  const stub = (query: string, matches: boolean): MediaQueryList =>
    ({
      matches,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
  window.matchMedia = (query: string) =>
    query === REDUCED_MOTION_QUERY ? stub(query, true) : original ? original(query) : stub(query, false);
}

// happy-dom does not implement the FontFaceSet API. Mantine's autosize Textarea
// subscribes to `document.fonts` "loadingdone" events on mount; without this shim
// it throws "Cannot read properties of undefined (reading 'addEventListener')".
if (typeof document !== "undefined" && !document.fonts) {
  Object.defineProperty(document, "fonts", {
    value: {
      addEventListener: () => {},
      removeEventListener: () => {},
      ready: Promise.resolve(),
    },
    configurable: true,
  });
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});
