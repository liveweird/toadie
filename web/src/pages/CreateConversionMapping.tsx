import { useTranslation } from "react-i18next";
import { Navigate } from "react-router-dom";
import { useForm } from "@mantine/form";
import { createConversionMapping } from "../api/conversionMappings";
import { isAdmin } from "../api/session";
import ConversionMappingEditor from "../components/ConversionMappingEditor";
import { useConversionMappingSave } from "../hooks/useConversionMappingSave";
import {
  conversionMappingFormValidation,
  emptyConversionMappingForm,
  type ConversionMappingFormValues,
} from "../utils/conversionMappingForm";
import { conversionMappingsPath } from "../utils/conversionLinks";

export default function CreateConversionMapping() {
  const { t } = useTranslation();

  const form = useForm<ConversionMappingFormValues>({
    initialValues: emptyConversionMappingForm(),
    validate: conversionMappingFormValidation(t),
    // Feedback when you LEAVE a field, not on every keystroke (the catalog editor's rule).
    validateInputOnBlur: true,
  });

  const save = useConversionMappingSave({
    saveRequest: (body) => createConversionMapping(body),
    toastKey: "conversion.mappings.toast.created",
  });

  // Mutations are ADMIN-only; the nav leaf and the list stay visible to everyone, so this
  // route needs its own backstop (the Users-page pattern).
  if (!isAdmin()) return <Navigate to={conversionMappingsPath} replace />;

  return (
    <ConversionMappingEditor
      title={t("conversion.mappings.createTitle")}
      submitLabel={t("common.action.create")}
      form={form}
      onSubmit={save.onSubmit}
      error={save.error}
      submitting={save.submitting}
      findings={[]}
      rejectedFindings={save.findings}
      drift={[]}
    />
  );
}
