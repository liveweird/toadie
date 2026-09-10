import { useMemo, useState } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import EditorRowList, { rowDomId, type RowExpansionApi } from "./EditorRowList";
import { renderWithProviders } from "../test/render";

type Row = { key: string; id: string; title: string; required: boolean };

const FAMILY = "widgets";

/** A minimal, standalone RowExpansionApi — proves EditorRowList works against the plain
 *  contract alone, independent of hooks/useBlueprintRowExpansion.ts (the app's one caller). */
function useTestExpansion(initial: string[]): RowExpansionApi {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set(initial));
  const [pendingFocus, setPendingFocus] = useState<string | null>(null);
  return useMemo<RowExpansionApi>(
    () => ({
      isExpanded: (id) => expanded.has(id),
      toggle: (id) =>
        setExpanded((current) => {
          const next = new Set(current);
          if (next.has(id)) next.delete(id);
          else next.add(id);
          return next;
        }),
      expandAll: (ids) => setExpanded((current) => new Set([...current, ...ids])),
      collapseAll: (ids) =>
        setExpanded((current) => {
          const next = new Set(current);
          for (const id of ids) next.delete(id);
          return next;
        }),
      reveal: (id) => {
        setExpanded((current) => (current.has(id) ? current : new Set(current).add(id)));
        setPendingFocus(id);
      },
      pendingFocus,
      focusHandled: () => setPendingFocus(null),
    }),
    [expanded, pendingFocus],
  );
}

function twoRows(): Row[] {
  return [
    { key: "a", id: "alpha", title: "Alpha title", required: true },
    { key: "b", id: "beta", title: "Beta title", required: false },
  ];
}

function Harness({
  rows,
  initialExpanded = [],
  errors = {},
  isLocked,
}: {
  rows: Row[];
  initialExpanded?: string[];
  errors?: Record<string, string>;
  isLocked?: (row: Row) => boolean;
}) {
  const expansion = useTestExpansion(initialExpanded);
  const [items, setItems] = useState(rows);
  const [text, setText] = useState<Record<string, string>>({});
  return (
    <EditorRowList<Row>
      family={FAMILY}
      rows={items}
      errors={errors}
      expansion={expansion}
      section="Widgets"
      newRowLabel="New widget"
      badge={(row) => (row.id.trim() ? `kind-${row.id}` : "")}
      isRequired={(row) => row.required}
      isLocked={isLocked}
      renderBody={(row) => (
        <input
          aria-label={`Body ${row.key}`}
          value={text[row.key] ?? ""}
          onChange={(e) => setText((current) => ({ ...current, [row.key]: e.target.value }))}
        />
      )}
      onAdd={() =>
        setItems((current) => [...current, { key: `k${current.length}`, id: "", title: "", required: false }])
      }
      addLabel="Add widget"
      onMove={(from, to) =>
        setItems((current) => {
          const next = [...current];
          const [moved] = next.splice(from, 1);
          next.splice(to, 0, moved);
          return next;
        })
      }
      onRemove={(index) => setItems((current) => current.filter((_, i) => i !== index))}
      controlLabels={{
        moveUp: "blueprints.movePropertyUp",
        moveDown: "blueprints.movePropertyDown",
        remove: "blueprints.removePropertyAria",
      }}
    />
  );
}

