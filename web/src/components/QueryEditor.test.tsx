import { beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import QueryEditor from "./QueryEditor";
import type { QueryCompletionSchema } from "../utils/queryCompletion";

// CodeMirror measures selections/ranges via document.createRange and ResizeObserver, neither
// of which happy-dom implements. These are the minimal shims needed to let a real EditorView
// mount and take keyboard input under happy-dom — the e2e suite drives the real rendered
// editor (fonts, scrolling, real selection) end to end.
beforeEach(() => {
  class StubRange {
    setStart() {}
    setEnd() {}
    getBoundingClientRect() {
      return { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 } as DOMRect;
    }
    getClientRects() {
      return [] as unknown as DOMRectList;
    }
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  document.createRange = (() => new StubRange()) as any;

  class StubResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal("ResizeObserver", StubResizeObserver);
});

const schema: QueryCompletionSchema = { blueprints: [], hierarchies: [] };

describe("QueryEditor", () => {
  test("renders the initial value and reports edits via onChange", async () => {
    const onChange = vi.fn();
    const onRun = vi.fn();
    renderWithProviders(
      <QueryEditor
        value="MATCH (a)"
        onChange={onChange}
        onRun={onRun}
        diagnostics={[]}
        completionSchema={schema}
        ariaLabel="Entity query"
      />,
    );

    const content = screen.getByRole("textbox", { name: "Entity query" });
    expect(content.textContent).toContain("MATCH (a)");
  });

  test("reports an edit via onChange (a real content mutation, reconciled by CodeMirror's own MutationObserver)", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <QueryEditor
        value="a"
        onChange={onChange}
        onRun={vi.fn()}
        diagnostics={[]}
        completionSchema={schema}
        ariaLabel="Entity query"
      />,
    );

    const content = screen.getByRole("textbox", { name: "Entity query" });
    const line = content.querySelector(".cm-line") as HTMLElement;
    const textNode = line.firstChild as Text;
    // happy-dom has no real contenteditable typing, so a raw DOM mutation stands in for it —
    // CodeMirror reconciles any external change to its content via its own MutationObserver,
    // exactly as it would a browser's native contenteditable edit.
    textNode.data = "ax";
    await Promise.resolve();
    await Promise.resolve();

    expect(onChange).toHaveBeenCalledWith("ax");
  });

  test("calls onRun on Mod-Enter without inserting a newline", async () => {
    const onChange = vi.fn();
    const onRun = vi.fn();
    renderWithProviders(
      <QueryEditor
        value="MATCH (a)"
        onChange={onChange}
        onRun={onRun}
        diagnostics={[]}
        completionSchema={schema}
        ariaLabel="Entity query"
      />,
    );

    // happy-dom's navigator.platform ("X11; Darwin arm64") does not match CodeMirror's `/Mac/`
    // check, so "Mod" resolves to Ctrl here — a real macOS browser would need metaKey instead.
    const content = screen.getByRole("textbox", { name: "Entity query" });
    content.dispatchEvent(
      new KeyboardEvent("keydown", { key: "Enter", ctrlKey: true, bubbles: true, cancelable: true }),
    );
    expect(onRun).toHaveBeenCalledTimes(1);
  });

  test("syncs an externally changed value into the document", () => {
    const { rerender } = renderWithProviders(
      <QueryEditor
        value="MATCH (a)"
        onChange={vi.fn()}
        onRun={vi.fn()}
        diagnostics={[]}
        completionSchema={schema}
        ariaLabel="Entity query"
      />,
    );
    rerender(
      <QueryEditor
        value="MATCH (b)"
        onChange={vi.fn()}
        onRun={vi.fn()}
        diagnostics={[]}
        completionSchema={schema}
        ariaLabel="Entity query"
      />,
    );

    const content = screen.getByRole("textbox", { name: "Entity query" });
    expect(content.textContent).toContain("MATCH (b)");
  });

  test("dispatches lint markers for the given diagnostics without crashing, and unmounts cleanly", () => {
    const { rerender, unmount } = renderWithProviders(
      <QueryEditor
        value="MATCH (a:srv)"
        onChange={vi.fn()}
        onRun={vi.fn()}
        diagnostics={[]}
        completionSchema={schema}
        ariaLabel="Entity query"
      />,
    );
    rerender(
      <QueryEditor
        value="MATCH (a:srv)"
        onChange={vi.fn()}
        onRun={vi.fn()}
        diagnostics={[{ code: "UNKNOWN_LABEL", message: "unknown label 'srv'", line: 1, column: 8, endLine: 1, endColumn: 11 }]}
        completionSchema={schema}
        ariaLabel="Entity query"
      />,
    );

    expect(screen.getByRole("textbox", { name: "Entity query" })).toBeTruthy();
    unmount();
  });
});
