/**
 * Reference-picker suggestions: which stored kinds each spec field points at, offered as the
 * FULL identity form `kind:namespace/name` — unambiguous regardless of the field's default
 * kind or the referencing file's namespace. Free-typed short forms stay legal (the descriptor
 * rules resolve them: per-field default kinds, namespaceless refs resolve in the referencing
 * file's own namespace, case-insensitive). Pure — the identity pool comes from
 * useCatalogIdentities.
 */

export type RefField =
  | "owner"
  | "system"
  | "subcomponentOf"
  | "providesApis"
  | "consumesApis"
  | "dependsOn"
  | "dependencyOf"
  | "parent"
  | "children"
  | "members"
  | "memberOf"
  | "domain"
  | "subdomainOf";

// What a field may point at (owner: Backstage allows a group OR a user). Exported for the
// wrong-kind validation message; mirrors the server's REF_FIELD_ALLOWED_KINDS.
export const TARGET_KINDS: Record<RefField, readonly string[]> = {
  owner: ["group", "user"],
  system: ["system"],
  subcomponentOf: ["component"],
  providesApis: ["api"],
  consumesApis: ["api"],
  dependsOn: ["component", "resource"],
  dependencyOf: ["component", "resource"],
  parent: ["group"],
  children: ["group"],
  members: ["user"],
  memberOf: ["group"],
  domain: ["domain"],
  subdomainOf: ["domain"],
};

// The field's default kind — null for dependsOn/dependencyOf (no default in Backstage).
// Feeds the resolution check for typed short forms; mirrors the server's REF_FIELD_DEFAULT_KINDS.
const DEFAULT_KINDS: Record<RefField, string | null> = {
  owner: "group",
  system: "system",
  subcomponentOf: "component",
  providesApis: "api",
  consumesApis: "api",
  dependsOn: null,
  dependencyOf: null,
  parent: "group",
  children: "group",
  members: "user",
  memberOf: "group",
  domain: "domain",
  subdomainOf: "domain",
};

// Loose on purpose (any string kind): case-folded here, and the pool rows'
// generated union type narrows into it.
type Identity = { kind: string; namespace: string; name: string };

/** The full identity form `kind:namespace/name` (canonical lowercase kind). */
function fullRef(target: Identity): string {
  return `${target.kind.toLowerCase()}:${target.namespace}/${target.name}`;
}

/** The single-occurrence split behind the lenient parse (mirror of the server's splitRefOnce). */
function splitOnce(value: string, sep: string): [string | null, string] | null {
  const first = value.indexOf(sep);
  if (first !== value.lastIndexOf(sep)) return null;
  return first >= 0 ? [value.slice(0, first), value.slice(first + 1)] : [null, value];
}

export type RefResolutionError = "unresolved" | "wrongKind" | "kindRequired" | "selfReference";

function sameIdentity(a: Identity, b: Identity): boolean {
  return (
    a.kind.toLowerCase() === b.kind.toLowerCase() &&
    a.namespace.toLowerCase() === b.namespace.toLowerCase() &&
    a.name.toLowerCase() === b.name.toLowerCase()
  );
}

/**
 * The write-blocking resolution verdict for one typed reference — the client mirror of the
 * server's rulebook (per-field default kind, allowed target kinds, contextual namespace,
 * case-insensitive, and no reference to [self] — the entity being edited). Null = resolves —
 * or unparsable (the grammar rule owns those), or an unavailable pool for the membership half
 * (loading/failed → grammar-and-kind checks only; the server stays the gate). The self check
 * needs no pool, so it runs regardless; a null [self] (blank name) skips it.
 */
export function refResolutionError(
  raw: string,
  field: RefField,
  currentNamespace: string,
  self: Identity | null,
  identities: readonly Identity[] | undefined,
): RefResolutionError | null {
  const kindSplit = splitOnce(raw, ":");
  if (!kindSplit) return null;
  const nameSplit = splitOnce(kindSplit[1], "/");
  if (!nameSplit) return null;
  const [rawKind, [rawNamespace, rawName]] = [kindSplit[0], nameSplit];
  if (rawKind === "" || rawNamespace === "" || rawName === "") return null;
  const kind = rawKind?.toLowerCase() ?? DEFAULT_KINDS[field];
  if (!kind) return "kindRequired";
  if (!TARGET_KINDS[field].includes(kind)) return "wrongKind";
  const namespace = (rawNamespace ?? currentNamespace).toLowerCase();
  const name = rawName.toLowerCase();
  if (self && sameIdentity({ kind, namespace, name }, self)) return "selfReference";
  if (!identities || identities.length === 0) return null;
  const resolves = identities.some(
    (identity) =>
      identity.kind.toLowerCase() === kind &&
      identity.namespace.toLowerCase() === namespace &&
      identity.name.toLowerCase() === name,
  );
  return resolves ? null : "unresolved";
}

/** Sorted, deduped full-identity picker options for [field]; [exclude] (the entity being
 *  edited) never offers itself — an entity may not reference itself. */
export function refSuggestions(
  identities: readonly Identity[] | undefined,
  field: RefField,
  exclude?: Identity | null,
): string[] {
  if (!identities) return [];
  const kinds = new Set(TARGET_KINDS[field]);
  const out = new Set<string>();
  for (const identity of identities) {
    if (!kinds.has(identity.kind.toLowerCase())) continue;
    if (exclude && sameIdentity(identity, exclude)) continue;
    out.add(fullRef(identity));
  }
  return [...out].sort((a, b) => a.localeCompare(b));
}
