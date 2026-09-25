import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, screen, waitFor, within } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import { useLocation } from "react-router-dom";
import IntegrationClients from "./IntegrationClients";
import { TOKEN_KEY, USER_ID_KEY } from "../api/session";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function tokenFor(familyId: string, userId: number): string {
  return `header.${btoa(JSON.stringify({ sid: familyId, userId }))}.signature`;
}

function replaceSession(familyId: string, userId: number) {
  const oldValue = localStorage.getItem(TOKEN_KEY);
  const newValue = tokenFor(familyId, userId);
  act(() => {
    localStorage.setItem(TOKEN_KEY, newValue);
    localStorage.setItem(USER_ID_KEY, String(userId));
    window.dispatchEvent(new StorageEvent("storage", { key: TOKEN_KEY, oldValue, newValue }));
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((fulfil, fail) => {
    resolve = fulfil;
    reject = fail;
  });
  return { promise, resolve, reject };
}

const CLIENTS = [
  {
    id: 1,
    name: "warehouse-sync",
    createdAt: 1_700_000_000_000,
    createdByName: "Ada Admin",
    lastUsedAt: 1_700_000_100_000,
    revoked: false,
    scope: "read" as const,
  },
  {
    id: 2,
    name: "old-reporting",
    createdAt: 1_600_000_000_000,
    createdByName: "Ada Admin",
    lastUsedAt: null,
    revoked: true,
    revokedAt: null,
    scope: "write" as const,
  },
];

const CREATED = {
  client: {
    id: 3,
    name: "bi-export",
    createdAt: 1_700_000_200_000,
    createdByName: "Ada Admin",
    revoked: false,
    scope: "read" as const,
  },
  apiKey: "toadie_int_abcdefghij0123456789abcdefghij0123456789abc",
};

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

describe("IntegrationClients page", () => {
  let fetchMock: FetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function serve({ createStatus = 201, revokeStatus = 204 } = {}) {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "POST" && url.endsWith("/revoke")) {
        return Promise.resolve(
          revokeStatus === 204
            ? new Response(null, { status: 204 })
            : jsonResponse(revokeStatus, { title: "Conflict", status: revokeStatus }),
        );
      }
      if (method === "POST") {
        return Promise.resolve(
          createStatus === 201
            ? jsonResponse(201, CREATED)
            : jsonResponse(createStatus, { title: "Invalid", status: createStatus }),
        );
      }
      return Promise.resolve(
        jsonResponse(200, { items: CLIENTS, page: 1, pageSize: 20, total: CLIENTS.length }),
      );
    });
  }

  test("non-admins are redirected without fetching", async () => {
    localStorage.setItem("toadie.auth.roles", "[]");
    serve();
    renderWithProviders(
      <>
        <IntegrationClients />
        <LocationProbe />
      </>,
      { route: "/integration-clients" },
    );

    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/"));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test("renders the paged list and offers revoke only for active keys", async () => {
    serve();
    renderWithProviders(<IntegrationClients />);

    expect(await screen.findByText("warehouse-sync")).toBeInTheDocument();
    expect(screen.getByText("old-reporting")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Revoked")).toBeInTheDocument();
    expect(screen.getByText("Never used")).toBeInTheDocument();
    expect(screen.getByText("2 total")).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("Read")).toBeInTheDocument();
    expect(within(table).getByText("Write")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/integration-clients?page=1&pageSize=20",
      expect.any(Object),
    );

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Operations for warehouse-sync" }));
    expect(await screen.findByRole("menuitem", { name: "Revoke API key for warehouse-sync" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("button", { name: "Operations for old-reporting" })).not.toBeInTheDocument();
  });

  test("creates a client and reveals its API key only in the one-time panel", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "bi-export");
    await user.click(screen.getByRole("button", { name: "Add client" }));

    expect(await screen.findByText("API key for “bi-export” — shown only once")).toBeInTheDocument();
    expect(screen.queryByText(CREATED.apiKey)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show password" }));
    expect(screen.getByText(CREATED.apiKey)).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(JSON.parse((post?.[1] as RequestInit).body as string)).toEqual({ name: "bi-export", scope: "read" });
  });

  test("creates a client with the write scope after choosing it in the Select", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "bi-export");
    await user.click(screen.getByLabelText("Scope", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Write" }));
    await user.click(screen.getByRole("button", { name: "Add client" }));

    await screen.findByText("API key for “bi-export” — shown only once");
    const post = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(JSON.parse((post?.[1] as RequestInit).body as string)).toEqual({ name: "bi-export", scope: "write" });
  });

  test("a second create remounts the API-key panel masked", async () => {
    let createCount = 0;
    const second = {
      client: { ...CREATED.client, id: 4, name: "dashboard" },
      apiKey: "toadie_int_secondsecondsecondsecondsecondsecond987654",
    };
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST") {
        createCount += 1;
        return Promise.resolve(jsonResponse(201, createCount === 1 ? CREATED : second));
      }
      return Promise.resolve(jsonResponse(200, { items: CLIENTS, page: 1, pageSize: 20, total: 2 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "bi-export");
    await user.click(screen.getByRole("button", { name: "Add client" }));
    await user.click(await screen.findByRole("button", { name: "Show password" }));
    expect(screen.getByText(CREATED.apiKey)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Client name"), "dashboard");
    await user.click(screen.getByRole("button", { name: "Add client" }));
    await screen.findByText("API key for “dashboard” — shown only once");
    expect(screen.queryByText(second.apiKey)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Show password" })).toHaveAttribute("aria-pressed", "false");
  });

  test.each([
    { replacement: "another admin account", userId: 2 },
    { replacement: "a fresh login for the same admin", userId: 1 },
  ])("clears a revealed key when $replacement replaces the current login", async ({ userId }) => {
    localStorage.setItem(TOKEN_KEY, tokenFor("admin-a", 1));
    localStorage.setItem(USER_ID_KEY, "1");
    serve();
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "bi-export");
    await user.click(screen.getByRole("button", { name: "Add client" }));
    await user.click(await screen.findByRole("button", { name: "Show password" }));
    expect(screen.getByText(CREATED.apiKey)).toBeInTheDocument();

    replaceSession("admin-b", userId);
    await waitFor(() => expect(screen.queryByText(CREATED.apiKey)).not.toBeInTheDocument());
    expect(screen.queryByText("API key for “bi-export” — shown only once")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Client name")).toHaveValue("");
  });

  test("ignores a create response that completes after the session boundary", async () => {
    localStorage.setItem(TOKEN_KEY, tokenFor("admin-a", 1));
    localStorage.setItem(USER_ID_KEY, "1");
    const create = deferred<Response>();
    let listRequests = 0;
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST") return create.promise;
      listRequests += 1;
      return Promise.resolve(jsonResponse(200, { items: CLIENTS, page: 1, pageSize: 20, total: 2 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "bi-export");
    await user.click(screen.getByRole("button", { name: "Add client" }));
    replaceSession("admin-b", 1);
    expect(screen.getByLabelText("Client name")).toHaveValue("");

    await act(async () => { create.resolve(jsonResponse(201, CREATED)); });
    await waitFor(() => expect(screen.queryByText("API key for “bi-export” — shown only once")).not.toBeInTheDocument());
    expect(screen.queryByText(CREATED.apiKey)).not.toBeInTheDocument();
    expect(listRequests).toBe(1);
  });

  test("ignores a create failure that completes after the session boundary", async () => {
    localStorage.setItem(TOKEN_KEY, tokenFor("admin-a", 1));
    localStorage.setItem(USER_ID_KEY, "1");
    const create = deferred<Response>();
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      (init?.method ?? "GET") === "POST"
        ? create.promise
        : Promise.resolve(jsonResponse(200, { items: CLIENTS, page: 1, pageSize: 20, total: 2 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "admin-a-client");
    await user.click(screen.getByRole("button", { name: "Add client" }));
    replaceSession("admin-b", 1);
    await user.type(screen.getByLabelText("Client name"), "admin-b-client");

    await act(async () => { create.reject(new Error("admin A network failure")); });
    await waitFor(() => expect(screen.getByLabelText("Client name")).toHaveValue("admin-b-client"));
    expect(screen.queryByText(/Client creation failed/)).not.toBeInTheDocument();
    expect(screen.queryByText("API key for “bi-export” — shown only once")).not.toBeInTheDocument();
  });

  test.each(["success", "failure"] as const)(
    "ignores stale revoke %s after replacement session opens its own modal",
    async (outcome) => {
      localStorage.setItem(TOKEN_KEY, tokenFor("admin-a", 1));
      localStorage.setItem(USER_ID_KEY, "1");
      const revoke = deferred<Response>();
      let listRequests = 0;
      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        if ((init?.method ?? "GET") === "POST" && url.endsWith("/revoke")) return revoke.promise;
        listRequests += 1;
        return Promise.resolve(jsonResponse(200, { items: CLIENTS, page: 1, pageSize: 20, total: 2 }));
      });
      const toast = vi.spyOn(notifications, "show").mockReturnValue("toast");
      const user = userEvent.setup();
      renderWithProviders(<IntegrationClients />);
      await screen.findByText("warehouse-sync");

      await user.click(screen.getByRole("button", { name: "Operations for warehouse-sync" }));
      await user.click(await screen.findByRole("menuitem", { name: "Revoke API key for warehouse-sync" }));
      let dialog = await screen.findByRole("dialog", { name: "Revoke this API key?" });
      await user.click(within(dialog).getByRole("button", { name: "Revoke" }));

      replaceSession("admin-b", 2);
      await user.type(screen.getByLabelText("Client name"), "admin-b-client");
      await user.click(screen.getByRole("button", { name: "Operations for warehouse-sync" }));
      await user.click(await screen.findByRole("menuitem", { name: "Revoke API key for warehouse-sync" }));
      dialog = await screen.findByRole("dialog", { name: "Revoke this API key?" });

      await act(async () => {
        if (outcome === "success") revoke.resolve(new Response(null, { status: 204 }));
        else revoke.reject(new Error("admin A network failure"));
      });

      await waitFor(() => expect(screen.getByLabelText("Client name")).toHaveValue("admin-b-client"));
      expect(screen.getByRole("dialog", { name: "Revoke this API key?" })).toBeInTheDocument();
      expect(within(dialog).queryByText(/API key revocation failed/)).not.toBeInTheDocument();
      expect(toast).not.toHaveBeenCalled();
      expect(listRequests).toBe(1);
      toast.mockRestore();
    },
  );

  test("reveals the one-time key without waiting for the list refetch", async () => {
    const refresh = deferred<Response>();
    let listRequests = 0;
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST") return Promise.resolve(jsonResponse(201, CREATED));
      listRequests += 1;
      return listRequests === 1
        ? Promise.resolve(jsonResponse(200, { items: CLIENTS, page: 1, pageSize: 20, total: 2 }))
        : refresh.promise;
    });
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "bi-export");
    await user.click(screen.getByRole("button", { name: "Add client" }));

    expect(await screen.findByText("API key for “bi-export” — shown only once")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add client" })).not.toHaveAttribute("data-loading");
    expect(screen.getByLabelText("Client name")).toHaveValue("");
    expect(listRequests).toBe(2);
    await act(async () => { refresh.resolve(jsonResponse(200, { items: CLIENTS, page: 1, pageSize: 20, total: 2 })); });
  });

  test("confirms a revoke, POSTs it, and shows success", async () => {
    serve();
    const toast = vi.spyOn(notifications, "show").mockReturnValue("toast");
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.click(screen.getByRole("button", { name: "Operations for warehouse-sync" }));
    await user.click(await screen.findByRole("menuitem", { name: "Revoke API key for warehouse-sync" }));
    const dialog = await screen.findByRole("dialog", { name: "Revoke this API key?" });
    await user.click(within(dialog).getByRole("button", { name: "Revoke" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/integration-clients/1/revoke",
      expect.objectContaining({ method: "POST" }),
    ));
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "API key revoked", color: "teal" }));
    toast.mockRestore();
  });

  test("maps create and revoke failures inline", async () => {
    serve({ createStatus: 400, revokeStatus: 409 });
    const user = userEvent.setup();
    renderWithProviders(<IntegrationClients />);
    await screen.findByText("warehouse-sync");

    await user.type(screen.getByLabelText("Client name"), "bad");
    await user.click(screen.getByRole("button", { name: "Add client" }));
    expect(await screen.findByText(/non-empty, single-line client name/)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Client name"), "-edited");
    expect(screen.queryByText(/non-empty, single-line client name/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Operations for warehouse-sync" }));
    await user.click(await screen.findByRole("menuitem", { name: "Revoke API key for warehouse-sync" }));
    const dialog = await screen.findByRole("dialog", { name: "Revoke this API key?" });
    await user.click(within(dialog).getByRole("button", { name: "Revoke" }));
    expect(await within(dialog).findByText("This API key has already been revoked.")).toBeInTheDocument();
  });
});
