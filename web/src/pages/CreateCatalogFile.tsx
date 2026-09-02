import { useTranslation } from "react-i18next";
import { useForm } from "@mantine/form";
import { createCatalogFile } from "../api/catalogFiles";
import CatalogFileEditor from "../components/CatalogFileEditor";
import SaveAnywayModal from "../components/SaveAnywayModal";
import { useCatalogFileSave } from "../hooks/useCatalogFileSave";
import {
  catalogFileFormValidation,
  emptyCatalogFileForm,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { CATALOG_CREATE_ERROR_KEYS } from "../utils/saveError";
import { catalogFilesPath } from "../utils/catalogFileLinks";

export default function CreateCatalogFile() {
  const { t } = useTranslation();

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: emptyCatalogFileForm(),
    validate: catalogFileFormValidation(t),
    // Feedback when you LEAVE a field, not on every keystroke: a fresh form would otherwise
    // flash red while you type the first character into each required field.
    validateInputOnBlur: true,
  });

  // The strict-save → Save-anyway flow shared with the edit page.
  const save = useCatalogFileSave({
    saveRequest: (body, options) => createCatalogFile(body, options),
    toastKey: "catalog.toast.created",
    errorKeys: CATALOG_CREATE_ERROR_KEYS,
  });

  return (
    <>
      <CatalogFileEditor
        title={t("catalog.createFile")}
        submitLabel={t("common.action.create")}
        form={form}
        onSubmit={save.onSubmit}
        back={{ to: catalogFilesPath, label: t("catalog.backToList") }}
        error={save.error}
        submitting={save.submitting}
      />
      <SaveAnywayModal
        findings={save.waiverFindings}
        onCancel={save.cancelWaiver}
        onConfirm={save.onSaveAnyway}
        saving={save.submitting}
      />
    </>
  );
}
