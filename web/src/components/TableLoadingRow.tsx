import { Center, Loader, Table } from "@mantine/core";

/** The shared first-load body for list tables (a single spinner row). */
export default function TableLoadingRow({ colSpan }: { colSpan: number }) {
  return (
    <Table.Tr>
      <Table.Td colSpan={colSpan}>
        <Center py="md">
          <Loader size="sm" />
        </Center>
      </Table.Td>
    </Table.Tr>
  );
}
