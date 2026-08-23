import { useState } from "react";
import { exportCatalogFiles, getCatalogFile, type CatalogFileListItem } from "../api/catalogFiles";
import { catalogInfoMultiYaml, catalogInfoYaml, downloadYaml } from "../utils/catalogYaml";

/**
 * The list page's two YAML downloads: a single file's catalog-info.yaml and the whole-workspace
 * (or namespace-slice) export. Owns the per-action error flags; the page renders the dismissible
 * error banners from them.
 */
export function useCatalogDownloads() {
  const [downloadError, setDownloadError] = useState(false);
  const [exportError, setExportError] = useState(false);

  // The list row carries only the display fields — fetch the full document for the YAML.
  async function handleDownload(row: CatalogFileListItem) {
    setDownloadError(false);
    try {
      const file = await getCatalogFile(row.id);
      downloadYaml(catalogInfoYaml({ kind: file.kind, metadata: file.metadata, spec: file.spec }));
    } catch {
      setDownloadError(true);
    }
  }

  // The whole workspace (or the exact-namespace slice the filter selects) as ONE
  // multi-document catalog-info.yaml — the round-trip's outbound half.
  async function handleExport(namespace?: string) {
    setExportError(false);
    try {
      const exported = await exportCatalogFiles(namespace);
      downloadYaml(catalogInfoMultiYaml(exported.files));
    } catch {
      setExportError(true);
    }
  }

  return {
    downloadError,
    exportError,
    dismissDownloadError: () => setDownloadError(false),
    dismissExportError: () => setExportError(false),
    handleDownload,
    handleExport,
  };
}
