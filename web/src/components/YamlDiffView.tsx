import { Stack, Text } from "@mantine/core";
import type { DiffLine } from "../utils/yamlDiff";

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

/**
 * The sync modal's line-diff pane: monospace −/+ rows in a bordered scroll region. The
 * add/remove signal is never color-only — the prefix travels in the same text node, so
 * assistive tech announces it. Focusable (`tabIndex`) so a keyboard user can scroll a
 * long diff, and named for AT via role="group" (aria-label on a bare div is ignored).
 */
export default function YamlDiffView({ diff, label }: { diff: DiffLine[]; label: string }) {
  return (
    <Stack
      gap={0}
      role="group"
      aria-label={label}
      tabIndex={0}
      style={{
        fontFamily: "var(--mantine-font-family-monospace)",
        fontSize: "var(--mantine-font-size-xs)",
        maxHeight: 360,
        overflow: "auto",
        border: "1px solid light-dark(var(--mantine-color-gray-3), var(--mantine-color-dark-4))",
        borderRadius: "var(--mantine-radius-sm)",
        padding: 8,
      }}
    >
      {diff.map((line, index) => (
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
