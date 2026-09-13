import { Select } from "@mantine/core";
import { IconSitemap } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { useHierarchies } from "../hooks/useHierarchies";

/**
 * The Entity graph/hierarchy toolbars' hierarchy select (2.4.1: compacted for the title row —
 * a `leftSection` icon and an `aria-label` stand in for the visible `label`/description this
 * used to carry as a Select field; `combobox { name: "Hierarchy" }` still resolves). Options are
 * the admin-curated `hierarchies` dictionary (`useHierarchies`) in payload order — the
 * value/onChange pair is fully controlled by the caller, which resolves the EFFECTIVE selected
 * id (a stale stored value falls back to the first dictionary entry) before rendering. An empty
 * dictionary disables the select and keeps the `description` hint pointing at the Hierarchies
 * admin page (the one piece of visible text this component still owns) — there is nothing to
 * switch between, and the caller's effective id resolves to "" in that case too.
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
      aria-label={t("entityHierarchy.hierarchyPicker.label")}
      placeholder={t("entityHierarchy.hierarchyPicker.label")}
      leftSection={<IconSitemap size={14} />}
      description={empty ? t("entityHierarchy.hierarchyPicker.empty") : undefined}
      data={options}
      value={empty ? null : value}
      onChange={(next) => onChange(next ?? "")}
      disabled={empty}
      allowDeselect={false}
    />
  );
}
