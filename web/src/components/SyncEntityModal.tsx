import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Badge, Button, Group, Loader, Modal, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  checkEntityImport,
  entitySaveFindings,
  fetchEntityUrl,
  getEntity,
  getEntitySyncState,
  syncEntity,
  type EntityBody,
  type EntityFinding,
} from "../api/entities";
import YamlDiffView from "./YamlDiffView";
import { useBlueprints } from "../hooks/useBlueprints";
import { entitySaveErrorMessage } from "../utils/entityForm";
import { canonicalEntityDocumentJson, pickSourceEntityDocument, type EntitySyncTarget } from "../utils/entitySync";
import { entityExportDocument } from "../utils/ontologyExport";
import { relativeTimeAgo } from "../utils/relativeTime";
import { normalizeSourceUrl } from "../utils/sourceUrl";
import { compareSyncSides } from "../utils/syncComparison";
import {
  preflightAccepted as isPreflightAccepted,
  preflightPending,
  querySettled,
} from "../utils/syncReadiness";
import {
  FETCH_URL_ERROR_KEYS,
  isSourceReferenceConflict,
  loadErrorMessage,
  saveErrorMessage,
} from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The Sync-from-source modal for entities — the `SyncCatalogFileModal.tsx` shape one level
 * over: fetches the remote copy through the SSRF-guarded server fetch, shows which side moved
 * since the last sync (the stored baseline attributes it) plus a diff of the canonical JSON,
 * and overwrites the stored copy on explicit confirmation. Unlike the catalog's repo sync,
 * the server NEVER waives here — a fetched copy failing the blueprint's current rules is
 * refused outright, which this modal pre-flights via `checkEntityImport` so the reader sees
 * the refusal BEFORE confirming rather than only after.
 *
 * Shell + body split: the body mounts only with a non-null target, so its query functions
 * narrow naturally (no `enabled`-laundering casts), while the shell keeps the one Modal
 * mounted for the open/close transition.
 */
