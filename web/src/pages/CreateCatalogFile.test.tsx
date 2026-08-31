import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { notifications } from "@mantine/notifications";
import CreateCatalogFile from "./CreateCatalogFile";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import classes from "../theme.module.css";

const TOKEN_KEY = "toadie.auth.token";
// The pill marks, by the same class the component applies (vitest runs with css: true, so
// these are the real hashed names — asserting the rendered colour, not a literal).
const findingPillClass = classes.findingPill;
const invalidPillClass = classes.invalidPill;

type FetchMock = ReturnType<typeof vi.fn>;

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderCreate() {
  return renderWithProviders(
    <Routes>
      <Route path="/files/new" element={<CreateCatalogFile />} />
      <Route path="/files" element={<PathProbe />} />
    </Routes>,
    { route: "/files/new" },
  );
}

function mockPostStatus(mockFetch: FetchMock, status: number, body: unknown = { title: "x", status }) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files") {
      return Promise.resolve(jsonResponse(status, body));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function findSaveCall(mockFetch: FetchMock) {
  return mockFetch.mock.calls.find(
    ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/files",
  );
}

async function fillMinimalForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/^name( \*)?$/i), "my-svc");
  await user.click(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }));
  await user.click(await screen.findByRole("option", { name: "service" }));
  await user.click(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }));
  await user.click(await screen.findByRole("option", { name: "production" }));
  await user.type(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }), "group:default/platform");
}

