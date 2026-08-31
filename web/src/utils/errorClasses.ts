import type { components } from "../api/schema";

type ErrorStatus = components["schemas"]["ErrorStatus"];

/**
 * The Errors page's pill classes — a grouping of the wire statuses: the four reference
 * verdicts share one pill, every other status is its own class. Order = pill order.
 */
export const ERROR_CLASSES = [
  "references",
  "structure",
  "namespace",
  "labels",
  "annotations",
  "tags",
  "types",
  "lifecycles",
  "source",
] as const;

export type ErrorClass = (typeof ERROR_CLASSES)[number];

const STATUS_CLASS: Record<ErrorStatus, ErrorClass> = {
  MISSING: "references",
  KIND_REQUIRED: "references",
  WRONG_KIND: "references",
  SELF_REFERENCE: "references",
  LABEL_NOT_ALLOWED: "labels",
  ANNOTATION_NOT_ALLOWED: "annotations",
  TAG_NOT_ALLOWED: "tags",
  TYPE_NOT_ALLOWED: "types",
  LIFECYCLE_NOT_ALLOWED: "lifecycles",
  STRUCTURE_INVALID: "structure",
  NAMESPACE_NOT_ALLOWED: "namespace",
  SOURCE_MISSING: "source",
};

export const classOfStatus = (status: ErrorStatus): ErrorClass => STATUS_CLASS[status];

/**
 * The report's badge colour follows the app-wide vocabulary, not one red for everything:
 * red = the rule is HARD on writes (structure, namespace — the row would be rejected as-is),
 * orange = a SOFT finding (saves through Save-anyway, the same orange as everywhere else),
 * gray = `SOURCE_MISSING`, which is no defect at all — the reference is optional and the
 * row is only reporting its absence.
 */
const CLASS_COLOR: Record<ErrorClass, string> = {
  structure: "red",
  namespace: "red",
  references: "orange",
  labels: "orange",
  annotations: "orange",
  tags: "orange",
  types: "orange",
  lifecycles: "orange",
  source: "gray",
};

export const colorOfStatus = (status: ErrorStatus): string => CLASS_COLOR[STATUS_CLASS[status]];
