import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useHierarchies } from "../hooks/useHierarchies";

/**
 * The Entity graph/hierarchy toolbars' hierarchy select (the `LensPicker` toolbar precedent —
 * a compact, size="xs" combo riding the toolbar's second row). Options are the admin-curated
 * `hierarchies` dictionary (`useHierarchies`) in payload order — the value/onChange pair is
 * fully controlled by the caller, which resolves the EFFECTIVE selected id (a stale stored
 * value falls back to the first dictionary entry) before rendering. An empty dictionary
 * disables the select and shows a hint pointing at the Hierarchies admin page — there is
 * nothing to switch between, and the caller's effective id resolves to "" in that case too.
 */
export default function HierarchyPicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (id: string) => void;
}) {
  const { t } = useTranslation();
  const { hierarchies } = useHierarchies();
  const empty = hierarchies.length === 0;
  const options = hierarchies.map((entry) => ({ value: entry.value, label: entry.value }));

  return (
    <Select
      size="xs"
      w={180}
      label={t("entityHierarchy.hierarchyPicker.label")}
      description={empty ? t("entityHierarchy.hierarchyPicker.empty") : undefined}
      data={options}
      value={empty ? null : value}
      onChange={(next) => onChange(next ?? "")}
      disabled={empty}
      allowDeselect={false}
    />
  );
}
