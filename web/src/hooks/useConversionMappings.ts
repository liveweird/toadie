import { listConversionMappings, type ConversionMapping } from "../api/conversionMappings";
import { useRegistryQuery } from "./useRegistryQuery";

/**
 * One cached query for the conversion-mapping registry — the ["conversionMappings"] key is shared
 * with pages/ConversionMappings.tsx, so a save or delete there refreshes every picker at once
 * (the export and import pages choose a mapping from this list).
 */
export function useConversionMappings(): {
  mappings: ConversionMapping[];
  loading: boolean;
  error: boolean;
  loadError: unknown;
} {
  return useRegistryQuery(["conversionMappings"], listConversionMappings, "mappings");
}
