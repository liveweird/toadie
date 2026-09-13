import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import EntityQueryBar from "./EntityQueryBar";
import type { EntityQueryDiagnostic } from "../api/entities";
import type { QueryCompletionSchema } from "../utils/queryCompletion";

vi.mock("./QueryEditor", () => ({
  default: ({
    value,
    onChange,
    onRun,
    ariaLabel,
  }: {
    value: string;
    onChange: (value: string) => void;
    onRun: () => void;
    ariaLabel: string;
  }) => (
    <textarea
      aria-label={ariaLabel}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === "Enter") onRun();
      }}
    />
  ),
}));

const schema: QueryCompletionSchema = { blueprints: [], hierarchies: [] };

const SAVED_QUERIES = [
  { id: 1, name: "My query", visibility: "PRIVATE", query: "MATCH (a)", createdBy: 5, creatorName: "Me", creatorDeleted: false, createdAt: 1, updatedAt: 1 },
];

function renderBar(overrides: Partial<Parameters<typeof EntityQueryBar>[0]> = {}) {
  const props = {
    value: "",
    onChange: vi.fn(),
    onRun: vi.fn(),
    onClear: vi.fn(),
    diagnostics: [] as EntityQueryDiagnostic[],
    completionSchema: schema,
    draft: "",
    onPick: vi.fn(),
    ...overrides,
  };
  renderWithProviders(<EntityQueryBar {...props} />);
  return props;
}

describe("EntityQueryBar", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn((url: string) => {
      if (url.startsWith("/api/v1/entity-queries")) return Promise.resolve(jsonResponse(200, { items: SAVED_QUERIES }));
      return Promise.resolve(jsonResponse(404, {}));
    });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.userId", "5");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders the saved-query picker in the header row and picking runs the text", async () => {
    const onPick = vi.fn();
    renderBar({ draft: "", onPick });
    const select = await screen.findByLabelText("Saved query", { selector: "input" });
    await userEvent.click(select);
    await userEvent.click(await screen.findByRole("option", { name: "My query" }));
    await waitFor(() => expect(onPick).toHaveBeenCalledWith("MATCH (a)"));
  });

  test("renders the mocked editor with the entity-query label", () => {
    renderBar({ value: "MATCH (a)" });
    expect(screen.getByRole("textbox", { name: "Entity query" })).toHaveValue("MATCH (a)");
  });

  test("Run is disabled for a blank query and calls onRun when clicked with content", async () => {
    const user = userEvent.setup();
    const { onRun } = renderBar({ value: "" });
    expect(screen.getByRole("button", { name: "Run" })).toBeDisabled();

    renderBar({ value: "MATCH (a)", onRun });
    await user.click(screen.getAllByRole("button", { name: "Run" })[1]);
    expect(onRun).toHaveBeenCalledTimes(1);
  });

  test("Clear is disabled for an empty value and calls onClear when clicked", async () => {
    const user = userEvent.setup();
    const { onClear } = renderBar({ value: "MATCH (a)" });
    const clearButton = screen.getByRole("button", { name: "Clear" });
    expect(clearButton).toBeEnabled();
    await user.click(clearButton);
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  test("typing forwards to onChange", async () => {
    const user = userEvent.setup();
    const { onChange } = renderBar({ value: "" });
    await user.type(screen.getByRole("textbox", { name: "Entity query" }), "x");
    expect(onChange).toHaveBeenCalledWith("x");
  });

  test("Mod-Enter on the editor calls onRun", () => {
    const onRun = vi.fn();
    renderBar({ value: "MATCH (a)", onRun });
    const content = screen.getByRole("textbox", { name: "Entity query" });
    content.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", ctrlKey: true, bubbles: true }));
    expect(onRun).toHaveBeenCalledTimes(1);
  });

  test("shows no applied badge when appliedCount is unset", () => {
    renderBar({ value: "MATCH (a)" });
    expect(screen.queryByText(/Applied/)).not.toBeInTheDocument();
  });

  test("shows the applied · N entities badge when a query is applied", () => {
    renderBar({ value: "MATCH (a)", appliedCount: 3 });
    expect(screen.getByTestId("entityQuery-applied")).toHaveTextContent("Applied · 3 entities");
  });

  test("singular applied count", () => {
    renderBar({ value: "MATCH (a)", appliedCount: 1 });
    expect(screen.getByTestId("entityQuery-applied")).toHaveTextContent("Applied · 1 entity");
  });

  test("renders no diagnostics list when there are none", () => {
    renderBar({ diagnostics: [] });
    expect(screen.queryByRole("list", { name: "Query problems" })).not.toBeInTheDocument();
  });

  test("renders a positioned diagnostic with its line/column and a suggestion", () => {
    renderBar({
      diagnostics: [
        {
          code: "UNKNOWN_LABEL",
          message: "unknown label 'srv'",
          line: 1,
          column: 12,
          endLine: 1,
          endColumn: 15,
          suggestion: "service",
        },
      ],
    });
    expect(screen.getByRole("list", { name: "Query problems" })).toBeInTheDocument();
    expect(screen.getByText("line 1, column 12")).toBeInTheDocument();
    expect(screen.getByText("unknown label 'srv'", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("Did you mean `service`?")).toBeInTheDocument();
  });

  test("renders a positionless diagnostic without a position prefix or suggestion", () => {
    renderBar({
      diagnostics: [{ code: "DEADLINE_EXCEEDED", message: "query exceeded its evaluation budget" }],
    });
    expect(screen.queryByText(/^line /)).not.toBeInTheDocument();
    expect(screen.getByText("query exceeded its evaluation budget")).toBeInTheDocument();
  });

  test("renders several diagnostics as separate list items", () => {
    renderBar({
      diagnostics: [
        { code: "UNKNOWN_LABEL", message: "unknown label 'srv'", line: 1, column: 1 },
        { code: "UNKNOWN_VARIABLE", message: "unknown variable 'b'", line: 1, column: 20 },
      ],
    });
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
  });
});
