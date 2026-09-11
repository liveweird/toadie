import { useTranslation } from "react-i18next";
import { Navigate, useSearchParams } from "react-router-dom";
import { useForm } from "@mantine/form";
import { createEntity } from "../api/entities";
import type { Blueprint } from "../api/blueprints";
import EntityEditor from "../components/EntityEditor";
import LoadingBlock from "../components/LoadingBlock";
import { useBlueprints } from "../hooks/useBlueprints";
import { useEntitySave } from "../hooks/useEntitySave";
import { emptyEntityForm, entityFormValidation, type EntityFormValues } from "../utils/entityForm";
import { entitiesPath } from "../utils/entityLinks";

/** Mounted only once the target blueprint is resolved — so `useForm`'s `validate` (which
 *  needs the blueprint's schema) is built from the RIGHT blueprint on its one and only
 *  construction, never a placeholder later replaced (Mantine's form config is captured once). */
function CreateEntityForm({ blueprint }: { blueprint: Blueprint }) {
  const { t } = useTranslation();
  const form = useForm<EntityFormValues>({
    initialValues: emptyEntityForm(blueprint),
    validate: entityFormValidation(t, blueprint),
    // Feedback when you LEAVE a field, not on every keystroke (the blueprint editor's rule).
    validateInputOnBlur: true,
  });
  const save = useEntitySave({ blueprint, saveRequest: (body) => createEntity(body), toastKey: "entities.toast.created" });
  return (
    <EntityEditor
      title={t("entities.createEntity")}
      submitLabel={t("common.action.create")}
      blueprint={blueprint}
      form={form}
      onSubmit={save.onSubmit}
      error={save.error}
      submitting={save.submitting}
      saveFindings={save.findings}
    />
  );
}

export default function CreateEntity() {
  const [params] = useSearchParams();
  const blueprintId = params.get("blueprint") ?? "";
  const { blueprints, loading } = useBlueprints();

  if (loading) return <LoadingBlock mih={200} />;

  const blueprint = blueprints.find((b) => b.identifier === blueprintId);
  // An unknown/missing ?blueprint= has nothing to build the form around.
  if (!blueprint) return <Navigate to={entitiesPath()} replace />;

  return <CreateEntityForm blueprint={blueprint} />;
}
