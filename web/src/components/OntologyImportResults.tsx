import { Fragment, useState } from "react";
import { Anchor, Badge, Group, Stack, Table, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Link as RouterLink } from "react-router-dom";
import { editBlueprintPath } from "../utils/blueprintLinks";
import { editEntityPath } from "../utils/entityLinks";
import type { OntologyResultRow, OntologyRowStatus } from "../utils/ontologyImport";

const STATUS_COLOR: Record<OntologyRowStatus, string> = {
  CREATED: "teal",
  UPDATED: "teal",
  // Nothing stored BY DESIGN under the Replace-existing switch — a caution, not a failure.
  EXISTS: "gray",
  INVALID: "red",
  CONFLICT: "red",
  ERROR: "red",
  // The one client-only status: a blueprint document skipped because the caller isn't (or is
  // no longer) an admin — never sent to the server.
  FORBIDDEN: "red",
};

type Counts = { created: number; updated: number; unchanged: number; stored: number };

function summaryCounts(rows: readonly OntologyResultRow[]): Counts {
  let created = 0;
  let updated = 0;
  let unchanged = 0;
  for (const row of rows) {
    if (row.status === "CREATED") created += 1;
    else if (row.status === "UPDATED") updated += 1;
    else if (row.status === "EXISTS") unchanged += 1;
  }
  return { created, updated, unchanged, stored: created + updated };
}

function identifierLabel(row: OntologyResultRow, t: TFunction): string {
  return row.kind === "entity"
    ? `${row.blueprint ?? ""} / ${row.identifier ?? ""}`
    : (row.identifier ?? t(`ontology.import.kind.${row.kind}`));
}

function IdentifierCell({ row, t }: { row: OntologyResultRow; t: TFunction }) {
  const face =
    row.kind === "entity" ? (
      <>
        <Text component="span" c="dimmed">
          {row.blueprint ?? ""} /{" "}
        </Text>
        {row.identifier ?? ""}
      </>
    ) : (
      (row.identifier ?? "")
    );
  if (row.id == null) {
    return (
      <Text size="sm" ff="monospace">
        {face}
      </Text>
    );
  }
  const to = row.kind === "blueprint" ? editBlueprintPath(row.id) : editEntityPath(row.id);
  return (
    <Anchor
      component={RouterLink}
      to={to}
      size="sm"
      ff="monospace"
      aria-label={t("common.action.editAria", { name: identifierLabel(row, t) })}
    >
      {face}
    </Anchor>
  );
}

function FindingsCell({
  row,
  expanded,
  onToggle,
  t,
}: {
  row: OntologyResultRow;
  expanded: boolean;
  onToggle: () => void;
  t: TFunction;
}) {
  if (!row.findings || row.findings.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {row.message ?? ""}
      </Text>
    );
  }
  return (
    <Stack gap={2}>
      {row.message && (
        <Text size="sm" c="dimmed">
          {row.message}
        </Text>
      )}
      <Anchor component="button" type="button" size="xs" onClick={onToggle}>
        {t(expanded ? "ontology.import.hideFindings" : "ontology.import.showFindings", { count: row.findings.length })}
      </Anchor>
    </Stack>
  );
}

/**
 * The Import ontology page's result table (`pages/ImportOntology.tsx`, kept as its own
 * component for the sonarjs complexity backstops): one row per document, grouped by the
 * client-side batch order (`utils/ontologyImport.ts#OntologyResultRow.index`), with an
 * expandable findings row for entities the server rejected with `EntityFinding`s.
 */
export default function OntologyImportResults({
  rows,
  mode,
  showSource,
}: {
  rows: readonly OntologyResultRow[];
  mode: "import" | "check";
  showSource: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set());
  const counts = summaryCounts(rows);
  const columnCount = 4 + (showSource ? 1 : 0);

  function toggle(index: number) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });
  }

  return (
    <Stack gap="xs">
      <Text size="sm" fw={500}>
        {t(mode === "check" ? "ontology.import.checkResultSummary" : "ontology.import.resultSummary", {
          total: rows.length,
          stored: counts.stored,
          created: counts.created,
          updated: counts.updated,
          unchanged: counts.unchanged,
        })}
      </Text>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("ontology.import.column.kind")}</Table.Th>
            <Table.Th>{t("ontology.import.column.identifier")}</Table.Th>
            {showSource && <Table.Th>{t("ontology.import.column.source")}</Table.Th>}
            <Table.Th>{t("ontology.import.column.result")}</Table.Th>
            <Table.Th>{t("ontology.import.column.detail")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row) => {
            const isExpanded = expanded.has(row.index);
            return (
              <Fragment key={row.index}>
                <Table.Tr>
                  <Table.Td>
                    <Badge variant="light" size="sm">
                      {t(`ontology.import.kind.${row.kind}`)}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <IdentifierCell row={row} t={t} />
                  </Table.Td>
                  {showSource && (
                    <Table.Td>
                      <Text size="sm" c="dimmed">
                        {row.source}
                      </Text>
                    </Table.Td>
                  )}
                  <Table.Td>
                    <Badge variant="light" size="sm" color={STATUS_COLOR[row.status]}>
                      {t(
                        mode === "check"
                          ? `ontology.import.checkStatus.${row.status}`
                          : `ontology.import.status.${row.status}`,
                      )}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <FindingsCell row={row} expanded={isExpanded} onToggle={() => toggle(row.index)} t={t} />
                  </Table.Td>
                </Table.Tr>
                {isExpanded && row.findings && row.findings.length > 0 && (
                  <Table.Tr>
                    <Table.Td colSpan={columnCount}>
                      <Stack gap={4} pl="md">
                        {row.findings.map((finding, i) => (
                          <Group key={`${finding.field}-${i}`} gap={4} wrap="nowrap">
                            <Text size="xs" ff="monospace" c="dimmed">
                              {finding.field}
                            </Text>
                            <Text size="xs" c="dimmed">
                              — {finding.message}
                            </Text>
                          </Group>
                        ))}
                      </Stack>
                    </Table.Td>
                  </Table.Tr>
                )}
              </Fragment>
            );
          })}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
