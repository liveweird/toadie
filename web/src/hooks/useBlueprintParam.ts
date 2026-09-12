import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useDebouncedValue } from "@mantine/hooks";

/**
 * One `?<param>=` URL slot (the `useQuickViewParam` idiom): blank or absent reads as "none
 * chosen" (junk -> null), and `setValue` REPLACES rather than pushes — picking a value is not
 * a Back-worthy navigation step. Shared by `useBlueprintParam` (the Entities list's
 * `?blueprint=`) and `useTeamParam` (its `?team=` sibling, Phase 4 ownership) — kept internal
 * since neither caller needs the raw param name.
 */
function useUrlParam(param: string): {
  value: string | null;
  setValue: (value: string | null) => void;
} {
  const [params, setParams] = useSearchParams();
  const raw = params.get(param);
  const value = raw !== null && raw.trim() !== "" ? raw : null;
  const setValue = useCallback(
    (next: string | null) => {
      const nextParams = new URLSearchParams(params);
      const trimmed = next?.trim();
      if (trimmed) nextParams.set(param, trimmed);
      else nextParams.delete(param);
      setParams(nextParams, { replace: true });
    },
    [param, params, setParams],
  );
  return { value, setValue };
}

/**
 * The Entities list's `?blueprint=` URL state: which blueprint's entities are shown, so a
 * reload/shared link restores the selection. The page itself decides what "none chosen"
 * renders (its pick-a-blueprint empty state) — this hook only owns the param.
 */
export function useBlueprintParam(): {
  blueprint: string | null;
  setBlueprint: (value: string | null) => void;
} {
  const { value, setValue } = useUrlParam("blueprint");
  return { blueprint: value, setBlueprint: setValue };
}

/**
 * The Entities list's `?team=` URL state (Phase 4 ownership) — the `useBlueprintParam` sibling
 * behind the Team filter Select: which `_team` entity's owned rows are shown.
 */
export function useTeamParam(): {
  team: string | null;
  setTeam: (value: string | null) => void;
} {
  const { value, setValue } = useUrlParam("team");
  return { team: value, setTeam: setValue };
}

/**
 * The Entities list's `?q=` URL state (D2, deep-link parity with `?blueprint=`/`?team=`):
 * unlike those two Selects, free text changes on every keystroke, so writing straight into the
 * URL would throttle typing behind a router round-trip and turn every character into a
 * `replace`d history entry. The INPUT is therefore controlled by local React state (`q`,
 * immediate); only the DEBOUNCED value (300 ms, the `useDebouncedValue` util the rest of the
 * app already debounces filter text with — `useCatalogFileFilterState`/`useEntityGraphFilterState`)
 * is written back into the URL, still via `useUrlParam`'s `replace: true`. Blank/absent reads
 * as `""`, matching every other text filter in the app.
 */
export function useQParam(): {
  q: string;
  setQ: (value: string) => void;
} {
  const { value, setValue } = useUrlParam("q");
  const [q, setQ] = useState(() => value ?? "");
  const [debouncedQ] = useDebouncedValue(q, 300);

  // write-only sync into the URL: setValue's identity changes with the URLSearchParams object
  // it closes over, so including it below would re-fire this effect on every unrelated param
  // change (e.g. picking a blueprint) rather than only when the debounced text settles.
  useEffect(() => {
    // mounting with ?q=x already in the URL must not rewrite history with the same value
    if (debouncedQ === (value ?? "")) return;
    setValue(debouncedQ || null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedQ]);

  return { q, setQ };
}
