import { Alert, Code, Paper, Stack, Text, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { checkCatalogFile, type CatalogFileRequest } from "../api/catalogFiles";

/**
 * The editor's live reference check: the current form document, debounced, against the stored
 * files (POST /api/v1/catalog-files/check). Saves ENFORCE resolution, so every finding here
 * means the save will be rejected; errors of the check request itself render nothing.
 */
export default function ReferenceCheckPanel({
  document,
  showSelfNote = false,
}: {
  document: CatalogFileRequest;
  /** Create page only: an unsaved file's self-references read as missing until first save. */
  showSelfNote?: boolean;
}) {
  const { t } = useTranslation();
  const json = JSON.stringify(document);
  const [debounced] = useDebouncedValue(json, 500);

  const { data } = useQuery({
    queryKey: ["catalogFileCheck", debounced],
    queryFn: () => checkCatalogFile(JSON.parse(debounced) as CatalogFileRequest),
    placeholderData: keepPreviousData,
  });

  const findings = data?.findings ?? [];

  return (
    <Paper withBorder shadow="sm" p="lg" radius="md">
      <Stack gap="sm">
        <Title order={3}>{t("crossCheck.panel.title")}</Title>
        {findings.length > 0 ? (
          <Alert color="red" variant="light" title={t("crossCheck.panel.errorsTitle")}>
            <Stack gap={4}>
              {findings.map((f, index) => (
                <Text size="sm" key={`${f.field}-${f.reference}-${index}`}>
                  <Code>{f.reference}</Code> ({f.field}) — {t(`crossCheck.message.${f.status}`)}
                </Text>
              ))}
            </Stack>
          </Alert>
        ) : data ? (
          <Text size="sm" c="dimmed">
            {t("crossCheck.panel.allClear")}
          </Text>
        ) : null}
        {showSelfNote && (
          <Text size="xs" c="dimmed">
            {t("crossCheck.panel.selfNote")}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}
