import { useState } from "react";
import { Alert, Button, Group, Modal, SegmentedControl, Select, Stack, Text } from "@mantine/core";
import { useForm, type UseFormReturnType } from "@mantine/form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { ParseKeys } from "i18next";
import { useTranslation } from "react-i18next";
import type { Blueprint } from "../api/blueprints";
import {
  applyEntityBulkChange,
  checkEntityBulkChange,
  type EntityBulkChangeRequest,
  type EntityBulkOperation,
  type EntityBulkResult,
} from "../api/entities";
import {
  bulkCounts,
  bulkErrorMessage,
  bulkFields,
  bulkValueOf,
  hasBulkValue,
  isOutcomeUnknown,
  operationsFor,
  toBulkRequest,
  valuePaths,
  type BulkField,
} from "../utils/entityBulk";
import { NO_ENTITY_FINDINGS } from "../utils/entityFieldFindings";
import { blankEntityForm, entityFieldError, toEntityRequest, type EntityFormValues } from "../utils/entityForm";
import { showSuccessToast } from "../utils/toast";
import EntityBulkResults from "./EntityBulkResults";
import EntityPropertyField from "./EntityPropertyField";
import EntityRelationField from "./EntityRelationField";
import EntityTeamField from "./EntityTeamField";

/** One selected entity as the modal needs it: the id the request names, the rest is display. */
export type BulkTarget = { id: number; identifier: string; title: string };

type Step = "configure" | "preview" | "result";
type Form = UseFormReturnType<EntityFormValues>;

// Literal keys (unusedKeys.test.ts).
const OPERATION_LABEL: Record<EntityBulkOperation, ParseKeys> = {
  SET: "entityBulk.operation.SET",
  CLEAR: "entityBulk.operation.CLEAR",
  ADD: "entityBulk.operation.ADD",
  REMOVE: "entityBulk.operation.REMOVE",
};
const OPERATION_HINT: Record<EntityBulkOperation, ParseKeys> = {
  SET: "entityBulk.operationHint.SET",
  CLEAR: "entityBulk.operationHint.CLEAR",
  ADD: "entityBulk.operationHint.ADD",
  REMOVE: "entityBulk.operationHint.REMOVE",
};

/** The editor's own widget for the picked field, over the throwaway form (never `required`:
 *  the bulk value is an input to an operation, and the server judges the AFTER document). */
function ValueEditor({ form, blueprint, field }: { form: Form; blueprint: Blueprint; field: BulkField }) {
  if (field.kind === "property") {
    return (
      <EntityPropertyField
        form={form}
        index={field.index}
        definition={blueprint.schema.properties[field.id]}
        required={false}
      />
    );
  }
  if (field.kind === "relation") {
    return (
      <EntityRelationField
        form={form}
        index={field.index}
        definition={blueprint.relations[field.id]}
        required={false}
      />
    );
  }
  return <EntityTeamField form={form} blueprint={blueprint} computedTeam={[]} findings={NO_ENTITY_FINDINGS} />;
}

/**
 * The bulk-change flow (2.38.0), three steps in one dialog: (1) pick the field, the operation
 * and — through the entity editor's OWN value widgets over a throwaway blank form — the value;
 * (2) the dry-run preview (`POST …/bulk-change/check`, the per-row verdicts); (3) the result of
 * the real run. Mounted only while open by its page, so every open starts from a fresh form.
 *
 * Blank is never an implicit clear: CLEAR is its own operation (disabled for a required field)
 * and an empty value widget blocks Preview. Only the picked field is validated
 * (`entityFieldError` over `valuePaths`) — the rest of the throwaway form is blank on purpose. The apply sends
 * EVERY selected entity, each with the write-version stamp the preview read, so an entity edited
 * between preview and apply comes back `STALE` rather than being overwritten. The write is not
 * atomic across rows (each is its own strict write); `onApplied` reports the rows so the page
 * keeps exactly the not-done ones selected.
 */
