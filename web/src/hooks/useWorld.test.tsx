import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { useWorld } from "./useWorld";

const STORAGE_KEY = "toadie.viewSettings.appShell.world";

function Probe() {
  const { world, switchTo } = useWorld();
  const { pathname } = useLocation();
  return (
    <div>
      <p>world: {world}</p>
      <p>at {pathname}</p>
      <button onClick={() => switchTo("backstage")}>go backstage</button>
    </div>
  );
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Probe />
    </MemoryRouter>,
  );
}

describe("useWorld", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  test("empty storage on a global page falls back to the default world", () => {
    renderAt("/changelog");
    expect(screen.getByText("world: port")).toBeInTheDocument();
  });

  test("a stored world is honored on a global page, and storage stays untouched", () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify("backstage"));
    renderAt("/changelog");
    expect(screen.getByText("world: backstage")).toBeInTheDocument();
    expect(localStorage.getItem(STORAGE_KEY)).toBe(JSON.stringify("backstage"));
  });

  test("the route's own world overrides a differing stored world, and rewrites storage", () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify("backstage"));
    renderAt("/blueprints");
    expect(screen.getByText("world: port")).toBeInTheDocument();
    expect(localStorage.getItem(STORAGE_KEY)).toBe(JSON.stringify("port"));
  });

  test("switchTo navigates to the target world's home and writes storage", async () => {
    const user = userEvent.setup();
    renderAt("/entity-hierarchy");
    await user.click(screen.getByRole("button", { name: "go backstage" }));
    expect(await screen.findByText("at /hierarchy")).toBeInTheDocument();
    expect(localStorage.getItem(STORAGE_KEY)).toBe(JSON.stringify("backstage"));
  });
});
