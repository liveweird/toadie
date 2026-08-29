import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { MultiSelect, Select } from "@mantine/core";
import type { CatalogFileFilterControlsState } from "../hooks/useCatalogFileFilterState";
import { useCatalogIdentities } from "../hooks/useCatalogIdentities";
import { useEntityTypes } from "../hooks/useEntityTypes";
import { useLabels } from "../hooks/useLabels";
import { useLifecycleOptions } from "../hooks/useLifecycleOptions";
import { useTagCategories } from "../hooks/useTagCategories";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import { refSuggestions } from "../utils/refSuggestions";
import ClearableTextInput from "./ClearableTextInput";
import NamespaceFilterSelect from "./NamespaceFilterSelect";

/** Appends a persisted-but-no-longer-offered value so it keeps displaying (the stale idiom —
 *  it still filters server-side; files may carry it from before a registry change). */
function withStale(options: string[], current: string): string[] {
  return current && !options.includes(current) ? [...options, current] : options;
}

/**
 * The shared filter controls (Files list, Hierarchy, Graph): the whole catalog-file filter
 * set, rendered inside the caller's FilterPanel. State lives in useCatalogFileFilterState —
 * this component only supplies the option sources (registries, dictionaries, the identity
 * pool) and the stale-value handling.
 */
export default function CatalogFileFilterControls({
  controls,
}: {
  controls: CatalogFileFilterControlsState;
}) {
  const { t } = useTranslation();
  const { categories } = useTagCategories();
  const { dictionaries } = useEntityTypes();
  const lifecycles = useLifecycleOptions(controls.lifecycle);
  const { labels } = useLabels();
  const identities = useCatalogIdentities();

  const tagOptions = useMemo(() => {
    const groups = categories.map((c) => ({ group: c.name, items: c.tags }));
    const known = categories.some((c) => c.tags.includes(controls.tag));
    return controls.tag && !known
      ? [...groups, { group: t("catalog.staleTagsGroup"), items: [controls.tag] }]
      : groups;
  }, [categories, controls.tag, t]);

  const typeOptions = useMemo(() => {
    // A picked kind narrows the options to that kind's dictionary; otherwise the union of
    // every dictionary (deduped — the same type may be allowed for several kinds).
    const source = controls.kind
      ? dictionaries.filter((d) => d.kind === controls.kind)
      : dictionaries;
    const union = [...new Set(source.flatMap((d) => d.types))].sort((a, b) => a.localeCompare(b));
    return withStale(union, controls.type);
  }, [dictionaries, controls.kind, controls.type]);

  const ownerOptions = useMemo(
    () => withStale(refSuggestions(identities, "owner"), controls.owner),
    [identities, controls.owner],
  );

  const labelOptions = useMemo(
    () => withStale([...labels.map((l) => l.key)].sort((a, b) => a.localeCompare(b)), controls.label),
    [labels, controls.label],
  );

  const labelValueOptions = useMemo(() => {
    const registered = labels.find((l) => l.key === controls.label)?.values ?? [];
    const stale = controls.labelValues.filter((v) => !registered.includes(v));
    return [...registered, ...stale];
  }, [labels, controls.label, controls.labelValues]);

  return (
    <>
      <ClearableTextInput
        label={t("common.field.name")}
        value={controls.name}
        onChange={controls.setName}
        clearLabel={t("common.filter.clearName")}
      />
      <NamespaceFilterSelect value={controls.namespace} onChange={controls.setNamespace} />
      <Select
        label={t("catalog.field.kind")}
        placeholder={t("catalog.anyKind")}
        data={[...ENTITY_KINDS]}
        value={controls.kind || null}
        onChange={(v) => controls.setKind(v ?? "")}
        clearable
        clearButtonProps={{ "aria-label": t("catalog.clearKindFilter") }}
      />
      <Select
        label={t("catalog.field.type")}
        placeholder={t("catalog.anyType")}
        data={typeOptions}
        value={controls.type || null}
        onChange={(v) => controls.setType(v ?? "")}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearType") }}
      />
      <Select
        label={t("catalog.field.lifecycle")}
        placeholder={t("catalog.anyLifecycle")}
        data={lifecycles.options}
        value={controls.lifecycle || null}
        onChange={(v) => controls.setLifecycle(v ?? "")}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearLifecycle") }}
      />
      <Select
        label={t("catalog.field.owner")}
        placeholder={t("catalog.anyOwner")}
        data={ownerOptions}
        value={controls.owner || null}
        onChange={(v) => controls.setOwner(v ?? "")}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearOwner") }}
      />
      <Select
        label={t("catalog.field.tags")}
        placeholder={t("catalog.anyTag")}
        data={tagOptions}
        value={controls.tag || null}
        onChange={(v) => controls.setTag(v ?? "")}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearTag") }}
      />
      <Select
        label={t("catalog.field.label")}
        placeholder={t("catalog.anyLabel")}
        data={labelOptions}
        value={controls.label || null}
        onChange={(v) => controls.setLabel(v ?? "")}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearLabel") }}
      />
      <MultiSelect
        label={t("catalog.field.labelValues")}
        placeholder={controls.labelValues.length === 0 ? t("catalog.anyLabelValue") : undefined}
        data={labelValueOptions}
        value={controls.labelValues}
        onChange={controls.setLabelValues}
        disabled={!controls.label}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearLabelValues") }}
      />
    </>
  );
}
