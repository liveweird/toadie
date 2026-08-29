import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Badge, Code, Group, Stack, Table, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconListCheck } from "@tabler/icons-react";
import { getCrossCheckReport } from "../api/catalogFiles";
import EmptyState from "../components/EmptyState";
import TableLoadingRow from "../components/TableLoadingRow";
import { loadErrorMessage } from "../utils/saveError";
import { editCatalogFilePath } from "../utils/catalogFileLinks";

// Saves enforce resolution, so every finding is an error (dangling refs arise from
// deletions) — the report needs no severity filter anymore.
export default function CrossCheck() {
  const { t } = useTranslation();

  const { data, isLoading, isError, error } = useQuery({
    // Under the "catalogFiles" prefix so every catalog mutation's invalidation refreshes it.
    queryKey: ["catalogFiles", "crossCheck"],
    queryFn: getCrossCheckReport,
  });

  const findings = data?.findings ?? [];
  const columnCount = 5;

  return (
    <Stack gap="md">
      <Title order={2}>{t("crossCheck.title")}</Title>

      {data && (
        <Group gap="lg">
          <Text size="sm" c="dimmed">
            {t("crossCheck.summary.files", { count: data.checkedFiles })}
          </Text>
          <Text size="sm" c="dimmed">
            {t("crossCheck.summary.references", { count: data.checkedReferences })}
          </Text>
          <Text size="sm" fw={600} c={findings.length > 0 ? "red" : "teal"}>
            {t("crossCheck.summary.errors", { count: findings.length })}
          </Text>
        </Group>
      )}

      {isError && (
        <Alert color="red" variant="light" title={t("crossCheck.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("crossCheck.field.file")}</Table.Th>
            <Table.Th>{t("crossCheck.field.namespace")}</Table.Th>
            <Table.Th>{t("crossCheck.field.refField")}</Table.Th>
            <Table.Th>{t("crossCheck.field.reference")}</Table.Th>
            <Table.Th>{t("crossCheck.field.status")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : findings.length > 0 ? (
            findings.map((f, index) => (
              <Table.Tr key={`${f.fileId}-${f.field}-${f.reference}-${index}`}>
                <Table.Td>
                  <Anchor
                    component={RouterLink}
                    to={editCatalogFilePath(f.fileId)}
                    size="sm"
                    fw={500}
                    aria-label={t("common.action.editAria", { name: f.fileName })}
                  >
                    {f.fileName}
                  </Anchor>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{f.fileNamespace}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{f.field}</Text>
                </Table.Td>
                <Table.Td>
                  <Code>{f.reference}</Code>
                </Table.Td>
                <Table.Td>
                  <Badge variant="light" color="red">
                    {t(`crossCheck.status.${f.status}`)}
                  </Badge>
                  <Text size="xs" c="dimmed" mt={2}>
                    {t(`crossCheck.message.${f.status}`)}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState
                  icon={<IconListCheck size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
                  label={t("crossCheck.noFindings")}
                />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