export default function SyncEntityModal({
  target,
  onClose,
  onCompleted,
}: {
  /** The entity to sync; null keeps the modal closed. */
  target: EntitySyncTarget | null;
  onClose: () => void;
  /** Fired only after a SUCCESSFUL sync — see the catalog modal for the rationale. */
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
  target: EntitySyncTarget;
  syncing: boolean;
  onSyncingChange: (syncing: boolean) => void;
  onClose: () => void;
  onCompleted?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [confirmError, setConfirmError] = useState<{ message: string; findings: EntityFinding[] } | null>(null);

  const { id } = target;
  const {
    blueprints,
    loading: blueprintsLoading,
    fetching: blueprintsFetching,
    loaded: blueprintsLoaded,
    error: blueprintsError,
    loadError: blueprintsLoadError,
  } = useBlueprints({ freshOnMount: true });

  const detail = useQuery({
    queryKey: ["entities", "detail", id],
    queryFn: () => getEntity(id),
  });
  const syncState = useQuery({
    queryKey: ["entities", "syncState", id],
    queryFn: () => getEntitySyncState(id),
  });
  const blueprint = blueprints.find((b) => b.identifier === detail.data?.blueprint);
  // Bind the fetch and eventual write guard to the source reference on the freshly loaded
  // detail. The list row that opened this modal may predate a source-reference edit.
  const sourceUrl = detail.data?.sourceUrl ?? null;
  const sourceStateMatches = sourceUrl != null && syncState.data?.sourceUrl === sourceUrl;
  // Keyed OUTSIDE the ["entities"] prefix on purpose: the post-sync list invalidation must
  // never re-trigger this server-side outbound fetch (or the pre-flight check below).
  const sourceFetch = useQuery({
    queryKey: ["entitySourceCopy", id, sourceUrl],
    queryFn: () => fetchEntityUrl(normalizeSourceUrl(sourceUrl ?? "")),
    enabled: [querySettled(detail), querySettled(syncState), sourceUrl != null, sourceStateMatches].every(Boolean),
    staleTime: 0,
    gcTime: 0,
  });

  // The remote side: parse the fetched text and pick this entity's document.
  const picked = useMemo(() => {
    if (!sourceFetch.data || !detail.data || !blueprintsLoaded || blueprintsFetching) return null;
    return pickSourceEntityDocument(sourceFetch.data.content, detail.data, blueprints);
  }, [sourceFetch.data, detail.data, blueprints, blueprintsLoaded, blueprintsFetching]);

  const remoteDocument = picked?.document ?? null;
  const remoteJson = remoteDocument ? canonicalEntityDocumentJson(remoteDocument) : null;
  // The pre-flight: the SAME classification a real sync would apply, run as a dry-run import
  // of one document against the CURRENT blueprint — an INVALID row is exactly what the sync
  // would refuse with, shown BEFORE the reader confirms rather than only after.
  const preflight = useQuery({
    queryKey: ["entitySourceCheck", id, sourceUrl, remoteJson],
    queryFn: () => {
      // `enabled` gates but does not narrow — guard honestly instead of casting.
      if (remoteDocument == null) throw new Error("pre-flight queried without a remote document");
      return checkEntityImport([remoteDocument], true);
    },
    enabled: [
      querySettled(sourceFetch),
      blueprintsLoaded,
      !blueprintsFetching,
      !blueprintsError,
      blueprint != null,
      remoteDocument != null,
    ].every(Boolean),
    staleTime: 0,
    gcTime: 0,
  });
  const preflightRow = preflight.data?.results[0];
  const preflightInvalid = preflightRow?.status === "INVALID";
  const preflightAccepted = isPreflightAccepted(preflight, preflightRow?.status);

  // All comparison runs over the canonical JSON render — one equality for diff and badges.
  const currentJson =
    detail.data && blueprint ? canonicalEntityDocumentJson(entityExportDocument(detail.data, blueprint)) : null;
  const baselineJson = syncState.data?.syncedDocument
    ? canonicalEntityDocumentJson(syncState.data.syncedDocument as Record<string, unknown>)
    : null;
  const lastSyncedAt = syncState.data?.lastSyncedAt ?? 0;
  const { inSync, dbChanged, repoChanged: sourceChanged, diff } = compareSyncSides({
    currentYaml: currentJson,
    repoYaml: remoteJson,
    baselineYaml: baselineJson,
    updatedAt: detail.data?.updatedAt ?? null,
    lastSyncedAt,
  });

  const loading = [
    detail.isFetching,
    syncState.isFetching,
    blueprintsLoading,
    blueprintsFetching,
    sourceFetch.isFetching,
    preflightPending(blueprint != null && remoteDocument != null, preflight),
  ].some(Boolean);
  const comparisonReady = [
    querySettled(detail),
    querySettled(syncState),
    blueprintsLoaded,
    !blueprintsFetching,
    !blueprintsError,
    blueprint != null,
    querySettled(sourceFetch),
    sourceStateMatches,
    currentJson != null,
    remoteJson != null,
  ].every(Boolean);
  function loadErrorText(): string | null {
    if (detail.isError) return loadErrorMessage(detail.error, t);
    if (syncState.isError) return loadErrorMessage(syncState.error, t);
    if (blueprintsError) return loadErrorMessage(blueprintsLoadError, t);
    if (blueprintsLoaded && !blueprintsFetching && detail.data && blueprint == null) {
      return t("entities.editor.blueprintMissing");
    }
    if (sourceFetch.isError) return saveErrorMessage(sourceFetch.error, t, FETCH_URL_ERROR_KEYS);
    if (sourceUrl == null && detail.data) return t("entities.sync.noSource");
    if (detail.data && syncState.data && !sourceStateMatches) return t("entities.sync.sourceStateMismatch");
    if (picked?.error === "parse") return t("entities.sync.parseFailed");
    if (picked?.error === "noMatch") return t("entities.sync.noMatch");
    if (picked?.error === "blueprintMismatch") {
      return t("entities.sync.blueprintMismatch", { blueprint: detail.data?.blueprint ?? target.blueprint });
    }
    if (preflight.isError) return loadErrorMessage(preflight.error, t);
    if (preflight.isSuccess && !preflightInvalid && !preflightAccepted) return t("entities.sync.preflightEmpty");
    return null;
  }
  const loadError = loadErrorText();
  const canConfirm = [
    !syncing,
    comparisonReady,
    preflightAccepted,
    remoteDocument != null,
    !inSync,
    loadError == null,
  ].every(Boolean);

  async function onConfirm() {
    if (!canConfirm || remoteDocument == null || sourceUrl == null) return;
    onSyncingChange(true);
    setConfirmError(null);
    try {
      await syncEntity(id, remoteDocument as EntityBody, sourceUrl);
      showSuccessToast(t("entities.toast.synced"));
      onSyncingChange(false);
      onClose();
      // Refresh AFTER closing — nothing blocks the toast, and the modal's own remote queries
      // sit outside this prefix, so no outbound re-fetch fires.
      void queryClient.invalidateQueries({ queryKey: ["entities"] });
      onCompleted?.();
    } catch (err) {
      onSyncingChange(false);
      setConfirmError({
        message: isSourceReferenceConflict(err) ? t("sync.sourceConflict") : entitySaveErrorMessage(err, t),
        findings: entitySaveFindings(err),
      });
    }
  }

  return (
    <Stack gap="sm">
      {sourceUrl != null && (
        <Text size="sm">
          {t("sync.sourceLabel")}{" "}
          <Anchor href={sourceUrl} target="_blank" rel="noreferrer" size="sm">
            {sourceUrl}
          </Anchor>
        </Text>
      )}
      <Text size="sm" c="dimmed">
        {lastSyncedAt > 0
          ? t("sync.lastSynced", { ago: relativeTimeAgo(lastSyncedAt, i18n.language) })
          : t("sync.neverSynced")}
      </Text>

      {loading && <Loader size="sm" role="status" aria-label={t("entities.sync.loadingAria")} />}
      {loadError != null && (
        <Alert color="red" variant="light" title={t("sync.loadFailed")}>
          {loadError}
        </Alert>
      )}

      {comparisonReady && loadError == null && remoteDocument != null && (
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
                    {t("sync.changedAtSource")}
                  </Badge>
                )}
                {dbChanged && (
                  <Badge variant="light" color="orange" size="sm">
                    {t("sync.changedInDb")}
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
                tooLarge: t("sync.diffTooLarge"),
                stored: t("sync.diffStoredLabel"),
                replacement: t("sync.diffReplacementLabel"),
              }}
            />
          )}

          {picked && picked.strippedComputed.length > 0 && (
            <Alert color="gray" variant="light">
              {t("ontology.import.strippedComputed", {
                count: picked.strippedComputed.length,
                keys: picked.strippedComputed.join(", "),
              })}
            </Alert>
          )}

          {preflightInvalid && (
            <Alert color="red" variant="light" title={t("sync.refusedTitle")}>
              <Stack gap={4}>
                <Text size="sm">{t("entities.sync.refusedBody")}</Text>
                {(preflightRow?.findings ?? []).length > 0 ? (
                  preflightRow?.findings?.map((finding, i) => (
                    <Text size="sm" key={`${finding.field}-${finding.code}-${i}`}>
                      {finding.field}: {finding.message}
                    </Text>
                  ))
                ) : (
                  <Text size="sm">{preflightRow?.message}</Text>
                )}
              </Stack>
            </Alert>
          )}

          {!inSync && preflightAccepted && (
            <Text size="sm" c="dimmed">
              {t("entities.sync.overwriteWarning")}
            </Text>
          )}
        </>
      )}

      {confirmError != null && (
        <Alert color="red" variant="light" title={t("sync.failed")}>
          <Stack gap={4}>
            {confirmError.findings.length > 0 ? (
              confirmError.findings.map((finding, i) => (
                <Text size="sm" key={`${finding.field}-${finding.code}-${i}`}>
                  {finding.field}: {finding.message}
                </Text>
              ))
            ) : (
              <Text size="sm">{confirmError.message}</Text>
            )}
          </Stack>
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
          disabled={!canConfirm}
        >
          {t("sync.confirm")}
        </Button>
      </Group>
    </Stack>
  );
}
