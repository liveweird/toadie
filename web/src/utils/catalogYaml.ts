import { stringify } from "yaml";
import type { CatalogFileRequest } from "../api/catalogFiles";

/**
 * Renders the canonical catalog-info.yaml for one stored document: fixed key order
 * (apiVersion, kind, metadata, spec — each section in the descriptor reference's per-kind
 * order), empties omitted, the `default` namespace left implicit (the Backstage convention).
 * Two deliberate exceptions to empty-omission: Group `children` and User `memberOf` are
 * REQUIRED-present in Backstage, so they render even when empty.
 */

// Per-kind spec key order (the descriptor reference). "children"/"memberOf" marked with "!"
// render even when empty.
const SPEC_ORDER: Record<string, readonly string[]> = {
  Component: [
    "type", "lifecycle", "owner", "system", "subcomponentOf",
    "providesApis", "consumesApis", "dependsOn", "dependencyOf",
  ],
  API: ["type", "lifecycle", "owner", "system", "definition"],
  System: ["type", "owner", "domain"],
  Domain: ["type", "owner", "subdomainOf"],
  Resource: ["type", "owner", "system", "dependsOn", "dependencyOf"],
  Group: ["type", "profile", "parent", "!children", "members"],
  User: ["profile", "!memberOf"],
};

function profileDoc(profile: NonNullable<CatalogFileRequest["spec"]["profile"]>) {
  const out: Record<string, unknown> = {};
  if (profile.displayName) out.displayName = profile.displayName;
  if (profile.email) out.email = profile.email;
  if (profile.picture) out.picture = profile.picture;
  return Object.keys(out).length > 0 ? out : undefined;
}

function specDoc(kind: string, spec: CatalogFileRequest["spec"]): Record<string, unknown> {
  const source = spec as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const entry of SPEC_ORDER[kind] ?? SPEC_ORDER.Component) {
    const alwaysEmit = entry.startsWith("!");
    const key = alwaysEmit ? entry.slice(1) : entry;
    const value = key === "profile" && spec.profile ? profileDoc(spec.profile) : source[key];
    if (value == null) {
      if (alwaysEmit) out[key] = [];
      continue;
    }
    if (Array.isArray(value) && value.length === 0 && !alwaysEmit) continue;
    if (typeof value === "string" && value === "") continue;
    out[key] = value;
  }
  return out;
}

export function catalogInfoYaml(file: CatalogFileRequest): string {
  const metadata: Record<string, unknown> = { name: file.metadata.name };
  const namespace = file.metadata.namespace;
  if (namespace && namespace !== "default") metadata.namespace = namespace;
  if (file.metadata.title) metadata.title = file.metadata.title;
  if (file.metadata.description) metadata.description = file.metadata.description;
  if (file.metadata.labels && Object.keys(file.metadata.labels).length > 0) {
    metadata.labels = file.metadata.labels;
  }
  if (file.metadata.annotations && Object.keys(file.metadata.annotations).length > 0) {
    metadata.annotations = file.metadata.annotations;
  }
  if (file.metadata.tags && file.metadata.tags.length > 0) metadata.tags = file.metadata.tags;
  if (file.metadata.links && file.metadata.links.length > 0) {
    metadata.links = file.metadata.links.map((l) => ({
      url: l.url,
      ...(l.title ? { title: l.title } : {}),
      ...(l.icon ? { icon: l.icon } : {}),
    }));
  }

  const kind = file.kind ?? "Component";
  return stringify(
    { apiVersion: "backstage.io/v1alpha1", kind, metadata, spec: specDoc(kind, file.spec) },
    { indent: 2 },
  );
}

/** Hands the YAML to the browser as a file download (Backstage's canonical filename). */
export function downloadYaml(text: string, filename = "catalog-info.yaml") {
  const blob = new Blob([text], { type: "application/yaml" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
