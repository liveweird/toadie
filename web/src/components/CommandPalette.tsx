import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { ParseKeys } from "i18next";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ActionIcon, Kbd, Text, UnstyledButton } from "@mantine/core";
import { useDebouncedValue, useOs } from "@mantine/hooks";
import { Spotlight, type SpotlightActionGroupData } from "@mantine/spotlight";
import { IconBox, IconFileImport, IconPlus, IconSearch } from "@tabler/icons-react";
import { listCatalogFiles } from "../api/catalogFiles";
import { listEntities } from "../api/entities";
import { isAdmin } from "../api/session";
import { ACCOUNT_NAV, sectionsFor, type World } from "../utils/navigation";
import { editCatalogFilePath, importCatalogFilesPath, newCatalogFilePath } from "../utils/catalogFileLinks";
import { editEntityPath, entitiesPath } from "../utils/entityLinks";
import { ontologyImportPath } from "../utils/ontologyLinks";
import { palette, paletteStore } from "../utils/commandPalette";
import KindBadge from "./KindBadge";
import classes from "../theme.module.css";

const MIN_QUERY = 2;
const DEBOUNCE_MS = 300;
const PAGE_SIZE = 10;

/** The trigger text/placeholder per world — literal keys so `locales/unusedKeys.test.ts` can
 *  see them referenced (a computed template string would not). */
const PLACEHOLDER: Record<World, ParseKeys> = {
  backstage: "appShell.palette.placeholderBackstage",
  port: "appShell.palette.placeholderPort",
};

/**
 * The command palette (v1.19.0, world-scoped since v2.3.0): ⌘K / Ctrl K, or the search-looking
 * trigger in the header. Three groups, all scoped to the CURRENT world — every page the session
 * may see in `world` (the same nav model as the sidebar plus the account leaves), that world's
 * quick actions, and that world's own content searched server-side (catalog FILES by name in
 * Backstage, ENTITIES by identifier/title in Port — both debounced, two characters minimum,
 * keyed under their feature's own query prefix so a mutation refreshes them like every other
 * list). A result opens its editor. Renders BOTH the trigger and the Spotlight, mounted once in
 * the shell header with the active world. No `highlightQuery`: it splits a label into `<mark>` +
 * text fragments, and tests/e2e locate results by their full name (the graph-node truncation
 * rule again).
 */
export default function CommandPalette({ world }: { world: World }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const os = useOs();
  const [opened, setOpened] = useState(false);
  const [query, setQuery] = useState("");
  const [debounced] = useDebouncedValue(query.trim(), DEBOUNCE_MS);
  const searching = opened && debounced.length >= MIN_QUERY;

  const files = useQuery({
    queryKey: ["catalogFiles", "palette", debounced],
    queryFn: () => listCatalogFiles({ page: 1, pageSize: PAGE_SIZE, sort: "name", name: debounced }),
    enabled: searching && world === "backstage",
    placeholderData: keepPreviousData,
  });

  const entities = useQuery({
    queryKey: ["entities", "palette", debounced],
    queryFn: () => listEntities({ q: debounced, page: 1, pageSize: PAGE_SIZE, sort: "identifier" }),
    enabled: searching && world === "port",
    placeholderData: keepPreviousData,
  });

  const go = (to: string) => {
    palette.close();
    navigate(to);
  };

  const pages: SpotlightActionGroupData = {
    group: t("appShell.palette.groupPages"),
    actions: [...sectionsFor(world, isAdmin()).flatMap((section) => section.items), ...ACCOUNT_NAV].map(
      (leaf) => {
        const Icon = leaf.icon;
        return {
          id: `page:${leaf.to}`,
          label: t(leaf.label),
          leftSection: <Icon size={18} stroke={1.5} />,
          onClick: () => go(leaf.to),
        };
      },
    ),
  };
  const actions: SpotlightActionGroupData = {
    group: t("appShell.palette.groupActions"),
    actions:
      world === "backstage"
        ? [
            {
              id: "action:new-file",
              label: t("catalog.createFile"),
              leftSection: <IconPlus size={18} stroke={1.5} />,
              onClick: () => go(newCatalogFilePath),
            },
            {
              id: "action:import",
              label: t("catalog.import.title"),
              leftSection: <IconFileImport size={18} stroke={1.5} />,
              onClick: () => go(importCatalogFilesPath),
            },
          ]
        : [
            {
              id: "action:new-entity",
              label: t("entities.createEntity"),
              description: t("appShell.palette.newEntityHint"),
              leftSection: <IconPlus size={18} stroke={1.5} />,
              onClick: () => go(entitiesPath()),
            },
            {
              id: "action:import-ontology",
              label: t("ontology.import.title"),
              leftSection: <IconFileImport size={18} stroke={1.5} />,
              onClick: () => go(ontologyImportPath),
            },
          ],
  };
  const fileGroup: SpotlightActionGroupData[] =
    world === "backstage" && searching && files.data && files.data.items.length > 0
      ? [
          {
            group: t("appShell.palette.groupFiles"),
            actions: files.data.items.map((file) => ({
              id: `file:${file.id}`,
              label: file.name,
              description: `${file.kind} · ${file.namespace}`,
              leftSection: <KindBadge kind={file.kind} size="xs" />,
              onClick: () => go(editCatalogFilePath(file.id)),
            })),
          },
        ]
      : [];
  const entityGroup: SpotlightActionGroupData[] =
    world === "port" && searching && entities.data && entities.data.items.length > 0
      ? [
          {
            group: t("appShell.palette.groupEntities"),
            actions: entities.data.items.map((entity) => ({
              id: `entity:${entity.id}`,
              label: entity.identifier,
              description: `${entity.blueprint} · ${entity.title}`,
              leftSection: <IconBox size={18} stroke={1.5} />,
              onClick: () => go(editEntityPath(entity.id)),
            })),
          },
        ]
      : [];

  const shortcut = os === "macos" ? "⌘ K" : "Ctrl K";
  const placeholder = t(PLACEHOLDER[world]);
  return (
    <>
      <UnstyledButton
        className={classes.paletteTrigger}
        visibleFrom="sm"
        aria-label={t("appShell.palette.open")}
        onClick={palette.open}
      >
        <IconSearch size={16} />
        <Text component="span" size="sm" inherit>
          {placeholder}
        </Text>
        <Kbd size="xs">{shortcut}</Kbd>
      </UnstyledButton>
      <ActionIcon hiddenFrom="sm" size="lg" aria-label={t("appShell.palette.open")} onClick={palette.open}>
        <IconSearch size={18} />
      </ActionIcon>
      <Spotlight
        store={paletteStore}
        shortcut="mod + K"
        query={query}
        onQueryChange={setQuery}
        onSpotlightOpen={() => setOpened(true)}
        onSpotlightClose={() => setOpened(false)}
        actions={[pages, actions, ...fileGroup, ...entityGroup]}
        nothingFound={t("appShell.palette.nothingFound")}
        scrollable
        maxHeight={420}
        searchProps={{
          leftSection: <IconSearch size={18} stroke={1.5} />,
          placeholder,
        }}
      />
    </>
  );
}
