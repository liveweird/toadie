/* eslint-disable react-refresh/only-export-components */
// -- test scaffolding: the wrapper exports helpers beside the component; fast-refresh is irrelevant under vitest
import { type ReactElement } from "react";
import { render, type RenderOptions } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { theme } from "../theme";

/** The app theme with reduced motion respected — paired with setup.ts's `useReducedMotion` mock, every Transition completes synchronously. */
const TEST_THEME = { ...theme, respectReducedMotion: true };

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
      <MantineProvider env="test" theme={TEST_THEME}>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>
    ),
    ...rest,
  });
}

export * from "@testing-library/react";

