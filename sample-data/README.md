# Sample data

`catalog-info.yaml` is a single multi-document Backstage descriptor file holding a small but
complete sample landscape — **34 entities covering all seven kinds**, in both namespaces. It
exists so the app can be exercised by hand: the Files list and its filters, the per-kind
editor, the Errors report, the Graph, the Hierarchy view, Lenses, and the YAML round-trip.

| Kind | n | Names |
|---|---|---|
| Domain | 4 | `commerce`, `payments` (a sub-domain of `commerce`), `platform`, `shared-services` |
| System | 3 | `storefront`, `payments`, `acquirer` (ns `external`) |
| Component | 9 | `storefront-web`, `catalog-service`, `checkout-service`, `live-cart-gateway`, `payments-gateway`, `payments-sdk` (a sub-component), `ledger-worker`, `settlement-pipeline`, `legacy-invoicing` |
| API | 6 | `catalog-graphql`, `checkout-rest`, `live-cart`, `order-events`, `payments-grpc`, `acquirer-rest` (ns `external`) |
| Resource | 7 | `catalog-db`, `orders-db`, `payments-db`, `order-bus`, `ledger-log`, `settlement-warehouse`, `session-cache` |
| Group | 3 | `platform-tribe`, `retail-tribe`, `payments-squad` |
| User | 2 | `anna.kowalska`, `marek.nowak` |

32 entities live in `default` and 2 in `external` (a third-party card acquirer we depend on
but do not own), so the namespace filter and `GET /api/v1/files/export?namespace=…` have
something to separate.

## It speaks the seeded vocabulary

Every value in the file comes from the ADMIN-curated registries seeded by **V22** — so the
whole file passes a **strict** save, with no `allowInvalid` waiver, and nothing here has to
be invented. Between them the documents exercise:

- **every type value** in all six type dictionaries (`service`/`website`/`library`/`job`/`data-pipeline`, `openapi`/`asyncapi`/`graphql`/`grpc`/`web-sockets`, `product`/`capability`, `database`/`message-broker`/`transaction-log`/`analytical-database`/`cache`, `team`/`org-unit`/`org-division`) — bar one: the Domain type `auxiliary` goes unused, because there are only four Domains and two of them are genuinely `core-value`
- **all four lifecycles** — `experimental`, `production`, `sunsetting`, `deprecated`
- **both namespaces**, **all eight label keys**, **all four annotation keys**, and **all four tag categories** (13 of the 17 tags; the other four are alternatives within a category already covered, like `rabbitmq` next to `kafka`)

That makes the editor's registry-backed Type, Lifecycle, Label, Tag and Annotation pickers
checkable against real documents.

**Which registry applies to which kind** is the thing most easily got wrong when extending
this file:

| Registry | Applies to |
|---|---|
| tags | Component (Languages, Framework) and Resource (Database, Events) — **nothing else** |
| annotations | Component, API, System, Domain, Resource — **never Group or User** |
| lifecycle | Component and API only |
| labels | per key: `criticality-tier`/`support-mode` are System-only; `data-classification`/`gdpr`/`pci-dss` are Resource-only; `exposure`/`hosting-model` span Component/API/System/Resource; `technology-status` is Component/Resource |

## The deliberate problems

The landscape is *mostly* coherent. Four references are wrong on purpose, one per error
class, so the Errors report and the editor's findings panel show real results:

| Where | Reference | Reported as |
|---|---|---|
| `legacy-invoicing` → `spec.owner` | `group:default/billing-squad` | **MISSING** — no such Group |
| `legacy-invoicing` → `spec.dependsOn` | `resource:default/invoice-archive` | **MISSING** — no such Resource |
| `legacy-invoicing` → `spec.dependsOn` | `orders-db` | **KIND_REQUIRED** — no `kind:` prefix, which Backstage cannot ingest, even though the target does exist |
| `catalog-service` → `spec.dependsOn` | `template:default/nodejs-service-template` | **WRONG_KIND** — `dependsOn` accepts only Component or Resource, and `Template` is outside the seven kinds toadie stores. In the **Graph** the same reference draws an `EXTERNAL` node, so one entry shows both views of it |

One more shape worth looking at, which is *not* a finding because nothing is unresolved:
`api:default/order-events` is provided by `checkout-service` and consumed by nobody, so the
Graph shows a provider with a loose end.

**Expected report on a freshly loaded workspace:** 34 files, 86 references checked, and
**4 findings — plus 34 `SOURCE_MISSING`**. That last one is not a defect: `SOURCE_MISSING` is
report-only, raised for every file that carries no source reference, and importing pasted
YAML sets none. Filter it out with the error-class pills, or set a `sourceUrl` on one file in
the editor's Source fieldset to watch it disappear (and to unlock the Sync-from-repo modal).

The Graph comes out as **37 nodes** (34 stored + 1 external + 2 missing) and **85 edges**
across twelve relation fields.

