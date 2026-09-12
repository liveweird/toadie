import { Alert, Fieldset, Select, Stack, Text, Textarea, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import BlueprintAggregationRow from "./BlueprintAggregationRow";
import BlueprintCalculationRow from "./BlueprintCalculationRow";
import BlueprintMirrorRow from "./BlueprintMirrorRow";
import BlueprintPropertyRow from "./BlueprintPropertyRow";
import BlueprintRelationRow from "./BlueprintRelationRow";
import EditorRowList, { rowDomId } from "./EditorRowList";
import LoadingBlock from "./LoadingBlock";
import { type BlueprintRowExpansion } from "../hooks/useBlueprintRowExpansion";
import { useHierarchies } from "../hooks/useHierarchies";
import { BELOW_INPUT, charCountDescription } from "../utils/charCount";
import {
  aggregationBadge,
  calculationBadge,
  mirrorBadge,
  propertyBadge,
  relationBadge,
} from "../utils/blueprintRowSummary";
import {
  MAX_DESCRIPTION_LENGTH,
  MAX_ICON_LENGTH,
  MAX_IDENTIFIER_LENGTH,
  MAX_TITLE_LENGTH,
  OWNERSHIP_TYPES,
  emptyAggregationDraft,
  emptyCalculationDraft,
  emptyMirrorDraft,
  emptyPropertyDraft,
  emptyRelationDraft,
  singleRelationIds,
  type RowFamily,
  type BlueprintFormValues,
} from "../utils/blueprintForm";
import { loadErrorMessage } from "../utils/saveError";
import { lockedRowIds } from "../utils/systemBlueprints";

type Form = UseFormReturnType<BlueprintFormValues>;

/** `insertListItem` + `expansion.reveal` in one step — every "Add …" button needs both.
 *  The `RowFamily` union is wider than Mantine's own `insertListItem` overload can verify
 *  against a single draft type, but every call site below passes a draft matching its own
 *  family literal, so the cast is safe (the `combinedPropertyIds` idiom already accepted
 *  the same trade-off in `utils/blueprintForm.ts`). */
function addRow(form: Form, expansion: BlueprintRowExpansion, family: RowFamily, draft: { key: string }) {
  form.insertListItem(family, draft as never);
  expansion.reveal(rowDomId(family, draft.key));
}

function IdentityFields({ form, system }: { form: Form; system: boolean }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      <TextInput
        label={t("blueprints.field.identifier")}
        autoFocus
        required
        maxLength={MAX_IDENTIFIER_LENGTH}
        description={
          system
            ? t("blueprints.hint.systemIdentifier")
            : charCountDescription(form.values.identifier.length, MAX_IDENTIFIER_LENGTH)
        }
        inputWrapperOrder={[...BELOW_INPUT]}
        {...form.getInputProps("identifier")}
        readOnly={system}
      />
      <TextInput
        label={t("blueprints.field.title")}
        required
        maxLength={MAX_TITLE_LENGTH}
        description={charCountDescription(form.values.title.length, MAX_TITLE_LENGTH)}
        inputWrapperOrder={[...BELOW_INPUT]}
        {...form.getInputProps("title")}
      />
      <Textarea
        label={t("blueprints.field.description")}
        autosize
        minRows={2}
        maxLength={MAX_DESCRIPTION_LENGTH}
        description={charCountDescription(form.values.description.length, MAX_DESCRIPTION_LENGTH)}
        inputWrapperOrder={[...BELOW_INPUT]}
        {...form.getInputProps("description")}
      />
      <TextInput
        label={t("blueprints.field.icon")}
        maxLength={MAX_ICON_LENGTH}
        description={t("blueprints.hint.icon")}
        {...form.getInputProps("icon")}
      />
    </Stack>
  );
}

