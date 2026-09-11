import { useCallback } from "react";
import { useSearchParams } from "react-router-dom";

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
