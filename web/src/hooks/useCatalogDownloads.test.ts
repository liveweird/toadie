import { afterEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useCatalogDownloads } from "./useCatalogDownloads";
import {
  exportCatalogFiles,
  getCatalogFile,
  type CatalogFileListItem,
} from "../api/catalogFiles";
import { catalogInfoMultiYaml, catalogInfoYaml, downloadYaml } from "../utils/catalogYaml";

vi.mock("../api/catalogFiles", () => ({
  getCatalogFile: vi.fn(),
  exportCatalogFiles: vi.fn(),
}));
vi.mock("../utils/catalogYaml", () => ({
  catalogInfoYaml: vi.fn(() => "single-yaml"),
  catalogInfoMultiYaml: vi.fn(() => "multi-yaml"),
  downloadYaml: vi.fn(),
}));

const ROW = { id: 5, kind: "Component", name: "web-app" } as CatalogFileListItem;

const FILE = {
  id: 5,
  kind: "Component",
  metadata: { name: "web-app" },
  spec: { type: "service" },
};

describe("useCatalogDownloads", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  test("handleDownload fetches the full document and downloads its YAML", async () => {
    vi.mocked(getCatalogFile).mockResolvedValue(FILE as never);
    const { result } = renderHook(() => useCatalogDownloads());

    await act(() => result.current.handleDownload(ROW));

    expect(getCatalogFile).toHaveBeenCalledWith(5);
    expect(catalogInfoYaml).toHaveBeenCalledWith({
      kind: FILE.kind,
      metadata: FILE.metadata,
      spec: FILE.spec,
    });
    expect(downloadYaml).toHaveBeenCalledWith("single-yaml");
    expect(result.current.downloadError).toBeNull();
    expect(result.current.downloadingId).toBeNull();
  });

  test("a failed download stores the error, which dismisses", async () => {
    vi.mocked(getCatalogFile).mockRejectedValueOnce(new Error("boom"));
    const { result } = renderHook(() => useCatalogDownloads());

    await act(() => result.current.handleDownload(ROW));
    expect(result.current.downloadError).toBeInstanceOf(Error);
    expect(downloadYaml).not.toHaveBeenCalled();

    act(() => result.current.dismissDownloadError());
    expect(result.current.downloadError).toBeNull();
  });

  test("handleExport downloads the multi-document YAML (namespace passed through)", async () => {
    const files = [{ kind: "Component", metadata: { name: "a" }, spec: {} }];
    vi.mocked(exportCatalogFiles).mockResolvedValue({ files } as never);
    const { result } = renderHook(() => useCatalogDownloads());

    await act(() => result.current.handleExport("team-a"));

    expect(exportCatalogFiles).toHaveBeenCalledWith("team-a");
    expect(catalogInfoMultiYaml).toHaveBeenCalledWith(files);
    expect(downloadYaml).toHaveBeenCalledWith("multi-yaml");
    expect(result.current.exportError).toBeNull();
    expect(result.current.exporting).toBe(false);
  });

  test("handleExport without a namespace exports the whole workspace", async () => {
    vi.mocked(exportCatalogFiles).mockResolvedValue({ files: [] } as never);
    const { result } = renderHook(() => useCatalogDownloads());

    await act(() => result.current.handleExport());
    expect(exportCatalogFiles).toHaveBeenCalledWith(undefined);
  });

  test("a failed export stores the error, which dismisses", async () => {
    vi.mocked(exportCatalogFiles).mockRejectedValueOnce(new Error("boom"));
    const { result } = renderHook(() => useCatalogDownloads());

    await act(() => result.current.handleExport());
    expect(result.current.exportError).toBeInstanceOf(Error);
    expect(downloadYaml).not.toHaveBeenCalled();

    act(() => result.current.dismissExportError());
    expect(result.current.exportError).toBeNull();
  });
});
