import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import type { Blueprint } from "../api/blueprints";
import EntityTeamField from "./EntityTeamField";
import { indexEntityFindings, NO_ENTITY_FINDINGS } from "../utils/entityFieldFindings";
import type { EntityFinding } from "../api/entities";
import type { EntityFormValues } from "../utils/entityForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function directBlueprint(title?: string): Blueprint {
  return { ownership: title ? { type: "Direct", title } : { type: "Direct" } } as unknown as Blueprint;
}

function inheritedBlueprint(path = "system"): Blueprint {
  return { ownership: { type: "Inherited", path } } as unknown as Blueprint;
}

function Harness({
  blueprint,
  computedTeam = [],
  findings = [],
}: {
  blueprint: Blueprint;
  computedTeam?: string[];
  findings?: EntityFinding[];
}) {
  const form = useForm<EntityFormValues>({
    initialValues: { blueprint: "bp", identifier: "", title: "", icon: "", team: [], properties: [], relations: [] },
  });
  return (
    <EntityTeamField
      form={form}
      blueprint={blueprint}
      computedTeam={computedTeam}
      findings={findings.length > 0 ? indexEntityFindings(findings) : NO_ENTITY_FINDINGS}
    />
  );
}

describe("EntityTeamField", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [{ id: 1, identifier: "platform", title: "Platform" }], page: 1, pageSize: 100, total: 1 }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("Direct ownership renders a MultiSelect over _team, labelled by ownership.title", async () => {
    renderWithProviders(<Harness blueprint={directBlueprint("Owned by")} />);
    expect(await screen.findByRole("combobox", { name: "Owned by" })).toBeInTheDocument();
    expect(mockFetch).toHaveBeenCalledWith(expect.stringContaining("blueprint=_team"), expect.anything());
  });

  test("absent ownership falls back to the generic Team label", async () => {
    renderWithProviders(<Harness blueprint={{} as Blueprint} />);
    expect(await screen.findByRole("combobox", { name: "Team" })).toBeInTheDocument();
  });

  test("Direct ownership shows a soft finding on the field", async () => {
    renderWithProviders(
      <Harness
        blueprint={directBlueprint()}
        findings={[{ code: "TEAM_TARGET_MISSING", field: "team", message: "raw" }]}
      />,
    );
    expect(await screen.findByText("This team no longer exists.")).toBeInTheDocument();
  });

  test("Inherited ownership renders read-only pills of the computed team, no fetch", async () => {
    renderWithProviders(<Harness blueprint={inheritedBlueprint("system")} computedTeam={["platform"]} />);
    expect(await screen.findByText("platform")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("Inherited ownership with no computed team shows the empty hint and the path", async () => {
    renderWithProviders(<Harness blueprint={inheritedBlueprint("system.owner")} computedTeam={[]} />);
    expect(await screen.findByText("No team could be determined for this entity")).toBeInTheDocument();
    expect(
      screen.getByText('Computed automatically from the "system.owner" relation chain — not editable here.'),
    ).toBeInTheDocument();
  });

  test("picking a team option updates the form's team array", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness blueprint={directBlueprint()} />);
    const select = await screen.findByRole("combobox", { name: "Team" });
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "platform — Platform" }));
    expect(await screen.findAllByText("platform — Platform")).not.toHaveLength(0);
  });
});
