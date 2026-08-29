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

// What a field may point at (owner: Backstage allows a group OR a user); mirrors the
// server's REF_FIELD_ALLOWED_KINDS.
const TARGET_KINDS: Record<RefField, readonly string[]> = {
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

// Loose on purpose (any string kind): case-folded here, and the pool rows'
// generated union type narrows into it.
type Identity = { kind: string; namespace: string; name: string };

/** The full identity form `kind:namespace/name` (canonical lowercase kind). */
function fullRef(target: Identity): string {
  return `${target.kind.toLowerCase()}:${target.namespace}/${target.name}`;
}

// (The client-side resolution verdict `refResolutionError` lived here until the
// save-with-findings model: resolution is soft now — the server's /check reports it, both
// in the live panel and in the editor's Save-anyway modal — so the client mirror was
// retired rather than kept in sync.)

function sameIdentity(a: Identity, b: Identity): boolean {
  return (
    a.kind.toLowerCase() === b.kind.toLowerCase() &&
    a.namespace.toLowerCase() === b.namespace.toLowerCase() &&
    a.name.toLowerCase() === b.name.toLowerCase()
  );
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
