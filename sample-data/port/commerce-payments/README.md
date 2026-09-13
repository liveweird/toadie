# Commerce and payments: Port ontology

This standalone Port demo models a commerce and payments platform as two dependent sets:

- [`blueprints/`](blueprints/) contains eleven numbered blueprint definitions.
- [`entities/`](entities/) contains eleven numbered files with 59 entity instances.

The numbering is dependency order. Load all blueprint definitions before any entity instances.
The related Backstage demo uses the same broad business vocabulary, but neither sample depends on
the other.

The first two definitions extend the protected `_team` and `_user` system blueprints seeded by
V31. The remaining nine are ordinary sample blueprints. The model uses two named hierarchies in
parallel: `composition` for organisation and architecture containment, and `deployment` for
workload → cluster → environment placement. A relation may participate in both.

| # | Blueprint | Role | `composition` parent | `deployment` parent |
|---:|---|---|---|---|
| 01 | `_team` | organisation | `parent` | — |
| 02 | `_user` | people | — | — |
| 03 | `domain` | business domain | `parent_domain` | — |
| 04 | `system` | software system | `domain` | — |
| 05 | `environment` | runtime environment | — | — |
| 06 | `cluster` | runtime cluster | `environment` | `environment` |
| 07 | `resource` | data/infrastructure resource | `system` | — |
| 08 | `library` | shared component | `system` | — |
| 09 | `api` | API or event contract | `system` | — |
| 10 | `service` | service, site, job, or pipeline | `system` | — |
| 11 | `workload` | deployed service instance | `service` | `cluster` |

Every blueprint has instances, every declared property and relation is used at least once, and
every entity validates without findings. The entity set covers every value of the mirrored type
and lifecycle dictionaries, direct and inherited ownership, relation arrays, and an explicit
null relation.

## Load with the scripts

The scripts need `bash`, `curl`, and `jq`. They log in with the seed admin by default; override
`TOADIE_URL`, `TOADIE_EMAIL`, or `TOADIE_PASSWORD` when needed.

```bash
sample-data/port/commerce-payments/blueprints/load.sh
sample-data/port/commerce-payments/entities/load.sh
```

The blueprint loader ensures both `composition` and `deployment` exist in the `hierarchies`
dictionary. It then loads definitions in two passes: first without forward-targeting aggregation
properties, then PUTs the complete definitions after all targets exist. `_team` and `_user` are
extended; nine definitions are created. The entity loader creates all 59 instances in dependency
order.

Both loaders are safe to rerun. Existing ordinary blueprints and entities are reported as skipped;
the protected system blueprints are PUT again to the sample definition, and definitions with
aggregation properties receive their second-pass PUT.

## Load through the Port UI

The UI requires one dictionary step that the blueprint loader performs automatically:

1. Open **Port → Hierarchies** at `/hierarchies` and add `deployment`. Keep the seeded
   `composition` value.
2. Open **Port → Import** at `/ontology/import`, select all eleven `blueprints/*.json` files,
   and turn **Replace existing definitions** on. Check the batch, then import it. `_team` and
   `_user` report `UPDATED`; the other nine report `CREATED` on a fresh workspace.
3. On the same Port import page, select all eleven `entities/*.json` files. Check the batch, then
   import it. A fresh workspace reports 59 `CREATED` rows with no findings.

The replacement switch is required for the definition batch because `_team` and `_user` already
exist. Without it their sample extensions remain unapplied and the team/user entity documents
fail on their added fields. Reimporting unchanged definitions with replacement enabled updates
the matching definitions; reimporting unchanged entities with replacement disabled reports 59
`EXISTS` rows and stores nothing.

## What the entity files contain

| Blueprint | Count and examples |
|---|---|
| `_team` | 4: tribes and squads |
| `_user` | 2: `anna.kowalska`, `marek.nowak` |
| `domain` | 5, including `commerce`, `payments`, and `back-office` |
| `system` | 5, including `storefront`, `payments`, and `developer-portal` |
| `environment` | 4: production, staging, test, development |
| `cluster` | 4 Kubernetes clusters |
| `resource` | 8 databases, event stores, and caches |
| `library` | 2: `acme-commons`, `payments-sdk` |
| `api` | 6 API and event contracts |
| `service` | 10 services, sites, jobs, and pipelines |
| `workload` | 9 runtime deployments |

Five blueprints define eleven computed properties evaluated on reads:

| Blueprint | Computed properties |
|---|---|
| `_team` | aggregation `member_count` |
| `domain` | aggregation `critical_systems` |
| `system` | aggregations `service_count`, `workload_replicas`, `deploys_per_week` |
| `service` | mirror `domain_title`; calculations `stack`, `risk` |
| `workload` | mirrors `service_lifecycle`, `env_type`, `languages` |

Computed values are merged into response properties and are never accepted as write input.
`deploys_per_week` is time-dependent; unresolvable computed values are absent rather than
findings. Ownership is direct on domain, system, service, library, API, resource, and cluster;
workload ownership is inherited through its `service` relation.

## Remove the demo

Delete entity instances before definitions:

```bash
sample-data/port/commerce-payments/entities/load.sh --delete
sample-data/port/commerce-payments/blueprints/load.sh --delete
```

The entity loader deletes in reverse dependency order. The blueprint loader first removes
forward-targeting aggregations and then deletes the nine ordinary definitions in reverse order.
A reference from outside the sample causes a `409` and is reported rather than forced.

Cleanup deliberately keeps the protected `_team` and `_user` rows and keeps both hierarchy
dictionary entries. Because the loader extended the system blueprints, `--delete` does not restore
their fresh V31 definitions; rerunning the loader reapplies the sample extensions. Cleanup affects
only records described by this demo and preserves unrelated local data.
