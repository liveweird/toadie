import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { entitySaveFindings, type EntityBody, type EntityFinding } from "../api/entities";
import { entitySaveErrorMessage, toEntityRequest, type EntityFormValues } from "../utils/entityForm";
import { entitiesPath } from "../utils/entityLinks";
import { showSuccessToast } from "../utils/toast";
import type { Blueprint } from "../api/blueprints";

/**
 * The entity save flow shared by the create and edit pages — the `useBlueprintSave` shape:
 * no Save-anyway waiver step (an entity's soft findings are reported, never blocked by a
 * client-visible waiver flow — the server 400s a strict save and names the finding, same as
 * any other validation error). Success invalidates the list, toasts, and returns to the
 * blueprint-scoped list.
 */
export function useEntitySave({
  blueprint,
  saveRequest,
  toastKey,
}: {
  /** The full blueprint (schema needed to coerce draft values by type) — the page resolves
   *  it before mounting the form (`CreateEntityForm`/`EditEntityForm`'s split from their
   *  loading-state parent, the EditBlueprint pattern). */
  blueprint: Blueprint;
  /** The transport call — create, or update bound to its id. */
  saveRequest: (body: EntityBody) => Promise<unknown>;
  toastKey: "entities.toast.created" | "entities.toast.saved";
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [findings, setFindings] = useState<EntityFinding[]>([]);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(values: EntityFormValues) {
    setError(null);
    setFindings([]);
    setSubmitting(true);
    try {
      await saveRequest(toEntityRequest(values, blueprint));
      await queryClient.invalidateQueries({ queryKey: ["entities"] });
      showSuccessToast(t(toastKey));
      navigate(entitiesPath(values.blueprint), { replace: true });
    } catch (err) {
      setError(entitySaveErrorMessage(err, t));
      setFindings(entitySaveFindings(err));
    } finally {
      setSubmitting(false);
    }
  }

  return { error, findings, submitting, onSubmit };
}
