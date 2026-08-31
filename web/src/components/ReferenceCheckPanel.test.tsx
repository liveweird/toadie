import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import ReferenceCheckPanel from "./ReferenceCheckPanel";
import type { DocumentCheckFinding } from "../api/catalogFiles";
import { renderWithProviders } from "../test/render";

// The panel is a pure renderer: the check itself runs once in the editor shell
// (useDocumentCheck) and is shared with the field block, so the request behaviour — the
// debounce, the query key, the silence on failure — is covered by the editor page tests.

const FINDINGS: DocumentCheckFinding[] = [
  { field: "spec.owner", reference: "group:default/ghost", status: "MISSING" },
  { field: "spec.dependsOn", reference: "orders-db", status: "KIND_REQUIRED" },
];

describe("ReferenceCheckPanel", () => {
  test("lists every finding with its status message", () => {
    renderWithProviders(<ReferenceCheckPanel findings={FINDINGS} checked />);

    expect(screen.getByText("Findings — saving will ask for confirmation")).toBeInTheDocument();
    expect(screen.getByText("group:default/ghost")).toBeInTheDocument();
    expect(screen.getByText(/No stored entity matches this reference/)).toBeInTheDocument();
    expect(screen.getByText("orders-db")).toBeInTheDocument();
    expect(screen.getByText(/need an explicit kind/)).toBeInTheDocument();
  });

  test("shows the all-clear line once a check has answered with nothing", () => {
    renderWithProviders(<ReferenceCheckPanel findings={[]} checked />);
    expect(screen.getByText("No findings — the document passes every check.")).toBeInTheDocument();
  });

  test("stays quiet before the first check answers — no premature all-clear", () => {
    renderWithProviders(<ReferenceCheckPanel findings={[]} checked={false} />);
    expect(
      screen.queryByText("No findings — the document passes every check."),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("Findings — saving will ask for confirmation"),
    ).not.toBeInTheDocument();
  });
});
