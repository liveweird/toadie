import { useTranslation } from "react-i18next";
import { Alert, Stack, Text } from "@mantine/core";
import type { YamlDiff } from "../utils/yamlDiff";

// Scheme-aware via Mantine's light-variant tokens — no hand-picked dark-mode rgba.
const DIFF_COLORS = {
  removed: {
    background: "var(--mantine-color-red-light)",
    color: "var(--mantine-color-red-light-color)",
    prefix: "-",
  },
  added: {
    background: "var(--mantine-color-teal-light)",
    color: "var(--mantine-color-teal-light-color)",
    prefix: "+",
  },
  same: { background: "transparent", color: "inherit", prefix: " " },
} as const;

// Shared keyboard-scrollable treatment for both detailed lines and complete-document fallback.
const SCROLL_STYLE = {
  fontFamily: "var(--mantine-font-family-monospace)",
  fontSize: "var(--mantine-font-size-xs)",
  maxHeight: 360,
  overflow: "auto",
  border: "1px solid light-dark(var(--mantine-color-gray-3), var(--mantine-color-dark-4))",
  borderRadius: "var(--mantine-radius-sm)",
  padding: 8,
} as const;

function CompleteYaml({ yaml, label }: { yaml: string; label: string }) {
  return (
    <Stack gap={4}>
      <Text size="xs" fw={600}>
        {label}
      </Text>
      <Stack gap={0} role="group" aria-label={label} tabIndex={0} style={SCROLL_STYLE}>
        <Text component="pre" size="xs" m={0} style={{ whiteSpace: "pre-wrap", fontFamily: "inherit" }}>
          {yaml}
        </Text>
      </Stack>
    </Stack>
  );
}

/**
 * A bounded line diff, or two complete documents when detailed comparison would be too costly.
 * Add/remove prefixes keep the detailed signal non-color-only; every scroll region is named and
 * focusable for keyboard users.
 */
export default function YamlDiffView({ diff, label }: { diff: YamlDiff; label: string }) {
  const { t } = useTranslation();
  if (diff.kind === "fallback") {
    return (
      <Stack gap="xs">
        <Alert color="orange" variant="light">
          {t("catalog.diff.tooLarge")}
        </Alert>
        <CompleteYaml yaml={diff.before} label={t("catalog.diff.storedLabel")} />
        <CompleteYaml yaml={diff.after} label={t("catalog.diff.replacementLabel")} />
      </Stack>
    );
  }
  return (
    <Stack
      gap={0}
      role="group"
      aria-label={label}
      tabIndex={0}
      style={SCROLL_STYLE}
    >
      {diff.lines.map((line, index) => (
        <Text
          key={index}
          component="pre"
          size="xs"
          m={0}
          style={{
            whiteSpace: "pre-wrap",
            backgroundColor: DIFF_COLORS[line.kind].background,
            color: DIFF_COLORS[line.kind].color,
            fontFamily: "inherit",
          }}
        >
          {`${DIFF_COLORS[line.kind].prefix} ${line.text}`}
        </Text>
      ))}
    </Stack>
  );
}
