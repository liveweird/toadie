import { type ReactNode } from "react";
import { Group, Text } from "@mantine/core";

/**
 * A visibly captioned chip row (2.4.1) — the Port canvases' twin of `CatalogKindPills`' bare
 * `role="group"` (which relies on `aria-label` alone, since the Backstage kind pills sit beside
 * a whole panel of OTHER controls that already say what they are): `BlueprintPills` and the
 * Entity graph's Relations `Chip.Group` both need a caption because they are now the FIRST thing
 * on the canvas's second row, with nothing else to say "these are blueprints"/"these are
 * relations". `role="group"` + `aria-label={label}` keeps the accessible name identical to the
 * old caption-less groups (`getByRole("group", { name: "Blueprints" | "Relations" })` still
 * resolves); the dimmed `Text` is the VISIBLE caption sighted users get that a screen reader
 * would otherwise only hear as the group's name.
 */
export default function CaptionedChipGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group gap={6} role="group" aria-label={label} wrap="wrap" align="center">
      <Text size="xs" fw={500} c="dimmed">
        {label}
      </Text>
      {children}
    </Group>
  );
}
