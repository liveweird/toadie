import { execSync } from "node:child_process";
import { existsSync, readFileSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { BASE_URL } from "./playwright.config";
import { adminApiHeaders, NAMESPACES_FILE, STARTED_MARKER, type DictionaryItems } from "./global-setup";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

/**
 * Removes the run namespaces global-setup registered — a reused local stack keeps running,
 * and without this each run would leave three throwaway entries in its dictionary. Runs
 * before any compose-down (the API has to still be up), and best-effort: a torn-down or
 * unreachable stack just keeps the file for the next run to overwrite.
 */
async function removeRunNamespaces(): Promise<void> {
  if (!existsSync(NAMESPACES_FILE)) return;
  const minted = Object.values(JSON.parse(readFileSync(NAMESPACES_FILE, "utf8")) as Record<string, string>);
  try {
    const headers = await adminApiHeaders();
    const current = (await (
      await fetch(`${BASE_URL}/api/v1/dictionaries/namespaces`, { headers })
    ).json()) as DictionaryItems;
    const kept = current.items.filter(({ value }) => !minted.includes(value));
    if (kept.length < current.items.length) {
      await fetch(`${BASE_URL}/api/v1/dictionaries/namespaces`, {
        method: "PUT",
        headers,
        body: JSON.stringify({ items: kept.map(({ id, value }) => ({ id, value })) }),
      });
    }
    rmSync(NAMESPACES_FILE, { force: true });
    console.log("[e2e] Removed the run namespaces.");
  } catch {
    console.log("[e2e] Stack unreachable — run-namespace cleanup skipped.");
  }
}

export default async function globalTeardown(): Promise<void> {
  await removeRunNamespaces();
  // Only tear down what we started (a pre-existing/reused stack is left running). The
  // postgres volume is deliberately KEPT (no `-v`): the local database may hold the user's
  // own demo files, and specs clean up their throwaway state themselves. Wipe manually with
  // `docker compose down -v` when you want a pristine database.
  if (!existsSync(STARTED_MARKER)) return;
  console.log("[e2e] Stopping the stack: docker compose down (volume kept) …");
  execSync("docker compose down", { cwd: repoRoot, stdio: "inherit" });
  rmSync(STARTED_MARKER, { force: true });
}
