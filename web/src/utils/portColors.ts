// Port's 14 named `EnumColor` values (`utils/blueprintForm.ts#ENUM_COLORS`) mapped onto a
// value `EntityComputedValue`'s colorized Badge can use: a Mantine theme colour key where one
// exists, a literal hex string for the four that don't (Mantine ships no bronze/gold/silver
// shade, and "paleBlue" would collide with the theme's own "blue").

const MANTINE_COLOR_NAMES: Record<string, string> = {
  blue: "blue",
  turquoise: "teal",
  orange: "orange",
  purple: "violet",
  pink: "pink",
  yellow: "yellow",
  green: "green",
  red: "red",
  darkGray: "dark",
  lightGray: "gray",
};

const HEX_COLORS: Record<string, string> = {
  bronze: "#CD7F32",
  gold: "#D4AF37",
  silver: "#C0C0C0",
  paleBlue: "#AFC6E9",
};

/** Maps a Port `EnumColor` name to a Mantine `color` prop value; an unrecognized or absent
 *  name answers `undefined` (Mantine's own default colour), never throws. */
export function portColor(name?: string): string | undefined {
  if (!name) return undefined;
  return MANTINE_COLOR_NAMES[name] ?? HEX_COLORS[name];
}
