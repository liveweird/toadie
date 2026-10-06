import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import EntityPropertyField from "./EntityPropertyField";
import type { EntityFinding } from "../api/entities";
import type { EntityFormValues, PropertyDefinitionWire, PropertyValueDraft } from "../utils/entityForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function draft(overrides: Partial<PropertyValueDraft> = {}): PropertyValueDraft {
  return { id: "prop", text: "", bool: "", list: [], json: "", unknown: false, ...overrides };
}

function Harness({
  initial,
  definition,
  required = false,
  validate,
  findings,
}: {
  initial: PropertyValueDraft;
  definition?: PropertyDefinitionWire;
  required?: boolean;
  /** Mantine's nested-rules shape for the `properties` list — opt-in, so the ordinary
   *  rendering tests below stay validate-free. */
  validate?: { text?: (value: string) => string | null };
  findings?: EntityFinding[];
}) {
  const form = useForm<EntityFormValues>({
    initialValues: {
      blueprint: "bp",
      identifier: "",
      title: "",
      icon: "",
      team: [],
      properties: [initial],
      relations: [],
      sourceUrl: "",
    },
    validate: validate ? { properties: validate } : undefined,
    validateInputOnBlur: true,
  });
  return (
    <div>
      <EntityPropertyField form={form} index={0} definition={definition} required={required} findings={findings} />
      <div data-testid="value">{JSON.stringify(form.values.properties[0])}</div>
    </div>
  );
}

