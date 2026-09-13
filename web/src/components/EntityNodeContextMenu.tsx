import { Menu } from "@mantine/core";
import { useTranslation } from "react-i18next";
import EntityQueryActionItems from "./EntityQueryActionItems";
import type { QueryNodeRef } from "../utils/queryTemplates";

/** One right-clicked node: its query coordinates, display name, and the pointer position the
 *  menu opens at (viewport coordinates — `MouseEvent.clientX/clientY`). */
export type EntityNodeContextMenuTarget = {
  node: QueryNodeRef;
  name: string;
  x: number;
  y: number;
};

/**
 * The Entity graph's node context menu (v2.2.0): a CONTROLLED Mantine `Menu` anchored at the
 * pointer rather than at a rendered trigger — a canvas node is not a DOM element `Menu.Target`
 * can wrap, so the target is an invisible 1×1 element positioned at the right-click coordinates
 * instead. Renders nothing while [target] is null; the caller (`EntityGraph.tsx`) owns the
 * `onNodeContextMenu` handler that produces one.
 */
export default function EntityNodeContextMenu({
  target,
  hierarchyId,
  onRun,
  onClose,
}: {
  target: EntityNodeContextMenuTarget | null;
  hierarchyId: string;
  onRun: (query: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  if (target === null) return null;
  return (
    <Menu
      // Always controlled open while [target] is non-null (the component unmounts entirely
      // to close, driven by [onClose] below) — the only way Mantine ever calls `onChange` is
      // to ask for a close (Escape, an outside click, or `closeOnItemClick`), so `onChange`
      // and `onClose` are the same request.
      opened
      onChange={onClose}
      position="bottom-start"
      withinPortal
      shadow="md"
    >
      {/* Popover wires the dropdown's `aria-labelledby` to THIS target, so its own accessible
          name (rather than any `aria-label` on the dropdown itself, which `aria-labelledby`
          would otherwise shadow) is what screen readers announce — hence the same string here. */}
      <Menu.Target>
        <div
          aria-label={t("entityQuery.actionsAria", { name: target.name })}
          style={{
            position: "fixed",
            left: target.x,
            top: target.y,
            width: 1,
            height: 1,
            pointerEvents: "none",
          }}
        />
      </Menu.Target>
      <Menu.Dropdown>
        <EntityQueryActionItems
          node={target.node}
          hierarchyId={hierarchyId}
          onRun={(query) => {
            onRun(query);
            onClose();
          }}
        />
      </Menu.Dropdown>
    </Menu>
  );
}
