// Canvas context actions (phase 7, v2.2.0 — `.claude/docs/entity-query-language.md` "Canvas
// actions"): the pure builders behind the Entity graph's node context menu and the Entity
// hierarchy's row menu "Query" group. Each returns entity-query TEXT the page then hands to
// `useEntityQuery().runText`, so the generated query lands in the editor AND runs at once — a
// user can read, edit and save it like one they typed. Every name goes through the one
// quoting/escaping vocabulary in `queryLanguage.ts`; nothing here validates (the server does).

import { quoteIfNeeded, stringLiteral } from "./queryLanguage";
import { TEAM_BLUEPRINT } from "./systemBlueprints";

/** The two coordinates that pin one entity in a query: its blueprint (the label) and its
 *  identifier (`$identifier`), unique within that blueprint. */
export type QueryNodeRef = { blueprint: string; identifier: string };

/** The variable-length hop ceiling of the language (`MAX_QUERY_HOPS`) — ancestor/descendant
 *  walks use the full range so the whole chain is returned. */
const MAX_HOPS = 10;

/** The number of hops the Expand actions offer. */
export const EXPAND_HOPS = [1, 2, 3] as const;
export type ExpandHops = (typeof EXPAND_HOPS)[number];

function anchor(variable: string, node: QueryNodeRef): string {
  return `MATCH (${variable}:${quoteIfNeeded(node.blueprint)} {$identifier: ${stringLiteral(node.identifier)}})`;
}

/** The entity plus everything within `hops` undirected hops over any relation or `$team` edge —
 *  the canvas neighbourhood. OPTIONAL so a lonely entity still shows itself. */
export function expandQuery(node: QueryNodeRef, hops: ExpandHops): string {
  return `${anchor("n", node)} OPTIONAL MATCH (n)-[*1..${hops}]-(m) RETURN n, m`;
}

/** The entity plus its whole parent chain in one hierarchy (the virtual child → parent edge
 *  type, up to the language's hop ceiling). */
export function ancestorsQuery(node: QueryNodeRef, hierarchyId: string): string {
  return `${anchor("n", node)} OPTIONAL MATCH (n)-[:${quoteIfNeeded(hierarchyId)}*1..${MAX_HOPS}]->(a) RETURN n, a`;
}

/** The entity plus every descendant in one hierarchy — the same virtual edge type, walked
 *  against the arrow (a child's parent link points AT this entity). */
export function descendantsQuery(node: QueryNodeRef, hierarchyId: string): string {
  return `${anchor("n", node)} OPTIONAL MATCH (n)<-[:${quoteIfNeeded(hierarchyId)}*1..${MAX_HOPS}]-(d) RETURN n, d`;
}

/** A `_team` entity plus everything whose EFFECTIVE team it is (the ownership pseudo-edge). */
export function ownedByQuery(teamIdentifier: string): string {
  return `MATCH (t:${TEAM_BLUEPRINT} {$identifier: ${stringLiteral(teamIdentifier)}}) OPTIONAL MATCH (e)-[:$team]->(t) RETURN t, e`;
}
