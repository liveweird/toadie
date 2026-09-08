import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useBlueprints } from "../hooks/useBlueprints";

/**
 * The relation/aggregation target picker: every OTHER active blueprint plus this blueprint's
 * OWN (live, possibly not-yet-saved) identifier — labelled distinctly since it names a
 * self-reference rather than a registry row. A stale stored value not among either is
 * appended so it keeps displaying.
 */
export default function BlueprintTargetSelect({
  value,
  onChange,
  ownIdentifier,
  error,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  ownIdentifier: string;
  error?: string;
  label: string;
}) {
  const { t } = useTranslation();
  const { blueprints, loading, error: loadError } = useBlueprints();
  const own = ownIdentifier.trim();
  const options = blueprints.filter((b) => b.identifier !== own).map((b) => b.identifier);
  const withSelf = own ? [...options, own] : options;
  const current = value.trim();
  const data = [
    ...withSelf.map((identifier) => ({
      value: identifier,
      label: identifier === own ? t("blueprints.field.targetSelf", { identifier }) : identifier,
    })),
    ...(current && !withSelf.includes(current) ? [{ value: current, label: current }] : []),
  ];
  let hint: string | undefined;
  if (loadError) hint = t("blueprints.targetOptionsFailed");
  else if (!loading && withSelf.length === 0) hint = t("blueprints.noBlueprintsYet");
  return (
    <Select
      label={label}
      required
      data={data}
      searchable
      description={hint}
      value={current || null}
      onChange={(v) => onChange(v ?? "")}
      error={error}
    />
  );
}
