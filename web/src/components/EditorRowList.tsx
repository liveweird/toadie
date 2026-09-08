import { useEffect, useRef, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Badge, Box, Button, Group, Select, Text, Tooltip, UnstyledButton } from "@mantine/core";
import type { FormErrors } from "@mantine/form";
import type { ParseKeys } from "i18next";
import { IconChevronDown, IconChevronRight, IconChevronsDown, IconChevronsUp, IconPlus } from "@tabler/icons-react";
import RowControls from "./RowControls";
import classes from "../theme.module.css";

/** A drafts row shape every family shares: the stable local `key` (React identity across
 *  reorders/removals), the Port identifier, and the display title — everything this
 *  component needs to render a header without knowing the rest of the row's fields. Not
 *  exported: TSX infers the generic `T` from each caller's `rows` array, so no other file
 *  ever needs to name this type. */
interface EditorRowListRow {
  key: string;
  id: string;
  title: string;
}

/** Expansion state, owned by the caller (`hooks/useBlueprintRowExpansion.ts` today) and
 *  shared across every family's list on one editor page. */
export interface RowExpansionApi {
  isExpanded: (rowId: string) => boolean;
  toggle: (rowId: string) => void;
  expandAll: (ids: string[]) => void;
  collapseAll: (ids: string[]) => void;
  /** Expands the row and queues one scroll+focus of its header. */
  reveal: (rowId: string) => void;
  pendingFocus: string | null;
  focusHandled: () => void;
}

/** The DOM id, expansion-state key, jump-Select option value AND row-ref-map key for one
 *  row — every one of those four concerns must stay the same string, so it is minted here,
 *  the one place, and reused by every caller (the fieldsets' Add handler, the expansion hook). */
// eslint-disable-next-line react-refresh/only-export-components -- a tiny pure helper shared by this file, the expansion hook, and the fieldsets; splitting it out would scatter the "one string, four meanings" invariant documented above.
export function rowDomId(family: string, key: string): string {
  return `${family}-${key}`;
}

/** Not exported: every call site passes an inline object literal, checked structurally
 *  against this file's own prop type — no other file needs to name it either. */
interface EditorRowListControlLabels {
  moveUp: ParseKeys;
  moveDown: ParseKeys;
  remove: ParseKeys;
}

function hasRowErrors(errors: FormErrors, family: string, index: number): boolean {
  const prefix = `${family}.${index}.`;
  return Object.keys(errors).some((key) => key.startsWith(prefix));
}

/**
 * The one shared row-list editor behind every Blueprint fieldset (properties, relations,
 * mirror/calculation/aggregation properties, v1.23.2): each row is a delimited, collapsible
 * header over a body that UNMOUNTS while collapsed — never Mantine `Collapse`, which
 * happy-dom cannot see through (the Hierarchy `TreeItem` idiom) — plus a searchable
 * jump-to Select and Expand/Collapse all when there are at least two rows. `RowControls`
 * (move/remove) sits BESIDE the toggle button, never inside it, so the toggle never becomes
 * a nested-interactive accessibility violation.
 */
