import type { TFunction } from "i18next";

/** The structural `params` string map a history event carries (`changed` + `<path>.from/to/added/removed`). */
export type EventParams = Record<string, string>;

/** The field paths an event recorded as changed (the comma-separated `changed` param). */
export function changedPaths(params: EventParams): string[] {
  return (params.changed ?? "").split(",").filter(Boolean);
}

/**
 * One body line per changed field that carries VALUES, or null. A field recorded by name only
 * (free text, a structured list, an over-long value) gets none — the sentence above already
 * names it, and a "Description: changed" line would only repeat it. `label` is the caller's
 * already-localized name for the path.
 */
export function changeLine(
  params: EventParams,
  path: string,
  label: string,
  t: TFunction,
): string | null {
  const from = params[`${path}.from`];
  const to = params[`${path}.to`];
  const added = params[`${path}.added`];
  const removed = params[`${path}.removed`];
  if (added && removed) return t("common.history.change.addedRemoved", { label, added, removed });
  if (added) return t("common.history.change.added", { label, added });
  if (removed) return t("common.history.change.removed", { label, removed });
  if (from && to) return t("common.history.change.fromTo", { label, from, to });
  if (to) return t("common.history.change.set", { label, to });
  if (from) return t("common.history.change.cleared", { label });
  return null;
}
