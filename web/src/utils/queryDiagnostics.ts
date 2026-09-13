// The bridge between the entity query language's server diagnostics
// (`.claude/docs/entity-query-language.md`) and this app's two consumers: the failed-graph-
// request 400 body (`entitySaveFindings`'s clone, reading a problem body defensively) and
// CodeMirror's `@codemirror/lint` gutter markers.

import type { Diagnostic } from "@codemirror/lint";
import { ApiError } from "../api/http";
import type { EntityQueryDiagnostic } from "../api/entities";

/**
 * A failed `getEntityGraph` request's `diagnostics` list (`EntityQueryProblem`), read
 * DEFENSIVELY off the 400 body via `ApiError`'s own public `body` field — `diagnostics` is
 * specific to this one response shape, not a generic RFC 7807 member. Any other error (a
 * non-400, a 400 without `diagnostics` — a repeated scalar parameter, an over-long `query`) or
 * non-ApiError answers an empty array, same as "nothing to show" — never throws.
 */
export function queryProblemDiagnostics(error: unknown): EntityQueryDiagnostic[] {
  if (!(error instanceof ApiError) || error.status !== 400) return [];
  const body = error.body as { diagnostics?: unknown } | null;
  return Array.isArray(body?.diagnostics) ? (body.diagnostics as EntityQueryDiagnostic[]) : [];
}

/** The 1-based line-start offsets of `doc` (offset 0 for line 1, and so on). */
function lineStartOffsets(doc: string): number[] {
  const offsets = [0];
  for (let i = 0; i < doc.length; i += 1) {
    if (doc[i] === "\n") offsets.push(i + 1);
  }
  return offsets;
}

function offsetOf(doc: string, starts: number[], line: number, column: number): number {
  const lineIndex = Math.min(Math.max(line - 1, 0), starts.length - 1);
  const raw = starts[lineIndex] + Math.max(column - 1, 0);
  return Math.min(Math.max(raw, 0), doc.length);
}

/**
 * Maps the server's `line`/`column` (1-based) / `endLine`/`endColumn` (exclusive) diagnostics
 * to `@codemirror/lint`'s character-offset `Diagnostic`s against `doc` — the SAME text the
 * diagnostics were computed against (the caller's current draft/query text). A positionless
 * diagnostic (the two evaluation-budget refusals) spans the whole document; a positioned one
 * missing its end spans one character. Every offset is clamped to `doc`'s length, since the
 * server-checked text and the editor's current text can differ by the time this runs.
 */
export function toLintDiagnostics(diagnostics: EntityQueryDiagnostic[], doc: string): Diagnostic[] {
  const starts = lineStartOffsets(doc);
  return diagnostics.map((diagnostic) => {
    const hasPosition = diagnostic.line != null && diagnostic.column != null;
    const from = hasPosition ? offsetOf(doc, starts, diagnostic.line!, diagnostic.column!) : 0;
    const to =
      hasPosition && diagnostic.endLine != null && diagnostic.endColumn != null
        ? offsetOf(doc, starts, diagnostic.endLine, diagnostic.endColumn)
        : hasPosition
          ? Math.min(from + 1, doc.length)
          : doc.length;
    const message = diagnostic.suggestion
      ? `${diagnostic.message} (Did you mean \`${diagnostic.suggestion}\`?)`
      : diagnostic.message;
    return { from: Math.min(from, to), to: Math.max(from, to), severity: "error", message };
  });
}
