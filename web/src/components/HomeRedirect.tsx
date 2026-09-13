import { Navigate } from "react-router-dom";
import { readStoredWorld } from "../hooks/useWorld";
import { homeOf } from "../utils/navigation";

/**
 * The `/` route (v2.3.0): redirects to the last remembered world's home
 * (`hierarchyPath` for Backstage, `entityHierarchyPath` for Port — `homeOf`), reading the
 * stored world ONCE rather than subscribing (there is nothing to render at `/` itself).
 */
export default function HomeRedirect() {
  return <Navigate to={homeOf(readStoredWorld())} replace />;
}
