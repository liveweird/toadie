import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import EntityOptionsSelect from "./EntityOptionsSelect";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function serveEntities(mockFetch: FetchMock, items: { id: number; identifier: string; title: string }[]) {
  mockFetch.mockImplementation(() =>
    Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 100, total: items.length })),
  );
}

function SingleHarness({ initial = "" }: { initial?: string }) {
  const form = useForm({ initialValues: { value: initial } });
  return (
    <div>
      <EntityOptionsSelect
        mode="single"
        target="_team"
        label="Team"
        failedHint="Team options failed to load"
        emptyHint="No teams yet"
        inputProps={form.getInputProps("value")}
      />
      <div data-testid="value">{form.values.value}</div>
    </div>
  );
}

function MultiHarness({ initial = [] as string[] }: { initial?: string[] }) {
  const form = useForm({ initialValues: { value: initial } });
  return (
    <div>
      <EntityOptionsSelect
        mode="multi"
        target="_team"
        label="Teams"
        failedHint="Team options failed to load"
        emptyHint="No teams yet"
        inputProps={form.getInputProps("value")}
      />
      <div data-testid="value">{JSON.stringify(form.values.value)}</div>
    </div>
  );
}

describe("EntityOptionsSelect", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("single mode renders a Select labelled identifier — title and picking sets the value", async () => {
    serveEntities(mockFetch, [{ id: 1, identifier: "platform", title: "Platform" }]);
    const user = userEvent.setup();
    renderWithProviders(<SingleHarness />);

    const select = await screen.findByRole("combobox", { name: "Team" });
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "platform — Platform" }));
    expect(screen.getByTestId("value")).toHaveTextContent("platform");
  });

  test("single mode's failed-load hint shows in the description", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, null));
    renderWithProviders(<SingleHarness />);
    expect(await screen.findByText("Team options failed to load")).toBeInTheDocument();
  });

  test("single mode's empty-pool hint shows once loaded", async () => {
    serveEntities(mockFetch, []);
    renderWithProviders(<SingleHarness />);
    expect(await screen.findByText("No teams yet")).toBeInTheDocument();
  });

  test("single mode appends a stale stored value so it keeps displaying", async () => {
    serveEntities(mockFetch, [{ id: 1, identifier: "platform", title: "Platform" }]);
    renderWithProviders(<SingleHarness initial="gone" />);
    expect(await screen.findByRole("combobox", { name: "Team" })).toHaveValue("gone");
  });

  test("multi mode renders a MultiSelect and appends a stale value", async () => {
    serveEntities(mockFetch, [{ id: 1, identifier: "platform", title: "Platform" }]);
    renderWithProviders(<MultiHarness initial={["gone"]} />);
    expect(await screen.findByRole("combobox", { name: "Teams" })).toBeInTheDocument();
    expect(screen.getAllByText("gone").length).toBeGreaterThan(0);
  });

  test("multi mode picking an option adds it to the value array", async () => {
    serveEntities(mockFetch, [{ id: 1, identifier: "platform", title: "Platform" }]);
    const user = userEvent.setup();
    renderWithProviders(<MultiHarness />);

    const select = await screen.findByRole("combobox", { name: "Teams" });
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "platform — Platform" }));
    expect(screen.getByTestId("value")).toHaveTextContent('["platform"]');
  });
});
