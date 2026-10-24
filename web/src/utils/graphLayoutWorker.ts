import { layoutPositions, type GraphPositions, type LayoutRequest } from "./graphLayout";

/**
 * The dagre layout in a dedicated Web Worker (bundled by Vite as a separate same-origin chunk
 * — `script-src 'self'` already allows it; an inline/`blob:` worker would NOT, see
 * `.claude/docs/security.md`). The page-side protocol lives in `hooks/useGraphLayoutWorker.ts`:
 * one request in, one reply out, matched by `id`; a request that is no longer wanted is
 * cancelled by TERMINATING the worker, so there is no abort message.
 */
export interface LayoutWorkerMessage {
  id: number;
  request: LayoutRequest;
}

export type LayoutWorkerReply = { id: number; positions: GraphPositions } | { id: number; error: string };

// The app's tsconfig types `self` as a `Window` (lib DOM, no WebWorker — the two conflict);
// this is the dedicated-worker surface the file actually uses.
const scope = self as unknown as {
  addEventListener(type: "message", listener: (event: MessageEvent<LayoutWorkerMessage>) => void): void;
  postMessage(message: LayoutWorkerReply): void;
};

scope.addEventListener("message", (event) => {
  const { id, request } = event.data;
  try {
    scope.postMessage({ id, positions: layoutPositions(request) });
  } catch (e) {
    scope.postMessage({ id, error: String(e) });
  }
});
