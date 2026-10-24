import { afterEach, beforeAll, describe, expect, test, vi } from "vitest";
import type { LayoutRequest } from "./graphLayout";
import type { LayoutWorkerMessage } from "./graphLayoutWorker";

// Under happy-dom `self` is the window, so the worker module's `message` listener can be
// driven by dispatching a MessageEvent, and its replies observed through a `postMessage` spy.
// (The real thing runs in a dedicated worker scope — e2e covers that wiring.)
const REQUEST: LayoutRequest = {
  nodes: [
    { id: "a", cluster: "alpha" },
    { id: "b", cluster: "beta" },
  ],
  edges: [{ sourceId: "a", targetId: "b" }],
  options: { ranker: "tight-tree" },
};

function send(message: LayoutWorkerMessage): void {
  window.dispatchEvent(new MessageEvent("message", { data: message }));
}

describe("graphLayoutWorker", () => {
  let postMessage: ReturnType<typeof vi.spyOn>;

  beforeAll(async () => {
    await import("./graphLayoutWorker");
  });

  afterEach(() => {
    postMessage.mockRestore();
  });

  test("answers a request with the layout positions under the same id", () => {
    postMessage = vi.spyOn(window, "postMessage").mockImplementation(() => {});
    send({ id: 7, request: REQUEST });

    expect(postMessage).toHaveBeenCalledTimes(1);
    const reply = postMessage.mock.calls[0][0] as { id: number; positions: Record<string, { x: number; y: number }> };
    expect(reply.id).toBe(7);
    expect(Object.keys(reply.positions).sort()).toEqual(["a", "b"]);
    expect(Number.isFinite(reply.positions.a.x)).toBe(true);
  });

  test("a request the layout cannot handle answers with an error, not silence", () => {
    postMessage = vi.spyOn(window, "postMessage").mockImplementation(() => {});
    // A malformed request (the layout itself throws) must still be answered.
    send({ id: 8, request: { nodes: null } as unknown as LayoutRequest });

    expect(postMessage).toHaveBeenCalledTimes(1);
    const reply = postMessage.mock.calls[0][0] as { id: number; error?: string };
    expect(reply.id).toBe(8);
    expect(typeof reply.error).toBe("string");
  });
});
