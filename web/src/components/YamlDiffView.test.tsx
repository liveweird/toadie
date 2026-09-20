import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import YamlDiffView from "./YamlDiffView";
import { diffLines, YAML_DIFF_LINE_LIMIT } from "../utils/yamlDiff";

function lines(count: number, value = "x") {
  return Array.from({ length: count }, () => value).join("\n");
}

function oversizedFallback() {
  // Combined line count past YAML_DIFF_LINE_LIMIT forces the fallback branch — the same
  // oversized-input idiom `yamlDiff.test.ts` uses.
  const diff = diffLines(lines(YAML_DIFF_LINE_LIMIT), lines(YAML_DIFF_LINE_LIMIT + 1));
  expect(diff.kind).toBe("fallback");
  return diff;
}

describe("YamlDiffView", () => {
  test("the over-budget fallback renders the default catalog.diff.* texts unchanged", () => {
    renderWithProviders(<YamlDiffView diff={oversizedFallback()} label="Diff" />);

    expect(
      screen.getByText(
        "These documents are too large for a detailed line comparison. Review the complete stored and replacement YAML below.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Complete stored YAML")).toBeInTheDocument();
    expect(screen.getByText("Complete replacement YAML")).toBeInTheDocument();
  });

  test("custom fallback labels render in the over-budget fallback", () => {
    renderWithProviders(
      <YamlDiffView
        diff={oversizedFallback()}
        label="Diff"
        fallbackLabels={{
          tooLarge: "Too large for entity comparison",
          stored: "Complete stored document",
          replacement: "Complete source document",
        }}
      />,
    );

    expect(screen.getByText("Too large for entity comparison")).toBeInTheDocument();
    expect(screen.getByText("Complete stored document")).toBeInTheDocument();
    expect(screen.getByText("Complete source document")).toBeInTheDocument();
    expect(screen.queryByText("Complete stored YAML")).not.toBeInTheDocument();
  });
});
