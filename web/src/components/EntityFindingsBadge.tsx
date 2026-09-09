import { Badge, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { EntityFinding } from "../api/entities";

/**
 * The stale-entity marker: an orange count badge (the app's soft-finding colour — the same
 * orange as Save-anyway and the catalog's local-changes badge) with a tooltip listing each
 * finding's field and code. Renders nothing for an entity with zero findings — most rows.
 */
export default function EntityFindingsBadge({ findings }: { findings: readonly EntityFinding[] }) {
  const { t } = useTranslation();
  if (findings.length === 0) return null;
  const list = findings.map((f) => `${f.field}: ${f.code}`).join(", ");
  return (
    <Tooltip label={list} multiline maw={320}>
      <Badge color="orange" variant="light">
        {t("entities.findings.count", { count: findings.length })}
      </Badge>
    </Tooltip>
  );
}
