import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import EntityQueryBar from "./EntityQueryBar";
import type { EntityQueryDiagnostic } from "../api/entities";
import type { QueryCompletionSchema } from "../utils/queryCompletion";
import { blueprintResponse } from "../test/fixtures";

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

const service = blueprintResponse({
  identifier: "service",
  title: "Service",
  schema: {
    properties: {
      lifecycle: { type: "string", title: "Lifecycle", enum: ["experimental", "production"] },
      score: { type: "number", title: "Score" },
      enabled: { type: "boolean", title: "Enabled" },
    },
    required: [],
  },
  relations: { parent: { title: "Parent", target: "service", required: false, many: false } },
});
const team = blueprintResponse({ id: 2, identifier: "_team", title: "Team", system: true });
const schema: QueryCompletionSchema = { blueprints: [service, team], hierarchies: ["composition"] };

async function pick(label: string, option: string) {
  await userEvent.click(screen.getByLabelText(label, { selector: "input" }));
  await userEvent.click(await screen.findByRole("option", { name: option }));
}

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
    applied: "",
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

  test("Clear is disabled with a blank draft and no applied query", () => {
    renderBar({ value: "", applied: "" });
    expect(screen.getByRole("button", { name: "Clear" })).toBeDisabled();
  });

  test("Clear stays enabled once a query is applied, even after the draft is erased by hand", () => {
    renderBar({ value: "", applied: "MATCH (a)" });
    expect(screen.getByRole("button", { name: "Clear" })).toBeEnabled();
  });

  test("Clear is enabled for a non-blank draft and calls onClear when clicked", async () => {
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

  test("opening and cancelling the builder preserves the current draft without running it", async () => {
    const user = userEvent.setup();
    const { onChange, onRun, onPick } = renderBar({ value: "MATCH (manual)", draft: "MATCH (manual)" });

    await user.click(screen.getByRole("button", { name: "Build query" }));
    expect(screen.getByRole("dialog", { name: "Build query" })).toBeInTheDocument();
    expect(screen.getByText(/replaces the text currently in the editor/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel" }));

    expect(screen.getByRole("textbox", { name: "Entity query" })).toHaveValue("MATCH (manual)");
    expect(onChange).not.toHaveBeenCalled();
    expect(onRun).not.toHaveBeenCalled();
    expect(onPick).not.toHaveBeenCalled();
  });

  test("builds a typed enum condition and only replaces the draft when Use query is chosen", async () => {
    const user = userEvent.setup();
    const { onChange, onRun, onPick } = renderBar({ value: "MATCH (manual)", draft: "MATCH (manual)" });
    await user.click(screen.getByRole("button", { name: "Build query" }));
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("button", { name: "Add condition" }));
    await pick("Property", "Lifecycle");
    expect(screen.getByLabelText("Operator", { selector: "input" })).toHaveValue("Equals");
    await pick("Value", "production");

    const expected = "MATCH (n:service) WHERE n.lifecycle = 'production' RETURN n";
    expect(screen.getByLabelText("Generated query")).toHaveTextContent(expected);
    await user.click(screen.getByRole("button", { name: "Use query" }));

    expect(onChange).toHaveBeenCalledOnce();
    expect(onChange).toHaveBeenCalledWith(expected);
    expect(onRun).not.toHaveBeenCalled();
    expect(onPick).not.toHaveBeenCalled();
  });

  test("retains builder form state across close and reopen", async () => {
    const user = userEvent.setup();
    renderBar();
    await user.click(screen.getByRole("button", { name: "Build query" }));
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(screen.getByRole("button", { name: "Build query" }));

    expect(screen.getByLabelText("Entity type", { selector: "input" })).toHaveValue("Service");
    expect(screen.getByLabelText("Generated query")).toHaveTextContent("MATCH (n:service) RETURN n");
  });

  test("builds an optional relation connection with a target and both result variables", async () => {
    const user = userEvent.setup();
    renderBar();
    await user.click(screen.getByRole("button", { name: "Build query" }));
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("switch", { name: "Add connection" }));
    await pick("Relation", "Parent");
    expect(screen.getByLabelText("Connected entity type", { selector: "input" })).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Connected entity identifier" }), "checkout");
    await user.click(screen.getByRole("switch", { name: "Keep entities without a matching connection" }));
    await pick("Results", "Both");

    expect(screen.getByLabelText("Generated query")).toHaveTextContent(
      "MATCH (n:service) OPTIONAL MATCH (n)-[:parent]->(m:service {$identifier: 'checkout'}) RETURN n, m",
    );
  });

  test("switches value controls with the selected property type and hides Value for null checks", async () => {
    const user = userEvent.setup();
    renderBar();
    await user.click(screen.getByRole("button", { name: "Build query" }));
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("button", { name: "Add condition" }));
    await pick("Property", "Score");
    expect(screen.getByRole("textbox", { name: "Value" })).toHaveAttribute("inputmode", "decimal");
    await pick("Property", "Enabled");
    expect(screen.getByLabelText("Value", { selector: "input" })).toHaveValue("");
    await pick("Operator", "Is missing");
    expect(screen.queryByLabelText("Value")).not.toBeInTheDocument();
  });

  test("builds a bounded incoming hierarchy connection and limit", async () => {
    const user = userEvent.setup();
    renderBar();
    await user.click(screen.getByRole("button", { name: "Build query" }));
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("switch", { name: "Add connection" }));
    await pick("Connection type", "Hierarchy");
    await pick("Hierarchy", "composition");
    await pick("Direction", "Children");
    await pick("Connected entity type", "Service");
    await user.clear(screen.getByRole("textbox", { name: "Maximum hops" }));
    await user.type(screen.getByRole("textbox", { name: "Maximum hops" }), "3");
    await user.type(screen.getByRole("textbox", { name: "Limit" }), "10");

    expect(screen.getByLabelText("Generated query")).toHaveTextContent(
      "MATCH (n:service) MATCH (n)<-[:composition*1..3]-(m:service) RETURN n LIMIT 10",
    );
  });

  test("shows bound validation and keeps Use query disabled", async () => {
    const user = userEvent.setup();
    renderBar();
    await user.click(screen.getByRole("button", { name: "Build query" }));
    await pick("Entity type", "Service");
    await user.type(screen.getByRole("textbox", { name: "Limit" }), "10001");

    expect(screen.getByText("Enter a whole number from 1 to 10,000.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Use query" })).toBeDisabled();
  });

  test("removing the only condition restores the valid base query", async () => {
    const user = userEvent.setup();
    renderBar();
    await user.click(screen.getByRole("button", { name: "Build query" }));
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("button", { name: "Add condition" }));
    expect(screen.getByRole("button", { name: "Use query" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Remove condition 1" }));

    expect(screen.getByLabelText("Generated query")).toHaveTextContent("MATCH (n:service) RETURN n");
    expect(screen.getByRole("button", { name: "Use query" })).toBeEnabled();
  });
});
