import { afterEach, describe, expect, test, vi } from "vitest";
import { downloadTextFile } from "./download";

describe("downloadTextFile", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test("hands the text to the browser as a Blob download with the given name and MIME type", async () => {
    const createObjectURL = vi.fn().mockReturnValue("blob:fake");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL }));
    let downloadedName: string | undefined;
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(function (this: HTMLAnchorElement) {
        downloadedName = this.download;
      });

    downloadTextFile('{"a":1}', "toadie-blueprints.json", "application/json");

    expect(createObjectURL).toHaveBeenCalledOnce();
    const blob = createObjectURL.mock.calls[0][0] as Blob;
    expect(blob.type).toBe("application/json");
    expect(await blob.text()).toBe('{"a":1}');
    expect(click).toHaveBeenCalledOnce();
    expect(downloadedName).toBe("toadie-blueprints.json");
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:fake");
    click.mockRestore();
  });
});
