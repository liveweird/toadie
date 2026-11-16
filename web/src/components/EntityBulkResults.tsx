import { Badge, Group, Stack, Table, Text } from "@mantine/core";
import type { ParseKeys } from "i18next";
import { useTranslation } from "react-i18next";
import type { EntityBulkResult, EntityBulkStatus } from "../api/entities";
import { bulkCounts, formatBulkValue } from "../utils/entityBulk";
import DataTable from "./DataTable";

// The app-wide colour vocabulary: teal = stored (or would be), gray = nothing to do, red = nothing stored.
const STATUS_COLOR: Record<EntityBulkStatus, string> = {
  UPDATED: "teal",
  UNCHANGED: "gray",
  INVALID: "red",
  STALE: "red",
  ERROR: "red",
};

// Literal keys (unusedKeys.test.ts): the dry-run reads as predictions, the real run as outcomes.
const STATUS_LABEL: Record<"check" | "apply", Record<EntityBulkStatus, ParseKeys>> = {
  check: {
    UPDATED: "entityBulk.checkStatus.UPDATED",
    UNCHANGED: "entityBulk.checkStatus.UNCHANGED",
    INVALID: "entityBulk.checkStatus.INVALID",
    STALE: "entityBulk.checkStatus.STALE",
    ERROR: "entityBulk.checkStatus.ERROR",
  },
  apply: {
    UPDATED: "entityBulk.status.UPDATED",
    UNCHANGED: "entityBulk.status.UNCHANGED",
    INVALID: "entityBulk.status.INVALID",
    STALE: "entityBulk.status.STALE",
    ERROR: "entityBulk.status.ERROR",
  },
};

function ValueText({ value }: { value: unknown }) {
  const text = formatBulkValue(value);
  return text === null || text === "" ? (
    <Text span size="xs" c="dimmed">
      —
    </Text>
  ) : (
    <Text span size="xs" title={text}>
      {text}
    </Text>
  );
}

function ChangeCell({ row }: { row: EntityBulkResult }) {
  const { t } = useTranslation();
  if (row.valuesOmitted) {
    return (
      <Text size="xs" c="dimmed">
        {t("entityBulk.valuesOmitted")}
      </Text>
    );
  }
  // A row that never got as far as a diff (stale, gone) carries neither side.
  if (row.before === undefined && row.after === undefined && row.status !== "UPDATED") return null;
  return (
    <Text size="xs" lineClamp={3}>
      <ValueText value={row.before} />
      {" → "}
      <ValueText value={row.after} />
    </Text>
  );
}

/**
 * The bulk-change modal's two result tables (2.38.0) — the `OntologyImportResults` shape for
 * one operation on one field: a summary line, then one row per target in request order with
 * the status Badge, the field's before → after, and the server's message. `mode` picks the
 * wording: `check` (the dry-run preview, "Will update …") or `apply` (the outcome, "Updated").
 * Row identity is plain text on purpose — the modal must not navigate away mid-flow; the tree
 * behind it carries the editor links.
 */
export default function EntityBulkResults({
  rows,
  mode,
}: {
  rows: readonly EntityBulkResult[];
  mode: "check" | "apply";
}) {
  const { t } = useTranslation();
  const counts = bulkCounts(rows);
  const failed = counts.INVALID + counts.STALE + counts.ERROR;
  return (
    <Stack gap="xs">
      <Text size="sm" fw={500}>
        {t(mode === "check" ? "entityBulk.summary.check" : "entityBulk.summary.apply", {
          updated: counts.UPDATED,
          unchanged: counts.UNCHANGED,
          failed,
        })}
      </Text>
      <Table.ScrollContainer minWidth={640}>
        <DataTable label={t("entityBulk.resultsTableLabel")}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t("entityBulk.column.entity")}</Table.Th>
              <Table.Th>{t("entityBulk.column.result")}</Table.Th>
              <Table.Th>{t("entityBulk.column.change")}</Table.Th>
              <Table.Th>{t("entityBulk.column.detail")}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((row) => (
              <Table.Tr key={row.id}>
                <Table.Td>
                  <Group gap={6} wrap="wrap">
                    <Text size="sm" ff="monospace">
                      {row.identifier ?? `#${row.id}`}
                    </Text>
                    {row.title && (
                      <Text size="xs" c="dimmed">
                        {row.title}
                      </Text>
                    )}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Badge variant="light" size="sm" color={STATUS_COLOR[row.status]}>
                    {t(STATUS_LABEL[mode][row.status])}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <ChangeCell row={row} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c="dimmed">
                    {row.message ?? ""}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </DataTable>
      </Table.ScrollContainer>
    </Stack>
  );
}