export default function EditorRowList<T extends EditorRowListRow>({
  family,
  rows,
  errors,
  expansion,
  section,
  newRowLabel,
  badge,
  isRequired,
  renderBody,
  onAdd,
  addLabel,
  onMove,
  onRemove,
  controlLabels,
}: {
  /** The form path prefix — also the testid/id prefix (`${family}-row-${index}`). */
  family: string;
  rows: T[];
  errors: FormErrors;
  expansion: RowExpansionApi;
  /** The translated fieldset legend, interpolated into the toolbar's aria-labels. */
  section: string;
  /** The placeholder identifier/name for a row whose own id is still blank. */
  newRowLabel: string;
  /** A neutral one-line summary for the header's gray badge; "" renders no badge. */
  badge: (row: T) => string;
  isRequired?: (row: T) => boolean;
  renderBody: (row: T, index: number) => ReactNode;
  onAdd: () => void;
  addLabel: string;
  onMove: (from: number, to: number) => void;
  onRemove: (index: number) => void;
  controlLabels: EditorRowListControlLabels;
}) {
  const { t } = useTranslation();
  const headerRefs = useRef(new Map<string, HTMLButtonElement | null>());

  // A post-commit scroll+focus of the row a jump/add/blocked-submit just revealed — headers
  // stay mounted regardless of expand state, so the ref is always available once the row
  // exists. Only the instance that actually owns the target id clears pendingFocus, so
  // sibling EditorRowList instances (the other four families) never race it away.
  useEffect(() => {
    const targetId = expansion.pendingFocus;
    if (!targetId) return;
    const node = headerRefs.current.get(targetId);
    if (!node) return;
    node.scrollIntoView?.({ block: "start" });
    node.focus();
    expansion.focusHandled();
  }, [expansion, expansion.pendingFocus]);

  const allIds = rows.map((row) => rowDomId(family, row.key));

  return (
    <>
      {rows.length >= 2 && (
        <Group gap="xs" mb="sm" wrap="wrap">
          <Select
            style={{ flex: 1, minWidth: 220 }}
            aria-label={t("blueprints.rows.jumpAria", { section })}
            placeholder={t("blueprints.rows.jump")}
            searchable
            value={null}
            data={rows.map((row) => {
              const displayId = row.id.trim() || newRowLabel;
              const title = row.title.trim();
              return {
                value: rowDomId(family, row.key),
                label: title ? `${displayId} — ${title}` : displayId,
              };
            })}
            onChange={(value) => value && expansion.reveal(value)}
          />
          <Tooltip label={t("blueprints.rows.expandAll", { section })}>
            <ActionIcon
              variant="default"
              aria-label={t("blueprints.rows.expandAll", { section })}
              onClick={() => expansion.expandAll(allIds)}
            >
              <IconChevronsDown size={16} />
            </ActionIcon>
          </Tooltip>
          <Tooltip label={t("blueprints.rows.collapseAll", { section })}>
            <ActionIcon
              variant="default"
              aria-label={t("blueprints.rows.collapseAll", { section })}
              onClick={() => expansion.collapseAll(allIds)}
            >
              <IconChevronsUp size={16} />
            </ActionIcon>
          </Tooltip>
        </Group>
      )}
      <Box className={classes.listRows} mb="sm">
        {rows.map((row, index) => {
          const rowId = rowDomId(family, row.key);
          const expanded = expansion.isExpanded(rowId);
          const displayName = row.id.trim() || newRowLabel;
          const badgeText = badge(row);
          const required = isRequired?.(row) ?? false;
          const bodyId = `${rowId}-body`;
          const showErrorDot = !expanded && hasRowErrors(errors, family, index);
          return (
            <Box key={row.key} id={rowId} className={classes.listRow} data-testid={`${family}-row-${index}`}>
              <Group gap={4} wrap="nowrap" className={classes.rowHeader}>
                <UnstyledButton
                  type="button"
                  ref={(node) => {
                    headerRefs.current.set(rowId, node);
                  }}
                  className={classes.rowToggle}
                  aria-expanded={expanded}
                  aria-controls={expanded ? bodyId : undefined}
                  aria-label={t("blueprints.rows.toggleAria", { name: displayName })}
                  onClick={() => expansion.toggle(rowId)}
                >
                  {expanded ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                  <Text ff="monospace" size="sm" fw={500} style={{ flexShrink: 0 }}>
                    {displayName}
                  </Text>
                  {badgeText && (
                    <Badge variant="light" color="gray" size="sm" tt="none" style={{ flexShrink: 0 }}>
                      {badgeText}
                    </Badge>
                  )}
                  {required && (
                    <Badge variant="outline" size="sm" tt="none" style={{ flexShrink: 0 }}>
                      {t("blueprints.field.required")}
                    </Badge>
                  )}
                  {row.title.trim() && (
                    <Text size="sm" c="dimmed" truncate style={{ flex: 1, minWidth: 0 }}>
                      {row.title}
                    </Text>
                  )}
                </UnstyledButton>
                {showErrorDot && (
                  <Tooltip label={t("blueprints.rows.hasErrors")}>
                    <Box role="img" aria-label={t("blueprints.rows.hasErrors")} className={classes.rowErrorDot} />
                  </Tooltip>
                )}
                <RowControls
                  index={index}
                  count={rows.length}
                  onMoveUp={() => onMove(index, index - 1)}
                  onMoveDown={() => onMove(index, index + 1)}
                  onRemove={() => onRemove(index)}
                  moveUpLabel={t(controlLabels.moveUp, { position: index + 1 })}
                  moveDownLabel={t(controlLabels.moveDown, { position: index + 1 })}
                  removeLabel={t(controlLabels.remove, { position: index + 1 })}
                />
              </Group>
              {expanded && (
                <Box id={bodyId} className={classes.rowBody}>
                  {renderBody(row, index)}
                </Box>
              )}
            </Box>
          );
        })}
      </Box>
      <Button variant="light" size="xs" leftSection={<IconPlus size={14} />} onClick={onAdd}>
        {addLabel}
      </Button>
    </>
  );
}
