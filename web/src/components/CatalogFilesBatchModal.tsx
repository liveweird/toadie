import { useId } from "react";
import { Alert, Badge, Button, Group, Modal, Stack, Table, Text } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { ImportFileResult } from "../api/catalogFiles";
import { batchCause, CatalogBatchError, runCatalogFileBatches, type CatalogBatch } from "../utils/catalogBatches";
import { MAX_DIALOG_ROWS, problemsFirst } from "../utils/rowOrder";
import { loadErrorMessage } from "../utils/saveError";
import CatalogFileNameLink from "./CatalogFileNameLink";
import DataTable from "./DataTable";
import KindBadge from "./KindBadge";
import LoadingBlock from "./LoadingBlock";

const STATUS_COLOR: Record<ImportFileResult["status"], string> = {
  CREATED: "teal",
  UPDATED: "teal",
  // Stored despite waived soft findings — the catalog import's orange caution.
  CREATED_WITH_FINDINGS: "orange",
  UPDATED_WITH_FINDINGS: "orange",
  UNCHANGED: "gray",
  CONFLICT: "red",
  INVALID: "red",
  ERROR: "red",
  // The conversion's `expected` revision no longer held: nothing was stored (not in CLEAN/STORED).
  STALE: "red",
};

const CLEAN: ReadonlySet<ImportFileResult["status"]> = new Set(["CREATED", "UPDATED", "UNCHANGED"]);

const STORED: ReadonlySet<ImportFileResult["status"]> = new Set([
  "CREATED",
  "CREATED_WITH_FINDINGS",
  "UPDATED",
  "UPDATED_WITH_FINDINGS",
]);

/**
 * The confirm step of "Create / update catalog files". Opening the dialog converts ONCE (`convert`
 * re-runs the Port -> Backstage conversion, so the batch is merged against the CURRENT stored
 * catalog) and dry-runs it (`/files/import/check` with `replaceExisting`, the predicted rows);
 * Confirm imports exactly that previewed batch — it never converts again. Each file carries the
 * revision of the stored file it was converted over (`expected`), so one edited since the preview
 * comes back as a red "Changed meanwhile" `STALE` row, untouched; the user converts again
 * (reopens the dialog) to import it. Mount it only while the dialog should be open. The check is
 * keyed OUTSIDE the `["catalogFiles"]` prefix with no caching (recomputed per open, never
 * refreshed by an unrelated mutation); the real import invalidates the whole prefix, even after
 * a partial failure. A failed chunk keeps the rows of the chunks that did store, with a
 * "stopped after N of M files" notice, and the predictions are no longer shown.
 */
