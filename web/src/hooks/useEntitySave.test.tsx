import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import type { Blueprint } from "../api/blueprints";
import { useEntitySave } from "./useEntitySave";
import { emptyEntityForm } from "../utils/entityForm";
import { ApiError } from "../api/http";
import { renderWithProviders } from "../test/render";

const BLUEPRINT = {
  id: 1,
  identifier: "service",
  title: "Service",
  schema: { properties: {}, required: [] },
  relations: {},
} as unknown as Blueprint;

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname + location.search}</div>;
}

function Harness({ saveRequest }: { saveRequest: (body: unknown) => Promise<unknown> }) {
  const save = useEntitySave({ blueprint: BLUEPRINT, saveRequest: saveRequest as never, toastKey: "entities.toast.created" });
  return (
    <div>
      <button onClick={() => void save.onSubmit(emptyEntityForm(BLUEPRINT))}>submit</button>
      {save.submitting && <span data-testid="submitting" />}
      {save.error && <span data-testid="error">{save.error}</span>}
      <span data-testid="findings">{JSON.stringify(save.findings)}</span>
    </div>
  );
}

function renderHarness(saveRequest: (body: unknown) => Promise<unknown>) {
  return renderWithProviders(
    <Routes>
      <Route path="/entities/new" element={<Harness saveRequest={saveRequest} />} />
      <Route path="/entities" element={<PathProbe />} />
    </Routes>,
    { route: "/entities/new" },
  );
}

describe("useEntitySave", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    localStorage.clear();
  });

  test("on success it navigates back to this blueprint's scoped list", async () => {
    const saveRequest = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/entities?blueprint=service"));
    expect(saveRequest).toHaveBeenCalledOnce();
  });

  test("a 409 renders the fixed conflict message inline and stays on the page", async () => {
    const saveRequest = vi.fn().mockRejectedValue(new ApiError(409, null));
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));

    expect(await screen.findByTestId("error")).toHaveTextContent(
      "An entity with this identifier already exists in this blueprint.",
    );
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
    expect(screen.getByTestId("findings")).toHaveTextContent("[]");
  });

  test("a 400 with findings exposes them, cleared again on the next submit", async () => {
    const findings = [{ code: "TEAM_TARGET_MISSING", field: "team", message: "gone" }];
    // The second call never resolves, so the component stays mounted (no navigation) and we
    // can observe `findings` reset to empty at the START of the new submit.
    const saveRequest = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(400, { title: "Invalid", status: 400, findings }))
      .mockImplementationOnce(() => new Promise(() => {}));
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));
    expect(await screen.findByTestId("error")).toBeInTheDocument();
    expect(screen.getByTestId("findings")).toHaveTextContent(JSON.stringify(findings));

    await user.click(screen.getByRole("button", { name: "submit" }));
    await waitFor(() => expect(screen.getByTestId("findings")).toHaveTextContent("[]"));
  });
});
