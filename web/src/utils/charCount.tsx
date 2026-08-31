import CharCount from "../components/CharCount";

/** nearLimit counters stay hidden until the text reaches this share of the limit. */
const NEAR_LIMIT_RATIO = 0.8;

export type CharCountMode = "always" | "nearLimit";

/** THE visibility rule — the single predicate deciding whether a counter renders at all. */
export function shouldShowCharCount(current: number, max: number, mode: CharCountMode): boolean {
  return mode === "always" || current >= max * NEAR_LIMIT_RATIO;
}

/** The inputWrapperOrder that puts the description (this counter) BELOW the input. */
export const BELOW_INPUT = ["label", "input", "description", "error"] as const;

/**
 * Description-slot helper for plain TextInputs: the counter node when visible, else undefined
 * (never an empty element — a truthy description renders Mantine's wrapper div even when empty).
 * Pair with inputWrapperOrder={BELOW_INPUT} to sit below the input.
 */
export function charCountDescription(current: number, max: number, mode: CharCountMode = "nearLimit") {
  return shouldShowCharCount(current, max, mode) ? <CharCount current={current} max={max} /> : undefined;
}
