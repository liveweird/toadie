import CharCount, { NEAR_LIMIT_RATIO, type CharCountMode } from "../components/CharCount";

/** Whether the counter renders at all — lets hosts skip an empty description slot. */
export function shouldShowCharCount(current: number, max: number, mode: CharCountMode): boolean {
  return mode === "always" || current >= max * NEAR_LIMIT_RATIO;
}

/**
 * Description-slot helper for plain TextInputs: the counter node when visible, else undefined
 * (never an empty element — a truthy description renders Mantine's wrapper div even when empty).
 * Pair with inputWrapperOrder={["label", "input", "description", "error"]} to sit below the input.
 */
export function charCountDescription(current: number, max: number, mode: CharCountMode = "nearLimit") {
  return shouldShowCharCount(current, max, mode) ? (
    <CharCount current={current} max={max} mode={mode} />
  ) : undefined;
}
