import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Badge, Button, Group, Loader, Modal, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  checkBlueprintImport,
  fetchBlueprintUrl,
  getBlueprint,
  getBlueprintSyncState,
  syncBlueprint,
  type BlueprintBody,
} from "../api/blueprints";
import YamlDiffView from "./YamlDiffView";
import { blueprintSaveErrorMessage } from "../utils/blueprintForm";
import {
  canonicalBlueprintDocumentJson,
  pickSourceBlueprintDocument,
  type BlueprintSyncTarget,
} from "../utils/blueprintSync";
import { blueprintExportDocument } from "../utils/ontologyExport";
import { relativeTimeAgo } from "../utils/relativeTime";
import { normalizeSourceUrl } from "../utils/sourceUrl";
import { compareSyncSides } from "../utils/syncComparison";
import { FETCH_URL_ERROR_KEYS, loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The Sync-from-source modal for blueprints (2.10.0) — `SyncEntityModal.tsx`'s shape one level
 * up: fetches the remote copy through the SSRF-guarded server fetch, shows which side moved
 * since the last sync (the stored baseline attributes it) plus a diff of the canonical JSON,
 * and overwrites the stored definition on explicit confirmation. Like the entity sync, the
 * server NEVER waives here — a fetched copy failing validation is refused outright, which this
 * modal pre-flights via `checkBlueprintImport` so the reader sees the refusal BEFORE confirming
 * rather than only after.
 *
 * Shell + body split: the body mounts only with a non-null target, so its query functions
 * narrow naturally (no `enabled`-laundering casts), while the shell keeps the one Modal mounted
 * for the open/close transition.
 */
export default function SyncBlueprintModal({
  target,
  onClose,
  onCompleted,
}: {
  /** The blueprint to sync; null keeps the modal closed. */
  target: BlueprintSyncTarget | null;
  onClose: () => void;
  /** Fired only after a SUCCESSFUL sync — see the catalog/entity modals for the rationale. */
  onCompleted?: () => void;
}) {
  const { t } = useTranslation();
  const [syncing, setSyncing] = useState(false);
  return (
    <Modal
      opened={target !== null}
      // Esc/overlay/the X follow the busy guard too (the ConfirmActionModal idiom) — a
      // dismissal mid-POST would unmount the error the failure is about to render.
      onClose={() => {
        if (!syncing) onClose();
      }}
      title={t("entities.sync.title", { identifier: target?.identifier ?? "" })}
      size="xl"
      centered
    >
      {target !== null && (
        <SyncModalBody
          target={target}
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
  target,
  syncing,
  onSyncingChange,
  onClose,
  onCompleted,
}: {
  target: BlueprintSyncTarget;
  syncing: boolean;
  onSyncingChange: (syncing: boolean) => void;
  onClose: () => void;
  onCompleted?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [confirmError, setConfirmError] = useState<string | null>(null);

  const { id, sourceUrl } = target;

  const detail = useQuery({
    queryKey: ["blueprints", "detail", id],
    queryFn: () => getBlueprint(id),
  });
  const syncState = useQuery({
    queryKey: ["blueprints", "syncState", id],
    queryFn: () => getBlueprintSyncState(id),
  });
  // Keyed OUTSIDE the ["blueprints"] prefix on purpose: the post-sync list invalidation must
  // never re-trigger this server-side outbound fetch (or the pre-flight check below).
  const sourceFetch = useQuery({
    queryKey: ["blueprintSourceCopy", id],
    queryFn: () => fetchBlueprintUrl(normalizeSourceUrl(sourceUrl ?? "")),
    enabled: sourceUrl != null,
    staleTime: 0,
    gcTime: 0,
  });

  // The remote side: parse the fetched text and pick this blueprint's document, keeping the
  // stored `hierarchyRelations` when the remote omits it (the server's own merge, mirrored here
  // so the diff shows the map unchanged rather than "removed").
  const picked = useMemo(() => {
    if (!sourceFetch.data || !syncState.data || !detail.data) return null;
    return pickSourceBlueprintDocument(sourceFetch.data.content, target, {
      // The CURRENTLY stored map only — never the sync baseline: an ordinary PUT may have changed
      // or cleared it since the last sync (a cleared map is ABSENT on the wire, so a baseline
      // fallback would resurrect it), and the server merges from the live row too.
      hierarchyRelations: detail.data.hierarchyRelations,
    });
  }, [sourceFetch.data, syncState.data, target, detail.data]);

  const remoteDocument = picked?.document ?? null;
  // The pre-flight: the SAME classification a real sync would apply, run as a dry-run import of
  // one document against the CURRENT registry — an INVALID row is exactly what the sync would
  // refuse with, shown BEFORE the reader confirms rather than only after.
  const preflight = useQuery({
    queryKey: ["blueprintSourceCheck", id],
    queryFn: () => {
      // `enabled` gates but does not narrow — guard honestly instead of casting.
      if (remoteDocument == null) throw new Error("pre-flight queried without a remote document");
      return checkBlueprintImport([remoteDocument], true);
    },
    enabled: remoteDocument != null,
    staleTime: 0,
    gcTime: 0,
  });
  const preflightRow = preflight.data?.results[0];
  const preflightInvalid = preflightRow?.status === "INVALID";

  // All comparison runs over the canonical JSON render — one equality for diff and badges.
  const currentJson = detail.data ? canonicalBlueprintDocumentJson(blueprintExportDocument(detail.data)) : null;
  const remoteJson = remoteDocument ? canonicalBlueprintDocumentJson(remoteDocument) : null;
  const baselineJson = syncState.data?.syncedDocument
    ? canonicalBlueprintDocumentJson(syncState.data.syncedDocument as Record<string, unknown>)
    : null;
  const lastSyncedAt = syncState.data?.lastSyncedAt ?? 0;
  const { inSync, dbChanged, repoChanged: sourceChanged, diff } = compareSyncSides({
    currentYaml: currentJson,
    repoYaml: remoteJson,
    baselineYaml: baselineJson,
    updatedAt: detail.data?.updatedAt ?? null,
    lastSyncedAt,
  });

  const loading = detail.isLoading || syncState.isLoading || sourceFetch.isLoading;
  function loadErrorText(): string | null {
    if (detail.isError) return loadErrorMessage(detail.error, t);
    if (syncState.isError) return loadErrorMessage(syncState.error, t);
    if (sourceFetch.isError) return saveErrorMessage(sourceFetch.error, t, FETCH_URL_ERROR_KEYS);
    if (picked?.error === "parse") return t("entities.sync.parseFailed");
    if (picked?.error === "noMatch") return t("blueprints.sync.noMatch");
    return null;
  }
  const loadError = loadErrorText();

  async function onConfirm() {
    if (remoteDocument == null) return;
    onSyncingChange(true);
    setConfirmError(null);
    try {
      await syncBlueprint(id, remoteDocument as BlueprintBody);
      showSuccessToast(t("blueprints.toast.synced"));
      onSyncingChange(false);
      onClose();
      // Refresh AFTER closing — nothing blocks the toast, and the modal's own remote queries
      // sit outside this prefix, so no outbound re-fetch fires. A hierarchy-relation change
      // affects the Entity graph/hierarchy shaping too (the `useBlueprintSave.ts` rule).
      void queryClient.invalidateQueries({ queryKey: ["blueprints"] });
      void queryClient.invalidateQueries({ queryKey: ["entities"] });
      onCompleted?.();
    } catch (err) {
      onSyncingChange(false);
      setConfirmError(blueprintSaveErrorMessage(err, t));
    }
  }

  return (
    <Stack gap="sm">
      {sourceUrl != null && (
        <Text size="sm">
          {t("entities.sync.sourceLabel")}{" "}
          <Anchor href={sourceUrl} target="_blank" rel="noreferrer" size="sm">
            {sourceUrl}
          </Anchor>
        </Text>
      )}
      <Text size="sm" c="dimmed">
        {lastSyncedAt > 0
          ? t("entities.sync.lastSynced", { ago: relativeTimeAgo(lastSyncedAt, i18n.language) })
          : t("entities.sync.neverSynced")}
      </Text>

      {loading && <Loader size="sm" role="status" aria-label={t("entities.sync.loadingAria")} />}
      {loadError != null && (
        <Alert color="red" variant="light" title={t("entities.sync.loadFailed")}>
          {loadError}
        </Alert>
      )}

      {!loading && loadError == null && currentJson != null && remoteDocument != null && (
        <>
          <Group gap="xs">
            {inSync ? (
              <Badge variant="light" color="teal" size="sm">
                {t("entities.sync.inSync")}
              </Badge>
            ) : (
              <>
                {/* No baseline (never synced) = sides cannot be attributed; the diff says it all. */}
                {sourceChanged && (
                  <Badge variant="light" color="orange" size="sm">
                    {t("entities.sync.changedAtSource")}
                  </Badge>
                )}
                {dbChanged && (
                  <Badge variant="light" color="orange" size="sm">
                    {t("entities.sync.changedInDb")}
                  </Badge>
                )}
              </>
            )}
          </Group>

          {diff != null && (
            <YamlDiffView
              diff={diff}
              label={t("entities.sync.diffLabel")}
              fallbackLabels={{
                tooLarge: t("entities.sync.diffTooLarge"),
                stored: t("entities.sync.diffStoredLabel"),
                replacement: t("entities.sync.diffReplacementLabel"),
              }}
            />
          )}

          {picked?.hierarchyKept && (
            <Text size="sm" c="dimmed">
              {t("blueprints.sync.hierarchyKept")}
            </Text>
          )}

          {preflightInvalid && (
            <Alert color="red" variant="light" title={t("entities.sync.refusedTitle")}>
              <Stack gap={4}>
                <Text size="sm">{t("blueprints.sync.refusedBody")}</Text>
                {preflightRow?.message && <Text size="sm">{preflightRow.message}</Text>}
              </Stack>
            </Alert>
          )}

          {!inSync && !preflightInvalid && (
            <Text size="sm" c="dimmed">
              {t("entities.sync.overwriteWarning")}
            </Text>
          )}
        </>
      )}

      {confirmError != null && (
        <Alert color="red" variant="light" title={t("entities.sync.failed")}>
          <Text size="sm">{confirmError}</Text>
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
          disabled={remoteDocument == null || inSync || preflightInvalid}
        >
          {t("entities.sync.confirm")}
        </Button>
      </Group>
    </Stack>
  );
}
