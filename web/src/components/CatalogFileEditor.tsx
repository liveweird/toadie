import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Grid, Group, Paper, Stack, Title } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import CatalogFileFormFields from "./CatalogFileFormFields";
import ReferenceCheckPanel from "./ReferenceCheckPanel";
import YamlPreviewCard from "./YamlPreviewCard";
import { toCatalogFileRequest, type CatalogFileFormValues } from "../utils/catalogFileForm";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { useNamespaceOptions } from "../hooks/useNamespaceOptions";

/**
 * The editor shell shared by the create and edit catalog-file pages: the app's document
 * screen — form beside a sticky live YAML preview + reference panel, wider than the
 * Container-sm simple-field forms (see web/CLAUDE.md). The pages own submit/error state.
 */
export default function CatalogFileEditor({
  title,
  submitLabel,
  form,
  onSubmit,
  error,
  submitting,
  showSelfNote,
}: {
  title: string;
  submitLabel: string;
  form: UseFormReturnType<CatalogFileFormValues>;
  onSubmit: (values: CatalogFileFormValues) => Promise<void>;
  error: string | null;
  submitting: boolean;
  showSelfNote?: boolean;
}) {
  const { t } = useTranslation();
  // One mapping per render, shared by the preview and the reference panel. Blank namespace
  // shows as the flagged default here (what will actually be stored); the pages' SUBMIT
  // mapping deliberately keeps it blank — the server resolves authoritatively.
  const { defaultNamespace } = useNamespaceOptions();
  const requestDocument = toCatalogFileRequest(form.values, defaultNamespace);
  return (
    <Grid>
      <Grid.Col span={{ base: 12, md: 7 }}>
        <Paper withBorder shadow="sm" p="xl" radius="md">
          <form onSubmit={form.onSubmit(onSubmit)} noValidate>
            <Stack>
              <Title order={2}>{title}</Title>
              <CatalogFileFormFields form={form} />
              {error && (
                <Alert color="red" variant="light">
                  {error}
                </Alert>
              )}
              <Group justify="flex-end" gap="sm">
                <Button component={RouterLink} to="/catalog-files" variant="default">
                  {t("common.action.cancel")}
                </Button>
                <Button type="submit" loading={submitting}>
                  {submitLabel}
                </Button>
              </Group>
            </Stack>
          </form>
        </Paper>
      </Grid.Col>
      <Grid.Col span={{ base: 12, md: 5 }}>
        <Stack style={{ position: "sticky", top: 72 }}>
          <YamlPreviewCard yaml={catalogInfoYaml(requestDocument)} />
          <ReferenceCheckPanel document={requestDocument} showSelfNote={showSelfNote} />
        </Stack>
      </Grid.Col>
    </Grid>
  );
}
