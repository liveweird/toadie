// The inline source-URL rule (the server's `sanitizedSourceUrl` static guards, mirrored
// client-side) plus the GitHub/GitLab browser-link → raw-file rewrite applied before a repo
// fetch. Extracted out of `catalogFileForm.ts`/`catalogImport.ts` so the catalog editor's field
// validator and the entity source-sync flow share one definition.

export const MAX_SOURCE_URL_LENGTH = 2048;

export type SourceUrlProblem = "length" | "grammar";

/**
 * The server's static sourceUrl guards (`sanitizedSourceUrl`): absolute https, no credentials,
 * a sane length — the public-host check stays a fetch-time concern. A blank value has no
 * problem (the field is optional).
 */
export function sourceUrlProblem(value: string): SourceUrlProblem | null {
  const v = value.trim();
  if (!v) return null;
  if (v.length > MAX_SOURCE_URL_LENGTH) return "length";
  try {
    const url = new URL(v);
    return url.protocol === "https:" && !url.username && !url.password ? null : "grammar";
  } catch {
    return "grammar";
  }
}

/**
 * Convenience rewrites of Git-hosting BROWSER links to their raw-file form, applied before
 * the server-side fetch (which needs the actual file, not the HTML viewer): GitHub
 * `/blob/` links become raw.githubusercontent.com, GitLab `/-/blob/` becomes `/-/raw/`.
 * Anything else — raw links included — passes through untouched.
 */
export function normalizeSourceUrl(url: string): string {
  const trimmed = url.trim();
  const github = /^https:\/\/github\.com\/([^/]+)\/([^/]+)\/blob\/(.+)$/.exec(trimmed);
  if (github) {
    return `https://raw.githubusercontent.com/${github[1]}/${github[2]}/${github[3]}`;
  }
  return trimmed.replace(/^(https:\/\/[^/]+\/.+)\/-\/blob\//, "$1/-/raw/");
}
