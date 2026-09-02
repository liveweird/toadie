import type { ParseKeys } from "i18next";
import {
  IconCategory,
  IconFileDescription,
  IconFolders,
  IconHash,
  IconHistory,
  IconKey,
  IconListCheck,
  IconNote,
  IconRecycle,
  IconSitemap,
  IconTag,
  IconToggleLeft,
  IconTopologyStar3,
  IconUsers,
  type Icon,
} from "@tabler/icons-react";
import { catalogFilesPath } from "./catalogFileLinks";

export type NavLeaf = {
  to: string;
  /** An i18n key, resolved with t() at render time. */
  label: ParseKeys;
  icon: Icon;
  /** When set, the leaf renders only for ADMIN sessions. */
  adminOnly?: boolean;
};

/** A labelled, always-open block of leaves — a section, never a collapsible group. */
export type NavSection = {
  label: ParseKeys;
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
    items: [
      { to: "/", label: "appShell.nav.home", icon: IconSitemap },
      { to: catalogFilesPath, label: "appShell.nav.catalogFiles", icon: IconFileDescription },
      { to: "/errors", label: "appShell.nav.errors", icon: IconListCheck },
      { to: "/graph", label: "appShell.nav.graph", icon: IconTopologyStar3 },
    ],
  },
  {
    label: "appShell.section.registries",
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
    label: "appShell.section.administration",
    items: [
      { to: "/users", label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
      { to: "/feature-flags", label: "appShell.nav.featureFlags", icon: IconToggleLeft, adminOnly: true },
    ],
  },
];

/** Account-scoped leaves: the header user menu and the palette render them, the sidebar never. */
export const ACCOUNT_NAV: ReadonlyArray<NavLeaf> = [
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
  { to: "/changelog", label: "appShell.nav.changelog", icon: IconHistory },
];

/** The sections a session may see: admin-only leaves filtered, empty sections dropped. */
export function visibleSections(admin: boolean): NavSection[] {
  return NAV_SECTIONS.flatMap((section) => {
    const items = section.items.filter((leaf) => !leaf.adminOnly || admin);
    return items.length > 0 ? [{ ...section, items }] : [];
  });
}

/** Longest-matching-prefix active-link resolution — "/" only matches exactly. */
export function activeNavPath(pathname: string, leaves: ReadonlyArray<NavLeaf>): string | null {
  const matches = (to: string) =>
    to === "/" ? pathname === "/" : pathname === to || pathname.startsWith(`${to}/`);
  return (
    leaves
      .map((leaf) => leaf.to)
      .filter(matches)
      .sort((a, b) => b.length - a.length)[0] ?? null
  );
}
