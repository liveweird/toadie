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
  preview = false,
}: {
  value: unknown;
  definition: ComputedDefinition;
  preview?: boolean;
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
      <Group gap={4} style={preview ? { minWidth: 0 } : undefined}>
        {value.map((item, index) => {
          const rendered = typeof item === "object" && item !== null ? JSON.stringify(item) : String(item);
          return typeof item === "object" && item !== null ? (
            <Code
              key={index}
              title={preview ? rendered : undefined}
              style={preview ? { display: "block", maxWidth: "100%", overflowX: "auto", whiteSpace: "nowrap" } : undefined}
            >
              {rendered}
            </Code>
          ) : (
            <Badge
              key={index}
              variant="light"
              color="gray"
              title={preview ? rendered : undefined}
              style={preview ? { maxWidth: "100%" } : undefined}
            >
              {rendered}
            </Badge>
          );
        })}
      </Group>
    );
  }

  if (typeof value === "object") {
    const rendered = JSON.stringify(value, null, 2);
    return (
      <Code
        block
        title={preview ? rendered : undefined}
        style={preview ? { maxWidth: "100%", overflowX: "auto" } : undefined}
      >
        {rendered}
      </Code>
    );
  }

  if (definition.colorized) {
    const rendered = String(value);
    return (
      <Badge
        variant="light"
        color={portColor(definition.colors?.[rendered])}
        title={preview ? rendered : undefined}
        style={preview ? { maxWidth: "100%" } : undefined}
      >
        {rendered}
      </Badge>
    );
  }

  const rendered = String(value);
  return (
    <Text size="sm" truncate={preview ? "end" : undefined} title={preview ? rendered : undefined}>
      {rendered}
    </Text>
  );
}
