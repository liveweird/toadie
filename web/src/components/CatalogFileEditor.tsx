import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Grid, Group, Paper, Stack, Title } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import CatalogFileFormFields from "./CatalogFileFormFields";
import PageHeader from "./PageHeader";
import ReferenceCheckPanel from "./ReferenceCheckPanel";
import YamlPreviewCard from "./YamlPreviewCard";
import { toCatalogFileRequest, type CatalogFileFormValues } from "../utils/catalogFileForm";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { useNamespaceOptions } from "../hooks/useNamespaceOptions";
import { useDocumentCheck } from "../hooks/useDocumentCheck";
import { indexFindings } from "../utils/fieldFindings";
import { catalogFilesPath } from "../utils/catalogFileLinks";
import classes from "../theme.module.css";

/**
 * The editor shell shared by the create and edit catalog-file pages: the app's document
 * screen — a PageHeader (title, back link, the whole-file operations as its actions), then
 * the form beside a sticky live YAML preview + findings panel, wider than the simple-field
 * forms (see web/CLAUDE.md). The form's Cancel/Save bar is STICKY at the bottom of the
 * viewport (v1.20.0), so a long document never hides its Save; the sections are flat
 * fieldsets under small legends rather than boxes. No tabs: the field-level finding marks
 * must stay on screen and a blocked submit must never sit on a hidden tab. The pages own
 * submit/error state.
 */
export default function CatalogFileEditor({
  title,
  submitLabel,
  form,
  onSubmit,
  error,
  submitting,
  back,
  actions,
  banner,
  history,
}: {
  title: string;
  submitLabel: string;
  form: UseFormReturnType<CatalogFileFormValues>;
  onSubmit: (values: CatalogFileFormValues) => Promise<void>;
  error: string | null;
  submitting: boolean;
  /** The header's back link (to the Files list). */
  back?: { to: string; label: string };
  /**
   * Whole-file operations (export/overwrite/sync) — edit-only, since they all act on a
   * STORED file. Rendered as the PageHeader's actions, outside the form's submit path.
   */
  actions?: ReactNode;
  /** A page-level notice under the header (the edit page's download-error alert). */
  banner?: ReactNode;
  /**
   * The file's change history — edit-only like [actions] (a document that isn't stored yet has
   * none). Full width BELOW the two columns: it is a record of the document above, not a
   * companion to the form, and the tab strip a two-tab section would need ("Document" +
   * "History") would only name what is already on screen.
   */
  history?: ReactNode;
}) {
  const { t } = useTranslation();
  // One mapping per render, shared by the preview and the reference panel. Blank namespace
  // shows as the flagged default here (what will actually be stored); the pages' SUBMIT
  // mapping deliberately keeps it blank — the server resolves authoritatively.
  const { defaultNamespace } = useNamespaceOptions();
  const requestDocument = toCatalogFileRequest(form.values, defaultNamespace);
  // ONE live check for both consumers: the panel lists the findings, the field block puts
  // each on the control that produced it.
  const { findings, checked } = useDocumentCheck(requestDocument);
  const fieldFindings = indexFindings(findings);
  return (
    <Stack gap="md">
      <PageHeader title={title} backTo={back} actions={actions} />
      {banner}
      <Grid>
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Paper withBorder p="lg" radius="md">
            <form onSubmit={form.onSubmit(onSubmit)} noValidate>
              <Stack>
                <CatalogFileFormFields form={form} findings={fieldFindings} />
                {error && (
                  <Alert color="red" variant="light">
                    {error}
                  </Alert>
                )}
                <Group justify="flex-end" gap="sm" className={classes.stickyActions}>
                  <Button component={RouterLink} to={catalogFilesPath} variant="default">
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
          <Stack className={classes.stickyAside}>
            <YamlPreviewCard yaml={catalogInfoYaml(requestDocument)} />
            <ReferenceCheckPanel findings={findings} checked={checked} />
          </Stack>
        </Grid.Col>
        {history && (
          <Grid.Col span={12}>
            <Paper withBorder p="lg" radius="md">
              <Stack>
                <Title order={3}>{t("catalog.history.title")}</Title>
                {history}
              </Stack>
            </Paper>
          </Grid.Col>
        )}
      </Grid>
    </Stack>
  );
}
