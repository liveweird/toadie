import CharCount from "../components/CharCount";

/** nearLimit counters stay hidden until the text reaches this share of the limit. */
const NEAR_LIMIT_RATIO = 0.8;

export type CharCountMode = "always" | "nearLimit";

/** THE visibility rule — the single predicate deciding whether a counter renders at all. */
export function shouldShowCharCount(current: number, max: number, mode: CharCountMode): boolean {
  return mode === "always" || current >= max * NEAR_LIMIT_RATIO;
}

/**
 * Description-slot helper for plain TextInputs: the counter node when visible, else undefined
 * (never an empty element — a truthy description renders Mantine's wrapper div even when empty).
 * Pair with inputWrapperOrder={["label", "input", "description", "error"]} to sit below the input.
 */
export function charCountDescription(current: number, max: number, mode: CharCountMode = "nearLimit") {
  return shouldShowCharCount(current, max, mode) ? <CharCount current={current} max={max} /> : undefined;
}
