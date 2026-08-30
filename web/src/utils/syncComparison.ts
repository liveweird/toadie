// The pure comparison behind the sync modal: which side moved since the last sync, and the
// line diff to show. All equality runs over the canonical catalogInfoYaml render — one
// semantics for the badges AND the diff (see SyncCatalogFileModal).

import { diffLines, type DiffLine } from "./yamlDiff";

export interface SyncComparison {
  /** Stored and repo renders are identical — nothing to overwrite. */
  inSync: boolean;
  /** The stored copy moved since the sync (`updatedAt > lastSyncedAt`, synced rows only). */
  dbChanged: boolean;
  /** The repo copy differs from the baseline snapshot taken at the last sync. */
  repoChanged: boolean;
  /** Stored → repo line diff; null while either side is missing or already in sync. */
  diff: DiffLine[] | null;
}

/**
 * The DB-moved-since-sync predicate — meaningful only while the row HAS been synced
 * (`lastSyncedAt > 0`): a sync stamps `updatedAt` and `lastSyncedAt` equal, and a
 * changed/cleared source reference resets `lastSyncedAt` to 0, where the comparison
 * carries no drift meaning. Shared by the Files list's "Local changes" badge and the
 * sync modal's side badge.
 */
export function hasLocalChanges(file: { updatedAt: number; lastSyncedAt: number }): boolean {
  return file.lastSyncedAt > 0 && file.updatedAt > file.lastSyncedAt;
}

export function compareSyncSides(input: {
  /** Canonical render of the stored document; null while still loading. */
  currentYaml: string | null;
  /** Canonical render of the fetched repo document; null while loading or unparsable. */
  repoYaml: string | null;
  /** Canonical render of the baseline stored at the last sync; null = never synced. */
  baselineYaml: string | null;
  /** The stored row's updatedAt; null while still loading. */
  updatedAt: number | null;
  /** The stored row's lastSyncedAt (0 = never synced). */
  lastSyncedAt: number;
}): SyncComparison {
  const { currentYaml, repoYaml, baselineYaml, updatedAt, lastSyncedAt } = input;
  const inSync = currentYaml != null && repoYaml != null && currentYaml === repoYaml;
  return {
    inSync,
    dbChanged: updatedAt != null && hasLocalChanges({ updatedAt, lastSyncedAt }),
    repoChanged: repoYaml != null && baselineYaml != null && repoYaml !== baselineYaml,
    diff: currentYaml != null && repoYaml != null && !inSync ? diffLines(currentYaml, repoYaml) : null,
  };
}
