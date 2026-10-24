import { vi } from "vitest";

/**
 * Layout for a windowed list under happy-dom, which lays nothing out: every element measures
 * 0px, so the virtualizer would "fit" an unbounded number of rows and render all of them.
 * Gives a 600px-tall window and makes every element carrying `data-index` (the rows
 * `useVirtualRows` measures) `rowHeight` px tall, the way a browser would. The caller's
 * afterEach must `vi.unstubAllGlobals()` and `vi.restoreAllMocks()`.
 */
export function stubVirtualLayout(rowHeight = 30) {
  class StubResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal("ResizeObserver", StubResizeObserver);
  vi.stubGlobal("innerHeight", 600);
  vi.stubGlobal("innerWidth", 1000);
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockImplementation(function (this: HTMLElement) {
    return this.hasAttribute("data-index") ? rowHeight : 0;
  });
}
