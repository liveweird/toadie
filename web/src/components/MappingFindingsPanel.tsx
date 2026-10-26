import { Alert, Badge, Group, Paper, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { MappingDrift, MappingFinding } from "../api/conversionMappings";

type Entry = { code: MappingFinding["code"] | MappingDrift["code"]; path: string; message: string };

/**
 * A mapping's blocking findings (red — HARD on its next save) and report-only drift (orange),
 * each with its code label, the JSON pointer into the document and the server's message. The
 * code vocabulary is the Errors report's (`entityErrors.mapping.*`, phase 1) — one wording for
 * the same finding on both surfaces. `findings` is the stored mapping's recomputed set OR the
 * just-refused save's (the editor passes whichever applies, `rejected` choosing the title).
 */
export default function MappingFindingsPanel({
  findings,
  drift,
  rejected,
}: {
  findings: readonly MappingFinding[];
  drift: readonly MappingDrift[];
  /** The findings are a just-refused save's, not the stored mapping's. */
  rejected: boolean;
}) {
  const { t } = useTranslation();
  if (findings.length === 0 && drift.length === 0) {
    return (
      <Paper withBorder p="md" radius="md">
        <Text size="sm" c="dimmed">
          {t("conversion.mappings.findings.clean")}
        </Text>
      </Paper>
    );
  }
  return (
    <Stack gap="sm">
      {findings.length > 0 && (
        <Alert
          color="red"
          variant="light"
          title={rejected ? t("conversion.mappings.findings.rejectedTitle") : t("conversion.mappings.findings.storedTitle")}
        >
          <FindingList entries={findings} kind="finding" />
        </Alert>
      )}
      {drift.length > 0 && (
        <Alert color="orange" variant="light" title={t("conversion.mappings.findings.driftTitle")}>
          <FindingList entries={drift} kind="drift" />
        </Alert>
      )}
    </Stack>
  );
}

function FindingList({ entries, kind }: { entries: readonly Entry[]; kind: "finding" | "drift" }) {
  const { t } = useTranslation();
  return (
    <Stack gap={6} component="ul" m={0} p={0} style={{ listStyle: "none" }}>
      {entries.map((entry, index) => (
        <li key={`${kind}-${entry.code}-${entry.path}-${index}`}>
          <Group gap={6} wrap="nowrap" align="flex-start">
            <Badge variant="light" size="xs" color={kind === "finding" ? "red" : "orange"} style={{ flexShrink: 0 }}>
              {t(`entityErrors.mapping.code.${entry.code}`)}
            </Badge>
            <Text size="xs" c="dimmed" ff="monospace" style={{ flexShrink: 0 }}>
              {entry.path || t("conversion.mappings.findings.root")}
            </Text>
            <Text size="xs">{entry.message}</Text>
          </Group>
        </li>
      ))}
    </Stack>
  );
}
