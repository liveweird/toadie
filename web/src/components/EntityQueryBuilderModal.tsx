import { useMemo, useState } from "react";
import type { ParseKeys } from "i18next";
import { useTranslation } from "react-i18next";
import {
  ActionIcon,
  Alert,
  Button,
  Code,
  Fieldset,
  Group,
  Modal,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  TextInput,
} from "@mantine/core";
import { IconPlus, IconTrash } from "@tabler/icons-react";
import type { QueryCompletionSchema } from "../utils/queryCompletion";
import {
  QUERY_BUILDER_MAX_CONDITIONS,
  buildQuery,
  createQueryBuilderModel,
  getQueryBuilderOperators,
  getQueryBuilderProperties,
  type QueryBuilderCondition,
  type QueryBuilderConnection,
  type QueryBuilderIssueCode,
  type QueryBuilderModel,
  type QueryBuilderOperator,
  type QueryBuilderProperty,
} from "../utils/queryBuilder";

const OPERATOR_KEYS: Record<QueryBuilderOperator, ParseKeys> = {
  eq: "entityQuery.builder.operatorEq",
  neq: "entityQuery.builder.operatorNeq",
  lt: "entityQuery.builder.operatorLt",
  lte: "entityQuery.builder.operatorLte",
  gt: "entityQuery.builder.operatorGt",
  gte: "entityQuery.builder.operatorGte",
  contains: "entityQuery.builder.operatorContains",
  startsWith: "entityQuery.builder.operatorStartsWith",
  endsWith: "entityQuery.builder.operatorEndsWith",
  isNull: "entityQuery.builder.operatorIsNull",
  isNotNull: "entityQuery.builder.operatorIsNotNull",
};

const ISSUE_KEYS: Record<QueryBuilderIssueCode, ParseKeys> = {
  blueprintRequired: "entityQuery.builder.issue.blueprintRequired",
  unknownBlueprint: "entityQuery.builder.issue.unknownBlueprint",
  tooManyConditions: "entityQuery.builder.issue.tooManyConditions",
  propertyRequired: "entityQuery.builder.issue.propertyRequired",
  unknownProperty: "entityQuery.builder.issue.unknownProperty",
  operatorUnsupported: "entityQuery.builder.issue.operatorUnsupported",
  valueRequired: "entityQuery.builder.issue.valueRequired",
  valueInvalid: "entityQuery.builder.issue.valueInvalid",
  connectionNameRequired: "entityQuery.builder.issue.connectionNameRequired",
  connectionUnsupported: "entityQuery.builder.issue.connectionUnsupported",
  directionUnsupported: "entityQuery.builder.issue.directionUnsupported",
  targetBlueprintUnknown: "entityQuery.builder.issue.targetBlueprintUnknown",
  targetIdentifierRequiresBlueprint: "entityQuery.builder.issue.targetIdentifierRequiresBlueprint",
  maxHopsInvalid: "entityQuery.builder.issue.maxHopsInvalid",
  returnUnsupported: "entityQuery.builder.issue.returnUnsupported",
  limitInvalid: "entityQuery.builder.issue.limitInvalid",
  queryTooLong: "entityQuery.builder.issue.queryTooLong",
  unsupportedName: "entityQuery.builder.issue.unsupportedName",
};

function blankCondition(): QueryBuilderCondition {
  return { property: "", operator: "eq", value: "" };
}

function connectionFor(kind: QueryBuilderConnection["kind"]): QueryBuilderConnection {
  if (kind === "ownership") {
    return {
      kind,
      name: "$team",
      direction: "out",
      targetBlueprint: "_team",
      targetIdentifier: "",
      maxHops: "1",
      optional: false,
    };
  }
  return {
    kind,
    name: "",
    direction: "out",
    targetBlueprint: "",
    targetIdentifier: "",
    maxHops: "1",
    optional: false,
  };
}

type BuilderResult = ReturnType<typeof buildQuery>;

