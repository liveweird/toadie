import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
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
});
