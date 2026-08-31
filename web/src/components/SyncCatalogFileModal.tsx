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
} from "../api/catalogFiles";
import YamlDiffView from "./YamlDiffView";
import { normalizeCatalogUrl, parseCatalogYaml, pickRepoDocument } from "../utils/catalogImport";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { relativeTimeAgo } from "../utils/relativeTime";
import { compareSyncSides } from "../utils/syncComparison";
import {
  CATALOG_SAVE_ERROR_KEYS,
  FETCH_URL_ERROR_KEYS,
  loadErrorMessage,
  saveErrorMessage,
} from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * Everything the modal reads about the file — the structural shape, not the list DTO, so the
 * editor can open it with a `CatalogFileResponse` too.
 */
export type SyncTarget = {
  id: number;
  kind: string;
  name: string;
  namespace: string;
  sourceUrl: string | null;
  updatedAt: number;
  lastSyncedAt: number;
};

/**
 * The Sync-from-repo modal (the Files list's Operations dropdown): fetches the repo copy
 * through the SSRF-guarded server fetch, shows WHICH side changed since the last sync (the
 * stored baseline attributes it) plus a line diff of the canonical YAML, and overwrites the
 * stored copy on explicit confirmation. DB→repo sync deliberately does not exist.
 *
 * Shell + body split: the body mounts only with a non-null row, so its query functions
 * narrow naturally (no `enabled`-laundering casts), while the shell keeps the one Modal
 * mounted for the open/close transition.
 */
export default function SyncCatalogFileModal({
  file,
  onClose,
  onCompleted,
}: {
  /** The file to sync; null keeps the modal closed. */
  file: SyncTarget | null;
  onClose: () => void;
  /** Fired only after a SUCCESSFUL sync — see OverwriteWithYamlModal for the rationale. */
  onCompleted?: () => void;
}) {
  const { t } = useTranslation();
  const [syncing, setSyncing] = useState(false);
  return (
    <Modal
      opened={file !== null}
      // Esc/overlay/the X follow the busy guard too (the ConfirmActionModal idiom) — a
      // dismissal mid-POST would unmount the error the failure is about to render.
      onClose={() => {
        if (!syncing) onClose();
      }}
      title={t("catalog.sync.title", { name: file?.name ?? "" })}
      size="xl"
      centered
    >
      {file !== null && (
        <SyncModalBody
          file={file}
          syncing={syncing}
          onSyncingChange={setSyncing}
          onClose={onClose}
          onCompleted={onCompleted}
        />
      )}
    </Modal>
  );
}

