// The entity query language's lexical vocabulary (phase 7, v2.0.0 — see
// `.claude/docs/entity-query-language.md`) and its CodeMirror 6 tokenizer. `cypherStream` is a
// `StreamParser` for `StreamLanguage.define` — presentational syntax highlighting only, no
// grammar/parsing: the server (`entityquery/QueryLexer.kt`) is the one authoritative lexer, and
// `POST /api/v1/entities/query/check` is the one authoritative validator. This module never
// throws and never rejects a query — it only colours it.

import { type StreamParser, type StringStream } from "@codemirror/language";
import { tags, type Tag } from "@lezer/highlight";

/** Clause keywords — also the clause-start completion vocabulary (`queryCompletion.ts`). Two
 *  words for `OPTIONAL MATCH` since the grammar never uses `OPTIONAL` alone. */
export const CLAUSE_KEYWORDS = ["MATCH", "OPTIONAL MATCH", "WHERE", "RETURN", "LIMIT"] as const;

/** Boolean/comparison operator words (case-insensitive, like every keyword here). */
export const OPERATOR_KEYWORDS = [
  "AND",
  "OR",
  "NOT",
  "IN",
  "CONTAINS",
  "STARTS",
  "ENDS",
  "WITH",
  "IS",
  "DISTINCT",
  "OPTIONAL",
] as const;

/** Literal keywords — tokenized/highlighted as atoms, not plain keywords. */
export const LITERAL_KEYWORDS = ["NULL", "TRUE", "FALSE"] as const;

/** The seven meta-properties (`$identifier` etc., without their leading `$`). */
export const METAS = [
  "identifier",
  "title",
  "blueprint",
  "team",
  "icon",
  "createdAt",
  "updatedAt",
] as const;

const ALL_KEYWORDS = new Set<string>([...CLAUSE_KEYWORDS.flatMap((k) => k.split(" ")), ...OPERATOR_KEYWORDS]);
const ALL_LITERALS = new Set<string>(LITERAL_KEYWORDS);

/** Backtick a name that is not a plain `[A-Za-z_][A-Za-z0-9_]*` identifier, doubling an
 *  embedded backtick (the lexer's own escape — see `.claude/docs/entity-query-language.md`). */
export function quoteIfNeeded(name: string): string {
  return /^[A-Za-z_]\w*$/.test(name) ? name : `\`${name.replace(/`/g, "``")}\``;
}

/** A single-quoted string literal with `\\`/`\'` escaped — the value's wire form in a query. */
export function stringLiteral(value: string): string {
  return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/** The token-name → highlight-tag mapping `StreamLanguage.define` resolves `token()`'s return
 *  value through — explicit rather than relying on CodeMirror's built-in legacy-name table, so
 *  the dependency on `@lezer/highlight` is direct or explicit. */
const CYPHER_TOKEN_TABLE: Record<string, Tag> = {
  keyword: tags.keyword,
  atom: tags.atom,
  variableName: tags.variableName,
  meta: tags.attributeName,
  string: tags.string,
  number: tags.number,
  comment: tags.comment,
  operator: tags.operator,
  punctuation: tags.punctuation,
  invalid: tags.invalid,
};

export type CypherStreamState = { blockComment: boolean };

const STRING_SINGLE_RE = /^'(?:[^'\\]|\\.)*'/;
const STRING_DOUBLE_RE = /^"(?:[^"\\]|\\.)*"/;
const BACKTICK_RE = /^`(?:[^`]|``)*`/;
const META_RE = /^\$[A-Za-z_]\w*/;
const NUMBER_RE = /^-?\d+(?:\.\d+)?/;
const IDENT_RE = /^[A-Za-z_]\w*/;
const BLOCK_COMMENT_END_RE = /^[\s\S]*?\*\//;
const TWO_CHAR_OPERATORS = ["<=", ">=", "<>", "!=", ".."];
const ONE_CHAR_OPERATORS = new Set(["=", "<", ">", "-"]);
const PUNCTUATION = new Set(["(", ")", "[", "]", "{", "}", ",", ":", ".", "|", "*"]);

function token(stream: StringStream, state: CypherStreamState): string | null {
  if (state.blockComment) {
    if (stream.match(BLOCK_COMMENT_END_RE)) state.blockComment = false;
    else stream.skipToEnd();
    return "comment";
  }
  if (stream.eatSpace()) return null;
  if (stream.match("//")) {
    stream.skipToEnd();
    return "comment";
  }
  if (stream.match("/*")) {
    if (!stream.match(BLOCK_COMMENT_END_RE)) {
      state.blockComment = true;
      stream.skipToEnd();
    }
    return "comment";
  }
  if (stream.match(STRING_SINGLE_RE) || stream.match(STRING_DOUBLE_RE)) return "string";
  if (stream.match(/^['"]/)) {
    // Unterminated string — colour the rest of the line as invalid rather than looping forever.
    stream.skipToEnd();
    return "invalid";
  }
  if (stream.match(BACKTICK_RE)) return "variableName";
  if (stream.match(META_RE)) return "meta";
  if (stream.match(NUMBER_RE)) return "number";
  for (const op of TWO_CHAR_OPERATORS) {
    if (stream.match(op)) return "operator";
  }
  if (stream.match(IDENT_RE)) {
    const word = stream.current().toUpperCase();
    if (ALL_LITERALS.has(word)) return "atom";
    if (ALL_KEYWORDS.has(word)) return "keyword";
    return "variableName";
  }
  const ch = stream.next();
  if (ch == null) return null;
  if (ONE_CHAR_OPERATORS.has(ch)) return "operator";
  if (PUNCTUATION.has(ch)) return "punctuation";
  return "invalid";
}

/** The `StreamParser` behind `StreamLanguage.define(cypherStream)` — no Lezer grammar build
 *  step, presentational highlighting only (see the file header). */
export const cypherStream: StreamParser<CypherStreamState> = {
  name: "entityQuery",
  startState: () => ({ blockComment: false }),
  copyState: (state) => ({ ...state }),
  token,
  tokenTable: CYPHER_TOKEN_TABLE,
};
