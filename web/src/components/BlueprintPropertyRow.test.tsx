import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import BlueprintPropertyRow from "./BlueprintPropertyRow";
import { emptyBlueprintForm, emptyPropertyDraft, type BlueprintFormValues, type PropertyType } from "../utils/blueprintForm";
import { renderWithProviders } from "../test/render";

// Removal/reordering lives one level up, in PropertiesFieldset's shared RowControls (the
// BlueprintFormFields.test.tsx "properties: Add inserts a row..." case) — this row renders
// no remove control of its own, avoiding a second, redundantly-labelled affordance.
function Harness({ initialType, locked }: { initialType: PropertyType; locked?: boolean }) {
  const form = useForm<BlueprintFormValues>({
    initialValues: {
      ...emptyBlueprintForm(),
      properties: [{ ...emptyPropertyDraft(), type: initialType }],
    },
  });
  return <BlueprintPropertyRow form={form} index={0} locked={locked} />;
}

// Mantine's Select/TagsInput render a labelled options listbox alongside the input (even
// empty, even closed) — getByLabelText alone is ambiguous, so every combobox-backed field
// is scoped to its input element.
async function selectOption(label: RegExp, option: string) {
  const user = userEvent.setup();
  await user.click(screen.getByRole("combobox", { name: label }));
  await user.click(await screen.findByRole("option", { name: option }));
}

describe("BlueprintPropertyRow", () => {
  test("string: format/pattern/length/spec/enum/default fields render", () => {
    renderWithProviders(<Harness initialType="string" />);
    expect(screen.getByRole("combobox", { name: "Format" })).toBeInTheDocument();
    expect(screen.getByLabelText("Pattern")).toBeInTheDocument();
    expect(screen.getByLabelText("Min length")).toBeInTheDocument();
    expect(screen.getByLabelText("Max length")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Spec" })).toBeInTheDocument();
    expect(screen.getByLabelText("Allowed values", { selector: "input" })).toBeInTheDocument();
    expect(screen.getByLabelText("Default", { selector: "input" })).toBeInTheDocument();
    // number-only / array-only / object-only fields stay absent
    expect(screen.queryByLabelText("Minimum")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Item type" })).not.toBeInTheDocument();
  });

  test("string: picking the date-time format reveals the date format select", async () => {
    renderWithProviders(<Harness initialType="string" />);
    expect(screen.queryByRole("combobox", { name: "Date format" })).not.toBeInTheDocument();

    await selectOption(/^format$/i, "date-time");

    expect(await screen.findByRole("combobox", { name: "Date format" })).toBeInTheDocument();
  });

  test("string: picking the embedded-url spec reveals the authentication fields", async () => {
    renderWithProviders(<Harness initialType="string" />);
    expect(screen.queryByLabelText("Authorization URL")).not.toBeInTheDocument();

    await selectOption(/^spec$/i, "embedded-url");

    expect(await screen.findByLabelText("Authorization URL")).toBeInTheDocument();
    expect(screen.getByLabelText("Token URL")).toBeInTheDocument();
    expect(screen.getByLabelText("Client ID")).toBeInTheDocument();
    expect(screen.getByLabelText("Authorization scope", { selector: "input" })).toBeInTheDocument();
  });

  test("string default is a Select once enum values are added", async () => {
    renderWithProviders(<Harness initialType="string" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Allowed values", { selector: "input" }), "a{Enter}b{Enter}");

    expect(await screen.findByRole("combobox", { name: "Default" })).toBeInTheDocument();
  });

  test("enum colours: adding two values shows a colour picker for each, independently settable", async () => {
    renderWithProviders(<Harness initialType="string" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Allowed values", { selector: "input" }), "red{Enter}green{Enter}");

    const redSelect = await screen.findByRole("combobox", { name: 'Colour for value "red"' });
    const greenSelect = screen.getByRole("combobox", { name: 'Colour for value "green"' });

    await user.click(redSelect);
    await user.click(await screen.findByRole("option", { name: "red" }));
    expect((redSelect as HTMLInputElement).value).toBe("red");
    expect((greenSelect as HTMLInputElement).value).toBe("");

    await user.click(greenSelect);
    await user.click(await screen.findByRole("option", { name: "green" }));
    expect((greenSelect as HTMLInputElement).value).toBe("green");
    // Setting the second value must not disturb the first.
    expect((redSelect as HTMLInputElement).value).toBe("red");
  });

  test("number default becomes a Select once enum values are added", async () => {
    renderWithProviders(<Harness initialType="number" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Allowed values", { selector: "input" }), "1{Enter}2{Enter}");

    expect(await screen.findByRole("combobox", { name: "Default" })).toBeInTheDocument();
  });

  test("number: bound and enum/default fields render, string-only fields are absent", () => {
    renderWithProviders(<Harness initialType="number" />);
    expect(screen.getByLabelText("Minimum")).toBeInTheDocument();
    expect(screen.getByLabelText("Maximum")).toBeInTheDocument();
    expect(screen.getByLabelText("Exclusive minimum")).toBeInTheDocument();
    expect(screen.getByLabelText("Exclusive maximum")).toBeInTheDocument();
    expect(screen.getByLabelText("Allowed values", { selector: "input" })).toBeInTheDocument();
    expect(screen.getByLabelText("Default")).toBeInTheDocument();
    expect(screen.queryByLabelText("Pattern")).not.toBeInTheDocument();
  });

  test("boolean: the default widget is a tri-state select (Unset/True/False)", async () => {
    renderWithProviders(<Harness initialType="boolean" />);
    const select = screen.getByRole("combobox", { name: "Default" }) as HTMLInputElement;
    expect(select.value).toBe("Unset");

    const user = userEvent.setup();
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "True" }));
    expect(select.value).toBe("True");
  });

  test("array: item type/format/min-max/unique/default fields render", async () => {
    renderWithProviders(<Harness initialType="array" />);
    expect(screen.getByRole("combobox", { name: "Item type" })).toBeInTheDocument();
    expect(screen.getByLabelText("Min items")).toBeInTheDocument();
    expect(screen.getByLabelText("Max items")).toBeInTheDocument();
    expect(screen.getByLabelText("Unique items")).toBeInTheDocument();
    expect(screen.getByLabelText("Default", { selector: "input" })).toBeInTheDocument();
    // the item format select appears only once the item type is "string"
    expect(screen.queryByRole("combobox", { name: "Format" })).not.toBeInTheDocument();
    await selectOption(/^item type$/i, "string");
    expect(await screen.findByRole("combobox", { name: "Format" })).toBeInTheDocument();
  });

  test("object: format/spec/schema/default fields render", () => {
    renderWithProviders(<Harness initialType="object" />);
    expect(screen.getByRole("combobox", { name: "Format" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Spec" })).toBeInTheDocument();
    expect(screen.getByLabelText("Sub-schema (JSON)")).toBeInTheDocument();
    expect(screen.getByLabelText("Default")).toBeInTheDocument();
  });

  test("locked marks the property id field read-only", () => {
    renderWithProviders(<Harness initialType="string" locked />);
    expect(screen.getByLabelText(/^property id/i)).toHaveAttribute("readonly");
  });

  test("the Advanced toggle reveals description/icon and tracks aria-expanded", async () => {
    renderWithProviders(<Harness initialType="string" />);
    const toggle = screen.getByRole("button", { name: "Advanced" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByLabelText("Icon")).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByLabelText("Icon")).toBeInTheDocument();
    expect(screen.getByLabelText("Description")).toBeInTheDocument();
  });
});
