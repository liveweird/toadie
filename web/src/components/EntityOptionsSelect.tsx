import { MultiSelect, Select } from "@mantine/core";
import type { GetInputPropsReturnType } from "@mantine/form";
import type { ReactNode } from "react";
import { useEntityOptions } from "../hooks/useEntityOptions";
import { foldedOptionsFilter } from "../utils/text";

type FindingProps = { error?: ReactNode; classNames?: { input: string; error: string } };

type SharedProps = {
  /** The target blueprint identifier every option (and this field's value) resolves to. */
  target: string;
  label: ReactNode;
  description?: ReactNode;
  required?: boolean;
  /** Shown (in `description`) when the target pool fails to load. */
  failedHint: string;
  /** Shown (in `description`) when the target pool loaded but is empty. */
  emptyHint: string;
  /** A soft finding's props (`utils/entityFieldFindings.ts`), spread LAST so it wins over
   *  `inputProps.error` — the plan's "finding props last" rule. Absent for fields that carry
   *  no findings (every `EntityRelationField` today). */
  findingProps?: FindingProps;
};

export type EntityOptionsSelectProps =
  | (SharedProps & { mode: "single"; inputProps: GetInputPropsReturnType; clearable?: boolean })
  | (SharedProps & { mode: "multi"; inputProps: GetInputPropsReturnType });

/**
 * The target-pool Select/MultiSelect shared by every entity-reference picker: relation fields
 * (`EntityRelationField`), the team ownership picker (`EntityTeamField`), and `format:
 * team|user` properties (`EntityPropertyField`'s `ReferencePropertyField`). Options are every
 * active entity of `target` (`useEntityOptions`, labelled `identifier — title`); a stale
 * stored value the pool doesn't currently carry is appended so it keeps displaying/can still
 * be deselected (the registry-Select idiom used throughout the app). A failed/empty pool is
 * advisory only — shown as a `description` hint, never a blocker: the field keeps rendering
 * its current value either way.
 *
 * `inputProps` is `form.getInputProps(...)`'s return value, spread first so `onBlur`/`onFocus`
 * (validate-on-blur) keep working; single mode then overrides `value`/`onChange` to bridge
 * Mantine's `string | null` Select contract onto the form's plain-string field, exactly as the
 * extracted `EntityRelationField` did before this split.
 */
export default function EntityOptionsSelect(props: EntityOptionsSelectProps) {
  const { target, label, description, required, failedHint, emptyHint, findingProps, inputProps } = props;
  const { options, loading, error } = useEntityOptions(target);
  const optionIds = options.map((e) => e.identifier);
  const labelFor = (identifier: string) => {
    const match = options.find((e) => e.identifier === identifier);
    return match ? `${match.identifier} — ${match.title}` : identifier;
  };

  let hint: string | undefined;
  if (error) hint = failedHint;
  else if (!loading && optionIds.length === 0) hint = emptyHint;

  if (props.mode === "multi") {
    const value: string[] = Array.isArray(inputProps.value) ? (inputProps.value as string[]) : [];
    const stale = value.filter((v) => !optionIds.includes(v));
    const data = [...optionIds, ...stale].map((v) => ({ value: v, label: labelFor(v) }));
    return (
      <MultiSelect
        label={label}
        description={description ?? hint}
        required={required}
        searchable
        filter={foldedOptionsFilter}
        data={data}
        {...inputProps}
        {...findingProps}
      />
    );
  }

  const current = typeof inputProps.value === "string" ? inputProps.value.trim() : "";
  const stale = current && !optionIds.includes(current) ? [current] : [];
  const data = [...optionIds, ...stale].map((v) => ({ value: v, label: labelFor(v) }));
  return (
    <Select
      label={label}
      description={description ?? hint}
      required={required}
      searchable
      clearable={props.clearable ?? !required}
      filter={foldedOptionsFilter}
      data={data}
      {...inputProps}
      value={current || null}
      onChange={(v) => inputProps.onChange(v ?? "")}
      {...findingProps}
    />
  );
}
