import { useCallback } from "react";
import { useSearchParams } from "react-router-dom";

const PARAM = "blueprint";

/**
 * The Entities list's `?blueprint=` URL state (the `useQuickViewParam` idiom): which
 * blueprint's entities are shown, so a reload/shared link restores the selection. Blank or
 * absent reads as "none chosen" (junk -> null); the page itself decides what "none chosen"
 * renders (its pick-a-blueprint empty state) — this hook only owns the param. `setBlueprint`
 * REPLACES (picking a different blueprint is not a Back-worthy navigation step, unlike the
 * quick-view drawer's open/close).
 */
export function useBlueprintParam(): {
  blueprint: string | null;
  setBlueprint: (value: string | null) => void;
} {
  const [params, setParams] = useSearchParams();
  const raw = params.get(PARAM);
  const blueprint = raw !== null && raw.trim() !== "" ? raw : null;
  const setBlueprint = useCallback(
    (value: string | null) => {
      const next = new URLSearchParams(params);
      const trimmed = value?.trim();
      if (trimmed) next.set(PARAM, trimmed);
      else next.delete(PARAM);
      setParams(next, { replace: true });
    },
    [params, setParams],
  );
  return { blueprint, setBlueprint };
}
