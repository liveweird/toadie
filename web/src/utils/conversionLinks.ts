// The conversion route family — the ONE place `/ontology/mappings…` is spelled out (the
// blueprintLinks rule). The Port world's Ontology section hosts the mapping registry; the
// export/import pages that USE a mapping live beside it.

export const conversionMappingsPath = "/ontology/mappings";
export const newConversionMappingPath = "/ontology/mappings/new";
export const editConversionMappingPath = (id: number) => `/ontology/mappings/${id}/edit`;

export const exportBackstagePath = "/ontology/export-backstage";
