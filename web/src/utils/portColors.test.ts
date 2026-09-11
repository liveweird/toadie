import { describe, expect, test } from "vitest";
import { ENUM_COLORS } from "./blueprintForm";
import { portColor } from "./portColors";

const HEX_RE = /^#[0-9A-Fa-f]{6}$/;

describe("portColor", () => {
  test("maps the ten palette-name colours onto their Mantine theme key", () => {
    expect(portColor("blue")).toBe("blue");
    expect(portColor("turquoise")).toBe("teal");
    expect(portColor("orange")).toBe("orange");
    expect(portColor("purple")).toBe("violet");
    expect(portColor("pink")).toBe("pink");
    expect(portColor("yellow")).toBe("yellow");
    expect(portColor("green")).toBe("green");
    expect(portColor("red")).toBe("red");
    expect(portColor("darkGray")).toBe("dark");
    expect(portColor("lightGray")).toBe("gray");
  });

  test("maps the four metal/pale colours onto a literal hex string", () => {
    for (const name of ["bronze", "gold", "silver", "paleBlue"]) {
      expect(portColor(name)).toMatch(HEX_RE);
    }
  });

  test("every one of Port's 14 EnumColor values resolves to something", () => {
    for (const name of ENUM_COLORS) {
      expect(portColor(name)).toBeDefined();
    }
  });

  test("an unrecognized or absent name resolves to undefined", () => {
    expect(portColor("mauve")).toBeUndefined();
    expect(portColor(undefined)).toBeUndefined();
    expect(portColor("")).toBeUndefined();
  });
});
