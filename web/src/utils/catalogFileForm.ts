import type { TFunction } from "i18next";
import type { CatalogFileRequest, CatalogFileResponse } from "../api/catalogFiles";

// Server limits (catalog/CatalogFile.kt) mirrored client-side.
export const MAX_ENTITY_PART_LENGTH = 63;
export const MAX_TITLE_LENGTH = 200;
export const MAX_DESCRIPTION_LENGTH = 2000;
const MAX_ANNOTATION_VALUE_LENGTH = 5000;
export const MAX_LINK_TITLE_LENGTH = 100;
export const MAX_DEFINITION_LENGTH = 100000;
export const MAX_SOURCE_URL_LENGTH = 2048;
const MAX_PROFILE_EMAIL_LENGTH = 254;

export const ENTITY_KINDS = ["Component", "API", "System", "Domain", "Resource", "Group", "User"] as const;
export type EntityKind = (typeof ENTITY_KINDS)[number];

// Tier = fill-in priority of a kind (1 first, 4 last) — a purely VISUAL marker (KindTierDot),
// never enforced anywhere.
export type KindTier = 1 | 2 | 3 | 4;
export const KIND_TIERS: Record<EntityKind, KindTier> = {
  Domain: 1,
  System: 1,
  Group: 1,
  Component: 2,
  Resource: 3,
  API: 3,
  User: 4,
};

const KIND_TIERS_FOLDED = new Map<string, KindTier>(
  ENTITY_KINDS.map((kind) => [kind.toLowerCase(), KIND_TIERS[kind]]),
);

/** Case-insensitive tier lookup — the graph endpoint spells kinds lowercase. */
export function kindTier(kind: string): KindTier | undefined {
  return KIND_TIERS_FOLDED.get(kind.toLowerCase());
}

// (The well-known TYPE and LIFECYCLE value lists moved server-side: V15/V16 seed them into
// the admin-curated registries, and the editor's pickers read those via useEntityTypes /
// useLifecycleOptions.)

