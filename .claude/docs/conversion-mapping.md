### Conversion mappings (Backstage <-> Port, phases 1-2)

**The mapping mirrors the ontology as DATA — no code branches on ontology names.**

- **Applies when:** writing or changing any Kotlin or TypeScript that converts between a Port blueprint's
  entities and a Backstage kind's catalog files, or that validates, stores or displays the mapping that
  drives it.
- **Requirement:** no code branches on a blueprint id, kind name, label key, annotation key, tag
  category, or property or relation name. Which blueprint is which kind, and which property becomes
  which label, tag, annotation, link or spec field, is read from the stored mapping document. The only
  structural knowledge code may own is Backstage's own — which spec fields a kind allows or requires
  and what each holds — and it is read from `catalog/CatalogFileValidation.kt`
  (`allowedSpecFields`, `requiredSpecFields`, `SPEC_SHAPES`, `SPEC_TYPE`/`SPEC_LIFECYCLE`/
  `SPEC_PROFILE`), `catalog/EntitySpecAccess.kt` (the `EntitySpec` field accessors the export writes through) and
  `catalog/Errors.kt` (`REF_FIELD_ALLOWED_KINDS`), never copied. Engine-owned and
  not configurable: identifier <-> `metadata.name`, blueprint <-> kind (+ the pair's selector), the
  namespace default, `{"meta":"team"}` targeting the `_team` system blueprint, and computed
  (mirror/calculation/aggregation) properties never being mapped.
- **Reference:** this file; `conversion/MappingDocument.kt` (the model);
  `sample-data/port/commerce-payments/mapping/backstage.json` (the default document).
- **Enforcement:** manual review, plus `SampleBlueprintsTest` (the real default mapping validates with
  zero findings and zero drift through the API, so the contract lives in the data, not in test-side
  tables), `MappingValidationTest` and — for the export engine — `CodecsTest`/`BackstageExportTest`
  (every rule and codec is exercised over fixture worlds that name no real ontology), and
  `SampleConversionTest` (the real sample converts to the real sample).
- **Exception:** none.

Phase 1 ships the document model, the strict parser, the pure validator, the ADMIN registry (V45,
`/api/v1/conversion-mappings`), mapping rows on the Port Errors report, and the default mapping. Phase 2
adds the Port -> Backstage EXPORT engine and its read endpoint (below). There is NO import engine yet.
Code: `conversion/` (`.claude/docs/package-layout.md`).

**The document (`schemaVersion` 1)** — `{schemaVersion, apiVersion: "backstage.io/v1alpha1", pairs[]}`;
a pair is `{blueprint, kind, select?, rules[], lossy?}`; a blueprint appears in at most one pair.

- `select: {"spec": "type", "in": [..]}` — the pair applies to a Backstage file whose plain-text spec
  field is one of the values. Among the pairs of ONE kind, selector pairs are tried in order and the
  single selector-less pair is the fallback. `AMBIGUOUS_SELECTOR` also covers: two selector pairs whose
  `in` sets intersect; a selector-less pair that can export a value another pair selects; and a
  selector pair exporting a value outside its own `in` (import would route it elsewhere).
- A **rule** is `{port, backstage, codec, direction?, ...options}`. `direction` is `both` (default),
  `export` (Port -> Backstage only) or `import`; `derive` and `constant` are export-only, and so is a
  `{"meta":"team"}` rule on an Inherited-ownership blueprint (its team is computed, so importing one
  would be `TEAM_NOT_ALLOWED`). A blueprint with Direct or no ownership may map `team` both ways.
- **Port addresses**: `{"meta": "title"|"icon"|"team"}`, `{"property": id}`, `{"relation": id}`,
  `{"reverse": {"blueprint": id, "relation": id}}` (derive only — the entities of another blueprint
  whose relation points at THIS pair's blueprint).
- **Backstage addresses**: `{"metadata": "title"|"description"|"namespace"}`, `{"label": key}`,
  `{"annotation": key}`, `{"tags": category}`, `{"links": "rest"}` (every `metadata.links` entry no
  `link` rule claims), `{"link": {"title", "icon"?}}` (one entry, by title; titles are unique within a
  pair), `{"profile": "displayName"|"email"|"picture"}`, `{"spec": <EntitySpec field>}`.
- **Codecs** (a sealed `Codec`; a rule carries only its own codec's options, anything else is a
  `SCHEMA` finding): `copy` (a string or number property, or `title`/`icon`, onto a text field —
  `prefix` is prepended on export and stripped on import, `exportDefault` is written on export when the
  value is absent, `importIgnore` lists Backstage values import skips; an array-of-object property
  onto `links: rest`), `valueMap` (`map`; a bijection whose keys are exactly the property's enum
  values, or `"true"`/`"false"` for a boolean), `tagCategory` (an enum-backed property <-> the tags of
  the named category; a string-array property must be `uniqueItems`; several rules from one property
  name several categories and the property enum must equal their union), `ref` (a relation, or
  `{"meta":"team"}`, onto an entity-reference spec field; a many-valued relation needs a list field),
  `derive` (a reverse address onto a reference LIST field), `link` (a url-format string property <->
  one link), `constant` (`value`; no `port`). **`jq` is reserved** and refused ("not supported yet").
- **References are checked at both ends**: the far-end blueprint of a `ref`/`derive` rule (the
  relation's target, `_team` for the team field, the reverse blueprint) must itself be in a pair
  (`TARGET_UNMAPPED`), and that pair's kind must be one the spec field may reference per
  `REF_FIELD_ALLOWED_KINDS` (`REF_KIND_MISMATCH`: `spec.owner` takes a Group or User, `spec.system` a
  System, ...).
- **Several rules onto one Backstage address** are allowed: on export the first that yields a value
  wins (put an `exportDefault` rule last). The default mapping uses it for `spec.dependsOn`.
- `lossy` declares what a pair deliberately does NOT carry, on both sides: `properties`/`relations`
  (Port ids; a property or relation the blueprint REQUIRES cannot be lossy — an import could not fill
  it — and a listed id must not also be mapped) and `backstage` (`metadata.description`,
  `spec.dependencyOf`, `profile.picture`, ... — an extension of the architect's
  `{properties, relations}`, so a field is either mapped or consciously dropped on either side).
  Duplicate entries are a `SCHEMA` finding.

**Validation** (`validateMapping`, pure; findings are `{code, path, message}`, `path` a JSON pointer).
Blocking — a write with any is the `ConversionMappingInvalid` `400` listing all of them, and every
read recomputes them: `SCHEMA`, `UNKNOWN_BLUEPRINT`, `UNKNOWN_PROPERTY`, `UNKNOWN_RELATION`,
`COMPUTED_PROPERTY`, `UNKNOWN_KIND`, `FIELD_NOT_ALLOWED`, `AMBIGUOUS_SELECTOR`, `UNDECLARED_FIELD`
(a schema property/relation neither mapped nor lossy), `CODEC_TYPE_MISMATCH` (the codec does not fit
its Port or Backstage end or its direction, `valueMap` is not a bijection, a tag array is not
`uniqueItems`), `REQUIRED_UNMAPPED` (a required spec field of the kind that no export-writing rule or
constant writes, or a required Port property/relation declared lossy), `TARGET_UNMAPPED`,
`REF_KIND_MISMATCH`. Report-only **drift** (never blocks; on every read and on the Errors report):
`VOCABULARY_DRIFT` (a rule's value set, a tag category union, the lifecycles/namespaces dictionary, or —
per kind — the UNION of the `spec.type` values its pairs can produce, differs from the registry list
it mirrors; also a free-valued property copied onto a closed-value label), `REGISTRY_KEY_MISSING`
(also: a tag category with no tags — an empty category cannot be told from a missing one),
`REGISTRY_KIND_MISMATCH`, `BACKSTAGE_FIELD_UNMAPPED`. The validator is fed the active blueprints
(`BlueprintService.activeRows`) and one `CatalogRegistryReader.loadSnapshot()`.

**Wire and storage** — no `null` member anywhere: the parser refuses JSON `null` in a document, the
response DTOs have no nullable member, and the findings-bearing `400` is encoded with
`explicitNulls = false`. The document is stored as compact RE-SERIALISED JSON (whitespace dropped,
escapes normalised; the JSON reader cannot report duplicate keys, so a duplicated key collapses to its
last value) and round-trips byte-identically from the first save on. Endpoints, authorization order and
audit events: `.claude/docs/authorization.md`, `.claude/docs/observability.md`; storage:
`.claude/docs/persistence.md` "V45".

**Proof** — `SampleBlueprintsTest` stores the default mapping and requires zero findings AND zero
drift against the sample blueprints and the V22-seeded registries; `MappingValidationTest` pins one
case per code (`.claude/docs/testing.md`); the export engine is "The export (phase 2)" below. Later phases (the import
engine, SPA pages) are tracked in `BACKLOG.md`.

**The export (phase 2)** — `GET /api/v1/conversion-mappings/{id}/catalog-documents`, a pure read (no
write, no lock, no audit; any authenticated user). Its selection is the Entity graph's filter set
(`blueprint` any-of, `q`, `team`, `query`) plus `entityId` and `merge` (default true); the answer is
`{files, rows}` — `files` post to `POST /api/v1/files/import` verbatim (an `INVALID` row is never in
it), `rows` carry one `ConversionRow` per SELECTED entity (`CONVERTED`, `CONVERTED_WITH_FINDINGS`,
`SKIPPED`, `INVALID`; no `null` member on the wire). A mapping with blocking findings is a `409`, an
unknown one a `404`. The engine (`conversion/BackstageExport.kt`, the codecs' export halves in
`Codecs.kt`) is a pure function of (typed document, blueprints, one registry snapshot, an entity
snapshot, optionally the stored catalog files):

- **Identities first.** Every entity of a mapped blueprint — shown or not — gets
  `(pair kind, entity identifier, namespace)`; the namespace is what its `metadata.namespace` rule yields,
  else the workspace's flagged default namespace (the namespaces dictionary's DEFAULT entry, `default`
  when none is flagged). The exported document carries that namespace EXPLICITLY (never blank): a
  reference must name its target's namespace anyway, and a blank namespace would resolve to the flagged
  default at import time, which could disagree with the references written beside it.
- **References render from the TARGET's converted identity** (`kind:namespace/name`, the kind from the
  target's pair), never the referrer's namespace. Several `ref` rules onto one spec field concatenate in
  RULE order, then — within one rule — the stored array order of a many-valued relation (entity-id order for `derive`),
  without duplicates; a single-reference field keeps its first value.
  `{"meta":"team"}` takes the entity's EFFECTIVE team (Inherited ownership resolved): several teams keep
  the first and the row says `OWNER_TRUNCATED`. A relation to a deleted entity is `TARGET_MISSING`, to a
  blueprint with no pair `TARGET_UNMAPPED` (the validator blocks the latter, so only a hand-built mapping
  reaches it) — the reference is dropped, the row converts with findings.
- **Leaf semantics.** The first rule that yields a value for a text leaf wins; `copy` writes an
  `exportDefault` verbatim (never prefixed) when the value is absent or blank; `valueMap` of an unlisted
  value is `VALUE_UNMAPPED`; `tagCategory` makes a tag of each value the tag registry places in the rule's
  category (so several rules from one property split its values by category); `link` and `links: rest`
  append in rule order; `derive` reads the WHOLE snapshot, so a filter never drops a reverse edge. A
  reference-list field that any rule targets is written even when empty (Group `children`, User
  `memberOf` must be present).
- **Footprint merge** (`merge=true`): over the stored catalog file of the same `(kind, namespace, name)`
  the pair's OWN leaves are replaced and everything else survives — a label or annotation by key, tags by
  tag category, links by title (all links when a `links: rest` rule exists), a reference-list entry by the
  BLUEPRINT it resolves to (so a `template:` reference or a dangling one survives, while an entry
  pointing at a converted entity of a rule's target blueprint is re-derived), profile fields
  individually. The row names the merged file in `existingId`. A leaf the pair owns that Port no longer
  has is removed: Port is the source of truth for what the mapping covers. A stored reference to a Port
  entity that has since been DELETED resolves to nothing, so the merge keeps it (it is indistinguishable
  from a foreign reference); the Errors report is what flags it.
- **Validation is the catalog's own**: the built (and merged) document goes through
  `sanitizedCatalogFile` + `validateCatalogFile`; a refusal is an `INVALID` row (`DOCUMENT_INVALID`). The
  IDENTITY is checked first, over the WHOLE snapshot and whatever the filter: a `metadata.name` the catalog
  refuses (grammar, 63 characters), a namespace outside the namespaces dictionary (which the importer would
  refuse), or an identity several entities convert to (`IDENTITY_CONFLICT` — ALL of them, even when the
  filter selects only one) makes that entity's own row `INVALID`, and every reference to it, from any other
  document, is dropped with a `TARGET_INVALID` finding — one bad entity never makes its referrers invalid.
  A namespace rule's own findings (a `valueMap` miss) reach the row too.
- **`unmapped`** on a row lists the Port fields the entity carries that no export-writing rule reads
  (`properties.<id>`, `relations.<id>`, `team`) — for a valid mapping a subset of its `lossy` declarations (the ones the entity actually carries),
  which `SampleConversionTest` asserts.
- **Reads** go through the read ledger (`.claude/docs/security.md`) on ONE reservation held until the
  answer is encoded: the entity workspace through the Entity graph's `shownScope` + `loadReadSet`
  (`entities/EntityExportRead.kt`; the selected rows plus every entity of the mapped blueprints as context —
  a context row is decoded to its skeleton (relations and team) and charged as overflow, except for a blueprint
  whose namespace rule reads a property, whose rows are decoded in full, filtered to those properties and
  prepaid at the shown ratio — and a
  SKIPPED row's properties are never decoded), then, only when merging, the stored catalog files the export
  can write to (`CatalogFileService.readActiveSources`, narrowed to the emitted identities). The mapping and
  its blueprints/registries are read first, outside the ledger (a registry-sized read).

The Backstage sample is GENERATED by this engine from the Port sample — see
`sample-data/backstage/commerce-payments/README.md` "Regenerating this file" — and
`SampleConversionTest` pins the equality modulo three hand-restored edits that carry the four deliberate findings.