function PropertiesFieldset({ form, expansion }: { form: Form; expansion: BlueprintRowExpansion }) {
  const { t } = useTranslation();
  const section = t("blueprints.section.properties");
  return (
    <Fieldset legend={section}>
      <EditorRowList
        family="properties"
        rows={form.values.properties}
        errors={form.errors}
        expansion={expansion}
        section={section}
        newRowLabel={t("blueprints.rows.new.properties")}
        badge={propertyBadge}
        isRequired={(row) => row.required}
        isLocked={(row) => lockedRowIds(form.values.identifier, "properties").has(row.id)}
        renderBody={(row, index) => (
          <BlueprintPropertyRow
            form={form}
            index={index}
            locked={lockedRowIds(form.values.identifier, "properties").has(row.id)}
          />
        )}
        onAdd={() => addRow(form, expansion, "properties", emptyPropertyDraft())}
        addLabel={t("blueprints.addProperty")}
        onMove={(from, to) => form.reorderListItem("properties", { from, to })}
        onRemove={(index) => form.removeListItem("properties", index)}
        controlLabels={{
          moveUp: "blueprints.movePropertyUp",
          moveDown: "blueprints.movePropertyDown",
          remove: "blueprints.removePropertyAria",
        }}
      />
    </Fieldset>
  );
}

function RelationsFieldset({ form, expansion }: { form: Form; expansion: BlueprintRowExpansion }) {
  const { t } = useTranslation();
  const section = t("blueprints.section.relations");
  return (
    <Fieldset legend={section}>
      <EditorRowList
        family="relations"
        rows={form.values.relations}
        errors={form.errors}
        expansion={expansion}
        section={section}
        newRowLabel={t("blueprints.rows.new.relations")}
        badge={relationBadge}
        isRequired={(row) => row.required}
        isLocked={(row) => lockedRowIds(form.values.identifier, "relations").has(row.id)}
        renderBody={(row, index) => (
          <BlueprintRelationRow
            form={form}
            index={index}
            locked={lockedRowIds(form.values.identifier, "relations").has(row.id)}
          />
        )}
        onAdd={() => addRow(form, expansion, "relations", emptyRelationDraft())}
        addLabel={t("blueprints.addRelation")}
        onMove={(from, to) => form.reorderListItem("relations", { from, to })}
        onRemove={(index) => form.removeListItem("relations", index)}
        controlLabels={{
          moveUp: "blueprints.moveRelationUp",
          moveDown: "blueprints.moveRelationDown",
          remove: "blueprints.removeRelationAria",
        }}
      />
    </Fieldset>
  );
}

function MirrorFieldset({ form, expansion }: { form: Form; expansion: BlueprintRowExpansion }) {
  const { t } = useTranslation();
  const section = t("blueprints.section.mirrorProperties");
  return (
    <Fieldset legend={section}>
      <EditorRowList
        family="mirrorProperties"
        rows={form.values.mirrorProperties}
        errors={form.errors}
        expansion={expansion}
        section={section}
        newRowLabel={t("blueprints.rows.new.mirrorProperties")}
        badge={mirrorBadge}
        renderBody={(_row, index) => <BlueprintMirrorRow form={form} index={index} />}
        onAdd={() => addRow(form, expansion, "mirrorProperties", emptyMirrorDraft())}
        addLabel={t("blueprints.addMirror")}
        onMove={(from, to) => form.reorderListItem("mirrorProperties", { from, to })}
        onRemove={(index) => form.removeListItem("mirrorProperties", index)}
        controlLabels={{
          moveUp: "blueprints.moveMirrorUp",
          moveDown: "blueprints.moveMirrorDown",
          remove: "blueprints.removeMirrorAria",
        }}
      />
    </Fieldset>
  );
}

