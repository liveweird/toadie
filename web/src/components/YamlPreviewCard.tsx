import { Code, Paper, ScrollArea, Stack, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";

/**
 * The live catalog-info.yaml pane beside the editor form — sticky so the document stays in
 * view while the (long) form scrolls.
 */
export default function YamlPreviewCard({ yaml }: { yaml: string }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder shadow="sm" p="lg" radius="md" style={{ position: "sticky", top: 72 }}>
      <Stack gap="sm">
        <Title order={3}>{t("catalog.preview")}</Title>
        <ScrollArea.Autosize mah="70vh">
          <Code block aria-label={t("catalog.preview")}>
            {yaml}
          </Code>
        </ScrollArea.Autosize>
      </Stack>
    </Paper>
  );
}
