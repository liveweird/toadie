import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import type { ParseKeys, TFunction } from "i18next";
import { Text } from "@mantine/core";
import type { Blueprint } from "../api/blueprints";
import { listEntityEvents, type Entity, type EntityEvent } from "../api/entities";
import { changedPaths, changeLine } from "../utils/eventChanges";
import { formatDateTime } from "../utils/relativeTime";
import EventTimeline from "./EventTimeline";

const PAGE_SIZE = 10;

const PROPERTIES_PREFIX = "properties.";
const RELATIONS_PREFIX = "relations.";

/** The envelope fields, named exactly as the editor's form names them. */
const FIELD_LABELS: Record<string, ParseKeys> = {
  identifier: "entities.field.identifier",
  title: "entities.field.title",
  icon: "entities.field.icon",
  team: "entities.field.team",
  sourceUrl: "entities.field.sourceUrl",
};

/**
 * A wire path as the editor labels it: the envelope fields through `entities.field.*`, a
 * property or relation by the TITLE its blueprint gives it (what the form shows), falling back
 * to the raw id once the blueprint no longer declares it. A path this build does not know
 * renders raw (the forward-compat rule: a newer server must never blank a line out).
 */
function fieldLabel(path: string, blueprint: Blueprint, t: TFunction): string {
  if (path.startsWith(PROPERTIES_PREFIX)) {
    const id = path.slice(PROPERTIES_PREFIX.length);
    return blueprint.schema.properties[id]?.title ?? id;
  }
  if (path.startsWith(RELATIONS_PREFIX)) {
    const id = path.slice(RELATIONS_PREFIX.length);
    return blueprint.relations[id]?.title ?? id;
  }
  const key = FIELD_LABELS[path];
  return key ? t(key) : path;
}

/** The one localized sentence a timeline entry leads with. */
function describeEvent(event: EntityEvent, fields: string, t: TFunction): string {
  const { params } = event;
  switch (event.type) {
    case "CREATED":
      return params.origin === "import" ? t("entities.event.createdImport") : t("entities.event.created");
    case "UPDATED":
      if (params.origin === "import") return t("entities.event.updatedImport", { fields });
      if (params.origin === "import-deferred") return t("entities.event.updatedImportDeferred", { fields });
      if (params.origin === "bulk") return t("entities.event.updatedBulk", { fields });
      if (params.origin === "cascade" && params.causeFrom && params.causeTo) {
        return t("entities.event.updatedCascade", {
          fields,
          blueprint: params.causeBlueprint ?? "",
          from: params.causeFrom,
          to: params.causeTo,
        });
      }
      return t("entities.event.updated", { fields });
    case "SYNCED":
      return fields ? t("entities.event.syncedChanges", { fields }) : t("entities.event.synced");
    case "DELETED":
      return t("entities.event.deleted");
    default:
      // Forward-compat: an event kind this client build doesn't know yet — show the raw type.
      return event.type;
  }
}

/** A positive whole number from a params string, else null (a malformed value adds no line). */
function positiveCount(value: string | undefined): number | null {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
}

/** The lines under the sentence: one per valued change, then the overflow and cascade notes. */
function eventLines(event: EntityEvent, blueprint: Blueprint, t: TFunction): string[] {
  const lines = changedPaths(event.params)
    .map((path) => changeLine(event.params, path, fieldLabel(path, blueprint, t), t))
    .filter((line): line is string => line !== null);
  const more = positiveCount(event.params.more);
  if (more !== null) lines.push(t("entities.history.more", { count: more }));
  const cascaded = positiveCount(event.params.cascaded);
  if (cascaded !== null) lines.push(t("entities.history.cascaded", { count: cascaded }));
  return lines;
}

/** A name with the account state that matters when reading history: revoked or deleted accounts stay named. */
function actorName(name: string, deleted: boolean, serviceAccount: boolean, t: TFunction): string {
  if (serviceAccount && deleted) return t("common.history.actor.serviceAccountRevoked", { name });
  if (serviceAccount) return t("common.history.actor.serviceAccount", { name });
  if (deleted) return t("common.history.actor.deleted", { name });
  return name;
}

/**
 * An entity's change history: the server's structural events rendered in the viewer's language,
 * newest first (server-ordered — never re-sorted here). The spinner / error / empty / Timeline
 * shell is the shared `EventTimeline`; this component keeps the query, the entity sentences and
 * the blueprint-aware field labels. Entities that predate the history carry no CREATED event, so
 * the last page closes with a footer naming who created the entity and when.
 */
export default function EntityHistory({ entity, blueprint }: { entity: Entity; blueprint: Blueprint }) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, error } = useQuery({
    // Inside the ["entities"] prefix, so every entity mutation and sync refreshes the trail.
    queryKey: ["entities", "events", entity.id, page],
    queryFn: () => listEntityEvents(entity.id, page, PAGE_SIZE),
  });

  const lastPage = Math.max(1, Math.ceil((data?.total ?? 0) / PAGE_SIZE));
  const oldest = data?.items[data.items.length - 1];
  const preHistory = data !== undefined && page >= lastPage && (oldest === undefined || oldest.type !== "CREATED");
  const footer = preHistory ? (
    <Text size="sm" c="dimmed">
      {t("entities.history.preHistory", {
        name: actorName(entity.creatorName, entity.creatorDeleted, false, t),
        date: formatDateTime(entity.createdAt, i18n.language),
      })}
    </Text>
  ) : undefined;

  return (
    <EventTimeline
      items={data?.items.map((event) => {
        const fields = changedPaths(event.params)
          .map((path) => fieldLabel(path, blueprint, t))
          .join(", ");
        return {
          key: event.id,
          title: describeEvent(event, fields, t),
          lines: eventLines(event, blueprint, t),
          who: actorName(event.userName, event.userDeleted, event.userServiceAccount, t),
          when: event.timestamp,
        };
      })}
      isLoading={isLoading}
      isError={isError}
      error={error}
      emptyMessage={t("common.history.empty")}
      page={page}
      pageSize={PAGE_SIZE}
      total={data?.total ?? 0}
      onPageChange={setPage}
      footer={footer}
    />
  );
}
