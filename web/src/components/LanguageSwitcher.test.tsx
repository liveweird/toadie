import { afterEach, describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import i18n from "../i18n";
import LanguageSwitcher from "./LanguageSwitcher";

describe("LanguageSwitcher", () => {
  afterEach(async () => {
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
});
