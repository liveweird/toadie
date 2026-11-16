import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { jsonResponse, mockFetch } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { blueprintResponse } from "../test/fixtures";
import type { EntityGraph, EntityGraphNode } from "../api/entities";

// The entity query bar's real CodeMirror editor is covered by its own tests — page tests drive a
// plain textarea stand-in (the EntityHierarchy.test.tsx idiom).
vi.mock("../components/QueryEditor", () => ({
  default: ({ value, onChange, ariaLabel }: { value: string; onChange: (value: string) => void; ariaLabel: string }) => (
    <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

import EntityBulkChange from "./EntityBulkChange";

function node(blueprint: string, identifier: string, entityId: number): EntityGraphNode {
  return {
    id: `${blueprint}|${identifier}`,
    entityId,
    blueprint,
    blueprintTitle: blueprint === "team" ? "Team" : "Service",
    identifier,
    title: identifier.toUpperCase(),
    findings: 0,
  };
}

// team platform ⊃ checkout, billing (relation "team" = the composition hierarchy); team empty has no
// service; "solo" is a service without a team.
const GRAPH: EntityGraph = {
  nodes: [
    node("team", "platform", 1),
    node("team", "empty", 2),
    node("service", "checkout", 10),
    node("service", "billing", 11),
    node("service", "solo", 12),
  ],
  edges: [
    { sourceId: "service|checkout", targetId: "team|platform", relation: "team", hierarchies: ["composition"], ownership: false },
    { sourceId: "service|billing", targetId: "team|platform", relation: "team", hierarchies: ["composition"], ownership: false },
  ],
};

const BLUEPRINTS = [
  blueprintResponse({ id: 1, identifier: "service", title: "Service", schema: { properties: { stage: { type: "string", title: "Stage", enum: ["beta", "live"] } }, required: [] }, relations: { team: { title: "Team", target: "team", required: false, many: false } }, hierarchyRelations: { composition: "team" } }),
  blueprintResponse({ id: 2, identifier: "team", title: "Team" }),
];

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname + location.search}</div>;
}

let graphUrls: string[];
let graph: EntityGraph;
let graphStatus: number;
let emptyAfterApply: boolean;

function renderPage(route = "/entities/bulk-change?blueprint=service") {
  vi.stubGlobal(
    "fetch",
    mockFetch([
      { match: "/api/v1/blueprints", respond: { status: 200, body: { items: BLUEPRINTS } } },
      { match: "/api/v1/dictionaries/hierarchies", respond: { status: 200, body: { items: [{ id: 1, value: "composition", isDefault: false }] } } },
      { match: "/api/v1/entity-queries", respond: { status: 200, body: { items: [] } } },
      { method: "POST", match: "/api/v1/entities/query/check", respond: { status: 200, body: { diagnostics: [] } } },
      { match: /\/api\/v1\/entities\?/, respond: { status: 200, body: { items: [], page: 1, pageSize: 100, total: 0 } } },
      {
        method: "POST",
        match: "/api/v1/entities/bulk-change/check",
        respond: () =>
          jsonResponse(200, {
            results: [
              { id: 10, identifier: "checkout", status: "UPDATED", updatedAt: 1 },
              { id: 11, identifier: "billing", status: "UPDATED", updatedAt: 2 },
            ],
          }),
      },
      {
        method: "POST",
        match: "/api/v1/entities/bulk-change",
        respond: () => {
          // What the change did to the data: the refetch that follows finds nothing matching any more.
          if (emptyAfterApply) graph = { nodes: [], edges: [] };
          return jsonResponse(200, {
            bulkId: "b",
            results: [
              { id: 10, identifier: "checkout", status: "UPDATED", updatedAt: 9 },
              { id: 11, identifier: "billing", status: "STALE", message: "changed since it was read", updatedAt: 8 },
            ],
          });
        },
      },
      {
        match: "/api/v1/entities/graph",
        respond: (url) => {
          graphUrls.push(url);
          if (url.includes("q=nomatch")) return jsonResponse(200, { nodes: [], edges: [] });
          return jsonResponse(graphStatus, graphStatus === 200 ? graph : { title: "x", status: graphStatus });
        },
      },
    ]),
  );
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/entities/bulk-change" element={<EntityBulkChange />} />
      </Routes>
      <PathProbe />
    </>,
    { route },
  );
}

describe("EntityBulkChange page", () => {
  beforeEach(() => {
    graphUrls = [];
    graph = GRAPH;
    graphStatus = 200;
    emptyAfterApply = false;
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.userId", "9");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("with no blueprint it asks for a pick and requests no graph", async () => {
    renderPage("/entities/bulk-change");
    expect(await screen.findByText("Pick a blueprint above to change its entities in bulk.")).toBeInTheDocument();
    expect(await screen.findByRole("radio", { name: "service" })).not.toBeChecked();
    expect(graphUrls).toHaveLength(0);
  });

  test("requests the blueprint plus its hierarchy ancestors and nests targets under their context rows", async () => {
    renderPage();
    expect(await screen.findByRole("checkbox", { name: "Select CHECKOUT" })).toBeInTheDocument();
    expect(graphUrls[0]).toBe("/api/v1/entities/graph?blueprint=service&blueprint=team");
    expect(screen.getByRole("radio", { name: "service" })).toBeChecked();
    // Context row for the team with targets beneath; the target-less team is pruned away.
    expect(screen.getByRole("checkbox", { name: "Select all 2 entities in the branch of PLATFORM" })).toBeInTheDocument();
    expect(screen.queryByText("EMPTY")).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Select SOLO" })).toBeInTheDocument();
    expect(screen.getByText("Entities shown: 3")).toBeInTheDocument();
    expect(screen.getByText("Selected: 0")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm" })).toBeDisabled();
  });

  test("a context checkbox selects every target beneath it; the tri-state follows single picks", async () => {
    const user = userEvent.setup();
    renderPage();
    const group = await screen.findByRole("checkbox", { name: "Select all 2 entities in the branch of PLATFORM" });

    await user.click(screen.getByRole("checkbox", { name: "Select BILLING" }));
    expect(group).toHaveProperty("indeterminate", true);
    expect(screen.getByText("Selected: 1")).toBeInTheDocument();

    await user.click(group);
    expect(group).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Select CHECKOUT" })).toBeChecked();
    expect(screen.getByText("Selected: 2")).toBeInTheDocument();
    // The ancestor itself is never a target: only the two services are counted.
    expect(screen.getByRole("button", { name: "Confirm" })).toBeEnabled();

    await user.click(group);
    expect(screen.getByText("Selected: 0")).toBeInTheDocument();
  });

  test("the header checkbox selects every shown target", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("checkbox", { name: "Select all 3 entities" }));
    expect(screen.getByText("Selected: 3")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Select SOLO" })).toBeChecked();
  });

  test("more than 200 selected disables Confirm with a hint", async () => {
    graph = {
      nodes: Array.from({ length: 201 }, (_, i) => node("service", `s${i}`, 100 + i)),
      edges: [],
    };
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("checkbox", { name: "Select all 201 entities" }));
    expect(screen.getByText("Selected: 201")).toBeInTheDocument();
    expect(screen.getByText("Select at most 200 entities per bulk change.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "Select S0" }));
    expect(screen.getByRole("button", { name: "Confirm" })).toBeEnabled();
  });

  test("picking another blueprint updates the URL, refetches and starts from an empty selection", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("checkbox", { name: "Select SOLO" }));
    expect(screen.getByText("Selected: 1")).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "team" }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("blueprint=team"));
    await waitFor(() => expect(graphUrls.at(-1)).toBe("/api/v1/entities/graph?blueprint=team"));
    expect(await screen.findByRole("checkbox", { name: "Select PLATFORM" })).not.toBeChecked();
    expect(screen.getByText("Selected: 0")).toBeInTheDocument();
  });

  test("the URL's q and team ride the graph request", async () => {
    renderPage("/entities/bulk-change?blueprint=service&q=check&team=platform");
    await screen.findByRole("checkbox", { name: "Select CHECKOUT" });
    expect(graphUrls[0]).toBe("/api/v1/entities/graph?blueprint=service&blueprint=team&team=platform&q=check");
  });

  test("Confirm opens the modal for exactly the selected targets", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("checkbox", { name: "Select all 2 entities in the branch of PLATFORM" }));
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Change one field of 2 selected Service entities.")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // Closing keeps the selection.
    expect(screen.getByText("Selected: 2")).toBeInTheDocument();
  });

  test("after the real run the done rows leave the selection and the failed ones stay selected", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("checkbox", { name: "Select all 2 entities in the branch of PLATFORM" }));
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("combobox", { name: "Field to change" }));
    await user.click(await screen.findByRole("option", { name: "Stage" }));
    await user.click(within(dialog).getByRole("combobox", { name: "Stage" }));
    await user.click(await screen.findByRole("option", { name: "live" }));
    await user.click(within(dialog).getByRole("button", { name: "Preview" }));
    await user.click(await within(dialog).findByRole("button", { name: "Apply to 2 entities" }));
    await within(dialog).findByText("Updated: 1, unchanged: 0, not changed: 1.");
    await user.click(within(dialog).getByRole("button", { name: "Close" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("checkbox", { name: "Select CHECKOUT" })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Select BILLING" })).toBeChecked();
    expect(screen.getByText("Selected: 1")).toBeInTheDocument();
  });

  test("the selection survives the tree going empty (a no-match search) and is back when it returns", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("checkbox", { name: "Select all 2 entities in the branch of PLATFORM" }));
    expect(screen.getByText("Selected: 2")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^Filters/ }));
    await user.type(screen.getByRole("textbox", { name: "Search" }), "nomatch");
    expect(await screen.findByText("Nothing to show — no entities of this blueprint match.")).toBeInTheDocument();

    await user.click(screen.getByLabelText("Clear search filter"));
    expect(await screen.findByRole("checkbox", { name: "Select CHECKOUT" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Select BILLING" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Select SOLO" })).not.toBeChecked();
    expect(screen.getByText("Selected: 2")).toBeInTheDocument();
  });

  test("the dialog and its result outlive the tree emptying under the refetch after the change", async () => {
    emptyAfterApply = true;
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("checkbox", { name: "Select all 2 entities in the branch of PLATFORM" }));
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("combobox", { name: "Field to change" }));
    await user.click(await screen.findByRole("option", { name: "Stage" }));
    await user.click(within(dialog).getByRole("combobox", { name: "Stage" }));
    await user.click(await screen.findByRole("option", { name: "live" }));
    await user.click(within(dialog).getByRole("button", { name: "Preview" }));
    await user.click(await within(dialog).findByRole("button", { name: "Apply to 2 entities" }));
    await within(dialog).findByText("Updated: 1, unchanged: 0, not changed: 1.");

    // The invalidation refetches a now-empty graph behind the dialog; the result must stay.
    expect(await screen.findByText("Nothing to show — no entities of this blueprint match.")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Updated: 1, unchanged: 0, not changed: 1.")).toBeInTheDocument();
  });

  test("a blueprint without matching entities shows the empty state", async () => {
    graph = { nodes: [node("team", "platform", 1)], edges: [] };
    renderPage();
    expect(await screen.findByText("Nothing to show — no entities of this blueprint match.")).toBeInTheDocument();
  });

  test("a failed graph load shows the status-tagged alert", async () => {
    graphStatus = 500;
    renderPage();
    expect(await screen.findByText("Could not load the entities")).toBeInTheDocument();
    expect(screen.getByText("Load failed (500)")).toBeInTheDocument();
  });

  test("an unknown blueprint in the URL reads as none picked", async () => {
    renderPage("/entities/bulk-change?blueprint=ghost");
    expect(await screen.findByText("Pick a blueprint above to change its entities in bulk.")).toBeInTheDocument();
    expect(graphUrls).toHaveLength(0);
  });

  test("collapsing a context row hides its targets but its checkbox still selects them", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("checkbox", { name: "Select CHECKOUT" });
    await user.click(screen.getByRole("button", { name: "Toggle children of PLATFORM" }));
    expect(screen.queryByRole("checkbox", { name: "Select CHECKOUT" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Select all 2 entities in the branch of PLATFORM" }));
    expect(screen.getByText("Selected: 2")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Expand all" }));
    expect(screen.getByRole("checkbox", { name: "Select CHECKOUT" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Collapse all" }));
    expect(screen.queryByRole("checkbox", { name: "Select CHECKOUT" })).not.toBeInTheDocument();
  });
});
