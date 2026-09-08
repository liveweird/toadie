import { useTranslation } from "react-i18next";
import { Navigate } from "react-router-dom";
import { useForm } from "@mantine/form";
import { createBlueprint } from "../api/blueprints";
import { isAdmin } from "../api/session";
import BlueprintEditor from "../components/BlueprintEditor";
import { useBlueprintSave } from "../hooks/useBlueprintSave";
import { blueprintFormValidation, emptyBlueprintForm, type BlueprintFormValues } from "../utils/blueprintForm";
import { blueprintsPath } from "../utils/blueprintLinks";

export default function CreateBlueprint() {
  const { t } = useTranslation();

  const form = useForm<BlueprintFormValues>({
    initialValues: emptyBlueprintForm(),
    validate: blueprintFormValidation(t),
    // Feedback when you LEAVE a field, not on every keystroke (the catalog editor's rule).
    validateInputOnBlur: true,
  });

  const save = useBlueprintSave({
    saveRequest: (body) => createBlueprint(body),
    toastKey: "blueprints.toast.created",
  });

  // Mutations are ADMIN-only; the nav leaf and the list stay visible to everyone, so this
  // route needs its own backstop (the Users-page pattern).
  if (!isAdmin()) return <Navigate to={blueprintsPath} replace />;

  return (
    <BlueprintEditor
      title={t("blueprints.createBlueprint")}
      submitLabel={t("common.action.create")}
      form={form}
      onSubmit={save.onSubmit}
      error={save.error}
      submitting={save.submitting}
    />
  );
}
