import type { LayoutWorkerMessage, LayoutWorkerReply } from "../utils/graphLayoutWorker";

/**
 * A controllable stand-in for the dagre layout worker (happy-dom has no `Worker`). It records
 * what the hook posted and lets a test answer — or never answer — each request, so the hook's
 * stale-drop / coalescing / supersede / deadline behaviour is driven without sleeps. Pass
 * `() => new FakeWorker() as unknown as Worker` as `createWorker`, or
 * `vi.stubGlobal("Worker", FakeWorker)` to put it behind the pages' default spawn.
 */
export class FakeWorker {
  /** Every worker created since the last [reset], oldest first. */
  static readonly instances: FakeWorker[] = [];
  static reset(): void {
    FakeWorker.instances.length = 0;
  }

  /** Every request posted to THIS worker, in order. */
  posted: LayoutWorkerMessage[] = [];
  terminated = false;
  private listeners: Record<string, ((event: { data?: unknown }) => void)[]> = {};

  constructor(..._args: unknown[]) {
    FakeWorker.instances.push(this);
  }

  postMessage(message: LayoutWorkerMessage): void {
    this.posted.push(message);
  }

  terminate(): void {
    this.terminated = true;
  }

  addEventListener(type: string, listener: (event: { data?: unknown }) => void): void {
    const existing = this.listeners[type] ?? [];
    existing.push(listener);
    this.listeners[type] = existing;
  }

  /** The worker answers (a terminated worker is silent, like a real one). */
  reply(reply: LayoutWorkerReply): void {
    if (this.terminated) return;
    for (const listener of this.listeners.message ?? []) listener({ data: reply });
  }

  /** Answer the request at [index] (default: the latest) with one position per node. */
  answer(index = this.posted.length - 1): void {
    const message = this.posted[index];
    const positions = Object.fromEntries(message.request.nodes.map((n, i) => [n.id, { x: i * 10, y: i * 10 }]));
    this.reply({ id: message.id, positions });
  }

  private answered = 0;
  /** Answer every request posted since the last call (the stale ones are the hook's to drop). */
  answerPending(): void {
    while (this.answered < this.posted.length) this.answer(this.answered++);
  }

  /** The worker script failing to load / a fault outside the layout's own try/catch. */
  fail(): void {
    if (this.terminated) return;
    for (const listener of this.listeners.error ?? []) listener({});
  }
}
