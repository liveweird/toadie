// The catalog-file link builders — the ONE place the /files route family is spelled out.
// Every surface that links to these screens (nav, tables, editors, report rows) goes through
// these instead of hand-assembling URLs, so a route rename is a one-file change.

export const catalogFilesPath = "/files";
export const newCatalogFilePath = "/files/new";
export const importCatalogFilesPath = "/files/import";
export const editCatalogFilePath = (id: number) => `/files/${id}/edit`;
