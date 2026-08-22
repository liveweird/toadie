import {
  AppShell,
  Badge,
  Modal,
  NavLink,
  Table,
  Tooltip,
  createTheme,
  type MantineColorsTuple,
} from "@mantine/core";
import classes from "./theme.module.css";

// "Toadie" brand palette — a warm amber/brown toad scale (light → dark, indices 0–9).
const toadie: MantineColorsTuple = [
  "#fffbeb",
  "#fef3c7",
  "#fde68a",
  "#fcd34d",
  "#fbbf24",
  "#f59e0b",
  "#d97706",
  "#b45309", // primary (light scheme)
  "#92400e", // primary (dark scheme)
  "#78350f",
];

// Inter is bundled (via @fontsource-variable/inter, imported in main.tsx) so it loads same-origin
// and satisfies the CSP `font-src 'self'`; the system stack is the fallback (e.g. in tests).
const sans =
  "'Inter Variable', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

// Design language (the Lettuce "clean enterprise SaaS" posture): the brand amber is the
// interactive accent (buttons, links, active nav) at a deep, serious shade; the canvas is a
// quiet near-white (dark: dark-8) that lets white surfaces lift on a soft, diffuse shadow
// scale. Don't reintroduce stock-blue actions or stock-green success states.
export const theme = createTheme({
  primaryColor: "toadie",
  // Shade 7 in light mode — deeper, calmer CTAs than the mid-amber 6.
  primaryShade: { light: 7, dark: 8 },
  // Pick readable text color on filled brand surfaces automatically.
  autoContrast: true,
  defaultRadius: "md",
  colors: { toadie },
  fontFamily: sans,
  headings: {
    fontFamily: sans,
    fontWeight: "600",
    // Tighter than Mantine's defaults — pages title themselves with order={2}, and the
    // stock 1.625rem reads oversized next to 14-px body text.
    sizes: {
      h1: { fontSize: "1.625rem", lineHeight: "1.3" },
      h2: { fontSize: "1.3rem", lineHeight: "1.35" },
      h3: { fontSize: "1.125rem", lineHeight: "1.4" },
    },
  },
  // Soft, diffuse elevation (the modern-SaaS ambient look) — every `shadow="sm"` Paper picks
  // this up with zero call-site changes.
  shadows: {
    xs: "0 1px 2px rgba(16, 24, 40, 0.04)",
    sm: "0 1px 2px rgba(16, 24, 40, 0.05), 0 1px 3px rgba(16, 24, 40, 0.07)",
    md: "0 4px 8px -2px rgba(16, 24, 40, 0.08), 0 2px 4px -2px rgba(16, 24, 40, 0.06)",
    lg: "0 12px 16px -4px rgba(16, 24, 40, 0.1), 0 4px 6px -2px rgba(16, 24, 40, 0.05)",
    xl: "0 20px 24px -4px rgba(16, 24, 40, 0.1), 0 8px 8px -4px rgba(16, 24, 40, 0.04)",
  },
  components: {
    // Every data table in the app: a card-like frame on the quiet canvas, hoverable rows,
    // and a neutral, compact header row (see theme.module.css). New tables inherit all of it.
    Table: Table.extend({
      defaultProps: { highlightOnHover: true, verticalSpacing: "sm" },
      classNames: { table: classes.table, thead: classes.tableHead },
    }),
    // The shell surfaces: white header/navbar over a tinted main canvas, crisp separators.
    AppShell: AppShell.extend({
      classNames: {
        main: classes.appMain,
        header: classes.appHeader,
        navbar: classes.appNavbar,
      },
    }),
    NavLink: NavLink.extend({ classNames: { root: classes.navLink } }),
    Badge: Badge.extend({ defaultProps: { radius: "sm" } }),
    Tooltip: Tooltip.extend({ defaultProps: { radius: "md" } }),
    Modal: Modal.extend({ defaultProps: { radius: "md" } }),
  },
});
