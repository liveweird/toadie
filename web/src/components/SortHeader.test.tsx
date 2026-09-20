import { describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Table } from "@mantine/core";
import SortHeader from "./SortHeader";
import { renderWithProviders } from "../test/render";

type Field = "identifier" | "title" | "updatedAt";

function renderHeader(props: {
  activeField: Field;
  activeDir: "asc" | "desc";
  onToggle: (field: Field) => void;
  secondary?: { field: Field; label: string };
}) {
  return renderWithProviders(
    <Table>
      <Table.Thead>
        <Table.Tr>
          <SortHeader<Field>
            field="identifier"
            label="Identifier"
            activeField={props.activeField}
            activeDir={props.activeDir}
            onToggle={props.onToggle}
            secondary={props.secondary}
          />
        </Table.Tr>
      </Table.Thead>
    </Table>,
  );
}

describe("SortHeader", () => {
  test("primary field only renders one sort button", () => {
    renderHeader({ activeField: "identifier", activeDir: "asc", onToggle: vi.fn() });
    expect(screen.getAllByRole("button")).toHaveLength(1);
    expect(screen.getByRole("columnheader")).toHaveAttribute("aria-sort", "ascending");
  });

  test("a secondary field renders a second button that toggles it", async () => {
    const onToggle = vi.fn();
    const user = userEvent.setup();
    renderHeader({
      activeField: "identifier",
      activeDir: "asc",
      onToggle,
      secondary: { field: "title", label: "Title" },
    });

    expect(screen.getByRole("button", { name: "Identifier" })).toBeInTheDocument();
    const secondaryButton = screen.getByRole("button", { name: "Title" });
    expect(secondaryButton).toBeInTheDocument();

    await user.click(secondaryButton);
    expect(onToggle).toHaveBeenCalledWith("title");
  });

  test("aria-sort reflects the secondary field when it is the active one", () => {
    renderHeader({
      activeField: "title",
      activeDir: "desc",
      onToggle: vi.fn(),
      secondary: { field: "title", label: "Title" },
    });
    expect(screen.getByRole("columnheader")).toHaveAttribute("aria-sort", "descending");
    // One cell-level aria-sort cannot say WHICH of the two fields it describes, so each
    // button exposes its own state.
    expect(screen.getByRole("button", { name: "Title" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Identifier" })).toHaveAttribute("aria-pressed", "false");
  });

  test("aria-sort is none when neither the primary nor the secondary field is active", () => {
    renderHeader({
      activeField: "updatedAt",
      activeDir: "asc",
      onToggle: vi.fn(),
      secondary: { field: "title", label: "Title" },
    });
    expect(screen.getByRole("columnheader")).toHaveAttribute("aria-sort", "none");
  });
});
