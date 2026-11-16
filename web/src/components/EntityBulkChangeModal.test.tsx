import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { jsonResponse, mockFetch, type MockRoute } from "../test/http";
import { blueprintResponse, entityResponse, pageOf } from "../test/fixtures";
import type { EntityBulkChangeResponse } from "../api/entities";
import EntityBulkChangeModal, { type BulkTarget } from "./EntityBulkChangeModal";

const BLUEPRINT = blueprintResponse({
  identifier: "service",
  title: "Service",
  schema: {
    properties: {
      stage: { type: "string", title: "Stage", enum: ["beta", "live"] },
      tags: { type: "array", title: "Tags", items: { type: "string" }, minItems: 2 },
      name: { type: "string", title: "Name" },
      note: { type: "string", title: "Note", maxLength: 3 },
    },
    required: ["name"],
  },
  relations: { team: { title: "Owning team", target: "team", required: false, many: false } },
});

const TARGETS: BulkTarget[] = [
  { id: 1, identifier: "checkout", title: "Checkout" },
  { id: 2, identifier: "billing", title: "Billing" },
];

const PREVIEW: EntityBulkChangeResponse = {
  results: [
    { id: 1, identifier: "checkout", title: "Checkout", status: "UPDATED", updatedAt: 111, before: "beta", after: "live" },
    { id: 2, identifier: "billing", title: "Billing", status: "UNCHANGED", updatedAt: 222, before: "live", after: "live" },
  ],
};

type Calls = { check: unknown[]; apply: unknown[] };

function setup(overrides: { check?: MockRoute["respond"]; apply?: MockRoute["respond"] } = {}) {
  const calls: Calls = { check: [], apply: [] };
  const fetchMock = mockFetch([
    {
      method: "POST",
      match: "/api/v1/entities/bulk-change/check",
      respond: (_url, init) => {
        calls.check.push(JSON.parse(init?.body as string));
        if (typeof overrides.check === "function") return overrides.check(_url, init);
        return jsonResponse(200, PREVIEW);
      },
    },
    {
      method: "POST",
      match: "/api/v1/entities/bulk-change",
      respond: (_url, init) => {
        calls.apply.push(JSON.parse(init?.body as string));
        if (typeof overrides.apply === "function") return overrides.apply(_url, init);
        return jsonResponse(200, {
          bulkId: "b-1",
          results: [{ ...PREVIEW.results[0], updatedAt: 999 }, PREVIEW.results[1]],
        });
      },
    },
    { match: /\/api\/v1\/entities\?/, respond: { status: 200, body: pageOf([entityResponse({ identifier: "platform", title: "Platform" })]) } },
  ]);
  vi.stubGlobal("fetch", fetchMock);
  const onClose = vi.fn();
  const onApplied = vi.fn();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  renderWithProviders(<EntityBulkChangeModal blueprint={BLUEPRINT} targets={TARGETS} onClose={onClose} onApplied={onApplied} />, {
    queryClient,
  });
  return { calls, onClose, onApplied, invalidate };
}

async function pickField(user: ReturnType<typeof userEvent.setup>, label: string) {
  await user.click(screen.getByRole("combobox", { name: "Field to change" }));
  await user.click(await screen.findByRole("option", { name: label }));
}

async function setStage(user: ReturnType<typeof userEvent.setup>, value: string) {
  await user.click(screen.getByRole("combobox", { name: "Stage" }));
  await user.click(await screen.findByRole("option", { name: value }));
}

