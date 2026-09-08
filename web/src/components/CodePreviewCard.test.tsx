import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import CodePreviewCard from "./CodePreviewCard";
import { renderWithProviders } from "../test/render";

describe("CodePreviewCard", () => {
  test("renders the title and the text inside a labelled, keyboard-focusable code block", () => {
    renderWithProviders(<CodePreviewCard title="JSON preview" label="JSON preview" text='{"a":1}' />);

    expect(screen.getByRole("heading", { name: "JSON preview" })).toBeInTheDocument();
    const code = screen.getByLabelText("JSON preview");
    expect(code).toHaveTextContent('{"a":1}');
  });

  test("a distinct label names the code block separately from the title", () => {
    renderWithProviders(<CodePreviewCard title="Preview" label="Some other label" text="x" />);

    expect(screen.getByRole("heading", { name: "Preview" })).toBeInTheDocument();
    expect(screen.getByLabelText("Some other label")).toHaveTextContent("x");
  });

  test("embedded renders the same content without an extra card wrapper", () => {
    const { container: standalone } = renderWithProviders(
      <CodePreviewCard title="Preview" label="Preview" text="x" />,
    );
    expect(standalone.querySelector(".mantine-Paper-root")).not.toBeNull();

    const { container: embedded } = renderWithProviders(
      <CodePreviewCard title="Preview" label="Preview" text="x" embedded />,
    );
    expect(embedded.querySelector(".mantine-Paper-root")).toBeNull();
  });
});
