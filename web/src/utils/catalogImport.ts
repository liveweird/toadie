import { parseAllDocuments } from "yaml";
import type { CatalogFileRequest } from "../api/catalogFiles";

/**
 * The STRICT mirror of the catalogYaml generator: parses a (multi-document) catalog-info.yaml
 * into the structured request documents the import endpoint takes. Strictness is deliberate —
 * an unknown key marks THAT document invalid naming the key (silently dropping it would store
 * a lossy import); `apiVersion` is the one accepted-and-ignored root key. Per-document rule
 * validation (per-kind required fields, grammars, lengths) stays the server's job — the import
 * response reports those as INVALID rows.
 */

type CatalogParseError = {
  /** 0-based position among the file's non-empty documents. */
  index: number;
  message: string;
};

export type CatalogParseResult = {
  /** The cleanly mapped documents, in file order (only meaningful when `errors` is empty). */
  documents: CatalogFileRequest[];
  errors: CatalogParseError[];
};

const KINDS = ["Component", "API", "System", "Domain", "Resource", "Group", "User"] as const;

const ROOT_KEYS = ["apiVersion", "kind", "metadata", "spec"];
const METADATA_KEYS = [
  "name", "namespace", "title", "description", "labels", "annotations", "tags", "links",
];
const LINK_KEYS = ["url", "title", "icon"];
const SPEC_KEYS = [
  "type", "lifecycle", "owner", "system", "subcomponentOf",
  "providesApis", "consumesApis", "dependsOn", "dependencyOf",
  "definition", "profile", "parent", "children", "members", "memberOf", "domain", "subdomainOf",
];
const PROFILE_KEYS = ["displayName", "email", "picture"];

/** A per-document mapping failure — caught at the document boundary, never aborts the file. */
class DocumentError extends Error {}

function asMapping(value: unknown, where: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new DocumentError(`${where} must be a YAML mapping`);
  }
  return value as Record<string, unknown>;
}

function rejectUnknownKeys(obj: Record<string, unknown>, allowed: readonly string[], where: string) {
  for (const key of Object.keys(obj)) {
    if (!allowed.includes(key)) throw new DocumentError(`unknown key ${where}${key}`);
  }
}

function asString(value: unknown, key: string): string {
  if (typeof value !== "string") throw new DocumentError(`${key} must be a string`);
  return value;
}

function asStringArray(value: unknown, key: string): string[] {
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== "string")) {
    throw new DocumentError(`${key} must be a list of strings`);
  }
  return value as string[];
}

function asStringMap(value: unknown, key: string): Record<string, string> {
  const map = asMapping(value, key);
  for (const [k, v] of Object.entries(map)) {
    if (typeof v !== "string") throw new DocumentError(`${key}.${k} must be a string`);
  }
  return map as Record<string, string>;
}

function mapKind(value: unknown): NonNullable<CatalogFileRequest["kind"]> {
  if (value === undefined) throw new DocumentError("kind is required");
  const raw = asString(value, "kind");
  const kind = KINDS.find((k) => k.toLowerCase() === raw.trim().toLowerCase());
  if (!kind) throw new DocumentError(`kind must be one of ${KINDS.join(", ")}`);
  return kind;
}

function mapLinks(value: unknown): CatalogFileRequest["metadata"]["links"] {
  if (!Array.isArray(value)) throw new DocumentError("metadata.links must be a list");
  return value.map((entry, i) => {
    const link = asMapping(entry, `metadata.links[${i}]`);
    rejectUnknownKeys(link, LINK_KEYS, `metadata.links[${i}].`);
    return {
      url: asString(link.url ?? "", `metadata.links[${i}].url`),
      ...(link.title !== undefined ? { title: asString(link.title, `metadata.links[${i}].title`) } : {}),
      ...(link.icon !== undefined ? { icon: asString(link.icon, `metadata.links[${i}].icon`) } : {}),
    };
  });
}

