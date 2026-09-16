import { useState } from "react";
import { Box, Button, Group, Stack, Text, Tooltip } from "@mantine/core";
import { IconGitBranch, IconPlayerPlay, IconX } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { EntityQueryDiagnostic } from "../api/entities";
import type { QueryCompletionSchema } from "../utils/queryCompletion";
import EntityQueryPicker from "./EntityQueryPicker";
import EntityQueryBuilderModal from "./EntityQueryBuilderModal";
import QueryEditor from "./QueryEditor";
import classes from "../theme.module.css";

/** `⌘` on a mac-reported platform, `Ctrl` everywhere else — the same distinction
 *  `QueryEditor`'s "Mod-Enter" binding resolves to at runtime. */
function modKeyLabel(): string {
  return typeof navigator !== "undefined" && /Mac/.test(navigator.platform) ? "⌘" : "Ctrl";
}

/**
 * The entity query bar (phase 7, v2.0.0 — `.claude/docs/entity-query-language.md`): the
 * CodeMirror editor plus Run (with its Mod+Enter hint)/Clear and the diagnostics list
 * underneath — the SAME diagnostics whether they came from the live `/query/check` (while
 * typing) or the last failed graph request (the page decides which to pass down). Rendered
 * identically by the Entity graph and Entity hierarchy pages, inside `EntityGraphToolbar`'s
 * collapsible Query section. Since 2.4.1 the "Applied · N entities" badge lives on the section's
 * OWN toggle (visible while collapsed too) rather than in this bar — `appliedCount` is gone, and
 * `applied` (the currently-narrowing text, `""` when none) instead decides Clear's disabled
 * state alongside the draft: Clear stays available whenever there is either a draft to erase or
 * an applied query still in force, even after the draft itself was erased by hand (the blank-
 * draft-clears-applied invariant lives in `useEntityQuery`, not here).
 */
export default function EntityQueryBar({
  value,
  onChange,
  onRun,
  onClear,
  diagnostics,
  completionSchema,
  applied,
  draft,
  onPick,
}: {
  value: string;
  onChange: (value: string) => void;
  onRun: () => void;
  onClear: () => void;
  diagnostics: EntityQueryDiagnostic[];
  completionSchema: QueryCompletionSchema;
  /** The currently applied (narrowing) query text; `""` when none is applied. */
  applied: string;
  /** The bar's current draft text — the saved-query picker's "Modified" comparison. */
  draft: string;
  /** Applies a saved query's text to BOTH the draft and the applied query (`useEntityQuery`'s
   *  `runText`). */
  onPick: (text: string) => void;
}) {
  const { t } = useTranslation();
  const [builderOpened, setBuilderOpened] = useState(false);

  return (
    <Stack gap={4}>
      <Group gap="xs" wrap="wrap">
        <EntityQueryPicker draft={draft} onPick={onPick} />
        <Button
          size="xs"
          variant="default"
          leftSection={<IconGitBranch size={14} />}
          onClick={() => setBuilderOpened(true)}
        >
          {t("entityQuery.builder.open")}
        </Button>
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
          disabled={value === "" && applied === ""}
        >
          {t("entityQuery.clear")}
        </Button>
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
      <EntityQueryBuilderModal
        opened={builderOpened}
        schema={completionSchema}
        onClose={() => setBuilderOpened(false)}
        onUse={onChange}
      />
    </Stack>
  );
}
