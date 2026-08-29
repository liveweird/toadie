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
  Title,
} from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconArrowLeft, IconDownload, IconFileImport, IconUpload } from "@tabler/icons-react";
import { fetchCatalogUrl, importCatalogFiles, type ImportFileResult } from "../api/catalogFiles";
import { normalizeCatalogUrl, parseCatalogYaml } from "../utils/catalogImport";
import { saveErrorMessage } from "../utils/saveError";
import { catalogFilesPath } from "../utils/catalogFileLinks";

const STATUS_COLOR: Record<ImportFileResult["status"], string> = {
  CREATED: "teal",
  // Stored, but carrying waived findings the cross-check page tracks — a caution, not a failure.
  CREATED_WITH_FINDINGS: "orange",
  CONFLICT: "yellow",
  INVALID: "red",
  ERROR: "red",
};

export default function ImportCatalogFiles() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [text, setText] = useState("");
  const [url, setUrl] = useState("");
  const [fetching, setFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [results, setResults] = useState<ImportFileResult[] | null>(null);

  // Parsing runs on the DEBOUNCED text — a multi-document paste re-parsed per keystroke is
  // wasted work; the summary/errors lag typing by the debounce, and the submit path re-parses
  // the live text so a click never imports a stale slice.
  const [debouncedText] = useDebouncedValue(text, 300);
  const parsed = useMemo(() => parseCatalogYaml(debouncedText), [debouncedText]);
  const canImport = parsed.documents.length > 0 && parsed.errors.length === 0;

  async function handleFile(file: File | null) {
    if (!file) return;
    setText(await file.text());
    setResults(null);
  }

  // Server-side fetch (any reachable public host, not just CORS-friendly ones); blob-style
  // Git-hosting links are rewritten to their raw form first.
  async function handleFetchUrl() {
    setFetching(true);
    setFetchError(null);
    try {
      const fetched = await fetchCatalogUrl(normalizeCatalogUrl(url));
      setText(fetched.content);
      setResults(null);
    } catch (err) {
      setFetchError(
        saveErrorMessage(err, t, {
          invalid: "catalog.import.urlInvalid",
          failedStatus: "catalog.import.urlFailedStatus",
          failed: "catalog.import.urlFailedNetwork",
        }),
      );
    } finally {
      setFetching(false);
    }
  }

  async function handleImport() {
    // Re-parse the LIVE text: the debounced parse above may lag a just-typed edit.
    const current = parseCatalogYaml(text);
    if (current.documents.length === 0 || current.errors.length > 0) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const response = await importCatalogFiles(current.documents);
      setResults(response.results);
      // The ["catalogFiles"] prefix covers every catalog-derived query: the list pages,
      // the reference-picker identities pool, the cross-check report, the graph, and the
      // editor's live check — all refetch after an import.
      await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
    } catch (err) {
      setSubmitError(
        saveErrorMessage(err, t, {
          invalid: "catalog.import.tooMany",
          failedStatus: "catalog.import.failedStatus",
          failed: "catalog.import.failedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  // Both stored statuses count as imported — the summary reports what landed.
  const createdCount =
    results?.filter((r) => r.status === "CREATED" || r.status === "CREATED_WITH_FINDINGS").length ?? 0;

  return (
    <Stack gap="md">
      <Title order={2}>{t("catalog.import.title")}</Title>
      <Text size="sm" c="dimmed">
        {t("catalog.import.intro")}
      </Text>

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

      <Textarea
        label={t("catalog.import.textareaLabel")}
        placeholder={t("catalog.import.placeholder")}
        value={text}
        onChange={(event) => {
          setText(event.currentTarget.value);
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
            {t("catalog.import.resultSummary", { created: createdCount, total: results.length })}
          </Text>
          <Table withTableBorder>
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
              {results.map((result) => (
                <Table.Tr key={result.index}>
                  <Table.Td>
                    <Badge variant="light" size="sm">
                      {result.kind}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{result.namespace}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" fw={500}>
                      {result.name}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" size="sm" color={STATUS_COLOR[result.status]}>
                      {t(`catalog.import.status.${result.status}`)}
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
        <Button
          leftSection={<IconFileImport size={16} />}
          onClick={() => void handleImport()}
          disabled={!canImport}
          loading={submitting}
        >
          {t("catalog.import.importButton")}
        </Button>
      </Group>
    </Stack>
  );
}
