import type { ParseKeys } from "i18next";
import {
  IconBox,
  IconCategory,
  IconFileDescription,
  IconFileImport,
  IconFolders,
  IconHash,
  IconHistory,
  IconKey,
  IconListCheck,
  IconNote,
  IconRecycle,
  IconSchema,
  IconBinaryTree2,
  IconSitemap,
  IconTag,
  IconToggleLeft,
  IconTopologyStar3,
  IconUsers,
  type Icon,
} from "@tabler/icons-react";
import { blueprintsPath } from "./blueprintLinks";
import { catalogFilesPath } from "./catalogFileLinks";
import { entitiesBasePath, entityGraphPath, entityHierarchyPath } from "./entityLinks";
import { ontologyErrorsPath, ontologyImportPath } from "./ontologyLinks";

/**
 * The two product worlds (v2.3.0): Backstage's catalog files and Port's ontology. The sidebar
 * shows ONE world at a time — the switch at its top, the world DERIVED from the route
 * (`worldOf`), the global Administration/account leaves in both. `/` and the brand open the last
 * used world's home (`hooks/useWorld.ts`); a first-ever visit lands on Port, the direction of travel.
 */
export type World = "backstage" | "port";
export const WORLDS = ["backstage", "port"] as const;
export const DEFAULT_WORLD: World = "port";
export const isWorld = (v: unknown): v is World => v === "backstage" || v === "port";

/** The catalog Hierarchy's route (v2.3.0 — moved off `/`, which now redirects per world). */
export const hierarchyPath = "/hierarchy";

export type WorldInfo = { label: ParseKeys; home: string; icon: Icon };
export const WORLD_INFO: Record<World, WorldInfo> = {
  backstage: { label: "appShell.world.backstage", home: hierarchyPath, icon: IconSitemap },
  port: { label: "appShell.world.port", home: entityHierarchyPath, icon: IconSchema },
};
export const homeOf = (world: World): string => WORLD_INFO[world].home;

export type NavLeaf = {
  to: string;
  /** An i18n key, resolved with t() at render time. */
  label: ParseKeys;
  icon: Icon;
  /** When set, the leaf renders only for ADMIN sessions. */
  adminOnly?: boolean;
};

/** A labelled, always-open block of leaves — a section, never a collapsible group — belonging
 *  to one world, or `"global"` (rendered in both). */
export type NavSection = {
  label: ParseKeys;
  world: World | "global";
  items: ReadonlyArray<NavLeaf>;
};

/**
 * The navigation model (v1.19.0), shared by the sidebar and the command palette. Sections
 * replaced the former collapsible Dictionaries/Metadata groups: every leaf is always in the
 * DOM, so tests and deep links address the links directly, and a static label costs less
 * vertical space than a toggle. Registry leaves are visible to everyone (non-admins get the
 * read-only lists); the Administration section is the ADMIN management surface.
 */
const NAV_SECTIONS: ReadonlyArray<NavSection> = [
  {
    label: "appShell.section.catalog",
    world: "backstage",
    items: [
      { to: hierarchyPath, label: "appShell.nav.home", icon: IconSitemap },
      { to: catalogFilesPath, label: "appShell.nav.catalogFiles", icon: IconFileDescription },
      { to: "/errors", label: "appShell.nav.errors", icon: IconListCheck },
      { to: "/graph", label: "appShell.nav.graph", icon: IconTopologyStar3 },
    ],
  },
  {
    label: "appShell.section.catalogDictionaries",
    world: "backstage",
    items: [
      { to: "/namespaces", label: "appShell.nav.namespaces", icon: IconFolders },
      { to: "/types", label: "appShell.nav.types", icon: IconCategory },
      { to: "/lifecycles", label: "appShell.nav.lifecycles", icon: IconRecycle },
      { to: "/labels", label: "appShell.nav.labels", icon: IconTag },
      { to: "/tags", label: "appShell.nav.tags", icon: IconHash },
      { to: "/annotations", label: "appShell.nav.annotations", icon: IconNote },
    ],
  },
  {
    label: "appShell.section.dataModel",
    world: "port",
    items: [
      { to: blueprintsPath, label: "appShell.nav.blueprints", icon: IconSchema },
      { to: entitiesBasePath, label: "appShell.nav.entities", icon: IconBox },
      { to: entityGraphPath, label: "appShell.nav.entityGraph", icon: IconTopologyStar3 },
      { to: entityHierarchyPath, label: "appShell.nav.entityHierarchy", icon: IconSitemap },
      { to: ontologyErrorsPath, label: "appShell.nav.ontologyErrors", icon: IconListCheck },
      { to: ontologyImportPath, label: "appShell.nav.ontologyImport", icon: IconFileImport },
    ],
  },
  {
    label: "appShell.section.portDictionaries",
    world: "port",
    items: [{ to: "/hierarchies", label: "appShell.nav.hierarchies", icon: IconBinaryTree2 }],
  },
  {
    label: "appShell.section.administration",
    world: "global",
    items: [
      { to: "/users", label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
      { to: "/feature-flags", label: "appShell.nav.featureFlags", icon: IconToggleLeft, adminOnly: true },
      { to: "/integration-clients", label: "appShell.nav.integrationClients", icon: IconKey, adminOnly: true },
    ],
  },
];

/** Account-scoped leaves: the header user menu and the palette render them, the sidebar never. */
export const ACCOUNT_NAV: ReadonlyArray<NavLeaf> = [
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
  { to: "/changelog", label: "appShell.nav.changelog", icon: IconHistory },
];

/** The sections a session sees in `world`: that world's plus the global ones, admin-only
 *  leaves filtered, empty sections dropped. */
export function sectionsFor(world: World, admin: boolean): NavSection[] {
  return NAV_SECTIONS.flatMap((section) => {
    if (section.world !== world && section.world !== "global") return [];
    const items = section.items.filter((leaf) => !leaf.adminOnly || admin);
    return items.length > 0 ? [{ ...section, items }] : [];
  });
}

/** Longest-matching-prefix active-link resolution over `leaves` (a leaf matches the exact path
 *  or a sub-path of it, so `/hierarchies` never matches `/hierarchy`). */
export function activeNavPath(pathname: string, leaves: ReadonlyArray<NavLeaf>): string | null {
  const matches = (to: string) => pathname === to || pathname.startsWith(`${to}/`);
  return (
    leaves
      .map((leaf) => leaf.to)
      .filter(matches)
      .sort((a, b) => b.length - a.length)[0] ?? null
  );
}

const ALL_LEAVES: ReadonlyArray<NavLeaf> = NAV_SECTIONS.flatMap((section) => section.items);
const WORLD_BY_PATH = new Map<string, World | "global">(
  NAV_SECTIONS.flatMap((section) => section.items.map((leaf) => [leaf.to, section.world] as const)),
);

/** The world a route belongs to (longest-prefix over every leaf, admin-agnostic); `null` for
 *  global pages (`/users`, `/changelog`, …), `/` and unknown paths — the caller keeps the last
 *  remembered world there. */
export function worldOf(pathname: string): World | null {
  const active = activeNavPath(pathname, ALL_LEAVES);
  const world = active === null ? undefined : WORLD_BY_PATH.get(active);
  return world === undefined || world === "global" ? null : world;
}
