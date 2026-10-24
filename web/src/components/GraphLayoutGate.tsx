import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Paper, Stack, Text, Title } from "@mantine/core";

export type GraphLayoutGateProps =
  | { kind: "computing" }
  | { kind: "failed"; timedOut: boolean; onRetry: () => void }
  | {
      kind: "large";
      nodes: number;
      edges: number;
      /** False when there is nothing to fold (no hierarchy) or the top-level view is active. */
      canShowTopLevel: boolean;
      onShowTopLevel: () => void;
      onShowAll: () => void;
    };

/**
 * What both graph canvases show INSTEAD of the canvas while there is nothing to draw yet: the
 * layout still computing in the worker (a named status — visible text, never an `aria-label`
 * on a bare span), it failed/timed out (a red inline Alert with Retry and the narrowing hint),
 * or the large-graph gate (`utils/graphGate.ts`) is asking before it lays out a graph with too
 * many nodes. Shared so Graph and EntityGraph cannot drift.
 */
export default function GraphLayoutGate(props: GraphLayoutGateProps) {
  const { t } = useTranslation();
  const titleId = useId();
  if (props.kind === "computing") {
    return (
      <Text role="status" size="sm" c="dimmed" style={{ flexShrink: 0 }}>
        {t("common.graphLayout.computing")}
      </Text>
    );
  }
  if (props.kind === "failed") {
    return (
      <Alert color="red" variant="light" title={t("common.graphLayout.failedTitle")} style={{ flexShrink: 0 }}>
        <Stack gap="xs" align="flex-start">
          <Text size="sm">{t(props.timedOut ? "common.graphLayout.timedOut" : "common.graphLayout.error")}</Text>
          <Button variant="default" size="xs" onClick={props.onRetry}>
            {t("common.graphLayout.retry")}
          </Button>
        </Stack>
      </Alert>
    );
  }
  return (
    <Paper withBorder radius="md" p="lg" role="region" aria-labelledby={titleId} style={{ flexShrink: 0 }}>
      <Stack gap="sm" align="flex-start">
        <Title order={3} id={titleId}>
          {t("common.graphLayout.large.title")}
        </Title>
        <Text size="sm">{t("common.graphLayout.large.body", { nodes: props.nodes, edges: props.edges })}</Text>
        <Group gap="xs">
          {props.canShowTopLevel && (
            <Button size="xs" onClick={props.onShowTopLevel}>
              {t("common.graphLayout.large.showTopLevel")}
            </Button>
          )}
          <Button size="xs" variant="default" onClick={props.onShowAll}>
            {t("common.graphLayout.large.showAll")}
          </Button>
        </Group>
        <Text size="xs" c="dimmed">
          {t("common.graphLayout.large.hint")}
        </Text>
      </Stack>
    </Paper>
  );
}
