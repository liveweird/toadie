// Relative-time rendering for the Files list's sync column ("3 days ago") via the built-in
// Intl.RelativeTimeFormat — no date library on purpose (the repo has none).

const UNITS: { unit: Intl.RelativeTimeFormatUnit; ms: number }[] = [
  { unit: "year", ms: 365 * 24 * 60 * 60 * 1000 },
  { unit: "month", ms: 30 * 24 * 60 * 60 * 1000 },
  { unit: "day", ms: 24 * 60 * 60 * 1000 },
  { unit: "hour", ms: 60 * 60 * 1000 },
  { unit: "minute", ms: 60 * 1000 },
];

/**
 * "3 days ago" in the given locale, on the largest unit with a non-zero count; anything
 * under a minute reads as "now"-ish via a 0-second phrase ("now"/"teraz" with
 * numeric: "auto"). [now] is injectable for tests.
 */
export function relativeTimeAgo(epochMillis: number, locale: string, now = Date.now()): string {
  const formatter = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const elapsed = Math.max(0, now - epochMillis);
  for (const { unit, ms } of UNITS) {
    if (elapsed >= ms) return formatter.format(-Math.floor(elapsed / ms), unit);
  }
  return formatter.format(0, "second");
}
