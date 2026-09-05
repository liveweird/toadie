import { expect, login, readyDialog, test } from "./helpers";

test("dialog actions wait for the entrance transition to finish", async ({ page }) => {
  await login(page);
  await page.goto("/files");
  // Hold the real modal at its entrance state. No fabricated API data, fixed sleeps,
  // animation-duration changes, or server writes: the unsaved lens is cancelled below.
  const entrance = await page.addStyleTag({ content: ".mantine-Modal-content { opacity: 0 !important; }" });
  await page.getByRole("button", { name: "Lens actions" }).click();
  await page.getByRole("menuitem", { name: "Save as new lens…" }).click();
  const dialog = page.getByRole("dialog", { name: "Save lens", exact: true });
  await expect(dialog).toHaveCSS("opacity", "0");
  // Playwright considers an opacity-zero element visible: that check alone is insufficient.
  await expect(dialog).toBeVisible();

  let ready = false;
  const waiting = readyDialog(page, "Save lens").then((result) => {
    ready = true;
    return result;
  });
  try {
    // The browser round-trip lets an incorrectly immediate helper resolve. The correct
    // helper must still be waiting for opacity, not return an actionable dialog yet.
    await expect(dialog).toHaveCSS("opacity", "0");
    expect(ready).toBe(false);
  } finally {
    await entrance.evaluate((element) => element.parentNode?.removeChild(element));
    await waiting;
  }
  expect(ready).toBe(true);
  await expect(dialog).toHaveCSS("opacity", "1");
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(dialog).toBeHidden();
});
