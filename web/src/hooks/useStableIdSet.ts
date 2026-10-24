import { useState } from "react";

function sameMembers(set: ReadonlySet<string>, ids: readonly string[]): boolean {
  if (set.size !== new Set(ids).size) return false;
  return ids.every((id) => set.has(id));
}

/**
 * The ids as a Set whose IDENTITY changes only when its MEMBERS do. The graph canvases feed the
 * saved `collapsed` list into the structural layout memo; the layout document arriving after the
 * graph (`[]` replacing the stable empty fallback) or a re-save echoing the same list is a new
 * array with the same members, and must not re-run dagre.
 */
export function useStableIdSet(ids: readonly string[]): ReadonlySet<string> {
  const [set, setSet] = useState<ReadonlySet<string>>(() => new Set(ids));
  if (sameMembers(set, ids)) return set;
  // Derived state, adjusted during render (React re-renders at once, before committing).
  const next = new Set(ids);
  setSet(next);
  return next;
}
