// The blueprint link builders — the ONE place the /blueprints route family is spelled out
// (the catalogFileLinks rule). Every surface that links to these screens goes through these
// instead of hand-assembling URLs.

export const blueprintsPath = "/blueprints";
export const newBlueprintPath = "/blueprints/new";
export const editBlueprintPath = (id: number) => `/blueprints/${id}/edit`;
