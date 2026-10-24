import type { CSSProperties, ReactNode } from "react";
import { useVirtualRows } from "../hooks/useVirtualRows";
import type { TreeRow } from "../utils/treeRows";
import classes from "../theme.module.css";

/**
 * The two Hierarchy pages' tree body: a flat list of [rows] (see `utils/treeRows.ts`), each
 * wrapped in a line that carries its indentation and branch guides from its depth. Past the
 * virtualization threshold only the rows in view are mounted — positioned absolutely inside a
 * box as tall as the whole list, with measured heights — otherwise every row simply flows.
 * The row content itself is the page's: [renderRow] keeps each page's own controls and labels.
 */
export default function VirtualTreeList<N extends { id: string }>({
  rows,
  renderRow,
}: {
  rows: TreeRow<N>[];
  renderRow: (row: TreeRow<N>) => ReactNode;
}) {
  const { attachList, measureRow, enabled, items, totalSize } = useVirtualRows(rows.length, (index) => rows[index]?.key ?? `#${index}`);
  return (
    <div
      ref={attachList}
      className={enabled ? classes.virtualList : undefined}
      style={enabled ? { height: totalSize } : undefined}
    >
      {items.map(({ index, offset }) => {
        const row = rows[index];
        const style = { "--tree-depth": row.depth } as CSSProperties;
        if (enabled) style.transform = `translateY(${offset}px)`;
        return (
          <div
            key={row.key}
            ref={measureRow}
            data-index={enabled ? index : undefined}
            className={enabled ? `${classes.treeLine} ${classes.virtualRow}` : classes.treeLine}
            style={style}
          >
            {renderRow(row)}
          </div>
        );
      })}
    </div>
  );
}
