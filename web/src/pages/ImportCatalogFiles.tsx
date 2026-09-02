import { useMemo, useState } from "react";
import { useDebouncedValue } from "@mantine/hooks";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import {
  Alert,
  Badge,
  Button,
  FileButton,
  Group,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
} from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconArrowLeft, IconDownload, IconFileImport, IconListCheck, IconUpload } from "@tabler/icons-react";
import {
  checkImportCatalogFiles,
  fetchCatalogUrl,
  importCatalogFiles,
  type ImportFileResult,
} from "../api/catalogFiles";
import CatalogFileNameLink from "../components/CatalogFileNameLink";
import { normalizeCatalogUrl, parseCatalogYaml } from "../utils/catalogImport";
import { FETCH_URL_ERROR_KEYS, saveErrorMessage } from "../utils/saveError";
import { catalogFilesPath } from "../utils/catalogFileLinks";
import KindBadge from "../components/KindBadge";
import PageHeader from "../components/PageHeader";

const STATUS_COLOR: Record<ImportFileResult["status"], string> = {
  CREATED: "teal",
  // Stored, but carrying waived findings the Errors page tracks — a caution, not a failure.
  CREATED_WITH_FINDINGS: "orange",
  // Nothing was stored — as blocking as INVALID, so the same red (not a softer third hue).
  CONFLICT: "red",
  INVALID: "red",
  ERROR: "red",
};