export default function EntityBulkChangeModal({
  blueprint,
  targets,
  onClose,
  onApplied,
}: {
  blueprint: Blueprint;
  targets: readonly BulkTarget[];
  onClose: () => void;
  /** Fired once with the real run's rows. */
  onApplied: (results: readonly EntityBulkResult[]) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [step, setStep] = useState<Step>("configure");
  const [fieldPath, setFieldPath] = useState<string | null>(null);
  const [operation, setOperation] = useState<EntityBulkOperation>("SET");
  const [attempted, setAttempted] = useState(false);
  const [previewed, setPreviewed] = useState<{ request: EntityBulkChangeRequest; rows: EntityBulkResult[] } | null>(
    null,
  );
  const [outcome, setOutcome] = useState<EntityBulkResult[] | null>(null);

  // No `validate`/blur rules on the throwaway form: Mantine captures a rule set once, while ADD/REMOVE
  // need per-item-only list rules, so Preview validates the picked field itself (`entityFieldError`).
  const form = useForm<EntityFormValues>({ initialValues: blankEntityForm(blueprint) });
  const check = useMutation({ mutationFn: checkEntityBulkChange });
  // The refresh lives in the mutation's OWN options (not the call-site callbacks): it must run on
  // success AND on a failure whose outcome is unknown, even if the dialog is gone by then.
  const apply = useMutation({
    mutationFn: applyEntityBulkChange,
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ["entities"] });
    },
  });
  // A timeout / network failure / 5xx may have committed some rows: the preview's stamps are no
  // longer trustworthy, so Apply stays off until the user goes back and previews again.
  const applyOutcomeUnknown = apply.isError && isOutcomeUnknown(apply.error);

  const fields = bulkFields(blueprint, blueprint.ownership?.title ?? t("entities.field.team"));
  const field = fields.find((candidate) => candidate.path === fieldPath) ?? null;
  const busy = check.isPending || apply.isPending;

  const groups = [
    { group: t("entityBulk.group.properties"), kind: "property" },
    { group: t("entityBulk.group.relations"), kind: "relation" },
    { group: t("entityBulk.group.ownership"), kind: "team" },
  ]
    .map(({ group, kind }) => ({
      group,
      items: fields.filter((candidate) => candidate.kind === kind).map((candidate) => ({ value: candidate.path, label: candidate.label })),
    }))
    .filter((group) => group.items.length > 0);

  const value =
    field && operation !== "CLEAR" ? bulkValueOf(field, toEntityRequest(form.values, blueprint)) : undefined;
  const valueMissing = attempted && field !== null && !hasBulkValue(operation, value);

  function pickField(next: string | null) {
    setFieldPath(next);
    setOperation("SET");
    setAttempted(false);
    form.reset();
    form.clearErrors();
    check.reset();
  }

  function close() {
    if (!busy) onClose();
  }

  function preview() {
    if (!field) return;
    setAttempted(true);
    // CLEAR has no value widget mounted: nothing to validate (and stale slot errors must not block it).
    form.clearErrors();
    if (operation !== "CLEAR") {
      const delta = operation === "ADD" || operation === "REMOVE";
      let invalid = false;
      for (const path of valuePaths(field)) {
        const message = entityFieldError(t, blueprint, form.values, path, delta);
        if (message) {
          form.setFieldError(path, message);
          invalid = true;
        }
      }
      if (invalid) return;
    }
    if (!hasBulkValue(operation, value)) return;
    const request = toBulkRequest({
      blueprint: blueprint.identifier,
      field,
      operation,
      value,
      entities: targets,
    });
    check.mutate(request, {
      onSuccess: (response) => {
        setPreviewed({ request, rows: response.results });
        setStep("preview");
      },
    });
  }

  function applyPreview() {
    if (!previewed) return;
    // EVERY selected entity goes with the write-version stamp the preview read (none for a row the
    // preview returned no stamp for), so one edited since the preview is reported STALE by the
    // server instead of being overwritten; the server classifies each row again.
    apply.mutate(
      {
        ...previewed.request,
        entities: previewed.rows.map((row) => (row.updatedAt === undefined ? { id: row.id } : { id: row.id, updatedAt: row.updatedAt })),
      },
      {
        onSuccess: (response) => {
          setOutcome(response.results);
          setStep("result");
          if (bulkCounts(response.results).UPDATED > 0) showSuccessToast(t("entityBulk.toast.applied"));
          onApplied(response.results);
        },
      },
    );
  }

  const wouldUpdate = previewed ? bulkCounts(previewed.rows).UPDATED : 0;
  const failedAfter = outcome ? bulkCounts(outcome) : null;

  return (
    <Modal opened onClose={close} title={t("entityBulk.modal.title")} size="xl" centered>
      {step === "configure" && (
        <Stack gap="md">
          <Text size="sm" c="dimmed">
            {t("entityBulk.modal.intro", { count: targets.length, blueprint: blueprint.title })}
          </Text>
          <Select
            label={t("entityBulk.field.label")}
            placeholder={t("entityBulk.field.placeholder")}
            data={groups}
            value={fieldPath}
            onChange={pickField}
            searchable
            allowDeselect={false}
          />
          {field && (
            <>
              <Stack gap={4}>
                <Text size="sm" fw={500}>
                  {t("entityBulk.operation.label")}
                </Text>
                <SegmentedControl
                  aria-label={t("entityBulk.operation.label")}
                  value={operation}
                  onChange={(next) => {
                    setOperation(next as EntityBulkOperation);
                    // Errors of the previous operation's value no longer describe what Preview will send.
                    form.clearErrors();
                  }}
                  data={operationsFor(field).map((option) => ({
                    value: option.operation,
                    label: t(OPERATION_LABEL[option.operation]),
                    disabled: !option.enabled,
                  }))}
                />
                <Text size="xs" c="dimmed">
                  {t(OPERATION_HINT[operation])}
                </Text>
              </Stack>
              {operation !== "CLEAR" && (
                <Stack gap={4}>
                  <ValueEditor key={field.path} form={form} blueprint={blueprint} field={field} />
                  {valueMissing && (
                    <Text size="xs" c="red" role="alert">
                      {t(operation === "SET" ? "entityBulk.valueRequired" : "entityBulk.itemsRequired")}
                    </Text>
                  )}
                </Stack>
              )}
            </>
          )}
          {check.isError && (
            <Alert color="red" variant="light">
              {bulkErrorMessage(check.error, t, "check")}
            </Alert>
          )}
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={close} disabled={busy}>
              {t("common.action.cancel")}
            </Button>
            <Button onClick={preview} disabled={!field} loading={check.isPending}>
              {t("entityBulk.preview.action")}
            </Button>
          </Group>
        </Stack>
      )}

      {step === "preview" && previewed && field && (
        <Stack gap="md">
          <Text size="sm" c="dimmed">
            {t("entityBulk.preview.intro", { field: field.label, operation: t(OPERATION_LABEL[operation]) })}
          </Text>
          <EntityBulkResults rows={previewed.rows} mode="check" />
          {apply.isError && (
            <Alert color="red" variant="light">
              {bulkErrorMessage(apply.error, t, "apply")}
            </Alert>
          )}
          <Group justify="space-between" gap="sm">
            <Button
              variant="default"
              disabled={busy}
              onClick={() => {
                apply.reset();
                setStep("configure");
              }}
            >
              {t("entityBulk.back")}
            </Button>
            <Group gap="sm">
              <Button variant="default" onClick={close} disabled={busy}>
                {t("common.action.cancel")}
              </Button>
              <Button onClick={applyPreview} loading={apply.isPending} disabled={wouldUpdate === 0 || applyOutcomeUnknown}>
                {t("entityBulk.apply", { count: wouldUpdate })}
              </Button>
            </Group>
          </Group>
        </Stack>
      )}

      {step === "result" && outcome && failedAfter && (
        <Stack gap="md">
          <EntityBulkResults rows={outcome} mode="apply" />
          {failedAfter.INVALID + failedAfter.STALE + failedAfter.ERROR > 0 && (
            <Alert color="orange" variant="light">
              {t("entityBulk.result.failedHint")}
            </Alert>
          )}
          <Group justify="flex-end">
            <Button onClick={onClose}>{t("common.action.close")}</Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
