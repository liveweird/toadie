import { describe, expect, test, vi } from "vitest";
import type { ParseKeys } from "i18next";
import userEvent from "@testing-library/user-event";
import CatalogFileOperations from "./CatalogFileOperations";
import i18n from "../i18n";
import { renderWithProviders, screen } from "../test/render";

function handlers() {
  return { onExport: vi.fn(), onOverwrite: vi.fn(), onDelete: vi.fn() };
}

const triggerName = () => i18n.t("common.table.operationsAria", { name: "orders" });
const label = (key: ParseKeys) => i18n.t(key);

async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: triggerName() }));
}

describe("CatalogFileOperations", () => {
  test("the bare menu offers Edit, Export, Overwrite and Delete only", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CatalogFileOperations id={7} name="orders" downloading={false} {...handlers()} />);
    await openMenu(user);

    expect(screen.getByRole("menuitem", { name: label("common.action.edit") })).toHaveAttribute("href", "/files/7/edit");
    expect(screen.getByRole("menuitem", { name: label("catalog.exportFile") })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: label("catalog.overwrite.action") })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: label("common.action.delete") })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: label("catalog.quickView") })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: label("sync.action") })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: label("catalog.pin.action") })).not.toBeInTheDocument();
  });

  test("Export, Overwrite and Delete fire their callbacks", async () => {
    const user = userEvent.setup();
    const h = handlers();
    renderWithProviders(<CatalogFileOperations id={7} name="orders" downloading={false} {...h} />);

    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: label("catalog.exportFile") }));
    expect(h.onExport).toHaveBeenCalledOnce();

    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: label("catalog.overwrite.action") }));
    expect(h.onOverwrite).toHaveBeenCalledOnce();

    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: label("common.action.delete") }));
    expect(h.onDelete).toHaveBeenCalledOnce();
  });

  test("Quick view appears only with its callback and fires it", async () => {
    const user = userEvent.setup();
    const onQuickView = vi.fn();
    renderWithProviders(
      <CatalogFileOperations id={7} name="orders" downloading={false} {...handlers()} onQuickView={onQuickView} />,
    );
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: label("catalog.quickView") }));
    expect(onQuickView).toHaveBeenCalledOnce();
  });

  test("Sync fires while enabled", async () => {
    const user = userEvent.setup();
    const onSync = vi.fn();
    renderWithProviders(
      <CatalogFileOperations id={7} name="orders" downloading={false} {...handlers()} sync={{ onSync, enabled: true }} />,
    );
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: label("sync.action") }));
    expect(onSync).toHaveBeenCalledOnce();
  });

  test("Sync greys out instead of vanishing for a source-less row", async () => {
    const user = userEvent.setup();
    const onSync = vi.fn();
    renderWithProviders(
      <CatalogFileOperations id={7} name="orders" downloading={false} {...handlers()} sync={{ onSync, enabled: false }} />,
    );
    await openMenu(user);
    const item = screen.getByRole("menuitem", { name: label("sync.action") });
    expect(item).toBeDisabled();
    await user.click(item);
    expect(onSync).not.toHaveBeenCalled();
  });

  test("Pin reads Pin while unpinned and toggles", async () => {
    const user = userEvent.setup();
    const onToggle = vi.fn();
    renderWithProviders(
      <CatalogFileOperations id={7} name="orders" downloading={false} {...handlers()} pin={{ onToggle, pinned: false }} />,
    );
    await openMenu(user);
    expect(screen.queryByRole("menuitem", { name: label("catalog.pin.clear") })).not.toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: label("catalog.pin.action") }));
    expect(onToggle).toHaveBeenCalledOnce();
  });

  test("Pin reads Unpin while this row is the pinned one", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CatalogFileOperations id={7} name="orders" downloading={false} {...handlers()} pin={{ onToggle: vi.fn(), pinned: true }} />,
    );
    await openMenu(user);
    expect(screen.getByRole("menuitem", { name: label("catalog.pin.clear") })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: label("catalog.pin.action") })).not.toBeInTheDocument();
  });

  test("the trigger shows its loading state while downloading", () => {
    renderWithProviders(<CatalogFileOperations id={7} name="orders" downloading {...handlers()} />);
    expect(screen.getByRole("button", { name: triggerName() })).toHaveAttribute("data-loading", "true");
  });
});
