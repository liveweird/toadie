# Backstage `catalog-info.yaml` descriptor format — offline reference

> **The authoritative source is <https://backstage.io/docs/features/software-catalog/descriptor-format/>**
> (well-known annotations: <https://backstage.io/docs/features/software-catalog/well-known-annotations/>).
> This file is a local snapshot (captured 2026-08-23) so Toadie work does not require browsing;
> re-check the site when implementing a new validation rule or when Backstage ships a new
> `apiVersion`. Toadie's three pillars all hang off this format: **visual creation** (the only
> allowed shapes below), **cross-checking** (the entity-reference + relation rules), and
> **combined rendering** (multi-document files + the relation graph).

## File conventions

- Recommended filename: `catalog-info.yaml`.
- A single file may contain **multiple YAML documents** separated by `---`; each document is one
  entity. (This is what makes "render several files/entities together" a first-class case.)
- The same envelope is used by descriptor YAML files and by the catalog API's JSON responses.

## Root envelope (every entity)

| Field | Required | Meaning |
| ----- | -------- | ------- |
| `apiVersion` | yes | `backstage.io/v<version><stability>` — core kinds use `backstage.io/v1alpha1` (Template: `backstage.io/v1beta2`). Custom kinds use their own prefix. |
| `kind` | yes | One of the core kinds below, or an org-defined custom kind. |
| `metadata` | yes | Common metadata object (next section). |
| `spec` | per kind | Kind-specific; interpretation depends on the `apiVersion`+`kind` pair. Location minimally requires `{}`. |

Server-populated, **never author-written**: `metadata.uid`, `metadata.etag`, `relations`,
`status`. A creation UI must not emit them; a validator should reject them in input files.

## `metadata` — common fields and validation rules

| Field | Required | Rule |
| ----- | -------- | ---- |
| `name` | yes | 1–63 chars of `[a-zA-Z0-9]` with `[-_.]` separators. Unique **case-insensitively** per kind within a namespace. Used in entity references and URLs. |
| `namespace` | no | Defaults to `default`. ≤63 chars, `[a-zA-Z0-9]` runs separated by `-`; case-insensitive, rendered lowercase. |
| `uid` | output only | Auto-generated, not stable across delete/re-register — never use as an external reference; use string entity refs. |
| `title` | no | Display-only alternative to `name`; no format restrictions. References always use `name`. |
| `description` | no | Brief human-readable purpose. |
| `labels` | no | K8s-style identifying key/value pairs. Key = optional lowercase domain prefix (≤253 chars) + `/` + name of `[a-zA-Z0-9]` with `[-_.]` separators (≤63 chars). Value follows the `name` rules. `backstage.io/` prefix is reserved. |
| `annotations` | no | K8s-style non-identifying key/value pairs. Key rules as labels; value is any string (any length). Used for external-system references — see the well-known list below. |
| `tags` | no | List of strings, each ≤63 chars of `[a-z0-9:+#]` with `-` separators (e.g. `java`, `c++`, `csharp:v2`). |
| `links` | no | List of `{url (required, standard URI), title?, icon? (semantic key, `[a-zA-Z0-9]` + `[-_.]`), type? (adopter-defined)}`. |

## Entity references

String form used by every `spec` relation field:

- Full: `<kind>:<namespace>/<name>` — e.g. `component:default/artist-web`.
- Kind + name: `<kind>:<name>` — namespace defaults **contextually**: inside a descriptor's
  spec field it is the *referencing entity's own* namespace (which is `default` unless the
  file sets one); only context-free lookups fall back to `default`.
- Bare `<name>` — the kind additionally defaults from the **context field** (each spec field
  below documents its default kind; e.g. `spec.owner` defaults to `Group`,
  `providesApis`/`consumesApis` to `API`, `subcomponentOf` to `Component`, `system` to
  `System`). `dependsOn`/`dependencyOf` have no sensible default — write the kind explicitly
  (`resource:orders-db`, `component:auth-svc`).
- Kind and namespace match case-insensitively.

Cross-checking = resolving each reference (with its contextual defaults applied) against the set
of known entities and flagging danglers, kind mismatches, and case-only duplicates.

## Kinds

