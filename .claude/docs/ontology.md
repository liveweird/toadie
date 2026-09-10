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
| A organisation | `team` | Group (type `team`/`org-unit`/`org-division`) | `parent` → team |
| | `user` | User | none (`member_of` is many) |
| | `domain` | Domain | `parent_domain` → domain |
| | `system` | System | `domain` → domain |
| B software | `service` | Component (type `service`/`website`/`job`/`data-pipeline`) | `system` → system |
| | `library` | Component (type `library`) | `system` → system |
| | `api` | API (type incl. `asyncapi` = a Kafka topic) | `system` → system |
| | `resource` | Resource | `system` → system |
| C runtime | `environment` | — Port default, dropped on export | none |
| | `cluster` | — Port-only (Resource `kubernetes-cluster` if ever exported) | `environment` → environment |
| | `workload` | — Port's "running service", dropped on export | `service` → service |

Load order = file order (a relation target must exist): team, user, domain, system, environment,
cluster, resource, library, api, service, workload. There is deliberately NO `api.provided_by`:
it would make `api` ↔ `service` a cycle no load order satisfies, and Backstage's own direction is
`providesApis` on the component. The Entity hierarchy page shows two trees — the org tree (teams)
and the architecture tree (domain → system → service/library/api/resource → workload, with
cluster → environment beside it); an orphan (a service without a system) surfaces as a root,
which is the intended nudge rather than an error — `system` is optional everywhere Backstage
makes `spec.system` optional.

**Conventions.** Property names `snake_case`. Ownership is the explicit relation `owned_by →
team` (`many: false`), `required` exactly where Backstage requires `spec.owner` (Domain, System,
Component, API, Resource — so `domain`, `system`, `service`, `library`, `api`, `resource`);
`workload` uses `ownership: {type: Inherited, path: service}`; Port's hidden `$team` arrives with
the phase that models `_team`. Required relations are always single (Port forbids `required` +
`many`). Every blueprint carries `links` (`array` of `object`, ↔ `metadata.links`) plus named
link properties where the link has one meaning (`repository`, `docs`, `ci_pipeline`, `dashboard`,
`logs`, `runbook`). `external: boolean` on `system`/`api` preserves the `external` namespace
(third parties). No mirror/calculation/aggregation properties — Toadie stores but never evaluates
them (candidates: `workload.languages ← service.languages`, `workload.env_type ←
environment.type`, `system.service_count`, `team.services_owned`, `domain.critical_systems`).
No secrets, connection strings or hostnames as properties, ever.

**Backstage export mapping (the round-trip contract).**

- Meta: `identifier` → `metadata.name`; `title`/`description` → `metadata.title`/`description`;
  `links` → `metadata.links`; `external: true` → namespace `external`, else `default`.
- Kind + `spec.type`: per the table; `service.type` is the Component type, `library` → `library`.
- Labels ← `criticality` (`criticality-tier`), `support_mode`, `exposure`, `hosting_model`,
  `technology_status`, `data_classification`, and the booleans `gdpr`/`pci_dss` (→ `yes`/`no`).
- Tags ← `languages` ∪ `frameworks` (Component), `engine` (Resource: Database ∪ Events).
- Annotations ← `repository` (`backstage.io/source-location`), `docs` (`techdocs-ref`),
  `kubernetes_id`, `kubernetes_label_selector`.
- Relations: `owned_by` → `spec.owner`; `system`/`domain`/`parent`/`parent_domain`/
  `subcomponent_of` → their fields; `depends_on` ∪ `uses` ∪ `uses_libraries` → `dependsOn`;
  `provides_apis` → `providesApis`, `consumes_apis` → `consumesApis`; `member_of` → `memberOf`; Group `children`/`members`
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
