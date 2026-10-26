import { Anchor, Badge, Stack, Table, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { useVirtualRows } from "../hooks/useVirtualRows";
import { countRows, ROW_STATUS_COLOR, type ConversionDisplayRow } from "../utils/conversionRows";
import { MAX_DIALOG_ROWS, problemsFirst } from "../utils/rowOrder";
import DataTable from "./DataTable";
import VirtualSpacerRow from "./VirtualSpacerRow";
import VirtualTableFrame from "./VirtualTableFrame";

const COLUMN_COUNT = 5;
const MIN_WIDTH = 820;

/**
 * The conversion report both directions share (Export to Backstage rows, Backstage import rows):
 * a one-line summary of the counts, then a table of result chip, source, destination (a link to
 * the stored counterpart the merge read), findings (localized code + field + the server's message)
 * and the fields no rule converted.
 *
 * **Page mode (default)** follows the Errors tables' rule: every row up to 300, then only the rows
 * in view are mounted (`useVirtualRows` window scroll, measured row heights, `VirtualSpacerRow`s
 * for the rest, a sticky header and fixed layout). **`bounded` (dialogs)** cannot window against
 * the page scroll: it lists the rows that need attention first (anything but CONVERTED) and caps
 * the tail at 300 with an announced note — the summary line still counts every row.
 */
export default function ConversionRowsTable({
  rows,
  label,
  fromLabel,
  toLabel,
  bounded = false,
}: {
  rows: readonly ConversionDisplayRow[];
  label: string;
  fromLabel: string;
  toLabel: string;
  bounded?: boolean;
}) {
  const { t } = useTranslation();
  const counts = countRows(rows);
  const ordered = bounded ? problemsFirst(rows, (row) => row.status === "CONVERTED") : rows;
  const listed = bounded ? ordered.slice(0, MAX_DIALOG_ROWS) : ordered;
  // The window is inert for a bounded list: the hook only engages above its threshold and
  // `bounded` lists never exceed it.
  const { attachList, measureRow, enabled, items, paddingTop, paddingBottom } = useVirtualRows<HTMLTableSectionElement>(
    listed.length,
    (index) => listed[index]?.key ?? `#${index}`,
    72,
  );
  const virtualized = enabled && !bounded;
  const fixedWidth = (width: string) => (virtualized ? { width } : undefined);
  const mounted = virtualized ? items : listed.map((_, index) => ({ index }));

  return (
    <Stack gap="xs">
      <Text size="sm" fw={500}>
        {t("conversion.rows.summary", counts)}
      </Text>
      <VirtualTableFrame virtualized={virtualized} minWidth={MIN_WIDTH}>
        <DataTable
          label={label}
          stickyHeader={virtualized}
          stickyHeaderOffset={48}
          aria-rowcount={virtualized ? listed.length + 1 : undefined}
          layout={virtualized ? "fixed" : undefined}
          miw={virtualized ? MIN_WIDTH : undefined}
        >
          <Table.Thead>
            <Table.Tr aria-rowindex={virtualized ? 1 : undefined}>
              <Table.Th style={fixedWidth("17%")}>{t("conversion.rows.column.status")}</Table.Th>
              <Table.Th style={fixedWidth("20%")}>{fromLabel}</Table.Th>
              <Table.Th style={fixedWidth("20%")}>{toLabel}</Table.Th>
              <Table.Th>{t("conversion.rows.column.findings")}</Table.Th>
              <Table.Th style={fixedWidth("11%")}>{t("conversion.rows.column.unmapped")}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody ref={attachList}>
            {virtualized && paddingTop > 0 && <VirtualSpacerRow height={paddingTop} colSpan={COLUMN_COUNT} />}
            {mounted.map(({ index }) => (
              <ConversionRow
                key={listed[index].key}
                row={listed[index]}
                measureRef={measureRow}
                index={index}
              />
            ))}
            {virtualized && paddingBottom > 0 && <VirtualSpacerRow height={paddingBottom} colSpan={COLUMN_COUNT} />}
          </Table.Tbody>
        </DataTable>
      </VirtualTableFrame>
      {bounded && ordered.length > listed.length && (
        <Text size="xs" c="dimmed">
          {t("conversion.rows.truncated", { shown: listed.length, total: ordered.length })}
        </Text>
      )}
    </Stack>
  );
}

function ConversionRow({
  row,
  measureRef,
  index,
}: {
  row: ConversionDisplayRow;
  measureRef: ((node: Element | null) => void) | undefined;
  index: number;
}) {
  const { t } = useTranslation();
  return (
    <Table.Tr
      ref={measureRef}
      data-index={measureRef ? index : undefined}
      aria-rowindex={measureRef ? index + 2 : undefined}
    >
      <Table.Td style={{ verticalAlign: "top" }}>
        <Badge variant="light" size="sm" color={ROW_STATUS_COLOR[row.status]} tt="none">
          {t(`conversion.rows.status.${row.status}`)}
        </Badge>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Text size="sm" ff="monospace" style={{ overflowWrap: "anywhere" }}>
          {row.from}
        </Text>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        {row.to == null ? (
          <Text size="sm" c="dimmed">
            {t("conversion.rows.none")}
          </Text>
        ) : row.existingPath != null ? (
          <Anchor
            component={RouterLink}
            to={row.existingPath}
            size="sm"
            ff="monospace"
            style={{ overflowWrap: "anywhere" }}
            aria-label={t("common.action.editAria", { name: row.to })}
          >
            {row.to}
          </Anchor>
        ) : (
          <Text size="sm" ff="monospace" style={{ overflowWrap: "anywhere" }}>
            {row.to}
          </Text>
        )}
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Stack gap={4}>
          {row.findings.map((finding, findingIndex) => (
            <Text size="xs" key={`${finding.code}-${finding.field}-${findingIndex}`}>
              <Badge variant="light" size="xs" color="orange" tt="none" mr={6}>
                {t(`conversion.rows.findingCode.${finding.code}`)}
              </Badge>
              {finding.field !== "" && (
                <Text component="span" size="xs" c="dimmed" ff="monospace" mr={6}>
                  {finding.field}
                </Text>
              )}
              {finding.message}
            </Text>
          ))}
        </Stack>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        {row.unmapped.length > 0 && (
          <Text size="xs" c="dimmed" title={row.unmapped.join(", ")}>
            {t("conversion.rows.unmappedCount", { count: row.unmapped.length })}
          </Text>
        )}
      </Table.Td>
    </Table.Tr>
  );
}