function CalculationFieldset({ form, expansion }: { form: Form; expansion: BlueprintRowExpansion }) {
  const { t } = useTranslation();
  const section = t("blueprints.section.calculationProperties");
  return (
    <Fieldset legend={section}>
      <EditorRowList
        family="calculationProperties"
        rows={form.values.calculationProperties}
        errors={form.errors}
        expansion={expansion}
        section={section}
        newRowLabel={t("blueprints.rows.new.calculationProperties")}
        badge={calculationBadge}
        renderBody={(_row, index) => <BlueprintCalculationRow form={form} index={index} />}
        onAdd={() => addRow(form, expansion, "calculationProperties", emptyCalculationDraft())}
        addLabel={t("blueprints.addCalculation")}
        onMove={(from, to) => form.reorderListItem("calculationProperties", { from, to })}
        onRemove={(index) => form.removeListItem("calculationProperties", index)}
        controlLabels={{
          moveUp: "blueprints.moveCalculationUp",
          moveDown: "blueprints.moveCalculationDown",
          remove: "blueprints.removeCalculationAria",
        }}
      />
    </Fieldset>
  );
}

function AggregationFieldset({ form, expansion }: { form: Form; expansion: BlueprintRowExpansion }) {
  const { t } = useTranslation();
  const section = t("blueprints.section.aggregationProperties");
  return (
    <Fieldset legend={section}>
      <EditorRowList
        family="aggregationProperties"
        rows={form.values.aggregationProperties}
        errors={form.errors}
        expansion={expansion}
        section={section}
        newRowLabel={t("blueprints.rows.new.aggregationProperties")}
        badge={aggregationBadge}
        renderBody={(_row, index) => <BlueprintAggregationRow form={form} index={index} />}
        onAdd={() => addRow(form, expansion, "aggregationProperties", emptyAggregationDraft())}
        addLabel={t("blueprints.addAggregation")}
        onMove={(from, to) => form.reorderListItem("aggregationProperties", { from, to })}
        onRemove={(index) => form.removeListItem("aggregationProperties", index)}
        controlLabels={{
          moveUp: "blueprints.moveAggregationUp",
          moveDown: "blueprints.moveAggregationDown",
          remove: "blueprints.removeAggregationAria",
        }}
      />
    </Fieldset>
  );
}

function OwnershipFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("blueprints.section.ownership")}>
      <Stack gap="sm">
        <Select
          label={t("blueprints.field.ownershipType")}
          data={[
            { value: "", label: t("blueprints.field.ownershipNone") },
            ...OWNERSHIP_TYPES.map((type) => ({ value: type, label: type })),
          ]}
          value={form.values.ownershipType || ""}
          onChange={(v) => form.setFieldValue("ownershipType", v ?? "")}
          allowDeselect={false}
        />
        {form.values.ownershipType && (
          <TextInput
            label={t("blueprints.field.title")}
            maxLength={MAX_TITLE_LENGTH}
            {...form.getInputProps("ownershipTitle")}
          />
        )}
        {form.values.ownershipType === "Inherited" && (
          <TextInput
            label={t("blueprints.field.path")}
            description={t("blueprints.hint.path")}
            required
            {...form.getInputProps("ownershipPath")}
          />
        )}
      </Stack>
    </Fieldset>
  );
}

/**
 * One row of the Hierarchy fieldset below — a Select for ONE `hierarchies` dictionary entry
 * (labelled with its own value, e.g. "composition"), or the same Select relabelled with the
 * raw stored KEY plus a warning-coloured hint when that key has since left the dictionary
 * (still rendered so the admin can clear a value whose next save would otherwise 400).
 */
function HierarchyRow({
  form,
  hierarchyId,
  known,
  options,
}: {
  form: Form;
  hierarchyId: string;
  known: boolean;
  options: string[];
}) {
  const { t } = useTranslation();
  const stored = form.values.hierarchyRelations[hierarchyId] ?? "";
  const value = options.includes(stored) ? stored : "";
  return (
    <Select
      label={hierarchyId}
      description={
        known ? (
          t("blueprints.hint.hierarchyRelations")
        ) : (
          <Text component="span" size="xs" c="orange">
            {t("blueprints.hint.hierarchyUnknown")}
          </Text>
        )
      }
      data={options}
      value={value || null}
      onChange={(v) => {
        const next = { ...form.values.hierarchyRelations };
        if (v) next[hierarchyId] = v;
        else delete next[hierarchyId];
        form.setFieldValue("hierarchyRelations", next);
      }}
      clearable
      clearButtonProps={{ "aria-label": t("blueprints.hierarchies.clearAria", { hierarchy: hierarchyId }) }}
      searchable
    />
  );
}

