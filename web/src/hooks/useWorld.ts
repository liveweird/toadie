// The current product world (v2.3.0 — `utils/navigation.ts`): DERIVED from the route when the
// page belongs to a world, else the last remembered one (global pages such as Users or the
// changelog keep it). The memory lives in view state (`toadie.viewSettings.appShell.world`) and
// is rewritten whenever the route names a world, so `/` (`components/HomeRedirect.tsx`) and the
// brand link open the world the user last worked in; a first-ever visit lands on `DEFAULT_WORLD`.
// ONE owner: the shell calls this hook and passes `world` down — never two writers of the key.

import { useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { DEFAULT_WORLD, homeOf, isWorld, type World, worldOf } from "../utils/navigation";
import { readStoredJson, useStoredState } from "./useStoredState";

const WORLD_STORAGE_KEY = "appShell.world";

/** The remembered world, read once without subscribing — for the `/` redirect. */
export function readStoredWorld(): World {
  const stored = readStoredJson(WORLD_STORAGE_KEY);
  return isWorld(stored) ? stored : DEFAULT_WORLD;
}

export function useWorld(): { world: World; switchTo: (world: World) => void } {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [stored, setStored] = useStoredState<World>(WORLD_STORAGE_KEY, DEFAULT_WORLD, isWorld);
  const routeWorld = worldOf(pathname);
  const world = routeWorld ?? stored;

  // React Router 7 wraps `navigate` in startTransition: switchTo's synchronous setStored(next)
  // renders once with the OLD pathname (the transition to the new route hasn't committed
  // yet), and without this guard the reconcile effect below would see that stale
  // (routeWorld !== stored) mismatch and write the OLD world back into storage — a write that
  // can land AFTER the transition's own, leaving the WRONG value on disk for a reload to pick
  // up (the world-switch.spec.ts CI flake). `pending` names the world switchTo is headed to;
  // the effect stays out of the way until the route actually reflects it.
  const pending = useRef<World | null>(null);

  useEffect(() => {
    if (pending.current !== null && routeWorld !== pending.current) return;
    pending.current = null;
    if (routeWorld !== null && routeWorld !== stored) setStored(routeWorld);
  }, [routeWorld, stored, setStored]);

  function switchTo(next: World) {
    pending.current = next;
    setStored(next);
    navigate(homeOf(next));
  }

  return { world, switchTo };
}
