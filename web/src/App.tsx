import type { ParseKeys } from "i18next";
import { lazy, Suspense } from "react";
import {
  ActionIcon,
  AppShell,
  Burger,
  Center,
  Group,
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
  IconHome,
  IconKey,
  IconListCheck,
  IconLogout,
  IconMoon,
  IconSun,
  IconTopologyStar3,
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
import { RouteErrorBoundary } from "./components/ErrorBoundary";

const Login = lazy(() => import("./pages/Login"));
const Home = lazy(() => import("./pages/Home"));
const CatalogFiles = lazy(() => import("./pages/CatalogFiles"));
const CreateCatalogFile = lazy(() => import("./pages/CreateCatalogFile"));
const ImportCatalogFiles = lazy(() => import("./pages/ImportCatalogFiles"));
const EditCatalogFile = lazy(() => import("./pages/EditCatalogFile"));
const CrossCheck = lazy(() => import("./pages/CrossCheck"));
const Users = lazy(() => import("./pages/Users"));
const CreateUser = lazy(() => import("./pages/CreateUser"));
const EditUser = lazy(() => import("./pages/EditUser"));
const ChangePassword = lazy(() => import("./pages/ChangePassword"));
const RenderGraph = lazy(() => import("./pages/RenderGraph"));
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
  icon: typeof IconHome;
  // When set, the leaf renders only for ADMIN sessions (the Lettuce feature/role-filter
  // machinery slots back in here as features arrive).
  adminOnly?: boolean;
};

// `label` holds an i18n key, resolved with t() at render time. Feature entries (catalog
// files, cross-checks, rendering) append here as they are built.
const NAV_ITEMS: ReadonlyArray<NavLeaf> = [
  { to: "/", label: "appShell.nav.home", icon: IconHome },
  { to: "/catalog-files", label: "appShell.nav.catalogFiles", icon: IconFileDescription },
  { to: "/cross-check", label: "appShell.nav.crossCheck", icon: IconListCheck },
  { to: "/render", label: "appShell.nav.render", icon: IconTopologyStar3 },
  { to: "/users", label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
];

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
  const visibleItems = NAV_ITEMS.filter((entry) => !entry.adminOnly || isAdmin());

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
        <AppShell.Section pt="xs" style={{ borderTop: "1px solid var(--mantine-color-default-border)" }}>
          <VersionStamp ta="center" pt={4} />
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
        <Route element={<RequireAuth />}>
          <Route element={<Shell />}>
            <Route index element={<Home />} />
            <Route path="catalog-files" element={<CatalogFiles />} />
            <Route path="catalog-files/new" element={<CreateCatalogFile />} />
            <Route path="catalog-files/import" element={<ImportCatalogFiles />} />
            <Route path="catalog-files/:id/edit" element={<EditCatalogFile />} />
            <Route path="cross-check" element={<CrossCheck />} />
            <Route path="render" element={<RenderGraph />} />
            <Route path="users" element={<Users />} />
            <Route path="users/new" element={<CreateUser />} />
            <Route path="users/:id/edit" element={<EditUser />} />
            <Route path="change-password" element={<ChangePassword />} />
            {/* The authenticated catch-all — LAST child, never feature-gated. */}
            <Route path="*" element={<NotFound />} />
          </Route>
        </Route>
      </Routes>
    </Suspense>
  );
}
