import { useTranslation } from "react-i18next";
import { Navigate, useParams } from "react-router-dom";
import { Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery } from "@tanstack/react-query";
import { getConversionMapping, replaceConversionMapping } from "../api/conversionMappings";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import ConversionMappingEditor from "../components/ConversionMappingEditor";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import { useConversionMappingSave } from "../hooks/useConversionMappingSave";
import {
  conversionMappingFormValidation,
  emptyConversionMappingForm,
  fromConversionMappingResponse,
  type ConversionMappingFormValues,
} from "../utils/conversionMappingForm";
import { conversionMappingsPath } from "../utils/conversionLinks";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";

export default function EditConversionMapping() {
  const { t } = useTranslation();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const idIsValid = Number.isFinite(id) && id > 0;

  const form = useForm<ConversionMappingFormValues>({
    initialValues: emptyConversionMappingForm(),
    validate: conversionMappingFormValidation(t),
    validateInputOnBlur: true,
  });

  const save = useConversionMappingSave({
    saveRequest: (body) => replaceConversionMapping(id, body),
    toastKey: "conversion.mappings.toast.saved",
  });

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["conversionMappings", "detail", id],
    queryFn: () => getConversionMapping(id),
    enabled: idIsValid && isAdmin(),
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the catalog editor's guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize(fromConversionMappingResponse(data));
  }

  if (!isAdmin()) return <Navigate to={conversionMappingsPath} replace />;
  if (!idIsValid) return <Navigate to={conversionMappingsPath} replace />;

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  if (isLoading || isError) {
    return (
      <Stack gap="md">
        <PageHeader
          title={t("conversion.mappings.editTitle")}
          backTo={{ to: conversionMappingsPath, label: t("conversion.mappings.backToList") }}
        />
        <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
          <EditPageLoadState
            isLoading={isLoading}
            message={notFound ? t("conversion.mappings.notFound") : loadErrorMessage(fetchError, t)}
            backTo={conversionMappingsPath}
            backLabel={t("conversion.mappings.backToList")}
          />
        </Paper>
      </Stack>
    );
  }

  return (
    <ConversionMappingEditor
      title={t("conversion.mappings.editTitle")}
      submitLabel={t("common.action.save")}
      form={form}
      onSubmit={save.onSubmit}
      error={save.error}
      submitting={save.submitting}
      findings={data?.findings ?? []}
      rejectedFindings={save.findings}
      drift={data?.drift ?? []}
    />
  );
}
