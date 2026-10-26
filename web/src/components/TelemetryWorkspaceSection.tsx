import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { TelemetryCurrent, TelemetryRange } from "../api/telemetry";
import { formatDateTime } from "../utils/relativeTime";
import { formatBytes, formatCount } from "../utils/telemetryFormat";
import { workspaceGroupName, workspaceTableName } from "../utils/telemetryNames";
import { workspaceSets } from "../utils/telemetryTrend";
import TelemetryTrend from "./TelemetryTrend";
import TelemetryValueTable from "./TelemetryValueTable";

/** Workspace: what the project data takes in the database (measured hourly) - the database and each table group - and how many rows of each counted table; two trends (bytes, counts). */
export default function TelemetryWorkspaceSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t, i18n } = useTranslation();
  const { workspace } = current;
  return (
    <Stack gap="md">
      {workspace == null ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.workspace.notYet")}
        </Text>
      ) : (
        <Stack gap="xs">
          <Text size="xs" c="dimmed">
            {t("telemetry.workspace.measuredAt", { time: formatDateTime(workspace.measuredAt, i18n.language) })}
          </Text>
          <TelemetryValueTable
            label={t("telemetry.workspace.sizeAria")}
            rows={[
              { label: t("telemetry.workspace.database"), value: formatBytes(workspace.databaseBytes) },
              ...workspace.groups.map((group) => ({ label: workspaceGroupName(group.name, t), value: formatBytes(group.bytes) })),
            ]}
          />
          <TelemetryValueTable
            label={t("telemetry.workspace.rowsAria")}
            rows={workspace.rows.map((row) => ({ label: workspaceTableName(row.name, t), value: formatCount(row.count) }))}
          />
        </Stack>
      )}
      <TelemetryTrend group="workspace" range={range} sets={workspaceSets} label={t("telemetry.section.workspace")} active={active} />
    </Stack>
  );
}
