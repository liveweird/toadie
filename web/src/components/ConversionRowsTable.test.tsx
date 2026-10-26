import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import { stubVirtualLayout } from "../test/virtualLayout";
import type { ConversionDisplayRow } from "../utils/conversionRows";
import ConversionRowsTable from "./ConversionRowsTable";

const rowsOf = (n: number, overrides: (i: number) => Partial<ConversionDisplayRow> = () => ({})): ConversionDisplayRow[] =>
  Array.from({ length: n }, (_, i) => ({
    key: `${i}`,
    status: "CONVERTED",
    from: `bp / e${i}`,
    to: `Component:default/e${i}`,
    existingPath: null,
    findings: [],
    unmapped: [],
    ...overrides(i),
  }));

const render = (rows: ConversionDisplayRow[], bounded = false) =>
  renderWithProviders(<ConversionRowsTable rows={rows} label="Rows" fromLabel="From" toLabel="To" bounded={bounded} />);

describe("ConversionRowsTable", () => {
  beforeEach(() => stubVirtualLayout(72));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  test("up to 300 rows every row is mounted in a plain table", () => {
    render(rowsOf(300));
    const table = screen.getByRole("table", { name: "Rows" });
    expect(within(table).getAllByRole("row")).toHaveLength(301);
    expect(table).not.toHaveAttribute("aria-rowcount");
  });

  test("above 300 rows only the window is mounted, with the true size announced and no row hidden by a cap", () => {
    render(rowsOf(1000));
    const table = screen.getByRole("table", { name: "Rows" });
    const mounted = within(table).getAllByRole("row").length;
    expect(mounted).toBeLessThan(100);
    expect(table).toHaveAttribute("aria-rowcount", "1001");
    expect(screen.getByText(/1000 rows/)).toBeInTheDocument();
    expect(screen.queryByText(/Showing the first/)).not.toBeInTheDocument();
    expect(within(table).getByText("bp / e0")).toBeInTheDocument();
  });

  test("bounded mode lists non-clean rows first, caps at 300 and says so", () => {
    render(rowsOf(350, (i) => (i === 349 ? { status: "INVALID" } : {})), true);
    const table = screen.getByRole("table", { name: "Rows" });
    expect(within(table).getAllByRole("row")).toHaveLength(301);
    expect(within(table).getAllByRole("row")[1]).toHaveTextContent("bp / e349");
    expect(screen.getByText("Showing the first 300 of 350 rows — the counts above cover all of them.")).toBeInTheDocument();
  });
});
