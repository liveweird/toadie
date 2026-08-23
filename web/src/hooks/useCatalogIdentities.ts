import { useQuery } from "@tanstack/react-query";
import { listAllCatalogFiles } from "../api/catalogFiles";

/**
 * The stored-identity pool behind the editor's reference pickers (the Lettuce option-pool hook
 * shape). Advisory data: consumers degrade to plain free-text fields while loading or on
 * failure. The `["catalogFiles", …]` key prefix means every file mutation's invalidation
 * refreshes the pool too.
 */
export function useCatalogIdentities() {
  const { data } = useQuery({
    queryKey: ["catalogFiles", "identities"],
    queryFn: () => listAllCatalogFiles(),
    staleTime: 5 * 60 * 1000,
  });
  return data;
}
