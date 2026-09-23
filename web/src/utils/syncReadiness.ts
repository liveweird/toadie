/** Minimal React Query state used by source-sync readiness checks. */
type SettledQuery = {
  isSuccess: boolean;
  isFetching: boolean;
};

type PreflightQuery = SettledQuery & {
  isPending: boolean;
};

export function querySettled(query: SettledQuery): boolean {
  return query.isSuccess && !query.isFetching;
}

export function preflightPending(hasDocument: boolean, query: PreflightQuery): boolean {
  return hasDocument && (query.isPending || query.isFetching);
}

/** Dry-run sync accepts only the two successful ontology-import classifications. */
export function preflightAccepted(
  query: SettledQuery,
  status: string | undefined,
): boolean {
  return querySettled(query) && ["CREATED", "UPDATED"].includes(status ?? "");
}
