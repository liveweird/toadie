import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import CatalogFileFilterControls from "./CatalogFileFilterControls";
import CatalogKindPills from "./CatalogKindPills";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const VIEW_KEY = "filterControlsTest";
const STORE_PREFIX = `toadie.viewSettings.${VIEW_KEY}.filter`;

// The option sources the controls load: registries, dictionaries, and the identity pool.
const RESPONSES: [string, unknown][] = [
  ["/api/v1/dictionaries/namespaces", { items: [{ id: 1, value: "default", isDefault: true }] }],
  ["/api/v1/dictionaries/lifecycles", { items: [{ id: 1, value: "production", isDefault: false }] }],
  [
    "/api/v1/entity-types",
    {
      items: [
        { id: 1, kind: "Component", types: ["service", "library"] },
        { id: 2, kind: "API", types: ["openapi", "service"] },
      ],
    },
  ],
  ["/api/v1/labels", { items: [{ id: 1, key: "example.com/tier", values: ["backend", "edge"], kinds: ["Component"] }] }],
  ["/api/v1/tag-categories", { items: [{ id: 1, name: "Tech", tags: ["java"], kinds: ["Component"] }] }],
  [
    "/api/v1/files",
    {
      items: [
        {
          id: 7,
          kind: "Group",
          name: "platform",
          namespace: "default",
          title: null,
          type: "team",
          lifecycle: null,
          owner: null,
          tags: [],
          creatorName: "A",
          creatorDeleted: false,
          updatedAt: 1,
        },
      ],
      page: 1,
      pageSize: 100,
      total: 1,
    },
  ],
];

function stubFetch(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    const hit = RESPONSES.find(([prefix]) => url.startsWith(prefix));
    return Promise.resolve(hit ? jsonResponse(200, hit[1]) : jsonResponse(404, {}));
  });
}

/** The full filter surface (pills + controls) over the real hook, derived outputs probed. */
function Harness() {
  const filters = useCatalogFileFilterState(VIEW_KEY);
  return (
    <>
      <CatalogKindPills kinds={filters.controls.kinds} setKinds={filters.controls.setKinds} />
      <CatalogFileFilterControls controls={filters.controls} />
      <div data-testid="count">{filters.activeFilterCount}</div>
      <div data-testid="noKinds">{String(filters.noKinds)}</div>
      <div data-testid="values">{JSON.stringify(filters.values)}</div>
    </>
  );
}

const values = () => JSON.parse(screen.getByTestId("values").textContent ?? "{}") as Record<string, unknown>;

async function pickOption(label: string, option: string) {
  fireEvent.click(screen.getByLabelText(label, { selector: "input" }));
  fireEvent.click(await screen.findByRole("option", { name: option }));
}

