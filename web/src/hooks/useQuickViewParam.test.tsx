import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { renderWithProviders, screen } from "../test/render";
import { useQuickViewParam } from "./useQuickViewParam";

function Probe() {
  const { fileId, open, close } = useQuickViewParam();
  const { search } = useLocation();
  return (
    <div>
      <p>id:{fileId === null ? "none" : fileId}</p>
      <p>search:{search}</p>
      <button type="button" onClick={() => open(7)}>
        open
      </button>
      <button type="button" onClick={close}>
        close
      </button>
    </div>
  );
}

describe("useQuickViewParam", () => {
  test.each([
    ["/files", "none"],
    ["/files?file=42", "42"],
    ["/files?file=0", "none"],
    ["/files?file=-3", "none"],
    ["/files?file=abc", "none"],
    ["/files?file=4.5", "none"],
  ])("%s reads as %s", (route, expected) => {
    renderWithProviders(<Probe />, { route });
    expect(screen.getByText(`id:${expected}`)).toBeInTheDocument();
  });

  test("open sets ?file beside the other params; close removes only it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />, { route: "/files?page=2" });
    await user.click(screen.getByRole("button", { name: "open" }));
    expect(screen.getByText("id:7")).toBeInTheDocument();
    expect(screen.getByText("search:?page=2&file=7")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "close" }));
    expect(screen.getByText("id:none")).toBeInTheDocument();
    expect(screen.getByText("search:?page=2")).toBeInTheDocument();
  });
});
