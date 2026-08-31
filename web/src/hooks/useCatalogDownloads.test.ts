import { afterEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useCatalogDownloads } from "./useCatalogDownloads";
import { getCatalogFile, type CatalogFileListItem } from "../api/catalogFiles";
import { catalogInfoYaml, downloadYaml } from "../utils/catalogYaml";

vi.mock("../api/catalogFiles", () => ({ getCatalogFile: vi.fn() }));
vi.mock("../utils/catalogYaml", () => ({
  catalogInfoYaml: vi.fn(() => "single-yaml"),
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

});
