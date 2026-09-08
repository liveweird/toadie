import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { useBlueprintSave } from "./useBlueprintSave";
import { emptyBlueprintForm } from "../utils/blueprintForm";
import { ApiError } from "../api/http";
import { renderWithProviders } from "../test/render";

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function Harness({ saveRequest }: { saveRequest: (body: unknown) => Promise<unknown> }) {
  const save = useBlueprintSave({
    saveRequest: saveRequest as never,
    toastKey: "blueprints.toast.created",
  });
  return (
    <div>
      <button onClick={() => void save.onSubmit(emptyBlueprintForm())}>submit</button>
      {save.submitting && <span data-testid="submitting" />}
      {save.error && <span data-testid="error">{save.error}</span>}
    </div>
  );
}

function renderHarness(saveRequest: (body: unknown) => Promise<unknown>) {
  return renderWithProviders(
    <Routes>
      <Route path="/blueprints/new" element={<Harness saveRequest={saveRequest} />} />
      <Route path="/blueprints" element={<PathProbe />} />
    </Routes>,
    { route: "/blueprints/new" },
  );
}

describe("useBlueprintSave", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    localStorage.clear();
  });

  test("on success it navigates back to the list — no waiver step", async () => {
    const saveRequest = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints"));
    expect(saveRequest).toHaveBeenCalledOnce();
  });

  test("a 409 renders the fixed conflict message inline and stays on the page", async () => {
    const saveRequest = vi.fn().mockRejectedValue(new ApiError(409, null));
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));

    expect(await screen.findByTestId("error")).toHaveTextContent("A blueprint with this identifier already exists");
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });
});
