import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { mappingSaveFindings, type ConversionMappingBody, type MappingFinding } from "../api/conversionMappings";
import {
  conversionMappingSaveErrorMessage,
  toConversionMappingRequest,
  type ConversionMappingFormValues,
} from "../utils/conversionMappingForm";
import { conversionMappingsPath } from "../utils/conversionLinks";
import { showSuccessToast } from "../utils/toast";

/**
 * The save flow shared by the create and edit pages: no Save-anyway waiver (blocking findings
 * refuse the write outright), so a refused save surfaces the server's positioned `findings`
 * next to the editor and stays put. Success invalidates the registry and the Port Errors report
 * (its mapping rows are recomputed from the same documents), toasts, and returns to the list.
 */
export function useConversionMappingSave({
  saveRequest,
  toastKey,
}: {
  saveRequest: (body: ConversionMappingBody) => Promise<unknown>;
  toastKey: "conversion.mappings.toast.created" | "conversion.mappings.toast.saved";
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [findings, setFindings] = useState<MappingFinding[]>([]);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(values: ConversionMappingFormValues) {
    setError(null);
    setFindings([]);
    setSubmitting(true);
    try {
      await saveRequest(toConversionMappingRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["conversionMappings"] });
      await queryClient.invalidateQueries({ queryKey: ["entities", "errors"] });
      showSuccessToast(t(toastKey));
      navigate(conversionMappingsPath, { replace: true });
    } catch (err) {
      const refused = mappingSaveFindings(err);
      setFindings(refused);
      setError(refused.length > 0 ? null : conversionMappingSaveErrorMessage(err, t));
    } finally {
      setSubmitting(false);
    }
  }

  return { error, findings, submitting, onSubmit };
}
