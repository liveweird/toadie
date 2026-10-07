import { useId, useState, type ReactNode } from "react";
import { Badge, Button, Fieldset, Group, Stack, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import type { Blueprint } from "../api/blueprints";
import EntityComputedFieldset from "./EntityComputedFieldset";
import EntityPropertyField from "./EntityPropertyField";
import EntityRelationField from "./EntityRelationField";
import EntityTeamField from "./EntityTeamField";
import SourceFieldset from "./SourceFieldset";
import TierFocusSelect from "./TierFocusSelect";
import { useTierFocus } from "../hooks/useTierFocus";
import { BELOW_INPUT, charCountDescription } from "../utils/charCount";
import { computedDefinitions } from "../utils/computedProperties";
import { NO_ENTITY_FINDINGS, type EntityFieldFindings } from "../utils/entityFieldFindings";
import {
  MAX_ICON_LENGTH,
  MAX_IDENTIFIER_LENGTH,
  MAX_TITLE_LENGTH,
  toEntityRequest,
  type EntityFormValues,
} from "../utils/entityForm";
import { fillTier, propertyTier, relationTier, withinFocus, type Tier } from "../utils/tiers";

type Form = UseFormReturnType<EntityFormValues>;

function IdentityFieldset({
  form,
  blueprint,
  computedTeam,
  findings,
}: {
  form: Form;
  blueprint: Blueprint;
  computedTeam: readonly string[];
  findings: EntityFieldFindings;
}) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("entities.section.identity")}>
      <Stack gap="sm">
        <TextInput
          label={t("entities.field.identifier")}
          autoFocus
          required
          maxLength={MAX_IDENTIFIER_LENGTH}
          description={charCountDescription(form.values.identifier.length, MAX_IDENTIFIER_LENGTH)}
          inputWrapperOrder={[...BELOW_INPUT]}
          {...form.getInputProps("identifier")}
        />
        <TextInput
          label={t("entities.field.title")}
          required
          maxLength={MAX_TITLE_LENGTH}
          description={charCountDescription(form.values.title.length, MAX_TITLE_LENGTH)}
          inputWrapperOrder={[...BELOW_INPUT]}
          {...form.getInputProps("title")}
        />
        <TextInput label={t("entities.field.icon")} maxLength={MAX_ICON_LENGTH} {...form.getInputProps("icon")} />
        <EntityTeamField form={form} blueprint={blueprint} computedTeam={computedTeam} findings={findings} />
      </Stack>
    </Fieldset>
  );
}

/** Whether any client validation error sits under `<section>.<index>.` — such a field is
 *  never folded away, so a rejected value is always in view. */
function hasFieldError(form: Form, section: "properties" | "relations", index: number): boolean {
  const prefix = `${section}.${index}.`;
  return Object.keys(form.errors).some((path) => path.startsWith(prefix));
}

/** One property/relation field as the fold sees it: its stable key, its rendered node, whether
 *  the Focus keeps it, and whether something (required, a finding, an error) pins it open. */
type FoldEntry = { key: string; node: ReactNode; inFocus: boolean; pinned: boolean };

/**
 * All fields in their original order, ONE stable tree: every field keeps the same parent for
 * its whole life, so a field that becomes pinned (an error lands, a finding arrives) or is
 * revealed never unmounts — no lost focus or caret mid-typing. When the tier Focus (2.18.0)
 * pushes some out, those fields are merely hidden (`display: none`) behind a "Show N more
 * fields" toggle; they stay mounted, keep their draft state and are submitted like any other.
 *
 * Pinning is LATCHED: once a field has been pinned under the current Focus it stays shown, so
 * clearing its error by typing never folds it away under the user's hands. The latch resets
 * when the Focus changes (and with the editor, which remounts the component).
 */
function FoldedFields({ entries, focus }: { entries: FoldEntry[]; focus: Tier | null }) {
  const { t } = useTranslation();
  const idBase = useId();
  const [open, setOpen] = useState(false);
  const [latch, setLatch] = useState<{ focus: Tier | null; keys: ReadonlySet<string> }>({ focus, keys: new Set() });
  // Derived-state update during render (React's documented pattern): fold newly pinned keys
  // into the latch, starting over when the Focus changed.
  const current = latch.focus === focus ? latch : { focus, keys: new Set<string>() };
  const pinnedKeys = entries.filter((entry) => entry.pinned).map((entry) => entry.key);
  if (current !== latch || pinnedKeys.some((key) => !current.keys.has(key))) {
    setLatch({ focus, keys: new Set([...current.keys, ...pinnedKeys]) });
  }

  const kept = (entry: FoldEntry) => entry.inFocus || entry.pinned || current.keys.has(entry.key);
  const folded = entries.filter((entry) => !kept(entry));
  const fieldId = (key: string) => `${idBase}-${key}`;
  return (
    <Stack gap="md">
      {entries.map((entry) => (
        <div key={entry.key} id={fieldId(entry.key)} style={kept(entry) || open ? undefined : { display: "none" }}>
          {entry.node}
        </div>
      ))}
      {folded.length > 0 && (
        <Button
          variant="subtle"
          size="compact-sm"
          style={{ alignSelf: "flex-start" }}
          aria-expanded={open}
          aria-controls={folded.map((entry) => fieldId(entry.key)).join(" ")}
          onClick={() => setOpen((value) => !value)}
        >
          {open ? t("common.tier.hideFields") : t("common.tier.moreFields", { count: folded.length })}
        </Button>
      )}
    </Stack>
  );
}

