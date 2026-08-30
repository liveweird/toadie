import { useDebouncedValue } from "@mantine/hooks";
import type { CatalogFileFilterValues } from "../api/catalogFiles";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import { isString, isStringArray, useStoredState } from "./useStoredState";

// The visible-kinds guard: only real kinds may restore (junk — or a leftover from the
// pre-visible-set semantics where [] meant "no filter" — falls back to all-on, and a bad
// stored value can never produce a 400ing kind= param).
const isKindArray = (v: unknown): v is string[] =>
  isStringArray(v) && v.every((entry) => (ENTITY_KINDS as readonly string[]).includes(entry));

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
  /** Bulk-set every slot from a values snapshot — the LensPicker's apply entry point. */
  applyValues: (v: CatalogFileFilterValues) => void;
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
  /** Every kind pill toggled OFF — the page shows NO entities and must not fetch. */
  noKinds: boolean;
  controls: CatalogFileFilterControlsState;
} {
  const [name, setName] = useStoredState(`${viewKey}.filter.name`, "", isString);
  const [namespace, setNamespace] = useStoredState(`${viewKey}.filter.namespace`, "", isString);
  // The VISIBLE-kinds set behind the always-on pills: all kinds start visible, the state
  // persists per view, and an EMPTY set means "show nothing" (short-circuited client-side
  // via noKinds — the API cannot express match-nothing). Fresh storage key: the retired
  // filter.kind slot's [] meant the opposite ("no filter").
  const [kinds, setKindsRaw] = useStoredState<string[]>(
    `${viewKey}.filter.visibleKinds`,
    [...ENTITY_KINDS],
    isKindArray,
  );
  // Chip.Group reports values in CLICK order — normalize so URLs/query keys stay stable.
  const setKinds = (next: string[]) =>
    setKindsRaw(
      [...next].sort(
        (a, b) => (ENTITY_KINDS as readonly string[]).indexOf(a) - (ENTITY_KINDS as readonly string[]).indexOf(b),
      ),
    );
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

  // The lens-apply entry point: one snapshot into all nine slots. An absent kind set means
  // "every kind visible" (all-on sends no param, so the two states are one on the wire), an
  // absent scalar clears its slot, and labelValue never survives without its label.
  function applyValues(next: CatalogFileFilterValues) {
    setName(next.name ?? "");
    setNamespace(next.namespace ?? "");
    setKinds(next.kind && next.kind.length > 0 ? [...next.kind] : [...ENTITY_KINDS]);
    setTag(next.tag ?? "");
    setType(next.type ?? "");
    setLifecycle(next.lifecycle ?? "");
    setOwner(next.owner ?? "");
    setLabelRaw(next.label ?? "");
    setLabelValues(next.label && next.labelValue ? [...next.labelValue] : []);
  }

  const values: CatalogFileFilterValues = {
    name: debouncedName || undefined,
    namespace: namespace || undefined,
    // All-on sends NO param (the server's empty-means-no-filter contract); none-on sends
    // none either — the page checks noKinds and never fetches.
    kind: kinds.length > 0 && kinds.length < ENTITY_KINDS.length ? kinds : undefined,
    tag: tag || undefined,
    type: type || undefined,
    lifecycle: lifecycle || undefined,
    owner: owner || undefined,
    label: label || undefined,
    // labelValue never travels without its key (the server 400s the orphaned param).
    labelValue: label && labelValues.length > 0 ? labelValues : undefined,
  };

  // The pills are always visible, so kinds never counts into the hidden-filter badge.
  const activeFilterCount =
    (name.trim() ? 1 : 0) +
    (namespace.trim() ? 1 : 0) +
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
    noKinds: kinds.length === 0,
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
      applyValues,
    },
  };
}
