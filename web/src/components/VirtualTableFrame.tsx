import type { ReactNode } from "react";
import { Table } from "@mantine/core";
import classes from "../theme.module.css";

/**
 * The Errors tables' frame. A table that renders everything keeps Mantine's
 * `Table.ScrollContainer` (horizontal overflow stays contained on narrow screens). A WINDOWED
 * table cannot: a sticky `thead` sticks to its nearest overflow ancestor, and the scroll
 * container is not the page, so the header would never stick. Windowed, the table sits in a
 * plain div whose own horizontal scroller (`.virtualTableFrame`) exists only below the
 * breakpoint where the canvas is too narrow for [minWidth] — wide screens, where the sticky
 * header matters, scroll the window itself.
 */
export default function VirtualTableFrame({
  virtualized,
  minWidth,
  children,
}: {
  virtualized: boolean;
  minWidth: number;
  children: ReactNode;
}) {
  if (virtualized) return <div className={classes.virtualTableFrame}>{children}</div>;
  return <Table.ScrollContainer minWidth={minWidth}>{children}</Table.ScrollContainer>;
}
