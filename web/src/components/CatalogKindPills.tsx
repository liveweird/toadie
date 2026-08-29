import { useTranslation } from "react-i18next";
import { Chip, Group, Stack, Text } from "@mantine/core";
import { ENTITY_KINDS } from "../utils/catalogFileForm";

/**
 * The always-visible VISIBLE-kinds pills above the Files list, Hierarchy tree, and Graph
 * canvas — deliberately OUTSIDE the collapsible FilterPanel. Every kind starts ON; the
 * per-view state persists (useCatalogFileFilterState's visibleKinds slot); with none on
 * the page shows no entities at all (the hook's noKinds short-circuit — the API cannot
 * express match-nothing).
 */
export default function CatalogKindPills({
  kinds,
  setKinds,
}: {
  kinds: string[];
  setKinds: (next: string[]) => void;
}) {
  const { t } = useTranslation();
  return (
    <Stack gap={4}>
      <Text size="sm" fw={500} component="label">
        {t("catalog.field.kind")}
      </Text>
      <Chip.Group multiple value={kinds} onChange={setKinds}>
        <Group gap="xs" role="group" aria-label={t("catalog.field.kind")}>
          {ENTITY_KINDS.map((kind) => (
            <Chip key={kind} value={kind} size="xs">
              {kind}
            </Chip>
          ))}
        </Group>
      </Chip.Group>
    </Stack>
  );
}
