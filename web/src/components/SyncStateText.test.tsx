import { describe, expect, test } from "vitest";
import SyncStateText, { syncStateSource } from "./SyncStateText";
import i18n from "../i18n";
import { formatDateTime } from "../utils/relativeTime";
import { renderWithProviders, screen } from "../test/render";

const SOURCE = "https://example.test/catalog-info.yaml";
const SYNCED_AT = Date.now() - 3 * 24 * 60 * 60 * 1000;

describe("SyncStateText", () => {
  test("a row without a source reference reads No source", () => {
    renderWithProviders(<SyncStateText file={{ sourceUrl: null, lastSyncedAt: 0, updatedAt: 5 }} />);
    expect(screen.getByText(i18n.t("catalog.sync.noSource"))).toBeInTheDocument();
  });

  test("a reference that was never synced reads Never synced", () => {
    renderWithProviders(<SyncStateText file={{ sourceUrl: SOURCE, lastSyncedAt: 0, updatedAt: 5 }} />);
    expect(screen.getByText(i18n.t("sync.neverSynced"))).toBeInTheDocument();
    expect(screen.queryByText(i18n.t("catalog.sync.localChanges"))).not.toBeInTheDocument();
  });

  test("a synced row shows the relative time with the absolute title and no marker", () => {
    renderWithProviders(<SyncStateText file={{ sourceUrl: SOURCE, lastSyncedAt: SYNCED_AT, updatedAt: SYNCED_AT }} />);
    expect(screen.getByTitle(formatDateTime(SYNCED_AT, i18n.language))).toHaveTextContent("3 days ago");
    expect(screen.queryByText(i18n.t("catalog.sync.localChanges"))).not.toBeInTheDocument();
  });

  test("a row edited after its sync carries the local-changes marker", () => {
    renderWithProviders(
      <SyncStateText file={{ sourceUrl: SOURCE, lastSyncedAt: SYNCED_AT, updatedAt: SYNCED_AT + 1000 }} />,
    );
    expect(screen.getByText(i18n.t("catalog.sync.localChanges"))).toBeInTheDocument();
  });
});

describe("syncStateSource", () => {
  test("narrows a row, mapping an absent sourceUrl to null", () => {
    expect(syncStateSource({ lastSyncedAt: 1, updatedAt: 2 })).toEqual({
      sourceUrl: null,
      lastSyncedAt: 1,
      updatedAt: 2,
    });
    expect(syncStateSource({ sourceUrl: SOURCE, lastSyncedAt: 1, updatedAt: 2 })).toEqual({
      sourceUrl: SOURCE,
      lastSyncedAt: 1,
      updatedAt: 2,
    });
  });
});
