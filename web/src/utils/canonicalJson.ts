// Canonical JSON rendering shared by the entity and (2.10.0) blueprint sync flows — a stable
// key order so the side-by-side comparison (current/remote/baseline) is a plain string
// equality/diff, never a structural one. Extracted out of `utils/entitySync.ts` when
// `utils/blueprintSync.ts` needed the identical rendering one level up, over a different
// top-level key order.

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Recursively sorts object keys (alphabetically); arrays keep their element order, but each
 *  element is itself sorted the same way. */
function sortKeysDeep(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeysDeep);
  if (isPlainObject(value)) {
    const sorted: Record<string, unknown> = {};
    for (const key of Object.keys(value).sort()) {
      sorted[key] = sortKeysDeep(value[key]);
    }
    return sorted;
  }
  return value;
}

/**
 * Renders a document with a stable key order for comparison: the top-level identity fields in
 * `topLevelOrder` (only those present), any other top-level key alphabetically after them, and
 * every nested object's keys sorted recursively (arrays keep their element order). Mirrors
 * `utils/catalogYaml.ts`'s canonical-rendering role, in JSON rather than YAML.
 */
export function canonicalDocumentJson(doc: Record<string, unknown>, topLevelOrder: readonly string[]): string {
  const ordered: Record<string, unknown> = {};
  for (const key of topLevelOrder) {
    if (key in doc) ordered[key] = sortKeysDeep(doc[key]);
  }
  const orderedKeys = new Set<string>(topLevelOrder);
  for (const key of Object.keys(doc).filter((k) => !orderedKeys.has(k)).sort()) {
    ordered[key] = sortKeysDeep(doc[key]);
  }
  return JSON.stringify(ordered, null, 2);
}
