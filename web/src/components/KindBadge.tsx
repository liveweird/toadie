import { type CSSProperties } from "react";
import { Badge } from "@mantine/core";
import KindTierDot from "./KindTierDot";
import classes from "../theme.module.css";

/**
 * THE kind surface (v1.19.0): one quiet neutral tint on every page that shows a kind — the
 * Files list, the Hierarchy tree, the Errors report, the import results, the registries'
 * applies-to cells, and the Graph node face. The tier dot is the only coloured element; the
 * badge itself never competes with the brand accent or the status colours. `MISSING` (a
 * dangling reference on the Hierarchy/Graph) is the one variant: a red outline, no fill.
 *
 * The kind stays a bare text node beside the aria-hidden dot — tests and e2e locate badges
 * by `getByText(kind, { exact: true })`.
 */
export default function KindBadge({
  kind,
  status,
  size = "sm",
  style,
}: {
  kind: string;
  status?: "STORED" | "MISSING";
  size?: "xs" | "sm";
  style?: CSSProperties;
}) {
  return (
    <Badge
      variant="light"
      color="gray"
      size={size}
      className={classes.kindBadge}
      style={style}
      data-status={status}
      leftSection={<KindTierDot kind={kind} />}
    >
      {kind}
    </Badge>
  );
}
