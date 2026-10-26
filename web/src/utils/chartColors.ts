/**
 * Chart series colours: the app's FIRST charts (the telemetry panel's trends), so the palette is defined here once and every
 * later chart draws from it.
 *
 * The app's colour vocabulary reserves red (hard / blocking), orange (soft finding), teal (success) and the amber brand (primary
 * actions), so a series — which is an arbitrary NAME, a metric or a table — wears none of them. What is left is the seven
 * Mantine hues below; each entry is the ONE shade of its hue that clears WCAG 1.4.11 (3:1) on every surface a chart sits on —
 * white and the `#f5f6f8` canvas in the light scheme, the body and canvas greys in the dark one — so there is no per-scheme pair.
 * The ORDER is the colour-blind-safety mechanism, found by running the dataviz skill's `validate_palette.js` over candidate
 * orderings (gray fails its chroma floor, and blue next to violet or cyan next to blue fails the normal-vision floor): this one
 * passes every gate on all four surfaces on the adjacent pairlist (worst adjacent CVD ΔE 14.1, worst adjacent normal-vision
 * ΔE 16.1). Lines take their colour by position, in this order. A chart never draws more lines than there are colours
 * (`TelemetryTrend` plots the first seven columns; the rest stay in the table), so the order is never cycled.
 * `chartColors.test.ts` recomputes the contrast and the CIEDE2000 distance between NEIGHBOURS (dataviz floor ≥ 15) from the theme.
 *
 * Colour is never the only carrier of identity: a chart with two or more lines also has a legend, a tooltip naming each line
 * and a table of the numbers, and every line after the first wears its own dash pattern (`LINE_DASHES`).
 */
export const CATEGORY_PALETTE: ReadonlyArray<string> = [
  "blue.7",
  "lime.9",
  "violet.5",
  "cyan.7",
  "indigo.5",
  "pink.6",
  "grape.6",
];

/**
 * The secondary encoding of the Nth line: solid first, then dashes that tell the lines apart without colour (the legend swatch
 * keeps its colour; the stroke pattern is what a colour-blind reader or a greyscale print can follow).
 */
export const LINE_DASHES: ReadonlyArray<string | undefined> = [
  undefined,
  "6 3",
  "2 3",
  "9 3 2 3",
  "1 4",
  "12 4",
  "5 2 1 2",
];

/** The colour and dash pattern of the `index`-th line of a chart. */
export function lineStyle(index: number): { color: string; strokeDasharray: string | undefined } {
  return {
    color: CATEGORY_PALETTE[index % CATEGORY_PALETTE.length] ?? "gray.6",
    strokeDasharray: LINE_DASHES[index % LINE_DASHES.length],
  };
}