## Loading it

**Through the UI** — sign in and go to **Import** (`/files/import`); pick the file or paste
its contents. All 34 documents should parse with zero errors. On an empty workspace 32 rows
come back `CREATED` and 2 `CREATED_WITH_FINDINGS` (`catalog-service` and `legacy-invoicing` —
import always waives soft findings). Re-importing later gives 34 `CONFLICT` rows and changes
nothing: import is report-and-skip. **Preview it first** with the dry run — the Import page's
check pass, or `POST /api/v1/files/import/check`, which classifies every row identically and
stores nothing.

**Through the API** — `POST /api/v1/files/import` takes *structured JSON*, not YAML (YAML
parsing is deliberately a client concern), so convert first:

```bash
TOKEN=$(curl -s -X POST localhost:8081/api/v1/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@toadie.local","password":"changeme"}' | jq -r .token)

ruby -ryaml -rjson -e 'files = YAML.load_stream(File.read("sample-data/catalog-info.yaml")).compact.map { |d|
    m = d["metadata"]; m["namespace"] ||= "default"
    { "kind" => d["kind"], "metadata" => m, "spec" => d["spec"] || {} }
  }; print({ "files" => files }.to_json)' \
| curl -s -X POST localhost:8081/api/v1/files/import \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data-binary @- \
| jq -c '[.results[].status] | group_by(.) | map({(.[0]): length}) | add'
```

`apiVersion` is dropped on the way in — toadie stores the descriptor, not the envelope — and
re-emitted by the YAML preview and by `GET …/export`, so the file round-trips.

## Seeing the other error classes

The file is strict-clean by design, so it never triggers the registry classes
(`LABEL_NOT_ALLOWED`, `TAG_NOT_ALLOWED`, `TYPE_NOT_ALLOWED`, `LIFECYCLE_NOT_ALLOWED`,
`ANNOTATION_NOT_ALLOWED`) or `SELF_REFERENCE`. To see one, open any file in the editor and
either point a self-referencing field at the file itself, or — since the pickers only offer
registered values — remove the value from its registry page afterwards. The strict save is
refused with the finding; **Save anyway** stores it and it then shows up in the report.

## Editing it

The SPA's import parser (`web/src/utils/catalogImport.ts`) is **strict**: any key outside its
allow-list marks that document invalid rather than silently dropping the key. Stay inside the
per-kind rules in `server/src/main/kotlin/catalog/CatalogFileValidation.kt` — in particular
`Group.spec.children` and `User.spec.memberOf` must be **present** (an empty list is fine), and
an optional scalar must be omitted rather than set to `""`.

Keep the `gdpr` / `pci-dss` label values **quoted** (`"yes"` / `"no"`). Unquoted, they are
booleans under YAML 1.1 — which is what Ruby's parser does, so the conversion snippet above
turns `no` into `false` and the document is rejected for a non-string label value. The SPA's
parser (the `yaml` package, YAML 1.2 core schema) reads them as plain strings instead, so
unquoted values would import through the UI and fail through the CLI — quoting sidesteps the
whole difference.

## Clearing it

The catalog is soft-deleted like everything else — delete the files from the Files list, or
`docker compose down -v` to drop the volume and come back to a database seeded with just the
registries.

## Blueprints (Port)

`blueprints/` is a second, independent sample set for the **Blueprints** feature (v1.23.0 —
Toadie's first step toward Port.io's data model, `.claude/docs/port-data-model.md`): eight
Port-native blueprint JSON documents, one `POST /api/v1/blueprints` body each, numbered in
**dependency order** — a relation or aggregation `target` must already exist (or be the
blueprint itself), so `service.depends_on` can self-reference but `workload.service` needs
`service` to have loaded first. There is no import UI for blueprints (unlike the catalog
above), so this ships its own loader.

| # | identifier | What it showcases |
|---|---|---|
| 01 | `team` | the stand-in for Port's `_team`: url/user/email string formats, an array of user with `uniqueItems`/`minItems`, an enum with colours, an object with `properties`/`additionalProperties` |
| 02 | `domain` | a relation to `team`; `pattern`+`minLength`/`maxLength`; markdown; Direct ownership with a title |
| 03 | `environment` | Port's default: yaml, ipv4/ipv6, a boolean default, three more enum colours (the last three land on `service.language`), `date_format: 24-hour` |
| 04 | `service` | the showcase: all three `spec` values (`open-api`/`async-api`/`embedded-url` with `specAuthentication`), `labeled-url`, proto/timer/idn-email, every number bound, a `default` of every type, relations single/many/required/self (`depends_on` → itself), mirror properties including `domain.$title`, colorized string + boolean calculation properties, Direct ownership |
| 05 | `workload` | Port's default: two required relations, `patternProperties`, a nested mirror path `service.domain.$title`, Inherited ownership |
| 06 | `deployment` | `exclusiveMinimum`, a self-target `entities/count` aggregation (`sibling_deploys`) |
| 07 | `incident` | the `team` string format, severity/status enums with colours, Inherited ownership |
| 08 | `organization` | Port's default, the aggregation showcase: nine aggregations spanning `entities`/`property` calculation, every `func` (count/average/sum/min/max/median), a `query`, and a `pathFilter` |

