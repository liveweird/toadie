# Commerce and payments: Backstage software catalog

[`catalog-info.yaml`](catalog-info.yaml) is a 34-document Backstage catalog covering all seven
kinds. It exercises the Files list, per-kind editor, Errors report, Graph, Hierarchy, Lenses, and
YAML import/export. This is a standalone demo; the Port sample tells a related story but is not a
prerequisite.

| Kind | Count | Examples |
|---|---:|---|
| Domain | 4 | `commerce`, `payments`, `platform`, `shared-services` |
| System | 3 | `storefront`, `payments`, `external/acquirer` |
| Component | 9 | `storefront-web`, `checkout-service`, `payments-gateway` |
| API | 6 | `catalog-graphql`, `order-events`, `payments-grpc` |
| Resource | 7 | `orders-db`, `order-bus`, `settlement-warehouse` |
| Group | 3 | `platform-tribe`, `retail-tribe`, `payments-squad` |
| User | 2 | `anna.kowalska`, `marek.nowak` |

Thirty-two documents use the `default` namespace and two use `external`. Every structural shape,
namespace, and registry-backed value is valid against the seeded vocabulary. The file uses all
seeded type values except Domain `auxiliary`, all four lifecycles, both namespaces, all eight
label keys, all four annotation keys, and all four tag categories.

The catalog is intentionally not reference-clean. Four soft reference findings in two documents
exercise the Errors report and import waiver:

| Document and field | Reference | Finding |
|---|---|---|
| `legacy-invoicing` owner | `group:default/billing-squad` | `MISSING` |
| `legacy-invoicing` dependency | `resource:default/invoice-archive` | `MISSING` |
| `legacy-invoicing` dependency | `orders-db` | `KIND_REQUIRED` |
| `catalog-service` dependency | `template:default/nodejs-service-template` | `WRONG_KIND` |

On an empty workspace, import reports 32 `CREATED` and 2 `CREATED_WITH_FINDINGS` rows. The stored
catalog then reports those four reference findings plus 34 report-only `SOURCE_MISSING` findings,
one for each document without a `sourceUrl`. `SOURCE_MISSING` is expected for a pasted or uploaded
local sample. The graph contains 34 stored nodes, two missing-reference nodes, and 84 edges.

## Load through the Backstage UI

Start Toadie, sign in, and open **Backstage → Import** at `/files/import`. Select
`sample-data/backstage/commerce-payments/catalog-info.yaml`, or paste its contents. Use **Check**
to run the dry run without storing anything, then import it. Import always waives soft findings;
the four intentional references therefore do not block the two affected documents.

Importing the unchanged file again reports 34 `CONFLICT` rows and stores nothing. Delete sample
rows from the Files page when finished. Catalog deletion is soft deletion, and there is no sample
loader that restores a prior workspace state.

`apiVersion` is discarded on input and emitted again by YAML preview and export. Keep the
`gdpr` and `pci-dss` values quoted as `"yes"` or `"no"`: YAML 1.1 tooling can parse the
unquoted words as booleans, while the SPA uses YAML 1.2 strings.

When editing the fixture, stay within the strict per-kind shape. In particular,
`Group.spec.children` and `User.spec.memberOf` must be present (an empty array is valid), and an
unused optional scalar must be omitted instead of set to an empty string. Registry applicability
also matters: tags apply only to Component and Resource, annotations never apply to Group or
User, lifecycle applies only to Component and API, and label keys have their own kind scopes.
