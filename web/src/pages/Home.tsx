import { Container, Paper, Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";

/**
 * The authenticated landing page. A placeholder while the catalog-info features are built —
 * the first real feature replaces this card with its own entry points.
 */
export default function Home() {
  const { t } = useTranslation();
  return (
    <Container size="sm">
      <Stack gap="sm">
        <Title order={2}>{t("common.home.title")}</Title>
        <Paper shadow="sm" p="xl" withBorder>
          <Text c="dimmed">{t("common.home.placeholder")}</Text>
        </Paper>
      </Stack>
    </Container>
  );
}