Across the set: all 5 property types, all 12 string formats, `labeled-url`, all 3 `spec`s, all
14 enum colours, every validation field (`pattern`/min·max length/items/numeric bounds),
every `date_format` variant, every relation shape, both ownership modes, and every
aggregation `func` — pinned by `SampleBlueprintsTest` (`.claude/docs/testing.md`).

**Loading it** — `sample-data/blueprints/load.sh` (bash, needs `curl` + `jq`): logs in as the
seed admin (override with `TOADIE_URL`/`TOADIE_EMAIL`/`TOADIE_PASSWORD`) and `POST`s each file
in order, printing `created <identifier>` (`201`) or `exists, skipped: <identifier>` (`409` —
re-runnable) per file, and exiting `1` on any other status with the problem detail. The token
is never printed.

```bash
sample-data/blueprints/load.sh
```

**Clearing it** — `sample-data/blueprints/load.sh --delete` removes the set in REVERSE
dependency order (so a target is never deleted while an earlier blueprint still relates or
aggregates to it); a `409` means something outside the sample set still targets it, and is
reported rather than forced.

```bash
sample-data/blueprints/load.sh --delete
```

Like the catalog file above, the blueprint registry is deliberately **NOT seeded** — no
migration inserts a blueprint, so a fresh environment's `/blueprints` page starts empty.

## Entities (Port)

`entities/` is a third sample set, for the **Entities** feature (v1.24.0 — Port migration
phase 2, `.claude/docs/port-data-model.md`'s "Entities" section): eight numbered JSON files,
one per sample blueprint, each a JSON ARRAY of `POST /api/v1/entities` bodies — 32 entities in
all. Numbered in the same **dependency order** as the blueprints above (`01-team` →
`02-domain` → `03-environment` → `04-service` → `05-workload` → `06-deployment` →
`07-incident` → `08-organization`), because a relation value must already exist: `service`'s
self-relation `depends_on` needs earlier services in its own file, `workload.service` needs
`04-service.json` to have loaded first, and so on. **Load the blueprint set first** — an
entity names its blueprint, and the registry must already hold it.

| # | blueprint | Entities | What it showcases |
|---|---|---|---|
| 01 | `team` | `platform`, `payments`, `search`, `growth` | every `team` property, all 4 `timezone` colours |
| 02 | `domain` | `commerce`, `payments`, `platform-ops`, `growth` | all 4 `criticality` values, a relation sent as explicit JSON `null` (`growth.owned_by`), the entity-level `team` field as both a string (`commerce`) and an array (`payments`) |
| 03 | `environment` | `prod`, `staging`, `dev` | all 3 `type`/`region` values, ipv4/ipv6 literals, yaml text |
| 04 | `service` | `catalog-service`, `pricing-service`, `checkout-service`, `search-service`, `notification-service` | every property (all 5 `language` values, all 4 `tier` values, every string format incl. `labeled-url`/`proto`/`timer`/`idn-email`), a self-relation many-array (`checkout-service.depends_on`) |
| 05 | `workload` | 6 workloads across the 5 services and 3 environments | all 3 `health` values |
| 06 | `deployment` | 5 deployments | all 4 `status` values |
| 07 | `incident` | 4 incidents | all 4 `severity` and all 3 `status` values |
| 08 | `organization` | `toadie-sample` | many relations to every domain and every team |

Across the set: every property of every sample blueprint is set at least once with a value
valid for its type/format, every enum value of every enum property is used at least once, the
entity-level `team` field appears both as a string and as an array (and absent elsewhere),
and at least one relation is a multi-element array. Pinned by `SampleEntitiesTest`
(`.claude/docs/testing.md`), which also loads the blueprint set and asserts the file
numbering is itself dependency-safe.

**Loading it** — `sample-data/entities/load.sh` (needs `curl` + `jq`, same login idiom as the
blueprints loader): logs in and `POST`s each entity in file order, printing
`created <blueprint>/<identifier>` (`201`) or `exists, skipped: <blueprint>/<identifier>`
(`409` — re-runnable) per entity, exiting `1` on any other status.

```bash
sample-data/blueprints/load.sh   # first, if not already loaded
sample-data/entities/load.sh
```

**Clearing it** — `sample-data/entities/load.sh --delete` removes the set in REVERSE
dependency order (files highest-numbered first, entities within a file in reverse creation
order), resolving each entity's id via an exact-identifier list lookup; a `409` means
something outside the sample set still relates to it, and is reported rather than forced.

```bash
sample-data/entities/load.sh --delete
```

Like the blueprint registry, entities are deliberately **NOT seeded**.
