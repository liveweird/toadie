import { useQuery } from "@tanstack/react-query";
import { listEntities, type Entity } from "../api/entities";

const PAGE_SIZE = 100;
/** The relation-target picker never needs more than this many candidates — the server's
 *  MAX_ENTITIES_PER_BLUEPRINT cap (see the plan's persistence section); pooling stops early
 *  regardless of a larger stored total. */
const MAX_OPTIONS = 2000;

async function loadAllOptions(blueprint: string): Promise<Entity[]> {
  const items: Entity[] = [];
  let page = 1;
  for (;;) {
    const result = await listEntities({ blueprint, page, pageSize: PAGE_SIZE, sort: "identifier" });
    items.push(...result.items);
    if (items.length >= result.total || result.items.length === 0 || items.length >= MAX_OPTIONS) {
      return items.slice(0, MAX_OPTIONS);
    }
    page += 1;
  }
}

/**
 * The stored-entity pool behind a relation picker's target blueprint (the useCatalogIdentities
 * pool-loop idiom, bounded — no dedicated options endpoint per the plan: the `{items}` wrapper
 * is reserved for a-few-dozen-row registries). Advisory data: a loading/failed pool still lets
 * the field display its current value, just without other choices to pick from.
 */
export function useEntityOptions(blueprint: string): {
  options: Entity[];
  loading: boolean;
  error: boolean;
} {
  const trimmed = blueprint.trim();
  const { data, isLoading, isError } = useQuery({
    queryKey: ["entities", "options", trimmed],
    queryFn: () => loadAllOptions(trimmed),
    enabled: trimmed !== "",
    staleTime: 5 * 60 * 1000,
  });
  return { options: data ?? [], loading: isLoading, error: isError };
}
