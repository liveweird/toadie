import { execSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { BASE_URL } from "./playwright.config";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");
export const STARTED_MARKER = resolve(here, ".playwright", "stack-started");

async function responds(url: string): Promise<boolean> {
  try {
    const res = await fetch(url, { redirect: "manual" });
    // Any HTTP response (the SPA index or a redirect) means the server is accepting connections.
    return res.status > 0;
  } catch {
    return false;
  }
}

async function waitForUp(url: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await responds(url)) return;
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(`Timed out waiting for the app at ${url}`);
}

export default async function globalSetup(): Promise<void> {
  // Reuse a stack that's already up (fast local iteration); don't tear it down afterward.
  if (await responds(BASE_URL)) {
    console.log(`[e2e] Reusing the app already running at ${BASE_URL}`);
    return;
  }

  console.log("[e2e] Starting the stack: docker compose up -d --build …");
  execSync("docker compose up -d --build", { cwd: repoRoot, stdio: "inherit" });

  console.log(`[e2e] Waiting for ${BASE_URL} …`);
  await waitForUp(BASE_URL, 240_000);

  mkdirSync(dirname(STARTED_MARKER), { recursive: true });
  writeFileSync(STARTED_MARKER, "started-by-e2e");
  console.log("[e2e] Stack is up.");
}
