import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Group, Pagination, Stack, Text, Timeline } from "@mantine/core";
import LoadingBlock from "./LoadingBlock";
import { formatDateTime } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";

/** One already-rendered history entry: the caller owns the wording, the shell owns the layout. */
export type TimelineItem = {
  key: string | number;
  /** The one localized sentence the entry leads with. */
  title: string;
  /** Dimmed body lines under the title (see `utils/eventChanges.ts`). */
  lines: string[];
  who: string;
  /** Epoch milliseconds. */
  when: number;
};

/**
 * The shared change-history shell (the Toadie counterpart of Lettuce's `EventTimeline`):
 * loading status, load-error Alert, empty note, then the newest-first Timeline with a
 * `who · when` meta line per entry and a bare Pagination past one page. The per-area
 * components keep their query and their `describeEvent` wording and pass finished items in.
 *
 * A failed load must never masquerade as an empty history, so the Alert wins over the note.
 * The optional `footer` renders last, under the timeline and its pagination (and under the
 * empty note), for an area-specific remark such as "history starts here".
 */
export default function EventTimeline({
  items,
  isLoading,
  isError,
  error,
  emptyMessage,
  page,
  pageSize,
  total,
  onPageChange,
  footer,
}: {
  items: TimelineItem[] | undefined;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  emptyMessage: string;
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  footer?: ReactNode;
}) {
  const { t, i18n } = useTranslation();

  if (isLoading) return <LoadingBlock py="sm" />;
  if (isError) {
    return (
      <Alert color="red" variant="light">
        {loadErrorMessage(error, t)}
      </Alert>
    );
  }
  if (!items || items.length === 0) {
    const empty = (
      <Text c="dimmed" size="sm">
        {emptyMessage}
      </Text>
    );
    return footer ? (
      <Stack gap="sm">
        {empty}
        {footer}
      </Stack>
    ) : (
      empty
    );
  }

  return (
    <Stack gap="sm">
      <Timeline bulletSize={12} lineWidth={2}>
        {items.map((item) => (
          <Timeline.Item key={item.key} title={item.title}>
            {item.lines.map((line, index) => (
              <Text key={`${index}:${line}`} size="sm" c="dimmed">
                {line}
              </Text>
            ))}
            <Text size="xs" c="dimmed">
              {item.who} · {formatDateTime(item.when, i18n.language)}
            </Text>
          </Timeline.Item>
        ))}
      </Timeline>
      {total > pageSize && (
        <Group justify="center">
          <Pagination
            size="sm"
            value={page}
            onChange={onPageChange}
            total={Math.ceil(total / pageSize)}
          />
        </Group>
      )}
      {footer}
    </Stack>
  );
}
