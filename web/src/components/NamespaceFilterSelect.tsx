import { useTranslation } from "react-i18next";
import { Select } from "@mantine/core";
import { useNamespaceOptions } from "../hooks/useNamespaceOptions";

/**
 * The namespace FILTER combo (the list and render pages): a Select over the ADMIN-curated
 * namespaces dictionary — the same options source as the catalog form's namespace field.
 * A persisted filter value no longer in the dictionary is appended by the hook so it keeps
 * displaying; a failed dictionary load degrades to that appended value alone (the list
 * itself still renders — no extra error UI for a filter).
 */
export default function NamespaceFilterSelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  const { options } = useNamespaceOptions(value);
  return (
    <Select
      label={t("catalog.field.namespace")}
      placeholder={t("catalog.anyNamespace")}
      data={options}
      value={value || null}
      onChange={(v) => onChange(v ?? "")}
      searchable
      clearable
      clearButtonProps={{ "aria-label": t("common.filter.clearNamespace") }}
    />
  );
}
