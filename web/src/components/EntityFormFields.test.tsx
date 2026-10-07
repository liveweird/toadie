import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useForm } from "@mantine/form";
import type { Blueprint } from "../api/blueprints";
import EntityFormFields from "./EntityFormFields";
import { indexEntityFindings } from "../utils/entityFieldFindings";
import { emptyEntityForm, toEntityRequest, type EntityFormValues } from "../utils/entityForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const BLUEPRINT_NO_COMPUTED = {
  id: 1,
  identifier: "team",
  title: "Team",
  schema: { properties: {}, required: [] },
  relations: {},
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
} as unknown as Blueprint;

const BLUEPRINT_WITH_COMPUTED = {
  ...BLUEPRINT_NO_COMPUTED,
  identifier: "service",
  mirrorProperties: { domain_title: { title: "Domain title", path: "system.domain.$title" } },
} as unknown as Blueprint;

function Harness({ blueprint, computed }: { blueprint: Blueprint; computed?: Record<string, unknown> }) {
  const form = useForm<EntityFormValues>({ initialValues: emptyEntityForm(blueprint) });
  return <EntityFormFields form={form} blueprint={blueprint} computed={computed} />;
}

describe("EntityFormFields — Computed section (v1.27.0)", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("no computed prop renders no Computed group, even when the blueprint declares computed properties", () => {
    renderWithProviders(<Harness blueprint={BLUEPRINT_WITH_COMPUTED} />);
    expect(screen.queryByRole("group", { name: "Computed" })).not.toBeInTheDocument();
  });

  test("a computed prop for a blueprint with no computed properties renders no Computed group", () => {
    renderWithProviders(<Harness blueprint={BLUEPRINT_NO_COMPUTED} computed={{}} />);
    expect(screen.queryByRole("group", { name: "Computed" })).not.toBeInTheDocument();
  });

  test("a computed prop with declared computed properties renders the group right before Source (2.9.0's trailing fieldset), with its value", () => {
    renderWithProviders(<Harness blueprint={BLUEPRINT_WITH_COMPUTED} computed={{ domain_title: "Commerce" }} />);

    const computedGroup = screen.getByRole("group", { name: "Computed" });
    expect(screen.getByText("Domain title")).toBeInTheDocument();
    expect(screen.getByText("Commerce")).toBeInTheDocument();

    // Source (the entity source-sync fieldset) always renders last; Computed is the LAST
    // section before it — the "Computed" test's original intent restated one release over.
    const groups = screen.getAllByRole("group");
    const sourceGroup = screen.getByRole("group", { name: "Source" });
    expect(groups[groups.length - 1]).toBe(sourceGroup);
    expect(groups[groups.length - 2]).toBe(computedGroup);
  });
});

