import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { notifications } from "@mantine/notifications";
import Users from "./Users";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";
const USER_ID_KEY = "toadie.auth.userId";

type FetchMock = ReturnType<typeof vi.fn>;

const SEED_USERS = [
  { id: 1, name: "Alice Admin", email: "alice@example.com", roles: ["ADMIN"] },
  { id: 2, name: "Bob Basic", email: "bob@example.com", roles: [] },
];

function usersPage(items: typeof SEED_USERS, total = items.length) {
  return jsonResponse(200, { items, page: 1, pageSize: 20, total });
}

function setupMocks(mockFetch: FetchMock, byUrl: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) =>
    url.startsWith("/api/v1/users")
      ? Promise.resolve(byUrl(url))
      : Promise.resolve(jsonResponse(404, {})),
  );
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/users" element={<Users />} />
      <Route path="/" element={<PathProbe />} />
    </Routes>,
    { route: "/users" },
  );
}

describe("Users page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    localStorage.setItem(USER_ID_KEY, "1");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("non-admin is redirected away without fetching", () => {
    localStorage.setItem(ROLES_KEY, "[]");
    setupMocks(mockFetch, () => usersPage(SEED_USERS));
    renderPage();
    expect(screen.getByTestId("probe")).toHaveTextContent("/");
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("renders rows with the admin badge and the own-row You marker", async () => {
    setupMocks(mockFetch, () => usersPage(SEED_USERS));
    renderPage();

    expect(await screen.findByText("Alice Admin")).toBeInTheDocument();
    expect(screen.getByText("Bob Basic")).toBeInTheDocument();
    expect(screen.getByText("Admin")).toBeInTheDocument();
    expect(screen.getByText("You")).toBeInTheDocument();
  });

  test("the own row offers Edit but neither Delete nor Reset password", async () => {
    setupMocks(mockFetch, () => usersPage(SEED_USERS));
    renderPage();

    await screen.findByText("Alice Admin");
    expect(screen.getByRole("link", { name: "Edit Alice Admin" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete Alice Admin" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Reset password for Alice Admin" }),
    ).not.toBeInTheDocument();
    // The other row has all three.
    expect(screen.getByRole("button", { name: "Delete Bob Basic" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reset password for Bob Basic" })).toBeInTheDocument();
  });

  test("the role filter refetches with role=", async () => {
    setupMocks(mockFetch, () => usersPage(SEED_USERS));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    fireEvent.click(screen.getByLabelText("Role", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Admin" }));

    await waitFor(() => {
      const called = mockFetch.mock.calls.some(
        ([url]) => typeof url === "string" && url.includes("role=ADMIN"),
      );
      expect(called).toBe(true);
    });
  });

  test("deleting a user confirms, DELETEs, refetches, and toasts", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    let listCount = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "DELETE" && url === "/api/v1/users/2") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url.startsWith("/api/v1/users?")) {
        listCount++;
        return Promise.resolve(usersPage(listCount === 1 ? SEED_USERS : [SEED_USERS[0]]));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Delete Bob Basic" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(screen.queryByText("Bob Basic")).not.toBeInTheDocument());
    expect(listCount).toBeGreaterThanOrEqual(2);
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ message: "User deleted", color: "teal" }),
    );
    toast.mockRestore();
  });

  test("a 409 on delete surfaces the last-administrator message in the modal", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "DELETE") {
        return Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409 }));
      }
      if (url.startsWith("/api/v1/users?")) return Promise.resolve(usersPage(SEED_USERS));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Delete Bob Basic" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^delete$/i }));

    expect(await screen.findByText(/last administrator cannot be removed/i)).toBeInTheDocument();
  });

  test("resetting a password confirms, PUTs a generated one, and reveals it once", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "PUT" && url === "/api/v1/users/2/password") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url.startsWith("/api/v1/users?")) return Promise.resolve(usersPage(SEED_USERS));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Reset password for Bob Basic" }));
    let dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^reset$/i }));

    // The reveal modal shows the masked generated password; the PUT body carried it.
    expect(await screen.findByText("Password reset")).toBeInTheDocument();
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "PUT" && url === "/api/v1/users/2/password",
    );
    const body = JSON.parse((putCall![1] as RequestInit).body as string) as { password: string };
    expect(body.password).toMatch(/^[A-Za-z0-9_-]{16}$/);
    expect(body).not.toHaveProperty("currentPassword");
    dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("*".repeat(16))).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: /show password/i }));
    expect(within(dialog).getByText(body.password)).toBeInTheDocument();

    // Closing discards the plaintext for good.
    await user.click(within(dialog).getByRole("button", { name: /^close$/i }));
    await waitFor(() => expect(screen.queryByText(body.password)).not.toBeInTheDocument());
  });

  test("shows the load-failure alert", async () => {
    setupMocks(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByText("Failed to load users")).toBeInTheDocument();
  });
});
