import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
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

  test("switchTo never writes the old world back to storage while router 7's startTransition is still settling the route", async () => {
    // React Router 7 wraps `navigate` in startTransition; switchTo's OWN setStored(next) is a
    // plain synchronous update, so React renders once with the NEW stored world but the OLD
    // pathname before the transition commits. Without the `pending` guard the reconcile
    // effect sees that stale mismatch and writes the OLD world straight back — a reload in
    // that exact window would restore the wrong world.
    const user = userEvent.setup();
    renderAt("/entity-hierarchy"); // a Port route
    // spyOn without mockImplementation calls through to the real setItem, so this observes
    // every write without changing behavior.
    const setItemSpy = vi.spyOn(localStorage, "setItem");

    try {
      await user.click(screen.getByRole("button", { name: "go backstage" }));
      expect(await screen.findByText("at /hierarchy")).toBeInTheDocument();

      const writes = setItemSpy.mock.calls
        .filter(([key]) => key === STORAGE_KEY)
        .map(([, value]) => JSON.parse(value) as string);

      expect(writes.length).toBeGreaterThan(0);
      expect(writes.at(-1)).toBe("backstage");
      // Once "backstage" is written, "port" must never be written again afterward — the
      // reconcile effect writing the stale route world back in the transition window.
      const firstBackstageIndex = writes.indexOf("backstage");
      expect(writes.slice(firstBackstageIndex + 1)).not.toContain("port");
    } finally {
      setItemSpy.mockRestore();
    }
  });
});