export default function CatalogFilesBatchModal({
  convert,
  onClose,
}: {
  convert: () => Promise<CatalogBatch>;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const openId = useId();

  const check = useQuery({
    queryKey: ["catalogFilesBatchCheck", openId],
    queryFn: async () => {
      const batch = await convert();
      return { batch, rows: await runCatalogFileBatches("check", batch) };
    },
    staleTime: 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const run = useMutation({
    // Imports the PREVIEWED batch: no second conversion (the guard is the batch's own `expected`).
    mutationFn: (previewed: CatalogBatch) => runCatalogFileBatches("import", previewed),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["catalogFiles"] }),
  });

  const done = run.isSuccess;
  const stopped = run.error instanceof CatalogBatchError ? run.error : null;
  // Once the real run happened (or failed), the predictions are history and are not shown.
  const rows = done ? run.data : run.isError ? (stopped?.rows ?? []) : check.data?.rows;
  const busy = run.isPending;

  return (
    <Modal
      opened
      onClose={busy ? () => undefined : onClose}
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      title={t("conversion.export.createModal.title")}
      size="xl"
      centered
    >
      <Stack gap="md">
        {check.isError && (
          <Alert color="red" variant="light">
            {t("conversion.export.createModal.checkFailed")} {loadErrorMessage(batchCause(check.error), t)}
          </Alert>
        )}
        {(check.isPending || busy) && <LoadingBlock py="md" />}
        {!busy && rows && rows.length > 0 && (
          <>
            <Text size="sm">
              {done
                ? t("conversion.export.createModal.doneTitle")
                : run.isError
                  ? t("conversion.export.createModal.storedBeforeFailure")
                  : t("conversion.export.createModal.previewBody")}
            </Text>
            <BatchRows rows={rows} mode={done || run.isError ? "import" : "check"} />
          </>
        )}
        {run.isError && (
          <Alert color="red" variant="light" title={t("conversion.export.createModal.importFailedTitle")}>
            {stopped
              ? t("conversion.export.createModal.stoppedAfter", { sent: stopped.filesSent, total: stopped.filesTotal })
              : t("conversion.export.createModal.nothingStored")}{" "}
            {loadErrorMessage(batchCause(run.error), t)}
          </Alert>
        )}
        <Group justify="flex-end" gap="sm">
          {done || run.isError ? (
            <Button onClick={onClose}>{t("conversion.export.createModal.close")}</Button>
          ) : (
            <>
              <Button variant="default" onClick={onClose} disabled={busy}>
                {t("conversion.export.createModal.cancel")}
              </Button>
              <Button
                onClick={() => check.data && run.mutate(check.data.batch)}
                loading={busy}
                disabled={!check.isSuccess || check.data.batch.files.length === 0}
              >
                {t("conversion.export.createModal.confirm")}
              </Button>
            </>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}

function BatchRows({ rows, mode }: { rows: readonly ImportFileResult[]; mode: "check" | "import" }) {
  const { t } = useTranslation();
  const stored = rows.filter((row) => STORED.has(row.status)).length;
  // A dialog cannot window against the page scroll: show the rows that need attention first and
  // cap the tail (the summary line above still counts every row).
  const ordered = problemsFirst(rows, (row) => CLEAN.has(row.status));
  const shown = ordered.slice(0, MAX_DIALOG_ROWS);
  const count = (status: ImportFileResult["status"]) => rows.filter((row) => row.status === status).length;
  const created = count("CREATED") + count("CREATED_WITH_FINDINGS");
  const updated = count("UPDATED") + count("UPDATED_WITH_FINDINGS");
  return (
    <Stack gap="xs">
      <Text size="sm" fw={500}>
        {t(mode === "check" ? "conversion.export.createModal.checkSummary" : "conversion.export.createModal.summary", {
          stored,
          total: rows.length,
          created,
          updated,
          unchanged: count("UNCHANGED"),
        })}
      </Text>
      <Table.ScrollContainer minWidth={640}>
        <DataTable label={t("conversion.export.createModal.resultsLabel")}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t("conversion.export.createModal.column.kind")}</Table.Th>
              <Table.Th>{t("conversion.export.createModal.column.namespace")}</Table.Th>
              <Table.Th>{t("conversion.export.createModal.column.name")}</Table.Th>
              <Table.Th>{t("conversion.export.createModal.column.result")}</Table.Th>
              <Table.Th>{t("conversion.export.createModal.column.detail")}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {shown.map((row) => (
              <Table.Tr key={row.index}>
                <Table.Td>
                  <KindBadge kind={row.kind} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{row.namespace}</Text>
                </Table.Td>
                <Table.Td>
                  {mode === "import" && row.fileId != null ? (
                    <CatalogFileNameLink id={row.fileId} name={row.name} />
                  ) : (
                    <Text size="sm" fw={500}>
                      {row.name}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Badge variant="light" size="sm" color={STATUS_COLOR[row.status]} tt="none">
                    {t(
                      mode === "check"
                        ? `conversion.export.createModal.checkStatus.${row.status}`
                        : `conversion.export.createModal.status.${row.status}`,
                    )}
                  </Badge>
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
      {ordered.length > shown.length && (
        <Text size="xs" c="dimmed">
          {t("conversion.export.createModal.truncated", { shown: shown.length, total: ordered.length })}
        </Text>
      )}
    </Stack>
  );
}
