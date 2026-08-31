import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, FileButton, Group, Loader, Modal, Stack, Text, Textarea } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconUpload } from "@tabler/icons-react";
import { checkCatalogFile, getCatalogFile, updateCatalogFile } from "../api/catalogFiles";
import YamlDiffView from "./YamlDiffView";
import { parseCatalogYaml, pickRepoDocument } from "../utils/catalogImport";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { diffLines } from "../utils/yamlDiff";
import { CATALOG_SAVE_ERROR_KEYS, loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/** The identity a multi-document paste is matched against. */
export type OverwriteTarget = {
  id: number;
  kind: string;
  name: string;
  namespace: string;
};

/**
 * Replace ONE stored file's document with a YAML the user pastes or picks — the Sync modal's
 * shape with the repo fetch swapped for the user's own text, since overwriting is equally
 * destructive and deserves the same diff-then-confirm ceremony.
 *
 * Two things it must not get wrong. It writes through the ordinary PUT, never
 * `syncCatalogFile`: the sync endpoint stamps `lastSyncedAt` and the baseline, which would
 * claim the file is in sync with a repo it never touched. And it passes the file's existing
 * `sourceUrl` back — PUT is a full replace, so omitting it would silently unlink the file
 * from its repo and reset the sync state.
 *
 * Shell + body split so the body's queries narrow on a non-null target (the Sync modal idiom).
 */
export default function OverwriteWithYamlModal({
  file,
  onClose,
  onCompleted,
}: {
  /** The file to overwrite; null keeps the modal closed. */
  file: OverwriteTarget | null;
  onClose: () => void;
  /**
   * Fired only after a SUCCESSFUL write — `onClose` alone cannot tell a Cancel from a
   * completed overwrite. The page owns what happens next (the useDeleteConfirm convention);
   * the editor re-seeds its form, the list pages need nothing.
   */
  onCompleted?: () => void;
}) {
  const { t } = useTranslation();
  const [saving, setSaving] = useState(false);
  return (
    <Modal
      opened={file !== null}
      // Busy-guarded like the sync modal: a dismissal mid-PUT would unmount the error the
      // failure is about to render.
      onClose={() => {
        if (!saving) onClose();
      }}
      title={t("catalog.overwrite.title", { name: file?.name ?? "" })}
      size="xl"
      centered
    >
      {file !== null && (
        <OverwriteModalBody
          file={file}
          saving={saving}
          onSavingChange={setSaving}
          onClose={onClose}
          onCompleted={onCompleted}
        />
      )}
    </Modal>
  );
}

function OverwriteModalBody({
  file,
  saving,
  onSavingChange,
  onClose,
  onCompleted,
}: {
  file: OverwriteTarget;
  saving: boolean;
  onSavingChange: (saving: boolean) => void;
  onClose: () => void;
  onCompleted?: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [text, setText] = useState("");
  const [saveError, setSaveError] = useState<string | null>(null);

  const detail = useQuery({
    queryKey: ["catalogFiles", "detail", file.id],
    queryFn: () => getCatalogFile(file.id),
  });

  // Parse on every keystroke: the text is hand-sized here (one document), unlike the import
  // page's multi-document paste, so there is nothing to debounce away.
  const parsed = useMemo(() => {
    if (!text.trim()) return null;
    const result = parseCatalogYaml(text);
    if (result.errors.length > 0 || result.documents.length === 0) {
      return { document: null, error: "parse" as const };
    }
    // Single-document input is taken as-is (a rename is legal — the server enforces
    // identity); a multi-document paste must contain THIS file's identity.
    const document = pickRepoDocument(result.documents, file);
    return document ? { document, error: null } : { document: null, error: "noMatch" as const };
  }, [text, file]);

  const document = parsed?.document ?? null;
  // Keyed OUTSIDE the ["catalogFiles"] prefix so the post-save invalidation cannot re-run it.
  const findings = useQuery({
    queryKey: ["overwriteFindings", file.id, text],
    queryFn: () => {
      if (document == null) throw new Error("findings queried without a document");
      return checkCatalogFile(document);
    },
    enabled: document != null,
    staleTime: 0,
    gcTime: 0,
  });

  const currentYaml = detail.data
    ? catalogInfoYaml({ kind: detail.data.kind, metadata: detail.data.metadata, spec: detail.data.spec })
    : null;
  const nextYaml = document ? catalogInfoYaml(document) : null;
  const identical = currentYaml != null && nextYaml != null && currentYaml === nextYaml;
  const diff = currentYaml != null && nextYaml != null && !identical ? diffLines(currentYaml, nextYaml) : null;

  const findingCount = findings.data?.findings.length ?? 0;

  function parseErrorText(): string | null {
    if (detail.isError) return loadErrorMessage(detail.error, t);
    if (parsed?.error === "parse") return t("catalog.overwrite.parseFailed");
    if (parsed?.error === "noMatch") return t("catalog.overwrite.noMatch", { name: file.name });
    return null;
  }

  async function onConfirm() {
    // The detail is what carries `sourceUrl` forward, so never write without it.
    if (document == null || !detail.data) return;
    onSavingChange(true);
    setSaveError(null);
    try {
      // The reference rides along: PUT is a full replace and an omitted sourceUrl clears it.
      // Findings are waived deliberately (the sync/import posture) — they are shown above,
      // and nesting the Save-anyway modal inside this one would be worse.
      await updateCatalogFile(
        file.id,
        { ...document, sourceUrl: detail.data?.sourceUrl ?? undefined },
        findingCount > 0 ? { allowInvalid: true } : undefined,
      );
      showSuccessToast(t("catalog.toast.overwritten"));
      onSavingChange(false);
      onClose();
      void queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      onCompleted?.();
    } catch (err) {
      onSavingChange(false);
      setSaveError(saveErrorMessage(err, t, CATALOG_SAVE_ERROR_KEYS));
    }
  }

  const loadError = parseErrorText();
  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        {t("catalog.overwrite.intro")}
      </Text>

      <Textarea
        label={t("catalog.import.textareaLabel")}
        placeholder={t("catalog.import.placeholder")}
        value={text}
        onChange={(event) => setText(event.currentTarget.value)}
        autosize
        minRows={6}
        maxRows={14}
        styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
      />
      <Group>
        <FileButton
          onChange={(picked) => {
            if (picked) void picked.text().then(setText);
          }}
          accept=".yaml,.yml,text/yaml"
        >
          {(props) => (
            <Button {...props} variant="default" size="xs" leftSection={<IconUpload size={14} />}>
              {t("catalog.import.pickFile")}
            </Button>
          )}
        </FileButton>
      </Group>

      {detail.isLoading && <Loader size="sm" aria-label={t("catalog.overwrite.loadingAria")} />}

      {loadError && (
        <Alert color="red" variant="light" title={t("catalog.overwrite.loadFailed")}>
          {loadError}
        </Alert>
      )}

      {identical && (
        <Text size="sm" c="dimmed">
          {t("catalog.overwrite.identical")}
        </Text>
      )}

      {diff != null && <YamlDiffView diff={diff} label={t("catalog.overwrite.diffLabel")} />}

      {findingCount > 0 && (
        <Alert color="orange" variant="light">
          {t("catalog.overwrite.findingsWarning", { count: findingCount })}
        </Alert>
      )}

      {document != null && !identical && (
        <Alert color="orange" variant="light">
          {t("catalog.overwrite.warning")}
        </Alert>
      )}

      {saveError && (
        <Alert color="red" variant="light" title={t("catalog.overwrite.failed")}>
          {saveError}
        </Alert>
      )}

      <Group justify="flex-end" gap="sm">
        <Button variant="default" onClick={onClose} disabled={saving} data-autofocus>
          {t("common.action.cancel")}
        </Button>
        <Button
          color="red"
          onClick={() => void onConfirm()}
          loading={saving}
          disabled={document == null || identical || !detail.data}
        >
          {t("catalog.overwrite.confirm")}
        </Button>
      </Group>
    </Stack>
  );
}
