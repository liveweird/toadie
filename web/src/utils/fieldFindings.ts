import type { DocumentCheckFinding } from "../api/catalogFiles";

/**
 * Routes the live check's findings (`POST /files/check`) to the editor control that produced
 * them, so a problem shows up ON the field and not only in the Findings panel.
 *
 * The wire gives a spec/metadata path plus the offending value — `{field: "spec.owner",
 * reference: "group:default/gone", status: "MISSING"}`. Three shapes to know:
 *
 * - **Refs, type, lifecycle** map one-to-one by stripping `spec.`: the form's value names
 *   mirror the spec field names exactly (`owner`, `dependsOn`, `type`, …). A multi-valued
 *   field emits one finding PER offending entry.
 * - **Tags** arrive as `metadata.tags` with the offending tag in `reference`.
 * - **Labels and annotations** carry the bare section path — `metadata.labels` for every row —
 *   so the row is identified only by `reference`, which for a label is either `key` (the key
 *   is unregistered, or not allowed for this kind) or `key=value` (the value is off the key's
 *   closed list). Split on the FIRST `=`: a label key cannot contain one. Annotation findings
 *   never carry a value — only keys are registry-checked.
 */
export interface FieldFindings {
  /** Findings for a plain form path (`owner`, `dependsOn`, `type`, `lifecycle`, `tags`). */
  forPath: (path: string) => DocumentCheckFinding[];
  /** The finding against a label row's KEY control, if the key itself is rejected. */
  forLabelKey: (key: string) => DocumentCheckFinding | undefined;
  /** The finding against a label row's VALUE control, if the value is off the closed list. */
  forLabelValue: (key: string) => DocumentCheckFinding | undefined;
  /** The finding against an annotation row's KEY control. */
  forAnnotationKey: (key: string) => DocumentCheckFinding | undefined;
}

const LABEL_FIELD = "metadata.labels";
const ANNOTATION_FIELD = "metadata.annotations";
const TAGS_FIELD = "metadata.tags";

/** `exposure=banana` → `["exposure", "banana"]`; `tier` → `["tier", undefined]`. */
function splitLabelReference(reference: string): [string, string | undefined] {
  const at = reference.indexOf("=");
  return at < 0 ? [reference, undefined] : [reference.slice(0, at), reference.slice(at + 1)];
}

/** Groups the findings once per render so each control's lookup is a map hit, not a scan. */
export function indexFindings(findings: readonly DocumentCheckFinding[]): FieldFindings {
  const byPath = new Map<string, DocumentCheckFinding[]>();
  const labelKeys = new Map<string, DocumentCheckFinding>();
  const labelValues = new Map<string, DocumentCheckFinding>();
  const annotationKeys = new Map<string, DocumentCheckFinding>();

  const push = (path: string, finding: DocumentCheckFinding) => {
    const existing = byPath.get(path);
    if (existing) existing.push(finding);
    else byPath.set(path, [finding]);
  };

  for (const finding of findings) {
    if (finding.field === LABEL_FIELD) {
      const [key, value] = splitLabelReference(finding.reference);
      // Value present = the value is at fault; otherwise the key is.
      (value === undefined ? labelKeys : labelValues).set(key, finding);
    } else if (finding.field === ANNOTATION_FIELD) {
      annotationKeys.set(finding.reference, finding);
    } else if (finding.field === TAGS_FIELD) {
      push("tags", finding);
    } else if (finding.field.startsWith("spec.")) {
      push(finding.field.slice("spec.".length), finding);
    }
    // Anything else (the report-only `metadata.namespace`/`document`/`source` verdicts, or a
    // field this client does not know) is skipped — the panel still lists it.
  }

  return {
    forPath: (path) => byPath.get(path) ?? [],
    forLabelKey: (key) => labelKeys.get(key),
    forLabelValue: (key) => labelValues.get(key),
    forAnnotationKey: (key) => annotationKeys.get(key),
  };
}

/** No findings at all — the stable empty lookup, so a findings-free render allocates nothing. */
export const NO_FINDINGS: FieldFindings = indexFindings([]);

/** How one entry of a multi-value field is marked: the tone to paint it, and why. */
export interface PillVerdict {
  /** `invalid` = the grammar rejects it (red, blocks the save); `finding` = soft (orange). */
  tone: "invalid" | "finding";
  /** The reason, shown as the pill's hover title. */
  title: string;
}

/**
 * The verdict on ONE entry of a multi-value field (a `TagsInput`/`MultiSelect` pill). The
 * field-level tint says something is wrong somewhere in the box; this says WHICH entry.
 *
 * The precedence rule is deliberately the one `findingProps` applies at field level — a field
 * shows one class of problem at a time and the pills follow it: while the field carries a hard
 * error only the entries the grammar rejects are marked (red outranks orange), otherwise the
 * flagged entries are. Red is gated on the field's `hardError` rather than on the predicate
 * alone because validation runs on BLUR: a half-typed reference must not flash red as you type
 * it. Once it has run, though, EVERY offending entry lights up — the rule's own message can
 * only ever name the first.
 *
 * `findings` are matched by exact string: the server echoes the stored value verbatim into
 * `reference` (`checkDocument`/`tagFindings`), so no normalization is needed. An entry edited
 * since the debounced check answered simply stops matching until the next one lands.
 */
export function pillVerdict(
  value: string,
  {
    findings,
    hardError,
    invalid,
    statusMessage,
  }: {
    findings: readonly DocumentCheckFinding[];
    hardError?: unknown;
    /** The grammar rule for one entry: the reason it is rejected, or null when it is fine. */
    invalid: (value: string) => string | null;
    /** Renders a finding's status as the message the panel and the field already show. */
    statusMessage: (finding: DocumentCheckFinding) => string;
  },
): PillVerdict | undefined {
  if (hardError) {
    const reason = invalid(value);
    return reason === null ? undefined : { tone: "invalid", title: reason };
  }
  const finding = findings.find((f) => f.reference === value);
  return finding === undefined ? undefined : { tone: "finding", title: statusMessage(finding) };
}
