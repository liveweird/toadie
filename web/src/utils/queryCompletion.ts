// The entity query bar's schema-aware completion source — a PURE function of the text before
// the cursor plus the current blueprint/hierarchy registry snapshot, wired into
// `components/QueryEditor.tsx` via `autocompletion({ override: [...] })`. Best-effort: it
// recognizes the common contexts of `.claude/docs/entity-query-language.md`'s grammar via small
// regex heuristics rather than a real parser (the server's `entityquery/QueryLexer.kt` +
// `QueryParser.kt` are the one authoritative grammar) — an ambiguous or malformed prefix simply
// answers `null` (no suggestions), never throws.

import type { Completion } from "@codemirror/autocomplete";
import type { Blueprint } from "../api/blueprints";
import { CLAUSE_KEYWORDS, METAS, quoteIfNeeded, stringLiteral } from "./queryLanguage";

export type QueryCompletionSchema = {
  blueprints: Blueprint[];
  /** Active hierarchy identifiers (the `hierarchies` dictionary's values). */
  hierarchies: string[];
};

export type QueryCompletionResult = { from: number; options: Completion[] };

const NAME_PARTIAL_RE = /^[A-Za-z0-9_$`]*$/;

function nameOption(name: string, detail?: string): Completion {
  return { label: name, detail, apply: quoteIfNeeded(name) };
}

function startsWithFold(value: string, partial: string): boolean {
  return value.toLowerCase().startsWith(partial.toLowerCase());
}

function blueprintOptions(blueprints: Blueprint[], partial: string): Completion[] {
  return blueprints
    .filter((b) => startsWithFold(b.identifier, partial))
    .map((b) => nameOption(b.identifier, b.title));
}

function edgeTypeOptions(names: readonly string[], partial: string): Completion[] {
  return Array.from(new Set(names))
    .filter((n) => startsWithFold(n, partial))
    .map((n) => nameOption(n));
}

function unionRelationKeys(blueprints: Blueprint[]): string[] {
  return blueprints.flatMap((b) => Object.keys(b.relations));
}

/** The innermost unmatched `(`/`[` in `text` (its bracket char + index), or `null` when every
 *  bracket is balanced — a cheap proxy for "we're inside a node/edge pattern" that never needs
 *  full parsing since only the LAST unmatched bracket matters for cursor-position completion. */
function lastUnmatchedBracket(text: string): { type: "(" | "["; index: number } | null {
  const stack: { type: "(" | "["; index: number }[] = [];
  // A bracket inside a string or backticked name is text, not structure: skip quoted runs
  // (a backslash escapes the next character inside '…'/"…"; a doubled backtick inside `…`).
  let quote: string | null = null;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (quote !== null) {
      if (ch === "\\" && quote !== "`") i += 1;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === "'" || ch === '"' || ch === "`") quote = ch;
    else if (ch === "(" || ch === "[") stack.push({ type: ch, index: i });
    else if (ch === ")" || ch === "]") stack.pop();
  }
  return stack.length > 0 ? stack[stack.length - 1] : null;
}

/** Unwraps a name that may be backticked (doubled-backtick escape), else returns it as-is. */
function unquote(raw: string): string {
  return raw.startsWith("`") && raw.endsWith("`") ? raw.slice(1, -1).replace(/``/g, "`") : raw;
}

const NODE_LABEL_RE = /^[A-Za-z_]\w*\s*:\s*([A-Za-z_]\w*|`(?:[^`]|``)*`)/;

/** The label of the node immediately preceding an edge body starting at `bracketIndex` — e.g.
 *  the `service` in `(a:service)-[` — or `null` when there is none/it is unlabelled. */
function labelOfPrecedingNode(text: string, bracketIndex: number): string | null {
  const before = text.slice(0, bracketIndex);
  const closeIndex = before.lastIndexOf(")");
  if (closeIndex === -1) return null;
  if (!/^[<>\-\s]*$/.test(before.slice(closeIndex + 1))) return null;
  const openIndex = before.lastIndexOf("(", closeIndex);
  if (openIndex === -1) return null;
  const match = NODE_LABEL_RE.exec(before.slice(openIndex + 1, closeIndex).trimStart());
  return match ? unquote(match[1]) : null;
}

/** The label a variable was declared with anywhere earlier in the text — e.g. `service` for
 *  `v` given `... (v:service) ...`, used to resolve `v.` property completions. */
function labelOfVariable(text: string, varName: string): string | null {
  const escaped = varName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp(`\\(\\s*${escaped}\\s*:\\s*([A-Za-z_]\\w*|\`(?:[^\`]|\`\`)*\`)`);
  const match = re.exec(text);
  return match ? unquote(match[1]) : null;
}

const IDENT_CHAR_RE = /\w/;
const LETTER_RE = /[A-Za-z]/;
const IDENT_RE = /^[A-Za-z_]\w*$/;
const PARTIAL_WORD_RE = /^\w*$/;

/** Scans left from `end` (exclusive) over identifier characters — a bounded single-character
 *  scan, never a backtracking-prone regex search over the whole (up to 2000-character) text. */
function identifierStart(text: string, end: number): number {
  let start = end;
  while (start > 0 && IDENT_CHAR_RE.test(text[start - 1])) start -= 1;
  return start;
}

/** Splits `text` as `<object>.<partial>` when it ends in a dot-access whose partial is a
 *  (possibly empty) word fragment and whose object is a full identifier — e.g. the trailing
 *  `"a."` of `"... WHERE a."` → `{ object: "a", partial: "" }`. `null` otherwise. */
function splitTrailingDotAccess(text: string): { object: string; partial: string } | null {
  const dotIndex = text.lastIndexOf(".");
  if (dotIndex === -1) return null;
  const partial = text.slice(dotIndex + 1);
  if (!PARTIAL_WORD_RE.test(partial)) return null;
  const object = text.slice(identifierStart(text, dotIndex), dotIndex);
  return IDENT_RE.test(object) ? { object, partial } : null;
}

/** Strips a trailing `=` or `IN [` (case-insensitive, a word boundary before `IN`) — nothing
 *  typed after it yet — returning the text before it, or `null` when the text doesn't end that
 *  way. Manual scanning, not a regex search, for the same reason as `identifierStart`. */
function stripTrailingEnumOperator(text: string): string | null {
  const trimmed = text.trimEnd();
  if (trimmed.endsWith("=")) return trimmed.slice(0, -1).trimEnd();
  if (!trimmed.endsWith("[")) return null;
  const beforeBracket = trimmed.slice(0, -1).trimEnd();
  if (beforeBracket.slice(-2).toLowerCase() !== "in") return null;
  const beforeIn = beforeBracket.slice(0, -2);
  const boundaryChar = beforeIn.slice(-1);
  if (boundaryChar !== "" && IDENT_CHAR_RE.test(boundaryChar)) return null;
  return beforeIn.trimEnd();
}

/** After `v.prop =` / `v.prop IN [` (nothing typed yet) → that property's declared `enum`
 *  values, as string literals. Absent enum → no match (fall through to other contexts). */
function enumLiteralCompletions(text: string, schema: QueryCompletionSchema): QueryCompletionResult | null {
  const beforeOperator = stripTrailingEnumOperator(text);
  if (beforeOperator == null) return null;
  const access = splitTrailingDotAccess(beforeOperator);
  if (!access) return null;
  const { object: varName, partial: propName } = access;
  const label = labelOfVariable(text, varName);
  const blueprint = label ? schema.blueprints.find((b) => b.identifier === label) : undefined;
  const property = blueprint?.schema.properties[propName];
  if (!property?.enum || property.enum.length === 0) return null;
  return {
    from: text.length,
    options: property.enum.map((value) => ({ label: stringLiteral(String(value)) })),
  };
}

/** After `v.` → the properties of the label `v` was declared with (or none, if unlabelled/
 *  unknown) union the seven metas. */
function propertyCompletions(text: string, schema: QueryCompletionSchema): QueryCompletionResult | null {
  const access = splitTrailingDotAccess(text);
  if (!access) return null;
  const { object: varName, partial } = access;
  const label = labelOfVariable(text, varName);
  const blueprint = label ? schema.blueprints.find((b) => b.identifier === label) : undefined;
  const propertyNames = blueprint ? Object.keys(blueprint.schema.properties) : [];
  const propertyOptions = propertyNames.filter((p) => startsWithFold(p, partial)).map((p) => nameOption(p));
  const metaOptions = METAS.filter((m) => startsWithFold(m, partial)).map((m) => ({
    label: `$${m}`,
    apply: `$${m}`,
  }));
  return { from: text.length - partial.length, options: [...propertyOptions, ...metaOptions] };
}

/** Inside `(v:` / `|` (a node label) or `[…:` / `|` (an edge body) — resolved via the nearest
 *  unmatched bracket, never past an already-opened `{` property map (unsupported context). */
function bracketCompletions(text: string, schema: QueryCompletionSchema): QueryCompletionResult | null {
  const bracket = lastUnmatchedBracket(text);
  if (!bracket) return null;
  const inner = text.slice(bracket.index + 1);
  if (inner.includes("{")) return null;
  const sepIndex = Math.max(inner.lastIndexOf(":"), inner.lastIndexOf("|"));
  if (sepIndex === -1) return null;
  const partial = inner.slice(sepIndex + 1);
  if (!NAME_PARTIAL_RE.test(partial)) return null;
  const from = text.length - partial.length;

  if (bracket.type === "(") {
    return { from, options: blueprintOptions(schema.blueprints, partial) };
  }

  const label = labelOfPrecedingNode(text, bracket.index);
  const blueprint = label ? schema.blueprints.find((b) => b.identifier === label) : undefined;
  const relationNames = blueprint ? Object.keys(blueprint.relations) : unionRelationKeys(schema.blueprints);
  const options = [
    ...edgeTypeOptions(relationNames, partial),
    ...edgeTypeOptions(schema.hierarchies, partial),
    ...("$team".startsWith(partial) ? [{ label: "$team", apply: "$team" }] : []),
  ];
  return { from, options };
}

/** At a clause start (empty text, or right after a `)` that closes a MATCH pattern) →
 *  `MATCH`/`OPTIONAL MATCH`/`WHERE`/`RETURN`/`LIMIT`, filtered by the word already typed. */
function clauseCompletions(text: string): QueryCompletionResult | null {
  let wordStart = text.length;
  while (wordStart > 0 && LETTER_RE.test(text[wordStart - 1])) wordStart -= 1;
  const word = text.slice(wordStart);
  const prefix = text.slice(0, wordStart).trimEnd();
  if (prefix !== "" && !prefix.endsWith(")")) return null;
  const upper = word.toUpperCase();
  const options = CLAUSE_KEYWORDS.filter((k) => k.startsWith(upper)).map((k) => ({ label: k, apply: k }));
  if (options.length === 0) return null;
  return { from: text.length - word.length, options };
}

/**
 * The completion source for `autocompletion({ override: [...] })` — `textBefore` is
 * `state.sliceDoc(0, pos)` (everything up to the cursor). Tries each context in priority order
 * (the enum-literal and property-dot contexts are more specific than the generic bracket/clause
 * ones) and returns the first that applies, or `null` for no suggestions.
 */
export function queryCompletions(
  textBefore: string,
  schema: QueryCompletionSchema,
): QueryCompletionResult | null {
  return (
    enumLiteralCompletions(textBefore, schema) ??
    propertyCompletions(textBefore, schema) ??
    bracketCompletions(textBefore, schema) ??
    clauseCompletions(textBefore)
  );
}
