import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { cleanup, render, renderHook, screen, waitFor } from "@testing-library/react";
import { useVirtualRows, VIRTUAL_ROWS_THRESHOLD } from "./useVirtualRows";

beforeEach(() => {
  class StubResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal("ResizeObserver", StubResizeObserver);
  vi.stubGlobal("innerHeight", 600);
  vi.stubGlobal("innerWidth", 1000);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** happy-dom lays nothing out: every element measures 0px, which would let the virtualizer
 * "fit" an unbounded number of rows. The virtualizer reads `offsetHeight`; give rows a height
 * the way a browser would. */
function stubRowHeight(height: number) {
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockImplementation(function (this: HTMLElement) {
    return this.dataset.testid === "row" ? height : 0;
  });
}

function Rows({ count, estimate }: { count: number; estimate?: number }) {
  const { attachList, measureRow, items, totalSize, paddingTop, paddingBottom } = useVirtualRows(count, estimate);
  return (
    <div ref={attachList} data-testid="list" data-total={totalSize} data-top={paddingTop} data-bottom={paddingBottom}>
      {items.map((item) => (
        <div key={item.index} data-testid="row" ref={measureRow} data-index={item.index}>
          row {item.index}
        </div>
      ))}
    </div>
  );
}

describe("useVirtualRows", () => {
  test("at or below the threshold every row renders and the machinery stays off", () => {
    const { result } = renderHook(() => useVirtualRows(VIRTUAL_ROWS_THRESHOLD));
    expect(result.current.enabled).toBe(false);
    expect(result.current.items).toHaveLength(VIRTUAL_ROWS_THRESHOLD);
    expect(result.current.items[41]).toEqual({ index: 41, offset: 0 });
    expect(result.current.totalSize).toBe(0);
    expect(result.current.paddingTop).toBe(0);
    expect(result.current.paddingBottom).toBe(0);
    expect(result.current.measureRow).toBeUndefined();
  });

  test("an empty list is not virtualized", () => {
    const { result } = renderHook(() => useVirtualRows(0));
    expect(result.current.enabled).toBe(false);
    expect(result.current.items).toEqual([]);
  });

  test("above the threshold only a bounded window plus overscan renders", async () => {
    stubRowHeight(30);
    render(<Rows count={2000} estimate={30} />);
    await waitFor(() => expect(screen.getAllByTestId("row").length).toBeGreaterThan(0));
    const rows = screen.getAllByTestId("row");
    // 600px / 30px = 20 visible + 12 overscan below, nothing above the first row.
    expect(rows.length).toBeLessThan(60);
    expect(rows[0]).toHaveTextContent("row 0");
    const list = screen.getByTestId("list");
    expect(list.dataset.total).toBe(String(2000 * 30));
    expect(list.dataset.top).toBe("0");
    // The spacer below stands in for every row not rendered.
    expect(Number(list.dataset.bottom)).toBe(2000 * 30 - rows.length * 30);
  });

  test("measured heights replace the estimate", async () => {
    stubRowHeight(50);
    render(<Rows count={400} estimate={30} />);
    await waitFor(() => expect(screen.getAllByTestId("row").length).toBeGreaterThan(0));
    // Rows that were mounted measured 50px (a 20px gain each over the estimate); the rest keep
    // the 30px estimate — so the total sits strictly between the all-estimate and all-measured sums.
    await waitFor(() => {
      const total = Number(screen.getByTestId("list").dataset.total);
      expect(total).toBeGreaterThan(400 * 30);
      expect(total).toBeLessThan(400 * 50);
      expect((total - 400 * 30) % 20).toBe(0);
    });
  });
});
