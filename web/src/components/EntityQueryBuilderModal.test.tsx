import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import i18n from "../i18n";
import { blueprintResponse } from "../test/fixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";
import type { QueryCompletionSchema } from "../utils/queryCompletion";
import EntityQueryBuilderModal from "./EntityQueryBuilderModal";

const service = blueprintResponse({
  identifier: "service",
  title: "Service",
  schema: {
    properties: {
      score: { type: "number", title: "Score" },
      enabled: { type: "boolean", title: "Enabled" },
    },
    required: [],
  },
  relations: { parent: { title: "Parent", target: "domain", required: false, many: false } },
});
const team = blueprintResponse({ id: 2, identifier: "_team", title: "Team", system: true });
const domain = blueprintResponse({ id: 3, identifier: "domain", title: "Domain" });
const schema: QueryCompletionSchema = { blueprints: [service, team, domain], hierarchies: ["composition"] };

async function pick(label: string, option: string, index = 0) {
  const inputs = screen.getAllByLabelText(label, { selector: "input" });
  await userEvent.click(inputs[index]);
  await userEvent.click(await screen.findByRole("option", { name: option }));
}

function renderModal(onUse = vi.fn(), onClose = vi.fn()) {
  renderWithProviders(
    <EntityQueryBuilderModal opened schema={schema} onUse={onUse} onClose={onClose} />,
  );
  return { onUse, onClose };
}

afterEach(async () => {
  await i18n.changeLanguage("en");
});

