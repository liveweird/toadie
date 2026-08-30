import { useTranslation } from "react-i18next";
import { Chip, Group, Stack, Text } from "@mantine/core";
import { ERROR_CLASSES } from "../utils/errorClasses";

/**
 * The always-visible ERROR-CLASS pills on the Errors page — the CatalogKindPills recipe
 * (outside the collapsible FilterPanel, all on by default, per-view persisted, excluded
 * from the active-filter badge). A captioned row so the pills never read as entity kinds.
 * Filtering is client-side over the fetched findings — the nine shared filters are the
 * server's; with none on the table shows no rows.
 */
export default function ErrorClassPills({
  classes,
  setClasses,
}: {
  classes: string[];
  setClasses: (next: string[]) => void;
}) {
  const { t } = useTranslation();
  return (
    <Stack gap={4}>
      <Text size="sm" fw={500} component="label">
        {t("errors.classesLabel")}
      </Text>
      <Chip.Group multiple value={classes} onChange={setClasses}>
        <Group gap="xs" role="group" aria-label={t("errors.classesLabel")}>
          {ERROR_CLASSES.map((errorClass) => (
            <Chip key={errorClass} value={errorClass} size="xs">
              {t(`errors.class.${errorClass}`)}
            </Chip>
          ))}
        </Group>
      </Chip.Group>
    </Stack>
  );
}
