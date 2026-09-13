import { describe, expect, test } from "vitest";
import { StringStream } from "@codemirror/language";
import { cypherStream, quoteIfNeeded, stringLiteral, type CypherStreamState } from "./queryLanguage";

/** Tokenizes one line through the real `StreamParser`, returning `{text, tag}` per token
 *  (`tag` is `cypherStream.token`'s own return value, i.e. the token-table key). */
function tokenize(line: string, state: CypherStreamState = { blockComment: false }) {
  const stream = new StringStream(line, 2, 2);
  const out: { text: string; tag: string | null }[] = [];
  while (!stream.eol()) {
    stream.start = stream.pos;
    const tag = cypherStream.token(stream, state);
    if (stream.pos === stream.start) break; // guard against an infinite loop on a bug
    out.push({ text: stream.current(), tag });
  }
  return out;
}

describe("cypherStream", () => {
  test("tokenizes keywords case-insensitively", () => {
    expect(tokenize("match Where RETURN limit")).toEqual([
      { text: "match", tag: "keyword" },
      { text: " ", tag: null },
      { text: "Where", tag: "keyword" },
      { text: " ", tag: null },
      { text: "RETURN", tag: "keyword" },
      { text: " ", tag: null },
      { text: "limit", tag: "keyword" },
    ]);
  });

  test("tokenizes operator words distinctly from clause keywords", () => {
    const tokens = tokenize("AND OR NOT IN CONTAINS STARTS ENDS WITH IS DISTINCT OPTIONAL");
    expect(tokens.filter((t) => t.tag != null).every((t) => t.tag === "keyword")).toBe(true);
  });

  test("tokenizes TRUE/FALSE/NULL as atoms", () => {
    expect(tokenize("true FALSE Null").map((t) => t.tag).filter(Boolean)).toEqual(["atom", "atom", "atom"]);
  });

  test("tokenizes plain identifiers as variableName", () => {
    expect(tokenize("service")).toEqual([{ text: "service", tag: "variableName" }]);
  });

  test("tokenizes a backticked name as one variableName token", () => {
    expect(tokenize("`web-service`")).toEqual([{ text: "`web-service`", tag: "variableName" }]);
  });

  test("tokenizes a backtick containing a doubled escape", () => {
    expect(tokenize("`a``b`")).toEqual([{ text: "`a``b`", tag: "variableName" }]);
  });

  test("tokenizes metas after a dot", () => {
    expect(tokenize("v.$title")).toEqual([
      { text: "v", tag: "variableName" },
      { text: ".", tag: "punctuation" },
      { text: "$title", tag: "meta" },
    ]);
  });

  test("tokenizes $team as a meta (edge type position)", () => {
    expect(tokenize("$team")).toEqual([{ text: "$team", tag: "meta" }]);
  });

  test("tokenizes single- and double-quoted strings with escapes", () => {
    expect(tokenize(`'it\\'s' "a\\"b"`)).toEqual([
      { text: "'it\\'s'", tag: "string" },
      { text: " ", tag: null },
      { text: '"a\\"b"', tag: "string" },
    ]);
  });

  test("tokenizes an unterminated string as invalid to end of line", () => {
    expect(tokenize("'oops")).toEqual([{ text: "'oops", tag: "invalid" }]);
  });

  test("tokenizes integer and decimal numbers, including a leading minus", () => {
    expect(tokenize("1 2.5 -3")).toEqual([
      { text: "1", tag: "number" },
      { text: " ", tag: null },
      { text: "2.5", tag: "number" },
      { text: " ", tag: null },
      { text: "-3", tag: "number" },
    ]);
  });

  test("tokenizes a line comment to end of line", () => {
    expect(tokenize("a // b c")).toEqual([
      { text: "a", tag: "variableName" },
      { text: " ", tag: null },
      { text: "// b c", tag: "comment" },
    ]);
  });

  test("tokenizes a single-line block comment", () => {
    expect(tokenize("/* c */ a")).toEqual([
      { text: "/* c */", tag: "comment" },
      { text: " ", tag: null },
      { text: "a", tag: "variableName" },
    ]);
  });

  test("carries an unterminated block comment across lines via state", () => {
    const state: CypherStreamState = { blockComment: false };
    expect(tokenize("/* start", state)).toEqual([{ text: "/* start", tag: "comment" }]);
    expect(state.blockComment).toBe(true);
    expect(tokenize("still going", state)).toEqual([{ text: "still going", tag: "comment" }]);
    expect(state.blockComment).toBe(true);
    expect(tokenize("end */ x", state)).toEqual([
      { text: "end */", tag: "comment" },
      { text: " ", tag: null },
      { text: "x", tag: "variableName" },
    ]);
    expect(state.blockComment).toBe(false);
  });

  test("tokenizes two-character operators before falling back to one-character ones", () => {
    expect(tokenize("<= >= <> != ..")).toEqual([
      { text: "<=", tag: "operator" },
      { text: " ", tag: null },
      { text: ">=", tag: "operator" },
      { text: " ", tag: null },
      { text: "<>", tag: "operator" },
      { text: " ", tag: null },
      { text: "!=", tag: "operator" },
      { text: " ", tag: null },
      { text: "..", tag: "operator" },
    ]);
  });

  test("tokenizes one-character operators and punctuation, including arrow parts", () => {
    expect(tokenize("(a:s)-[:r]->(b)")).toEqual([
      { text: "(", tag: "punctuation" },
      { text: "a", tag: "variableName" },
      { text: ":", tag: "punctuation" },
      { text: "s", tag: "variableName" },
      { text: ")", tag: "punctuation" },
      { text: "-", tag: "operator" },
      { text: "[", tag: "punctuation" },
      { text: ":", tag: "punctuation" },
      { text: "r", tag: "variableName" },
      { text: "]", tag: "punctuation" },
      { text: "-", tag: "operator" },
      { text: ">", tag: "operator" },
      { text: "(", tag: "punctuation" },
      { text: "b", tag: "variableName" },
      { text: ")", tag: "punctuation" },
    ]);
  });

  test("tokenizes a stray unsupported character as invalid", () => {
    expect(tokenize("~")).toEqual([{ text: "~", tag: "invalid" }]);
  });

  test("copyState returns an independent copy", () => {
    const state = cypherStream.startState?.(2) ?? { blockComment: false };
    state.blockComment = true;
    const copy = cypherStream.copyState?.(state) ?? state;
    copy.blockComment = false;
    expect(state.blockComment).toBe(true);
  });
});

describe("quoteIfNeeded", () => {
  test("leaves a plain identifier untouched", () => {
    expect(quoteIfNeeded("service")).toBe("service");
    expect(quoteIfNeeded("_service_2")).toBe("_service_2");
  });

  test("backticks a name with a hyphen", () => {
    expect(quoteIfNeeded("web-service")).toBe("`web-service`");
  });

  test("backticks a name starting with a digit", () => {
    expect(quoteIfNeeded("2fast")).toBe("`2fast`");
  });

  test("doubles an embedded backtick", () => {
    expect(quoteIfNeeded("a`b")).toBe("`a``b`");
  });
});

describe("stringLiteral", () => {
  test("wraps a plain value in single quotes", () => {
    expect(stringLiteral("service")).toBe("'service'");
  });

  test("escapes an embedded single quote", () => {
    expect(stringLiteral("it's")).toBe("'it\\'s'");
  });

  test("escapes an embedded backslash before the quote escape", () => {
    expect(stringLiteral("a\\b'c")).toBe("'a\\\\b\\'c'");
  });
});
