import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Alert, Button, Grid, Group, Paper, Stack, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { createCatalogFile } from "../api/catalogFiles";
import CatalogFileFormFields from "../components/CatalogFileFormFields";
import YamlPreviewCard from "../components/YamlPreviewCard";
import {
  catalogFileFormValidation,
  EMPTY_CATALOG_FILE_FORM,
  toCatalogFileRequest,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

export default function CreateCatalogFile() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: EMPTY_CATALOG_FILE_FORM,
    validate: catalogFileFormValidation(t),
  });

  async function onSubmit(values: CatalogFileFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await createCatalogFile(toCatalogFileRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      showSuccessToast(t("catalog.toast.created"));
      navigate("/catalog-files", { replace: true });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          invalid: "catalog.validationError",
          conflict: "catalog.conflictError",
          failedStatus: "catalog.createFailedStatus",
          failed: "catalog.createFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    // The editor is the app's first document screen: form + live YAML side by side, wider
    // than the Container-sm simple-field forms (see web/CLAUDE.md).
    <Grid>
      <Grid.Col span={{ base: 12, md: 7 }}>
        <Paper withBorder shadow="sm" p="xl" radius="md">
          <form onSubmit={form.onSubmit(onSubmit)} noValidate>
            <Stack>
              <Title order={2}>{t("catalog.createFile")}</Title>
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
                  {t("common.action.create")}
                </Button>
              </Group>
            </Stack>
          </form>
        </Paper>
      </Grid.Col>
      <Grid.Col span={{ base: 12, md: 5 }}>
        <YamlPreviewCard yaml={catalogInfoYaml(toCatalogFileRequest(form.values))} />
      </Grid.Col>
    </Grid>
  );
}
