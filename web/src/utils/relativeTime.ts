// Relative/absolute time rendering for the Files list's timestamp columns (relative text with the
// precise timestamp as hover text) via the built-in
// Intl formatters — no date library on purpose (the repo has none). Formatters are cached
// per locale: the list renders one per row per render, and construction is the costly part.

const UNITS: { unit: Intl.RelativeTimeFormatUnit; ms: number }[] = [
  { unit: "year", ms: 365 * 24 * 60 * 60 * 1000 },
  { unit: "month", ms: 30 * 24 * 60 * 60 * 1000 },
  { unit: "day", ms: 24 * 60 * 60 * 1000 },
  { unit: "hour", ms: 60 * 60 * 1000 },
  { unit: "minute", ms: 60 * 1000 },
];

const relativeFormatters = new Map<string, Intl.RelativeTimeFormat>();
const dateTimeFormatters = new Map<string, Intl.DateTimeFormat>();

function cached<T>(cache: Map<string, T>, locale: string, build: (locale: string) => T): T {
  let formatter = cache.get(locale);
  if (!formatter) {
    formatter = build(locale);
    cache.set(locale, formatter);
  }
  return formatter;
}

/**
 * "3 days ago" in the given locale, on the largest unit with a non-zero count; anything
 * under a minute reads as "now"-ish via a 0-second phrase ("now"/"teraz" with
 * numeric: "auto"). [now] is injectable for tests.
 */
export function relativeTimeAgo(epochMillis: number, locale: string, now = Date.now()): string {
  const formatter = cached(
    relativeFormatters,
    locale,
    (l) => new Intl.RelativeTimeFormat(l, { numeric: "auto" }),
  );
  const elapsed = Math.max(0, now - epochMillis);
  for (const { unit, ms } of UNITS) {
    if (elapsed >= ms) return formatter.format(-Math.floor(elapsed / ms), unit);
  }
  return formatter.format(0, "second");
}

/** The locale's full date + time — the precise-timestamp tooltip on timestamp cells. */
export function formatDateTime(epochMillis: number, locale: string): string {
  return cached(dateTimeFormatters, locale, (l) => new Intl.DateTimeFormat(l, {
    dateStyle: "medium",
    timeStyle: "short",
  })).format(epochMillis);
}
