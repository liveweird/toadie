import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Table } from "@mantine/core";

/**
 * A telemetry table that can outgrow the 420 px panel (wider than it, or capped by `maxHeight`): a native scroller that is itself a
 * focusable, labelled group, so the keyboard can reach and scroll it (axe `scrollable-region-focusable`, the `YamlDiffView` scroll
 * region idiom) and assistive technology names it. `label` is the table's own accessible name; the region is named after it ("Scrollable: <label>"), never the same string, so the two are told apart.
 */
export default function TelemetryScrollRegion({
  label,
  minWidth,
  maxHeight,
  children,
}: {
  label: string;
  minWidth: number;
  maxHeight?: number;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Table.ScrollContainer type="native" minWidth={minWidth} maxHeight={maxHeight} tabIndex={0} role="group" aria-label={t("telemetry.scrollRegion", { name: label })}>
      {children}
    </Table.ScrollContainer>
  );
}
