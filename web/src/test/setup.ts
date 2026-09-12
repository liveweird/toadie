import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";
import i18n from "../i18n";

// Every Mantine Transition is SYNCHRONOUS under test: `useTransition` skips its rAF→rAF→setTimeout
// chain and sets the status directly when the theme respects reduced motion AND the hook reports
// it (use-transition.mjs: `newTransitionDuration === 0`). `env="test"` alone does NOT do this — it
// only drops the transition STYLES — so a Button loader's 150 ms Transition, or a Modal's, kept
// firing a state update after RTL's afterEach cleanup had torn down the happy-dom window
// ("ReferenceError: window is not defined" as a vitest Unhandled Error: CI on PRs #26, #40, #38,
// each in a different test file). `renderWithProviders` and every file-local MantineProvider set
// `respectReducedMotion: true`; this mock supplies the other half.
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

if (typeof window !== "undefined" && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
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
