### The baseline ontology (Port blueprints)

**`sample-data/blueprints/` is the eleven-blueprint model the platform catalog is built on**
(the baseline ontology, v1.25.2; the sample set itself since v1.25.3, when it replaced the
feature-showcase set), and `sample-data/entities/` is the same landscape as
`sample-data/catalog-info.yaml` re-told as its entities — the two samples tell one story, which
is what the Backstage round trip below is about.
The design decisions, taken 2026-09-10: `service` + `library` rather than one `component` or a
five-way split (a Port relation targets ONE blueprint, so every extra blueprint multiplies the
dependency relations); the full runtime layer `environment` + `cluster` + `workload`; Kafka topics
are `api` of type `asyncapi`; a minimal `user`. `SampleBlueprintsTest` pins everything below that is
mechanical, and `SampleEntitiesTest` proves the entity sample saves with zero findings (`.claude/docs/testing.md`).

**Four constraints the set satisfies.** (1) Every blueprint maps to one of the seven Backstage
kinds or is a documented Port-only extra an export drops — the round trip is the contract below.
(2) Every enum that mirrors a V22-seeded registry (per-kind types, lifecycles, the labels' closed
value lists, the tag categories) carries the registry's values **verbatim**, so export = copy;
extending a vocabulary means editing BOTH the blueprint enum and the registry (the test fails
when one forgets the other). (3) It covers the platform it describes: on-premise + virtualised
Kubernetes, Java services, sync APIs and Kafka, SPAs and server-rendered UIs, jobs and pipelines,
PostgreSQL/ClickHouse/Redis, few environments, team ownership, external links. (4) It is Port's own
default shape (service / environment / workload / `_team` / `_user`) with the usual layering.

| Layer | Blueprint | Backstage kind | `hierarchyRelation` (parent) |
|---|---|---|---|
| A organisation | `_team` | Group (type `team`/`org-unit`/`org-division`), Port system blueprint | `parent` → team |
| | `_user` | User, Port system blueprint | none (team relation, many) |
| | `domain` | Domain | `parent_domain` → domain |
| | `system` | System | `domain` → domain |
| B software | `service` | Component (type `service`/`website`/`job`/`data-pipeline`) | `system` → system |
| | `library` | Component (type `library`) | `system` → system |
| | `api` | API (type incl. `asyncapi` = a Kafka topic) | `system` → system |
| | `resource` | Resource | `system` → system |
| C runtime | `environment` | — Port default, dropped on export | none |
| | `cluster` | — Port-only (Resource `kubernetes-cluster` if ever exported) | `environment` → environment |
| | `workload` | — Port's "running service", dropped on export | `service` → service |

Load order = file order (a relation target must exist): _team, _user, domain, system, environment,
cluster, resource, library, api, service, workload. There is deliberately NO `api.provided_by`:
it would make `api` ↔ `service` a cycle no load order satisfies, and Backstage's own direction is
`providesApis` on the component. The Entity hierarchy page shows two trees — the org tree (teams)
and the architecture tree (domain → system → service/library/api/resource → workload, with
cluster → environment beside it); an orphan (a service without a system) surfaces as a root,
which is the intended nudge rather than an error — `system` is optional everywhere Backstage
makes `spec.system` optional.

**Conventions.** Property names `snake_case`. Ownership is Port's `$team` (arrived with Phase 4,
v1.26.0, V31): each blueprint declares `ownership: {type: Direct}` (domain, system, service,
library, api, resource, cluster) or `ownership: {type: Inherited, path: service}` (workload). On entities, the top-level `team`
field IS the ownership — for Direct blueprints it must name an active `_team` entity (string or
array; validated on write, `TEAM_TARGET_MISSING` finding on read; renaming a `_team` cascades,
deleting a referenced one is `409`), for Inherited blueprints it is read-only and computed from
the `ownership.path`. `_user.team` (many, optional) is membership, a relation to `_team` entities.
Required relations are always single (Port forbids `required` + `many`). Every blueprint carries
`links` (`array` of `object`, ↔ `metadata.links`) plus named link properties where the link has
one meaning (`repository`, `docs`, `ci_pipeline`, `dashboard`, `logs`, `runbook`). `external:
boolean` on `system`/`api` preserves the `external` namespace (third parties). No secrets,
connection strings or hostnames as properties, ever.

