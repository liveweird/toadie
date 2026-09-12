import { describe, expect, it } from "vitest";

// Guards against dead i18n keys: every EN key must be reachable from application source,
// either as a literal string/template segment `t("area.key")` (or the `ParseKeys`-typed
// object literals in utils/navigation.ts etc.) or via an explicitly allowlisted dynamic-key
// prefix (an enum/status-driven `t(\`area.sub.${x}\`)` call this static scan can't otherwise
// resolve). Loads everything through Vite's import.meta.glob — no Node fs/types needed,
// the locales/parity.test.ts idiom for loading JSON, extended to source files via the
// `?raw` query.

type Json = Record<string, unknown>;
type JsonModule = { default: Json };

const EN_MODULES = import.meta.glob<JsonModule>("./en/*.json", { eager: true });
const SOURCE_MODULES = import.meta.glob("../**/*.{ts,tsx}", {
  eager: true,
  query: "?raw",
  import: "default",
}) as Record<string, string>;

// CLDR plural categories: i18next appends `_<category>` at lookup time, so a call site
// passes the BASE key (e.g. `t("catalog.import.summaryDocuments", { count })`), never the
// suffixed one — mirrors locales/parity.test.ts's own stripping.
const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;
const pluralBase = (key: string): string => key.replace(PLURAL_SUFFIX, "");

function flatten(obj: unknown, prefix = ""): string[] {
  if (obj !== null && typeof obj === "object") {
    return Object.entries(obj as Json).flatMap(([k, v]) =>
      flatten(v, prefix ? `${prefix}.${k}` : k),
    );
  }
  return [prefix];
}

const ALL_KEYS: string[] = [];
for (const [path, mod] of Object.entries(EN_MODULES)) {
  const match = /^\.\/en\/([^/]+)\.json$/.exec(path);
  if (!match) continue;
  const [, area] = match;
  for (const key of flatten(mod.default)) ALL_KEYS.push(`${area}.${key}`);
}

// Every non-test source file outside locales/ itself, concatenated into one haystack.
// Paths are relative to this file (src/locales/), so "../locales/" is this directory.
const SOURCE_TEXT = Object.entries(SOURCE_MODULES)
  .filter(([path]) => !path.includes(".test.") && !path.startsWith("../locales/"))
  .map(([, text]) => text)
  .join("\n");

function usedLiterally(key: string): boolean {
  return (
    SOURCE_TEXT.includes(`"${key}"`) ||
    SOURCE_TEXT.includes(`'${key}'`) ||
    SOURCE_TEXT.includes(`\`${key}\``)
  );
}

/**
 * Keys reached only through a runtime-built i18n key — one entry per call site, the
 * narrowest prefix that covers it. Add here only after confirming the call site actually
 * interpolates a value into that exact namespace; anything else is dead and should be
 * deleted (EN+PL) instead of allowlisted.
 */
const DYNAMIC_KEY_PREFIXES: ReadonlyArray<string> = [
  // t(`errors.message.${status}`) — utils/findingProps.tsx, components/SaveAnywayModal.tsx,
  // components/ReferenceCheckPanel.tsx, components/FindingPill.tsx, pages/Errors.tsx
  // (finding/status-code driven).
  "errors.message.",
  // t(`errors.class.${errorClass}`) — components/ErrorsSummaryStrip.tsx.
  "errors.class.",
  // t(`errors.status.${f.status}`) — pages/Errors.tsx.
  "errors.status.",
  // t(`common.table.${control}Page`) — components/PaginationBar.tsx (Mantine Pagination's
  // first/previous/next/last control names); listed one by one so the rest of
  // `common.table.*` stays guarded.
  "common.table.firstPage",
  "common.table.previousPage",
  "common.table.nextPage",
  "common.table.lastPage",
  // t(`common.feature.${feature}`) — pages/FeatureFlags.tsx, pages/UserFeatures.tsx
  // (the Feature enum).
  "common.feature.",
  // t(`common.featureHint.${f}`) — pages/UserFeatures.tsx.
  "common.featureHint.",
  // t(`catalog.field.${field}`) / t(`catalog.hint.${field}`) — components/CatalogFileFormFields.tsx
  // (kind-field driven).
  "catalog.field.",
  "catalog.hint.",
  // t("catalog.event.created", { context: event.params.origin }) resolves to
  // catalog.event.created_import — components/CatalogFileHistory.tsx.
  "catalog.event.created_",
  // t(`catalog.import.checkStatus.${status}`) / t(`catalog.import.status.${status}`) —
  // pages/ImportCatalogFiles.tsx (the OntologyImportStatus enum).
  "catalog.import.checkStatus.",
  "catalog.import.status.",
  // t(`ontology.import.kind.${row.kind}`) / checkStatus / status — components/OntologyImportResults.tsx.
  "ontology.import.kind.",
  "ontology.import.checkStatus.",
  "ontology.import.status.",
  // t(`entities.computed.kind.${definition.kind}`) — components/EntityComputedFieldset.tsx
  // (the ComputedPropertyKind enum).
  "entities.computed.kind.",
  // t(`entityGraph.legend.${key}`) — pages/EntityGraph.tsx (the status legend map).
  "entityGraph.legend.",
  // t(`render.relation.${family}`) / t(`render.legend.${key}`) — pages/RenderGraph.tsx.
  "render.relation.",
  "render.legend.",
];

function usedDynamically(key: string): boolean {
  return DYNAMIC_KEY_PREFIXES.some((prefix) => key.startsWith(prefix));
}

describe("locale keys are reachable from source (no dead i18n keys)", () => {
  it.each(ALL_KEYS)("%s", (key) => {
    const base = pluralBase(key);
    const reachable =
      usedLiterally(key) || usedLiterally(base) || usedDynamically(key) || usedDynamically(base);
    expect(reachable, `unused key: ${key} — remove it (EN+PL) or allowlist its dynamic prefix`).toBe(
      true,
    );
  });
});
