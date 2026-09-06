import { createUserViaUi, expect, login, test } from "./helpers";

type LayoutDocument = {
  mode: "auto" | "manual";
  positions: Record<string, { x: number; y: number }>;
  collapsed: string[];
};

function deferred() {
  let settled = false;
  let resolvePromise!: () => void;
  const promise = new Promise<void>((resolve) => {
    resolvePromise = resolve;
  });
  return {
    promise,
    release: () => {
      if (settled) return;
      settled = true;
      resolvePromise();
    },
  };
}

test("layout loading and serialized retries preserve the latest full document", async ({ page }, testInfo) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide cleanup authorization").toBe(true);

  let user: Awaited<ReturnType<typeof createUserViaUi>> | undefined;
  const releaseInitialGet = deferred();
  const initialGetFetched = deferred();
  const releaseFirstPut = deferred();
  const firstPutFetched = deferred();
  const trailingPutCompleted = deferred();
  const failedPutSeen = deferred();
  const retryPutCompleted = deferred();

  try {
    user = await createUserViaUi(page, "E2E Graph Persistence");
    const layoutPath = `/api/v1/users/${user.id}/graph-layout`;
    const hiddenNode = "component:e2e-hidden/retained-off-screen";
    const baseline: LayoutDocument = {
      mode: "auto",
      positions: { [hiddenNode]: { x: 321.5, y: -87.25 } },
      collapsed: [hiddenNode],
    };
    const seeded = await page.request.put(layoutPath, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: baseline,
    });
    expect(seeded.status()).toBe(204);

    let holdInitialGet = true;
    let failNextGet = false;
    let failNextPut = false;
    const putBodies: LayoutDocument[] = [];
    const putStatuses: number[] = [];

    await page.route(`**${layoutPath}`, async (route) => {
      const method = route.request().method();
      if (method === "GET" && holdInitialGet) {
        holdInitialGet = false;
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        initialGetFetched.release();
        await releaseInitialGet.promise;
        await route.fulfill({ response });
        return;
      }
      if (method === "GET" && failNextGet) {
        failNextGet = false;
        await route.fulfill({
          status: 503,
          contentType: "application/problem+json",
          body: JSON.stringify({ title: "Unavailable", status: 503 }),
        });
        return;
      }
      if (method !== "PUT") {
        await route.continue();
        return;
      }

      const body = route.request().postDataJSON() as LayoutDocument;
      const index = putBodies.push(body) - 1;
      if (index === 0) {
        const response = await route.fetch();
        putStatuses[index] = response.status();
        firstPutFetched.release();
        await releaseFirstPut.promise;
        await route.fulfill({ response });
        return;
      }
      if (failNextPut) {
        failNextPut = false;
        putStatuses[index] = 503;
        failedPutSeen.release();
        await route.fulfill({
          status: 503,
          contentType: "application/problem+json",
          body: JSON.stringify({ title: "Unavailable", status: 503 }),
        });
        return;
      }

      const response = await route.fetch();
      putStatuses[index] = response.status();
      await route.fulfill({ response });
      if (index === 1) trailingPutCompleted.release();
      if (index === 3) retryPutCompleted.release();
    });

    // Logging in directly clears the browser's admin credentials without revoking the retained
    // admin session, which remains valid solely for finally cleanup.
    await login(page, user.email, user.password);
    await page.goto("/graph");
    await initialGetFetched.promise;

    await expect(page.getByRole("status", { name: "Loading saved layout…" })).toBeVisible();
    await expect(page.getByRole("radio", { name: "Auto" })).toBeDisabled();
    await expect(page.getByRole("radio", { name: "Manual" })).toBeDisabled();
    expect(putBodies).toHaveLength(0);

    releaseInitialGet.release();
    await expect(page.getByRole("radio", { name: "Auto" })).toBeChecked();
    await expect(page.getByRole("radio", { name: "Manual" })).toBeEnabled();

    // A failed fresh-device load keeps all mutating controls inert until explicit retry restores
    // the complete server baseline.
    failNextGet = true;
    await page.reload();
    const loadAlert = page.getByRole("alert").filter({ hasText: "Failed to load the saved layout" });
    await expect(loadAlert).toBeVisible();
    await expect(page.getByRole("radio", { name: "Manual" })).toBeDisabled();
    const retriedGet = page.waitForResponse(
      (response) => response.url().endsWith(layoutPath) && response.request().method() === "GET",
    );
    await loadAlert.getByRole("button", { name: "Retry loading layout" }).click();
    expect((await retriedGet).status()).toBe(200);
    await expect(page.getByRole("radio", { name: "Auto" })).toBeChecked();
    await expect(loadAlert).toHaveCount(0);

    // Hold the first real acknowledgement. Later mode edits update the UI immediately but may
    // produce only one trailing request, containing the newest whole document.
    await page.getByText("Manual", { exact: true }).click();
    await firstPutFetched.promise;
    await expect(page.getByRole("status", { name: "Saving layout…" })).toBeVisible();
    await page.getByText("Auto", { exact: true }).click();
    await page.getByText("Manual", { exact: true }).click();
    await expect(page.getByRole("radio", { name: "Manual" })).toBeChecked();
    expect(putBodies).toHaveLength(1);

    releaseFirstPut.release();
    await trailingPutCompleted.promise;
    await expect(page.getByRole("status", { name: "Saving layout…" })).toHaveCount(0);
    expect(putStatuses.slice(0, 2)).toEqual([204, 204]);
    expect(putBodies).toEqual([
      { ...baseline, mode: "manual" },
      { ...baseline, mode: "manual" },
    ]);

    // A failed replace leaves local state editable but pauses transport. A newer Manual choice
    // stays local until Retry, which must carry that latest document rather than replaying the
    // failed Auto request.
    failNextPut = true;
    await page.getByText("Auto", { exact: true }).click();
    await failedPutSeen.promise;
    const saveAlert = page.getByRole("alert").filter({ hasText: "Failed to save the layout" });
    await expect(saveAlert).toBeVisible();
    await expect(page.getByRole("radio", { name: "Auto" })).toBeChecked();
    await page.getByText("Manual", { exact: true }).click();
    await expect(page.getByRole("radio", { name: "Manual" })).toBeChecked();
    await page.getByRole("button", { name: "Reset layout" }).click();
    expect(putBodies).toHaveLength(3);
    await testInfo.attach("graph-layout-save-error", {
      body: await page.screenshot(),
      contentType: "image/png",
    });

    await saveAlert.getByRole("button", { name: "Retry saving layout" }).click();
    await retryPutCompleted.promise;
    await expect(saveAlert).toHaveCount(0);
    expect(putStatuses).toEqual([204, 204, 503, 204]);
    expect(putBodies.slice(2)).toEqual([
      { ...baseline, mode: "auto" },
      { mode: "manual", positions: {}, collapsed: baseline.collapsed },
    ]);

    const userToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
    expect(userToken).not.toBeNull();
    const stored = await page.request.get(layoutPath, {
      headers: { Authorization: `Bearer ${userToken}` },
    });
    expect(stored.status()).toBe(200);
    expect(await stored.json()).toEqual({ mode: "manual", positions: {}, collapsed: baseline.collapsed });

    await page.reload();
    await expect(page.getByRole("radio", { name: "Manual" })).toBeChecked();
    await expect(page.getByRole("radio", { name: "Auto" })).toBeEnabled();
  } finally {
    // Unblock any intercepted request before cleanup even when an assertion fails.
    releaseInitialGet.release();
    releaseFirstPut.release();
    if (user && adminToken) {
      const deleted = await page.request.delete(`/api/v1/users/${user.id}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(deleted.status()).toBe(204);
    }
  }
});