describe("EntityQueryBuilderModal", () => {
  test("combines free-text, numeric, and boolean controls as AND conditions", async () => {
    const user = userEvent.setup();
    const { onUse } = renderModal();
    await pick("Entity type", "Service");

    await user.click(screen.getByRole("button", { name: "Add condition" }));
    await pick("Property", "Title");
    await user.type(screen.getByRole("textbox", { name: "Value" }), "Checkout");

    await user.click(screen.getByRole("button", { name: "Add condition" }));
    await pick("Property", "Score", 1);
    await user.type(screen.getAllByRole("textbox", { name: "Value" })[1], "2.5");

    await user.click(screen.getByRole("button", { name: "Add condition" }));
    await pick("Property", "Enabled", 2);
    await pick("Value", "True", 2);

    const expected = "MATCH (n:service) WHERE n.$title = 'Checkout' AND n.score = 2.5 AND n.enabled = TRUE RETURN n";
    expect(screen.getByLabelText("Generated query")).toHaveTextContent(expected);
    await user.click(screen.getByRole("button", { name: "Use query" }));
    expect(onUse).toHaveBeenCalledWith(expected);
  });

  test("offers incoming ownership from teams and resets dependent form state when the entity type changes", async () => {
    const user = userEvent.setup();
    renderModal();
    await pick("Entity type", "Team");
    await user.click(screen.getByRole("switch", { name: "Add connection" }));
    await pick("Connection type", "Ownership");
    await pick("Direction", "Incoming");
    await pick("Connected entity type", "Service");
    await user.click(screen.getByRole("switch", { name: "Keep entities without a matching connection" }));
    await pick("Results", "Connected entities");

    expect(screen.getByLabelText("Generated query")).toHaveTextContent(
      "MATCH (n:_team) OPTIONAL MATCH (n)<-[:$team]-(m:service) RETURN m",
    );

    await user.click(screen.getByRole("switch", { name: "Add connection" }));
    expect(screen.getByRole("switch", { name: "Add connection" })).not.toBeChecked();
    expect(screen.getByLabelText("Generated query")).toHaveTextContent("MATCH (n:_team) RETURN n");
    await user.click(screen.getByRole("switch", { name: "Add connection" }));
    await pick("Entity type", "Service");
    expect(screen.getByRole("switch", { name: "Add connection" })).not.toBeChecked();
    expect(screen.getByLabelText("Generated query")).toHaveTextContent("MATCH (n:service) RETURN n");
  });

  test("fixes outgoing ownership to Team and does not offer an invalid incoming direction", async () => {
    const user = userEvent.setup();
    renderModal();
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("switch", { name: "Add connection" }));
    await pick("Connection type", "Ownership");

    expect(screen.getByLabelText("Connected entity type", { selector: "input" })).toBeDisabled();
    expect(screen.getByLabelText("Connected entity type", { selector: "input" })).toHaveValue("Team");
    await user.click(screen.getByLabelText("Direction", { selector: "input" }));
    expect(screen.queryByRole("option", { name: "Incoming" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Generated query")).toHaveTextContent(
      "MATCH (n:service) MATCH (n)-[:$team]->(m:_team) RETURN n",
    );
  });

  test("renders the builder controls in Polish", async () => {
    await i18n.changeLanguage("pl");
    renderModal();
    expect(screen.getByRole("dialog", { name: "Zbuduj zapytanie" })).toBeInTheDocument();
    expect(screen.getByLabelText("Typ encji", { selector: "input" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Użyj zapytania" })).toBeDisabled();
  });

  test("reports a schema type that disappears while the builder remains open", async () => {
    const onUse = vi.fn();
    const onClose = vi.fn();
    const view = renderWithProviders(
      <EntityQueryBuilderModal opened schema={schema} onUse={onUse} onClose={onClose} />,
    );
    await pick("Entity type", "Service");

    view.rerender(
      <EntityQueryBuilderModal
        opened
        schema={{ blueprints: [team], hierarchies: schema.hierarchies }}
        onUse={onUse}
        onClose={onClose}
      />,
    );
    expect(screen.getByText("This entity type is no longer available.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Use query" })).toBeDisabled();
  });

  test("shows the generated-query budget error for an oversized connected identifier", async () => {
    const user = userEvent.setup();
    renderModal();
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("switch", { name: "Add connection" }));
    await pick("Connection type", "Hierarchy");
    await pick("Hierarchy", "composition");
    await pick("Connected entity type", "Service");
    await user.click(screen.getByRole("textbox", { name: "Connected entity identifier" }));
    await user.paste("x".repeat(2_000));

    expect(screen.getByText("The generated query is too long. Remove some conditions.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Use query" })).toBeDisabled();
  });

  test("keeps a fixed relation target visible when that target disappears from the live schema", async () => {
    const onUse = vi.fn();
    const onClose = vi.fn();
    const view = renderWithProviders(
      <EntityQueryBuilderModal opened schema={schema} onUse={onUse} onClose={onClose} />,
    );
    await pick("Entity type", "Service");
    await userEvent.click(screen.getByRole("switch", { name: "Add connection" }));
    await pick("Relation", "Parent");
    await waitFor(() => {
      expect(screen.getByLabelText("Connected entity type", { selector: "input" })).toHaveValue("Domain");
    });

    view.rerender(
      <EntityQueryBuilderModal
        opened
        schema={{ blueprints: [service, team], hierarchies: schema.hierarchies }}
        onUse={onUse}
        onClose={onClose}
      />,
    );
    expect(screen.getByText("This connected entity type is no longer available.")).toBeInTheDocument();
    expect(screen.getByLabelText("Connected entity type", { selector: "input" })).toHaveValue("Domain");
    expect(screen.getByRole("button", { name: "Use query" })).toBeDisabled();
  });

  test("adds identifiers only when duplicate schema titles would make choices ambiguous", async () => {
    const twin = blueprintResponse({ id: 4, identifier: "service-v2", title: "Service" });
    renderWithProviders(
      <EntityQueryBuilderModal
        opened
        schema={{ blueprints: [service, twin], hierarchies: [] }}
        onUse={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    await userEvent.click(screen.getByLabelText("Entity type", { selector: "input" }));
    expect(screen.getByRole("option", { name: "Service (service)" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("option", { name: "Service (service-v2)" }));
    expect(screen.getByLabelText("Generated query")).toHaveTextContent("MATCH (n:`service-v2`) RETURN n");
  });

  test("preserves lossless numeric text instead of rounding through JavaScript numbers", async () => {
    const user = userEvent.setup();
    renderModal();
    await pick("Entity type", "Service");
    await user.click(screen.getByRole("button", { name: "Add condition" }));
    await pick("Property", "Score");
    const value = screen.getByRole("textbox", { name: "Value" });
    await user.type(value, "9223372036854775807");

    expect(value).toHaveValue("9223372036854775807");
    expect(screen.getByLabelText("Generated query")).toHaveTextContent(
      "MATCH (n:service) WHERE n.score = 9223372036854775807 RETURN n",
    );
  });
});
