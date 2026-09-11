import { useMemo, useState } from "react";
import { useDebouncedValue } from "@mantine/hooks";
import { useTranslation } from "react-i18next";
import { Alert, Button, FileButton, Group, Paper, Pill, Progress, Stack, Switch, Text, Textarea } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconFileImport, IconListCheck, IconUpload } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { checkBlueprintImport, importBlueprints } from "../api/blueprints";
import { checkEntityImport, importEntities } from "../api/entities";
import {
  parseOntologySources,
  runImportBatch,
  summarizeStripped,
  type ImportSource,
  type OntologyResultRow,
} from "../utils/ontologyImport";
import { saveErrorMessage } from "../utils/saveError";
import { useBlueprints } from "../hooks/useBlueprints";
import OntologyImportResults from "../components/OntologyImportResults";
import PageHeader from "../components/PageHeader";

function combineSources(pastedText: string, files: readonly ImportSource[], pastedLabel: string): ImportSource[] {
  const sources = [...files];
  if (pastedText.trim()) sources.unshift({ label: pastedLabel, text: pastedText });
  return sources;
}

export default function ImportOntology() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { blueprints: registryBlueprints } = useBlueprints();

  const [text, setText] = useState("");
  const [files, setFiles] = useState<ImportSource[]>([]);
  const [replaceExisting, setReplaceExisting] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [checking, setChecking] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [progress, setProgress] = useState<{ sent: number; total: number } | null>(null);
  // The mode discriminates the report: a dry-run's rows are PREDICTIONS ("Would be
  // created"), never to be mislabeled as stored.
  const [results, setResults] = useState<{ mode: "import" | "check"; rows: OntologyResultRow[] } | null>(null);

  const [debouncedText] = useDebouncedValue(text, 300);
  const pastedLabel = t("ontology.import.pastedSource");

  const sources = useMemo(
    () => combineSources(debouncedText, files, pastedLabel),
    [debouncedText, files, pastedLabel],
  );
  const parsed = useMemo(() => parseOntologySources(sources, registryBlueprints), [sources, registryBlueprints]);
  const summary = useMemo(() => summarizeStripped(parsed.documents), [parsed.documents]);
  const canImport = parsed.documents.length > 0 && parsed.errors.length === 0;
  const blueprintCount = parsed.documents.filter((doc) => doc.kind === "blueprint").length;
  const entityCount = parsed.documents.filter((doc) => doc.kind === "entity").length;
  const showNotAdminNotice = !isAdmin() && blueprintCount > 0;

  function clearResults() {
    setResults(null);
  }

  async function handleFiles(pickedFiles: File[]) {
    if (pickedFiles.length === 0) return;
    const picked = await Promise.all(pickedFiles.map(async (file) => ({ label: file.name, text: await file.text() })));
    setFiles((prev) => [...prev, ...picked]);
    clearResults();
  }

  function removeFile(index: number) {
    setFiles((prev) => prev.filter((_, i) => i !== index));
    clearResults();
  }

  async function runBatch(mode: "import" | "check") {
    // Re-parse the LIVE text: the debounced parse above may lag a just-typed edit.
    const current = parseOntologySources(combineSources(text, files, pastedLabel), registryBlueprints);
    if (current.documents.length === 0 || current.errors.length > 0) return;
    const setBusy = mode === "import" ? setSubmitting : setChecking;
    setBusy(true);
    setSubmitError(null);
    setProgress(null);
    try {
      const rows = await runImportBatch({
        documents: current.documents,
        replaceExisting,
        admin: isAdmin(),
        importBlueprintsChunk: mode === "import" ? importBlueprints : checkBlueprintImport,
        importEntitiesChunk: mode === "import" ? importEntities : checkEntityImport,
        onProgress: (sent, total) => setProgress({ sent, total }),
        onRows: (partial) => setResults({ mode, rows: partial }),
      });
      setResults({ mode, rows });
      if (mode === "import" && rows.some((row) => row.id != null && row.status !== "EXISTS")) {
        await queryClient.invalidateQueries({ queryKey: ["blueprints"] });
        await queryClient.invalidateQueries({ queryKey: ["entities"] });
      }
    } catch (err) {
      // Rows already reported via onRows stay visible — only the failure banner is new.
      setSubmitError(
        saveErrorMessage(err, t, {
          failedStatus: "ontology.import.failedStatus",
          failed: "ontology.import.failedNetwork",
        }),
      );
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("ontology.import.title")} description={t("ontology.import.intro")} />

      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          <Textarea
            label={t("ontology.import.textareaLabel")}
            placeholder={t("ontology.import.placeholder")}
            value={text}
            onChange={(event) => {
              setText(event.currentTarget.value);
              clearResults();
            }}
            autosize
            minRows={10}
            maxRows={24}
            styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
          />

          <Group justify="space-between" align="flex-end">
            <FileButton onChange={(picked) => void handleFiles(picked)} accept=".json,application/json" multiple>
              {(props) => (
                <Button {...props} variant="default" leftSection={<IconUpload size={16} />}>
                  {t("ontology.import.pickFiles")}
                </Button>
              )}
            </FileButton>
            {sources.length > 0 && (
              <Text size="sm" c={parsed.errors.length > 0 ? "red" : "dimmed"}>
                {parsed.errors.length > 0
                  ? t("ontology.import.summaryErrors", { count: parsed.errors.length })
                  : t("ontology.import.summaryReady", { blueprints: blueprintCount, entities: entityCount })}
              </Text>
            )}
          </Group>

          {files.length > 0 && (
            <Pill.Group>
              {files.map((file, index) => (
                <Pill
                  key={`${file.label}-${index}`}
                  withRemoveButton
                  onRemove={() => removeFile(index)}
                  removeButtonProps={{
                    "aria-label": t("ontology.import.removeFileAria", { name: file.label }),
                    "aria-hidden": false,
                    tabIndex: 0,
                  }}
                >
                  {file.label}
                </Pill>
              ))}
            </Pill.Group>
          )}

          <Switch
            label={t("ontology.import.replaceExisting")}
            description={t("ontology.import.replaceExistingHint")}
            checked={replaceExisting}
            onChange={(event) => {
              setReplaceExisting(event.currentTarget.checked);
              clearResults();
            }}
          />
        </Stack>
      </Paper>

      {parsed.errors.length > 0 && (
        <Alert color="red" variant="light" title={t("ontology.import.parseErrorsTitle")}>
          <Stack gap={4}>
            {parsed.errors.map((error, i) => (
              <Text size="sm" key={`${error.source}-${error.index ?? "syntax"}-${i}`}>
                {error.source}: {error.message}
              </Text>
            ))}
          </Stack>
        </Alert>
      )}

      {(summary.strippedCount > 0 || summary.strippedComputedCount > 0) && (
        <Alert color="gray" variant="light" title={t("ontology.import.strippedTitle")}>
          <Stack gap={4}>
            {summary.strippedCount > 0 && (
              <Text size="sm">
                {t("ontology.import.strippedKeys", { count: summary.strippedCount, keys: summary.strippedKeys.join(", ") })}
              </Text>
            )}
            {summary.strippedComputedCount > 0 && (
              <Text size="sm">
                {t("ontology.import.strippedComputed", {
                  count: summary.strippedComputedCount,
                  keys: summary.strippedComputedKeys.join(", "),
                })}
              </Text>
            )}
          </Stack>
        </Alert>
      )}

      {showNotAdminNotice && (
        <Alert color="orange" variant="light" title={t("ontology.import.notAdminTitle")}>
          {t("ontology.import.notAdminBody", { count: blueprintCount })}
        </Alert>
      )}

      {submitError && (
        <Alert color="red" variant="light" title={t("ontology.import.failedTitle")}>
          {submitError}
        </Alert>
      )}

      {progress && (
        <Progress
          value={(progress.sent / progress.total) * 100}
          aria-label={t("ontology.import.progress", { current: progress.sent, total: progress.total })}
        />
      )}

      {results && <OntologyImportResults rows={results.rows} mode={results.mode} showSource={sources.length > 1} />}

      <Group justify="flex-end">
        <Group gap="xs">
          <Button
            variant="default"
            leftSection={<IconListCheck size={16} />}
            onClick={() => void runBatch("check")}
            disabled={!canImport}
            loading={checking}
          >
            {t("ontology.import.checkButton")}
          </Button>
          <Button
            leftSection={<IconFileImport size={16} />}
            onClick={() => void runBatch("import")}
            disabled={!canImport}
            loading={submitting}
          >
            {t("ontology.import.importButton")}
          </Button>
        </Group>
      </Group>
    </Stack>
  );
}
