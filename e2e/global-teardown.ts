import { execSync } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { STARTED_MARKER } from "./global-setup";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

export default function globalTeardown(): void {
  // Only tear down what we started (a pre-existing/reused stack is left running). The
  // postgres volume is deliberately KEPT (no `-v`): the local database may hold the user's
  // own demo files, and specs clean up their throwaway state themselves. Wipe manually with
  // `docker compose down -v` when you want a pristine database.
  if (!existsSync(STARTED_MARKER)) return;
  console.log("[e2e] Stopping the stack: docker compose down (volume kept) …");
  execSync("docker compose down", { cwd: repoRoot, stdio: "inherit" });
  rmSync(STARTED_MARKER, { force: true });
}
