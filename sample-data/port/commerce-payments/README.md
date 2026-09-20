# Commerce and payments: Port ontology

This standalone Port demo models a commerce and payments platform as two dependent sets:

- [`blueprints/`](blueprints/) contains fifteen numbered blueprint definitions.
- [`entities/`](entities/) contains fifteen numbered files with 76 entity instances.

The numbering is dependency order. Load all blueprint definitions before any entity instances.
The related Backstage demo uses the same broad business vocabulary, but neither sample depends on
the other.

The first two definitions extend the protected `_team` and `_user` system blueprints seeded by
V31. The remaining thirteen are ordinary sample blueprints. The model uses two named hierarchies
in parallel: `composition` for organisation and architecture containment, and `deployment` for
workload → cluster → environment placement. A relation may participate in both.

| # | Blueprint | Role | `composition` parent | `deployment` parent |
|---:|---|---|---|---|
| 01 | `_team` | organisation | `parent` | — |
| 02 | `_user` | people | — | — |
| 03 | `domain` | business domain | `parent_domain` | — |
| 04 | `product` | commercial offering | `parent_product` | — |
| 05 | `system` | software system | `domain` | — |
| 06 | `environment` | runtime environment | — | — |
| 07 | `cluster` | runtime cluster | `environment` | `environment` |
| 08 | `resource` | data/infrastructure resource | `system` | — |
| 09 | `dataset` | data contract surface | `system` | — |
| 10 | `library` | shared component | `system` | — |
| 11 | `api` | API or event contract | `system` | — |
| 12 | `service` | service, site, job, or pipeline | `system` | — |
| 13 | `workload` | deployed service instance | `service` | `cluster` |
| 14 | `api_adoption` | declared API usage | `consumer` | — |
| 15 | `dataset_adoption` | declared dataset usage | `consumer` | — |

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
extended; thirteen definitions are created. The entity loader creates all 76 instances in
dependency order.

Both loaders are safe to rerun. Existing ordinary blueprints and entities are reported as skipped;
the protected system blueprints are PUT again to the sample definition, and definitions with
aggregation properties receive their second-pass PUT.

## Source references

By default both loaders stamp every blueprint and entity they create or extend with the public
raw-GitHub address of its own sample file: `sourceUrl = $TOADIE_SOURCE_BASE/blueprints/<file>` or
`$TOADIE_SOURCE_BASE/entities/<file>` (an entity's `sourceUrl` names the ARRAY file it came from,
since the sync picker matches an entity by identifier inside one). A freshly loaded workspace
therefore shows a clean Port Errors report, and **Sync from source** — in the Blueprints and
Entities row menus and editors — works right away, provided the app has outbound https access to
`raw.githubusercontent.com`. Set `TOADIE_SOURCE_BASE` to point the loaders at a fork or branch's
own checkout, or to an empty string to load completely without source references. With sources, every row
starts "Never synced" until its first Sync from source; without them, every row reads "No source"
and the Port Errors report lists 91 gray `SOURCE_MISSING` findings — one per blueprint and
entity — until sources are set by hand. `--delete` is unaffected either way: it
resolves rows by their stored identifiers, not by their source references.

## Load through the Port UI

The UI requires one dictionary step that the blueprint loader performs automatically:

1. Open **Port → Hierarchies** at `/hierarchies` and add `deployment`. Keep the seeded
   `composition` value.
2. Open **Port → Import** at `/ontology/import`, select all fifteen `blueprints/*.json` files,
   and turn **Replace existing definitions** on. Check the batch, then import it. `_team` and
   `_user` report `UPDATED`; the other thirteen report `CREATED` on a fresh workspace.
3. On the same Port import page, select all fifteen `entities/*.json` files. Check the batch, then
   import it. A fresh workspace reports 76 `CREATED` rows with no findings.

The replacement switch is required for the definition batch because `_team` and `_user` already
exist. Without it their sample extensions remain unapplied and the team/user entity documents
fail on their added fields. Reimporting unchanged definitions with replacement enabled updates
the matching definitions; reimporting unchanged entities with replacement disabled reports 76
`EXISTS` rows and stores nothing.

## What the entity files contain

| Blueprint | Count and examples |
|---|---|
| `_team` | 4: tribes and squads |
| `_user` | 2: `anna.kowalska`, `marek.nowak` |
| `domain` | 5, including `commerce`, `payments`, and `back-office` |
| `product` | 5: `commerce-suite`, `shop`, `pay`, `invoicing`, `dev-portal` |
| `system` | 5, including `storefront`, `payments`, and `developer-portal`, each delivering one or more products |
| `environment` | 4: production, staging, test, development |
| `cluster` | 4 Kubernetes clusters |
| `resource` | 8 databases, event stores, and caches |
| `dataset` | 5: `product-catalog`, `orders`, `payments-ledger`, `settlements-daily`, `catalog-search-index` |
| `library` | 2: `acme-commons`, `payments-sdk` |
| `api` | 6 API and event contracts |
| `service` | 10 services, sites, jobs, and pipelines |
| `workload` | 9 runtime deployments |
| `api_adoption` | 4 declared API consumptions, one major-line-less |
| `dataset_adoption` | 3 declared dataset consumptions, one contract-version-less |

Ten blueprints define twenty computed properties evaluated on reads:

| Blueprint | Computed properties |
|---|---|
| `_team` | aggregation `member_count` |
| `domain` | aggregation `critical_systems` |
| `product` | aggregations `system_count`, `critical_systems`, `service_count` |
| `system` | aggregations `service_count`, `workload_replicas`, `deploys_per_week` |
| `dataset` | aggregations `producer_count`, `consumer_count`, `adoption_count` |
| `api` | aggregation `adoption_count` |
| `service` | mirror `domain_title`; calculations `stack`, `risk` |
| `workload` | mirrors `service_lifecycle`, `env_type`, `languages` |
| `api_adoption` | mirror `api_lifecycle` |
| `dataset_adoption` | mirror `dataset_lifecycle` |

Computed values are merged into response properties and are never accepted as write input.
`deploys_per_week` is time-dependent; unresolvable computed values are absent rather than
findings. Ownership is direct on domain, product, system, service, library, API, resource,
cluster, and dataset; workload ownership is inherited through its `service` relation, and
`api_adoption`/`dataset_adoption` ownership is inherited through their `consumer` relation.

## Remove the demo

Delete entity instances before definitions:

```bash
sample-data/port/commerce-payments/entities/load.sh --delete
sample-data/port/commerce-payments/blueprints/load.sh --delete
```

The entity loader deletes in reverse dependency order. The blueprint loader first removes
forward-targeting aggregations and then deletes the thirteen ordinary definitions in reverse
order. A reference from outside the sample causes a `409` and is reported rather than forced.

Cleanup deliberately keeps the protected `_team` and `_user` rows and keeps both hierarchy
dictionary entries. Because the loader extended the system blueprints, `--delete` does not restore
their fresh V31 definitions; rerunning the loader reapplies the sample extensions. Cleanup affects
only records described by this demo and preserves unrelated local data.
