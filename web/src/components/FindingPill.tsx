import { Pill, type ComboboxRenderPillInput } from "@mantine/core";
import type { TFunction } from "i18next";
import type { DocumentCheckFinding } from "../api/catalogFiles";
import { pillVerdict } from "../utils/fieldFindings";
import classes from "../theme.module.css";

/**
 * Mantine's `renderPill` for a multi-value field, marking the entries that are at fault: red
 * for a grammar-invalid one (it blocks the save), orange for a soft finding (it saves through
 * the Save-anyway confirmation). Same colour split as the field-level tint — see
 * `utils/findingProps.tsx` — because the pill is the same verdict at entry granularity: the
 * box says something here is wrong, the pill says which one, and the message underneath keeps
 * saying why (so the colour is never the only carrier).
 *
 * `renderPill` replaces the default pill wholesale, so this re-renders it faithfully. That is
 * cheap here: neither `MultiSelect` nor `TagsInput` styles its pills (both pass `classes: {}`
 * to `useStyles`), and `Pill` picks up size and variant from the surrounding `PillGroup` /
 * `PillsInput` contexts — an unmarked pill therefore renders exactly as before. One gap the
 * callback cannot close: Mantine passes neither `readOnly` nor a computed `withRemoveButton`
 * into `renderPill`, so `!disabled` mirrors the default only for non-readOnly controls —
 * a future read-only picker rendering finding pills must thread its own flag through here.
 */
export function renderFindingPill({
  findings,
  hardError,
  invalid,
  t,
}: {
  findings: readonly DocumentCheckFinding[];
  hardError?: unknown;
  /** The grammar rule for ONE entry: the reason it is rejected, or null when it is fine. */
  invalid: (value: string) => string | null;
  t: TFunction;
}) {
  return function FindingPill({
    option,
    value,
    onRemove,
    disabled,
    reorderProps,
  }: ComboboxRenderPillInput) {
    const item = String(value ?? option.value);
    const verdict = pillVerdict(item, {
      findings,
      hardError,
      invalid,
      statusMessage: (finding) => t(`errors.message.${finding.status}`),
    });
    return (
      <Pill
        withRemoveButton={!disabled}
        onRemove={onRemove}
        disabled={disabled}
        title={verdict?.title}
        className={verdict && (verdict.tone === "invalid" ? classes.invalidPill : classes.findingPill)}
        {...reorderProps}
      >
        {/* The option's label when the combobox has one (MultiSelect), else the raw entry
            (TagsInput is free text and passes no option). */}
        {option?.label ?? item}
      </Pill>
    );
  };
}
