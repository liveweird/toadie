# Commerce and payments: Backstage software catalog

[`catalog-info.yaml`](catalog-info.yaml) is a 42-document Backstage catalog covering all seven
kinds. It exercises the Files list, per-kind editor, Errors report, Graph, Hierarchy, Lenses, and
YAML import/export. Loading it is standalone: the Port sample is not a prerequisite. The file is,
however, **generated from** the Port sample (see [Regenerating this file](#regenerating-this-file)):
it is what the [default conversion mapping](../../port/commerce-payments/mapping/backstage.json)
makes of the 42 Port entities of the eight mapped blueprints.

| Kind | Count | Examples |
|---|---:|---|
| Domain | 5 | `commerce`, `payments`, `platform`, `shared-services`, `back-office` |
| System | 5 | `storefront`, `payments`, `external/acquirer`, `invoicing`, `developer-portal` |
| Component | 12 | ten services (`storefront-web`, `checkout-service`, `payments-gateway`, ...) and two libraries (`payments-sdk`, `acme-commons`) |
| API | 6 | `catalog-graphql`, `order-events`, `payments-grpc` |
| Resource | 8 | `orders-db`, `order-bus`, `settlement-warehouse` |
| Group | 4 | `platform-tribe`, `retail-tribe`, `payments-squad`, `storefront-squad` |
| User | 2 | `anna.kowalska`, `marek.nowak` |

Forty documents use the `default` namespace and two use `external` (a Port entity flagged
`external`). Every structural shape, namespace, and registry-backed value is valid against the seeded
vocabulary. The file uses all seeded type values, all four lifecycles, both namespaces, all eleven
label keys, all five annotation keys, and all four tag categories.

The catalog is intentionally not reference-clean. Four soft reference findings in two documents
exercise the Errors report and import waiver:

| Document and field | Reference | Finding |
|---|---|---|
| `legacy-invoicing` owner | `group:default/billing-squad` | `MISSING` |
| `legacy-invoicing` dependency | `resource:default/invoice-archive` | `MISSING` |
| `legacy-invoicing` dependency | `orders-db` | `KIND_REQUIRED` |
| `catalog-service` dependency | `template:default/nodejs-service-template` | `WRONG_KIND` |

On an empty workspace, import reports 40 `CREATED` and 2 `CREATED_WITH_FINDINGS` rows. The stored
catalog then reports those four reference findings plus 42 report-only `SOURCE_MISSING` findings,
one for each document without a `sourceUrl`. `SOURCE_MISSING` is expected for a pasted or uploaded
local sample. The graph contains 42 stored nodes, two missing-reference nodes, and 113 edges.

## Load through the Backstage UI

Start Toadie, sign in, and open **Backstage → Import** at `/files/import`. Select
`sample-data/backstage/commerce-payments/catalog-info.yaml`, or paste its contents. Use **Check**
to run the dry run without storing anything, then import it. Import always waives soft findings;
the four intentional references therefore do not block the two affected documents.

Importing the unchanged file again reports 42 `CONFLICT` rows and stores nothing. Delete sample
rows from the Files page when finished. Catalog deletion is soft deletion, and there is no sample
loader that restores a prior workspace state.

`apiVersion` is discarded on input and emitted again by YAML preview and export. Keep the yes/no label values (`gdpr`, `pci-dss`, `banking-outsourcing`, `cash-flow-impact`, `dora-cif`) quoted as `"yes"` or `"no"`: YAML 1.1 tooling can parse the
unquoted words as booleans, while the SPA uses YAML 1.2 strings.

When editing the fixture, stay within the strict per-kind shape. In particular,
`Group.spec.children` and `User.spec.memberOf` must be present (an empty array is valid), and an
unused optional scalar must be omitted instead of set to an empty string. Registry applicability
also matters: tags apply only to Component and Resource, annotations never apply to Group or
User, lifecycle applies only to Component and API, and label keys have their own kind scopes.

## Regenerating this file

The documents are not edited by hand; they are the export of the Port sample. `SampleConversionTest`
(`.claude/docs/testing.md`) pins that every document equals what the default mapping makes of the Port
entities — except three hand-restored edits, the only differences it tolerates, which carry the four
reference findings above (`catalog-service`'s `template:` dependency; `legacy-invoicing`'s owner and its
dependencies). After changing the Port sample or `mapping/backstage.json`:

1. Run the conversion test with the dump switched on, so it writes the raw export response:
   `TOADIE_DUMP_BACKSTAGE_SAMPLE=/tmp/export.json ./gradlew :server:test --tests ch.nokillswit.SampleConversionTest`
   (the test itself fails while the checked-in file is stale — the dump is written first).
2. Turn the `files` of that response into multi-document YAML (one document per file, `apiVersion:
   backstage.io/v1alpha1` first, `yes`/`no` label values quoted), keeping the section comments.
3. Restore the three deliberate edits by hand, each with its "on purpose" comment, and run the test again.

The same export is available live: `GET /api/v1/conversion-mappings/{id}/catalog-documents` converts the
stored Port entities through a stored mapping (see `.claude/docs/conversion-mapping.md`).
