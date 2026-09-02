import { lazy, Suspense } from "react";
import { AppShell, Box, Burger, Group, NavLink, ScrollArea, Text } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { Link as RouterLink, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { isAdmin } from "./api/session";
import { RedirectIfAuthed, RequireAuth } from "./auth";
import BrandLogo from "./components/BrandLogo";
import CommandPalette from "./components/CommandPalette";
import LoadingBlock from "./components/LoadingBlock";
import UserMenu from "./components/UserMenu";
import VersionStamp from "./components/VersionStamp";
import { RouteErrorBoundary } from "./components/ErrorBoundary";
import { activeNavPath, visibleSections, type NavLeaf } from "./utils/navigation";
import classes from "./theme.module.css";

const Login = lazy(() => import("./pages/Login"));
const ResetPassword = lazy(() => import("./pages/ResetPassword"));
const Hierarchy = lazy(() => import("./pages/Hierarchy"));
const CatalogFiles = lazy(() => import("./pages/CatalogFiles"));
const CreateCatalogFile = lazy(() => import("./pages/CreateCatalogFile"));
const ImportCatalogFiles = lazy(() => import("./pages/ImportCatalogFiles"));
const EditCatalogFile = lazy(() => import("./pages/EditCatalogFile"));
const ErrorsPage = lazy(() => import("./pages/Errors"));
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
    <LoadingBlock mih={200} />
  );
}

function Shell() {
  const { t } = useTranslation();
  const [opened, { toggle, close }] = useDisclosure();
  const { pathname } = useLocation();

  // The nav model lives in utils/navigation.ts (shared with the command palette): sections
  // of always-present leaves, admin-only ones filtered per session (the routes are guarded
  // too), an empty section disappearing with them.
  const sections = visibleSections(isAdmin());
  const activeTo = activeNavPath(
    pathname,
    sections.flatMap((section) => section.items),
  );

  const renderLeaf = (leaf: NavLeaf) => {
    const active = leaf.to === activeTo;
    const Icon = leaf.icon;
    return (
      <NavLink
        key={leaf.to}
        component={RouterLink}
        to={leaf.to}
        active={active}
        aria-current={active ? "page" : undefined}
        label={t(leaf.label)}
        leftSection={<Icon size={18} stroke={1.5} />}
        onClick={close}
      />
    );
  };

  return (
    <AppShell
      header={{ height: 48 }}
      navbar={{ width: 240, breakpoint: "sm", collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <a href="#main-content" className="skip-link">
          {t("appShell.skipToContent")}
        </a>
        <Group h={48} px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <BrandLogo />
            <Text fw={600} size="md" className={classes.brandText}>
              {t("appShell.brand")}
            </Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <CommandPalette />
            <UserMenu />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {/* The link list scrolls when it outgrows the viewport; the version stamp stays pinned. */}
        <AppShell.Section grow component={ScrollArea} type="hover" scrollbarSize={6} offsetScrollbars>
          {sections.map((section) => (
            // A labelled, always-open block — never a toggle, so every leaf stays in the DOM
            // for tests and deep links.
            <Box key={section.label} role="group" aria-label={t(section.label)}>
              <Text component="div" className={classes.navSectionLabel}>
                {t(section.label)}
              </Text>
              {section.items.map(renderLeaf)}
            </Box>
          ))}
        </AppShell.Section>
        <AppShell.Section pt="xs" style={{ borderTop: "1px solid var(--mantine-color-default-border)" }}>
          <VersionStamp to="/changelog" ta="center" pt={4} />
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
            <Route path="files" element={<CatalogFiles />} />
            <Route path="files/new" element={<CreateCatalogFile />} />
            <Route path="files/import" element={<ImportCatalogFiles />} />
            <Route path="files/:id/edit" element={<EditCatalogFile />} />
            <Route path="errors" element={<ErrorsPage />} />
            <Route path="graph" element={<RenderGraph />} />
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
