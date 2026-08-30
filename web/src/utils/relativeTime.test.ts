import { describe, expect, test } from "vitest";
import { formatDate, formatDateTime, relativeTimeAgo } from "./relativeTime";

const NOW = Date.UTC(2026, 7, 30, 12, 0, 0);
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe("relativeTimeAgo", () => {
  test.each([
    [NOW - 3 * MINUTE, "3 minutes ago"],
    [NOW - 5 * HOUR, "5 hours ago"],
    [NOW - 3 * DAY, "3 days ago"],
    [NOW - 45 * DAY, "last month"],
    [NOW - 400 * DAY, "last year"],
  ] as const)("formats %d as %s", (at, expected) => {
    expect(relativeTimeAgo(at, "en", NOW)).toBe(expected);
  });

  test("anything under a minute reads as now", () => {
    expect(relativeTimeAgo(NOW - 10 * 1000, "en", NOW)).toBe("now");
    // A clock skewed slightly ahead never renders a future phrase.
    expect(relativeTimeAgo(NOW + 5 * 1000, "en", NOW)).toBe("now");
  });

  test("renders in the given locale", () => {
    expect(relativeTimeAgo(NOW - 3 * DAY, "pl", NOW)).toBe("3 dni temu");
  });
});

describe("formatDateTime / formatDate", () => {
  // Locale/timezone output is the platform's — assert against the same Intl options the
  // implementation promises, which stays deterministic in any zone.
  test("formatDateTime renders the locale's medium date + short time", () => {
    const expected = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" });
    expect(formatDateTime(NOW, "en")).toBe(expected.format(NOW));
  });

  test("formatDate renders the locale's medium date only", () => {
    const expected = new Intl.DateTimeFormat("pl", { dateStyle: "medium" });
    expect(formatDate(NOW, "pl")).toBe(expected.format(NOW));
  });

  test("repeat calls reuse the cached formatter and stay stable", () => {
    expect(formatDateTime(NOW, "en")).toBe(formatDateTime(NOW, "en"));
    expect(formatDate(NOW, "en")).toBe(formatDate(NOW, "en"));
  });
});
