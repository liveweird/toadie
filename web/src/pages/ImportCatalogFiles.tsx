import { useMemo, useState } from "react";
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
  Textarea,
  Title,
} from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconArrowLeft, IconFileImport, IconUpload } from "@tabler/icons-react";
import { importCatalogFiles, type ImportFileResult } from "../api/catalogFiles";
import { parseCatalogYaml } from "../utils/catalogImport";
import { saveErrorMessage } from "../utils/saveError";

const STATUS_COLOR: Record<ImportFileResult["status"], string> = {
  CREATED: "teal",
  CONFLICT: "yellow",
  INVALID: "red",
  ERROR: "red",
};

export default function ImportCatalogFiles() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [text, setText] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [results, setResults] = useState<ImportFileResult[] | null>(null);

  const parsed = useMemo(() => parseCatalogYaml(text), [text]);
  const canImport = parsed.documents.length > 0 && parsed.errors.length === 0;

  async function handleFile(file: File | null) {
    if (!file) return;
    setText(await file.text());
    setResults(null);
  }

  async function handleImport() {
    setSubmitting(true);
    setSubmitError(null);
    try {
      const response = await importCatalogFiles(parsed.documents);
      setResults(response.results);
      // Everything derived from the store refreshes: list, identities, graph, cross-check.
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

  const createdCount = results?.filter((r) => r.status === "CREATED").length ?? 0;

  return (
    <Stack gap="md">
      <Title order={2}>{t("catalog.import.title")}</Title>
      <Text size="sm" c="dimmed">
        {t("catalog.import.intro")}
      </Text>

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
          <Table withTableBorder verticalSpacing="sm">
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
          to="/catalog-files"
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
