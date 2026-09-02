import { Alert, Code, Paper, Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DocumentCheckFinding } from "../api/catalogFiles";

/**
 * The editor's findings list — the aggregate view beside the form. Each finding also shows on
 * the control that produced it (see `utils/fieldFindings.ts`); this panel stays because it
 * catches what the fields cannot: findings whose field is scrolled out of view, and any
 * `field` the client does not know how to route.
 *
 * A dumb renderer: the check itself runs once in the editor shell (`useDocumentCheck`) and is
 * shared with the field block, so the two never issue separate requests.
 */
export default function ReferenceCheckPanel({
  findings,
  checked,
  embedded,
}: {
  findings: DocumentCheckFinding[];
  /** True once a check has answered — the all-clear line must not flash before the first. */
  checked: boolean;
  /** No card of its own — a host (the quick-view drawer) supplies the surface. */
  embedded?: boolean;
}) {
  const { t } = useTranslation();
  const body = (
    <Stack gap="sm">
      <Title order={3}>{t("errors.panel.title")}</Title>
      {findings.length > 0 ? (
        <Alert color="orange" variant="light" title={t("errors.panel.errorsTitle")}>
          <Stack gap={4}>
            {findings.map((f, index) => (
              <Text size="sm" key={`${f.field}-${f.reference}-${index}`}>
                <Code>{f.reference}</Code> ({f.field}) — {t(`errors.message.${f.status}`)}
              </Text>
            ))}
          </Stack>
        </Alert>
      ) : checked ? (
        <Text size="sm" c="dimmed">
          {t("errors.panel.allClear")}
        </Text>
      ) : null}
    </Stack>
  );
  return embedded ? body : (
    <Paper withBorder p="md" radius="md">
      {body}
    </Paper>
  );
}