describe("EntityPropertyField", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockResolvedValue(
      jsonResponse(200, { items: [{ id: 1, identifier: "platform", title: "Platform" }], page: 1, pageSize: 100, total: 1 }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("string -> TextInput, typing updates the text slot", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness initial={draft()} definition={{ type: "string", title: "Name" } as PropertyDefinitionWire} />,
    );
    const input = screen.getByLabelText("Name");
    await user.type(input, "checkout");
    expect(screen.getByTestId("value")).toHaveTextContent('"text":"checkout"');
  });

  test("string with enum -> Select offering the enum values", () => {
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "string", title: "Tier", enum: ["gold", "silver"] } as PropertyDefinitionWire}
      />,
    );
    expect(screen.getByRole("combobox", { name: "Tier" })).toBeInTheDocument();
  });

  test("number -> NumberInput", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness initial={draft()} definition={{ type: "number", title: "Count" } as PropertyDefinitionWire} />,
    );
    await user.type(screen.getByLabelText("Count"), "5");
    expect(screen.getByTestId("value")).toHaveTextContent('"text":"5"');
  });

  test("boolean -> tri-state Select defaulting to Unset", () => {
    renderWithProviders(
      <Harness initial={draft()} definition={{ type: "boolean", title: "Active" } as PropertyDefinitionWire} />,
    );
    expect(screen.getByRole("combobox", { name: "Active" })).toHaveValue("Unset");
  });

  test("array of strings -> TagsInput", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "array", title: "Tags", items: { type: "string" } } as PropertyDefinitionWire}
      />,
    );
    await user.type(screen.getByLabelText("Tags", { selector: "input" }), "a{Enter}");
    expect(screen.getByTestId("value")).toHaveTextContent('"list":["a"]');
  });

  test("array of booleans -> a MultiSelect over True/False, not a free-text TagsInput", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "array", title: "Flags", items: { type: "boolean" } } as PropertyDefinitionWire}
      />,
    );
    const select = screen.getByRole("combobox", { name: "Flags" });
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "True" }));
    expect(screen.getByTestId("value")).toHaveTextContent('"list":["true"]');
  });

  test("number is validated on blur (getInputProps spread carries onBlur)", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "number", title: "Score", minimum: 1, maximum: 10 } as PropertyDefinitionWire}
        validate={{ text: (value: string) => (Number(value) > 10 ? "Must be at most 10" : null) }}
      />,
    );
    const input = screen.getByLabelText("Score");
    await user.type(input, "99");
    expect(screen.queryByText("Must be at most 10")).not.toBeInTheDocument();
    await user.tab();
    expect(await screen.findByText("Must be at most 10")).toBeInTheDocument();
  });

  test("array of object items -> a JSON Textarea, not TagsInput", () => {
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "array", title: "Rows", items: { type: "object" } } as PropertyDefinitionWire}
      />,
    );
    expect(screen.getByRole("textbox", { name: "Rows" }).tagName).toBe("TEXTAREA");
  });

  test("object -> a JSON Textarea", () => {
    renderWithProviders(
      <Harness initial={draft()} definition={{ type: "object", title: "Config" } as PropertyDefinitionWire} />,
    );
    expect(screen.getByRole("textbox", { name: "Config" }).tagName).toBe("TEXTAREA");
  });

  test("object with format labeled-url -> two TextInputs sharing the json slot", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "object", title: "Link", format: "labeled-url" } as PropertyDefinitionWire}
      />,
    );
    await user.type(screen.getByLabelText("URL"), "https://example.com");
    expect(screen.getByTestId("value")).toHaveTextContent('https://example.com');
  });

  test("number with enum -> Select offering the enum values as strings, picking one fills the text slot", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "number", title: "Size", enum: [1, 5] } as PropertyDefinitionWire}
      />,
    );
    await user.click(screen.getByRole("combobox", { name: "Size" }));
    await user.click(await screen.findByRole("option", { name: "5" }));
    expect(screen.getByTestId("value")).toHaveTextContent('"text":"5"');
  });

  test("array with items.enum -> MultiSelect over the enum values", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={
          { type: "array", title: "Regions", items: { type: "string", enum: ["eu", "us"] } } as PropertyDefinitionWire
        }
      />,
    );
    await user.click(screen.getByRole("combobox", { name: "Regions" }));
    await user.click(await screen.findByRole("option", { name: "eu" }));
    expect(screen.getByTestId("value")).toHaveTextContent('"list":["eu"]');
  });

  test("string with a markdown format -> a monospace multi-line Textarea", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "string", title: "Notes", format: "markdown" } as PropertyDefinitionWire}
      />,
    );
    const box = screen.getByRole("textbox", { name: "Notes" });
    expect(box.tagName).toBe("TEXTAREA");
    await user.type(box, "hi");
    expect(screen.getByTestId("value")).toHaveTextContent('"text":"hi"');
  });

  test("string with format team -> a single Select over the _team pool", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "string", title: "Squad", format: "team" } as PropertyDefinitionWire}
      />,
    );
    await user.click(screen.getByRole("combobox", { name: "Squad" }));
    await user.click(await screen.findByRole("option", { name: "platform — Platform" }));
    expect(screen.getByTestId("value")).toHaveTextContent('"text":"platform"');
    expect(mockFetch.mock.calls.some(([url]) => String(url).includes("blueprint=_team"))).toBe(true);
  });

  test("array of format user items -> a MultiSelect over the _user pool", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={
          { type: "array", title: "Reviewers", items: { type: "string", format: "user" } } as PropertyDefinitionWire
        }
      />,
    );
    await user.click(screen.getByRole("combobox", { name: "Reviewers" }));
    await user.click(await screen.findByRole("option", { name: "platform — Platform" }));
    expect(screen.getByTestId("value")).toHaveTextContent('"list":["platform"]');
    expect(mockFetch.mock.calls.some(([url]) => String(url).includes("blueprint=_user"))).toBe(true);
  });

  test("a failed _team pool on a scalar team property shows the team hint", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "boom", status: 500 }));
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "string", title: "Squad", format: "team" } as PropertyDefinitionWire}
      />,
    );
    expect(await screen.findByText("Could not load the teams")).toBeInTheDocument();
  });

  test("a failed _user pool on a scalar user property shows the user hint", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "boom", status: 500 }));
    renderWithProviders(
      <Harness
        initial={draft()}
        definition={{ type: "string", title: "Lead", format: "user" } as PropertyDefinitionWire}
      />,
    );
    expect(await screen.findByText("Could not load the users")).toBeInTheDocument();
  });

  test("labeled-url: typing display text stores it, clearing both parts empties the json slot", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={draft({ json: '{"url":"https://example.com"}' })}
        definition={{ type: "object", title: "Link", format: "labeled-url" } as PropertyDefinitionWire}
      />,
    );
    await user.type(screen.getByLabelText("Display text"), "Home");
    expect(screen.getByTestId("value")).toHaveTextContent('displayText');
    await user.clear(screen.getByLabelText("URL"));
    await user.clear(screen.getByLabelText("Display text"));
    expect(screen.getByTestId("value")).toHaveTextContent('"json":""');
  });

  test("a stored key the blueprint no longer declares renders as an unknown JSON row", () => {
    renderWithProviders(<Harness initial={draft({ id: "gone", json: "{}", unknown: true })} definition={undefined} />);
    expect(screen.getByRole("textbox", { name: "gone" }).tagName).toBe("TEXTAREA");
  });
});
