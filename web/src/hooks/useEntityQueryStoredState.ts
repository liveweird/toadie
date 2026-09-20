import { useCallback, useEffect, useState } from "react";
import { useSessionUserId } from "../auth";
import { readStoredJson, writeStoredJson } from "./useStoredState";

const LEGACY_KEYS = ["text", "applied", "open", "picked"] as const;

function discardUnownedLegacyState() {
  try {
    for (const key of LEGACY_KEYS) {
      localStorage.removeItem(`toadie.viewSettings.entityQuery.${key}`);
    }
  } catch {
    // View persistence is best-effort. The scoped state below remains isolated in memory.
  }
}

function entityQueryStorageKey(userId: number, key: string): string {
  return `entityQuery.account.${userId}.${key}`;
}

/**
 * Entity-query view state belongs to the authenticated account. The graph and hierarchy still
 * share one state for that account, while logout and account switches synchronously read a
 * different storage partition. Old global keys are discarded because their owner cannot be
 * established safely.
 */
export function useEntityQueryStoredState<T>(
  key: string,
  initial: T,
  isValid: (value: unknown) => boolean,
): [T, (next: T) => void] {
  const userId = useSessionUserId();
  const storageKey = userId === null ? null : entityQueryStorageKey(userId, key);
  const read = (): T => {
    if (storageKey === null) return initial;
    const stored = readStoredJson(storageKey);
    return stored !== undefined && isValid(stored) ? (stored as T) : initial;
  };
  const [snapshot, setSnapshot] = useState<{ storageKey: string | null; value: T }>(() => ({
    storageKey,
    value: read(),
  }));

  // A key change is handled during render rather than in an effect, so a newly signed-in
  // account can never render or request with the previous account's query for one frame.
  const value = snapshot.storageKey === storageKey ? snapshot.value : read();

  useEffect(() => {
    discardUnownedLegacyState();
  }, [userId]);

  const set = useCallback((next: T) => {
    setSnapshot({ storageKey, value: next });
    if (storageKey !== null) writeStoredJson(storageKey, next);
  }, [storageKey]);

  return [value, set];
}
