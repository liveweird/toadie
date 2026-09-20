import { Badge, Group, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { hasLocalChanges } from "../utils/syncComparison";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";

/** Everything the sync state is derived from — both the list item and the detail carry it. */
export type SyncStateSource = {
  sourceUrl: string | null;
  lastSyncedAt: number;
  updatedAt: number;
};

/**
 * Narrows a catalog file, entity, or (2.10.0) blueprint row to the shared `SyncStateSource`
 * shape — the ONE narrowing the Files/Entities/Blueprints list's Last-sync column and every
 * editor's header share (extracted from `utils/entitySync.ts#entitySyncSource`, since a
 * blueprint row needs the identical narrowing one level up).
 */
// eslint-disable-next-line react-refresh/only-export-components -- a tiny pure narrowing shared by every caller of this component (Files/Entities/Blueprints list cells and editor headers); it belongs beside the shape it narrows, not in a same-purpose-only file of its own.
export function syncStateSource(row: { sourceUrl?: string; lastSyncedAt: number; updatedAt: number }): SyncStateSource {
  return { sourceUrl: row.sourceUrl ?? null, lastSyncedAt: row.lastSyncedAt, updatedAt: row.updatedAt };
}

/**
 * One file's sync state, shared by the Files list's Last-sync column and the editor: no
 * reference → dimmed "No source" (also a standing Errors-report finding), reference but
 * never synced → "Never synced", synced → relative time (absolute timestamp in the title)
 * plus an orange "local changes" marker when the DB moved since (`updatedAt > lastSyncedAt`
 * — a sync stamps both equal).
 */
export default function SyncStateText({ file }: { file: SyncStateSource }) {
  const { t, i18n } = useTranslation();
  if (file.sourceUrl == null) {
    return (
      <Text size="sm" c="dimmed">
        {t("catalog.sync.noSource")}
      </Text>
    );
  }
  if (file.lastSyncedAt === 0) {
    return (
      <Text size="sm" c="dimmed">
        {t("sync.neverSynced")}
      </Text>
    );
  }
  return (
    <Group gap={6} wrap="nowrap">
      <Text size="sm" title={formatDateTime(file.lastSyncedAt, i18n.language)}>
        {relativeTimeAgo(file.lastSyncedAt, i18n.language)}
      </Text>
      {hasLocalChanges(file) && (
        <Badge variant="light" color="orange" size="sm">
          {t("catalog.sync.localChanges")}
        </Badge>
      )}
    </Group>
  );
}
