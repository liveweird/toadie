import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listEntities, type EntityPage } from "../api/entities";

export type UseEntitiesParams = {
  /** Undefined/blank = no blueprint chosen yet — the query stays disabled (the CatalogFiles
   *  noKinds idiom: the page shows its own "pick a blueprint" empty state instead of fetching
   *  an unfiltered, meaningless page). */
  blueprint?: string;
  /** Phase 4 ownership: the Team filter Select's picked `_team` identifier. */
  team?: string;
  q?: string;
  page: number;
  pageSize: number;
  sort?: string;
};

/** The paged entities list behind `pages/Entities.tsx` — one cached query per
 *  blueprint/team/q/page/pageSize/sort combination, `keepPreviousData` so paging/sorting
 *  doesn't flash a spinner. */
export function useEntities(params: UseEntitiesParams) {
  const blueprint = params.blueprint?.trim();
  return useQuery<EntityPage>({
    queryKey: ["entities", blueprint, params.team, params.q, params.page, params.pageSize, params.sort],
    queryFn: () => listEntities({ ...params, blueprint }),
    placeholderData: keepPreviousData,
    enabled: Boolean(blueprint),
  });
}
