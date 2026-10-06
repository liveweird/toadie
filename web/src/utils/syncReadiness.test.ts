import { describe, expect, test } from "vitest";
import { preflightAccepted, preflightPending, querySettled } from "./syncReadiness";

const settled = { isSuccess: true, isFetching: false };

describe("querySettled", () => {
  test("is true only for a successful query that is not refetching", () => {
    expect(querySettled(settled)).toBe(true);
    expect(querySettled({ isSuccess: true, isFetching: true })).toBe(false);
    expect(querySettled({ isSuccess: false, isFetching: false })).toBe(false);
    expect(querySettled({ isSuccess: false, isFetching: true })).toBe(false);
  });
});

describe("preflightPending", () => {
  test("is never pending without a document", () => {
    expect(preflightPending(false, { ...settled, isPending: true })).toBe(false);
    expect(preflightPending(false, { isSuccess: false, isFetching: true, isPending: true })).toBe(false);
  });

  test("is pending while the query is pending or fetching", () => {
    expect(preflightPending(true, { isSuccess: false, isFetching: false, isPending: true })).toBe(true);
    expect(preflightPending(true, { isSuccess: true, isFetching: true, isPending: false })).toBe(true);
  });

  test("is settled once the query is neither pending nor fetching", () => {
    expect(preflightPending(true, { ...settled, isPending: false })).toBe(false);
  });
});

describe("preflightAccepted", () => {
  test("accepts only CREATED and UPDATED from a settled query", () => {
    expect(preflightAccepted(settled, "CREATED")).toBe(true);
    expect(preflightAccepted(settled, "UPDATED")).toBe(true);
    expect(preflightAccepted(settled, "EXISTS")).toBe(false);
    expect(preflightAccepted(settled, "FAILED")).toBe(false);
    expect(preflightAccepted(settled, undefined)).toBe(false);
  });

  test("rejects an accepted status while the query is unsettled", () => {
    expect(preflightAccepted({ isSuccess: true, isFetching: true }, "CREATED")).toBe(false);
    expect(preflightAccepted({ isSuccess: false, isFetching: false }, "UPDATED")).toBe(false);
  });
});
