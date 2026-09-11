import type { TFunction } from "i18next";
import type { EntityFinding } from "../api/entities";
import classes from "../theme.module.css";

/**
 * Routes an entity's `findings` (the same validation a strict save enforces, re-evaluated
 * against the blueprint's CURRENT definition — see `.claude/docs/port-data-model.md`
 * "Lifecycle rules") to the editor control that produced them, so a stale/rejected entity
 * paints the problem ON the field, not only in the Alert above the form.
 *
 * Unlike the catalog's `FieldFindings` (`utils/fieldFindings.ts`), the wire `field` here IS
 * the form path already — `team`, `properties.<id>`, `relations.<id>` — no reference-splitting
 * needed, since an entity finding always names exactly one property/relation id or `team`.
 */
export interface EntityFieldFindings {
  forField: (field: string) => EntityFinding[];
}

/** Groups findings once per render so a control's lookup is a map hit, not a scan. */
export function indexEntityFindings(findings: readonly EntityFinding[]): EntityFieldFindings {
  const byField = new Map<string, EntityFinding[]>();
  for (const finding of findings) {
    const existing = byField.get(finding.field);
    if (existing) existing.push(finding);
    else byField.set(finding.field, [finding]);
  }
  return { forField: (field) => byField.get(field) ?? [] };
}

/** No findings at all — the stable empty lookup, so a findings-free render allocates nothing. */
export const NO_ENTITY_FINDINGS: EntityFieldFindings = indexEntityFindings([]);

/**
 * The edit page shows TWO finding sources that can overlap — the loaded entity's stale-vs-
 * current-blueprint `findings` and a just-rejected save's `findings` — merged into one list
 * for the Alert and the field paint, deduped by `field`+`code` (first occurrence wins) so a
 * finding both sources agree on is never shown twice.
 */
export function dedupeEntityFindings(
  stale: readonly EntityFinding[],
  saved: readonly EntityFinding[],
): EntityFinding[] {
  const seen = new Set<string>();
  const merged: EntityFinding[] = [];
  for (const finding of [...stale, ...saved]) {
    const key = `${finding.field}|${finding.code}`;
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(finding);
  }
  return merged;
}

/**
 * `TEAM_TARGET_MISSING` gets a friendlier, localized phrasing (the plan's rule — it's the one
 * ownership finding a user is likely to hit by simply deleting a team); every other code shows
 * the server's own message verbatim, the same posture the catalog's `errors.message.<STATUS>`
 * catalogue takes for everything it doesn't special-case.
 */
function entityFindingMessage(finding: EntityFinding, t: TFunction): string {
  return finding.code === "TEAM_TARGET_MISSING" ? t("entities.finding.teamTargetMissing") : finding.message;
}

type FindingProps =
  | { error: string; classNames: { input: string; error: string } }
  | Record<string, never>;

/**
 * The props to spread on an input so an entity finding shows on the field itself — orange
 * border and orange message, the catalog's `findingProps` convention reused: entity findings
 * also don't block the SAVE ATTEMPT by themselves (the server still 400s a strict save until
 * they're fixed, same continuity as a catalog soft finding routing through Save-anyway), so the
 * colour stays orange rather than red. Spread AFTER `form.getInputProps(...)` — later props
 * win — and pass `hardError` so a real client validation error always outranks a finding.
 */
export function entityFindingProps(
  findings: readonly EntityFinding[],
  t: TFunction,
  { hardError }: { hardError?: unknown } = {},
): FindingProps {
  if (hardError || findings.length === 0) return {};
  return {
    error: entityFindingMessage(findings[0], t),
    classNames: { input: classes.findingInput, error: classes.findingMessage },
  };
}
