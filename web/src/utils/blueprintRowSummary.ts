import type {
  AggregationDraft,
  CalculationDraft,
  MirrorDraft,
  PropertyDraft,
  RelationDraft,
} from "./blueprintForm";

// EditorRowList's neutral gray header badge, one helper per row family — a one-line summary
// of literal Port tokens (never translated, the same rule as the field Selects themselves:
// these are wire values an admin meets again in the JSON preview) joined with " · ". "" = no
// badge (EditorRowList renders nothing for a blank string).
const SEP = " · ";

/** "string · date-time", "array · number" (the items type), "object · labeled-url" — the
 *  type plus whichever literal Port token most distinguishes the row. */
export function propertyBadge(draft: PropertyDraft): string {
  if (draft.type === "string" && draft.format) return [draft.type, draft.format].join(SEP);
  if (draft.type === "array" && draft.itemsType) return [draft.type, draft.itemsType].join(SEP);
  if (draft.type === "object" && draft.objectFormat) return [draft.type, draft.objectFormat].join(SEP);
  return draft.type;
}

/** "team · many" — the target blueprint plus the multiplicity token when the relation is many. */
export function relationBadge(draft: RelationDraft): string {
  const target = draft.target.trim();
  if (!target) return "";
  return draft.many ? [target, "many"].join(SEP) : target;
}

/** The mirrored path itself — the one thing that identifies a mirror property at a glance. */
export function mirrorBadge(draft: MirrorDraft): string {
  return draft.path.trim();
}

/** "type[ · format]" — a calculation property's declared result type plus its format when set. */
export function calculationBadge(draft: CalculationDraft): string {
  return draft.format ? [draft.type, draft.format].join(SEP) : draft.type;
}

/** "service · count" — the target blueprint plus the aggregation function. */
export function aggregationBadge(draft: AggregationDraft): string {
  const target = draft.target.trim();
  return target ? [target, draft.func].join(SEP) : draft.func;
}
