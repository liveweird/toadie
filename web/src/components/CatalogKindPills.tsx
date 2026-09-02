import { useTranslation } from "react-i18next";
import { Chip, Group } from "@mantine/core";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import KindTierDot from "./KindTierDot";

/**
 * The always-visible VISIBLE-kinds pills on the Files list, Hierarchy tree, Graph canvas and
 * Errors report — deliberately OUTSIDE the collapsible FilterPanel, at the end of its header
 * row (the `trailing` slot, v1.20.0). No visible "Kind" caption: the group's `aria-label`
 * names it, and the tier-dotted chips are self-evidently kinds. Every kind starts ON; the
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
    <Chip.Group multiple value={kinds} onChange={setKinds}>
      <Group gap={6} role="group" aria-label={t("catalog.field.kind")}>
        {ENTITY_KINDS.map((kind) => (
          <Chip key={kind} value={kind} size="xs">
            <Group gap={4} wrap="nowrap" display="inline-flex" component="span">
              <KindTierDot kind={kind} />
              {kind}
            </Group>
          </Chip>
        ))}
      </Group>
    </Chip.Group>
  );
}
