import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { useLocation } from "react-router-dom";
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

const OPTIONS_URL = "/api/v1/login/options";

describe("Login", () => {
  // The page reads the public login options on mount; that read is answered apart from the
  // queued `fetchMock()` responses so the tests below keep scripting only their own requests.
  let optionsResponse: () => Response | Promise<Response>;
  const apiMock = vi.fn();

  beforeEach(() => {
    optionsResponse = () => jsonResponse(200, { sso: null });
    apiMock.mockReset();
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) =>
      url === OPTIONS_URL ? Promise.resolve(optionsResponse()) : (apiMock(url, init) as Promise<Response>),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    sessionStorage.clear();
    localStorage.clear();
  });

  const fetchMock = () => apiMock;

  async function submit(email = "user@test.example", password = "correct-horse") {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Email"), email);
    await user.type(screen.getByLabelText("Password"), password);
    await user.click(screen.getByRole("button", { name: "Sign in" }));
  }

  function LocationProbe() {
    const location = useLocation();
    return <output data-testid="location">{location.pathname + location.search + location.hash}</output>;
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

  test("a successful login restores pathname, search, and hash", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
    renderWithProviders(
      <><Login /><LocationProbe /></>,
      { route: { pathname: "/login", state: { from: { pathname: "/entities", search: "?blueprint=_team", hash: "#quality" } } } },
    );
    await submit();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/entities?blueprint=_team#quality"));
  });

  test("a successful login refuses an external return location", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
    renderWithProviders(
      <><Login /><LocationProbe /></>,
      { route: { pathname: "/login", state: { from: { pathname: "//example.invalid/steal" } } } },
    );
    await submit();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/));
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
    expect(await screen.findByText("Too many sign-in attempts. Try again shortly.")).toBeInTheDocument();
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
    renderWithProviders(
      <><Login /><LocationProbe /></>,
      { route: { pathname: "/login", state: { from: { pathname: "/files", search: "?file=123", hash: "#yaml" } } } },
    );
    await submit();

    // The card switched to the PIN step — no session yet.
    expect(await screen.findByText("Enter your sign-in code")).toBeInTheDocument();
    expect(localStorage.getItem("toadie.auth.token")).toBeNull();

    const pin = screen.getAllByRole("textbox");
    for (let i = 0; i < 6; i += 1) await user.type(pin[i], String(i + 1));
    await user.click(screen.getByRole("button", { name: "Verify code" }));

    await waitFor(() => expect(localStorage.getItem("toadie.auth.token")).toBe("t"));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/files?file=123#yaml"));
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

  describe("single sign-on", () => {
    function stubAssign() {
      const assign = vi.fn();
      vi.stubGlobal("location", { ...window.location, assign });
      return assign;
    }

    test("shows the provider button above the password form when the options enable SSO", async () => {
      optionsResponse = () => jsonResponse(200, { sso: { label: "Okta" } });
      renderWithProviders(<Login />, { route: "/login" });
      const button = await screen.findByRole("button", { name: "Sign in with Okta" });
      expect(screen.getByText("or")).toBeInTheDocument();
      const email = screen.getByLabelText("Email");
      expect(button.compareDocumentPosition(email) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(screen.getByRole("link", { name: /forgot password/i })).toBeInTheDocument();
    });

    test("shows no button when SSO is off", async () => {
      renderWithProviders(<Login />, { route: "/login" });
      await waitFor(() => expect(screen.getByLabelText("Email")).toBeInTheDocument());
      expect(screen.queryByRole("button", { name: /sign in with/i })).not.toBeInTheDocument();
      expect(screen.queryByText("or")).not.toBeInTheDocument();
    });

    test("a failed options read hides the button and leaves the password form working", async () => {
      optionsResponse = () => jsonResponse(500, { title: "boom", status: 500 });
      fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
      renderWithProviders(<Login />, { route: "/login" });
      await submit();
      await waitFor(() => expect(localStorage.getItem("toadie.auth.token")).toBe("access-1"));
      expect(screen.queryByRole("button", { name: /sign in with/i })).not.toBeInTheDocument();
    });

    test("clicking stores the return location and navigates to the start URL", async () => {
      const assign = stubAssign();
      optionsResponse = () => jsonResponse(200, { sso: { label: "Okta" } });
      renderWithProviders(<Login />, {
        route: { pathname: "/login", state: { from: { pathname: "/entities", search: "?blueprint=_team", hash: "#q" } } },
      });
      await userEvent.click(await screen.findByRole("button", { name: "Sign in with Okta" }));
      expect(assign).toHaveBeenCalledWith("/api/v1/login/sso/start");
      expect(JSON.parse(sessionStorage.getItem("toadie.sso.returnTo")!)).toEqual({
        pathname: "/entities", search: "?blueprint=_team", hash: "#q",
      });
    });

    test("clicking without a protected-route origin stores nothing", async () => {
      const assign = stubAssign();
      sessionStorage.setItem("toadie.sso.returnTo", JSON.stringify({ pathname: "/stale" }));
      optionsResponse = () => jsonResponse(200, { sso: { label: "Okta" } });
      renderWithProviders(<Login />, { route: "/login" });
      await userEvent.click(await screen.findByRole("button", { name: "Sign in with Okta" }));
      expect(assign).toHaveBeenCalledWith("/api/v1/login/sso/start");
      expect(sessionStorage.getItem("toadie.sso.returnTo")).toBeNull();
    });

    test.each([
      ["cancelled", /sign-in was cancelled/i],
      ["expired", /took too long and expired/i],
      ["failed", /single sign-on failed/i],
      ["not_allowed", /cannot sign in through single sign-on/i],
      ["something_new", /single sign-on failed/i],
    ])("#sso_error=%s renders its alert and strips the fragment", async (code, message) => {
      renderWithProviders(<><Login /><LocationProbe /></>, { route: `/login#sso_error=${code}` });
      expect(await screen.findByText(message)).toBeInTheDocument();
      await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/login$/));
    });

    test("stripping the error fragment keeps the protected-route origin for the next sign-in", async () => {
      renderWithProviders(
        <><Login /><LocationProbe /></>,
        { route: { pathname: "/login", hash: "#sso_error=cancelled", state: { from: { pathname: "/files" } } } },
      );
      await screen.findByText(/sign-in was cancelled/i);
      fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
      await submit();
      await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/files$/));
    });
  });
});
