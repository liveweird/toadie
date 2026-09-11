/**
 * Hands arbitrary text to the browser as a file download via a Blob + anchor click. Extracted
 * from `catalogYaml.ts#downloadYaml` (the YAML export's original body) when JSON export
 * (`utils/ontologyExport.ts`) needed the identical Blob+anchor idiom with a different MIME type.
 */
export function downloadTextFile(text: string, filename: string, mimeType: string) {
  const blob = new Blob([text], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
