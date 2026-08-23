import { stringify } from "yaml";
import type { CatalogFileRequest } from "../api/catalogFiles";

/**
 * Renders the canonical catalog-info.yaml for one stored document: fixed key order
 * (apiVersion, kind, metadata, spec — each section in the descriptor reference's order),
 * empties omitted, the `default` namespace left implicit (the Backstage convention).
 * Client-side by design in this slice — server-side canonical YAML arrives with the
 * combined-render feature.
 */
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

  const spec: Record<string, unknown> = {
    type: file.spec.type,
    lifecycle: file.spec.lifecycle,
    owner: file.spec.owner,
  };
  if (file.spec.system) spec.system = file.spec.system;
  if (file.spec.subcomponentOf) spec.subcomponentOf = file.spec.subcomponentOf;
  for (const field of ["providesApis", "consumesApis", "dependsOn", "dependencyOf"] as const) {
    const refs = file.spec[field];
    if (refs && refs.length > 0) spec[field] = refs;
  }

  return stringify(
    { apiVersion: "backstage.io/v1alpha1", kind: "Component", metadata, spec },
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
