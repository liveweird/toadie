import { describe, expect, test } from "vitest";
import { DEFAULT_THEME, type MantineColorsTuple } from "@mantine/core";
import { deltaE2000 } from "../test/colorDifference";
import { contrast } from "../test/contrast";
import { theme } from "../theme";
import { CATEGORY_PALETTE, LINE_DASHES, lineStyle } from "./chartColors";

/** "blue.7" → the hex, resolved through the app theme first (the `toadie` tuple), then Mantine's defaults. */
function shade(color: string): string {
  const [name = "", index = "0"] = color.split(".");
  const tuple = (theme.colors?.[name] ?? DEFAULT_THEME.colors[name]) as MantineColorsTuple;
  return tuple[Number(index)] as string;
}

// The surfaces a chart sits on: white / the shell canvas (theme.module.css `#f5f6f8`) in light, the body / canvas greys in dark.
const SURFACES = ["#ffffff", "#f5f6f8", DEFAULT_THEME.colors.dark[7], DEFAULT_THEME.colors.dark[8]] as string[];

describe("chart colours", () => {
  test.each(CATEGORY_PALETTE.map((color) => [color]))("%s clears 3:1 on every chart surface in both schemes", (color) => {
    for (const surface of SURFACES) {
      expect(contrast(shade(color), surface), `${color} on ${surface}`).toBeGreaterThanOrEqual(3);
    }
  });

  test("the palette wears none of the app's semantic hues and no hue twice", () => {
    const hues = CATEGORY_PALETTE.map((color) => color.split(".")[0]);
    for (const semantic of ["red", "orange", "teal", "toadie"]) expect(hues).not.toContain(semantic);
    expect(new Set(hues).size).toBe(hues.length);
  });

  test("neighbouring palette entries are ΔE2000 ≥ 15 apart (a chart never draws past the seventh, so there is no wrap-around)", () => {
    CATEGORY_PALETTE.slice(1).forEach((color, i) => {
      const previous = CATEGORY_PALETTE[i]!;
      expect(deltaE2000(shade(previous), shade(color)), `${previous} → ${color}`).toBeGreaterThanOrEqual(15);
    });
  });

  test("every line has its own dash pattern, the first solid, and a line past the palette wraps both together", () => {
    expect(LINE_DASHES).toHaveLength(CATEGORY_PALETTE.length);
    expect(new Set(LINE_DASHES).size).toBe(LINE_DASHES.length);
    expect(lineStyle(0)).toEqual({ color: "blue.7", strokeDasharray: undefined });
    expect(lineStyle(1).strokeDasharray).toBe("6 3");
    expect(lineStyle(CATEGORY_PALETTE.length)).toEqual(lineStyle(0));
  });
});
