import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import i18n from "../i18n";
import LanguageSwitcher from "./LanguageSwitcher";

describe("LanguageSwitcher", () => {
  afterEach(async () => {
    vi.unstubAllGlobals();
    localStorage.clear();
    await i18n.changeLanguage("en");
  });

  test("lists every language by its native name and switches on pick", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LanguageSwitcher />);

    await user.click(screen.getByRole("button", { name: "Language" }));
    expect(await screen.findByText("English")).toBeInTheDocument();

    await user.click(screen.getByText("Polski"));
    expect(i18n.resolvedLanguage).toBe("pl");
    // The trigger reflects the new language code.
    expect(screen.getByRole("button", { name: "Język" })).toHaveTextContent("PL");
  });

  test("a signed-in pick also saves the language server-side (fire-and-forget)", async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.userId", "7");
    const user = userEvent.setup();
    renderWithProviders(<LanguageSwitcher />);

    await user.click(screen.getByRole("button", { name: "Language" }));
    await user.click(await screen.findByText("Polski"));

    await vi.waitFor(() => {
      const call = mockFetch.mock.calls.find(([url]) => url === "/api/v1/users/7/language");
      expect(call).toBeDefined();
      expect((call![1] as RequestInit).method).toBe("PUT");
      expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({ language: "pl" });
    });
  });

  test("without a session userId no server save is attempted", async () => {
    const mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<LanguageSwitcher />);

    await user.click(screen.getByRole("button", { name: "Language" }));
    await user.click(await screen.findByText("Polski"));

    expect(i18n.resolvedLanguage).toBe("pl");
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
