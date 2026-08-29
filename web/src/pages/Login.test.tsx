import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { jsonResponse } from "../test/http";
import Login from "./Login";

const SESSION = {
  token: "access-1",
  expiresAt: 1,
  refreshToken: "refresh-1",
  refreshExpiresAt: 2,
  userId: 7,
  roles: [],
};

describe("Login", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const fetchMock = () => fetch as unknown as ReturnType<typeof vi.fn>;

  async function submit(email = "user@test.example", password = "correct-horse") {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Email"), email);
    await user.type(screen.getByLabelText("Password"), password);
    await user.click(screen.getByRole("button", { name: "Sign in" }));
  }

  test("client-side validation blocks an empty submit without a request", async () => {
    renderWithProviders(<Login />, { route: "/login" });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("Enter a valid email")).toBeInTheDocument();
    expect(screen.getByText("Password is required")).toBeInTheDocument();
    expect(fetchMock()).not.toHaveBeenCalled();
  });

  test("a successful login persists the session", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
    renderWithProviders(<Login />, { route: "/login" });
    await submit();
    await waitFor(() => expect(localStorage.getItem("toadie.auth.token")).toBe("access-1"));
  });

  test("401 shows the invalid-credentials message", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(401, { title: "Unauthorized", status: 401 }));
    renderWithProviders(<Login />, { route: "/login" });
    await submit();
    expect(await screen.findByText("Invalid email or password")).toBeInTheDocument();
  });

  test("429 shows the lockout message", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(429, { title: "Too Many Requests", status: 429 }));
    renderWithProviders(<Login />, { route: "/login" });
    await submit();
    expect(await screen.findByText(/Too many failed login attempts/)).toBeInTheDocument();
  });

  test("an unexpected status shows the generic status message", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    renderWithProviders(<Login />, { route: "/login" });
    await submit();
    expect(await screen.findByText("Login failed (500)")).toBeInTheDocument();
  });

  test("a network failure shows the generic connectivity message", async () => {
    fetchMock().mockRejectedValueOnce(new Error("offline"));
    renderWithProviders(<Login />, { route: "/login" });
    await submit();
    expect(
      await screen.findByText("Login failed. Check your connection and try again."),
    ).toBeInTheDocument();
  });

  test("an MFA challenge switches to the code step; the verified code signs in", async () => {
    fetchMock()
      .mockResolvedValueOnce(
        jsonResponse(200, { mfaRequired: true, challengeId: "ch-9", expiresAt: 99 }),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, {
          token: "t", expiresAt: 1, refreshToken: "r", refreshExpiresAt: 2, userId: 7,
          roles: [], disabledFeatures: [], language: "en",
        }),
      );
    const user = userEvent.setup();
    renderWithProviders(<Login />, { route: "/login" });
    await submit();

    // The card switched to the PIN step — no session yet.
    expect(await screen.findByText("Enter your sign-in code")).toBeInTheDocument();
    expect(localStorage.getItem("toadie.auth.token")).toBeNull();

    const pin = screen.getAllByRole("textbox");
    for (let i = 0; i < 6; i += 1) await user.type(pin[i], String(i + 1));
    await user.click(screen.getByRole("button", { name: "Verify code" }));

    await waitFor(() => expect(localStorage.getItem("toadie.auth.token")).toBe("t"));
    const [url, init] = fetchMock().mock.calls.at(-1)!;
    expect(url).toBe("/api/v1/login/mfa");
    expect(JSON.parse((init as { body: string }).body)).toEqual({
      challengeId: "ch-9",
      code: "123456",
    });
  });

  test("a wrong code shows the invalid-code message and stays on the PIN step", async () => {
    fetchMock()
      .mockResolvedValueOnce(
        jsonResponse(200, { mfaRequired: true, challengeId: "ch-9", expiresAt: 99 }),
      )
      .mockResolvedValueOnce(jsonResponse(401, { title: "Unauthorized", status: 401 }));
    const user = userEvent.setup();
    renderWithProviders(<Login />, { route: "/login" });
    await submit();
    await screen.findByText("Enter your sign-in code");

    const pin = screen.getAllByRole("textbox");
    for (let i = 0; i < 6; i += 1) await user.type(pin[i], "0");
    await user.click(screen.getByRole("button", { name: "Verify code" }));

    expect(
      await screen.findByText("That code is invalid or has expired. Sign in again to get a new one."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Verify code" })).toBeInTheDocument();
  });

  test("links to the self-service password reset", () => {
    renderWithProviders(<Login />, { route: "/login" });
    expect(screen.getByRole("link", { name: /forgot password/i })).toHaveAttribute(
      "href",
      "/reset-password",
    );
  });
});
