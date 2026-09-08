import { useTranslation } from "react-i18next";
import { Navigate, useParams } from "react-router-dom";
import { Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery } from "@tanstack/react-query";
import { getBlueprint, updateBlueprint } from "../api/blueprints";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import BlueprintEditor from "../components/BlueprintEditor";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import { useBlueprintSave } from "../hooks/useBlueprintSave";
import {
  blueprintFormValidation,
  emptyBlueprintForm,
  fromBlueprintResponse,
  type BlueprintFormValues,
} from "../utils/blueprintForm";
import { blueprintsPath } from "../utils/blueprintLinks";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";

export default function EditBlueprint() {
  const { t } = useTranslation();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const idIsValid = Number.isFinite(id) && id > 0;

  const form = useForm<BlueprintFormValues>({
    initialValues: emptyBlueprintForm(),
    validate: blueprintFormValidation(t),
    // Feedback when you LEAVE a field, not on every keystroke (the catalog editor's rule).
    validateInputOnBlur: true,
  });

  const save = useBlueprintSave({
    saveRequest: (body) => updateBlueprint(id, body),
    toastKey: "blueprints.toast.saved",
  });

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["blueprints", "detail", id],
    queryFn: () => getBlueprint(id),
    enabled: idIsValid && isAdmin(),
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the catalog editor's guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize(fromBlueprintResponse(data));
  }

  // Mutations are ADMIN-only; the nav leaf and the list stay visible to everyone, so this
  // route needs its own backstop (the Users-page pattern).
  if (!isAdmin()) return <Navigate to={blueprintsPath} replace />;
  if (!idIsValid) return <Navigate to={blueprintsPath} replace />;

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  if (isLoading || isError) {
    return (
      <Stack gap="md">
        <PageHeader
          title={t("blueprints.editBlueprint")}
          backTo={{ to: blueprintsPath, label: t("blueprints.backToList") }}
        />
        <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
          <EditPageLoadState
            isLoading={isLoading}
            message={notFound ? t("blueprints.notFound") : loadErrorMessage(fetchError, t)}
            backTo={blueprintsPath}
            backLabel={t("blueprints.backToList")}
          />
        </Paper>
      </Stack>
    );
  }

  return (
    <BlueprintEditor
      title={t("blueprints.editBlueprint")}
      submitLabel={t("common.action.save")}
      form={form}
      onSubmit={save.onSubmit}
      error={save.error}
      submitting={save.submitting}
    />
  );
}
