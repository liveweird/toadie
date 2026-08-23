import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Badge, Code, Group, Select, Stack, Table, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconListCheck } from "@tabler/icons-react";
import { getCrossCheckReport, type CrossCheckStatus } from "../api/catalogFiles";
import EmptyState from "../components/EmptyState";
import TableLoadingRow from "../components/TableLoadingRow";
import { loadErrorMessage } from "../utils/saveError";

const VIEWS = ["errors", "unverifiable", "all"] as const;
type View = (typeof VIEWS)[number];

const isBlockingStatus = (status: CrossCheckStatus) => status !== "UNVERIFIABLE";

export default function CrossCheck() {
  const { t } = useTranslation();
  // Deliberately not persisted: "problems" is the right default every visit.
  const [view, setView] = useState<View>("errors");

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["crossCheck"],
    queryFn: getCrossCheckReport,
  });

  const findings = data?.findings ?? [];
  const errorCount = findings.filter((f) => isBlockingStatus(f.status)).length;
  const shown =
    view === "all"
      ? findings
      : findings.filter((f) =>
          view === "errors" ? isBlockingStatus(f.status) : !isBlockingStatus(f.status),
        );
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
          <Text size="sm" fw={600} c={errorCount > 0 ? "red" : "teal"}>
            {t("crossCheck.summary.errors", { count: errorCount })}
          </Text>
          <Text size="sm" c="dimmed">
            {t("crossCheck.summary.unverifiable", { count: findings.length - errorCount })}
          </Text>
        </Group>
      )}

      <Group>
        <Select
          size="xs"
          w={200}
          aria-label={t("crossCheck.filter.label")}
          data={VIEWS.map((v) => ({ value: v, label: t(`crossCheck.filter.${v}`) }))}
          value={view}
          onChange={(v) => {
            if (v) setView(v as View);
          }}
          allowDeselect={false}
        />
      </Group>

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
          ) : shown.length > 0 ? (
            shown.map((f, index) => (
              <Table.Tr key={`${f.fileId}-${f.field}-${f.reference}-${index}`}>
                <Table.Td>
                  <Anchor
                    component={RouterLink}
                    to={`/catalog-files/${f.fileId}/edit`}
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
                  <Badge variant="light" color={isBlockingStatus(f.status) ? "red" : "gray"}>
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
                  label={
                    view === "errors" ? t("crossCheck.noFindings") : t("crossCheck.noFindingsFiltered")
                  }
                />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