function mapMetadata(value: unknown): CatalogFileRequest["metadata"] {
  if (value === undefined) throw new DocumentError("metadata is required");
  const raw = asMapping(value, "metadata");
  rejectUnknownKeys(raw, METADATA_KEYS, "metadata.");
  if (raw.name === undefined) throw new DocumentError("metadata.name is required");
  return {
    name: asString(raw.name, "metadata.name"),
    namespace: raw.namespace !== undefined ? asString(raw.namespace, "metadata.namespace") : "default",
    ...(raw.title !== undefined ? { title: asString(raw.title, "metadata.title") } : {}),
    ...(raw.description !== undefined
      ? { description: asString(raw.description, "metadata.description") }
      : {}),
    ...(raw.labels !== undefined ? { labels: asStringMap(raw.labels, "metadata.labels") } : {}),
    ...(raw.annotations !== undefined
      ? { annotations: asStringMap(raw.annotations, "metadata.annotations") }
      : {}),
    ...(raw.tags !== undefined ? { tags: asStringArray(raw.tags, "metadata.tags") } : {}),
    ...(raw.links !== undefined ? { links: mapLinks(raw.links) } : {}),
  };
}

function mapProfile(value: unknown): NonNullable<CatalogFileRequest["spec"]["profile"]> {
  const raw = asMapping(value, "spec.profile");
  rejectUnknownKeys(raw, PROFILE_KEYS, "spec.profile.");
  const out: NonNullable<CatalogFileRequest["spec"]["profile"]> = {};
  if (raw.displayName !== undefined) out.displayName = asString(raw.displayName, "spec.profile.displayName");
  if (raw.email !== undefined) out.email = asString(raw.email, "spec.profile.email");
  if (raw.picture !== undefined) out.picture = asString(raw.picture, "spec.profile.picture");
  return out;
}

const SPEC_STRING_KEYS = [
  "type", "lifecycle", "owner", "system", "subcomponentOf",
  "definition", "parent", "domain", "subdomainOf",
] as const;
const SPEC_ARRAY_KEYS = [
  "providesApis", "consumesApis", "dependsOn", "dependencyOf", "children", "members", "memberOf",
] as const;

function mapSpec(value: unknown): CatalogFileRequest["spec"] {
  // Absent spec maps to {} — the server's per-kind required-field rules then answer INVALID
  // with a message far better than a parse error could give.
  if (value === undefined) return {};
  const raw = asMapping(value, "spec");
  rejectUnknownKeys(raw, SPEC_KEYS, "spec.");
  const out: Record<string, unknown> = {};
  for (const key of SPEC_STRING_KEYS) {
    if (raw[key] !== undefined) out[key] = asString(raw[key], `spec.${key}`);
  }
  for (const key of SPEC_ARRAY_KEYS) {
    if (raw[key] !== undefined) out[key] = asStringArray(raw[key], `spec.${key}`);
  }
  if (raw.profile !== undefined) out.profile = mapProfile(raw.profile);
  return out as CatalogFileRequest["spec"];
}

function mapDocument(raw: unknown): CatalogFileRequest {
  const doc = asMapping(raw, "the document");
  rejectUnknownKeys(doc, ROOT_KEYS, "");
  // apiVersion is accepted and ignored — Toadie stores the descriptor, not the envelope.
  return { kind: mapKind(doc.kind), metadata: mapMetadata(doc.metadata), spec: mapSpec(doc.spec) };
}

/** Parses a (multi-document) catalog-info.yaml; every error carries its document's index. */
export function parseCatalogYaml(text: string): CatalogParseResult {
  const documents: CatalogFileRequest[] = [];
  const errors: CatalogParseError[] = [];
  let index = 0;
  for (const doc of parseAllDocuments(text)) {
    if (doc.errors.length > 0) {
      errors.push({ index, message: doc.errors[0].message });
      index += 1;
      continue;
    }
    const js: unknown = doc.toJS();
    if (js == null) continue; // a blank document (stray `---`) is not an entity
    try {
      documents.push(mapDocument(js));
    } catch (err) {
      if (!(err instanceof DocumentError)) throw err;
      errors.push({ index, message: err.message });
    }
    index += 1;
  }
  return { documents, errors };
}
