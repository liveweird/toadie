import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Badge, Button, Group, Loader, Modal, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  checkCatalogFile,
  fetchCatalogUrl,
  getCatalogFile,
  getSyncState,
  syncCatalogFile,
  type CatalogFileListItem,
  type CatalogFileRequest,
} from "../api/catalogFiles";
import { normalizeCatalogUrl, parseCatalogYaml, pickRepoDocument } from "../utils/catalogImport";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { relativeTimeAgo } from "../utils/relativeTime";
import { diffLines } from "../utils/yamlDiff";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const DIFF_COLORS = {
  removed: {
    background: "light-dark(var(--mantine-color-red-0), rgba(224, 49, 49, 0.15))",
    color: "light-dark(var(--mantine-color-red-9), var(--mantine-color-red-3))",
    prefix: "-",
  },
  added: {
    background: "light-dark(var(--mantine-color-teal-0), rgba(9, 146, 104, 0.15))",
    color: "light-dark(var(--mantine-color-teal-9), var(--mantine-color-teal-3))",
    prefix: "+",
  },
  same: { background: "transparent", color: "inherit", prefix: " " },
} as const;

/**
 * The Sync-from-repo modal (the Files list's Operations dropdown): fetches the repo copy
 * through the SSRF-guarded server fetch, shows WHICH side changed since the last sync (the
 * stored baseline attributes it) plus a line diff of the canonical YAML, and overwrites the
 * DB copy on explicit confirmation. DB→repo sync deliberately does not exist.
 */
