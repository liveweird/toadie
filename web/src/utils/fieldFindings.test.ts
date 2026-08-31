import { describe, expect, test } from "vitest";
import { indexFindings, NO_FINDINGS, pillVerdict } from "./fieldFindings";
import type { DocumentCheckFinding } from "../api/catalogFiles";

// The real wire triples, taken from a deliberately broken document checked against the
// running server — the shapes this mapper exists to route.
const FINDINGS: DocumentCheckFinding[] = [
  { field: "spec.owner", reference: "group:default/no-such-group", status: "MISSING" },
  { field: "spec.system", reference: "component:default/checkout-service", status: "WRONG_KIND" },
  { field: "spec.subcomponentOf", reference: "component:default/broken", status: "SELF_REFERENCE" },
  { field: "spec.dependsOn", reference: "payments-db", status: "KIND_REQUIRED" },
  { field: "spec.dependsOn", reference: "resource:default/no-such-db", status: "MISSING" },
  { field: "metadata.labels", reference: "tier", status: "LABEL_NOT_ALLOWED" },
  { field: "metadata.labels", reference: "exposure=banana", status: "LABEL_NOT_ALLOWED" },
  { field: "metadata.annotations", reference: "acme.io/nope", status: "ANNOTATION_NOT_ALLOWED" },
  { field: "metadata.tags", reference: "cobol", status: "TAG_NOT_ALLOWED" },
  { field: "spec.type", reference: "bogus-type", status: "TYPE_NOT_ALLOWED" },
  { field: "spec.lifecycle", reference: "bogus-lifecycle", status: "LIFECYCLE_NOT_ALLOWED" },
];

describe("indexFindings", () => {
  const index = indexFindings(FINDINGS);

  test("a spec path maps to the form path by dropping the prefix", () => {
    // The form's value names mirror the spec field names exactly, so this is the whole rule.
    expect(index.forPath("owner").map((f) => f.status)).toEqual(["MISSING"]);
    expect(index.forPath("system").map((f) => f.status)).toEqual(["WRONG_KIND"]);
    expect(index.forPath("subcomponentOf").map((f) => f.status)).toEqual(["SELF_REFERENCE"]);
    expect(index.forPath("type").map((f) => f.status)).toEqual(["TYPE_NOT_ALLOWED"]);
    expect(index.forPath("lifecycle").map((f) => f.status)).toEqual(["LIFECYCLE_NOT_ALLOWED"]);
  });

  test("a multi-value field collects every offending entry", () => {
    expect(index.forPath("dependsOn").map((f) => f.reference)).toEqual([
      "payments-db",
      "resource:default/no-such-db",
    ]);
  });

  test("metadata.tags lands on the tags control", () => {
    expect(index.forPath("tags").map((f) => f.reference)).toEqual(["cobol"]);
  });

  test("a label finding routes to the KEY control when it names only a key", () => {
    expect(index.forLabelKey("tier")?.status).toBe("LABEL_NOT_ALLOWED");
    expect(index.forLabelValue("tier")).toBeUndefined();
  });

  test("a label finding routes to the VALUE control when it carries key=value", () => {
    // `exposure=banana` means the KEY is fine and the value is off its closed list.
    expect(index.forLabelValue("exposure")?.reference).toBe("exposure=banana");
    expect(index.forLabelKey("exposure")).toBeUndefined();
  });

  test("a value containing '=' still splits on the FIRST one", () => {
    const index2 = indexFindings([
      { field: "metadata.labels", reference: "k=a=b", status: "LABEL_NOT_ALLOWED" },
    ]);
    expect(index2.forLabelValue("k")?.reference).toBe("k=a=b");
  });

  test("annotation findings always target the key control", () => {
    // Annotation VALUES are not registry-checked, so there is never a value finding.
    expect(index.forAnnotationKey("acme.io/nope")?.status).toBe("ANNOTATION_NOT_ALLOWED");
  });

  test("unknown and report-only fields are skipped, not thrown on", () => {
    const index2 = indexFindings([
      { field: "metadata.namespace", reference: "gone", status: "NAMESPACE_NOT_ALLOWED" },
      { field: "document", reference: "", status: "STRUCTURE_INVALID" },
      { field: "source", reference: "", status: "SOURCE_MISSING" },
      { field: "spec.whatever", reference: "x", status: "MISSING" },
    ]);
    // They reach no control (the panel still lists them), and nothing blows up.
    expect(index2.forPath("namespace")).toEqual([]);
    expect(index2.forPath("whatever").map((f) => f.reference)).toEqual(["x"]);
  });

  test("nothing to route yields empty lookups everywhere", () => {
    expect(NO_FINDINGS.forPath("owner")).toEqual([]);
    expect(NO_FINDINGS.forLabelKey("tier")).toBeUndefined();
    expect(NO_FINDINGS.forLabelValue("tier")).toBeUndefined();
    expect(NO_FINDINGS.forAnnotationKey("k")).toBeUndefined();
  });
});

describe("pillVerdict", () => {
  // One multi-value field's world: "orders-db" is well-formed but flagged by the live check,
  // "Bad Ref!" is not a legal reference at all, "component:default/api" is fine.
  const findings: DocumentCheckFinding[] = [
    { field: "spec.dependsOn", reference: "orders-db", status: "KIND_REQUIRED" },
  ];
  const invalid = (value: string) => (/^[a-z:/-]+$/.test(value) ? null : "Not a valid reference");
  const statusMessage = (finding: DocumentCheckFinding) => `msg:${finding.status}`;
  const verdict = (value: string, hardError?: unknown) =>
    pillVerdict(value, { findings, hardError, invalid, statusMessage });

  test("a clean entry is never marked", () => {
    expect(verdict("component:default/api")).toBeUndefined();
    expect(verdict("component:default/api", "Some entry is invalid")).toBeUndefined();
  });

  test("a flagged entry is marked orange, carrying the status message", () => {
    expect(verdict("orders-db")).toEqual({ tone: "finding", title: "msg:KIND_REQUIRED" });
  });

  test("the match is exact — an entry edited since the check answered stops matching", () => {
    // The check is debounced; between its answer and this render the pill may have changed.
    expect(verdict("orders-db-2")).toBeUndefined();
  });

  test("no mark until validation has run — findings aside, red waits for the field's error", () => {
    // validateInputOnBlur: a half-typed reference must not flash red while you type it.
    expect(verdict("Bad Ref!")).toBeUndefined();
  });

  test("once the field carries a hard error, every malformed entry is marked red", () => {
    // The rule's own message can only name the first offender; the pills name them all.
    expect(verdict("Bad Ref!", "Entry is invalid")).toEqual({
      tone: "invalid",
      title: "Not a valid reference",
    });
    expect(verdict("Another Bad!", "Entry is invalid")).toEqual({
      tone: "invalid",
      title: "Not a valid reference",
    });
  });

  test("red outranks orange on the same field — one class of problem at a time", () => {
    // A hard error is showing, so the well-formed-but-flagged entry stays unmarked: the field
    // is currently reporting "you cannot save this", not "this saves with findings".
    expect(verdict("orders-db", "Entry is invalid")).toBeUndefined();
  });
});
