import type { TFunction } from "i18next";
import type { CatalogFileRequest, CatalogFileResponse } from "../api/catalogFiles";

// Server limits (catalog/CatalogFile.kt) mirrored client-side.
export const MAX_ENTITY_PART_LENGTH = 63;
export const MAX_TITLE_LENGTH = 200;
export const MAX_DESCRIPTION_LENGTH = 2000;
const MAX_ANNOTATION_VALUE_LENGTH = 5000;
export const MAX_LINK_TITLE_LENGTH = 100;

// The well-known values from the Backstage descriptor reference — suggestions, not an enum
// (both fields are open strings by design).
export const WELL_KNOWN_TYPES = ["service", "website", "library"] as const;
export const WELL_KNOWN_LIFECYCLES = ["experimental", "production", "deprecated"] as const;

// The grammars from .claude/docs/backstage-descriptor-format.md, identical to the server's
// validateCatalogFile (catalog/CatalogFile.kt) — keep the two in sync.
const NAME_RE = /^[A-Za-z0-9]+(?:[-_.][A-Za-z0-9]+)*$/;
const NAMESPACE_RE = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const TAG_RE = /^[a-z0-9:+#]+(?:-[a-z0-9:+#]+)*$/;
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
};

export const EMPTY_CATALOG_FILE_FORM: CatalogFileFormValues = {
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
};

export function isValidName(value: string): boolean {
  return value.length >= 1 && value.length <= MAX_ENTITY_PART_LENGTH && NAME_RE.test(value);
}

function isValidKey(key: string): boolean {
  const slash = key.indexOf("/");
  if (slash !== key.lastIndexOf("/")) return false;
  const prefix = slash >= 0 ? key.slice(0, slash) : null;
  const name = slash >= 0 ? key.slice(slash + 1) : key;
  if (prefix !== null && (prefix.length < 1 || prefix.length > 253 || !KEY_PREFIX_RE.test(prefix))) {
    return false;
  }
  return isValidName(name);
}

/** Format check of `[kind:][namespace/]name` — resolution is the future cross-check feature. */
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

function singleWordError(t: TFunction) {
  return (value: string) => {
    const v = value.trim();
    if (!v) return t("catalog.validation.required");
    return v.length <= MAX_ENTITY_PART_LENGTH && !/\s/.test(v) ? null : t("catalog.validation.singleWord");
  };
}

function optionalRefError(t: TFunction) {
  return (value: string) => {
    const v = value.trim();
    return !v || isValidEntityRef(v) ? null : t("catalog.validation.ref");
  };
}

function refArrayError(t: TFunction) {
  return (values: string[]) => {
    const bad = values.map((r) => r.trim()).find((r) => r && !isValidEntityRef(r));
    return bad === undefined ? null : t("catalog.validation.refEntry", { ref: bad });
  };
}

/** Validation rules shared by the create and edit pages (mirrors the server's checks). */
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
      url: (value: string) =>
        isAbsoluteUri(value.trim()) ? null : t("catalog.validation.url"),
      icon: (value: string) => {
        const v = value.trim();
        return !v || isValidName(v) ? null : t("catalog.validation.icon");
      },
    },
    type: singleWordError(t),
    lifecycle: singleWordError(t),
    owner: (value: string) => {
      const v = value.trim();
      if (!v) return t("catalog.validation.required");
      return isValidEntityRef(v) ? null : t("catalog.validation.ref");
    },
    system: optionalRefError(t),
    subcomponentOf: optionalRefError(t),
    providesApis: refArrayError(t),
    consumesApis: refArrayError(t),
    dependsOn: refArrayError(t),
    dependencyOf: refArrayError(t),
  };
}

const trimmedTags = (values: string[]) => values.map((v) => v.trim()).filter(Boolean);

/** Form → wire shape: trims everything, drops empties, folds the namespace like the server. */
export function toCatalogFileRequest(values: CatalogFileFormValues): CatalogFileRequest {
  const labels = Object.fromEntries(
    values.labels.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value.trim()]),
  );
  const annotations = Object.fromEntries(
    values.annotations.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value.trim()]),
  );
  return {
    metadata: {
      name: values.name.trim(),
      namespace: values.namespace.trim().toLowerCase() || "default",
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
    spec: {
      type: values.type.trim(),
      lifecycle: values.lifecycle.trim(),
      owner: values.owner.trim(),
      system: values.system.trim() || undefined,
      subcomponentOf: values.subcomponentOf.trim() || undefined,
      providesApis: trimmedTags(values.providesApis),
      consumesApis: trimmedTags(values.consumesApis),
      dependsOn: trimmedTags(values.dependsOn),
      dependencyOf: trimmedTags(values.dependencyOf),
    },
  };
}

/** Wire shape → form values (the edit page's prefill). */
export function fromCatalogFileResponse(file: CatalogFileResponse): CatalogFileFormValues {
  return {
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
    type: file.spec.type,
    lifecycle: file.spec.lifecycle,
    owner: file.spec.owner,
    system: file.spec.system ?? "",
    subcomponentOf: file.spec.subcomponentOf ?? "",
    providesApis: file.spec.providesApis ?? [],
    consumesApis: file.spec.consumesApis ?? [],
    dependsOn: file.spec.dependsOn ?? [],
    dependencyOf: file.spec.dependencyOf ?? [],
  };
}
