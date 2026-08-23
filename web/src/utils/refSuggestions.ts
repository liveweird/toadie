/**
 * Reference-picker suggestions: which stored kinds each spec field points at, and the shortest
 * reference form that still resolves under the descriptor rules (per-field default kinds,
 * namespaceless refs resolve in the referencing file's own namespace, case-insensitive).
 * Pure — the identity pool comes from useCatalogIdentities.
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

// What a field may point at (owner: Backstage allows a group OR a user).
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

// The field's default kind — null for dependsOn/dependencyOf (no default in Backstage, so
// their suggestions always carry an explicit kind). Mirrors the server's REF_FIELD_DEFAULT_KINDS.
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

/** The shortest reference form that resolves to [target] from a file in [currentNamespace]. */
export function shortestRef(target: Identity, defaultKind: string | null, currentNamespace: string): string {
  const kind = target.kind.toLowerCase();
  const sameKind = defaultKind !== null && kind === defaultKind;
  const sameNamespace = target.namespace.toLowerCase() === currentNamespace.toLowerCase();
  if (sameKind && sameNamespace) return target.name;
  if (sameKind) return `${target.namespace}/${target.name}`;
  if (sameNamespace) return `${kind}:${target.name}`;
  return `${kind}:${target.namespace}/${target.name}`;
}

/** Sorted, deduped picker options for [field], shortened relative to [currentNamespace]. */
export function refSuggestions(
  identities: readonly Identity[] | undefined,
  field: RefField,
  currentNamespace: string,
): string[] {
  if (!identities) return [];
  const namespace = currentNamespace.trim().toLowerCase() || "default";
  const kinds = new Set(TARGET_KINDS[field]);
  const out = new Set<string>();
  for (const identity of identities) {
    if (!kinds.has(identity.kind.toLowerCase())) continue;
    out.add(shortestRef(identity, DEFAULT_KINDS[field], namespace));
  }
  return [...out].sort((a, b) => a.localeCompare(b));
}
