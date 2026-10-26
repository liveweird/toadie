import { useMemo, useState } from "react";
import { useDebouncedValue } from "@mantine/hooks";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Button, FileButton, Group, Paper, Progress, Select, Stack, Switch, Text, Textarea, TextInput, Title } from "@mantine/core";
import { IconArrowsExchange, IconDownload, IconFileImport, IconListCheck, IconUpload } from "@tabler/icons-react";
import { fetchCatalogUrl, type CatalogFileRequest } from "../api/catalogFiles";
import { useConversionMappings } from "../hooks/useConversionMappings";
import { parseCatalogYaml } from "../utils/catalogImport";
import { conversionMappingsPath, editConversionMappingPath } from "../utils/conversionLinks";
import { convertCatalogFiles, convertForImport, importConverted } from "../utils/conversionBatches";
import { ImportPhaseError, importFlowError } from "../utils/conversionErrors";
import { importDisplayRows } from "../utils/conversionRows";
import { IMPORT_CHUNK_SIZE, type OntologyResultRow } from "../utils/ontologyImport";
import { FETCH_URL_ERROR_KEYS, saveErrorMessage } from "../utils/saveError";
import { normalizeSourceUrl } from "../utils/sourceUrl";
import BackstageImportConfirmModal from "./BackstageImportConfirmModal";
import ConversionRowsTable from "./ConversionRowsTable";
import OntologyImportResults from "./OntologyImportResults";

type Converted = ReturnType<typeof importDisplayRows>;

/**
 * The "Backstage catalog (YAML)" source of the Ontology import page (conversion phase 5c):
 * paste / pick / fetch a multi-document catalog-info.yaml (the Files import's own strict
 * `parseCatalogYaml`), choose a conversion mapping and the merge switch, then
 *
 * - **Convert** — previews the per-file conversion report (`POST …/entity-documents`, a pure
 *   computation);
 * - **Check import** / **Import** — each runs a FRESH conversion immediately before talking to
 *   `POST /entities/import(/check)` with `replaceExisting: true`, never reusing the preview's
 *   documents: nothing guards the entity import against a Port-only edit made after a conversion
 *   (`.claude/docs/conversion-mapping.md` "Read-then-write caveat"), so the gap between the merge
 *   read and the write is kept as short as the client can make it.
 */