/**
 * The Hierarchy fieldset (v1.25.0, Port migration phase 3; multi-hierarchy v1.32.0) — a
 * Toadie-only extension, not a Port field: one admin-marked relation PER `hierarchies`
 * dictionary entry, naming this blueprint's containment parent for that hierarchy, backing
 * the Entity graph/hierarchy pages. One row per active dictionary entry (in the admin's
 * payload order), plus one row per stored key that has fallen out of the dictionary (so it
 * stays clearable). Every row's OPTIONS are the blueprint's own CURRENT single relations
 * (`many === false`), so a Select can never newly hold an invalid VALUE; a relation flipped to
 * `many` or removed silently drops out of the option list, and `toBlueprintRequest`'s
 * derive-don't-clear guard (not an effect) keeps the submitted value in sync the same way.
 */
function HierarchyFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  const { hierarchies, loading, error, loadError } = useHierarchies();
  const options = singleRelationIds(form.values.relations);
  const activeValues = hierarchies.map((h) => h.value);
  const staleKeys = Object.keys(form.values.hierarchyRelations).filter((k) => !activeValues.includes(k));

  return (
    <Fieldset legend={t("blueprints.section.hierarchy")}>
      {loading ? (
        <LoadingBlock />
      ) : error ? (
        <Alert color="red" variant="light" title={t("blueprints.loadFailed")}>
          {loadErrorMessage(loadError, t)}
        </Alert>
      ) : hierarchies.length === 0 && staleKeys.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("blueprints.hierarchies.empty")}
        </Text>
      ) : (
        <Stack gap="sm">
          {hierarchies.map((hierarchy) => (
            <HierarchyRow key={hierarchy.id} form={form} hierarchyId={hierarchy.value} known options={options} />
          ))}
          {staleKeys.map((key) => (
            <HierarchyRow key={key} form={form} hierarchyId={key} known={false} options={options} />
          ))}
        </Stack>
      )}
    </Fieldset>
  );
}

/**
 * The field block for the Blueprint editor (create/edit pages own submit/error handling and
 * the JSON preview). Identity fields first, then the six Port fieldsets in schema order, each
 * row family (all but Ownership) rendered through the shared `EditorRowList` (v1.23.2) — one
 * `expansion` state (`hooks/useBlueprintRowExpansion.ts`) owned by `BlueprintEditor` and
 * threaded through every family so a blocked submit can reveal errors across all five lists.
 * The Hierarchy fieldset follows Ownership, last, since it depends on the Relations rows above.
 */
export default function BlueprintFormFields({
  form,
  expansion,
  system = false,
}: {
  form: Form;
  expansion: BlueprintRowExpansion;
  /** A system blueprint (`_team`/`_user`, Phase 4 v1.26.0): the identifier is read-only and
   *  its base properties/relations are locked (see `utils/systemBlueprints.ts`). */
  system?: boolean;
}) {
  return (
    <Stack gap="md">
      <IdentityFields form={form} system={system} />
      <PropertiesFieldset form={form} expansion={expansion} />
      <RelationsFieldset form={form} expansion={expansion} />
      <MirrorFieldset form={form} expansion={expansion} />
      <CalculationFieldset form={form} expansion={expansion} />
      <AggregationFieldset form={form} expansion={expansion} />
      <OwnershipFieldset form={form} />
      <HierarchyFieldset form={form} />
    </Stack>
  );
}
