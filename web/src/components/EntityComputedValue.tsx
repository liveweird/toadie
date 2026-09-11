import { Badge, Code, Group, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ComputedDefinition } from "../utils/computedProperties";
import { portColor } from "../utils/portColors";

/**
 * Renders one computed (mirror/calculation/aggregation) property VALUE — read-only, shared by
 * the entity editor's Computed fieldset (`EntityComputedFieldset`) and the entities list's
 * preview columns (`pages/Entities.tsx`). The server evaluates an unresolvable mirror/
 * calculation/aggregation to ABSENT, never `null` or a thrown error (`.claude/docs/port-data-
 * model.md` "Computed properties"), so absent shows the dimmed localized "Not available"
 * placeholder rather than the ordinary property table's bare dash — it reads distinctly from
 * an entity that simply never set an ordinary property.
 */
export default function EntityComputedValue({
  value,
  definition,
}: {
  value: unknown;
  definition: ComputedDefinition;
}) {
  const { t } = useTranslation();

  if (value === undefined || value === null) {
    return <Text c="dimmed">{t("entities.computed.absent")}</Text>;
  }

  if (typeof value === "boolean") {
    return (
      <Badge variant="light" color={value ? "green" : "gray"}>
        {value ? t("entities.field.true") : t("entities.field.false")}
      </Badge>
    );
  }

  if (Array.isArray(value)) {
    return (
      <Group gap={4}>
        {value.map((item, index) =>
          typeof item === "object" && item !== null ? (
            <Code key={index}>{JSON.stringify(item)}</Code>
          ) : (
            <Badge key={index} variant="light" color="gray">
              {String(item)}
            </Badge>
          ),
        )}
      </Group>
    );
  }

  if (typeof value === "object") {
    return <Code block>{JSON.stringify(value, null, 2)}</Code>;
  }

  if (definition.colorized) {
    return (
      <Badge variant="light" color={portColor(definition.colors?.[String(value)])}>
        {String(value)}
      </Badge>
    );
  }

  return <Text size="sm">{String(value)}</Text>;
}