const STALE_SCHEMA_ISSUES = new Set<QueryBuilderIssueCode>([
  "unknownBlueprint",
  "unknownProperty",
  "operatorUnsupported",
  "targetBlueprintUnknown",
  "connectionUnsupported",
  "unsupportedName",
]);

type SelectChoice = { value: string; label: string };

/** Keep the common case quiet while preserving an unambiguous accessible name when schema
 * authors give two identifiers the same display title. */
function disambiguateChoices(choices: SelectChoice[]): SelectChoice[] {
  const counts = new Map<string, number>();
  for (const choice of choices) counts.set(choice.label, (counts.get(choice.label) ?? 0) + 1);
  return choices.map((choice) => counts.get(choice.label) === 1
    ? choice
    : { ...choice, label: `${choice.label} (${choice.value})` });
}

function issueAt(result: BuilderResult, path: string, touched: ReadonlySet<string>): QueryBuilderIssueCode | undefined {
  const code = result.errors.find((issue) => issue.path === path)?.code;
  if (!code || (!touched.has(path) && !STALE_SCHEMA_ISSUES.has(code))) return undefined;
  return code;
}

function ValueField({
  property,
  condition,
  error,
  onChange,
}: {
  property: QueryBuilderProperty | undefined;
  condition: QueryBuilderCondition;
  error?: string;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  if (condition.operator === "isNull" || condition.operator === "isNotNull") return null;
  const common = { label: t("entityQuery.builder.value"), error };
  if (property?.enumValues.length) {
    return (
      <Select
        {...common}
        searchable
        data={property.enumValues.map((value) => String(value))}
        value={condition.value || null}
        onChange={(value) => onChange(value ?? "")}
      />
    );
  }
  if (property?.type === "boolean") {
    return (
      <Select
        {...common}
        allowDeselect={false}
        data={[
          { value: "true", label: t("entityQuery.builder.booleanTrue") },
          { value: "false", label: t("entityQuery.builder.booleanFalse") },
        ]}
        value={condition.value || null}
        onChange={(value) => onChange(value ?? "")}
      />
    );
  }
  if (property?.type === "number") {
    return (
      <TextInput
        {...common}
        inputMode="decimal"
        value={condition.value}
        onChange={(event) => onChange(event.currentTarget.value)}
      />
    );
  }
  return <TextInput {...common} value={condition.value} onChange={(event) => onChange(event.currentTarget.value)} />;
}

function ConditionsEditor({
  model,
  result,
  properties,
  touched,
  onModelChange,
  touch,
}: {
  model: QueryBuilderModel;
  result: BuilderResult;
  properties: QueryBuilderProperty[];
  touched: ReadonlySet<string>;
  onModelChange: (model: QueryBuilderModel) => void;
  touch: (path: string) => void;
}) {
  const { t } = useTranslation();
  const errorText = (path: string) => {
    const code = issueAt(result, path, touched);
    return code ? t(ISSUE_KEYS[code]) : undefined;
  };
  const update = (index: number, condition: QueryBuilderCondition) => {
    const conditions = model.conditions.map((current, currentIndex) => currentIndex === index ? condition : current);
    onModelChange({ ...model, conditions });
  };

  return (
    <Fieldset legend={t("entityQuery.builder.conditions")} style={{ minInlineSize: 0 }}>
      <Stack gap="sm">
        {model.conditions.map((condition, index) => {
          const prefix = `conditions.${index}`;
          const property = properties.find((candidate) => candidate.id === condition.property);
          const operators = property ? getQueryBuilderOperators(property) : [];
          return (
            <SimpleGrid key={index} cols={{ base: 1, sm: 3 }} style={{ minWidth: 0 }}>
              <Select
                style={{ minWidth: 0 }}
                label={t("entityQuery.builder.property")}
                searchable
                data={disambiguateChoices(properties.map((candidate) => ({
                  value: candidate.id,
                  label: candidate.source === "meta"
                    ? t(candidate.id === "$identifier" ? "entityQuery.builder.metaIdentifier" : "entityQuery.builder.metaTitle")
                    : candidate.title,
                })))}
                value={condition.property || null}
                error={errorText(`${prefix}.property`)}
                onChange={(value) => {
                  const nextProperty = properties.find((candidate) => candidate.id === value);
                  const nextOperators = nextProperty ? getQueryBuilderOperators(nextProperty) : [];
                  update(index, {
                    property: value ?? "",
                    operator: nextOperators[0] ?? "eq",
                    value: "",
                  });
                  touch(`${prefix}.property`);
                }}
              />
              <Select
                style={{ minWidth: 0 }}
                label={t("entityQuery.builder.operator")}
                allowDeselect={false}
                disabled={!property}
                data={operators.map((operator) => ({ value: operator, label: t(OPERATOR_KEYS[operator]) }))}
                value={condition.operator}
                error={errorText(`${prefix}.operator`)}
                onChange={(value) => {
                  update(index, { ...condition, operator: (value ?? "eq") as QueryBuilderOperator, value: "" });
                  touch(`${prefix}.operator`);
                }}
              />
              <div style={{ minWidth: 0 }}>
                <ValueField
                  property={property}
                  condition={condition}
                  error={errorText(`${prefix}.value`)}
                  onChange={(value) => {
                    update(index, { ...condition, value });
                    touch(`${prefix}.value`);
                  }}
                />
              </div>
              <ActionIcon
                variant="subtle"
                color="red"
                aria-label={t("entityQuery.builder.removeCondition", { number: index + 1 })}
                onClick={() => onModelChange({
                  ...model,
                  conditions: model.conditions.filter((_, currentIndex) => currentIndex !== index),
                })}
              >
                <IconTrash size={16} />
              </ActionIcon>
            </SimpleGrid>
          );
        })}
        <Button
          variant="default"
          leftSection={<IconPlus size={14} />}
          disabled={!model.blueprint || model.conditions.length >= QUERY_BUILDER_MAX_CONDITIONS}
          onClick={() => onModelChange({ ...model, conditions: [...model.conditions, blankCondition()] })}
        >
          {t("entityQuery.builder.addCondition")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

function ConnectionEditor({
  model,
  result,
  schema,
  touched,
  onModelChange,
  touch,
}: {
  model: QueryBuilderModel;
  result: BuilderResult;
  schema: QueryCompletionSchema;
  touched: ReadonlySet<string>;
  onModelChange: (model: QueryBuilderModel) => void;
  touch: (path: string) => void;
}) {
  const { t } = useTranslation();
  const connection = model.connection;
  const blueprint = schema.blueprints.find((candidate) => candidate.identifier === model.blueprint);
  const errorText = (path: string) => {
    const code = issueAt(result, path, touched);
    return code ? t(ISSUE_KEYS[code]) : undefined;
  };
  const replace = (patch: Partial<QueryBuilderConnection>) => {
    if (connection) onModelChange({ ...model, connection: { ...connection, ...patch } });
  };
  const blueprintData = disambiguateChoices(
    schema.blueprints.map((candidate) => ({ value: candidate.identifier, label: candidate.title })),
  );
  const targetIsFixed = connection?.kind === "relation" || (connection?.kind === "ownership" && connection.direction === "out");

  return (
    <Fieldset legend={t("entityQuery.builder.connection")} style={{ minInlineSize: 0 }}>
      <Stack gap="sm">
        <Switch
          label={t("entityQuery.builder.addConnection")}
          checked={connection !== null}
          disabled={!model.blueprint}
          onChange={(event) => onModelChange({
            ...model,
            connection: event.currentTarget.checked ? connectionFor("relation") : null,
            returns: event.currentTarget.checked ? model.returns : "start",
          })}
        />
        {connection && (
          <>
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <Select
                label={t("entityQuery.builder.connectionType")}
                allowDeselect={false}
                data={[
                  { value: "relation", label: t("entityQuery.builder.connectionTypeRelation") },
                  { value: "ownership", label: t("entityQuery.builder.connectionTypeOwnership") },
                  { value: "hierarchy", label: t("entityQuery.builder.connectionTypeHierarchy") },
                ]}
                value={connection.kind}
                onChange={(value) => {
                  onModelChange({
                    ...model,
                    connection: connectionFor((value ?? "relation") as QueryBuilderConnection["kind"]),
                  });
                  touch("connection.name");
                  touch("connection.targetBlueprint");
                }}
              />
              {connection.kind === "relation" && (
                <Select
                  label={t("entityQuery.builder.relation")}
                  searchable
                  data={disambiguateChoices(Object.entries(blueprint?.relations ?? {}).map(([id, relation]) => ({
                    value: id,
                    label: relation.title,
                  })))}
                  value={connection.name || null}
                  error={errorText("connection.name")}
                  onChange={(value) => {
                    const relation = value ? blueprint?.relations[value] : undefined;
                    replace({ name: value ?? "", targetBlueprint: relation?.target ?? "", direction: "out", maxHops: "1" });
                    touch("connection.name");
                    touch("connection.targetBlueprint");
                  }}
                />
              )}
              {connection.kind === "ownership" && (
                <Select
                  label={t("entityQuery.builder.direction")}
                  allowDeselect={false}
                  data={[
                    { value: "out", label: t("entityQuery.builder.directionOutgoing") },
                    ...(model.blueprint === "_team" ? [{ value: "in", label: t("entityQuery.builder.directionIncoming") }] : []),
                  ]}
                  value={connection.direction}
                  error={errorText("connection.direction")}
                  onChange={(value) => replace({
                    direction: (value ?? "out") as "out" | "in",
                    targetBlueprint: value === "in" ? "" : "_team",
                    targetIdentifier: "",
                  })}
                />
              )}
              {connection.kind === "hierarchy" && (
                <Select
                  label={t("entityQuery.builder.hierarchy")}
                  searchable
                  data={schema.hierarchies}
                  value={connection.name || null}
                  error={errorText("connection.name")}
                  onChange={(value) => {
                    replace({ name: value ?? "" });
                    touch("connection.name");
                  }}
                />
              )}
              {connection.kind === "hierarchy" && (
                <Select
                  label={t("entityQuery.builder.direction")}
                  allowDeselect={false}
                  data={[
                    { value: "out", label: t("entityQuery.builder.directionParent") },
                    { value: "in", label: t("entityQuery.builder.directionChildren") },
                  ]}
                  value={connection.direction}
                  error={errorText("connection.direction")}
                  onChange={(value) => replace({ direction: (value ?? "out") as "out" | "in" })}
                />
              )}
              <Select
                label={t("entityQuery.builder.connectedType")}
                searchable
                clearable={!targetIsFixed}
                disabled={targetIsFixed}
                data={blueprintData}
                value={connection.targetBlueprint || null}
                error={errorText("connection.targetBlueprint")}
                onChange={(value) => {
                  replace({ targetBlueprint: value ?? "", targetIdentifier: value ? connection.targetIdentifier : "" });
                  touch("connection.targetBlueprint");
                }}
              />
              <TextInput
                label={t("entityQuery.builder.connectedIdentifier")}
                value={connection.targetIdentifier}
                error={errorText("connection.targetIdentifier")}
                onChange={(event) => {
                  replace({ targetIdentifier: event.currentTarget.value });
                  touch("connection.targetIdentifier");
                }}
              />
              {connection.kind === "hierarchy" && (
                <NumberInput
                  label={t("entityQuery.builder.maxHops")}
                  min={1}
                  max={10}
                  allowDecimal={false}
                  value={connection.maxHops === "" ? "" : Number(connection.maxHops)}
                  error={errorText("connection.maxHops")}
                  onChange={(value) => {
                    replace({ maxHops: value === "" ? "" : String(value) });
                    touch("connection.maxHops");
                  }}
                />
              )}
              <Switch
                label={t("entityQuery.builder.optionalConnection")}
                checked={connection.optional}
                style={{ minWidth: 0 }}
                styles={{ label: { whiteSpace: "normal", overflowWrap: "anywhere" } }}
                onChange={(event) => replace({ optional: event.currentTarget.checked })}
              />
            </SimpleGrid>
          </>
        )}
      </Stack>
    </Fieldset>
  );
}

export default function EntityQueryBuilderModal({
  opened,
  schema,
  onClose,
  onUse,
}: {
  opened: boolean;
  schema: QueryCompletionSchema;
  onClose: () => void;
  onUse: (query: string) => void;
}) {
  const { t } = useTranslation();
  const [model, setModel] = useState(createQueryBuilderModel);
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const properties = useMemo(() => getQueryBuilderProperties(schema, model.blueprint), [model.blueprint, schema]);
  const result = useMemo(() => buildQuery(model, schema), [model, schema]);
  const touch = (path: string) => setTouched((current) => new Set(current).add(path));
  const blueprintIssue = issueAt(result, "blueprint", touched);
  const limitIssue = issueAt(result, "limit", touched);
  const globalIssue = result.errors.find((issue) => issue.path === "query" || issue.path === "conditions");

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={t("entityQuery.builder.title")}
      size="xl"
      centered
    >
      <Stack style={{ minWidth: 0 }}>
        <Select
          style={{ minWidth: 0, maxWidth: "100%" }}
          label={t("entityQuery.builder.entityType")}
          searchable
          data={disambiguateChoices(
            schema.blueprints.map((blueprint) => ({ value: blueprint.identifier, label: blueprint.title })),
          )}
          value={model.blueprint || null}
          error={blueprintIssue ? t(ISSUE_KEYS[blueprintIssue]) : undefined}
          onChange={(value) => {
            setModel({ ...createQueryBuilderModel(), blueprint: value ?? "" });
            setTouched(new Set(["blueprint"]));
          }}
        />
        <ConditionsEditor
          model={model}
          result={result}
          properties={properties}
          touched={touched}
          onModelChange={setModel}
          touch={touch}
        />
        <ConnectionEditor
          model={model}
          result={result}
          schema={schema}
          touched={touched}
          onModelChange={setModel}
          touch={touch}
        />
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <Select
            label={t("entityQuery.builder.results")}
            allowDeselect={false}
            data={[
              { value: "start", label: t("entityQuery.builder.resultsStart") },
              ...(model.connection ? [
                { value: "target", label: t("entityQuery.builder.resultsTarget") },
                { value: "both", label: t("entityQuery.builder.resultsBoth") },
              ] : []),
            ]}
            value={model.returns}
            onChange={(value) => setModel({ ...model, returns: value as QueryBuilderModel["returns"] })}
          />
          <NumberInput
            label={t("entityQuery.builder.limit")}
            min={1}
            max={10_000}
            allowDecimal={false}
            value={model.limit === "" ? "" : Number(model.limit)}
            error={limitIssue ? t(ISSUE_KEYS[limitIssue]) : undefined}
            onChange={(value) => {
              setModel({ ...model, limit: value === "" ? "" : String(value) });
              touch("limit");
            }}
          />
        </SimpleGrid>
        {globalIssue && model.blueprint && (
          <Alert color="red" variant="light">{t(ISSUE_KEYS[globalIssue.code])}</Alert>
        )}
        <Stack gap={4}>
          <Text size="sm" fw={500}>{t("entityQuery.builder.preview")}</Text>
          {result.query ? (
            <Code
              block
              aria-label={t("entityQuery.builder.preview")}
              style={{ display: "block", maxWidth: "100%", overflowX: "auto" }}
            >
              {result.query}
            </Code>
          ) : (
            <Text size="sm" c="dimmed">{t("entityQuery.builder.emptyPreview")}</Text>
          )}
        </Stack>
        <Text size="sm" c="dimmed">{t("entityQuery.builder.replaceHint")}</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>{t("common.action.cancel")}</Button>
          <Button
            disabled={result.errors.length > 0 || result.query === ""}
            onClick={() => {
              onUse(result.query);
              onClose();
            }}
          >
            {t("entityQuery.builder.useQuery")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
