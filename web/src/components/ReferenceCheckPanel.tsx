import { Alert, Code, Paper, Stack, Text, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { checkCatalogFile, type CatalogFileRequest } from "../api/catalogFiles";

/**
 * The editor's live check: the current form document, debounced, against the stored files
 * AND the registries (POST /api/v1/files/check — references plus label/annotation/
 * tag/type/lifecycle findings). Every finding here makes a strict save ask for the
 * Save-anyway confirmation; errors of the check request itself render nothing.
 */
export default function ReferenceCheckPanel({ document }: { document: CatalogFileRequest }) {
  const { t } = useTranslation();
  const json = JSON.stringify(document);
  const [debounced] = useDebouncedValue(json, 500);

  const { data } = useQuery({
    // Under the "catalogFiles" prefix so catalog mutations refresh a live check; keyed on
    // the debounced document with gcTime 0 — superseded documents' entries are dropped as
    // soon as the key moves on, so typing never accumulates cache entries.
    queryKey: ["catalogFiles", "check", debounced],
    queryFn: () => checkCatalogFile(JSON.parse(debounced) as CatalogFileRequest),
    placeholderData: keepPreviousData,
    gcTime: 0,
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
      </Stack>
    </Paper>
  );
}