// The grammars from .claude/docs/backstage-descriptor-format.md, identical to the server's
// validateCatalogFile (catalog/CatalogFile.kt) — keep the two in sync.
const NAME_RE = /^[A-Za-z0-9]+(?:[-_.][A-Za-z0-9]+)*$/;
// Exported: utils/namespaceForm.ts validates namespace-dictionary values against the same
// grammar (the descriptor format owns the rule, the dictionary borrows it — like the server).
export const NAMESPACE_RE = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
export const TAG_RE = /^[a-z0-9:+#]+(?:-[a-z0-9:+#]+)*$/;
const KEY_PREFIX_RE = /^[a-z0-9]+(?:-[a-z0-9]+)*(?:\.[a-z0-9]+(?:-[a-z0-9]+)*)*$/;
const KIND_RE = /^[A-Za-z][A-Za-z0-9]*$/;

const SERVER_WRITTEN_ANNOTATIONS = new Set([
  "backstage.io/managed-by-location",
  "backstage.io/managed-by-origin-location",
  "backstage.io/orphan",
]);

type KeyValueRow = { key: string; value: string };
type LinkRow = { url: string; title: string; icon: string };

/** The form values shared by CreateCatalogFile and EditCatalogFile (the shared-vocab idiom). */
export type CatalogFileFormValues = {
  kind: EntityKind;
  name: string;
  namespace: string;
  title: string;
  description: string;
  tags: string[];
  labels: KeyValueRow[];
  annotations: KeyValueRow[];
  links: LinkRow[];
  type: string;
  lifecycle: string;
  owner: string;
  system: string;
  subcomponentOf: string;
  providesApis: string[];
  consumesApis: string[];
  dependsOn: string[];
  dependencyOf: string[];
  definition: string;
  profileDisplayName: string;
  profileEmail: string;
  profilePicture: string;
  parent: string;
  children: string[];
  members: string[];
  memberOf: string[];
  domain: string;
  subdomainOf: string;
  /** The optional source reference — envelope state beside the document, never in the YAML. */
  sourceUrl: string;
};

/** A fresh set of initial form values — a factory so form instances never share array refs. */
export function emptyCatalogFileForm(): CatalogFileFormValues {
  return {
    kind: "Component",
    name: "",
    namespace: "",
    title: "",
    description: "",
    tags: [],
    labels: [],
    annotations: [],
    links: [],
    type: "",
    lifecycle: "",
    owner: "",
    system: "",
    subcomponentOf: "",
    providesApis: [],
    consumesApis: [],
    dependsOn: [],
    dependencyOf: [],
    definition: "",
    profileDisplayName: "",
    profileEmail: "",
    profilePicture: "",
    parent: "",
    children: [],
    members: [],
    memberOf: [],
    domain: "",
    subdomainOf: "",
    sourceUrl: "",
  };
}

// The client mirror of the server's per-kind field tables (catalog/CatalogFile.kt): which
// spec fields a kind carries, and which it requires. Keep the three in sync.
export type SpecFieldName =
  | "type"
  | "lifecycle"
  | "owner"
  | "system"
  | "subcomponentOf"
  | "providesApis"
  | "consumesApis"
  | "dependsOn"
  | "dependencyOf"
  | "definition"
  | "profile"
  | "parent"
  | "children"
  | "members"
  | "memberOf"
  | "domain"
  | "subdomainOf";

const KIND_FIELDS: Record<EntityKind, readonly SpecFieldName[]> = {
  Component: [
    "type", "lifecycle", "owner", "system", "subcomponentOf",
    "providesApis", "consumesApis", "dependsOn", "dependencyOf",
  ],
  API: ["type", "lifecycle", "owner", "system", "definition"],
  System: ["type", "owner", "domain"],
  Domain: ["type", "owner", "subdomainOf"],
  Resource: ["type", "owner", "system", "dependsOn", "dependencyOf"],
  Group: ["type", "profile", "parent", "children", "members"],
  User: ["profile", "memberOf"],
};

const KIND_REQUIRED: Record<EntityKind, readonly SpecFieldName[]> = {
  Component: ["type", "lifecycle", "owner"],
  API: ["type", "lifecycle", "owner", "definition"],
  System: ["owner"],
  Domain: ["owner"],
  Resource: ["type", "owner"],
  Group: ["type"],
  User: [],
};

/** The multi-value relation fields, in the editor's render order. */
export const RELATION_FIELDS = [
  "providesApis",
  "consumesApis",
  "dependsOn",
  "dependencyOf",
  "children",
  "members",
  "memberOf",
] as const satisfies readonly SpecFieldName[];

export function fieldApplies(kind: EntityKind, field: SpecFieldName): boolean {
  return KIND_FIELDS[kind].includes(field);
}

export function fieldRequired(kind: EntityKind, field: SpecFieldName): boolean {
  return KIND_REQUIRED[kind].includes(field);
}

export function isValidName(value: string): boolean {
  return value.length >= 1 && value.length <= MAX_ENTITY_PART_LENGTH && NAME_RE.test(value);
}

export function isValidKey(key: string): boolean {
  const slash = key.indexOf("/");
  if (slash !== key.lastIndexOf("/")) return false;
  const prefix = slash >= 0 ? key.slice(0, slash) : null;
  const name = slash >= 0 ? key.slice(slash + 1) : key;
  if (prefix !== null && (prefix.length < 1 || prefix.length > 253 || !KEY_PREFIX_RE.test(prefix))) {
    return false;
  }
  return isValidName(name);
}

/** Format check of `[kind:][namespace/]name` — resolution is the Errors report's job. */
export function isValidEntityRef(ref: string): boolean {
  const colon = ref.indexOf(":");
  if (colon !== ref.lastIndexOf(":")) return false;
  const kind = colon >= 0 ? ref.slice(0, colon) : null;
  const rest = colon >= 0 ? ref.slice(colon + 1) : ref;
  if (kind !== null && (kind.length > MAX_ENTITY_PART_LENGTH || !KIND_RE.test(kind))) return false;
  const slash = rest.indexOf("/");
  if (slash !== rest.lastIndexOf("/")) return false;
  const namespace = slash >= 0 ? rest.slice(0, slash) : null;
  const name = slash >= 0 ? rest.slice(slash + 1) : rest;
  if (
    namespace !== null &&
    (namespace.length < 1 ||
      namespace.length > MAX_ENTITY_PART_LENGTH ||
      !NAMESPACE_RE.test(namespace.toLowerCase()))
  ) {
    return false;
  }
  return isValidName(name);
}

function isAbsoluteUri(url: string): boolean {
  try {
    new URL(url);
    return true;
  } catch {
    return false;
  }
}

// Per-kind-aware rule factories: a rule returns null when its field doesn't apply to the
// current kind, so switching kinds never leaves stale errors on hidden fields.
function singleWordRule(field: SpecFieldName, t: TFunction) {
  return (value: string, values: CatalogFileFormValues) => {
    if (!fieldApplies(values.kind, field)) return null;
    const v = value.trim();
    if (!v) return fieldRequired(values.kind, field) ? t("catalog.validation.required") : null;
    return v.length <= MAX_ENTITY_PART_LENGTH && !/\s/.test(v) ? null : t("catalog.validation.singleWord");
  };
}

// Grammar only — resolution is a SOFT concern now: the live check panel advises while
// typing, and a strict save's 400 opens the Save-anyway modal with the server's findings
// (which also cover registry problems the client can't see). Blocking here would make
// imported-with-findings files uneditable.
function refRule(field: SpecFieldName, t: TFunction) {
  return (value: string, values: CatalogFileFormValues) => {
    if (!fieldApplies(values.kind, field)) return null;
    const v = value.trim();
    if (!v) return fieldRequired(values.kind, field) ? t("catalog.validation.required") : null;
    return isValidEntityRef(v) ? null : t("catalog.validation.ref");
  };
}

function refArrayRule(field: SpecFieldName, t: TFunction) {
  return (values_: string[], values: CatalogFileFormValues) => {
    if (!fieldApplies(values.kind, field)) return null;
    const trimmed = values_.map((r) => r.trim()).filter(Boolean);
    const bad = trimmed.find((r) => !isValidEntityRef(r));
    return bad === undefined ? null : t("catalog.validation.refEntry", { ref: bad });
  };
}

/**
 * Validation rules shared by the create and edit pages (mirrors the server's STRUCTURAL
 * checks — grammars, per-kind applicability/requiredness). Reference RESOLUTION is soft
 * and deliberately absent: the server reports it via /check and the Save-anyway flow.
 */
export function catalogFileFormValidation(t: TFunction) {
  return {
    name: (value: string) => (isValidName(value.trim()) ? null : t("catalog.validation.name")),
    namespace: (value: string) => {
      const v = value.trim().toLowerCase();
      if (!v) return null; // blank = the default namespace
      return v.length <= MAX_ENTITY_PART_LENGTH && NAMESPACE_RE.test(v)
        ? null
        : t("catalog.validation.namespace");
    },
    tags: (values: string[]) => {
      const bad = values.find(
        (tag) => tag.length < 1 || tag.length > MAX_ENTITY_PART_LENGTH || !TAG_RE.test(tag),
      );
      return bad === undefined ? null : t("catalog.validation.tag", { tag: bad });
    },
    labels: {
      key: (value: string) => (isValidKey(value.trim()) ? null : t("catalog.validation.key")),
      value: (value: string) =>
        isValidName(value.trim()) ? null : t("catalog.validation.labelValue"),
    },
    annotations: {
      key: (value: string) => {
        const v = value.trim();
        if (!isValidKey(v)) return t("catalog.validation.key");
        return SERVER_WRITTEN_ANNOTATIONS.has(v) ? t("catalog.validation.reservedKey") : null;
      },
      value: (value: string) =>
        value.length <= MAX_ANNOTATION_VALUE_LENGTH
          ? null
          : t("catalog.validation.annotationValueLength"),
    },
    links: {
      url: (value: string) => (isAbsoluteUri(value.trim()) ? null : t("catalog.validation.url")),
      icon: (value: string) => {
        const v = value.trim();
        return !v || isValidName(v) ? null : t("catalog.validation.icon");
      },
    },
    type: singleWordRule("type", t),
    lifecycle: singleWordRule("lifecycle", t),
    owner: refRule("owner", t),
    system: refRule("system", t),
    subcomponentOf: refRule("subcomponentOf", t),
    providesApis: refArrayRule("providesApis", t),
    consumesApis: refArrayRule("consumesApis", t),
    dependsOn: refArrayRule("dependsOn", t),
    dependencyOf: refArrayRule("dependencyOf", t),
    definition: (value: string, values: CatalogFileFormValues) => {
      if (!fieldApplies(values.kind, "definition")) return null;
      const v = value.trim();
      if (!v) return t("catalog.validation.required");
      return v.length <= MAX_DEFINITION_LENGTH ? null : t("catalog.validation.definitionLength");
    },
    profileEmail: (value: string, values: CatalogFileFormValues) => {
      if (!fieldApplies(values.kind, "profile")) return null;
      return value.trim().length <= MAX_PROFILE_EMAIL_LENGTH
        ? null
        : t("catalog.validation.profileEmailLength");
    },
    profilePicture: (value: string, values: CatalogFileFormValues) => {
      if (!fieldApplies(values.kind, "profile")) return null;
      const v = value.trim();
      return !v || isAbsoluteUri(v) ? null : t("catalog.validation.url");
    },
    // The server's static sourceUrl guards (sanitizedSourceUrl): absolute https, no
    // credentials, sane length — the public-host check stays a fetch-time concern.
    sourceUrl: (value: string) => {
      const v = value.trim();
      if (!v) return null;
      if (v.length > MAX_SOURCE_URL_LENGTH) return t("catalog.validation.sourceUrl");
      try {
        const url = new URL(v);
        return url.protocol === "https:" && !url.username && !url.password
          ? null
          : t("catalog.validation.sourceUrl");
      } catch {
        return t("catalog.validation.sourceUrl");
      }
    },
    parent: refRule("parent", t),
    children: refArrayRule("children", t),
    members: refArrayRule("members", t),
    memberOf: refArrayRule("memberOf", t),
    domain: refRule("domain", t),
    subdomainOf: refRule("subdomainOf", t),
  };
}

const trimmedTags = (values: string[]) => values.map((v) => v.trim()).filter(Boolean);
const orUndefined = (value: string) => value.trim() || undefined;

type EntitySpecWire = CatalogFileRequest["spec"];

// The kind's fields only — foreign values a kind switch left behind are stripped, mirroring
// the server's forbidden-field rule.
function specFor(values: CatalogFileFormValues): EntitySpecWire {
  const kind = values.kind;
  const has = (field: SpecFieldName) => fieldApplies(kind, field);
  const profile =
    has("profile") &&
    (values.profileDisplayName.trim() || values.profileEmail.trim() || values.profilePicture.trim())
      ? {
          displayName: orUndefined(values.profileDisplayName),
          email: orUndefined(values.profileEmail),
          picture: orUndefined(values.profilePicture),
        }
      : undefined;
  return {
    type: has("type") ? orUndefined(values.type) : undefined,
    lifecycle: has("lifecycle") ? orUndefined(values.lifecycle) : undefined,
    owner: has("owner") ? orUndefined(values.owner) : undefined,
    system: has("system") ? orUndefined(values.system) : undefined,
    subcomponentOf: has("subcomponentOf") ? orUndefined(values.subcomponentOf) : undefined,
    providesApis: has("providesApis") ? trimmedTags(values.providesApis) : undefined,
    consumesApis: has("consumesApis") ? trimmedTags(values.consumesApis) : undefined,
    dependsOn: has("dependsOn") ? trimmedTags(values.dependsOn) : undefined,
    dependencyOf: has("dependencyOf") ? trimmedTags(values.dependencyOf) : undefined,
    definition: has("definition") ? values.definition.trim() || undefined : undefined,
    profile,
    parent: has("parent") ? orUndefined(values.parent) : undefined,
    // PRESENT-and-possibly-empty by contract for Group/User (the Backstage rule).
    children: has("children") ? trimmedTags(values.children) : undefined,
    members: has("members") ? trimmedTags(values.members) : undefined,
    memberOf: has("memberOf") ? trimmedTags(values.memberOf) : undefined,
    domain: has("domain") ? orUndefined(values.domain) : undefined,
    subdomainOf: has("subdomainOf") ? orUndefined(values.subdomainOf) : undefined,
  };
}

/**
 * Form → wire shape: trims everything, drops empties, folds the namespace like the server.
 * A blank namespace means "the ADMIN-flagged default entry": the submit path sends it blank
 * (the server resolves authoritatively), while the editor's preview/check path passes the
 * flagged value as [defaultNamespace] so the YAML preview shows what will actually be stored.
 */
export function toCatalogFileRequest(
  values: CatalogFileFormValues,
  defaultNamespace = "",
): CatalogFileRequest {
  const labels = Object.fromEntries(
    values.labels.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value.trim()]),
  );
  const annotations = Object.fromEntries(
    values.annotations.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value.trim()]),
  );
  return {
    kind: values.kind,
    metadata: {
      name: values.name.trim(),
      namespace: values.namespace.trim().toLowerCase() || defaultNamespace,
      title: values.title.trim() || undefined,
      description: values.description.trim() || undefined,
      labels,
      annotations,
      tags: trimmedTags(values.tags),
      links: values.links
        .filter((l) => l.url.trim())
        .map((l) => ({
          url: l.url.trim(),
          title: l.title.trim() || undefined,
          icon: l.icon.trim() || undefined,
        })),
    },
    spec: specFor(values),
  };
}

