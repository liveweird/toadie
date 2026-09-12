/* eslint-disable react-refresh/only-export-components */
// -- test scaffolding: the wrapper exports helpers beside the component; fast-refresh is irrelevant under vitest
import { type ReactElement } from "react";
import { act, render, type RenderOptions } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { theme } from "../theme";

interface Options extends Omit<RenderOptions, "wrapper"> {
  route?: string;
}

export function renderWithProviders(ui: ReactElement, options: Options = {}) {
  const { route = "/", ...rest } = options;
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(ui, {
    wrapper: ({ children }) => (
      <MantineProvider env="test" theme={theme}>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>
    ),
    ...rest,
  });
}

export * from "@testing-library/react";

/**
 * Drain Mantine's Button loader `Transition` before a test returns. Button.mjs mounts its loader
 * in a `Transition duration={150}` that `env="test"` does NOT short-circuit (`env` only skips
 * the styles; `useTransition` still schedules its rAF→rAF→setTimeout(150) chain), so every test
 * that toggles a submit button's `loading` true→false leaves that chain pending. If it fires
 * after RTL's afterEach `cleanup()` it dispatches a state update into the torn-down happy-dom
 * window — "ReferenceError: window is not defined" as a vitest Unhandled Error (CI on PR #26,
 * then PR #40 — the same race, different test file). Await this as the LAST statement of any
 * test whose submit went through a loading state, so the update lands while still mounted.
 */
export const settleButtonTransition = () =>
  act(() => new Promise<void>((resolve) => setTimeout(resolve, 300)));
