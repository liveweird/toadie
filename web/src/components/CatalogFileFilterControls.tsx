import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Chip, Group, MultiSelect, Select, Stack, Text } from "@mantine/core";
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

// The nav/pills display order for the type groups (ENTITY_KINDS widened for indexOf).
const KIND_ORDER: readonly string[] = ENTITY_KINDS;

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
    // Grouped by kind (the tags Select's separator idiom); picked kind pills narrow which
    // groups appear. Option VALUES are kind-prefixed because the same type may legally be
    // allowed for several kinds ("service" in Component AND System) and Mantine requires
    // unique values across groups — labels stay the bare type, and onChange strips the
    // prefix back off. The stale item keeps its raw (prefix-less) value.
    const source = (
      controls.kinds.length > 0
        ? dictionaries.filter((d) => controls.kinds.includes(d.kind))
        : dictionaries
    )
      .filter((d) => d.types.length > 0)
      .sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind));
    const groups = source.map((d) => ({
      group: d.kind,
      items: [...d.types]
        .sort((a, b) => a.localeCompare(b))
        .map((type) => ({ value: `${d.kind}:${type}`, label: type })),
    }));
    const known = source.some((d) => d.types.includes(controls.type));
    return controls.type && !known
      ? [...groups, { group: t("catalog.staleTagsGroup"), items: [{ value: controls.type, label: controls.type }] }]
      : groups;
  }, [dictionaries, controls.kinds, controls.type, t]);

  // The Select needs an option VALUE; the filter stores the bare type — resolve it to the
  // first group offering that label (which group is irrelevant: the server has no
  // type↔kind interplay).
  const selectedType = useMemo(() => {
    if (!controls.type) return null;
    for (const group of typeOptions) {
      const hit = group.items.find((item) => item.label === controls.type);
      if (hit) return hit.value;
    }
    return null;
  }, [typeOptions, controls.type]);

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
      <Stack gap={4}>
        <Text size="sm" fw={500} component="label">
          {t("catalog.field.kind")}
        </Text>
        {/* Multi-select pills — any-of/IN server-side; the min height keeps the row
            bottom-aligned with the 36px Select inputs beside it. */}
        <Chip.Group multiple value={controls.kinds} onChange={controls.setKinds}>
          <Group gap="xs" role="group" aria-label={t("catalog.field.kind")} mih={36} align="center">
            {ENTITY_KINDS.map((kind) => (
              <Chip key={kind} value={kind} size="xs">
                {kind}
              </Chip>
            ))}
          </Group>
        </Chip.Group>
      </Stack>
      <Select
        label={t("catalog.field.type")}
        placeholder={t("catalog.anyType")}
        data={typeOptions}
        value={selectedType}
        onChange={(v) => controls.setType(v ? v.slice(v.indexOf(":") + 1) : "")}
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
