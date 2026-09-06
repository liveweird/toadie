// A minimal line-level diff (LCS) for the sync modal's DB-vs-repo YAML comparison — both
// sides are rendered through the canonical catalogInfoYaml generator, so a line diff is
// meaningful. Hand-rolled on purpose: no diff dependency for ~40 lines of classic DP.

type DiffLine = {
  kind: "same" | "removed" | "added";
  text: string;
};

export type YamlDiff =
  | { kind: "detailed"; lines: DiffLine[] }
  | { kind: "fallback"; before: string; after: string };

// Strict ceilings keep both the quadratic work and the detailed DOM bounded. Character
// length is checked before splitting, and matrix cells include the sentinel row/column.
export const YAML_DIFF_CHARACTER_LIMIT = 200_000;
export const YAML_DIFF_LINE_LIMIT = 2_000;
export const YAML_DIFF_MATRIX_CELL_LIMIT = 250_000;

/** The line-by-line diff of [before] → [after]: removed lines first at each divergence. */
export function diffLines(before: string, after: string): YamlDiff {
  if (before.length + after.length > YAML_DIFF_CHARACTER_LIMIT) {
    return { kind: "fallback", before, after };
  }
  const a = before.split("\n");
  const b = after.split("\n");
  if (
    a.length + b.length > YAML_DIFF_LINE_LIMIT ||
    (a.length + 1) * (b.length + 1) > YAML_DIFF_MATRIX_CELL_LIMIT
  ) {
    return { kind: "fallback", before, after };
  }
  // lcs[i][j] = length of the longest common subsequence of a[i..] and b[j..].
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0),
  );
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ kind: "same", text: a[i] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ kind: "removed", text: a[i] });
      i++;
    } else {
      out.push({ kind: "added", text: b[j] });
      j++;
    }
  }
  while (i < a.length) out.push({ kind: "removed", text: a[i++] });
  while (j < b.length) out.push({ kind: "added", text: b[j++] });
  return { kind: "detailed", lines: out };
}
