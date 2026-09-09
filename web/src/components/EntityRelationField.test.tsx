import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import EntityRelationField from "./EntityRelationField";
import type { EntityFormValues, RelationDefinitionWire, RelationValueDraft } from "../utils/entityForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function serveEntities(mockFetch: FetchMock, items: { id: number; identifier: string; title: string }[]) {
  mockFetch.mockImplementation(() =>
    Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 100, total: items.length })),
  );
}

function Harness({ initial, definition, required = false }: { initial: RelationValueDraft; definition: RelationDefinitionWire; required?: boolean }) {
  const form = useForm<EntityFormValues>({
    initialValues: { blueprint: "bp", identifier: "", title: "", icon: "", team: [], properties: [], relations: [initial] },
  });
  return (
    <div>
      <EntityRelationField form={form} index={0} definition={definition} required={required} />
      <div data-testid="value">{JSON.stringify(form.values.relations[0])}</div>
    </div>
  );
}

describe("EntityRelationField", () => {
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

  test("many: false -> a Select labelled identifier — title, picking sets the single slot", async () => {
    serveEntities(mockFetch, [{ id: 1, identifier: "commerce", title: "Commerce" }]);
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={{ id: "system", single: "", many: [] }}
        definition={{ title: "System", target: "system", required: false, many: false } as RelationDefinitionWire}
      />,
    );
    const select = await screen.findByRole("combobox", { name: "System" });
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "commerce — Commerce" }));
    expect(screen.getByTestId("value")).toHaveTextContent('"single":"commerce"');
  });

  test("many: true -> a MultiSelect", async () => {
    serveEntities(mockFetch, [{ id: 1, identifier: "catalog", title: "Catalog" }]);
    renderWithProviders(
      <Harness
        initial={{ id: "dependsOn", single: "", many: [] }}
        definition={{ title: "Depends on", target: "service", required: false, many: true } as RelationDefinitionWire}
      />,
    );
    expect(await screen.findByRole("combobox", { name: "Depends on" })).toBeInTheDocument();
  });

  test("an empty target pool shows the no-targets hint", async () => {
    serveEntities(mockFetch, []);
    renderWithProviders(
      <Harness
        initial={{ id: "system", single: "", many: [] }}
        definition={{ title: "System", target: "system", required: false, many: false } as RelationDefinitionWire}
      />,
    );
    expect(await screen.findByText("No entities of the target blueprint yet")).toBeInTheDocument();
  });

  test("a stored value outside the loaded pool is appended so it keeps displaying", async () => {
    serveEntities(mockFetch, [{ id: 1, identifier: "commerce", title: "Commerce" }]);
    renderWithProviders(
      <Harness
        initial={{ id: "system", single: "stale-target", many: [] }}
        definition={{ title: "System", target: "system", required: false, many: false } as RelationDefinitionWire}
      />,
    );
    expect(await screen.findByRole("combobox", { name: "System" })).toHaveValue("stale-target");
  });
});
