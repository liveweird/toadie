import { useState } from "react";
import LoadingBlock from "./LoadingBlock";
import { useTranslation } from "react-i18next";
import { Alert, Group, Pagination, Stack, Text, Timeline } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import type { ParseKeys, TFunction } from "i18next";
import { listCatalogFileEvents, type CatalogFileEvent } from "../api/catalogFiles";
import { formatDateTime } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";

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

function changedPaths(event: CatalogFileEvent): string[] {
  return (event.params.changed ?? "").split(",").filter(Boolean);
}

/** The one localized sentence a timeline entry leads with. */
function describeEvent(event: CatalogFileEvent, t: TFunction): string {
  const fields = changedPaths(event)
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
 * One body line per changed field that carries VALUES. A field recorded by name only (free
 * text, a structured list, an over-long value) gets none — the sentence above already names it,
 * and a "Description: changed" line would only repeat it.
 */
function changeLine(event: CatalogFileEvent, path: string, t: TFunction): string | null {
  const label = fieldLabel(path, t);
  const from = event.params[`${path}.from`];
  const to = event.params[`${path}.to`];
  const added = event.params[`${path}.added`];
  const removed = event.params[`${path}.removed`];
  if (added && removed) return t("catalog.change.addedRemoved", { label, added, removed });
  if (added) return t("catalog.change.added", { label, added });
  if (removed) return t("catalog.change.removed", { label, removed });
  if (from && to) return t("catalog.change.fromTo", { label, from, to });
  if (to) return t("catalog.change.set", { label, to });
  if (from) return t("catalog.change.cleared", { label });
  return null;
}

/**
 * A catalog file's change history: the server's structural events rendered in the viewer's
 * language, newest first (server-ordered — never re-sorted here).
 *
 * When a second entity gets a history, this component's spinner/error/empty/Timeline shell is
 * what to extract as Lettuce's shared `EventTimeline`; one copy does not earn the abstraction.
 * The rule that DOES carry over today: a failed load must never masquerade as an empty history.
 */
export default function CatalogFileHistory({ fileId }: { fileId: number }) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, error } = useQuery({
    // Inside the ["catalogFiles"] prefix, so every catalog mutation refreshes the trail.
    queryKey: ["catalogFiles", "events", fileId, page],
    queryFn: () => listCatalogFileEvents(fileId, page, PAGE_SIZE),
  });

  if (isLoading) return <LoadingBlock py="sm" />;
  if (isError) {
    return (
      <Alert color="red" variant="light">
        {loadErrorMessage(error, t)}
      </Alert>
    );
  }
  if (!data || data.items.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        {t("catalog.history.empty")}
      </Text>
    );
  }

  return (
    <Stack gap="sm">
      <Timeline bulletSize={12} lineWidth={2}>
        {data.items.map((event) => (
          <Timeline.Item key={event.id} title={describeEvent(event, t)}>
            {changedPaths(event)
              .map((path) => changeLine(event, path, t))
              .filter((line): line is string => line !== null)
              .map((line) => (
                <Text key={line} size="sm" c="dimmed">
                  {line}
                </Text>
              ))}
            <Text size="xs" c="dimmed">
              {event.userName} · {formatDateTime(event.timestamp, i18n.language)}
            </Text>
          </Timeline.Item>
        ))}
      </Timeline>
      {data.total > PAGE_SIZE && (
        <Group justify="center">
          <Pagination
            size="sm"
            value={page}
            onChange={setPage}
            total={Math.ceil(data.total / PAGE_SIZE)}
          />
        </Group>
      )}
    </Stack>
  );
}
