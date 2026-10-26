/** Rows rendered at most in a dialog (which cannot window against the page scroll). */
export const MAX_DIALOG_ROWS = 300;

/**
 * Orders rows for a bounded list: every non-clean row (a refusal, a conflict, a finding) before
 * the clean ones, otherwise in the original order, so truncating the tail can only ever hide
 * rows that need no attention. The sort is stable and does not mutate its input.
 */
export function problemsFirst<T>(rows: readonly T[], isClean: (row: T) => boolean): T[] {
  return [...rows.filter((row) => !isClean(row)), ...rows.filter(isClean)];
}
