import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import OntologyImportResults from "./OntologyImportResults";
import type { OntologyResultRow } from "../utils/ontologyImport";

const BLUEPRINT_ROW: OntologyResultRow = {
  index: 0,
  kind: "blueprint",
  identifier: "service",
  source: "pasted",
  status: "CREATED",
  id: 10,
};

const ENTITY_ROW: OntologyResultRow = {
  index: 1,
  kind: "entity",
  identifier: "checkout",
  blueprint: "service",
  source: "pasted",
  status: "EXISTS",
  id: 20,
};

const INVALID_ROW: OntologyResultRow = {
  index: 2,
  kind: "entity",
  identifier: "billing",
  blueprint: "service",
  source: "a.json",
  status: "INVALID",
  message: "Unknown blueprint",
  findings: [{ code: "TYPE_MISMATCH", field: "properties.tier", message: "must be a string" }],
};

describe("OntologyImportResults", () => {
  test("renders kind badges, dimmed blueprint/identifier for entities, and links stored rows to their editors", () => {
    renderWithProviders(<OntologyImportResults rows={[BLUEPRINT_ROW, ENTITY_ROW]} mode="import" showSource={false} />);

    expect(screen.getByText("Blueprint")).toBeInTheDocument();
    expect(screen.getByText("Entity")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Edit service" })).toHaveAttribute("href", "/blueprints/10/edit");
    expect(screen.getByRole("link", { name: "Edit service / checkout" })).toHaveAttribute(
      "href",
      "/entities/20/edit",
    );
    expect(screen.getByText("Created")).toBeInTheDocument();
    expect(screen.getByText("Already exists")).toBeInTheDocument();
  });

  test("a row with no id renders as plain (non-linked) text", () => {
    const row: OntologyResultRow = { index: 0, kind: "blueprint", identifier: "b1", source: "s", status: "INVALID", message: "bad" };
    renderWithProviders(<OntologyImportResults rows={[row]} mode="import" showSource={false} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("b1")).toBeInTheDocument();
    expect(screen.getByText("bad")).toBeInTheDocument();
  });

  test("check mode renders the would-be labels and the would-import summary", () => {
    renderWithProviders(<OntologyImportResults rows={[BLUEPRINT_ROW]} mode="check" showSource={false} />);
    expect(screen.getByText("Would be created")).toBeInTheDocument();
    expect(screen.getByText(/Would import 1 of 1/)).toBeInTheDocument();
  });

  test("the Source column renders only when requested", () => {
    const { rerender } = renderWithProviders(
      <OntologyImportResults rows={[BLUEPRINT_ROW]} mode="import" showSource={false} />,
    );
    expect(screen.queryByText("Source")).not.toBeInTheDocument();
    rerender(<OntologyImportResults rows={[BLUEPRINT_ROW]} mode="import" showSource />);
    expect(screen.getByText("Source")).toBeInTheDocument();
    expect(screen.getByText("pasted")).toBeInTheDocument();
  });

  test("an entity row with findings can be expanded and collapsed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OntologyImportResults rows={[INVALID_ROW]} mode="import" showSource={false} />);

    expect(screen.queryByText("properties.tier")).not.toBeInTheDocument();
    const toggleButton = screen.getByRole("button", { name: "Show 1 finding" });
    expect(toggleButton).toBeInTheDocument();
    await user.click(toggleButton);
    expect(screen.getByText("properties.tier")).toBeInTheDocument();
    expect(screen.getByText(/must be a string/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Hide findings" }));
    expect(screen.queryByText("properties.tier")).not.toBeInTheDocument();
  });

  test("the overall summary counts created/updated/unchanged rows", () => {
    const updated: OntologyResultRow = { ...BLUEPRINT_ROW, index: 3, status: "UPDATED" };
    renderWithProviders(
      <OntologyImportResults rows={[BLUEPRINT_ROW, ENTITY_ROW, updated]} mode="import" showSource={false} />,
    );
    expect(screen.getByText(/Imported 2 of 3 — 1 created, 1 updated, 1 unchanged/)).toBeInTheDocument();
  });
});
