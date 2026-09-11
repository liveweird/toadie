import { useTranslation } from "react-i18next";
import { Navigate, useParams } from "react-router-dom";
import { Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery } from "@tanstack/react-query";
import type { Blueprint } from "../api/blueprints";
import { getEntity, updateEntity, type Entity } from "../api/entities";
import { ApiError } from "../api/http";
import EditPageLoadState from "../components/EditPageLoadState";
import EntityEditor from "../components/EntityEditor";
import PageHeader from "../components/PageHeader";
import { useBlueprints } from "../hooks/useBlueprints";
import { useEntitySave } from "../hooks/useEntitySave";
import { computedValuesOf } from "../utils/computedProperties";
import { entityFormValidation, fromEntityResponse, teamValuesOf, type EntityFormValues } from "../utils/entityForm";
import { entitiesPath } from "../utils/entityLinks";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";

/** Mounted only once the entity AND its blueprint are both resolved — the CreateEntity split. */
function EditEntityForm({ entity, blueprint }: { entity: Entity; blueprint: Blueprint }) {
  const { t } = useTranslation();
  const form = useForm<EntityFormValues>({
    initialValues: fromEntityResponse(entity, blueprint),
    validate: entityFormValidation(t, blueprint),
    validateInputOnBlur: true,
  });
  const save = useEntitySave({
    blueprint,
    saveRequest: (body) => updateEntity(entity.id, body),
    toastKey: "entities.toast.saved",
  });
  return (
    <EntityEditor
      title={t("entities.editEntity")}
      submitLabel={t("common.action.save")}
      blueprint={blueprint}
      form={form}
      onSubmit={save.onSubmit}
      error={save.error}
      submitting={save.submitting}
      staleFindings={entity.findings}
      saveFindings={save.findings}
      computedTeam={teamValuesOf(entity.team)}
      computed={computedValuesOf(entity, blueprint)}
    />
  );
}

export default function EditEntity() {
  const { t } = useTranslation();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const idIsValid = Number.isFinite(id) && id > 0;

  const entityQuery = useQuery({
    queryKey: ["entities", "detail", id],
    queryFn: () => getEntity(id),
    enabled: idIsValid,
    retry: false,
  });
  const { blueprints, loading: blueprintsLoading, error: blueprintsError } = useBlueprints();

  if (!idIsValid) return <Navigate to={entitiesPath()} replace />;

  const notFound = entityQuery.isError && entityQuery.error instanceof ApiError && entityQuery.error.status === 404;
  const blueprint = entityQuery.data ? blueprints.find((b) => b.id === entityQuery.data.blueprintId) : undefined;
  const isLoading = entityQuery.isLoading || blueprintsLoading;
  // The entity loaded fine and the blueprint registry loaded fine, but the entity's own
  // blueprint isn't among the results (deleted since) — a distinct case from a load failure.
  const blueprintMissing =
    !isLoading && !entityQuery.isError && !blueprintsError && entityQuery.data !== undefined && !blueprint;
  const isError = entityQuery.isError || blueprintsError || blueprintMissing;

  if (isLoading || isError || !entityQuery.data || !blueprint) {
    return (
      <Stack gap="md">
        <PageHeader title={t("entities.editEntity")} backTo={{ to: entitiesPath(), label: t("entities.backToList") }} />
        <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
          <EditPageLoadState
            isLoading={isLoading}
            message={
              notFound
                ? t("entities.notFound")
                : blueprintMissing
                  ? t("entities.editor.blueprintMissing")
                  : loadErrorMessage(entityQuery.error, t)
            }
            backTo={entitiesPath()}
            backLabel={t("entities.backToList")}
          />
        </Paper>
      </Stack>
    );
  }

  return <EditEntityForm entity={entityQuery.data} blueprint={blueprint} />;
}
