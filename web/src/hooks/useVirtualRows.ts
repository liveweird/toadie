import { useLayoutEffect, useState } from "react";
import { useWindowVirtualizer } from "@tanstack/react-virtual";

/** A list shorter than this renders every row — windowing only pays off (and only costs
 * its measure/absolute-position machinery) once the DOM would otherwise be hundreds of rows. */
export const VIRTUAL_ROWS_THRESHOLD = 300;

/** Rows drawn beyond the viewport on each side, so a fast scroll never shows a blank edge. */
const OVERSCAN = 12;

type MeasureRow = (node: Element | null) => void;

export interface VirtualRow {
  /** Index into the caller's flat row array. */
  index: number;
  /** Distance from the TOP OF THE LIST to this row's top, in px (0 when not virtualized). */
  offset: number;
}

/**
 * Window-scrolled row virtualization behind ONE render path: the caller maps `items` onto its
 * rows exactly as it would map the whole array. Below [VIRTUAL_ROWS_THRESHOLD] `items` IS every
 * row, the paddings are 0 and `measureRow` is undefined, so small lists render byte-identically to a
 * plain `.map`. Above it, `items` is the visible window (plus overscan) and the caller either
 * pads a table's body with `paddingTop`/`paddingBottom` spacer rows (rows stay `<tr>`s in flow)
 * or positions block rows at `offset` inside a `totalSize`-tall `position: relative` box.
 *
 * Row heights are MEASURED (a row element takes `ref={measureRow}` and `data-index={index}`), because
 * rows wrap at narrow widths and in deep trees. `attachList` goes on the element that holds the
 * rows; its distance from the document top is the virtualizer's scroll margin. Destructure the
 * result at the call site: the React-compiler lint treats an object whose members are used as
 * `ref`s as a ref, and flags every other property read off it during render.
 */
export function useVirtualRows<T extends HTMLElement = HTMLDivElement>(count: number, estimateSize = 32) {
  // A callback ref into state (not a ref object): the element mounting is what (re)starts the
  // scroll-margin measurement, and an object holding a ref cannot be read during render.
  const [listElement, attachList] = useState<T | null>(null);
  const [scrollMargin, setScrollMargin] = useState(0);
  const enabled = count > VIRTUAL_ROWS_THRESHOLD;

  const virtualizer = useWindowVirtualizer({
    count,
    estimateSize: () => estimateSize,
    overscan: OVERSCAN,
    scrollMargin,
    enabled,
  });

  // The list's top moves when the toolbar above grows (filters, query, visibility sections),
  // which resizes the page body — re-measure then, not on every scroll.
  useLayoutEffect(() => {
    if (!enabled || !listElement) return undefined;
    const measure = () => {
      const top = Math.round(listElement.getBoundingClientRect().top + window.scrollY);
      setScrollMargin((current) => (current === top ? current : top));
    };
    measure();
    if (typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(document.body);
    return () => observer.disconnect();
  }, [enabled, listElement]);

  if (!enabled) {
    return {
      enabled,
      attachList,
      items: Array.from({ length: count }, (_, index): VirtualRow => ({ index, offset: 0 })),
      totalSize: 0,
      paddingTop: 0,
      paddingBottom: 0,
      measureRow: undefined as MeasureRow | undefined,
    };
  }

  const virtualItems = virtualizer.getVirtualItems();
  const totalSize = virtualizer.getTotalSize();
  const items = virtualItems.map((item): VirtualRow => ({ index: item.index, offset: item.start - scrollMargin }));
  const last = virtualItems.at(-1);
  return {
    enabled,
    attachList,
    items,
    totalSize,
    paddingTop: items[0]?.offset ?? 0,
    paddingBottom: last ? Math.max(0, totalSize - (last.end - scrollMargin)) : 0,
    measureRow: virtualizer.measureElement as MeasureRow | undefined,
  };
}
