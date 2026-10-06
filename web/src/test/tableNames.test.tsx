import { Table } from "@mantine/core";
import { describe, expect, it } from "vitest";
import DataTable from "../components/DataTable";
import { renderWithProviders, screen } from "./render";

// Every data table must carry an accessible name. The ONE mechanism is components/DataTable
// (a required `label` prop → aria-label), so the sweep has two halves: no source file may open a
// bare Mantine `<Table>` root (static scan, the locales/unusedKeys.test.ts `?raw` idiom), and the
// wrapper itself must expose the name on the `table` role. Each table-bearing page's own test
// additionally asserts `getByRole("table", { name })` against its rendered, data-filled table.

const SOURCE_MODULES = import.meta.glob("../**/*.tsx", {
  eager: true,
  query: "?raw",
  import: "default",
}) as Record<string, string>;

// A Mantine table ROOT: `<Table>` / `<Table prop…` — never `<Table.Thead>` and friends, never
// `<DataTable`. (Matches the opening tag only when the next character is a space, `>` or newline.)
const BARE_TABLE_ROOT = /<Table(?=[\s>])/;

describe("accessible table names", () => {
  it("no source file renders a bare Mantine <Table> root (use components/DataTable)", () => {
    const offenders = Object.entries(SOURCE_MODULES)
      .filter(([path]) => !path.endsWith(".test.tsx") && !path.endsWith("/components/DataTable.tsx"))
      .filter(([, source]) => BARE_TABLE_ROOT.test(source))
      .map(([path]) => path);
    expect(offenders).toEqual([]);
  });

  it("DataTable exposes its label as the table's accessible name", () => {
    renderWithProviders(
      <DataTable label="Things" layout="fixed">
        <Table.Tbody />
      </DataTable>,
    );
    expect(screen.getByRole("table", { name: "Things" })).toBeInTheDocument();
  });
});