function SyncModalBody({
  file,
  syncing,
  onSyncingChange,
  onClose,
  onCompleted,
}: {
  file: SyncTarget;
  syncing: boolean;
  onSyncingChange: (syncing: boolean) => void;
  onClose: () => void;
  onCompleted?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [syncError, setSyncError] = useState<string | null>(null);

  const { id, sourceUrl } = file;

  const detail = useQuery({
    queryKey: ["catalogFiles", "detail", id],
    queryFn: () => getCatalogFile(id),
  });
  const syncState = useQuery({
    queryKey: ["catalogFiles", "syncState", id],
    queryFn: () => getSyncState(id),
  });
  // Keyed OUTSIDE the ["catalogFiles"] prefix on purpose: the post-sync list invalidation
  // must never re-trigger this server-side outbound fetch (or the findings check below).
  const repoFetch = useQuery({
    queryKey: ["repoCopy", id],
    queryFn: () => fetchCatalogUrl(normalizeCatalogUrl(sourceUrl ?? "")),
    enabled: sourceUrl != null,
    staleTime: 0,
    gcTime: 0,
  });

  // The repo side: parse the fetched text and pick this row's document.
  const repo = useMemo(() => {
    if (!repoFetch.data) return null;
    const parsed = parseCatalogYaml(repoFetch.data.content);
    if (parsed.errors.length > 0 || parsed.documents.length === 0) {
      return { document: null, error: "parse" as const };
    }
    const document = pickRepoDocument(parsed.documents, file);
    return document ? { document, error: null } : { document: null, error: "noMatch" as const };
  }, [repoFetch.data, file]);

  const repoDocument = repo?.document ?? null;
  const repoFindings = useQuery({
    queryKey: ["repoFindings", id],
    queryFn: () => {
      // `enabled` gates but does not narrow — guard honestly instead of casting.
      if (repoDocument == null) throw new Error("repo findings queried without a repo document");
      return checkCatalogFile(repoDocument);
    },
    enabled: repoDocument != null,
    staleTime: 0,
    gcTime: 0,
  });

  // All comparison runs over the canonical YAML render — one equality for diff and badges
  // (the pure half lives in utils/syncComparison.ts).
  const currentYaml = detail.data
    ? catalogInfoYaml({ kind: detail.data.kind, metadata: detail.data.metadata, spec: detail.data.spec })
    : null;
  const lastSyncedAt = syncState.data?.lastSyncedAt ?? 0;
  const { inSync, dbChanged, repoChanged, diff } = compareSyncSides({
    currentYaml,
    repoYaml: repoDocument ? catalogInfoYaml(repoDocument) : null,
    baselineYaml: syncState.data?.syncedDocument ? catalogInfoYaml(syncState.data.syncedDocument) : null,
    updatedAt: detail.data?.updatedAt ?? null,
    lastSyncedAt,
  });

  const loading = detail.isLoading || syncState.isLoading || repoFetch.isLoading;
  function loadErrorText(): string | null {
    if (detail.isError) return loadErrorMessage(detail.error, t);
    if (syncState.isError) return loadErrorMessage(syncState.error, t);
    if (repoFetch.isError) return saveErrorMessage(repoFetch.error, t, FETCH_URL_ERROR_KEYS);
    if (repo?.error === "parse") return t("catalog.sync.parseFailed");
    if (repo?.error === "noMatch") return t("catalog.sync.noMatch");
    return null;
  }
  const loadError = loadErrorText();

  async function onConfirm() {
    if (repoDocument == null) return;
    onSyncingChange(true);
    setSyncError(null);
    try {
      await syncCatalogFile(id, repoDocument);
      showSuccessToast(t("catalog.toast.synced"));
      onSyncingChange(false);
      onClose();
      // Refresh the list AFTER closing — nothing blocks the toast, and the modal's own
      // repo queries sit outside this prefix, so no outbound re-fetch fires.
      void queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      onCompleted?.();
    } catch (err) {
      onSyncingChange(false);
      setSyncError(saveErrorMessage(err, t, CATALOG_SAVE_ERROR_KEYS));
    }
  }

  return (
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

      {!loading && loadError == null && currentYaml != null && repoDocument != null && (
        <>
          <Group gap="xs">
            {inSync ? (
              <Badge variant="light" color="teal" size="sm">
                {t("catalog.sync.inSync")}
              </Badge>
            ) : (
              <>
                {/* No baseline (never synced) = sides cannot be attributed; the diff says it all. */}
                {repoChanged && (
                  <Badge variant="light" color="orange" size="sm">
                    {t("catalog.sync.changedInRepo")}
                  </Badge>
                )}
                {dbChanged && (
                  <Badge variant="light" color="orange" size="sm">
                    {t("catalog.sync.changedInDb")}
                  </Badge>
                )}
              </>
            )}
          </Group>

          {diff != null && <YamlDiffView diff={diff} label={t("catalog.sync.diffLabel")} />}

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
        <Button variant="default" onClick={onClose} disabled={syncing} data-autofocus>
          {t("common.action.cancel")}
        </Button>
        <Button
          color="red"
          onClick={() => void onConfirm()}
          loading={syncing}
          disabled={repoDocument == null || inSync}
        >
          {t("catalog.sync.confirm")}
        </Button>
      </Group>
    </Stack>
  );
}
