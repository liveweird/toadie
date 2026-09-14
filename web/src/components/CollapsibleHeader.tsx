import { type ReactNode } from "react";
import { Group, Paper, Stack } from "@mantine/core";
import PageHeader from "./PageHeader";
import ToolbarToggle from "./ToolbarToggle";

/**
 * One collapsible section behind a title-row `ToolbarToggle`. `frame: "plain"` renders a bare
 * `Group` (the visibility pills — nothing to border, they're chips already); `frame: "paper"`
 * keeps the bordered `Paper` drawer (Filters/Query).
 */
export interface HeaderSection {
  id: string;
  icon: ReactNode;
  label: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  count?: number;
  extra?: ReactNode;
  frame: "paper" | "plain";
  content: ReactNode;
}

/**
 * The shared collapsible-header shape (2.4.2): one `ToolbarToggle` per section on the title row
 * (`PageHeader`'s `actions`), with the OPEN sections rendered underneath, in the SAME order, as
 * `PageHeader`'s `toolbar` — so a first visit with every section collapsed shows exactly the
 * title row (`toolbar` is `undefined` while nothing is open, keeping `PageHeader` free of an
 * empty second row). `EntityGraphToolbar` (Filters/Visibility/Query) and `CatalogToolbar`'s
 * header mode (Filters/Visibility, Graph and Hierarchy) are its two composers; each owns its
 * own persisted open state (`useStoredState`) and merely describes its sections here — this
 * component itself is stateless.
 */
export default function CollapsibleHeader({
  title,
  sections,
  aside,
  children,
}: {
  title: string;
  sections: HeaderSection[];
  /** Rendered right after the toggles, before `children` — the LensPicker's slot. */
  aside?: ReactNode;
  /** The view's own secondary controls (layout controls, expand/collapse, the hierarchy picker…). */
  children?: ReactNode;
}) {
  const openSections = sections.filter((section) => section.open);
  return (
    <PageHeader
      title={title}
      actions={
        <Group gap="xs" wrap="wrap" align="center">
          {sections.map((section) => (
            <ToolbarToggle
              key={section.id}
              icon={section.icon}
              label={section.label}
              open={section.open}
              onClick={() => section.onOpenChange(!section.open)}
              count={section.count}
              extra={section.extra}
              controlsId={section.open ? section.id : undefined}
            />
          ))}
          {aside}
          {children}
        </Group>
      }
      toolbar={
        openSections.length > 0 ? (
          <Stack gap="sm">
            {openSections.map((section) =>
              section.frame === "plain" ? (
                <Group key={section.id} id={section.id} gap="md" wrap="wrap" align="center">
                  {section.content}
                </Group>
              ) : (
                <Paper
                  key={section.id}
                  id={section.id}
                  withBorder
                  radius="md"
                  p="sm"
                  bg="var(--mantine-color-default-hover)"
                >
                  {section.content}
                </Paper>
              ),
            )}
          </Stack>
        ) : undefined
      }
    />
  );
}
