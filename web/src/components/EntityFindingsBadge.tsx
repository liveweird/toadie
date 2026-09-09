import { Badge, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { EntityFinding } from "../api/entities";

/**
 * The stale-entity marker: an orange count badge (the app's soft-finding colour — the same
 * orange as Save-anyway and the catalog's local-changes badge). Renders nothing for an entity
 * with zero findings — most rows. Takes either the Entities list's full finding array (a
 * tooltip lists each finding's field and code) or the Entity graph's bare COUNT (v1.25.0 —
 * `GET /api/v1/entities/graph` sends only a number per node, no per-finding detail).
 */
export default function EntityFindingsBadge({ findings }: { findings: readonly EntityFinding[] | number }) {
  const { t } = useTranslation();
  const count = typeof findings === "number" ? findings : findings.length;
  if (count === 0) return null;
  const badge = (
    <Badge color="orange" variant="light">
      {t("entities.findings.count", { count })}
    </Badge>
  );
  if (typeof findings === "number") return badge;
  const list = findings.map((f) => `${f.field}: ${f.code}`).join(", ");
  return (
    <Tooltip label={list} multiline maw={320}>
      {badge}
    </Tooltip>
  );
}
