import { vi } from "vitest";

/**
 * Layout for a windowed list under happy-dom, which lays nothing out: every element measures
 * 0px, so the virtualizer would "fit" an unbounded number of rows and render all of them.
 * Gives a 600px-tall window and makes every element carrying `data-index` (the rows
 * `useVirtualRows` measures) `rowHeight` px tall (or whatever the function says for that element),
 * the way a browser would. The caller's
 * afterEach must `vi.unstubAllGlobals()` and `vi.restoreAllMocks()`.
 */
export function stubVirtualLayout(rowHeight: number | ((row: HTMLElement) => number) = 30) {
  class StubResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal("ResizeObserver", StubResizeObserver);
  vi.stubGlobal("innerHeight", 600);
  vi.stubGlobal("innerWidth", 1000);
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockImplementation(function (this: HTMLElement) {
    if (!this.hasAttribute("data-index")) return 0;
    return typeof rowHeight === "function" ? rowHeight(this) : rowHeight;
  });
}
