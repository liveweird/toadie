import { Anchor, Container, Stack, Text, Title } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";

/**
 * The authenticated catch-all (v2.22.0) — before it, an unmatched URL matched no route at
 * all, so the Shell layout never mounted and the user got a completely blank document.
 * Registered as the last `path="*"` child of the Shell route; deliberately not feature-gated.
 */
export default function NotFound() {
  const { t } = useTranslation();
  return (
    <Container size="sm">
      <Stack gap="sm">
        <Title order={2}>{t("common.notFound.title")}</Title>
        <Text c="dimmed">{t("common.notFound.message")}</Text>
        <Anchor component={RouterLink} to="/">
          {t("common.notFound.backHome")}
        </Anchor>
      </Stack>
    </Container>
  );
}