export default function BackstageCatalogImport() {
  const { t } = useTranslation();
  const { mappings, loading: mappingsLoading } = useConversionMappings();

  const [text, setText] = useState("");
  const [url, setUrl] = useState("");
  const [fetching, setFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [pickedId, setPickedId] = useState<number | null>(null);
  const [merge, setMerge] = useState(true);
  const [busy, setBusy] = useState<"convert" | "check" | null>(null);
  const [error, setError] = useState<{ title: string; message: string } | null>(null);
  // The parsed documents the confirm dialog converts (a snapshot of the live text at click time).
  const [confirmFiles, setConfirmFiles] = useState<CatalogFileRequest[] | null>(null);
  const [progress, setProgress] = useState<{ sent: number; total: number } | null>(null);
  const [conversion, setConversion] = useState<Converted | null>(null);
  const [results, setResults] = useState<{ mode: "import" | "check"; rows: OntologyResultRow[] } | null>(null);

  const [debouncedText] = useDebouncedValue(text, 300);
  const parsed = useMemo(() => parseCatalogYaml(debouncedText), [debouncedText]);
  const mapping =
    mappings.find((m) => m.id === pickedId) ?? (pickedId === null && mappings.length === 1 ? mappings[0] : null);
  const stale = mapping != null && mapping.findings.length > 0;
  const ready = parsed.documents.length > 0 && parsed.errors.length === 0 && mapping != null && !stale;

  function resetOutput() {
    setConversion(null);
    setResults(null);
    setError(null);
  }

  async function handleFile(file: File | null) {
    if (!file) return;
    setText(await file.text());
    resetOutput();
  }

  async function handleFetchUrl() {
    setFetching(true);
    setFetchError(null);
    try {
      const fetched = await fetchCatalogUrl(normalizeSourceUrl(url));
      setText(fetched.content);
      resetOutput();
    } catch (err) {
      setFetchError(saveErrorMessage(err, t, FETCH_URL_ERROR_KEYS));
    } finally {
      setFetching(false);
    }
  }

  /** Re-parses the LIVE text (the debounced parse may lag a just-typed edit); null when not convertible. */
  function liveFiles(): CatalogFileRequest[] | null {
    const current = parseCatalogYaml(text);
    return mapping == null || current.documents.length === 0 || current.errors.length > 0 ? null : current.documents;
  }

  /** Convert (preview) and Check: a fresh conversion each, and for Check the entity dry run after it. */
  async function run(mode: "convert" | "check") {
    const files = liveFiles();
    if (files == null || mapping == null) return;
    setBusy(mode);
    setError(null);
    setProgress(null);
    setResults(null);
    const convert = async () => {
      const converted = await convertCatalogFiles(mapping.id, files, merge);
      setConversion(importDisplayRows(converted.rows));
      return converted;
    };
    try {
      if (mode === "convert") {
        await convert().catch((err: unknown) => {
          throw new ImportPhaseError("convert", err);
        });
        return;
      }
      const { rows } = await importConverted(await convertForImport(convert), mode, {
        sourceLabel: t("conversion.import.sourceLabel"),
        onProgress: (sent, total) => setProgress({ sent, total }),
        onRows: (partial) => setResults({ mode, rows: partial }),
      });
      setResults({ mode, rows });
    } catch (err) {
      setError(importFlowError(err, t));
    } finally {
      setBusy(null);
      setProgress(null);
    }
  }

  function openConfirm() {
    const files = liveFiles();
    if (files == null) return;
    setError(null);
    setResults(null);
    setConfirmFiles(files);
  }

  return (
    <Stack gap="md">
      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          <Group align="flex-end" gap="xs">
            <TextInput
              label={t("catalog.import.urlLabel")}
              placeholder="https://raw.githubusercontent.com/acme/service/main/catalog-info.yaml"
              value={url}
              onChange={(event) => setUrl(event.currentTarget.value)}
              disabled={busy !== null}
              style={{ flex: 1 }}
            />
            <Button
              variant="default"
              leftSection={<IconDownload size={16} />}
              onClick={() => void handleFetchUrl()}
              disabled={!url.trim() || busy !== null}
              loading={fetching}
            >
              {t("catalog.import.fetchButton")}
            </Button>
          </Group>
          {fetchError && (
            <Alert color="red" variant="light" title={t("catalog.import.urlFailedTitle")}>
              {fetchError}
            </Alert>
          )}
          <Textarea
            label={t("catalog.import.textareaLabel")}
            placeholder={t("catalog.import.placeholder")}
            value={text}
            onChange={(event) => {
              setText(event.currentTarget.value);
              resetOutput();
            }}
            disabled={busy !== null}
            autosize
            minRows={10}
            maxRows={24}
            styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
          />
          <Group justify="space-between">
            <FileButton onChange={(file) => void handleFile(file)} accept=".yaml,.yml,text/yaml" disabled={busy !== null}>
              {(props) => (
                <Button {...props} variant="default" leftSection={<IconUpload size={16} />}>
                  {t("catalog.import.pickFile")}
                </Button>
              )}
            </FileButton>
            {text.trim() && (
              <Text size="sm" c={parsed.errors.length > 0 ? "red" : "dimmed"}>
                {parsed.errors.length > 0
                  ? t("catalog.import.summaryErrors", { count: parsed.errors.length })
                  : t("catalog.import.summaryDocuments", { count: parsed.documents.length })}
              </Text>
            )}
          </Group>
          <Group align="flex-start" gap="md" wrap="wrap">
            <Select
              label={t("conversion.export.mappingLabel")}
              placeholder={t("conversion.export.mappingPlaceholder")}
              data={mappings.map((m) => ({
                value: String(m.id),
                label: m.findings.length > 0 ? `${m.name} ${t("conversion.export.mappingStaleSuffix")}` : m.name,
              }))}
              value={mapping ? String(mapping.id) : null}
              onChange={(value) => {
                setPickedId(value === null ? null : Number(value));
                resetOutput();
              }}
              disabled={mappingsLoading || mappings.length === 0 || busy !== null}
              allowDeselect={false}
              searchable
              w={320}
            />
            <Switch
              mt={26}
              label={t("conversion.import.merge")}
              description={t("conversion.import.mergeHint")}
              checked={merge}
              disabled={busy !== null}
              onChange={(event) => {
                setMerge(event.currentTarget.checked);
                resetOutput();
              }}
              maw={420}
            />
          </Group>
          {!mappingsLoading && mappings.length === 0 && (
            <Text size="sm" c="dimmed">
              {t("conversion.export.noMappings")}{" "}
              <Anchor component={RouterLink} to={conversionMappingsPath} size="sm">
                {t("conversion.export.manageMappings")}
              </Anchor>
            </Text>
          )}
          {stale && (
            <Alert color="red" variant="light">
              {t("conversion.export.mappingStaleAlert")}{" "}
              <Anchor component={RouterLink} to={editConversionMappingPath(mapping.id)} size="sm">
                {t("conversion.export.manageMappings")}
              </Anchor>
            </Alert>
          )}
          <Text size="xs" c="dimmed">
            {t("conversion.import.replaceNote")}
            {parsed.documents.length > IMPORT_CHUNK_SIZE && ` ${t("conversion.import.chunkNote", { size: IMPORT_CHUNK_SIZE })}`}
          </Text>
        </Stack>
      </Paper>

      {parsed.errors.length > 0 && (
        <Alert color="red" variant="light" title={t("catalog.import.parseErrorsTitle")}>
          <Stack gap={4}>
            {parsed.errors.map((parseError) => (
              <Text size="sm" key={`${parseError.index}-${parseError.message}`}>
                {t("catalog.import.documentLabel", { number: parseError.index + 1 })}: {parseError.message}
              </Text>
            ))}
          </Stack>
        </Alert>
      )}

      {error && (
        <Alert color="red" variant="light" title={error.title}>
          {error.message}
        </Alert>
      )}

      {progress && (
        <Progress
          value={(progress.sent / progress.total) * 100}
          aria-label={t("ontology.import.progress", { current: progress.sent, total: progress.total })}
        />
      )}

      {conversion && (
        <Stack gap="xs">
          <Title order={3}>{t("conversion.import.conversionTitle")}</Title>
          <ConversionRowsTable
            rows={conversion}
            label={t("conversion.import.tableLabel")}
            fromLabel={t("conversion.import.fromColumn")}
            toLabel={t("conversion.import.toColumn")}
          />
        </Stack>
      )}

      {results && (
        <Stack gap="xs">
          <Title order={3}>{t("conversion.import.resultsTitle")}</Title>
          <OntologyImportResults rows={results.rows} mode={results.mode} showSource={false} />
        </Stack>
      )}

      <Group justify="flex-end">
        <Group gap="xs">
          <Button
            variant="default"
            leftSection={<IconArrowsExchange size={16} />}
            onClick={() => void run("convert")}
            disabled={!ready || busy !== null}
            loading={busy === "convert"}
          >
            {t("conversion.import.convertButton")}
          </Button>
          <Button
            variant="default"
            leftSection={<IconListCheck size={16} />}
            onClick={() => void run("check")}
            disabled={!ready || busy !== null}
            loading={busy === "check"}
          >
            {t("ontology.import.checkButton")}
          </Button>
          <Button
            leftSection={<IconFileImport size={16} />}
            onClick={openConfirm}
            disabled={!ready || busy !== null}
          >
            {t("ontology.import.importButton")}
          </Button>
        </Group>
      </Group>

      {confirmFiles && mapping && (
        <BackstageImportConfirmModal
          convert={() => convertCatalogFiles(mapping.id, confirmFiles, merge)}
          merge={merge}
          sourceLabel={t("conversion.import.sourceLabel")}
          onClose={() => setConfirmFiles(null)}
        />
      )}
    </Stack>
  );
}
