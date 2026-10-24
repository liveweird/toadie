import { useCallback, useEffect, useMemo, useState } from "react";
import { layoutPositions, type GraphPositions, type LayoutRequest } from "../utils/graphLayout";
import type { LayoutWorkerMessage, LayoutWorkerReply } from "../utils/graphLayoutWorker";

/** How long a running layout may keep going once a NEWER request is waiting behind it; past
 *  it the worker is terminated and respawned (termination IS the cancellation — a dagre run
 *  cannot be interrupted). Before it, the running job is allowed to finish: a cheap layout is
 *  discarded as stale without paying for a respawn. */
export const SUPERSEDE_GRACE_MS = 300;
/** The most one layout may run before it is abandoned and the user is offered a retry. */
export const LAYOUT_DEADLINE_MS = 30_000;

const NO_POSITIONS: GraphPositions = {};

export type LayoutStatus = "idle" | "pending" | "ready" | "timeout" | "error";

/** The positions answering [source] — the exact job the page submitted, so the page can pair
 *  them with the folded graph they were computed for. */
export interface LayoutResult<S> {
  source: S;
  positions: GraphPositions;
}

type Outcome<S> = { kind: "ready" | "timeout" | "error"; source: S };
interface State<S> {
  outcome: Outcome<S> | null;
  /** The newest SUCCESSFUL result — kept on screen while a newer layout is pending. */
  ready: LayoutResult<S> | null;
}

/** The real worker: a same-origin module chunk. Created lazily (on the first job), never at
 *  module top level. */
function spawnLayoutWorker(): Worker {
  // No `Worker` global (happy-dom, ancient engines) = the runner's synchronous fallback.
  if (typeof Worker === "undefined") throw new Error("Web Workers are unavailable");
  return new Worker(new URL("../utils/graphLayoutWorker.ts", import.meta.url), { type: "module" });
}

interface Job<S> {
  id: number;
  source: S;
}
interface Running<S> extends Job<S> {
  pastGrace: boolean;
  grace: ReturnType<typeof setTimeout>;
  deadline: ReturnType<typeof setTimeout>;
}

/**
 * The imperative core: at most one job running in the worker, at most one (the newest) waiting.
 * Request ids increase monotonically and only the LATEST id's result is ever reported. When the
 * worker cannot be had (no `Worker` global, or the constructor throws) every job runs
 * synchronously on the calling thread instead — the path the page tests exercise.
 */
class LayoutRunner<S extends { request: LayoutRequest }> {
  private worker: Worker | null = null;
  private synchronous = false;
  private seq = 0;
  private running: Running<S> | null = null;
  private queued: Job<S> | null = null;
  private last: S | null = null;

  private readonly createWorker: () => Worker;
  private readonly report: (outcome: Outcome<S>, positions?: GraphPositions) => void;

  constructor(
    createWorker: () => Worker,
    report: (outcome: Outcome<S>, positions?: GraphPositions) => void,
  ) {
    this.createWorker = createWorker;
    this.report = report;
  }

  submit(source: S): void {
    this.last = source;
    const job = { id: ++this.seq, source };
    const running = this.running;
    if (!running) {
      this.start(job);
      return;
    }
    // Only the newest waits.
    this.queued = job;
    if (running.pastGrace) this.supersede();
  }

  retry(): void {
    if (this.last) this.submit(this.last);
  }

  /** Nothing is wanted any more (no job, or unmounted): stop the worker, drop everything. */
  cancel(): void {
    this.seq += 1;
    this.queued = null;
    this.clearRunning();
    this.terminate();
  }

  private start(job: Job<S>): void {
    if (!this.synchronous && !this.worker) this.worker = this.spawn();
    if (!this.worker) {
      this.runSynchronously(job);
      return;
    }
    const grace = setTimeout(() => {
      if (!this.running || this.running.id !== job.id) return;
      this.running.pastGrace = true;
      if (this.queued) this.supersede();
    }, SUPERSEDE_GRACE_MS);
    const deadline = setTimeout(() => this.expire(job.id), LAYOUT_DEADLINE_MS);
    this.running = { ...job, pastGrace: false, grace, deadline };
    const message: LayoutWorkerMessage = { id: job.id, request: job.source.request };
    this.worker.postMessage(message);
  }

  private spawn(): Worker | null {
    try {
      const worker = this.createWorker();
      worker.addEventListener("message", (event: MessageEvent<LayoutWorkerReply>) => this.onReply(worker, event.data));
      // A script that cannot load (CSP block, 404, a stale hashed chunk after a deploy) or a
      // fault outside the layout's own try/catch: the worker is dead, whatever it was doing.
      worker.addEventListener("error", () => this.onFault(worker));
      return worker;
    } catch {
      this.synchronous = true;
      return null;
    }
  }

