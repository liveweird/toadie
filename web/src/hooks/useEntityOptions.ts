import { useQuery } from "@tanstack/react-query";
import { listAllEntities, type Entity } from "../api/entities";

/**
 * The stored-entity pool behind a relation picker's target blueprint (`api/entities.ts#listAllEntities`
 * owns the pool loop). Advisory data: a loading/failed pool still lets the field display its
 * current value, just without other choices to pick from.
 */
export function useEntityOptions(blueprint: string): {
  options: Entity[];
  loading: boolean;
  error: boolean;
} {
  const trimmed = blueprint.trim();
  const { data, isLoading, isError } = useQuery({
    queryKey: ["entities", "options", trimmed],
    queryFn: () => listAllEntities({ blueprint: trimmed }),
    enabled: trimmed !== "",
    staleTime: 5 * 60 * 1000,
  });
  return { options: data ?? [], loading: isLoading, error: isError };
}