describe("EntityBulkChangeModal", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("configure -> preview -> apply: the apply carries the preview's stamps and the page hears the outcome", async () => {
    const user = userEvent.setup();
    const { calls, onApplied } = setup();

    expect(screen.getByText("Change one field of 2 selected Service entities.")).toBeInTheDocument();
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));

    expect(await screen.findByText("Will be updated: 1, unchanged: 1, cannot be changed: 0.")).toBeInTheDocument();
    // The dry-run names the targets without stamps (the graph carries none).
    expect(calls.check).toEqual([
      { blueprint: "service", field: "properties.stage", operation: "SET", value: "live", entities: [{ id: 1 }, { id: 2 }] },
    ]);
    expect(calls.apply).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Apply to 1 entity" }));
    expect(await screen.findByText("Updated: 1, unchanged: 1, not changed: 0.")).toBeInTheDocument();
    // EVERY selected entity is sent, each with the write-version stamp the preview read.
    expect(calls.apply).toEqual([
      { blueprint: "service", field: "properties.stage", operation: "SET", value: "live", entities: [{ id: 1, updatedAt: 111 }, { id: 2, updatedAt: 222 }] },
    ]);
    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onApplied.mock.calls[0][0].map((r: { id: number; status: string }) => [r.id, r.status])).toEqual([
      [1, "UPDATED"],
      [2, "UNCHANGED"],
    ]);
  });

  test("a row the preview returned no stamp for is sent without one", async () => {
    const user = userEvent.setup();
    const { calls } = setup({
      check: () => jsonResponse(200, { results: [{ ...PREVIEW.results[0] }, { id: 2, status: "UPDATED" }] }),
    });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(await screen.findByRole("button", { name: "Apply to 2 entities" }));
    await screen.findByText(/Updated: /);
    expect((calls.apply[0] as { entities: unknown[] }).entities).toEqual([{ id: 1, updatedAt: 111 }, { id: 2 }]);
  });

  test("an entity edited after the preview comes back STALE and the result says it stays selected", async () => {
    const user = userEvent.setup();
    setup({
      apply: () =>
        jsonResponse(200, {
          bulkId: "b-2",
          results: [
            { id: 1, identifier: "checkout", status: "STALE", message: "changed since it was read", updatedAt: 500 },
            PREVIEW.results[1],
          ],
        }),
    });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(await screen.findByRole("button", { name: "Apply to 1 entity" }));
    expect(await screen.findByText("Updated: 0, unchanged: 1, not changed: 1.")).toBeInTheDocument();
    expect(screen.getByText("changed since it was read")).toBeInTheDocument();
    expect(screen.getByText(/stay selected/)).toBeInTheDocument();
  });

  test("a blank value never previews: SET asks for a value and nothing is requested", async () => {
    const user = userEvent.setup();
    const { calls } = setup();
    await pickField(user, "Stage");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("Enter a value to set. To remove the value, use Clear.")).toBeInTheDocument();
    expect(calls.check).toHaveLength(0);
  });

  test("the field's own client rule blocks the preview (a required text field left blank)", async () => {
    const user = userEvent.setup();
    const { calls } = setup();
    await pickField(user, "Name");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("Required")).toBeInTheDocument();
    expect(calls.check).toHaveLength(0);
  });

  test("Clear needs no value, is disabled for a required field, and Add/Remove exist on list fields only", async () => {
    const user = userEvent.setup();
    const { calls } = setup();

    await pickField(user, "Name");
    expect(screen.getByRole("radio", { name: "Clear" })).toBeDisabled();
    expect(screen.queryByRole("radio", { name: "Add items" })).not.toBeInTheDocument();

    await pickField(user, "Tags");
    expect(screen.getByRole("radio", { name: "Add items" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: "Remove items" })).toBeEnabled();

    await pickField(user, "Stage");
    await user.click(screen.getByText("Clear"));
    expect(screen.queryByRole("combobox", { name: "Stage" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await screen.findByText(/Will be updated/);
    expect("value" in (calls.check[0] as object)).toBe(false);
    expect(calls.check[0]).toMatchObject({ field: "properties.stage", operation: "CLEAR" });
  });

  test("Add items sends the typed list as ADD", async () => {
    const user = userEvent.setup();
    const { calls } = setup();
    await pickField(user, "Tags");
    await user.click(screen.getByText("Add items"));
    await user.type(screen.getByLabelText("Tags", { selector: "input" }), "alpha{Enter}beta{Enter}");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await screen.findByText(/Will be updated/);
    expect(calls.check[0]).toMatchObject({ field: "properties.tags", operation: "ADD", value: ["alpha", "beta"] });
  });

  test("a relation is set through the editor's own relation picker", async () => {
    const user = userEvent.setup();
    const { calls } = setup();
    await pickField(user, "Owning team");
    await user.click(await screen.findByRole("combobox", { name: "Owning team" }));
    await user.click(await screen.findByRole("option", { name: "platform — Platform" }));
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await screen.findByText(/Will be updated/);
    expect(calls.check[0]).toMatchObject({ field: "relations.team", operation: "SET", value: "platform" });
  });

  test("a refused preview shows the fixed message inline and stays on the first step", async () => {
    const user = userEvent.setup();
    setup({ check: () => jsonResponse(400, { title: "Bad", status: 400, detail: "secret server detail" }) });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText(/The server refused this change/)).toBeInTheDocument();
    expect(screen.queryByText(/secret server detail/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Preview" })).toBeInTheDocument();
  });

  test("a busy server (429) and a timed-out apply read differently, and Back returns to the form", async () => {
    const user = userEvent.setup();
    let attempt = 0;
    setup({
      apply: () => {
        attempt += 1;
        if (attempt === 1) return jsonResponse(429, { title: "Busy", status: 429 });
        throw new DOMException("timed out", "TimeoutError");
      },
    });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(await screen.findByRole("button", { name: "Apply to 1 entity" }));
    expect(await screen.findByText(/The server is busy/)).toBeInTheDocument();
    // A 429 was refused before any write: retrying the same preview is safe.
    expect(screen.getByRole("button", { name: "Apply to 1 entity" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Apply to 1 entity" }));
    expect(await screen.findByText(/some entities may already have been changed/)).toBeInTheDocument();
    // The outcome is unknown: the old preview's stamps are never re-sent.
    expect(screen.getByRole("button", { name: "Apply to 1 entity" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Back" }));
    expect(screen.getByRole("button", { name: "Preview" })).toBeInTheDocument();
    expect(screen.queryByText(/some entities may already/)).not.toBeInTheDocument();
  });

  test("Apply is disabled when the preview predicts nothing to write", async () => {
    const user = userEvent.setup();
    setup({
      check: () =>
        jsonResponse(200, {
          results: [{ id: 1, identifier: "checkout", status: "INVALID", message: "properties.name: required", updatedAt: 1 }],
        }),
    });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("properties.name: required")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply to 0 entities" })).toBeDisabled();
  });

  test("ADD/REMOVE validate the typed items only: a delta below minItems previews, a SET of it does not", async () => {
    const user = userEvent.setup();
    const { calls } = setup();
    await pickField(user, "Tags");

    await user.type(screen.getByLabelText("Tags", { selector: "input" }), "alpha{Enter}");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText(/at least 2/i)).toBeInTheDocument();
    expect(calls.check).toHaveLength(0);

    await user.click(screen.getByText("Add items"));
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await screen.findByText(/Will be updated/);
    expect(calls.check[0]).toMatchObject({ field: "properties.tags", operation: "ADD", value: ["alpha"] });
  });

  test("switching to Clear drops the value's errors and previews without validating the unmounted widget", async () => {
    const user = userEvent.setup();
    const { calls } = setup();
    await pickField(user, "Note");
    await user.type(screen.getByRole("textbox", { name: "Note" }), "toolong");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText(/at most 3/i)).toBeInTheDocument();
    expect(calls.check).toHaveLength(0);

    await user.click(screen.getByText("Clear"));
    expect(screen.queryByText(/at most 3/i)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await screen.findByText(/Will be updated/);
    expect(calls.check[0]).toMatchObject({ field: "properties.note", operation: "CLEAR" });
  });

  test("the entities cache is refreshed after a successful apply", async () => {
    const user = userEvent.setup();
    const ok = setup();
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(await screen.findByRole("button", { name: "Apply to 1 entity" }));
    await screen.findByText(/Updated: /);
    expect(ok.invalidate).toHaveBeenCalledWith({ queryKey: ["entities"] });
  });

  test("a timed-out apply still refreshes the entities cache and says the result is unknown", async () => {
    const user = userEvent.setup();
    const { invalidate } = setup({
      apply: () => {
        throw new DOMException("timed out", "TimeoutError");
      },
    });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(await screen.findByRole("button", { name: "Apply to 1 entity" }));
    expect(await screen.findByText(/The result is unknown/)).toBeInTheDocument();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["entities"] });
  });

  test("a 5xx apply is just as unknown: Apply stays off until a fresh preview", async () => {
    const user = userEvent.setup();
    setup({ apply: () => jsonResponse(503, { title: "down", status: 503 }) });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(await screen.findByRole("button", { name: "Apply to 1 entity" }));
    expect(await screen.findByText(/The result is unknown/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply to 1 entity" })).toBeDisabled();
  });

  test("Escape, the overlay and the X are ignored while the preview request is in flight", async () => {
    const user = userEvent.setup();
    const { onClose } = setup({ check: () => new Promise<Response>(() => {}) as unknown as Response });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled());

    await user.keyboard("{Escape}");
    await user.click(document.querySelector(".mantine-Modal-overlay") as HTMLElement);
    await user.click(document.querySelector(".mantine-Modal-close") as HTMLElement);
    expect(onClose).not.toHaveBeenCalled();
  });

  test("Escape, the overlay and the X are ignored while the apply is in flight", async () => {
    const user = userEvent.setup();
    const { onClose } = setup({ apply: () => new Promise<Response>(() => {}) as unknown as Response });
    await pickField(user, "Stage");
    await setStage(user, "live");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(await screen.findByRole("button", { name: "Apply to 1 entity" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Back" })).toBeDisabled());

    await user.keyboard("{Escape}");
    await user.click(document.querySelector(".mantine-Modal-overlay") as HTMLElement);
    await user.click(document.querySelector(".mantine-Modal-close") as HTMLElement);
    expect(onClose).not.toHaveBeenCalled();
  });

  test("Cancel closes the dialog", async () => {
    const user = userEvent.setup();
    const { onClose } = setup();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
