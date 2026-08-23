import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate, useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Center, Grid, Group, Loader, Paper, Stack, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getCatalogFile, updateCatalogFile } from "../api/catalogFiles";
import { ApiError } from "../api/http";
import CatalogFileFormFields from "../components/CatalogFileFormFields";
import YamlPreviewCard from "../components/YamlPreviewCard";
import {
  catalogFileFormValidation,
  EMPTY_CATALOG_FILE_FORM,
  fromCatalogFileResponse,
  toCatalogFileRequest,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

export default function EditCatalogFile() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: EMPTY_CATALOG_FILE_FORM,
    validate: catalogFileFormValidation(t),
  });

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["catalogFile", id],
    queryFn: () => getCatalogFile(id),
    enabled: idIsValid,
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize(fromCatalogFileResponse(data));
  }

  if (!idIsValid) return <Navigate to="/catalog-files" replace />;

  async function onSubmit(values: CatalogFileFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateCatalogFile(id, toCatalogFileRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      await queryClient.invalidateQueries({ queryKey: ["catalogFile", id] });
      showSuccessToast(t("catalog.toast.updated"));
      navigate("/catalog-files", { replace: true });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          notFound: "catalog.fileGone",
          invalid: "catalog.validationError",
          conflict: "catalog.conflictError",
          failedStatus: "catalog.editFailedStatus",
          failed: "catalog.editFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  if (isLoading || isError) {
    return (
      <Paper withBorder shadow="sm" p="xl" radius="md" maw={560}>
        <Stack>
          <Title order={2}>{t("catalog.editFile")}</Title>
          {isLoading ? (
            <Center py="xl">
              <Loader />
            </Center>
          ) : (
            <>
              <Alert color="red" variant="light">
                {notFound
                  ? t("catalog.fileNotFound")
                  : t("catalog.loadFileFailed", {
                      suffix: fetchError instanceof ApiError ? ` (${fetchError.status})` : "",
                    })}
              </Alert>
              <Group justify="flex-end">
                <Button component={RouterLink} to="/catalog-files" variant="default">
                  {t("catalog.backToList")}
                </Button>
              </Group>
            </>
          )}
        </Stack>
      </Paper>
    );
  }

  return (
    // Same two-pane document layout as the create page (see web/CLAUDE.md).
    <Grid>
      <Grid.Col span={{ base: 12, md: 7 }}>
        <Paper withBorder shadow="sm" p="xl" radius="md">
          <form onSubmit={form.onSubmit(onSubmit)} noValidate>
            <Stack>
              <Title order={2}>{t("catalog.editFile")}</Title>
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
                  {t("common.action.save")}
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