export default function SyncCatalogFileModal({
  file,
  onClose,
}: {
  /** The row to sync; null keeps the modal closed. */
  file: CatalogFileListItem | null;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  const id = file?.id;
  const sourceUrl = file?.sourceUrl ?? null;
  const enabled = id != null && sourceUrl != null;

  const detail = useQuery({
    queryKey: ["catalogFiles", "detail", id],
    queryFn: () => getCatalogFile(id as number),
    enabled,
  });
  const syncState = useQuery({
    queryKey: ["catalogFiles", "syncState", id],
    queryFn: () => getSyncState(id as number),
    enabled,
  });
  const repoFetch = useQuery({
    queryKey: ["catalogFiles", "repoCopy", id],
    queryFn: () => fetchCatalogUrl(normalizeCatalogUrl(sourceUrl as string)),
    enabled,
    staleTime: 0,
    gcTime: 0,
  });

  // The repo side: parse the fetched text and pick this row's document.
  const repo = useMemo(() => {
    if (!repoFetch.data || !file) return null;
    const parsed = parseCatalogYaml(repoFetch.data.content);
    if (parsed.errors.length > 0 || parsed.documents.length === 0) {
      return { document: null, error: "parse" as const };
    }
    const document = pickRepoDocument(parsed.documents, file);
    return document ? { document, error: null } : { document: null, error: "noMatch" as const };
  }, [repoFetch.data, file]);

  const repoDocument = repo?.document ?? null;
  const repoFindings = useQuery({
    queryKey: ["catalogFiles", "repoFindings", id],
    queryFn: () => checkCatalogFile(repoDocument as CatalogFileRequest),
    enabled: repoDocument != null,
    staleTime: 0,
    gcTime: 0,
  });

  // All comparison runs over the canonical YAML render — one equality for diff and badges.
  const currentYaml = detail.data
    ? catalogInfoYaml({ kind: detail.data.kind, metadata: detail.data.metadata, spec: detail.data.spec })
    : null;
  const repoYaml = repoDocument ? catalogInfoYaml(repoDocument) : null;
  const baselineYaml = syncState.data?.syncedDocument
    ? catalogInfoYaml(syncState.data.syncedDocument)
    : null;

  const lastSyncedAt = syncState.data?.lastSyncedAt ?? 0;
  const inSync = currentYaml != null && repoYaml != null && currentYaml === repoYaml;
  const dbChanged =
    detail.data != null && lastSyncedAt > 0 && detail.data.updatedAt > lastSyncedAt;
  const repoChanged = repoYaml != null && baselineYaml != null && repoYaml !== baselineYaml;
  const diff =
    currentYaml != null && repoYaml != null && !inSync ? diffLines(currentYaml, repoYaml) : null;

  const loading = enabled && (detail.isLoading || syncState.isLoading || repoFetch.isLoading);
  function loadErrorText(): string | null {
    if (detail.isError) return loadErrorMessage(detail.error, t);
    if (syncState.isError) return loadErrorMessage(syncState.error, t);
    if (repoFetch.isError) {
      return saveErrorMessage(repoFetch.error, t, {
        invalid: "catalog.import.urlInvalid",
        failedStatus: "catalog.import.urlFailedStatus",
        failed: "catalog.import.urlFailedNetwork",
      });
    }
    if (repo?.error === "parse") return t("catalog.sync.parseFailed");
    if (repo?.error === "noMatch") return t("catalog.sync.noMatch");
    return null;
  }
  const loadError = loadErrorText();

  function close() {
    setSyncError(null);
    onClose();
  }

  async function onConfirm() {
    if (id == null || repoDocument == null) return;
    setSyncing(true);
    setSyncError(null);
    try {
      await syncCatalogFile(id, repoDocument);
      await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      showSuccessToast(t("catalog.toast.synced"));
      onClose();
    } catch (err) {
      setSyncError(
        saveErrorMessage(err, t, {
          notFound: "catalog.fileGone",
          invalid: "catalog.validationError",
          conflict: "catalog.conflictError",
          failedStatus: "common.error.saveFailedStatus",
          failed: "common.error.saveFailedNetwork",
        }),
      );
    } finally {
      setSyncing(false);
    }
  }

  return (
    <Modal
      opened={file !== null}
      onClose={close}
      title={t("catalog.sync.title", { name: file?.name ?? "" })}
      size="xl"
      centered
    >
      <Stack gap="sm">
        {sourceUrl != null && (
          <Text size="sm">
            {t("catalog.sync.sourceLabel")}{" "}
            <Anchor href={sourceUrl} target="_blank" rel="noreferrer" size="sm">
              {sourceUrl}
            </Anchor>
          </Text>
        )}
        <Text size="sm" c="dimmed">
          {lastSyncedAt > 0
            ? t("catalog.sync.lastSynced", { ago: relativeTimeAgo(lastSyncedAt, i18n.language) })
            : t("catalog.sync.neverSynced")}
        </Text>

        {loading && <Loader size="sm" aria-label={t("catalog.sync.loadingAria")} />}
        {loadError != null && (
          <Alert color="red" variant="light" title={t("catalog.sync.loadFailed")}>
            {loadError}
          </Alert>
        )}

        {!loading && loadError == null && repoYaml != null && currentYaml != null && (
          <>
            <Group gap="xs">
              {inSync ? (
                <Badge variant="light" color="teal">
                  {t("catalog.sync.inSync")}
                </Badge>
              ) : (
                <>
                  {/* No baseline (never synced) = sides cannot be attributed; the diff says it all. */}
                  {repoChanged && (
                    <Badge variant="light" color="orange">
                      {t("catalog.sync.changedInRepo")}
                    </Badge>
                  )}
                  {dbChanged && (
                    <Badge variant="light" color="orange">
                      {t("catalog.sync.changedInDb")}
                    </Badge>
                  )}
                </>
              )}
            </Group>

            {diff != null && (
              <Stack
                gap={0}
                style={{
                  fontFamily: "var(--mantine-font-family-monospace)",
                  fontSize: "var(--mantine-font-size-xs)",
                  maxHeight: 360,
                  overflow: "auto",
                  border: "1px solid light-dark(var(--mantine-color-gray-3), var(--mantine-color-dark-4))",
                  borderRadius: "var(--mantine-radius-sm)",
                  padding: 8,
                }}
                aria-label={t("catalog.sync.diffLabel")}
              >
                {diff.map((line, index) => (
                  <Text
                    key={index}
                    component="pre"
                    size="xs"
                    m={0}
                    style={{
                      whiteSpace: "pre-wrap",
                      backgroundColor: DIFF_COLORS[line.kind].background,
                      color: DIFF_COLORS[line.kind].color,
                      fontFamily: "inherit",
                    }}
                  >
                    {`${DIFF_COLORS[line.kind].prefix} ${line.text}`}
                  </Text>
                ))}
              </Stack>
            )}

            {(repoFindings.data?.findings.length ?? 0) > 0 && (
              <Alert color="orange" variant="light">
                {t("catalog.sync.findingsWarning", { count: repoFindings.data?.findings.length })}
              </Alert>
            )}

            {!inSync && (
              <Text size="sm" c="dimmed">
                {t("catalog.sync.overwriteWarning")}
              </Text>
            )}
          </>
        )}

        {syncError != null && (
          <Alert color="red" variant="light" title={t("catalog.sync.failed")}>
            {syncError}
          </Alert>
        )}

        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={close} disabled={syncing}>
            {t("common.action.cancel")}
          </Button>
          <Button
            color="orange"
            onClick={() => void onConfirm()}
            loading={syncing}
            disabled={repoDocument == null || inSync}
          >
            {t("catalog.sync.confirm")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
