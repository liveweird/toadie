import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ActionIcon, Kbd, Text, UnstyledButton } from "@mantine/core";
import { useDebouncedValue, useOs } from "@mantine/hooks";
import { Spotlight, type SpotlightActionGroupData } from "@mantine/spotlight";
import { IconFileImport, IconPlus, IconSearch } from "@tabler/icons-react";
import { listCatalogFiles } from "../api/catalogFiles";
import { isAdmin } from "../api/session";
import { ACCOUNT_NAV, visibleSections } from "../utils/navigation";
import { editCatalogFilePath, importCatalogFilesPath, newCatalogFilePath } from "../utils/catalogFileLinks";
import { palette, paletteStore } from "../utils/commandPalette";
import KindBadge from "./KindBadge";
import classes from "../theme.module.css";

const MIN_QUERY = 2;
const DEBOUNCE_MS = 300;
const PAGE_SIZE = 10;

/**
 * The command palette (v1.19.0): ⌘K / Ctrl K, or the search-looking trigger in the header.
 * Three groups — every page the session may see (the same nav model as the sidebar plus the
 * account leaves), the two catalog actions, and catalog FILES matched server-side by name
 * through the ordinary list endpoint (debounced, two characters minimum, keyed under
 * `["catalogFiles", …]` so a mutation refreshes it like every other catalog query). A file
 * result opens its editor. Renders BOTH the trigger and the Spotlight, mounted once in the
 * shell header. No `highlightQuery`: it splits a label into `<mark>` + text fragments, and
 * tests/e2e locate results by their full name (the graph-node truncation rule again).
 */
export default function CommandPalette() {
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
    enabled: searching,
    placeholderData: keepPreviousData,
  });

  const go = (to: string) => {
    palette.close();
    navigate(to);
  };

  const pages: SpotlightActionGroupData = {
    group: t("appShell.palette.groupPages"),
    actions: [...visibleSections(isAdmin()).flatMap((section) => section.items), ...ACCOUNT_NAV].map(
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
    actions: [
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
    ],
  };
  const fileGroup: SpotlightActionGroupData[] =
    searching && files.data && files.data.items.length > 0
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

  const shortcut = os === "macos" ? "⌘ K" : "Ctrl K";
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
          {t("appShell.palette.placeholder")}
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
        actions={[pages, actions, ...fileGroup]}
        nothingFound={t("appShell.palette.nothingFound")}
        scrollable
        maxHeight={420}
        searchProps={{
          leftSection: <IconSearch size={18} stroke={1.5} />,
          placeholder: t("appShell.palette.placeholder"),
        }}
      />
    </>
  );
}
