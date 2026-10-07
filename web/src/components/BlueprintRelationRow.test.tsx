import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import BlueprintRelationRow from "./BlueprintRelationRow";
import { emptyBlueprintForm, emptyRelationDraft, type BlueprintFormValues } from "../utils/blueprintForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function Harness({ locked }: { locked?: boolean }) {
  const form = useForm<BlueprintFormValues>({
    initialValues: { ...emptyBlueprintForm(), relations: [emptyRelationDraft()] },
  });
  return <BlueprintRelationRow form={form} index={0} locked={locked} />;
}

describe("BlueprintRelationRow", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { items: [] })));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("locked marks the relation id field read-only", () => {
    renderWithProviders(<Harness locked />);
    expect(screen.getByLabelText(/^relation id/i)).toHaveAttribute("readonly");
  });

  test("unlocked leaves the relation id field editable", () => {
    renderWithProviders(<Harness />);
    expect(screen.getByLabelText(/^relation id/i)).not.toHaveAttribute("readonly");
  });

  describe("fill-in tier", () => {
    function TierHarness({ id, tier }: { id: string; tier: number | null }) {
      const form = useForm<BlueprintFormValues>({
        initialValues: { ...emptyBlueprintForm(), relations: [{ ...emptyRelationDraft(), id, tier }] },
      });
      return (
        <>
          <BlueprintRelationRow form={form} index={0} />
          <output data-testid="tier">{String(form.values.relations[0].tier)}</output>
        </>
      );
    }

    test("the tier Select is named after the row and shows the stored tier", () => {
      renderWithProviders(<TierHarness id="system" tier={1} />);
      expect(screen.getByRole("combobox", { name: "Tier for system" })).toHaveValue("Tier 1");
    });

    test("picking a tier writes it to the draft and No tier clears it", async () => {
      const user = userEvent.setup();
      renderWithProviders(<TierHarness id="system" tier={null} />);

      await user.click(screen.getByRole("combobox", { name: "Tier for system" }));
      await user.click(await screen.findByRole("option", { name: "Tier 4" }));
      expect(screen.getByTestId("tier")).toHaveTextContent("4");

      await user.click(screen.getByRole("combobox", { name: "Tier for system" }));
      await user.click(await screen.findByRole("option", { name: "No tier" }));
      expect(screen.getByTestId("tier")).toHaveTextContent("null");
    });
  });
});
