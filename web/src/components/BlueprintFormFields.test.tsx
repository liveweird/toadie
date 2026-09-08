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

function serveBlueprintList(mockFetch: FetchMock, items: { id: number; identifier: string }[] = []) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if ((init?.method ?? "GET") === "GET" && url === "/api/v1/blueprints") {
      return Promise.resolve(jsonResponse(200, { items }));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function Harness({ initial }: { initial: Partial<BlueprintFormValues> }) {
  const form = useForm<BlueprintFormValues>({ initialValues: { ...emptyBlueprintForm(), ...initial } });
  // The test harness wires the row-expansion hook itself — BlueprintEditor's job in the
  // real app, required so EditorRowList's fold state has something to read/write.
  const expansion = useBlueprintRowExpansion(form);
  return <BlueprintFormFields form={form} expansion={expansion} />;
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
});
