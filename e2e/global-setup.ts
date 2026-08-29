import { execSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { BASE_URL } from "./playwright.config";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");
export const STARTED_MARKER = resolve(here, ".playwright", "stack-started");
// The per-run namespaces registered below, persisted for global-teardown's removal.
export const NAMESPACES_FILE = resolve(here, ".playwright", "run-namespaces.json");

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

/** Logs in as the seed admin and returns Authorization/Content-Type headers. */
export async function adminApiHeaders(): Promise<Record<string, string>> {
  const login = await fetch(`${BASE_URL}/api/v1/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: "admin@toadie.local", password: "changeme" }),
  });
  if (!login.ok) throw new Error(`[e2e] admin API login failed: ${login.status}`);
  const { token } = (await login.json()) as { token: string };
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
}

export type DictionaryItems = { items: { id: number; value: string; isDefault: boolean }[] };

/**
 * The well-known owner Group (`group:default/platform`) the specs' Component fixtures
 * reference — catalog writes enforce reference RESOLUTION, so it must be stored. Idempotent
 * (a 409 means an earlier run seeded it) and deliberately PERSISTENT: it carries no `e2e`
 * marker because it must survive runs (the volume's own demo files may reference it too).
 */
async function ensurePlatformGroup(headers: Record<string, string>): Promise<void> {
  const post = await fetch(`${BASE_URL}/api/v1/catalog-files`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      kind: "Group",
      metadata: { name: "platform", namespace: "default" },
      spec: { type: "team", children: [] },
    }),
  });
  if (post.status !== 201 && post.status !== 409) {
    throw new Error(`[e2e] platform-group seed failed: ${post.status}`);
  }
}

/**
 * Registers this run's throwaway namespaces (catalog writes accept only dictionary-defined
 * namespaces) and hands them to the workers via process.env — the setup process is the ONE
 * dictionary writer besides namespaces.spec.ts, because the PUT is a whole-document replace
 * and concurrent writers from parallel workers would lose each other's entries.
 */
async function registerRunNamespaces(): Promise<void> {
  const headers = await adminApiHeaders();
  await ensurePlatformGroup(headers);
  const uniq = `${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`;
  const minted: Record<string, string> = {
    KINDS: `e2e-kns-${uniq}`,
    RENDER: `e2e-rns-${uniq}`,
    ROUNDTRIP: `e2e-rtns-${uniq}`,
    HIERARCHY: `e2e-hns-${uniq}`,
  };
  const current = (await (
    await fetch(`${BASE_URL}/api/v1/dictionaries/namespaces`, { headers })
  ).json()) as DictionaryItems;
  const put = await fetch(`${BASE_URL}/api/v1/dictionaries/namespaces`, {
    method: "PUT",
    headers,
    body: JSON.stringify({
      items: [
        // The whole-document replace must replay each row's DEFAULT flag, or the
        // exactly-one-default rule rejects the PUT (and a flip would be a silent side effect).
        ...current.items.map(({ id, value, isDefault }) => ({ id, value, isDefault })),
        ...Object.values(minted).map((value) => ({ value })),
      ],
    }),
  });
  if (put.status !== 204) throw new Error(`[e2e] namespace registration failed: ${put.status}`);
  for (const [key, value] of Object.entries(minted)) process.env[`E2E_NS_${key}`] = value;
  mkdirSync(dirname(NAMESPACES_FILE), { recursive: true });
  writeFileSync(NAMESPACES_FILE, JSON.stringify(minted));
  console.log(`[e2e] Registered run namespaces: ${Object.values(minted).join(", ")}`);
}

export default async function globalSetup(): Promise<void> {
  // Reuse a stack that's already up (fast local iteration); don't tear it down afterward.
  if (await responds(BASE_URL)) {
    console.log(`[e2e] Reusing the app already running at ${BASE_URL}`);
    await registerRunNamespaces();
    return;
  }

  console.log("[e2e] Starting the stack: docker compose up -d --build …");
  execSync("docker compose up -d --build", { cwd: repoRoot, stdio: "inherit" });

  console.log(`[e2e] Waiting for ${BASE_URL} …`);
  await waitForUp(BASE_URL, 240_000);

  mkdirSync(dirname(STARTED_MARKER), { recursive: true });
  writeFileSync(STARTED_MARKER, "started-by-e2e");
  console.log("[e2e] Stack is up.");
  await registerRunNamespaces();
}
