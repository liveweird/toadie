import { useCallback } from "react";
import { useSearchParams } from "react-router-dom";

const PARAM = "file";

/**
 * The quick-view drawer's URL state (v1.21.0) — the app's first URL-carried view state:
 * `?file=<id>` names the file open in the drawer, so a reload restores it and the address
 * is shareable. Orthogonal to the localStorage view settings (filters, pills, sort, page):
 * a shared link opens the drawer for anyone regardless of THEIR filters, because the drawer
 * fetches the file itself. `open` PUSHES (Back closes the drawer, as a reader expects);
 * `close` REPLACES (closing is not a step worth revisiting). Junk (non-positive, non-integer,
 * absent) reads as closed.
 */
export function useQuickViewParam(): {
  fileId: number | null;
  open: (id: number) => void;
  close: () => void;
} {
  const [params, setParams] = useSearchParams();
  const raw = params.get(PARAM);
  const fileId = raw !== null && /^[1-9]\d*$/.test(raw) ? Number(raw) : null;
  const open = useCallback(
    (id: number) => {
      const next = new URLSearchParams(params);
      next.set(PARAM, String(id));
      setParams(next);
    },
    [params, setParams],
  );
  const close = useCallback(() => {
    const next = new URLSearchParams(params);
    next.delete(PARAM);
    setParams(next, { replace: true });
  }, [params, setParams]);
  return { fileId, open, close };
}
