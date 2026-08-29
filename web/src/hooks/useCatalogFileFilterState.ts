import { useDebouncedValue } from "@mantine/hooks";
import type { CatalogFileFilterValues } from "../api/catalogFiles";
import { isString, isStringArray, useStoredState } from "./useStoredState";

/** The raw control values + setters CatalogFileFilterControls renders. */
export type CatalogFileFilterControlsState = {
  name: string;
  setName: (v: string) => void;
  namespace: string;
  setNamespace: (v: string) => void;
  kinds: string[];
  setKinds: (v: string[]) => void;
  tag: string;
  setTag: (v: string) => void;
  type: string;
  setType: (v: string) => void;
  lifecycle: string;
  setLifecycle: (v: string) => void;
  owner: string;
  setOwner: (v: string) => void;
  label: string;
  setLabel: (v: string) => void;
  labelValues: string[];
  setLabelValues: (v: string[]) => void;
};

/**
 * The shared catalog-file filter state (Files list, Hierarchy, Graph — one filter set, the
 * server declares it identically on the list and graph endpoints). Every slot persists under
 * `toadie.viewSettings.<viewKey>.filter.*`, so each view keeps its own filters; only the
 * freetext Name filter debounces (the Selects change discretely). `values` is the normalized
 * request/query-key shape (blank → absent, name DEBOUNCED); `deps` feeds usePagedSort's
 * page-1 reset on the list page.
 */
export function useCatalogFileFilterState(viewKey: string): {
  values: CatalogFileFilterValues;
  deps: unknown[];
  activeFilterCount: number;
  controls: CatalogFileFilterControlsState;
} {
  const [name, setName] = useStoredState(`${viewKey}.filter.name`, "", isString);
  const [namespace, setNamespace] = useStoredState(`${viewKey}.filter.namespace`, "", isString);
  // Multi-select pills (any-of/IN server-side); a pre-pills persisted STRING fails the
  // array guard and falls back to [] cleanly.
  const [kinds, setKinds] = useStoredState<string[]>(`${viewKey}.filter.kind`, [], isStringArray);
  const [tag, setTag] = useStoredState(`${viewKey}.filter.tag`, "", isString);
  const [type, setType] = useStoredState(`${viewKey}.filter.type`, "", isString);
  const [lifecycle, setLifecycle] = useStoredState(`${viewKey}.filter.lifecycle`, "", isString);
  const [owner, setOwner] = useStoredState(`${viewKey}.filter.owner`, "", isString);
  const [label, setLabelRaw] = useStoredState(`${viewKey}.filter.label`, "", isString);
  const [labelValues, setLabelValues] = useStoredState<string[]>(
    `${viewKey}.filter.labelValue`,
    [],
    isStringArray,
  );
  const [debouncedName] = useDebouncedValue(name, 300);

  // Picking a different label key invalidates the chosen values (the editor's key-reset idiom).
  function setLabel(next: string) {
    setLabelRaw(next);
    if (next !== label) setLabelValues([]);
  }

  const values: CatalogFileFilterValues = {
    name: debouncedName || undefined,
    namespace: namespace || undefined,
    kind: kinds.length > 0 ? kinds : undefined,
    tag: tag || undefined,
    type: type || undefined,
    lifecycle: lifecycle || undefined,
    owner: owner || undefined,
    label: label || undefined,
    // labelValue never travels without its key (the server 400s the orphaned param).
    labelValue: label && labelValues.length > 0 ? labelValues : undefined,
  };

  const activeFilterCount =
    (name.trim() ? 1 : 0) +
    (namespace.trim() ? 1 : 0) +
    (kinds.length > 0 ? 1 : 0) +
    (tag.trim() ? 1 : 0) +
    (type ? 1 : 0) +
    (lifecycle ? 1 : 0) +
    (owner ? 1 : 0) +
    (label ? 1 : 0) +
    (label && labelValues.length > 0 ? 1 : 0);

  return {
    values,
    deps: [debouncedName, namespace, kinds, tag, type, lifecycle, owner, label, labelValues],
    activeFilterCount,
    controls: {
      name,
      setName,
      namespace,
      setNamespace,
      kinds,
      setKinds,
      tag,
      setTag,
      type,
      setType,
      lifecycle,
      setLifecycle,
      owner,
      setOwner,
      label,
      setLabel,
      labelValues,
      setLabelValues,
    },
  };
}
