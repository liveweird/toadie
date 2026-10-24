import { useTranslation } from "react-i18next";
import { Alert, Button, Stack, Text } from "@mantine/core";

export type GraphLayoutGateProps =
  | { kind: "computing" }
  | { kind: "failed"; timedOut: boolean; onRetry: () => void };

/**
 * What both graph canvases show INSTEAD of the canvas while there is nothing to draw yet: the
 * layout still computing in the worker (a named status — visible text, never an `aria-label`
 * on a bare span), or it failed/timed out (a red inline Alert with Retry and the narrowing
 * hint). Shared so Graph and EntityGraph cannot drift.
 */
export default function GraphLayoutGate(props: GraphLayoutGateProps) {
  const { t } = useTranslation();
  if (props.kind === "computing") {
    return (
      <Text role="status" size="sm" c="dimmed" style={{ flexShrink: 0 }}>
        {t("common.graphLayout.computing")}
      </Text>
    );
  }
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
