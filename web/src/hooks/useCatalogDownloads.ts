import { useState } from "react";
import { exportCatalogFiles, getCatalogFile, type CatalogFileListItem } from "../api/catalogFiles";
import { catalogInfoMultiYaml, catalogInfoYaml, downloadYaml } from "../utils/catalogYaml";

/**
 * The list page's two YAML downloads: a single file's catalog-info.yaml and the whole-workspace
 * (or namespace-slice) export. Owns the per-action error values (the caught failure, for
 * status-aware messages) and in-flight state (the page disables/spins the buttons, and a
 * double-click while one runs is a no-op); the page renders the dismissible error banners.
 */
export function useCatalogDownloads() {
  const [downloadError, setDownloadError] = useState<unknown>(null);
  const [exportError, setExportError] = useState<unknown>(null);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [exporting, setExporting] = useState(false);

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

  // The whole workspace (or the exact-namespace slice the filter selects) as ONE
  // multi-document catalog-info.yaml — the round-trip's outbound half.
  async function handleExport(namespace?: string) {
    if (exporting) return;
    setExportError(null);
    setExporting(true);
    try {
      const exported = await exportCatalogFiles(namespace);
      downloadYaml(catalogInfoMultiYaml(exported.files));
    } catch (err) {
      setExportError(err);
    } finally {
      setExporting(false);
    }
  }

  return {
    downloadError,
    exportError,
    downloadingId,
    exporting,
    dismissDownloadError: () => setDownloadError(null),
    dismissExportError: () => setExportError(null),
    handleDownload,
    handleExport,
  };
}
