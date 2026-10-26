import { useId } from "react";
import { Alert, Button, Group, Modal, Stack, Text } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { convertForImport, importConverted, type ConvertedBatch, type PairedBatch } from "../utils/conversionBatches";
import { ImportPhaseError, importFlowError } from "../utils/conversionErrors";
import { importDisplayRows } from "../utils/conversionRows";
import { anyStored, type OntologyResultRow } from "../utils/ontologyImport";
import ConversionRowsTable from "./ConversionRowsTable";
import LoadingBlock from "./LoadingBlock";
import OntologyImportResults from "./OntologyImportResults";

/**
 * The confirm step of the Backstage catalog import. Importing here is `replaceExisting`, so it
 * never fires from a bare click: opening the dialog converts ONCE and dry-runs the entity import
 * (the conversion report and the predicted rows are shown), and Confirm imports exactly that
 * previewed batch — it never converts again. Each document travels with the write-version of the
 * Port entity it was converted over (`expected`, from the conversion rows), so one edited since
 * the preview comes back as a red "Changed meanwhile" `STALE` row, untouched; the user converts
 * again (reopens the dialog) to import it. With Merge off the converted document replaces the
 * stored entity wholesale, and the dialog says so. A chunk that fails part-way keeps the rows of
 * the chunks that stored, with how many chunks went through (`.claude/docs/conversion-mapping.md`
 * "Read-then-write guard"). Mount it only while the dialog should be open.
 */
export default function BackstageImportConfirmModal({
  convert,
  merge,
  sourceLabel,
  onClose,
}: {
  convert: () => Promise<ConvertedBatch>;
  merge: boolean;
  /** The label the converted documents carry as their source (the results' Source column). */
  sourceLabel: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const openId = useId();

  const check = useQuery({
    queryKey: ["backstageImportCheck", openId],
    queryFn: async () => importConverted(await convertForImport(convert), "check", { sourceLabel }),
    staleTime: 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const run = useMutation({
    // Imports the PREVIEWED batch: no second conversion (the guard is the batch's own `expected`).
    mutationFn: (previewed: PairedBatch) => importConverted(previewed, "import", { sourceLabel }),
    // Refresh the cached entities whenever anything stored — also after a batch that failed part-way.
    onSettled: async (data, error) => {
      const rows = data?.rows ?? (error instanceof ImportPhaseError ? error.rows : []);
      if (anyStored(rows)) await queryClient.invalidateQueries({ queryKey: ["entities"] });
    },
  });

  const busy = run.isPending;
  const failure = run.error instanceof ImportPhaseError ? run.error : null;
  const failureText = run.isError ? importFlowError(run.error, t) : null;
  const checkFailure = check.isError ? importFlowError(check.error, t) : null;

  // Once the real run happened (or failed) the predictions are history and are not shown.
  let converted: PairedBatch | undefined;
  let rows: OntologyResultRow[] | undefined;
  let mode: "check" | "import" = "check";
  if (run.isSuccess) {
    ({ converted, rows } = run.data);
    mode = "import";
  } else if (run.isError) {
    rows = failure?.rows ?? [];
    mode = "import";
  } else if (check.isSuccess) {
    ({ converted, rows } = check.data);
  }

  return (
    <Modal
      opened
      onClose={busy ? () => undefined : onClose}
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      title={t("conversion.import.confirm.title")}
      size="xl"
      centered
    >
      <Stack gap="md">
        {!merge && (
          <Alert color="orange" variant="light">
            {t("conversion.import.confirm.mergeOff")}
          </Alert>
        )}
        {(check.isPending || busy) && <LoadingBlock py="md" />}
        {checkFailure && (
          <Alert color="red" variant="light" title={checkFailure.title}>
            {checkFailure.message}
          </Alert>
        )}
        {!busy && converted && (
          <Stack gap="xs">
            <Text size="sm">
              {run.isSuccess ? t("conversion.import.confirm.done") : t("conversion.import.confirm.preview")}
            </Text>
            <ConversionRowsTable
              bounded
              rows={importDisplayRows(converted.rows)}
              label={t("conversion.import.tableLabel")}
              fromLabel={t("conversion.import.fromColumn")}
              toLabel={t("conversion.import.toColumn")}
            />
          </Stack>
        )}
        {!busy && rows && rows.length > 0 && (
          <Stack gap="xs">
            {run.isError && <Text size="sm">{t("conversion.import.confirm.storedBeforeFailure")}</Text>}
            <OntologyImportResults rows={rows} mode={mode} showSource={false} />
          </Stack>
        )}
        {failureText && (
          <Alert color="red" variant="light" title={failureText.title}>
            {failure && failure.phase === "import"
              ? t("conversion.import.confirm.stoppedAfter", { sent: failure.chunksSent, total: failure.chunksTotal })
              : t("conversion.import.confirm.nothingStored")}{" "}
            {failureText.message}
          </Alert>
        )}
        <Group justify="flex-end" gap="sm">
          {run.isSuccess || run.isError ? (
            <Button onClick={onClose}>{t("conversion.import.confirm.close")}</Button>
          ) : (
            <>
              <Button variant="default" onClick={onClose} disabled={busy}>
                {t("conversion.import.confirm.cancel")}
              </Button>
              <Button
                onClick={() => check.data && run.mutate(check.data.converted)}
                loading={busy}
                disabled={!check.isSuccess || check.data.converted.documents.length === 0}
              >
                {t("conversion.import.confirm.confirm")}
              </Button>
            </>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}
