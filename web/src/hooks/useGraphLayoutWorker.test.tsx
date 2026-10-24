import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { FakeWorker } from "../test/fakeWorker";
import type { LayoutRequest } from "../utils/graphLayout";
import { LAYOUT_DEADLINE_MS, SUPERSEDE_GRACE_MS, useGraphLayoutWorker } from "./useGraphLayoutWorker";

// A job is whatever object carries a `request`; the hook reports back the SAME object, which is
// how a page pairs positions with the folded graph they belong to.
function jobOf(...ids: string[]): { request: LayoutRequest } {
  return { request: { nodes: ids.map((id) => ({ id, cluster: "c" })), edges: [] } };
}

const createWorker = () => new FakeWorker() as unknown as Worker;
const worker = (index: number): FakeWorker => FakeWorker.instances[index];

function mount(initial: { request: LayoutRequest } | null) {
  return renderHook(({ job }) => useGraphLayoutWorker(job, { createWorker }), { initialProps: { job: initial } });
}

describe("useGraphLayoutWorker", () => {
  beforeEach(() => {
    FakeWorker.reset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  test("a job is pending until the worker answers, then ready with the job it answered", () => {
    const job = jobOf("a", "b");
    const { result } = mount(job);

    expect(result.current.status).toBe("pending");
    expect(result.current.result).toBeNull();
    expect(worker(0).posted).toHaveLength(1);
    expect(worker(0).posted[0].request).toBe(job.request);

    act(() => worker(0).answer());

    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(job);
    expect(Object.keys(result.current.result?.positions ?? {})).toEqual(["a", "b"]);
  });

  test("no job is idle and spawns no worker", () => {
    const { result } = mount(null);
    expect(result.current).toMatchObject({ status: "idle", result: null });
    expect(FakeWorker.instances).toHaveLength(0);
  });

  test("a stale result is dropped and the newest job runs next on the same worker", () => {
    const first = jobOf("a");
    const second = jobOf("b");
    const { result, rerender } = mount(first);

    rerender({ job: second });
    // Still one outstanding message: the newer request waits behind the running one.
    expect(worker(0).posted).toHaveLength(1);

    act(() => worker(0).answer(0));
    // The first answer is for a superseded id: not reported, and the queued job starts.
    expect(result.current.status).toBe("pending");
    expect(result.current.result).toBeNull();
    expect(worker(0).posted).toHaveLength(2);

    act(() => worker(0).answer(1));
    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(second);
    expect(FakeWorker.instances).toHaveLength(1);
  });

  test("a burst of requests coalesces: only the newest waits behind the running one", () => {
    const { result, rerender } = mount(jobOf("a"));
    const last = jobOf("d");
    rerender({ job: jobOf("b") });
    rerender({ job: jobOf("c") });
    rerender({ job: last });

    expect(worker(0).posted).toHaveLength(1);
    act(() => worker(0).answer(0));

    expect(worker(0).posted).toHaveLength(2);
    expect(worker(0).posted[1].request.nodes.map((n) => n.id)).toEqual(["d"]);
    act(() => worker(0).answer(1));
    expect(result.current.result?.source).toBe(last);
  });

  test("the previous result stays available while a newer layout is pending", () => {
    const first = jobOf("a");
    const { result, rerender } = mount(first);
    act(() => worker(0).answer());
    expect(result.current.status).toBe("ready");

    rerender({ job: jobOf("a", "b") });
    expect(result.current.status).toBe("pending");
    expect(result.current.result?.source).toBe(first);
  });

  test("a running job older than the grace is terminated and respawned the moment a newer one arrives", () => {
    vi.useFakeTimers();
    const { result, rerender } = mount(jobOf("slow"));
    act(() => {
      vi.advanceTimersByTime(SUPERSEDE_GRACE_MS + 1);
    });
    expect(worker(0).terminated).toBe(false);

    const newer = jobOf("fast");
    rerender({ job: newer });

    expect(worker(0).terminated).toBe(true);
    expect(FakeWorker.instances).toHaveLength(2);
    expect(worker(1).posted).toHaveLength(1);
    expect(worker(1).posted[0].request.nodes.map((n) => n.id)).toEqual(["fast"]);

    act(() => worker(1).answer());
    expect(result.current.result?.source).toBe(newer);
  });

  test("a newer job arriving inside the grace waits for it, then supersedes", () => {
    vi.useFakeTimers();
    const { rerender } = mount(jobOf("slow"));
    act(() => {
      vi.advanceTimersByTime(100);
    });
    rerender({ job: jobOf("fast") });
    expect(worker(0).terminated).toBe(false);

    act(() => {
      vi.advanceTimersByTime(SUPERSEDE_GRACE_MS);
    });
    expect(worker(0).terminated).toBe(true);
    expect(worker(1).posted[0].request.nodes.map((n) => n.id)).toEqual(["fast"]);
  });

  test("a job that misses the deadline is abandoned as a timeout, and retry resubmits it", () => {
    vi.useFakeTimers();
    const job = jobOf("huge");
    const { result } = mount(job);

    act(() => {
      vi.advanceTimersByTime(LAYOUT_DEADLINE_MS);
    });
    expect(worker(0).terminated).toBe(true);
    expect(result.current.status).toBe("timeout");

    act(() => result.current.retry());
    expect(result.current.status).toBe("pending");
    expect(FakeWorker.instances).toHaveLength(2);
    expect(worker(1).posted[0].request).toBe(job.request);

    act(() => worker(1).answer());
    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(job);
  });

  test("a finished job clears its deadline", () => {
    vi.useFakeTimers();
    const { result } = mount(jobOf("a"));
    act(() => worker(0).answer());
    act(() => {
      vi.advanceTimersByTime(LAYOUT_DEADLINE_MS * 2);
    });
    expect(result.current.status).toBe("ready");
    expect(worker(0).terminated).toBe(false);
  });

  test("an error reply surfaces as an error, and retry resubmits to the same worker", () => {
    const job = jobOf("a");
    const { result } = mount(job);

    act(() => worker(0).reply({ id: worker(0).posted[0].id, error: "boom" }));
    expect(result.current.status).toBe("error");

    act(() => result.current.retry());
    expect(result.current.status).toBe("pending");
    expect(worker(0).posted).toHaveLength(2);
    act(() => worker(0).answer(1));
    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(job);
  });

  test("a worker that fails to load is dropped and the job re-runs on the calling thread, no deadline wait", () => {
    vi.useFakeTimers();
    const job = jobOf("a", "b");
    const { result, rerender } = mount(job);

    // The load failure is an async `error` event; the dead worker never answers.
    act(() => worker(0).fail());

    expect(worker(0).terminated).toBe(true);
    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(job);
    expect(Object.keys(result.current.result?.positions ?? {})).toEqual(["a", "b"]);

    // Later jobs stay on the synchronous path: no second worker is ever spawned.
    const next = jobOf("c");
    rerender({ job: next });
    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(next);
    expect(FakeWorker.instances).toHaveLength(1);
  });

  test("a load failure with a newer job waiting runs the newer one, not the stale one", () => {
    const { result, rerender } = mount(jobOf("old"));
    const newest = jobOf("new");
    rerender({ job: newest });

    act(() => worker(0).fail());

    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(newest);
  });

  test("an empty request is answered at once: no worker, no pending state", () => {
    const empty = jobOf();
    const { result, rerender } = mount(empty);

    expect(result.current).toMatchObject({ status: "ready" });
    expect(result.current.result?.source).toBe(empty);
    expect(result.current.result?.positions).toEqual({});
    expect(FakeWorker.instances).toHaveLength(0);

    // Back to a real graph: pending with nothing resurrected from before the empty spell.
    rerender({ job: jobOf("a") });
    expect(result.current).toMatchObject({ status: "pending", result: null });
  });

  test("no job terminates the worker and forgets the result", () => {
    const { result, rerender } = mount(jobOf("a"));
    act(() => worker(0).answer());

    rerender({ job: null });

    expect(worker(0).terminated).toBe(true);
    expect(result.current).toMatchObject({ status: "idle", result: null });

    // A later job starts fresh, with nothing resurrected from before the gap.
    rerender({ job: jobOf("b") });
    expect(result.current).toMatchObject({ status: "pending", result: null });
    expect(FakeWorker.instances).toHaveLength(2);
  });

  test("unmounting terminates the worker", () => {
    const { unmount } = mount(jobOf("a"));
    unmount();
    expect(worker(0).terminated).toBe(true);
  });

  test("without a Worker global the layout runs on the calling thread", () => {
    // happy-dom has no Worker: the default spawn throws and every job is laid out in place.
    expect(typeof Worker).toBe("undefined");
    const job = jobOf("a", "b");
    const { result } = renderHook(() => useGraphLayoutWorker(job));

    expect(result.current.status).toBe("ready");
    expect(result.current.result?.source).toBe(job);
    const positions = result.current.result?.positions ?? {};
    expect(Object.keys(positions)).toEqual(["a", "b"]);
    expect(Number.isFinite(positions.a.x)).toBe(true);
  });

  test("a worker constructor that throws falls back to the calling thread", () => {
    const job = jobOf("a");
    const { result } = renderHook(() =>
      useGraphLayoutWorker(job, {
        createWorker: () => {
          throw new Error("blocked by policy");
        },
      }),
    );
    expect(result.current.status).toBe("ready");
    expect(Object.keys(result.current.result?.positions ?? {})).toEqual(["a"]);
  });
});