export default function ImportCatalogFiles() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [text, setText] = useState("");
  const [url, setUrl] = useState("");
  const [fetching, setFetching] = useState(false);
  // The normalized URL the CURRENT text came from — set on a successful fetch, cleared the
  // moment the text changes by typing or file pick. Imports pass it as the batch's source
  // reference (every stored row starts synced); an edited/pasted batch carries none.
  const [fetchedFrom, setFetchedFrom] = useState<string | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [checking, setChecking] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  // The mode discriminates the report: a dry-run's rows are PREDICTIONS ("Would be
  // created"), never to be mislabeled as stored.
  const [results, setResults] = useState<{ mode: "import" | "check"; rows: ImportFileResult[] } | null>(null);

  // Parsing runs on the DEBOUNCED text — a multi-document paste re-parsed per keystroke is
  // wasted work; the summary/errors lag typing by the debounce, and the submit path re-parses
  // the live text so a click never imports a stale slice.
  const [debouncedText] = useDebouncedValue(text, 300);
  const parsed = useMemo(() => parseCatalogYaml(debouncedText), [debouncedText]);
  const canImport = parsed.documents.length > 0 && parsed.errors.length === 0;

  async function handleFile(file: File | null) {
    if (!file) return;
    setText(await file.text());
    setFetchedFrom(null);
    setResults(null);
  }

  // Server-side fetch (any reachable public host, not just CORS-friendly ones); blob-style
  // Git-hosting links are rewritten to their raw form first.
  async function handleFetchUrl() {
    setFetching(true);
    setFetchError(null);
    try {
      const normalized = normalizeCatalogUrl(url);
      const fetched = await fetchCatalogUrl(normalized);
      setText(fetched.content);
      setFetchedFrom(normalized);
      setResults(null);
    } catch (err) {
      setFetchError(saveErrorMessage(err, t, FETCH_URL_ERROR_KEYS));
    } finally {
      setFetching(false);
    }
  }

  async function runBatch(mode: "import" | "check") {
    // Re-parse the LIVE text: the debounced parse above may lag a just-typed edit.
    const current = parseCatalogYaml(text);
    if (current.documents.length === 0 || current.errors.length > 0) return;
    const setBusy = mode === "import" ? setSubmitting : setChecking;
    setBusy(true);
    setSubmitError(null);
    try {
      const response =
        mode === "import"
          ? await importCatalogFiles(current.documents, fetchedFrom ?? undefined)
          : await checkImportCatalogFiles(current.documents);
      setResults({ mode, rows: response.results });
      if (mode === "import") {
        // The ["catalogFiles"] prefix covers every catalog-derived query: the list pages,
        // the reference-picker identities pool, the Errors report, the graph, and the
        // editor's live check — all refetch after an import. A dry-run changed nothing.
        await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      }
    } catch (err) {
      setSubmitError(
        saveErrorMessage(err, t, {
          invalid: "catalog.import.tooMany",
          failedStatus: "catalog.import.failedStatus",
          failed: "catalog.import.failedNetwork",
        }),
      );
    } finally {
      setBusy(false);
    }
  }

  // Both stored statuses count as imported (or would-import) — the summary reports them.
  const createdCount =
    results?.rows.filter((r) => r.status === "CREATED" || r.status === "CREATED_WITH_FINDINGS").length ?? 0;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("catalog.import.title")}
        description={t("catalog.import.intro")}
        backTo={{ to: catalogFilesPath, label: t("catalog.backToList") }}
      />

      <Group align="flex-end" gap="xs">
        <TextInput
          label={t("catalog.import.urlLabel")}
          placeholder="https://raw.githubusercontent.com/acme/service/main/catalog-info.yaml"
          value={url}
          onChange={(event) => setUrl(event.currentTarget.value)}
          style={{ flex: 1 }}
        />
        <Button
          variant="default"
          leftSection={<IconDownload size={16} />}
          onClick={() => void handleFetchUrl()}
          disabled={!url.trim()}
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

      {fetchedFrom != null && (
        <Text size="sm" c="dimmed">
          {t("catalog.import.sourceHint", { url: fetchedFrom })}
        </Text>
      )}

      <Textarea
        label={t("catalog.import.textareaLabel")}
        placeholder={t("catalog.import.placeholder")}
        value={text}
        onChange={(event) => {
          setText(event.currentTarget.value);
          setFetchedFrom(null);
          setResults(null);
        }}
        autosize
        minRows={10}
        maxRows={24}
        styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
      />

      <Group justify="space-between">
        <FileButton onChange={(file) => void handleFile(file)} accept=".yaml,.yml,text/yaml">
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

      {parsed.errors.length > 0 && (
        <Alert color="red" variant="light" title={t("catalog.import.parseErrorsTitle")}>
          <Stack gap={4}>
            {parsed.errors.map((error) => (
              <Text size="sm" key={`${error.index}-${error.message}`}>
                {t("catalog.import.documentLabel", { number: error.index + 1 })}: {error.message}
              </Text>
            ))}
          </Stack>
        </Alert>
      )}

      {submitError && (
        <Alert color="red" variant="light" title={t("catalog.import.failedTitle")}>
          {submitError}
        </Alert>
      )}

      {results && (
        <Stack gap="xs">
          <Text size="sm" fw={500}>
            {t(
              results.mode === "check"
                ? "catalog.import.checkResultSummary"
                : "catalog.import.resultSummary",
              { created: createdCount, total: results.rows.length },
            )}
          </Text>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("catalog.field.kind")}</Table.Th>
                <Table.Th>{t("catalog.field.namespace")}</Table.Th>
                <Table.Th>{t("common.field.name")}</Table.Th>
                <Table.Th>{t("catalog.import.statusHeader")}</Table.Th>
                <Table.Th>{t("catalog.import.messageHeader")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {results.rows.map((result) => (
                <Table.Tr key={result.index}>
                  <Table.Td>
                    <KindBadge kind={result.kind} />
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{result.namespace}</Text>
                  </Table.Td>
                  <Table.Td>
                    {/* A stored row links straight to its editor — the WITH_FINDINGS ones are
                        exactly the files the reader goes on to fix. Dry-run rows and skipped
                        rows have no fileId (nothing was stored). */}
                    {result.fileId != null ? (
                      <CatalogFileNameLink id={result.fileId} name={result.name} />
                    ) : (
                      <Text size="sm" fw={500}>
                        {result.name}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" size="sm" color={STATUS_COLOR[result.status]}>
                      {t(
                        results.mode === "check"
                          ? `catalog.import.checkStatus.${result.status}`
                          : `catalog.import.status.${result.status}`,
                      )}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {result.message ?? ""}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      )}

      <Group justify="space-between">
        <Button
          component={RouterLink}
          to={catalogFilesPath}
          variant="default"
          leftSection={<IconArrowLeft size={16} />}
        >
          {t("catalog.backToList")}
        </Button>
        <Group gap="xs">
          <Button
            variant="default"
            leftSection={<IconListCheck size={16} />}
            onClick={() => void runBatch("check")}
            disabled={!canImport}
            loading={checking}
          >
            {t("catalog.import.checkButton")}
          </Button>
          <Button
            leftSection={<IconFileImport size={16} />}
            onClick={() => void runBatch("import")}
            disabled={!canImport}
            loading={submitting}
          >
            {t("catalog.import.importButton")}
          </Button>
        </Group>
      </Group>
    </Stack>
  );
}
