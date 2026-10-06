import { Table, type TableProps } from "@mantine/core";

interface DataTableProps extends TableProps {
  /** The table's accessible name — pass the page's (or section's) already-translated heading. */
  label: string;
}

/**
 * The one root for data tables: Mantine's `Table` with a REQUIRED accessible name, so an unnamed
 * `table` landmark cannot compile. `Table.Thead`/`Tbody`/`Tr`… still come from Mantine.
 */
export default function DataTable({ label, ...props }: DataTableProps) {
  return <Table aria-label={label} {...props} />;
}
