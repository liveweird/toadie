import { type ReactNode } from "react";
import { Badge, Button, Group } from "@mantine/core";
import { IconChevronDown } from "@tabler/icons-react";

/**
 * The disclosure button behind every collapsible toolbar section (extracted verbatim from
 * `FilterPanel.tsx` — the Filters toggle stays its first consumer, `EntityGraphToolbar`'s
 * Filters/Query toggles its second): `variant="default" size="xs"`, a leading icon, a rotating
 * chevron, `aria-expanded`, and an optional `aria-controls` naming the mounted section (omitted
 * while collapsed — the section unmounts, so pointing at a nonexistent id would be worse than
 * pointing at none). `count` renders the same circular active-count badge `FilterPanel` always
 * has; `extra` renders BEFORE the chevron for a caller that needs a different badge shape (the
 * Query toggle's "Applied · N" pill, which is text, not a count).
 */
export default function ToolbarToggle({
  icon,
  label,
  open,
  onClick,
  count,
  controlsId,
  extra,
}: {
  icon: ReactNode;
  label: string;
  open: boolean;
  onClick: () => void;
  count?: number;
  controlsId?: string;
  extra?: ReactNode;
}) {
  return (
    <Button
      variant="default"
      size="xs"
      onClick={onClick}
      aria-expanded={open}
      aria-controls={controlsId}
      leftSection={icon}
      rightSection={
        <Group gap={6} wrap="nowrap" component="span">
          {count != null && count > 0 && (
            <Badge size="sm" circle variant="filled">
              {count}
            </Badge>
          )}
          {extra}
          <IconChevronDown
            size={16}
            style={{ transform: open ? "rotate(180deg)" : "none", transition: "transform 150ms ease" }}
          />
        </Group>
      }
    >
      {label}
    </Button>
  );
}
