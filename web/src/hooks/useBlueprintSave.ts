import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import type { BlueprintBody } from "../api/blueprints";
import { blueprintSaveErrorMessage, toBlueprintRequest, type BlueprintFormValues } from "../utils/blueprintForm";
import { blueprintsPath } from "../utils/blueprintLinks";
import { showSuccessToast } from "../utils/toast";

/**
 * The blueprint save flow shared by the create and edit pages — simpler than the catalog
 * file's: a blueprint definition carries no soft/registry findings, so there is no
 * Save-anyway waiver step. Success invalidates the registry, toasts, and returns to the list.
 */
export function useBlueprintSave({
  saveRequest,
  toastKey,
}: {
  /** The transport call — create, or update bound to its id. */
  saveRequest: (body: BlueprintBody) => Promise<unknown>;
  toastKey: "blueprints.toast.created" | "blueprints.toast.saved";
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(values: BlueprintFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await saveRequest(toBlueprintRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["blueprints"] });
      showSuccessToast(t(toastKey));
      navigate(blueprintsPath, { replace: true });
    } catch (err) {
      setError(blueprintSaveErrorMessage(err, t));
    } finally {
      setSubmitting(false);
    }
  }

  return { error, submitting, onSubmit };
}