describe("EditorRowList", () => {
  test("only rows named in the expansion set have a mounted body", () => {
    renderWithProviders(<Harness rows={twoRows()} initialExpanded={[rowDomId(FAMILY, "a")]} />);
    expect(screen.getByLabelText("Body a")).toBeInTheDocument();
    expect(screen.queryByLabelText("Body b")).not.toBeInTheDocument();
  });

  test("toggling mounts/unmounts the body; state held outside it survives the round trip", async () => {
    renderWithProviders(<Harness rows={twoRows()} initialExpanded={[]} />);
    const user = userEvent.setup();
    const toggle = screen.getByRole("button", { name: "Toggle beta" });

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await user.type(screen.getByLabelText("Body b"), "hello");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByLabelText("Body b")).not.toBeInTheDocument();

    await user.click(toggle);
    expect(screen.getByLabelText("Body b")).toHaveValue("hello");
  });

  test("RowControls move up/down reorder the rows via onMove", async () => {
    renderWithProviders(<Harness rows={twoRows()} initialExpanded={[]} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Move property 2 up" }));
    expect(screen.getByTestId(`${FAMILY}-row-0`)).toHaveTextContent("beta");
    expect(screen.getByTestId(`${FAMILY}-row-1`)).toHaveTextContent("alpha");

    await user.click(screen.getByRole("button", { name: "Move property 1 down" }));
    expect(screen.getByTestId(`${FAMILY}-row-0`)).toHaveTextContent("alpha");
    expect(screen.getByTestId(`${FAMILY}-row-1`)).toHaveTextContent("beta");
  });

  test("the header shows the mono id, the badge, the Required outline, and the dimmed title", () => {
    renderWithProviders(<Harness rows={twoRows()} initialExpanded={[]} />);
    expect(screen.getByText("alpha")).toBeInTheDocument();
    expect(screen.getByText("kind-alpha")).toBeInTheDocument();
    expect(screen.getByText("Alpha title")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Toggle alpha" }).textContent).toContain("Required");
    expect(screen.getByRole("button", { name: "Toggle beta" }).textContent).not.toContain("Required");
  });

  test("a blank-id row shows the newRowLabel placeholder as its identifier and toggle name", async () => {
    renderWithProviders(<Harness rows={[]} initialExpanded={[]} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Add widget" }));
    expect(screen.getByRole("button", { name: "Toggle New widget" })).toBeInTheDocument();
    expect(screen.getByText("New widget")).toBeInTheDocument();
  });

  test("the toolbar (jump + Expand/Collapse all) is hidden with a single row", () => {
    renderWithProviders(<Harness rows={[twoRows()[0]]} initialExpanded={[]} />);
    expect(screen.queryByRole("button", { name: "Expand all in Widgets" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Jump to a row in Widgets" })).not.toBeInTheDocument();
  });

  test("Expand all / Collapse all act on every row", async () => {
    renderWithProviders(<Harness rows={twoRows()} initialExpanded={[]} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Expand all in Widgets" }));
    expect(screen.getByLabelText("Body a")).toBeInTheDocument();
    expect(screen.getByLabelText("Body b")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Collapse all in Widgets" }));
    expect(screen.queryByLabelText("Body a")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Body b")).not.toBeInTheDocument();
  });

  test("the Jump select reveals a row, scrolls its header into view, and focuses it", async () => {
    renderWithProviders(<Harness rows={twoRows()} initialExpanded={[]} />);
    const betaToggle = screen.getByRole("button", { name: "Toggle beta" });
    const scrollSpy = vi.fn();
    betaToggle.scrollIntoView = scrollSpy;
    const user = userEvent.setup();

    const jump = screen.getByRole("combobox", { name: "Jump to a row in Widgets" });
    await user.click(jump);
    await user.click(await screen.findByRole("option", { name: "beta — Beta title" }));

    expect(await screen.findByLabelText("Body b")).toBeInTheDocument();
    expect(scrollSpy).toHaveBeenCalledWith({ block: "start" });
    expect(betaToggle).toHaveFocus();
  });

  test("a locked row shows the Seeded badge and disables its remove control; move stays enabled", () => {
    renderWithProviders(
      <Harness rows={twoRows()} initialExpanded={[]} isLocked={(row) => row.id === "alpha"} />,
    );
    expect(screen.getByText("Seeded")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove property 1" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Remove property 2" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Move property 1 down" })).toBeEnabled();
  });

  test("the error dot shows only while its row stays collapsed", async () => {
    renderWithProviders(
      <Harness
        rows={twoRows()}
        initialExpanded={[rowDomId(FAMILY, "a")]}
        errors={{ [`${FAMILY}.1.title`]: "Required" }}
      />,
    );
    expect(screen.getByRole("img", { name: "This row has errors" })).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Toggle beta" }));
    expect(screen.queryByRole("img", { name: "This row has errors" })).not.toBeInTheDocument();
  });
});