All `backstage.io/v1alpha1` unless noted. "Relation" names the catalog-generated relation
(forward / reverse) — relations themselves are **read-only output**, derived from these spec
fields by the processors.

### Component — a unit of software (has source/artifacts)

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `type` | yes | Open string; well-known: `service`, `website`, `library`. |
| `lifecycle` | yes | Open string; well-known: `experimental`, `production`, `deprecated`. |
| `owner` | yes | Entity ref, default kind `Group`. → `ownedBy` / `ownerOf`. |
| `system` | no | Default kind `System`. → `partOf` / `hasPart`. |
| `subcomponentOf` | no | Default kind `Component`. → `partOf` / `hasPart`. |
| `providesApis` | no | Array, default kind `API`. → `providesApi` / `apiProvidedBy`. |
| `consumesApis` | no | Array, default kind `API`. → `consumesApi` / `apiConsumedBy`. |
| `dependsOn` | no | Array of Component/Resource refs. → `dependsOn` / `dependencyOf`. |
| `dependencyOf` | no | Array of Component/Resource refs (the inverse declaration). |

### API — an interface (OpenAPI/AsyncAPI/GraphQL/gRPC/…)

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `type` | yes | Well-known: `openapi`, `asyncapi`, `graphql`, `grpc`. |
| `lifecycle` | yes | As Component. |
| `owner` | yes | Default kind `Group`. → `ownedBy`. |
| `system` | no | Default kind `System`. → `partOf`. |
| `definition` | yes | The spec document itself as a string (per `type`); should carry its base URL inside (OpenAPI 3 `servers`, OpenAPI 2 `host`/`basePath`/`schemes`, AsyncAPI `servers`). Commonly filled via a substitution (below). |

### Group — an organizational unit

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `type` | yes | e.g. `team`, `business-unit`, `product-area`, `root`. |
| `profile` | no | `{displayName?, email?, picture?}`. |
| `parent` | no | Group ref. → `childOf` / `parentOf`. |
| `children` | yes | Array of Group refs — **may be empty, but must be present**. → `parentOf`. |
| `members` | no | Array of User refs. → `hasMember` / `memberOf`. |

### User — a person

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `profile` | no | `{displayName?, email?, picture?}`. |
| `memberOf` | yes | Array of Group refs — may be empty; memberships are **not** transitive. → `memberOf` / `hasMember`. |

### Resource — infrastructure a component needs

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `type` | yes | e.g. `database`, `s3-bucket`, `kubernetes-cluster`. |
| `owner` | yes | Default kind `Group`. → `ownedBy`. |
| `system` | no | Default kind `System`. → `partOf`. |
| `dependsOn` / `dependencyOf` | no | Arrays of Component/Resource refs, as Component. |

### System — a collection of Components/Resources behind an abstraction

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `owner` | yes | Default kind `Group`. → `ownedBy`. |
| `domain` | no | Default kind `Domain`. → `partOf` / `hasPart`. |
| `type` | no | e.g. `product`, `service`, `feature-set`. |

### Domain — a bounded context grouping Systems

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `owner` | yes | Default kind `Group`. → `ownedBy`. |
| `subdomainOf` | no | Domain ref. → `partOf` / `hasPart`. |
| `type` | no | e.g. `product-area`, `bundle`. |

### Location — a pointer to more catalog data

`apiVersion: backstage.io/v1alpha1`. `spec` may be `{}`.

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `type` | no | e.g. `url`, `file`; inherited from the originating location when omitted. |
| `target` | no | Single absolute URL/path or relative path. |
| `targets` | no | Array of the same; relative paths resolve against the Location entity's own location. |
| `presence` | no | `required` (default) or `optional`. |

### Template — scaffolder template (`apiVersion: backstage.io/v1beta2`)

| Spec field | Required | Notes |
| ---------- | -------- | ----- |
| `type` | yes | Type of component produced (aligns with Component `spec.type`, e.g. `website`). |
| `parameters` | yes | Array of parameter-group objects (JSON-Schema-shaped; see the Software Templates docs). |
| `steps` | yes | Array of scaffolder actions run in order. |
| `owner` | no | Default kind `Group`. → `ownedBy`. |