  private runSynchronously(job: Job<S>): void {
    try {
      this.report({ kind: "ready", source: job.source }, layoutPositions(job.source.request));
    } catch {
      this.report({ kind: "error", source: job.source });
    }
  }

  private onReply(from: Worker, reply: LayoutWorkerReply): void {
    const running = this.running;
    // A terminated worker's late message (or any foreign id) is simply ignored.
    if (from !== this.worker || !running || reply.id !== running.id) return;
    this.clearRunning();
    if (running.id === this.seq) {
      if ("error" in reply) this.report({ kind: "error", source: running.source });
      else this.report({ kind: "ready", source: running.source }, reply.positions);
    }
    // else: a stale result — dropped; the newer job below is the one that matters.
    this.startQueued();
  }

  /**
   * The worker itself failed (its own `error` event — layout failures come back as replies, not
   * as events). A dead worker never answers again, so retrying against it would just wait out
   * the deadline: drop it, switch THIS runner to the synchronous path for good, and re-run the
   * job that was in flight (or the newer one waiting behind it) on the calling thread.
   */
  private onFault(from: Worker): void {
    if (from !== this.worker) return;
    const running = this.running;
    this.clearRunning();
    this.terminate();
    this.synchronous = true;
    if (this.queued) this.startQueued();
    else if (running && running.id === this.seq) this.start({ id: running.id, source: running.source });
  }

  /** The running job overstayed while a newer one waits: kill the worker, start the newer. */
  private supersede(): void {
    this.clearRunning();
    this.terminate();
    this.startQueued();
  }

  private expire(id: number): void {
    const running = this.running;
    if (!running || running.id !== id) return;
    this.clearRunning();
    this.terminate();
    if (this.queued) this.startQueued();
    else this.report({ kind: "timeout", source: running.source });
  }

  private startQueued(): void {
    const next = this.queued;
    this.queued = null;
    if (next) this.start(next);
  }

  private clearRunning(): void {
    if (!this.running) return;
    clearTimeout(this.running.grace);
    clearTimeout(this.running.deadline);
    this.running = null;
  }

  private terminate(): void {
    this.worker?.terminate();
    this.worker = null;
  }
}

/**
 * Runs the dagre layout for [job] off the main thread. [job] carries the [LayoutRequest] as
 * `request`; the result reports back the SAME job object it answers, so a page can pair
 * positions with the folded graph they belong to. While a newer layout is pending the previous
 * successful result stays in `result` (the old canvas stays on screen). `job === null` clears
 * everything and stops the worker. `retry` resubmits after a `timeout`/`error`.
 */
export function useGraphLayoutWorker<S extends { request: LayoutRequest }>(
  job: S | null,
  deps: { createWorker?: () => Worker } = {},
): { result: LayoutResult<S> | null; status: LayoutStatus; retry: () => void } {
  const createWorker = deps.createWorker ?? spawnLayoutWorker;
  const [state, setState] = useState<State<S>>({ outcome: null, ready: null });
  const [runner] = useState(
    () =>
      new LayoutRunner<S>(createWorker, (outcome, positions) =>
        setState((prev) => ({
          outcome,
          ready: outcome.kind === "ready" && positions ? { source: outcome.source, positions } : prev.ready,
        })),
      ),
  );

  // An empty graph has nothing to lay out: answered in render (no worker, no pending flash).
  const empty = job !== null && job.request.nodes.length === 0;
  const emptyResult = useMemo(() => (empty && job ? { source: job, positions: NO_POSITIONS } : null), [empty, job]);

  useEffect(() => {
    if (job && !empty) runner.submit(job);
    else runner.cancel();
  }, [job, empty, runner]);
  useEffect(() => () => runner.cancel(), [runner]);

  // No job (or an empty one) = nothing to show or to resurrect later (derived state, reset
  // during render).
  if ((job === null || empty) && (state.outcome !== null || state.ready !== null)) {
    setState({ outcome: null, ready: null });
  }

  const retry = useCallback(() => {
    setState((prev) => ({ ...prev, outcome: null }));
    runner.retry();
  }, [runner]);

  if (job === null) return { result: null, status: "idle", retry };
  if (emptyResult) return { result: emptyResult, status: "ready", retry };
  const status = state.outcome?.source === job ? state.outcome.kind : "pending";
  return { result: state.ready, status, retry };
}
