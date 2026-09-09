import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { renderWithProviders, screen } from "../test/render";
import { useBlueprintParam } from "./useBlueprintParam";

function Probe() {
  const { blueprint, setBlueprint } = useBlueprintParam();
  const { search } = useLocation();
  return (
    <div>
      <p>blueprint:{blueprint === null ? "none" : blueprint}</p>
      <p>search:{search}</p>
      <button type="button" onClick={() => setBlueprint("service")}>
        pick
      </button>
      <button type="button" onClick={() => setBlueprint(null)}>
        clear
      </button>
    </div>
  );
}

describe("useBlueprintParam", () => {
  test.each([
    ["/entities", "none"],
    ["/entities?blueprint=service", "service"],
    ["/entities?blueprint=", "none"],
    ["/entities?blueprint=%20", "none"],
  ])("%s reads as %s", (route, expected) => {
    renderWithProviders(<Probe />, { route });
    expect(screen.getByText(`blueprint:${expected}`)).toBeInTheDocument();
  });

  test("setBlueprint replaces the param beside others; null removes it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />, { route: "/entities?page=2" });
    await user.click(screen.getByRole("button", { name: "pick" }));
    expect(screen.getByText("blueprint:service")).toBeInTheDocument();
    expect(screen.getByText("search:?page=2&blueprint=service")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "clear" }));
    expect(screen.getByText("blueprint:none")).toBeInTheDocument();
    expect(screen.getByText("search:?page=2")).toBeInTheDocument();
  });
});