/** Wire shape → form values (the edit page's prefill). */
export function fromCatalogFileResponse(file: CatalogFileResponse): CatalogFileFormValues {
  return {
    kind: (ENTITY_KINDS as readonly string[]).includes(file.kind)
      ? (file.kind as EntityKind)
      : "Component",
    name: file.metadata.name,
    namespace: file.metadata.namespace ?? "",
    title: file.metadata.title ?? "",
    description: file.metadata.description ?? "",
    tags: file.metadata.tags ?? [],
    labels: Object.entries(file.metadata.labels ?? {}).map(([key, value]) => ({ key, value })),
    annotations: Object.entries(file.metadata.annotations ?? {}).map(([key, value]) => ({
      key,
      value,
    })),
    links: (file.metadata.links ?? []).map((l) => ({
      url: l.url,
      title: l.title ?? "",
      icon: l.icon ?? "",
    })),
    type: file.spec.type ?? "",
    lifecycle: file.spec.lifecycle ?? "",
    owner: file.spec.owner ?? "",
    system: file.spec.system ?? "",
    subcomponentOf: file.spec.subcomponentOf ?? "",
    providesApis: file.spec.providesApis ?? [],
    consumesApis: file.spec.consumesApis ?? [],
    dependsOn: file.spec.dependsOn ?? [],
    dependencyOf: file.spec.dependencyOf ?? [],
    definition: file.spec.definition ?? "",
    profileDisplayName: file.spec.profile?.displayName ?? "",
    profileEmail: file.spec.profile?.email ?? "",
    profilePicture: file.spec.profile?.picture ?? "",
    parent: file.spec.parent ?? "",
    children: file.spec.children ?? [],
    members: file.spec.members ?? [],
    memberOf: file.spec.memberOf ?? [],
    domain: file.spec.domain ?? "",
    subdomainOf: file.spec.subdomainOf ?? "",
    sourceUrl: file.sourceUrl ?? "",
  };
}
