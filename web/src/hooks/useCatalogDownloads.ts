import { useState } from "react";
import { getCatalogFile, type CatalogFileListItem } from "../api/catalogFiles";
import { catalogInfoYaml, downloadYaml } from "../utils/catalogYaml";

/**
 * One file's YAML export, shared by the Files list and the Hierarchy tree. Owns the caught
 * failure (for status-aware messages) and the in-flight id (the page spins the row's
 * Operations trigger, and a second click while one runs is a no-op); the page renders the
 * dismissible error banner.
 *
 * Whole-workspace export lives on the API only (`GET /api/v1/files/export`) — the SPA's
 * bottom-bar button was removed for saying "Export YAML" without saying what it exported.
 */
export function useCatalogDownloads() {
  const [downloadError, setDownloadError] = useState<unknown>(null);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);

  // The list row carries only the display fields — fetch the full document for the YAML.
  // Only the id is read, so any row shape carrying one qualifies (the Hierarchy tree's rows).
  async function handleDownload(row: Pick<CatalogFileListItem, "id">) {
    if (downloadingId !== null) return;
    setDownloadError(null);
    setDownloadingId(row.id);
    try {
      const file = await getCatalogFile(row.id);
      downloadYaml(catalogInfoYaml({ kind: file.kind, metadata: file.metadata, spec: file.spec }));
    } catch (err) {
      setDownloadError(err);
    } finally {
      setDownloadingId(null);
    }
  }

  return {
    downloadError,
    downloadingId,
    dismissDownloadError: () => setDownloadError(null),
    handleDownload,
  };
}
