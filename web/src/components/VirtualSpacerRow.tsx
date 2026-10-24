import { Table } from "@mantine/core";

/**
 * The blank rows above and below a windowed table body, standing in for the rows that are not
 * mounted so the scrollbar and the document height stay true. Purely presentational: hidden
 * from assistive technology and borderless (Mantine draws a rule under every row).
 */
export default function VirtualSpacerRow({ height, colSpan }: { height: number; colSpan: number }) {
  return (
    <Table.Tr aria-hidden="true" style={{ height, border: 0 }}>
      <Table.Td colSpan={colSpan} p={0} style={{ height, border: 0 }} />
    </Table.Tr>
  );
}
