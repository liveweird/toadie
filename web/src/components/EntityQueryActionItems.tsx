// The "Query" menu group shared by the Entity graph's node context menu and the Entity
// hierarchy's row actions menu (phase 7, v2.2.0 — canvas context actions). Renders Mantine
// `Menu` items ONLY, so the caller owns the surrounding `Menu` (a controlled one at the pointer
// on the graph, `RowActionsMenu` on the hierarchy). Every item hands generated query text to
// `onRun` — the pages pass `useEntityQuery().runText`, so the query lands in the shared bar
// AND runs. The Ancestors/Descendants pair addresses the canvas's SELECTED hierarchy and is
// omitted when none is selected (an empty `hierarchies` dictionary); "Owned by this team"
// appears on `_team` nodes only.

import { Menu } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { TEAM_BLUEPRINT } from "../utils/systemBlueprints";
import {
  ancestorsQuery,
  descendantsQuery,
  EXPAND_HOPS,
  expandQuery,
  ownedByQuery,
  type QueryNodeRef,
} from "../utils/queryTemplates";

export default function EntityQueryActionItems({
  node,
  hierarchyId,
  onRun,
}: {
  node: QueryNodeRef;
  /** The canvas's selected hierarchy id; "" when the dictionary is empty. */
  hierarchyId: string;
  onRun: (query: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <>
      <Menu.Label>{t("entityQuery.actionsGroup")}</Menu.Label>
      {EXPAND_HOPS.map((hops) => (
        <Menu.Item key={hops} onClick={() => onRun(expandQuery(node, hops))}>
          {t("entityQuery.actionExpand", { count: hops })}
        </Menu.Item>
      ))}
      {hierarchyId !== "" && (
        <>
          <Menu.Item onClick={() => onRun(ancestorsQuery(node, hierarchyId))}>
            {t("entityQuery.actionAncestors", { hierarchy: hierarchyId })}
          </Menu.Item>
          <Menu.Item onClick={() => onRun(descendantsQuery(node, hierarchyId))}>
            {t("entityQuery.actionDescendants", { hierarchy: hierarchyId })}
          </Menu.Item>
        </>
      )}
      {node.blueprint.toLowerCase() === TEAM_BLUEPRINT && (
        <Menu.Item onClick={() => onRun(ownedByQuery(node.identifier))}>
          {t("entityQuery.actionOwnedBy")}
        </Menu.Item>
      )}
    </>
  );
}
