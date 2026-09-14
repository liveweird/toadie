// The ontology-import route — the ONE place `/ontology/import` is spelled out (the
// catalogFileLinks/blueprintLinks/entityLinks rule). Reached from the nav, and linked from
// both the Blueprints and Entities pages, so no single "back to" makes sense for it.

export const ontologyImportPath = "/ontology/import";

// The Port-world Errors report route (v2.5.0) — the ONE place `/ontology/errors` is spelled
// out. Reached from the nav only; the Open-in-graph action navigates AWAY to `entityGraphPath`
// instead of linking back here.
export const ontologyErrorsPath = "/ontology/errors";