**Computed properties (phase 5, v1.27.0).** Nine mirror/calculation/aggregation properties
across five blueprints, evaluated at entity read time (`.claude/docs/port-data-model.md`
"Computed properties"); `SampleEntitiesTest` derives every expected value from the sample
entity files rather than hardcoding them.

| Blueprint | id | kind | definition |
|---|---|---|---|
| `_team` | `member_count` | aggregation | target `_user`, `entities/count` — direct, via `_user`'s own `team` relation |
| `domain` | `critical_systems` | aggregation | target `system`, `entities/count`, query `criticality = critical` — direct, via `system.domain` |
| `system` | `service_count` | aggregation | target `service`, `entities/count` — direct, via `service.system` |
| `system` | `workload_replicas` | aggregation | target `workload`, `property/sum` of `replicas`, `pathFilter [{fromBlueprint: workload, path: [service, system]}]` (reverse: workload → service → system) |
| `system` | `deploys_per_week` | aggregation | target `workload`, `entities/average` per `week`, `measureTimeBy: last_deployed`, the same reverse `pathFilter` — time-dependent (**assumption**, see `port-data-model.md`) |
| `service` | `domain_title` | mirror | `system.domain.$title` |
| `service` | `stack` | calculation | `((.properties.languages // []) + (.properties.frameworks // [])) \| join(", ")` |
| `service` | `risk` | calculation, colorized | `technology_status`/`lifecycle` black-list/deprecated → `high` (red), grey-zone/sunsetting → `medium` (yellow), else `low` (green) |
| `workload` | `service_lifecycle`, `env_type`, `languages` | mirror | `service.lifecycle`, `environment.type`, `service.languages` |

An unresolvable value (a dangling relation, a jq failure, a type mismatch) is simply ABSENT —
never a finding, never a save blocker; `01-team.json`'s `member_count` is the one SYSTEM
blueprint extension (`_team` may add aggregation properties like any other field). Because an
aggregation's `target` must already be an ACTIVE blueprint, `domain.critical_systems` and
`system`'s three aggregations name a blueprint that loads LATER in the numbered set — the
loader (`sample-data/blueprints/load.sh`, `SampleData.loadBlueprints`) applies these in TWO
passes: every file first, with `aggregationProperties` stripped, then a second pass PUTs the
full file back onto every blueprint that declares one, once every target exists.

**Backstage export mapping (the round-trip contract).**

- Meta: `identifier` → `metadata.name`; `title`/`description` → `metadata.title`/`description`;
  `links` → `metadata.links`; `external: true` → namespace `external`, else `default`.
- Kind + `spec.type`: per the table; `service.type` is the Component type, `library` → `library`.
- Labels ← `criticality` (`criticality-tier`), `support_mode`, `exposure`, `hosting_model`,
  `technology_status`, `data_classification`, and the booleans `gdpr`/`pci_dss` (→ `yes`/`no`).
- Tags ← `languages` ∪ `frameworks` (Component), `engine` (Resource: Database ∪ Events).
- Annotations ← `repository` (`backstage.io/source-location`), `docs` (`techdocs-ref`),
  `kubernetes_id`, `kubernetes_label_selector`.
- Ownership: entity `team` field (string for single, array for multiple) → `spec.owner`; read-only
  on Inherited blueprints, computed from the ownership path.
- Relations: `system`/`domain`/`parent`/`parent_domain`/`subcomponent_of` → their fields;
  `depends_on` ∪ `uses` ∪ `uses_libraries` → `dependsOn`; `provides_apis` → `providesApis`,
  `consumes_apis` → `consumesApis`; `_user.team` → `memberOf`; Group `children`/`members`
  and API providers are derived from the reverse side; `api.definition_url` → `definition:
  {$text: <url>}`, `api.definition` → the inline text.
- Lossy (Port-only): `environment`, `cluster`, `workload`, `service.schedule`, `library.artifact`,
  `resource.hosted_on`. No registry change is needed for the baseline; `kubernetes-cluster` joins
  the Resource types only if clusters are ever exported.

**Deliberately out.** `deployment` and `incident` (Port defaults) — event-like records that only
make sense fed by CI/CD or the incident tool; add them with the first integration. An
`organization` root (single company; the top-level domains and teams are the roots). Per-environment
resource instances (a `resource` is the logical store, `orders-db`, one entity across environments),
VM/hypervisor/host inventory (a CMDB, not an architecture map — `cluster.virtualization` +
`hosting_model` + `datacenter` say what the map needs), scorecards (unsupported), secrets.
