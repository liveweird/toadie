import { Badge, Box, Button, Group, Stack, Text, Tooltip } from "@mantine/core";
import { IconPlayerPlay, IconX } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { EntityQueryDiagnostic } from "../api/entities";
import type { QueryCompletionSchema } from "../utils/queryCompletion";
import EntityQueryPicker from "./EntityQueryPicker";
import QueryEditor from "./QueryEditor";
import classes from "../theme.module.css";

/** `⌘` on a mac-reported platform, `Ctrl` everywhere else — the same distinction
 *  `QueryEditor`'s "Mod-Enter" binding resolves to at runtime. */
function modKeyLabel(): string {
  return typeof navigator !== "undefined" && /Mac/.test(navigator.platform) ? "⌘" : "Ctrl";
}

/**
 * The entity query bar (phase 7, v2.0.0 — `.claude/docs/entity-query-language.md`): the
 * CodeMirror editor plus Run (with its Mod+Enter hint)/Clear, the "Applied · N entities" badge
 * once a query narrows the canvas, and the diagnostics list underneath — the SAME diagnostics
 * whether they came from the live `/query/check` (while typing) or the last failed graph
 * request (the page decides which to pass down). Rendered identically by the Entity graph and
 * Entity hierarchy pages, through `EntityGraphToolbar`.
 */
export default function EntityQueryBar({
  value,
  onChange,
  onRun,
  onClear,
  diagnostics,
  completionSchema,
  appliedCount,
  draft,
  onPick,
}: {
  value: string;
  onChange: (value: string) => void;
  onRun: () => void;
  onClear: () => void;
  diagnostics: EntityQueryDiagnostic[];
  completionSchema: QueryCompletionSchema;
  /** Set once a query is applied and the graph loaded successfully; the shown entity count. */
  appliedCount?: number;
  /** The bar's current draft text — the saved-query picker's "Modified" comparison. */
  draft: string;
  /** Applies a saved query's text to BOTH the draft and the applied query (`useEntityQuery`'s
   *  `runText`). */
  onPick: (text: string) => void;
}) {
  const { t } = useTranslation();

  return (
    <Stack gap={4}>
      <Group gap="xs" wrap="nowrap">
        <EntityQueryPicker draft={draft} onPick={onPick} />
      </Group>
      <Group gap="xs" align="flex-start" wrap="nowrap">
        <Box className={classes.queryEditor} style={{ flex: 1, minWidth: 0 }}>
          <QueryEditor
            value={value}
            onChange={onChange}
            onRun={() => {
              // The keyboard shortcut shares the Run button's blank guard.
              if (value.trim() !== "") onRun();
            }}
            diagnostics={diagnostics}
            completionSchema={completionSchema}
            ariaLabel={t("entityQuery.label")}
            placeholder={t("entityQuery.placeholder")}
          />
        </Box>
        <Tooltip label={t("entityQuery.runHint", { key: modKeyLabel() })}>
          <Button
            size="sm"
            leftSection={<IconPlayerPlay size={14} />}
            onClick={onRun}
            disabled={value.trim() === ""}
          >
            {t("entityQuery.run")}
          </Button>
        </Tooltip>
        <Button
          size="sm"
          variant="default"
          leftSection={<IconX size={14} />}
          onClick={onClear}
          disabled={value === ""}
        >
          {t("entityQuery.clear")}
        </Button>
        {appliedCount != null && (
          <Badge data-testid="entityQuery-applied" variant="light" color="gray" size="lg" tt="none" style={{ flexShrink: 0 }}>
            {t("entityQuery.applied")} · {t("entityQuery.appliedCount", { count: appliedCount })}
          </Badge>
        )}
      </Group>
      {diagnostics.length > 0 && (
        <Stack gap={2} role="list" aria-label={t("entityQuery.diagnosticsTitle")}>
          {diagnostics.map((diagnostic, index) => (
            // Diagnostics carry no stable id (server order is the only identity); the list is
            // rebuilt wholesale on every check/run, so an index key is safe here.
            <Text key={index} role="listitem" size="xs" c="red">
              {diagnostic.line != null && diagnostic.column != null && (
                <Text component="span" c="dimmed" mr={4}>
                  {t("entityQuery.position", { line: diagnostic.line, column: diagnostic.column })}
                </Text>
              )}
              {diagnostic.message}
              {diagnostic.suggestion && (
                <Text component="span" c="dimmed" ml={4}>
                  {t("entityQuery.didYouMean", { name: diagnostic.suggestion })}
                </Text>
              )}
            </Text>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
