import type { TFunction } from "i18next";
import type { DocumentCheckFinding } from "../api/catalogFiles";
import classes from "../theme.module.css";

/** What a Mantine input needs to render a soft finding: the message plus the orange skin. */
type FindingProps =
  | { error: string; classNames: { input: string; error: string } }
  | Record<string, never>;

/**
 * The props to spread on an input so a SOFT finding shows on the field itself — orange border
 * and orange message, distinct from the red of a hard validation error, because a finding does
 * not block the save (it routes through the Save-anyway confirmation instead).
 *
 * Spread these AFTER `form.getInputProps(...)`: `getInputProps` returns a plain object, so the
 * later `error` wins. And pass `hardError` — a real validation error always takes precedence,
 * since "you cannot save this" outranks "this will save with findings".
 *
 * The message reuses the shared `errors.message.<STATUS>` catalogue (the Findings panel and
 * the Save-anyway dialog render the same strings). For a single-value field the offending
 * value is already visible in the input, so only the status message is shown; multi-value
 * fields name the entries at fault, since nothing else identifies which pill is wrong.
 */
export function findingProps(
  findings: readonly DocumentCheckFinding[],
  t: TFunction,
  { hardError, namedValues = false }: { hardError?: unknown; namedValues?: boolean } = {},
): FindingProps {
  if (hardError || findings.length === 0) return {};
  const message = namedValues
    ? findings
        .map((f) => t("catalog.finding.entry", { reference: f.reference, message: t(`errors.message.${f.status}`) }))
        .join(" ")
    : t(`errors.message.${findings[0].status}`);
  return {
    error: message,
    classNames: { input: classes.findingInput, error: classes.findingMessage },
  };
}
