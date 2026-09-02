import { Code, Paper, ScrollArea, Stack, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";

/**
 * The live catalog-info.yaml pane beside the editor form. The editor pages wrap it (plus the
 * ReferenceCheckPanel) in a sticky Stack so the whole column stays in view while the (long)
 * form scrolls.
 */
export default function YamlPreviewCard({ yaml, embedded }: { yaml: string; embedded?: boolean }) {
  const { t } = useTranslation();
  const body = (
    <Stack gap="sm">
      <Title order={3}>{t("catalog.preview")}</Title>
      {/* A keyboard-focusable scroll region (the YamlDiffView rule): long YAML overflows the
          drawer's width, and a scrollable region must be reachable by keyboard. */}
      <ScrollArea.Autosize mah="70vh" viewportProps={{ tabIndex: 0 }}>
        <Code block aria-label={t("catalog.preview")}>
          {yaml}
        </Code>
      </ScrollArea.Autosize>
    </Stack>
  );
  // `embedded` = no card of its own — a host (the quick-view drawer) supplies the surface.
  return embedded ? body : (
    <Paper withBorder p="md" radius="md">
      {body}
    </Paper>
  );
}
