import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { changeLine, changedPaths } from "./eventChanges";

const t = i18n.t.bind(i18n);

describe("changedPaths", () => {
  test("splits the comma-separated changed param, dropping blanks", () => {
    expect(changedPaths({ changed: "spec.owner,metadata.title" })).toEqual([
      "spec.owner",
      "metadata.title",
    ]);
    expect(changedPaths({ changed: "a,,b," })).toEqual(["a", "b"]);
  });

  test("an event without the param changed nothing", () => {
    expect(changedPaths({})).toEqual([]);
    expect(changedPaths({ changed: "" })).toEqual([]);
  });
});

describe("changeLine", () => {
  test("from and to render the transition", () => {
    expect(changeLine({ "spec.owner.from": "a", "spec.owner.to": "b" }, "spec.owner", "Owner", t)).toBe(
      "Owner: a → b",
    );
  });

  test("only a new value reads as set, only an old one as cleared", () => {
    expect(changeLine({ "x.to": "b" }, "x", "X", t)).toBe("X: set to b");
    expect(changeLine({ "x.from": "a" }, "x", "X", t)).toBe("X: cleared");
  });

  test("added and removed entries, alone or together", () => {
    expect(changeLine({ "x.added": "a" }, "x", "X", t)).toBe("X: +a");
    expect(changeLine({ "x.removed": "r" }, "x", "X", t)).toBe("X: −r");
    expect(changeLine({ "x.added": "a", "x.removed": "r" }, "x", "X", t)).toBe("X: +a −r");
  });

  test("a field recorded by name only gets no line", () => {
    expect(changeLine({ changed: "metadata.description" }, "metadata.description", "D", t)).toBeNull();
  });
});

describe("changeLine precedence", () => {
  test("list companions win over scalar ones, and added+removed over either alone", () => {
    expect(changeLine({ "x.added": "a", "x.from": "1", "x.to": "2" }, "x", "X", t)).toBe(
      t("common.history.change.added", { label: "X", added: "a" }),
    );
    expect(changeLine({ "x.removed": "b", "x.to": "2" }, "x", "X", t)).toBe(
      t("common.history.change.removed", { label: "X", removed: "b" }),
    );
    expect(changeLine({ "x.added": "a", "x.removed": "b", "x.to": "2" }, "x", "X", t)).toBe(
      t("common.history.change.addedRemoved", { label: "X", added: "a", removed: "b" }),
    );
  });
});