// 2.18.0 — fill-in tiers: the header badge, the Focus fold and the label dots.
describe("EntityFormFields — fill-in tiers (2.18.0)", () => {
  // a: tier 1, b: tier 3, c: untiered, d: untiered but required; relation r: tier 2.
  const TIERED = {
    ...BLUEPRINT_NO_COMPUTED,
    identifier: "service",
    schema: {
      properties: {
        a: { type: "string", title: "Alpha" },
        b: { type: "string", title: "Beta" },
        c: { type: "string", title: "Gamma" },
        d: { type: "string", title: "Delta" },
      },
      required: ["d"],
    },
    relations: { r: { title: "Rel", target: "team", required: false, many: false } },
    tiers: { blueprint: 1, properties: { a: 1, b: 3 }, relations: { r: 2 } },
  } as unknown as Blueprint;

  function TierHarness({
    initial,
    errors,
    findings,
  }: {
    initial?: Partial<Record<string, string>>;
    errors?: Record<string, string>;
    findings?: Parameters<typeof indexEntityFindings>[0];
  }) {
    const base = emptyEntityForm(TIERED);
    const form = useForm<EntityFormValues>({
      initialValues: {
        ...base,
        properties: base.properties.map((draft) => ({ ...draft, text: initial?.[draft.id] ?? "" })),
      },
      initialErrors: errors,
    });
    return (
      <>
        <EntityFormFields form={form} blueprint={TIERED} findings={findings ? indexEntityFindings(findings) : undefined} />
        <pre data-testid="request">{JSON.stringify(toEntityRequest(form.values, TIERED).properties)}</pre>
      </>
    );
  }

  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a blueprint without tiered fields shows no badge and no Focus, and a stored Focus folds nothing", () => {
    localStorage.setItem("toadie.viewSettings.entityEditor.focusTier", "1");
    const untiered = {
      ...TIERED,
      tiers: undefined,
    } as unknown as Blueprint;
    function Plain() {
      const form = useForm<EntityFormValues>({ initialValues: emptyEntityForm(untiered) });
      return <EntityFormFields form={form} blueprint={untiered} />;
    }
    renderWithProviders(<Plain />);

    expect(screen.queryByText(/Filled through tier|incomplete/)).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Focus" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /more field/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Gamma", { selector: "input" })).toBeVisible();
  });

  test("the badge reads Tier 1 incomplete with the tier-1 field empty, then follows the draft live", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TierHarness />);

    expect(screen.getByText("Tier 1 incomplete")).toBeInTheDocument();

    await user.click(screen.getByLabelText("Alpha", { selector: "input" }));
    await user.paste("hello");
    // Tier 1 is filled, tier 2 (the relation) is not: filled through tier 1.
    expect(await screen.findByText("Filled through tier 1")).toBeInTheDocument();

    await user.click(screen.getByLabelText("Beta", { selector: "input" }));
    await user.paste("x");
    // The tier-2 relation stays empty, so the badge holds at 1.
    expect(screen.getByText("Filled through tier 1")).toBeInTheDocument();
  });

  test("a prefilled tier-1 and tier-3 property still stops at the unfilled tier-2 relation", () => {
    renderWithProviders(<TierHarness initial={{ a: "x", b: "y" }} />);
    expect(screen.getByText("Filled through tier 1")).toBeInTheDocument();
  });

  test("labels lead with an aria-hidden dot and keep their bare accessible names", () => {
    renderWithProviders(<TierHarness />);

    const alpha = screen.getByLabelText("Alpha", { selector: "input" });
    const label = document.querySelector(`label[for="${alpha.id}"]`);
    expect(label?.querySelector('[data-tier="1"][aria-hidden="true"]')).not.toBeNull();
    expect(screen.getByRole("textbox", { name: "Beta" })).toBeInTheDocument();
    const rel = screen.getByRole("combobox", { name: "Rel" });
    expect(document.querySelector(`label[for="${rel.id}"]`)?.querySelector('[data-tier="2"]')).not.toBeNull();
    const gamma = screen.getByLabelText("Gamma", { selector: "input" });
    expect(document.querySelector(`label[for="${gamma.id}"]`)?.querySelector("[data-tier]")).toBeNull();
  });

  test("a Focus folds the out-of-focus fields behind a counted toggle, in both fieldsets", async () => {
    localStorage.setItem("toadie.viewSettings.entityEditor.focusTier", "1");
    renderWithProviders(<TierHarness />);

    // Properties: Beta (tier 3) and Gamma (untiered) fold; Alpha (tier 1) and the required
    // Delta stay. Relations: Rel (tier 2) folds.
    const toggles = screen.getAllByRole("button", { name: /^Show \d+ more fields?$/ });
    expect(toggles.map((toggle) => toggle.textContent)).toEqual(["Show 2 more fields", "Show 1 more field"]);
    expect(screen.getByLabelText("Alpha", { selector: "input" })).toBeVisible();
    expect(screen.getByLabelText(/^Delta/, { selector: "input" })).toBeVisible();
    expect(screen.getByLabelText("Beta", { selector: "input" })).not.toBeVisible();
    expect(screen.getByLabelText("Gamma", { selector: "input" })).not.toBeVisible();
  });

  test("the toggle reveals the folded fields and collapses them again", async () => {
    localStorage.setItem("toadie.viewSettings.entityEditor.focusTier", "1");
    const user = userEvent.setup();
    renderWithProviders(<TierHarness />);

    await user.click(screen.getByRole("button", { name: "Show 2 more fields" }));
    expect(screen.getByLabelText("Beta", { selector: "input" })).toBeVisible();
    const hide = screen.getByRole("button", { name: "Hide the extra fields", expanded: true });
    await user.click(hide);
    expect(screen.getByLabelText("Beta", { selector: "input" })).not.toBeVisible();
  });

  test("a finding or a validation error keeps its field out of the fold", () => {
    localStorage.setItem("toadie.viewSettings.entityEditor.focusTier", "1");
    renderWithProviders(
      <TierHarness
        errors={{ "properties.2.text": "Bad value" }}
        findings={[{ code: "PATTERN_MISMATCH", field: "properties.b", message: "Does not match" }]}
      />,
    );

    // Beta (finding) and Gamma (error, index 2) stay visible: nothing left folded in Properties.
    expect(screen.getByLabelText("Beta", { selector: "input" })).toBeVisible();
    expect(screen.getByLabelText("Gamma", { selector: "input" })).toBeVisible();
    expect(screen.getAllByRole("button", { name: /^Show \d+ more fields?$/ }).map((b) => b.textContent)).toEqual([
      "Show 1 more field",
    ]);
  });

  test("a field pinned by an error stays the same mounted, focused node when typing clears the error", async () => {
    localStorage.setItem("toadie.viewSettings.entityEditor.focusTier", "1");
    const user = userEvent.setup();
    renderWithProviders(<TierHarness errors={{ "properties.2.text": "Bad value" }} />);

    const gamma = screen.getByLabelText("Gamma", { selector: "input" });
    expect(gamma).toBeVisible();
    expect(screen.getByText("Bad value")).toBeInTheDocument();

    await user.click(gamma);
    await user.keyboard("x");

    // The error cleared on change, yet the field neither remounted nor folded away.
    expect(screen.queryByText("Bad value")).not.toBeInTheDocument();
    const after = screen.getByLabelText("Gamma", { selector: "input" });
    expect(after).toBe(gamma);
    expect(after).toHaveFocus();
    expect(after).toBeVisible();
    expect(after).toHaveValue("x");
  });

  test("folded fields keep their values and are still part of the request", () => {
    localStorage.setItem("toadie.viewSettings.entityEditor.focusTier", "1");
    renderWithProviders(<TierHarness initial={{ b: "kept" }} />);

    expect(screen.getByLabelText("Beta", { selector: "input" })).not.toBeVisible();
    expect(screen.getByLabelText("Beta", { selector: "input" })).toHaveValue("kept");
    expect(JSON.parse(screen.getByTestId("request").textContent ?? "{}")).toEqual({ b: "kept" });
  });

  test("picking a Focus in the header persists it and folds on the spot", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TierHarness />);

    expect(screen.queryByRole("button", { name: /more field/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Focus" }));
    await user.click(await screen.findByRole("option", { name: "Tier 1" }));

    expect(await screen.findAllByRole("button", { name: /more field/ })).toHaveLength(2);
    expect(localStorage.getItem("toadie.viewSettings.entityEditor.focusTier")).toBe("1");
    const properties = screen.getByRole("group", { name: "Properties" });
    expect(within(properties).getByLabelText("Alpha", { selector: "input" })).toBeVisible();
    expect(within(properties).getByLabelText("Beta", { selector: "input" })).not.toBeVisible();
  });
});
