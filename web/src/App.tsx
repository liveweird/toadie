import type { ParseKeys } from "i18next";
import { lazy, Suspense } from "react";
import {
  ActionIcon,
  AppShell,
  Burger,
  Center,
  Group,
  Indicator,
  Loader,
  NavLink,
  ScrollArea,
  Text,
  useComputedColorScheme,
  useMantineColorScheme,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import {
  IconFileDescription,
  IconFolders,
  IconHash,
  IconHistory,
  IconSitemap,
  IconKey,
  IconListCheck,
  IconCategory,
  IconNote,
  IconRecycle,
  IconTag,
  IconLogout,
  IconMoon,
  IconSun,
  IconTopologyStar3,
  IconToggleLeft,
  IconUsers,
} from "@tabler/icons-react";
import {
  Link as RouterLink,
  Outlet,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { logout } from "./api/auth";
import { isAdmin } from "./api/session";
import { RedirectIfAuthed, RequireAuth, flagSignedOut, notifyAuthChange } from "./auth";
import BrandLogo from "./components/BrandLogo";
import LanguageSwitcher from "./components/LanguageSwitcher";
import VersionStamp from "./components/VersionStamp";
import { useChangelogUnseen } from "./hooks/useChangelogSeen";
import { RouteErrorBoundary } from "./components/ErrorBoundary";

const Login = lazy(() => import("./pages/Login"));
const ResetPassword = lazy(() => import("./pages/ResetPassword"));
const Hierarchy = lazy(() => import("./pages/Hierarchy"));
const CatalogFiles = lazy(() => import("./pages/CatalogFiles"));
const CreateCatalogFile = lazy(() => import("./pages/CreateCatalogFile"));
const ImportCatalogFiles = lazy(() => import("./pages/ImportCatalogFiles"));
const EditCatalogFile = lazy(() => import("./pages/EditCatalogFile"));
const CrossCheck = lazy(() => import("./pages/CrossCheck"));
const Namespaces = lazy(() => import("./pages/Namespaces"));
const Labels = lazy(() => import("./pages/Labels"));
const Tags = lazy(() => import("./pages/Tags"));
const Types = lazy(() => import("./pages/Types"));
const Lifecycles = lazy(() => import("./pages/Lifecycles"));
const Annotations = lazy(() => import("./pages/Annotations"));
const Users = lazy(() => import("./pages/Users"));
const UserFeatures = lazy(() => import("./pages/UserFeatures"));
const FeatureFlags = lazy(() => import("./pages/FeatureFlags"));
const CreateUser = lazy(() => import("./pages/CreateUser"));
const EditUser = lazy(() => import("./pages/EditUser"));
const ChangePassword = lazy(() => import("./pages/ChangePassword"));
const RenderGraph = lazy(() => import("./pages/RenderGraph"));
const Changelog = lazy(() => import("./pages/Changelog"));
const NotFound = lazy(() => import("./pages/NotFound"));

function RouteFallback() {
  return (
    <Center mih={200}>
      <Loader />
    </Center>
  );
}

type NavLeaf = {
  to: string;
  label: ParseKeys;
  icon: typeof IconSitemap;
  // When set, the leaf renders only for ADMIN sessions (the Lettuce feature/role-filter
  // machinery slots back in here as features arrive).
  adminOnly?: boolean;
};

// `label` holds an i18n key, resolved with t() at render time. Feature entries (catalog
// files, cross-checks, rendering) append here as they are built.
const NAV_ITEMS: ReadonlyArray<NavLeaf> = [
  { to: "/", label: "appShell.nav.home", icon: IconSitemap },
  { to: "/catalog-files", label: "appShell.nav.catalogFiles", icon: IconFileDescription },
  { to: "/cross-check", label: "appShell.nav.crossCheck", icon: IconListCheck },
  { to: "/render", label: "appShell.nav.render", icon: IconTopologyStar3 },
  // Visible to everyone: non-admins get the read-only list, ADMINs the editor (the page branches).
  { to: "/namespaces", label: "appShell.nav.namespaces", icon: IconFolders },
  { to: "/labels", label: "appShell.nav.labels", icon: IconTag },
  { to: "/tags", label: "appShell.nav.tags", icon: IconHash },
  { to: "/types", label: "appShell.nav.types", icon: IconCategory },
  { to: "/lifecycles", label: "appShell.nav.lifecycles", icon: IconRecycle },
  { to: "/annotations", label: "appShell.nav.annotations", icon: IconNote },
  { to: "/users", label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
  { to: "/feature-flags", label: "appShell.nav.featureFlags", icon: IconToggleLeft, adminOnly: true },
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
];

// Rendered last, directly above the version stamp it pairs with, rather than inside
// NAV_ITEMS (the Lettuce placement).
const CHANGELOG_NAV: NavLeaf = { to: "/changelog", label: "appShell.nav.changelog", icon: IconHistory };

function ColorSchemeToggle() {
  const { t } = useTranslation();
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme("light", { getInitialValueInEffect: true });
  const next = computed === "dark" ? "light" : "dark";
  return (
    <ActionIcon
      variant="subtle"
      color="gray"
      size="lg"
      aria-label={t("appShell.toggleColorScheme")}
      onClick={() => setColorScheme(next)}
    >
      {computed === "dark" ? <IconSun size={18} /> : <IconMoon size={18} />}
    </ActionIcon>
  );
}

function Shell() {
  const { t } = useTranslation();
  const [opened, { toggle, close }] = useDisclosure();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { pathname } = useLocation();

  // Admin-gated leaves render only for ADMIN sessions (the routes are guarded too).
  const visibleItems = [...NAV_ITEMS.filter((entry) => !entry.adminOnly || isAdmin()), CHANGELOG_NAV];
  const changelogUnseen = useChangelogUnseen();

  // Longest-matching-prefix active-link resolution — "/" only matches exactly.
  const matches = (to: string) =>
    to === "/" ? pathname === "/" : pathname === to || pathname.startsWith(`${to}/`);
  const activeTo =
    visibleItems.map((e) => e.to)
      .filter(matches)
      .sort((a, b) => b.length - a.length)[0] ?? null;

  async function handleLogout() {
    await logout();
    queryClient.clear();
    flagSignedOut();
    navigate("/login", { replace: true });
    notifyAuthChange();
  }

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 240, breakpoint: "sm", collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <a href="#main-content" className="skip-link">
          {t("appShell.skipToContent")}
        </a>
        <Group h={56} px="md" justify="space-between">
          <Group gap="sm">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <BrandLogo />
            <Text fw={600} size="lg">
              {t("appShell.brand")}
            </Text>
          </Group>
          <Group gap="sm">
            <LanguageSwitcher />
            <ColorSchemeToggle />
            <ActionIcon
              variant="subtle"
              color="gray"
              size="lg"
              aria-label={t("common.action.logout")}
              onClick={() => void handleLogout()}
            >
              <IconLogout size={18} />
            </ActionIcon>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="sm">
        {/* The link list scrolls when it outgrows the viewport; the version stamp stays pinned. */}
        <AppShell.Section grow component={ScrollArea} type="hover" scrollbarSize={6} offsetScrollbars>
          {visibleItems.map((entry) => {
            const active = entry.to === activeTo;
            const Icon = entry.icon;
            return (
              <NavLink
                key={entry.to}
                component={RouterLink}
                to={entry.to}
                active={active}
                aria-current={active ? "page" : undefined}
                label={t(entry.label)}
                leftSection={<Icon size={18} stroke={1.5} />}
                onClick={close}
              />
            );
          })}
        </AppShell.Section>
        {/* The title carries the accessible "what's new" name only while the dot is shown. */}
        <AppShell.Section pt="xs" style={{ borderTop: "1px solid var(--mantine-color-default-border)" }}>
          <Indicator
            color="red"
            size={8}
            disabled={!changelogUnseen}
            title={changelogUnseen ? t("changelog.whatsNew") : undefined}
          >
            <VersionStamp to="/changelog" ta="center" pt={4} />
          </Indicator>
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main id="main-content" tabIndex={-1}>
        {/* A page crash stays inside the main area — header/nav keep working, and navigating
            anywhere remounts the boundary (see components/ErrorBoundary.tsx). The inner
            Suspense keeps the shell mounted while a lazy page chunk loads. */}
        <RouteErrorBoundary>
          <Suspense fallback={<RouteFallback />}>
            <Outlet />
          </Suspense>
        </RouteErrorBoundary>
      </AppShell.Main>
    </AppShell>
  );
}

export default function App() {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route
          path="/login"
          element={
            <RedirectIfAuthed>
              <Login />
            </RedirectIfAuthed>
          }
        />
        <Route
          path="/reset-password"
          element={
            <RedirectIfAuthed>
              <ResetPassword />
            </RedirectIfAuthed>
          }
        />
        <Route element={<RequireAuth />}>
          <Route element={<Shell />}>
            <Route index element={<Hierarchy />} />
            <Route path="catalog-files" element={<CatalogFiles />} />
            <Route path="catalog-files/new" element={<CreateCatalogFile />} />
            <Route path="catalog-files/import" element={<ImportCatalogFiles />} />
            <Route path="catalog-files/:id/edit" element={<EditCatalogFile />} />
            <Route path="cross-check" element={<CrossCheck />} />
            <Route path="render" element={<RenderGraph />} />
            <Route path="namespaces" element={<Namespaces />} />
            <Route path="labels" element={<Labels />} />
            <Route path="tags" element={<Tags />} />
            <Route path="types" element={<Types />} />
            <Route path="lifecycles" element={<Lifecycles />} />
            <Route path="annotations" element={<Annotations />} />
            <Route path="users" element={<Users />} />
            <Route path="users/new" element={<CreateUser />} />
            <Route path="users/:id/edit" element={<EditUser />} />
            <Route path="users/:id/features" element={<UserFeatures />} />
            <Route path="feature-flags" element={<FeatureFlags />} />
            <Route path="change-password" element={<ChangePassword />} />
            <Route path="changelog" element={<Changelog />} />
            {/* The authenticated catch-all — LAST child, never feature-gated. */}
            <Route path="*" element={<NotFound />} />
          </Route>
        </Route>
      </Routes>
    </Suspense>
  );
}
