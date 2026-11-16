import { describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import EventTimeline, { type TimelineItem } from "./EventTimeline";
import { renderWithProviders } from "../test/render";

const ITEM: TimelineItem = {
  key: 1,
  title: "File updated: Title.",
  lines: ["Title: a → b"],
  who: "Mona Maintainer",
  when: Date.UTC(2026, 7, 30, 9, 15),
};

function shell(props: Partial<React.ComponentProps<typeof EventTimeline>> = {}) {
  return (
    <EventTimeline
      items={[ITEM]}
      isLoading={false}
      isError={false}
      error={null}
      emptyMessage="Nothing yet."
      page={1}
      pageSize={10}
      total={1}
      onPageChange={() => undefined}
      {...props}
    />
  );
}

describe("EventTimeline", () => {
  test("a pending load is a named status", () => {
    renderWithProviders(shell({ items: undefined, isLoading: true }));
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
  });

  test("a failed load is an alert, never the empty note", () => {
    renderWithProviders(shell({ items: undefined, isError: true, error: new Error("x") }));
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.queryByText("Nothing yet.")).not.toBeInTheDocument();
  });

  test("renders title, body lines and the who-when line", () => {
    renderWithProviders(shell());
    expect(screen.getByText("File updated: Title.")).toBeInTheDocument();
    expect(screen.getByText("Title: a → b")).toBeInTheDocument();
    expect(screen.getByText(/Mona Maintainer ·/)).toBeInTheDocument();
  });

  test("pagination appears past one page and reports the chosen page", async () => {
    const onPageChange = vi.fn();
    renderWithProviders(shell({ total: 25, onPageChange }));
    await userEvent.click(screen.getByRole("button", { name: "2" }));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  test("exactly one page shows no pager; one more item does", () => {
    const { unmount } = renderWithProviders(shell({ total: 10 }));
    expect(screen.queryByRole("button", { name: "2" })).not.toBeInTheDocument();
    unmount();
    renderWithProviders(shell({ total: 11 }));
    expect(screen.getByRole("button", { name: "2" })).toBeInTheDocument();
  });

  test("the footer renders under a populated timeline and under the empty note", () => {
    const footer = <p>History starts here.</p>;
    const { unmount } = renderWithProviders(shell({ footer }));
    expect(screen.getByText("History starts here.")).toBeInTheDocument();
    unmount();
    renderWithProviders(shell({ items: [], footer }));
    expect(screen.getByText("Nothing yet.")).toBeInTheDocument();
    expect(screen.getByText("History starts here.")).toBeInTheDocument();
  });
});
