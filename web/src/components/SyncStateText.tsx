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
        {t("catalog.sync.neverSynced")}
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
