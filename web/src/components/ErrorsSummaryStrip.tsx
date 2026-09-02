import { useTranslation } from "react-i18next";
import { Chip, Group, Paper, Text } from "@mantine/core";
import type { ErrorsReport } from "../api/catalogFiles";
import { ERROR_CLASSES, type ErrorClass } from "../utils/errorClasses";
import { countByClass } from "../utils/errorGroups";
import classes from "../theme.module.css";

/**
 * The Errors page's summary strip (v1.20.0): three stat tiles — files checked, references
 * checked, errors shown — and, pushed right, the error-CLASS chips with a per-class count
 * from the UNFILTERED report. The count is `aria-hidden`, so each chip's accessible name
 * stays the bare class ("References") that tests and e2e locate. Filtering stays
 * client-side over the fetched findings (the nine shared filters are the server's); with no
 * class on the table shows no rows.
 */
export default function ErrorsSummaryStrip({
  report,
  classes: selected,
  setClasses,
  shownErrors,
}: {
  report: ErrorsReport | undefined;
  classes: string[];
  setClasses: (next: string[]) => void;
  shownErrors: number;
}) {
  const { t } = useTranslation();
  const counts = countByClass(report?.findings ?? []);
  const tile = (value: number | string, label: string, color?: string) => (
    <Paper withBorder radius="md" px="md" py={6} data-tile={label}>
      <Text size="lg" fw={700} lh={1.2} c={color} style={{ fontVariantNumeric: "tabular-nums" }}>
        {value}
      </Text>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
    </Paper>
  );
  return (
    <Group gap="sm" wrap="wrap" align="stretch" style={{ width: "100%" }}>
      {tile(report?.checkedFiles ?? "—", t("errors.summary.filesLabel"))}
      {tile(report?.checkedReferences ?? "—", t("errors.summary.referencesLabel"))}
      {tile(
        report ? shownErrors : "—",
        t("errors.summary.errorsLabel"),
        report ? (shownErrors > 0 ? "red" : "teal") : undefined,
      )}
      <Chip.Group multiple value={selected} onChange={setClasses}>
        <Group gap={6} role="group" aria-label={t("errors.classesLabel")} ml="auto" align="center">
          {ERROR_CLASSES.map((errorClass: ErrorClass) => (
            <Chip key={errorClass} value={errorClass} size="xs">
              <span>{t(`errors.class.${errorClass}`)}</span>
              <span aria-hidden="true" className={classes.chipCount} data-zero={counts[errorClass] === 0 || undefined}>
                {counts[errorClass]}
              </span>
            </Chip>
          ))}
        </Group>
      </Chip.Group>
    </Group>
  );
}