describe("CreateCatalogFile page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    // The Type Select loads the per-kind dictionaries; serve them ABOVE the per-test mock so
    // every existing mockImplementation (and findSaveCall over mockFetch.mock.calls) is
    // untouched by the registry traffic.
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/annotation-keys") {
        return Promise.resolve(
          jsonResponse(200, {
            items: [{ id: 1, key: "github.com/project-slug", kinds: ["Component"] }],
          }),
        );
      }
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/dictionaries/lifecycles") {
        return Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 1, value: "experimental", isDefault: false },
              { id: 2, value: "production", isDefault: false },
            ],
          }),
        );
      }
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/entity-types") {
        return Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 1, kind: "Component", types: ["service", "website"] },
              { id: 2, kind: "API", types: ["openapi"] },
              { id: 3, kind: "Group", types: ["team"] },
            ],
          }),
        );
      }
      return (mockFetch as unknown as (u: string, i?: RequestInit) => Promise<Response>)(url, init);
    });
    localStorage.setItem(TOKEN_KEY, "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("client-side validation blocks an empty submission", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(
      await screen.findByText(/1–63 alphanumeric characters with single/i),
    ).toBeInTheDocument();
    expect(screen.getAllByText("Required").length).toBeGreaterThanOrEqual(3);
    // The advisory reference panel may POST /check — only the SAVE call must be absent.
    expect(findSaveCall(mockFetch)).toBeUndefined();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("empty registries surface every none-defined hint once loaded", async () => {
    // Every registry answers an EMPTY list (not an error): the pickers must say "none
    // defined" — and only after loading resolves, never during the fetch.
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") !== "GET") return Promise.resolve(jsonResponse(404, {}));
      if (url.startsWith("/api/v1/files")) {
        return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
      }
      return Promise.resolve(jsonResponse(200, { items: [] }));
    });
    renderCreate();

    expect(await screen.findByText(/no namespaces are defined/i)).toBeInTheDocument();
    expect(screen.getByText(/no types are defined for kind Component/i)).toBeInTheDocument();
    expect(screen.getByText(/no lifecycles are defined/i)).toBeInTheDocument();
    expect(screen.getByText(/no labels are defined for kind Component/i)).toBeInTheDocument();
    expect(screen.getByText(/no annotation keys are/i)).toBeInTheDocument();
    expect(screen.getByText(/no tags are defined for kind Component/i)).toBeInTheDocument();
  });

  test("labels come from the registry pickers and land in the payload", async () => {
    // The registry offers one Component label; the row's two Selects are the only way in.
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/labels") {
        return Promise.resolve(
          jsonResponse(200, { items: [{ id: 1, key: "tier", values: ["backend", "frontend"], kinds: ["Component"] }] }),
        );
      }
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files") {
        return Promise.resolve(
          jsonResponse(201, {
            id: 9,
            kind: "Component",
            metadata: { name: "my-svc", namespace: "default" },
            spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
            createdBy: 1,
            creatorName: "A",
            creatorDeleted: false,
            createdAt: 1,
            updatedAt: 1,
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    // Adding is gated on the registry offering a label for this kind.
    await waitFor(() => expect(screen.getByRole("button", { name: /add label/i })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: /add label/i }));
    await user.click(screen.getByRole("combobox", { name: "Labels Key 1" }));
    await user.click(await screen.findByRole("option", { name: "tier" }));
    await user.click(screen.getByRole("combobox", { name: "Labels Value 1" }));
    await user.click(await screen.findByRole("option", { name: "backend" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(findSaveCall(mockFetch)).toBeDefined());
    const body = JSON.parse((findSaveCall(mockFetch)![1] as RequestInit).body as string);
    expect(body.metadata.labels).toEqual({ tier: "backend" });
  });

  test("a soft-rejected create opens the Save-anyway modal; confirming retries with allowInvalid", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "POST" && url === "/api/v1/files") {
        return Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }));
      }
      if (method === "POST" && url === "/api/v1/files?allowInvalid=true") {
        return Promise.resolve(jsonResponse(201, { id: 9 }));
      }
      if (method === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [{ field: "spec.owner", reference: "ghost-team", status: "MISSING" }],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    // The strict POST was rejected for soft findings — the modal lists them (scoped: the
    // live check panel renders the same finding text beside the form).
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByText("Save with findings?")).toBeInTheDocument();
    expect(within(modal).getByText("ghost-team")).toBeInTheDocument();
    expect(within(modal).getByText(/No stored entity matches this reference/)).toBeInTheDocument();

    await user.click(within(modal).getByRole("button", { name: /save anyway/i }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    const waived = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "POST" &&
        url === "/api/v1/files?allowInvalid=true",
    );
    expect(waived).toBeDefined();
  });

  test("a 400 the check cannot explain falls back to the validation alert — no modal", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "POST" && url === "/api/v1/files") {
        return Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }));
      }
      if (method === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(jsonResponse(200, { findings: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(/validation error/i)).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("tags come from the grouped category picker and land in the payload", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/tag-categories") {
        return Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 1, name: "Languages", tags: ["java", "c++"], kinds: ["Component"] },
              { id: 2, name: "Teams", tags: ["core"], kinds: ["Group"] },
            ],
          }),
        );
      }
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files") {
        return Promise.resolve(
          jsonResponse(201, {
            id: 9,
            kind: "Component",
            metadata: { name: "my-svc", namespace: "default" },
            spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
            createdBy: 1,
            creatorName: "A",
            creatorDeleted: false,
            createdAt: 1,
            updatedAt: 1,
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    // Only the Component category's tags are offered, grouped under its name; the Group
    // category never appears for a Component document.
    await user.click(screen.getByRole("combobox", { name: /^tags$/i }));
    expect(await screen.findByText("Languages")).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "core" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "java" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(findSaveCall(mockFetch)).toBeDefined());
    const body = JSON.parse((findSaveCall(mockFetch)![1] as RequestInit).body as string);
    expect(body.metadata.tags).toEqual(["java"]);
  });

  test("with no registry label for the kind, adding is disabled behind the hint", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/labels") {
        return Promise.resolve(
          jsonResponse(200, { items: [{ id: 1, key: "tier", values: ["backend"], kinds: ["API"] }] }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    expect(await screen.findByText(/No labels are defined for kind Component/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add label/i })).toBeDisabled();
    // The registry HAS an API label — switching kind re-enables adding.
    await user.click(screen.getByRole("combobox", { name: /^kind$/i }));
    await user.click(await screen.findByRole("option", { name: "API" }));
    await waitFor(() => expect(screen.getByRole("button", { name: /add label/i })).toBeEnabled());
  });

  test("submits the trimmed request, toasts, and navigates to the list", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    mockPostStatus(mockFetch, 201, {
      id: 9,
      kind: "Component",
      metadata: { name: "my-svc", namespace: "default" },
      spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
      createdBy: 1,
      creatorName: "A",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    const postCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/files",
    );
    expect(postCall).toBeDefined();
    expect(JSON.parse((postCall![1] as RequestInit).body as string)).toEqual({
      kind: "Component",
      // Blank namespace on the wire — the server resolves it to the flagged default.
      metadata: { name: "my-svc", namespace: "", labels: {}, annotations: {}, tags: [], links: [] },
      spec: {
        type: "service",
        lifecycle: "production",
        owner: "group:default/platform",
        providesApis: [],
        consumesApis: [],
        dependsOn: [],
        dependencyOf: [],
      },
    });
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ message: "Catalog file created", color: "teal" }),
    );
    toast.mockRestore();
  });

  test("switching the kind swaps the per-kind sections and submits a Group", async () => {
    mockPostStatus(mockFetch, 201, {
      id: 11,
      kind: "Group",
      metadata: { name: "team-a", namespace: "default" },
      spec: { type: "team", children: [], members: [] },
      createdBy: 1,
      creatorName: "A",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
    });
    const user = userEvent.setup();
    renderCreate();

    // Component sections show owner + APIs; switch to Group.
    expect(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" })).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Kind", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Group" }));

    // Group has no owner/lifecycle but gains profile + membership fields.
    expect(screen.queryByLabelText(/^owner( \*)?$/i, { selector: "input" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^lifecycle( \*)?$/i)).not.toBeInTheDocument();
    expect(screen.getByLabelText("Display name")).toBeInTheDocument();
    expect(screen.getByLabelText("Members", { selector: "input" })).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "team-a");
    await user.click(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "team" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    const postCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/files",
    );
    expect(JSON.parse((postCall![1] as RequestInit).body as string)).toEqual({
      kind: "Group",
      metadata: { name: "team-a", namespace: "", labels: {}, annotations: {}, tags: [], links: [] },
      spec: { type: "team", children: [], members: [] },
    });
  });

  test("an API without its definition is blocked client-side", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    fireEvent.click(screen.getByLabelText("Kind", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "API" }));
    expect(screen.getByLabelText(/^definition( \*)?$/i)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "billing-api");
    await user.click(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "openapi" }));
    await user.click(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "production" }));
    await user.type(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }), "team-a");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findAllByText("Required")).not.toHaveLength(0);
    expect(findSaveCall(mockFetch)).toBeUndefined();
  });

  test("annotation and link rows can be added and removed", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    // Adding is gated on the registry offering a key for this kind.
    await waitFor(() => expect(screen.getByRole("button", { name: /add annotation/i })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: /add annotation/i }));
    await user.click(screen.getByRole("combobox", { name: "Annotations Key 1" }));
    await user.click(await screen.findByRole("option", { name: "github.com/project-slug" }));
    await user.type(screen.getByLabelText("Annotations Value 1"), "acme/repo — any text");
    await user.click(screen.getByRole("button", { name: "Remove annotation 1" }));
    expect(screen.queryByRole("combobox", { name: "Annotations Key 1" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /add link/i }));
    await user.type(screen.getByLabelText("URL 1"), "https://example.com");
    await user.click(screen.getByRole("button", { name: "Remove link 1" }));
    expect(screen.queryByLabelText("URL 1")).not.toBeInTheDocument();
  });

  test("the owner picker suggests stored groups and inserts the shortened ref", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files") {
        return Promise.resolve(jsonResponse(201, {
          id: 12, kind: "Component",
          metadata: { name: "my-svc", namespace: "default" },
          spec: { type: "service", lifecycle: "production", owner: "team-a" },
          createdBy: 1, creatorName: "A", creatorDeleted: false, createdAt: 1, updatedAt: 1,
        }));
      }
      if (url.startsWith("/api/v1/files?")) {
        // The identity pool behind the pickers.
        return Promise.resolve(jsonResponse(200, {
          items: [
            { id: 5, kind: "Group", name: "team-a", namespace: "default", title: null, type: "team", lifecycle: null, owner: null, creatorName: "A", creatorDeleted: false, updatedAt: 1 },
            { id: 6, kind: "API", name: "billing-api", namespace: "default", title: null, type: "openapi", lifecycle: "production", owner: null, creatorName: "A", creatorDeleted: false, updatedAt: 1 },
          ],
          page: 1, pageSize: 100, total: 2,
        }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "my-svc");
    await user.click(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "service" }));
    await user.click(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "production" }));

    // Open the owner picker — the stored group is offered as its full identity;
    // the API is filtered out (wrong kind).
    fireEvent.click(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "group:default/team-a" }));
    expect(screen.queryByRole("option", { name: /billing-api/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^create$/i }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    const postCall = findSaveCall(mockFetch);
    expect(JSON.parse((postCall![1] as RequestInit).body as string).spec.owner).toBe("group:default/team-a");
  });

  test("the YAML preview follows the form values", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    const preview = screen.getByLabelText("YAML preview");
    expect(preview).toHaveTextContent("kind: Component");

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "preview-svc");
    expect(preview).toHaveTextContent("name: preview-svc");
  });

  test("the check panel lists the findings", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [
              { field: "spec.dependsOn", reference: "component:ghost", status: "MISSING" },
              { field: "spec.dependsOn", reference: "orders-db", status: "KIND_REQUIRED" },
              { field: "spec.owner", reference: "component:team-x", status: "WRONG_KIND" },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderCreate();

    expect(await screen.findByText("Findings — saving will ask for confirmation")).toBeInTheDocument();
    expect(screen.getByText("component:ghost")).toBeInTheDocument();
    expect(screen.getByText("orders-db")).toBeInTheDocument();
    expect(screen.getByText("component:team-x")).toBeInTheDocument();
  });

  test("each finding also shows on the control that produced it, in the soft-finding tint", async () => {
    // The panel keeps the aggregate; the fields carry the same verdicts so you see them where
    // you would fix them. Findings are SOFT — orange, not the red of a blocking error.
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [
              { field: "spec.owner", reference: "group:default/ghost", status: "MISSING" },
              { field: "spec.dependsOn", reference: "orders-db", status: "KIND_REQUIRED" },
              { field: "metadata.tags", reference: "cobol", status: "TAG_NOT_ALLOWED" },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderCreate();

    // Owner is single-valued, so the message alone — the offending value is in the input.
    const owner = await screen.findByRole("combobox", { name: /owner/i });
    await waitFor(() =>
      expect(owner).toHaveAccessibleDescription(/No stored entity matches this reference/),
    );
    // A multi-value field names WHICH entry is at fault, since nothing else identifies it.
    expect(screen.getByText(/orders-db: .*need an explicit kind/)).toBeInTheDocument();
    expect(screen.getByText(/cobol: .*do not allow this tag/)).toBeInTheDocument();
  });

  test("in a multi-value field the offending PILL is marked, not just the box", async () => {
    // The box tint cannot say which of several entries is at fault. Orange marks the flagged
    // one; once validation has run, red marks the malformed one and outranks orange.
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [{ field: "spec.dependsOn", reference: "orders-db", status: "KIND_REQUIRED" }],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    const dependsOn = await screen.findByRole("combobox", { name: /depends on/i });
    await user.type(dependsOn, "component:default/api{Enter}orders-db{Enter}");
    // The pill's text sits in Mantine's label span; the mark lands on the pill root above it.
    const control = dependsOn.closest("[class*='InputWrapper-root']") as HTMLElement;
    const pill = (text: string) => within(control).getByText(text).parentElement;

    await waitFor(() => expect(pill("orders-db")).toHaveClass(findingPillClass));
    expect(pill("orders-db")?.getAttribute("title")).toMatch(/need an explicit kind/i);
    expect(pill("component:default/api")).not.toHaveClass(findingPillClass);

    // A malformed entry (two slashes) — nothing until the field is blurred, then red.
    await user.type(dependsOn, "a//b{Enter}");
    expect(pill("a//b")).not.toHaveClass(invalidPillClass);
    await user.tab();

    await waitFor(() => expect(pill("a//b")).toHaveClass(invalidPillClass));
    expect(pill("a//b")?.getAttribute("title")).toMatch(/entity reference/i);
    // Red outranks orange: while the save is blocked, the field reports only the blocker.
    expect(pill("orders-db")).not.toHaveClass(findingPillClass);
  });

  test("a hard validation error outranks a finding on the same field", async () => {
    // "You cannot save this" beats "this saves with findings" — red wins over orange.
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [{ field: "spec.owner", reference: "", status: "MISSING" }],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    const owner = await screen.findByRole("combobox", { name: /owner/i });
    await user.click(owner);
    await user.tab();

    await waitFor(() => expect(owner).toHaveAccessibleDescription(/Required/));
    expect(owner).not.toHaveAccessibleDescription(/No stored entity matches/);
  });

  test("leaving a required field empty flags it before any submit", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, {})));
    const user = userEvent.setup();
    renderCreate();

    const name = await screen.findByLabelText(/^name( \*)?$/i);
    await user.click(name);
    await user.tab();

    await waitFor(() => expect(name).toHaveAccessibleDescription(/1–63 alphanumeric/));
    expect(findSaveCall(mockFetch)).toBeUndefined();
  });

  test("the check panel shows the all-clear line when everything passes", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(jsonResponse(200, { findings: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderCreate();

    expect(await screen.findByText("No findings — the document passes every check.")).toBeInTheDocument();
    expect(screen.queryByText("Findings — saving will ask for confirmation")).not.toBeInTheDocument();
  });

  test.each([
    [409, /already exists/i],
    [400, /validation error/i],
    [500, /create failed \(500\)/i],
  ])("a %i on save surfaces an error and stays on the form", async (status, pattern) => {
    mockPostStatus(mockFetch, status);
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(pattern)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });
});
