import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import { Route, Routes, useLocation } from "react-router-dom";
import { useCatalogFileSave } from "./useCatalogFileSave";
import { emptyCatalogFileForm } from "../utils/catalogFileForm";
import { CATALOG_CREATE_ERROR_KEYS } from "../utils/saveError";
import { ApiError } from "../api/http";
import i18n from "../i18n";
import { renderWithProviders } from "../test/render";

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

type SaveRequest = (...args: unknown[]) => Promise<unknown>;

function Harness({ saveRequest, sourceUrl = "" }: { saveRequest: SaveRequest; sourceUrl?: string }) {
  const save = useCatalogFileSave({
    saveRequest: saveRequest as never,
    toastKey: "catalog.toast.created",
    errorKeys: CATALOG_CREATE_ERROR_KEYS,
  });
  const values = { ...emptyCatalogFileForm(), name: "orders", sourceUrl };
  return (
    <div>
      <button onClick={() => void save.onSubmit(values)}>submit</button>
      <button onClick={() => void save.onSaveAnyway()}>anyway</button>
      <button onClick={save.cancelWaiver}>cancel</button>
      {save.error && <span data-testid="error">{save.error}</span>}
      <span data-testid="findings">{JSON.stringify(save.waiverFindings)}</span>
    </div>
  );
}

function renderHarness(saveRequest: SaveRequest, sourceUrl?: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/files/new" element={<Harness saveRequest={saveRequest} sourceUrl={sourceUrl} />} />
      <Route path="/files" element={<PathProbe />} />
    </Routes>,
    { route: "/files/new" },
  );
}

const FINDINGS = [{ code: "OWNER_MISSING", field: "spec.owner", message: "gone" }];
const softRejection = () => new ApiError(400, { title: "Invalid", status: 400, findings: FINDINGS });

describe("useCatalogFileSave", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    localStorage.clear();
  });

  test("a strict save sends the document with the trimmed source and returns to the list", async () => {
    const invalidateQueriesSpy = vi.spyOn(QueryClient.prototype, "invalidateQueries");
    const saveRequest = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderHarness(saveRequest, "  https://github.com/acme/repo/catalog-info.yaml  ");

    await user.click(screen.getByRole("button", { name: "submit" }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    expect(saveRequest).toHaveBeenCalledOnce();
    // The prefix invalidation is what keeps the Files list fresh after the redirect.
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: ["catalogFiles"] });
    const [body, options] = saveRequest.mock.calls[0];
    expect(body).toMatchObject({ kind: "Component", sourceUrl: "https://github.com/acme/repo/catalog-info.yaml" });
    expect(options).toBeUndefined();
  });

  test("a blank source reference is sent as undefined", async () => {
    const saveRequest = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderHarness(saveRequest, "   ");

    await user.click(screen.getByRole("button", { name: "submit" }));

    await waitFor(() => expect(saveRequest).toHaveBeenCalledOnce());
    expect(saveRequest.mock.calls[0][0].sourceUrl).toBeUndefined();
  });

  test("a revision conflict maps to the stale-revision message, not the identity clash", async () => {
    const saveRequest = vi
      .fn()
      .mockRejectedValue(new ApiError(409, { type: "urn:toadie:catalog-revision-conflict", status: 409, title: "Stale" }));
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));

    expect(await screen.findByTestId("error")).toHaveTextContent(i18n.t("catalog.staleRevision"));
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("a 400 without findings renders the error and parks no waiver", async () => {
    const saveRequest = vi.fn().mockRejectedValue(new ApiError(400, { title: "Bad request", status: 400, detail: "Namespace is not allowed" }));
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));

    expect(await screen.findByTestId("error")).not.toHaveTextContent("");
    expect(screen.getByTestId("findings")).toHaveTextContent("null");
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("an ordinary 409 renders the page's conflict wording", async () => {
    const saveRequest = vi.fn().mockRejectedValue(new ApiError(409, null));
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));

    expect(await screen.findByTestId("error")).toHaveTextContent(i18n.t("catalog.conflictError"));
    expect(screen.getByTestId("findings")).toHaveTextContent("null");
  });

  test("a soft rejection parks the request; Save anyway retries with the waiver", async () => {
    const saveRequest = vi.fn().mockRejectedValueOnce(softRejection()).mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));
    await waitFor(() => expect(screen.getByTestId("findings")).toHaveTextContent(JSON.stringify(FINDINGS)));
    expect(screen.queryByTestId("error")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "anyway" }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    expect(saveRequest).toHaveBeenCalledTimes(2);
    expect(saveRequest.mock.calls[1][1]).toEqual({ allowInvalid: true });
  });

  test("Save anyway without a parked waiver does nothing", async () => {
    const saveRequest = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "anyway" }));

    expect(saveRequest).not.toHaveBeenCalled();
  });

  test("cancelling the waiver clears the parked findings", async () => {
    const saveRequest = vi.fn().mockRejectedValue(softRejection());
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));
    await waitFor(() => expect(screen.getByTestId("findings")).toHaveTextContent(JSON.stringify(FINDINGS)));

    await user.click(screen.getByRole("button", { name: "cancel" }));

    expect(screen.getByTestId("findings")).toHaveTextContent("null");
  });

  test("a failed Save anyway renders the mapped error and drops the waiver", async () => {
    const saveRequest = vi.fn().mockRejectedValueOnce(softRejection()).mockRejectedValueOnce(new ApiError(500, null));
    const user = userEvent.setup();
    renderHarness(saveRequest);

    await user.click(screen.getByRole("button", { name: "submit" }));
    await waitFor(() => expect(screen.getByTestId("findings")).toHaveTextContent(JSON.stringify(FINDINGS)));
    await user.click(screen.getByRole("button", { name: "anyway" }));

    expect(await screen.findByTestId("error")).toHaveTextContent(
      i18n.t("common.error.createFailedStatus", { status: 500 }),
    );
    expect(screen.getByTestId("findings")).toHaveTextContent("null");
  });
});
