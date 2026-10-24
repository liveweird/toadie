import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from "@testing-library/react";
import { stubVirtualLayout } from "../test/virtualLayout";
import { useVirtualRows, VIRTUAL_ROWS_THRESHOLD } from "./useVirtualRows";

beforeEach(() => {
  // Rows are measured at 30px unless a test passes its own layout.
  stubVirtualLayout(30);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const byIndex = (index: number) => index;

function Rows({ count, estimate }: { count: number; estimate?: number }) {
  const { attachList, measureRow, items, totalSize, paddingTop, paddingBottom } = useVirtualRows(
    count,
    byIndex,
    estimate,
  );
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
    const { result } = renderHook(() => useVirtualRows(VIRTUAL_ROWS_THRESHOLD, byIndex));
    expect(result.current.enabled).toBe(false);
    expect(result.current.items).toHaveLength(VIRTUAL_ROWS_THRESHOLD);
    expect(result.current.items[41]).toEqual({ index: 41, offset: 0 });
    expect(result.current.totalSize).toBe(0);
    expect(result.current.paddingTop).toBe(0);
    expect(result.current.paddingBottom).toBe(0);
    expect(result.current.measureRow).toBeUndefined();
  });

  test("an empty list is not virtualized", () => {
    const { result } = renderHook(() => useVirtualRows(0, byIndex));
    expect(result.current.enabled).toBe(false);
    expect(result.current.items).toEqual([]);
  });

  test("above the threshold only a bounded window plus overscan renders", async () => {
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
    vi.restoreAllMocks();
    stubVirtualLayout(50);
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

  describe("row identity", () => {
    // 400 rows named r0..r399 whose height depends on the NAME (20px for even, 60px for odd), so a
    // row that changes index changes which index-keyed size would be wrong for it.
    const heightOf = (id: number) => (id % 2 === 0 ? 20 : 60);
    const names = Array.from({ length: 400 }, (_, i) => i);

    function Keyed() {
      const [ids, setIds] = useState(names);
      const { attachList, measureRow, items } = useVirtualRows(ids.length, (index) => ids[index] ?? `#${index}`, 40);
      return (
        <div ref={attachList} data-testid="list">
          <button onClick={() => setIds((current) => current.slice(1))}>drop first</button>
          {items.map((item) => (
            <div
              key={ids[item.index]}
              data-testid="row"
              data-id={ids[item.index]}
              data-offset={item.offset}
              ref={measureRow}
              data-index={item.index}
            >
              r{ids[item.index]}
            </div>
          ))}
        </div>
      );
    }

    test("rows that shift index keep their own measured height and position", async () => {
      vi.restoreAllMocks();
      stubVirtualLayout((row) => heightOf(Number(row.dataset.id)));
      render(<Keyed />);
      await waitFor(() => expect(screen.getAllByTestId("row").length).toBeGreaterThan(10));

      // Dropping the first row shifts every other row up one index while the mounted ones keep
      // their DOM elements (same React key) and are not re-measured.
      fireEvent.click(screen.getByText("drop first"));
      await waitFor(() => expect(screen.queryByText("r0")).not.toBeInTheDocument());

      // Each mounted row sits exactly below the true heights of the rows before it (rows 1..id-1),
      // wherever the shift left its cached size.
      const rows = screen.getAllByTestId("row");
      expect(rows.length).toBeGreaterThan(10);
      let expected = 0;
      for (const row of rows) {
        const id = Number(row.dataset.id);
        // Rows before the first mounted one are unmeasured: skip to the first mounted row's offset.
        if (row === rows[0]) expected = Number(row.dataset.offset);
        expect(Number(row.dataset.offset)).toBe(expected);
        expected += heightOf(id);
      }
    });
  });

  describe("scroll margin", () => {
    test("follows the list's top when content above it grows", async () => {
      let top = 100;
      vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
        const y = this.dataset.testid === "list" ? top : 0;
        return { top: y, bottom: y, left: 0, right: 0, width: 0, height: 0, x: 0, y, toJSON: () => ({}) } as DOMRect;
      });
      render(<Rows count={2000} estimate={30} />);
      await waitFor(() => expect(screen.getAllByTestId("row").length).toBeGreaterThan(0));
      const nearTop = screen.getAllByTestId("row").length;

      // A panel above the list opens: the list now starts far below the 600px viewport, so only
      // the overscan rows are needed.
      top = 3000;
      act(() => {
        window.dispatchEvent(new Event("scroll"));
      });
      await waitFor(() => expect(screen.getAllByTestId("row").length).toBeLessThan(nearTop));
      expect(screen.getAllByTestId("row").length).toBeLessThanOrEqual(14);
    });
  });
});
