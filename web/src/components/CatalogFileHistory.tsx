import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import type { ParseKeys, TFunction } from "i18next";
import { listCatalogFileEvents, type CatalogFileEvent } from "../api/catalogFiles";
import { changedPaths, changeLine } from "../utils/eventChanges";
import EventTimeline from "./EventTimeline";

const PAGE_SIZE = 10;

const LABELS_PREFIX = "metadata.labels.";
const ANNOTATIONS_PREFIX = "metadata.annotations.";

/**
 * The wire's field paths mapped onto the labels the editor already uses for the same fields —
 * the history names a field exactly as the form does. A path this build does not know renders
 * raw (the forward-compat rule: a newer server must never blank a line out).
 */
const FIELD_LABELS: Record<string, ParseKeys> = {
  kind: "catalog.field.kind",
  "metadata.name": "common.field.name",
  "metadata.namespace": "catalog.field.namespace",
  "metadata.title": "catalog.field.title",
  "metadata.description": "catalog.field.description",
  "metadata.tags": "catalog.field.tags",
  "metadata.links": "catalog.section.links",
  "spec.type": "catalog.field.type",
  "spec.lifecycle": "catalog.field.lifecycle",
  "spec.owner": "catalog.field.owner",
  "spec.system": "catalog.field.system",
  "spec.subcomponentOf": "catalog.field.subcomponentOf",
  "spec.providesApis": "catalog.field.providesApis",
  "spec.consumesApis": "catalog.field.consumesApis",
  "spec.dependsOn": "catalog.field.dependsOn",
  "spec.dependencyOf": "catalog.field.dependencyOf",
  "spec.definition": "catalog.field.definition",
  "spec.parent": "catalog.field.parent",
  "spec.children": "catalog.field.children",
  "spec.members": "catalog.field.members",
  "spec.memberOf": "catalog.field.memberOf",
  "spec.domain": "catalog.field.domain",
  "spec.subdomainOf": "catalog.field.subdomainOf",
  "spec.profile.displayName": "catalog.field.profileDisplayName",
  "spec.profile.email": "catalog.field.profileEmail",
  "spec.profile.picture": "catalog.field.profilePicture",
  sourceUrl: "catalog.field.sourceUrl",
};

/** Labels and annotations diff per ENTRY, so their paths carry the key that moved. */
function fieldLabel(path: string, t: TFunction): string {
  if (path.startsWith(LABELS_PREFIX)) {
    return t("catalog.history.labelField", { key: path.slice(LABELS_PREFIX.length) });
  }
  if (path.startsWith(ANNOTATIONS_PREFIX)) {
    return t("catalog.history.annotationField", { key: path.slice(ANNOTATIONS_PREFIX.length) });
  }
  const key = FIELD_LABELS[path];
  return key ? t(key) : path;
}

/** The one localized sentence a timeline entry leads with. */
function describeEvent(event: CatalogFileEvent, t: TFunction): string {
  const fields = changedPaths(event.params)
    .map((path) => fieldLabel(path, t))
    .join(", ");
  switch (event.type) {
    case "CREATED":
      // The import loop marks its rows; i18next's context picks the wording.
      return t("catalog.event.created", { context: event.params.origin });
    case "UPDATED":
      return t("catalog.event.updated", { fields });
    case "SYNCED":
      return fields ? t("catalog.event.synced_changes", { fields }) : t("catalog.event.synced");
    case "DELETED":
      return t("catalog.event.deleted");
    default:
      // Forward-compat: an event kind this client build doesn't know yet — show the raw type.
      return event.type;
  }
}

/**
 * A catalog file's change history: the server's structural events rendered in the viewer's
 * language, newest first (server-ordered — never re-sorted here). The spinner / error / empty /
 * Timeline shell is the shared `EventTimeline`; this component keeps the query and the
 * catalog-specific sentences and field labels.
 */
export default function CatalogFileHistory({ fileId }: { fileId: number }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, error } = useQuery({
    // Inside the ["catalogFiles"] prefix, so every catalog mutation refreshes the trail.
    queryKey: ["catalogFiles", "events", fileId, page],
    queryFn: () => listCatalogFileEvents(fileId, page, PAGE_SIZE),
  });

  return (
    <EventTimeline
      items={data?.items.map((event) => ({
        key: event.id,
        title: describeEvent(event, t),
        lines: changedPaths(event.params)
          .map((path) => changeLine(event.params, path, fieldLabel(path, t), t))
          .filter((line): line is string => line !== null),
        who: event.userName,
        when: event.timestamp,
      }))}
      isLoading={isLoading}
      isError={isError}
      error={error}
      emptyMessage={t("common.history.empty")}
      page={page}
      pageSize={PAGE_SIZE}
      total={data?.total ?? 0}
      onPageChange={setPage}
    />
  );
}
