import { readdirSync } from "node:fs";
import { resolve } from "node:path";

// Every Gradle lockfile in the repository, as repo-relative POSIX paths (build outputs, installed
// packages and agent worktrees are not part of the repository).
export function lockfiles(root, dir = "") {
  const skipped = new Set(["node_modules", "build", ".git", ".gradle", ".kotlin", ".claude"]);
  return readdirSync(resolve(root, dir), { withFileTypes: true }).flatMap((entry) => {
    const path = dir ? `${dir}/${entry.name}` : entry.name;
    if (entry.isDirectory()) return skipped.has(entry.name) ? [] : lockfiles(root, path);
    return entry.name.endsWith("gradle.lockfile") ? [path] : [];
  });
}