Optional `metadata.annotations."backstage.io/time-saved"`: ISO 8601 duration (e.g. `PT8H`).

## Substitutions (in descriptor files)

An object of the form `{ $text: <url> }`, `{ $json: <url> }`, or `{ $yaml: <url> }` anywhere in
the document is replaced at ingestion:

- `$text` — embed the referenced file verbatim as a string (the classic use: `spec.definition:
  { $text: ./openapi.yaml }` on an API entity).
- `$json` / `$yaml` — parse and embed as a structured value.
- URLs may be absolute or relative to the descriptor file's own location.
- Absolute URLs outside configured integrations need `backend.reading.allow` host allow-listing.

## Read-only output fields (API responses, never authored)

- `relations[]` — `{type, targetRef, metadata}`; the authoritative view of connections (prefer
  over raw spec fields when consuming). Forward/reverse pairs: `ownedBy`/`ownerOf`,
  `partOf`/`hasPart`, `providesApi`/`apiProvidedBy`, `consumesApi`/`apiConsumedBy`,
  `dependsOn`/`dependencyOf`, `parentOf`/`childOf`, `hasMember`/`memberOf`.
- `status.items[]` — `{type (domain-prefixed, e.g. backstage.io/catalog-processing), level
  (error|warning|info), message, error?{name,message,stack}}`.
- `metadata.uid`, `metadata.etag`.

## Well-known annotations (validation targets)

Core `backstage.io/` (server-written ones marked ⚙ — reject in authored files):

| Key | Value |
| --- | ----- |
| ⚙ `backstage.io/managed-by-location` | Location ref `<type>:<target>` — where the entity was fetched from. |
| ⚙ `backstage.io/managed-by-origin-location` | Location ref — the registration that created it. |
| ⚙ `backstage.io/orphan` | `"true"` when no registered location references it anymore. |
| `backstage.io/techdocs-ref` | Path/URL to TechDocs source (`dir:.` is the common value). |
| `backstage.io/techdocs-entity` | Entity ref owning the docs (docs shared from another entity). |
| `backstage.io/techdocs-entity-path` | Path to this entity's docs inside that owning entity. |
| `backstage.io/view-url` | URL of the canonical metadata YAML. |
| `backstage.io/edit-url` | URL to edit the source file. |
| `backstage.io/source-location` | Location ref to the entity's source code. |
| `backstage.io/source-template` | Template entity ref that scaffolded the entity. |
| `backstage.io/code-coverage` | `scm-only` or `enabled`. |
| `backstage.io/ldap-{rdn,uuid,dn}` | LDAP identifiers (org ingestion). |

Common integrations: `github.com/project-slug` (`org/repo`), `github.com/team-slug`,
`github.com/user-login`, `github.com/user-id`, `gitlab.com/user-id`,
`circleci.com/project-slug` (`scm/org/project`), `jenkins.io/job-full-name`,
`gocd.org/pipelines` (comma-separated), `sentry.io/project-slug`, `rollbar.com/project-slug`,
`sonarqube.org/project-key`, `periskop.io/service-name`, `vault.io/secrets-path`,
`graph.microsoft.com/{tenant-id,group-id,user-id}`.

Deprecated (a linter may warn): `backstage.io/github-actions-id` → `github.com/project-slug`;
`backstage.io/definition-at-location` → substitutions; `jenkins.io/github-folder` →
`jenkins.io/job-full-name`.

## Constraint quick table (for the validator)

| Element | Constraint |
| ------- | ---------- |
| `metadata.name` | 1–63 chars, `[a-zA-Z0-9]` + `[-_.]` separators; unique case-insensitively per kind+namespace |
| `metadata.namespace` | ≤63 chars, `[a-zA-Z0-9]` + `-`; lowercased |
| label/annotation key prefix | ≤253-char lowercase domain + `/` |
| label/annotation key name | ≤63 chars, `[a-zA-Z0-9]` + `[-_.]` |
| label value | as `name` rules |
| annotation value | any string |
| tag | ≤63 chars, `[a-z0-9:+#]` + `-` |
| link `url` | standard URI |
| link `icon` | `[a-zA-Z0-9]` + `[-_.]` |
| entity ref | `kind:namespace/name`, defaults per context field |
