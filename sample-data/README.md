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