function PropertiesFieldset({
  form,
  blueprint,
  findings,
  focus,
}: {
  form: Form;
  blueprint: Blueprint;
  findings: EntityFieldFindings;
  focus: Tier | null;
}) {
  const { t } = useTranslation();
  if (form.values.properties.length === 0) return null;
  const required = new Set(blueprint.schema.required);
  const entries: FoldEntry[] = form.values.properties.map((draft, index) => {
    const tier = propertyTier(blueprint, draft.id);
    const fieldFindings = findings.forField(`properties.${draft.id}`);
    return {
      key: draft.id,
      inFocus: withinFocus(tier, focus),
      pinned:
        required.has(draft.id) || draft.unknown || fieldFindings.length > 0 || hasFieldError(form, "properties", index),
      node: (
        <EntityPropertyField
          form={form}
          index={index}
          definition={blueprint.schema.properties[draft.id]}
          required={required.has(draft.id)}
          findings={fieldFindings}
          tier={tier}
        />
      ),
    };
  });
  return (
    <Fieldset legend={t("entities.section.properties")}>
      <FoldedFields entries={entries} focus={focus} />
    </Fieldset>
  );
}

function RelationsFieldset({
  form,
  blueprint,
  findings,
  focus,
}: {
  form: Form;
  blueprint: Blueprint;
  findings: EntityFieldFindings;
  focus: Tier | null;
}) {
  const { t } = useTranslation();
  if (form.values.relations.length === 0) return null;
  const entries: FoldEntry[] = [];
  form.values.relations.forEach((draft, index) => {
    const definition = blueprint.relations[draft.id];
    if (!definition) return;
    const tier = relationTier(blueprint, draft.id);
    entries.push({
      key: draft.id,
      inFocus: withinFocus(tier, focus),
      pinned:
        definition.required ||
        findings.forField(`relations.${draft.id}`).length > 0 ||
        hasFieldError(form, "relations", index),
      node: (
        <EntityRelationField
          form={form}
          index={index}
          definition={definition}
          required={definition.required}
          tier={tier}
        />
      ),
    });
  });
  return (
    <Fieldset legend={t("entities.section.relations")}>
      <FoldedFields entries={entries} focus={focus} />
    </Fieldset>
  );
}

/**
 * The tier header (2.18.0) — the "Filled through tier N" badge, computed LIVE from the values
 * the request builder would send (`toEntityRequest`, so exactly what a save would carry), and
 * the Focus picker. Only rendered for a blueprint that has tiered fields: `fillTier` is
 * `null` for one without, and an untiered blueprint has nothing to prioritise.
 */
function TierHeader({
  filled,
  focus,
  onFocus,
}: {
  filled: number;
  focus: Tier | null;
  onFocus: (next: Tier | null) => void;
}) {
  const { t } = useTranslation();
  return (
    <Group justify="space-between" align="flex-end" wrap="wrap">
      <Badge variant="light" color={filled >= 4 ? "teal" : "gray"} size="lg" tt="none">
        {filled === 0 ? t("common.tier.incomplete") : t("common.tier.filledThrough", { tier: filled })}
      </Badge>
      <TierFocusSelect value={focus} onChange={onFocus} />
    </Group>
  );
}

/**
 * The field block for the Entity editor: Identity (identifier/title/icon/team) then one
 * fixed-size row per blueprint schema property and relation — unlike the Blueprint editor's
 * foldable EditorRowLists, an entity cannot invent new property/relation ids, so there is
 * nothing to add, move, or remove here. `findings` (Phase 4 ownership + the general stale-entity
 * surface) is indexed once by `EntityEditor` and threaded down so `team`/`properties.<id>`
 * controls paint their own soft finding; `computedTeam` is the entity's own EFFECTIVE `team`
 * (empty on create) — `EntityTeamField`'s Inherited-ownership pills.
 *
 * `computed` (v1.27.0, phase 5) is the entity's evaluated mirror/calculation/aggregation
 * values (`utils/computedProperties.ts#computedValuesOf`) — `undefined` on create, where
 * nothing has been saved yet for the server to compute against. The read-only
 * `EntityComputedFieldset` renders LAST, after Relations, and only when a value map was
 * supplied AND the blueprint actually declares at least one computed property (a blueprint
 * with none renders nothing, same as an entity with no relations).
 */
export default function EntityFormFields({
  form,
  blueprint,
  computedTeam = [],
  findings = NO_ENTITY_FINDINGS,
  computed,
}: {
  form: Form;
  blueprint: Blueprint;
  computedTeam?: readonly string[];
  findings?: EntityFieldFindings;
  computed?: Record<string, unknown>;
}) {
  const { t } = useTranslation();
  const definitions = computedDefinitions(blueprint);
  const [storedFocus, setFocus] = useTierFocus("entityEditor.focusTier");
  const request = toEntityRequest(form.values, blueprint);
  const filled = fillTier(blueprint, request.properties, request.relations);
  // A blueprint without tiered fields has nothing to prioritise: no header, and a stored
  // Focus must not fold its (all untiered) fields away with no control left to undo it.
  const focus = filled === null ? null : storedFocus;
  return (
    <Stack gap="md">
      <IdentityFieldset form={form} blueprint={blueprint} computedTeam={computedTeam} findings={findings} />
      {filled !== null && <TierHeader filled={filled} focus={focus} onFocus={setFocus} />}
      <PropertiesFieldset form={form} blueprint={blueprint} findings={findings} focus={focus} />
      <RelationsFieldset form={form} blueprint={blueprint} findings={findings} focus={focus} />
      {computed !== undefined && definitions.length > 0 && (
        <EntityComputedFieldset definitions={definitions} values={computed} />
      )}
      <SourceFieldset
        legend={t("entities.section.source")}
        label={t("entities.field.sourceUrl")}
        hint={t("entities.hint.sourceUrl")}
        placeholder="https://raw.githubusercontent.com/acme/service/main/entity.json"
        inputProps={form.getInputProps("sourceUrl")}
      />
    </Stack>
  );
}
