import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import BlueprintFormFields from "./BlueprintFormFields";
import { useBlueprintRowExpansion } from "../hooks/useBlueprintRowExpansion";
import {
  emptyAggregationDraft,
  emptyBlueprintForm,
  emptyCalculationDraft,
  emptyPropertyDraft,
  emptyRelationDraft,
  type BlueprintFormValues,
} from "../utils/blueprintForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function serveBlueprintList(
  mockFetch: FetchMock,
  items: { id: number; identifier: string }[] = [],
  hierarchies: { id: number; value: string }[] = [],
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/blueprints") {
      return Promise.resolve(jsonResponse(200, { items }));
    }
    if (method === "GET" && url === "/api/v1/dictionaries/hierarchies") {
      return Promise.resolve(
        jsonResponse(200, { items: hierarchies.map((h) => ({ ...h, isDefault: false })) }),
      );
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function Harness({ initial, system = false }: { initial: Partial<BlueprintFormValues>; system?: boolean }) {
  const form = useForm<BlueprintFormValues>({ initialValues: { ...emptyBlueprintForm(), ...initial } });
  // The test harness wires the row-expansion hook itself — BlueprintEditor's job in the
  // real app, required so EditorRowList's fold state has something to read/write.
  const expansion = useBlueprintRowExpansion(form);
  return <BlueprintFormFields form={form} expansion={expansion} system={system} />;
}

async function openCombobox(name: RegExp) {
  const user = userEvent.setup();
  await user.click(screen.getByRole("combobox", { name }));
}

describe("BlueprintFormFields", () => {
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

  test("the target select offers other blueprints plus this blueprint's own identifier, labelled distinctly", async () => {
    serveBlueprintList(mockFetch, [{ id: 1, identifier: "team" }]);
    renderWithProviders(
      <Harness initial={{ identifier: "microservice", relations: [emptyRelationDraft()] }} />,
    );
    const user = userEvent.setup();
    const targetSelect = (await screen.findAllByRole("combobox", { name: "Target blueprint" }))[0];
    await user.click(targetSelect);

    expect(await screen.findByRole("option", { name: "team" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "microservice (this blueprint)" })).toBeInTheDocument();

    // Picking an option actually commits it to the form (the onChange callback).
    await user.click(screen.getByRole("option", { name: "team" }));
    expect((targetSelect as HTMLInputElement).value).toBe("team");
  });

  test("the target select shows a hint when the blueprint list fails to load", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, {})));
    renderWithProviders(<Harness initial={{ relations: [emptyRelationDraft()] }} />);

    expect(await screen.findByText("Could not load the blueprint list")).toBeInTheDocument();
  });

  test("properties: Add inserts a row, and RowControls move/remove it", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(<Harness initial={{}} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Add property" }));
    await user.click(screen.getByRole("button", { name: "Add property" }));
    expect(screen.getAllByLabelText(/^property id( \*)?$/i)).toHaveLength(2);

    await user.click(screen.getByRole("button", { name: "Move property 1 down" }));
    await user.click(screen.getByRole("button", { name: "Remove property 2" }));
    expect(screen.getAllByLabelText(/^property id( \*)?$/i)).toHaveLength(1);
  });

  test("properties: with two stored rows, only the first starts expanded", () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(
      <Harness
        initial={{
          properties: [
            { ...emptyPropertyDraft(), id: "language", title: "Language" },
            { ...emptyPropertyDraft(), id: "priority", title: "Priority" },
          ],
        }}
      />,
    );

    expect(screen.getByRole("button", { name: "Toggle language" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("button", { name: "Toggle priority" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getAllByLabelText(/^property id( \*)?$/i)).toHaveLength(1);
  });

  test("relations: Add inserts a row, and required+many together is invalid via the shared rule (row renders both switches)", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(<Harness initial={{}} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Add relation" }));
    // Required fields render their label with a trailing " *" (an aria-hidden span) —
    // getByLabelText matches the label's plain textContent, unlike getByRole's accessible-
    // name computation, so required-field queries allow the optional suffix (the
    // EditCatalogFile.test.tsx idiom).
    expect(screen.getByLabelText(/^relation id( \*)?$/i)).toBeInTheDocument();
    expect(screen.getByLabelText("Required")).toBeInTheDocument();
    expect(screen.getByLabelText("Many")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Add relation" }));
    await user.click(screen.getByRole("button", { name: "Move relation 1 down" }));
    await user.click(screen.getByRole("button", { name: "Remove relation 1" }));
    await user.click(screen.getByRole("button", { name: "Remove relation 1" }));
    expect(screen.queryByLabelText(/^relation id( \*)?$/i)).not.toBeInTheDocument();
  });

  test("mirror properties: Add inserts an id/title/path row, remove clears it", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(<Harness initial={{}} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Add mirror property" }));
    expect(screen.getByLabelText(/^property id( \*)?$/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^path( \*)?$/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Add mirror property" }));
    await user.click(screen.getByRole("button", { name: "Move mirror property 1 down" }));
    await user.click(screen.getByRole("button", { name: "Remove mirror property 1" }));
    await user.click(screen.getByRole("button", { name: "Remove mirror property 1" }));
    expect(screen.queryByLabelText(/^path( \*)?$/i)).not.toBeInTheDocument();
  });

  test("calculation properties: Add inserts a row; the colours textarea only appears once Colorized is on; remove clears it", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(<Harness initial={{}} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Add calculation property" }));
    expect(screen.queryByLabelText("Colors (JSON)")).not.toBeInTheDocument();

    await user.click(screen.getByLabelText("Colorized"));
    expect(await screen.findByLabelText("Colors (JSON)")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Add calculation property" }));
    await user.click(screen.getByRole("button", { name: "Move calculation property 1 down" }));
    await user.click(screen.getByRole("button", { name: "Remove calculation property 1" }));
    await user.click(screen.getByRole("button", { name: "Remove calculation property 1" }));
    expect(screen.queryByLabelText("Colorized")).not.toBeInTheDocument();
  });

  test("calculation properties: an object-type row offers the object spec whitelist", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(
      <Harness initial={{ calculationProperties: [{ ...emptyCalculationDraft(), type: "object" }] }} />,
    );
    const user = userEvent.setup();

    await user.click(screen.getByRole("combobox", { name: "Spec" }));
    expect(await screen.findByRole("option", { name: "open-api" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "embedded-url" })).not.toBeInTheDocument();
  });

  test("aggregation properties: func=average reveals averageOf/measureTimeBy", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(
      <Harness initial={{ aggregationProperties: [{ ...emptyAggregationDraft(), func: "average" }] }} />,
    );
    expect(screen.getByRole("combobox", { name: /^average of/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/^measure time by/i)).toBeInTheDocument();
  });

  test("aggregation properties: switching calculationBy to property reveals the Property field", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(
      <Harness initial={{ aggregationProperties: [{ ...emptyAggregationDraft(), calculationBy: "entities" }] }} />,
    );
    expect(screen.queryByLabelText(/^property( \*)?$/i)).not.toBeInTheDocument();

    await openCombobox(/^calculate by$/i);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("option", { name: "property" }));

    expect(await screen.findByLabelText(/^property( \*)?$/i)).toBeInTheDocument();
  });

  test("aggregation properties: Add inserts a row; remove clears it", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(<Harness initial={{}} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Add aggregation property" }));
    expect(screen.getByRole("combobox", { name: "Target blueprint" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Add aggregation property" }));
    await user.click(screen.getByRole("button", { name: "Move aggregation property 1 down" }));
    await user.click(screen.getByRole("button", { name: "Remove aggregation property 1" }));
    await user.click(screen.getByRole("button", { name: "Remove aggregation property 1" }));
    expect(screen.queryByRole("combobox", { name: "Target blueprint" })).not.toBeInTheDocument();
  });

  test("ownership: None hides the title/path fields; Direct shows title only; Inherited also shows path", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(<Harness initial={{}} />);
    expect(screen.queryByLabelText("Title", { selector: "input" })).not.toBeInTheDocument();

    await openCombobox(/^ownership$/i);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("option", { name: "Direct" }));

    expect(await screen.findByLabelText("Title")).toBeInTheDocument();
    expect(screen.queryByLabelText("Path")).not.toBeInTheDocument();

    await openCombobox(/^ownership$/i);
    await user.click(await screen.findByRole("option", { name: "Inherited" }));

    expect(await screen.findByLabelText(/^path( \*)?$/i)).toBeInTheDocument();
  });

  test("hierarchy: offers only the blueprint's CURRENT single relations, not many ones", async () => {
    serveBlueprintList(mockFetch, [], [{ id: 1, value: "composition" }]);
    renderWithProviders(
      <Harness
        initial={{
          relations: [
            { ...emptyRelationDraft(), id: "owningTeam", title: "T", target: "team", many: false },
            { ...emptyRelationDraft(), id: "peers", title: "P", target: "team", many: true },
          ],
        }}
      />,
    );
    // The dictionary query resolves async — wait for the Select before opening it.
    await screen.findByRole("combobox", { name: /^composition$/i });
    await openCombobox(/^composition$/i);
    expect(await screen.findByRole("option", { name: "owningTeam" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "peers" })).not.toBeInTheDocument();
  });

  test("hierarchy: two dictionary entries render two Selects, each independently settable", async () => {
    serveBlueprintList(
      mockFetch,
      [],
      [
        { id: 1, value: "composition" },
        { id: 2, value: "org" },
      ],
    );
    renderWithProviders(
      <Harness
        initial={{
          relations: [{ ...emptyRelationDraft(), id: "owningTeam", title: "T", target: "team", many: false }],
        }}
      />,
    );
    expect(await screen.findByRole("combobox", { name: /^composition$/i })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: /^org$/i })).toBeInTheDocument();

    const user = userEvent.setup();
    await openCombobox(/^composition$/i);
    await user.click(await screen.findByRole("option", { name: "owningTeam" }));

    expect(screen.getByRole("combobox", { name: /^composition$/i })).toHaveValue("owningTeam");
    expect(screen.getByRole("combobox", { name: /^org$/i })).toHaveValue("");
  });

  test("hierarchy: clearing a Select removes just that hierarchy's entry", async () => {
    serveBlueprintList(mockFetch, [], [{ id: 1, value: "composition" }]);
    renderWithProviders(
      <Harness
        initial={{
          relations: [{ ...emptyRelationDraft(), id: "owningTeam", title: "T", target: "team", many: false }],
          hierarchyRelations: { composition: "owningTeam" },
        }}
      />,
    );
    const select = await screen.findByRole("combobox", { name: /^composition$/i });
    expect(select).toHaveValue("owningTeam");

    const user = userEvent.setup();
    // Mantine's Input.ClearButton is `aria-hidden` (a mouse affordance — keyboard users clear
    // with Backspace on the searchable input), so reach it by its label, the
    // EntityGraphFilterControls idiom.
    await user.click(screen.getByLabelText("Clear composition"));

    expect(select).toHaveValue("");
  });

  test("hierarchy: a stored key no longer in the dictionary still renders, with a warning hint", async () => {
    serveBlueprintList(mockFetch, [], [{ id: 1, value: "composition" }]);
    renderWithProviders(
      <Harness
        initial={{
          relations: [{ ...emptyRelationDraft(), id: "owningTeam", title: "T", target: "team", many: false }],
          hierarchyRelations: { composition: "owningTeam", retired: "owningTeam" },
        }}
      />,
    );
    expect(await screen.findByRole("combobox", { name: /^retired$/i })).toHaveValue("owningTeam");
    expect(
      screen.getByText("This hierarchy no longer exists in the Hierarchies dictionary — clear it or re-add the value."),
    ).toBeInTheDocument();
  });

  test("hierarchy: an empty dictionary shows the hint and renders no Selects", async () => {
    serveBlueprintList(mockFetch, [], []);
    renderWithProviders(<Harness initial={{}} />);
    expect(await screen.findByText("No hierarchies defined yet — add them on the Hierarchies page.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: /^composition$/i })).not.toBeInTheDocument();
  });

  test("hierarchy: dictionary loading shows a named loading status", async () => {
    let resolveHierarchies: (response: Response) => void = () => {};
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: [] }));
      if (method === "GET" && url === "/api/v1/dictionaries/hierarchies") {
        return new Promise<Response>((resolve) => {
          resolveHierarchies = resolve;
        });
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Harness initial={{}} />);
    expect(await screen.findByRole("status")).toBeInTheDocument();
    resolveHierarchies(jsonResponse(200, { items: [] }));
  });

  test("hierarchy: a dictionary load failure shows the load-error alert", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: [] }));
      if (method === "GET" && url === "/api/v1/dictionaries/hierarchies") return Promise.resolve(jsonResponse(500, {}));
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Harness initial={{}} />);
    expect(await screen.findByText("Could not load the blueprints")).toBeInTheDocument();
  });

  test("system locks: _user's base email property and team relation are locked, remove disabled", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(
      <Harness
        system
        initial={{
          identifier: "_user",
          properties: [{ ...emptyPropertyDraft(), id: "email", title: "Email" }],
          relations: [{ ...emptyRelationDraft(), id: "team", title: "Team", target: "_team" }],
        }}
      />,
    );

    expect(screen.getAllByText("Seeded")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Remove property 1" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Remove relation 1" })).toBeDisabled();
  });

  test("system locks: _team locks its base parent relation only, an extra property stays unlocked", async () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(
      <Harness
        system
        initial={{
          identifier: "_team",
          properties: [{ ...emptyPropertyDraft(), id: "custom", title: "Custom" }],
          relations: [{ ...emptyRelationDraft(), id: "parent", title: "Parent", target: "_team" }],
        }}
      />,
    );

    expect(screen.getByText("Seeded")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove property 1" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Remove relation 1" })).toBeDisabled();
  });

  test("a plain blueprint locks nothing, even a relation named the same as a system base row", () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(
      <Harness
        initial={{
          identifier: "microservice",
          relations: [{ ...emptyRelationDraft(), id: "team", title: "Team", target: "_team" }],
        }}
      />,
    );

    expect(screen.queryByText("Seeded")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove relation 1" })).toBeEnabled();
  });

  test("identifier is read-only with the system hint when system", () => {
    serveBlueprintList(mockFetch);
    renderWithProviders(<Harness system initial={{ identifier: "_team" }} />);

    const identifierInput = screen.getByLabelText(/^identifier/i);
    expect(identifierInput).toHaveAttribute("readonly");
    expect(screen.getByText("A system blueprint's identifier cannot be changed.")).toBeInTheDocument();
  });

  test("hierarchy: a stored value naming a many relation renders as unset (derive, don't clear)", async () => {
    serveBlueprintList(mockFetch, [], [{ id: 1, value: "composition" }]);
    renderWithProviders(
      <Harness
        initial={{
          relations: [{ ...emptyRelationDraft(), id: "peers", title: "P", target: "team", many: true }],
          hierarchyRelations: { composition: "peers" },
        }}
      />,
    );
    expect(await screen.findByRole("combobox", { name: /^composition$/i })).toHaveValue("");
  });
});
