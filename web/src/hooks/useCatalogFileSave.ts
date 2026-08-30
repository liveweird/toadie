import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import type { ParseKeys } from "i18next";
import {
  softRejectionFindings,
  type CatalogFileRequest,
  type CatalogSaveOptions,
  type CatalogFileWriteRequest,
  type DocumentCheckFinding,
} from "../api/catalogFiles";
import { saveErrorMessage, type SaveErrorKeys } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import { catalogFilesPath } from "../utils/catalogFileLinks";
import type { CatalogFileFormValues } from "../utils/catalogFileForm";
import { toCatalogFileRequest } from "../utils/catalogFileForm";

/** A strict save rejected for SOFT findings, parked for the Save-anyway modal. */
interface CatalogSaveWaiver {
  request: CatalogFileRequest;
  sourceUrl: string | undefined;
  findings: DocumentCheckFinding[];
}

/**
 * The catalog-file save flow shared by the create and edit pages: strict save → on a soft
 * rejection park the request for the Save-anyway modal (confirming retries with the
 * allowInvalid waiver), on any other failure render the page's fixed error vocabulary.
 * Success invalidates the list, toasts, and returns to /files. The document stays pure
 * (the /check body); the source reference rides beside it in the write request.
 */
export function useCatalogFileSave({
  saveRequest,
  toastKey,
  errorKeys,
}: {
  /** The transport call — create, or update bound to its id. */
  saveRequest: (body: CatalogFileWriteRequest, options?: CatalogSaveOptions) => Promise<unknown>;
  toastKey: ParseKeys;
  errorKeys: SaveErrorKeys;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [waiver, setWaiver] = useState<CatalogSaveWaiver | null>(null);

  async function save(request: CatalogFileRequest, sourceUrl: string | undefined, allowInvalid: boolean) {
    await saveRequest({ ...request, sourceUrl }, allowInvalid ? { allowInvalid: true } : undefined);
    // The prefix covers the list AND the per-id detail queries.
    await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
    showSuccessToast(t(toastKey));
    navigate(catalogFilesPath, { replace: true });
  }

  const mapError = (err: unknown) => saveErrorMessage(err, t, errorKeys);

  async function onSubmit(values: CatalogFileFormValues) {
    setError(null);
    setSubmitting(true);
    const request = toCatalogFileRequest(values);
    const sourceUrl = values.sourceUrl.trim() || undefined;
    try {
      await save(request, sourceUrl, false);
    } catch (err) {
      const findings = await softRejectionFindings(err, request);
      if (findings) setWaiver({ request, sourceUrl, findings });
      else setError(mapError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function onSaveAnyway() {
    if (!waiver) return;
    setSubmitting(true);
    try {
      await save(waiver.request, waiver.sourceUrl, true);
    } catch (err) {
      setError(mapError(err));
    } finally {
      setWaiver(null);
      setSubmitting(false);
    }
  }

  return {
    error,
    submitting,
    waiverFindings: waiver?.findings ?? null,
    cancelWaiver: () => setWaiver(null),
    onSubmit,
    onSaveAnyway,
  };
}
