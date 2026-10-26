import type { ReactNode } from "react";
import { Table, Text } from "@mantine/core";
import DataTable from "./DataTable";

/** A two-column "name, value" table of current figures; `label` is its accessible name (every Table has one). */
export default function TelemetryValueTable({ label, rows }: { label: string; rows: { label: string; value: ReactNode }[] }) {
  return (
    <DataTable label={label} verticalSpacing={4}>
      <Table.Tbody>
        {rows.map((row) => (
          <Table.Tr key={row.label}>
            <Table.Th scope="row" fw={400}>
              <Text size="sm" c="dimmed" inherit>
                {row.label}
              </Text>
            </Table.Th>
            <Table.Td ta="right">{row.value}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </DataTable>
  );
}