describe("CatalogFileFilterControls + useCatalogFileFilterState", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    stubFetch(mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the Type options group by kind (duplicates allowed) and follow the visible kinds", async () => {
    renderWithProviders(<Harness />);

    // All kinds visible by default — one group per kind; "service" legally appears under
    // BOTH (kind-prefixed values).
    fireEvent.click(screen.getByLabelText("Type", { selector: "input" }));
    await screen.findByRole("option", { name: "library" });
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual([
      "library",
      "service",
      "openapi",
      "service",
    ]);
    // Picking the duplicate label sets the BARE type (the prefix never leaks).
    fireEvent.click(screen.getAllByRole("option", { name: "service" })[1]);
    await waitFor(() => expect(values().type).toBe("service"));

    // Hiding a kind drops its group from the Type options.
    fireEvent.click(screen.getByRole("checkbox", { name: "Component" }));
    fireEvent.click(screen.getByLabelText("Type", { selector: "input" }));
    await screen.findByRole("option", { name: "openapi" });
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["openapi", "service"]);
  });

  test("the kind pills are a visible set: all on by default, subsets travel, none means noKinds", async () => {
    renderWithProviders(<Harness />);

    // All seven start ON and — being the no-filter default — send NO kind param.
    expect(screen.getAllByRole("checkbox", { checked: true })).toHaveLength(7);
    expect(values().kind).toBeUndefined();
    expect(screen.getByTestId("noKinds")).toHaveTextContent("false");

    // Each pill shows its tier dot, but the dot is aria-hidden: the checkbox accessible
    // name stays the bare kind (this locator IS the pin — everything below relies on it).
    const groupPill = screen.getByRole("checkbox", { name: "Group" });
    expect(groupPill.closest(".mantine-Chip-root")?.querySelector('[data-tier="1"]')).not.toBeNull();

    // Toggling one OFF sends the remaining six, in canonical kind order.
    fireEvent.click(screen.getByRole("checkbox", { name: "Group" }));
    await waitFor(() =>
      expect(values().kind).toEqual(["Component", "API", "System", "Domain", "Resource", "User"]),
    );
    // The pills are always visible — they never count into the hidden-filter badge.
    expect(screen.getByTestId("count")).toHaveTextContent("0");

    // Toggling everything OFF flips noKinds (the page shows nothing and must not fetch).
    for (const kind of ["Component", "API", "System", "Domain", "Resource", "User"]) {
      fireEvent.click(screen.getByRole("checkbox", { name: kind }));
    }
    await waitFor(() => expect(screen.getByTestId("noKinds")).toHaveTextContent("true"));
    expect(values().kind).toBeUndefined();
  });

  test("a junk persisted visible-kinds value falls back to all-on", () => {
    localStorage.setItem(`${STORE_PREFIX}.visibleKinds`, JSON.stringify(["Component", "Gadget"]));
    renderWithProviders(<Harness />);
    expect(screen.getAllByRole("checkbox", { checked: true })).toHaveLength(7);
    expect(values().kind).toBeUndefined();
  });

  test("filter picks land in the normalized values and the active count", async () => {
    renderWithProviders(<Harness />);

    await pickOption("Lifecycle", "production");
    await pickOption("Owner", "group:default/platform");
    await waitFor(() => {
      expect(values()).toMatchObject({ lifecycle: "production", owner: "group:default/platform" });
    });
    expect(screen.getByTestId("count")).toHaveTextContent("2");
  });

  test("the label values MultiSelect needs a key, counts separately, and resets on key change", async () => {
    renderWithProviders(<Harness />);

    expect(screen.getByLabelText("Label values", { selector: "input" })).toBeDisabled();
    await pickOption("Label", "example.com/tier");
    await pickOption("Label values", "backend");
    await waitFor(() => {
      expect(values()).toMatchObject({ label: "example.com/tier", labelValue: ["backend"] });
    });
    expect(screen.getByTestId("count")).toHaveTextContent("2");

    // Clearing the key (the clear button) drops the values with it — no orphaned labelValue.
    fireEvent.click(await screen.findByLabelText("Clear label filter"));
    await waitFor(() => {
      expect(values().label).toBeUndefined();
      expect(values().labelValue).toBeUndefined();
    });
  });

  test("stale persisted values keep displaying and travel until cleared", async () => {
    // Persisted filters whose registry rows are gone (the stale idiom) — plus a corrupt
    // labelValue slot that must fall back silently.
    localStorage.setItem(`${STORE_PREFIX}.type`, JSON.stringify("retired-type"));
    localStorage.setItem(`${STORE_PREFIX}.owner`, JSON.stringify("group:default/ghost"));
    localStorage.setItem(`${STORE_PREFIX}.label`, JSON.stringify("gone/key"));
    localStorage.setItem(`${STORE_PREFIX}.labelValue`, JSON.stringify("not-an-array"));
    renderWithProviders(<Harness />);

    expect(screen.getByLabelText("Type", { selector: "input" })).toHaveValue("retired-type");
    expect(screen.getByLabelText("Owner", { selector: "input" })).toHaveValue("group:default/ghost");
    expect(values()).toMatchObject({ type: "retired-type", owner: "group:default/ghost", label: "gone/key" });
    expect(values().labelValue).toBeUndefined();
  });
});
