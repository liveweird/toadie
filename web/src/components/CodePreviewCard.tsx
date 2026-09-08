import { Code, Paper, ScrollArea, Stack, Title } from "@mantine/core";

/**
 * The live read-only text pane beside a full-page editor form — extracted from
 * YamlPreviewCard.tsx (its second use, the Blueprint editor's JSON preview) so the "sticky
 * card with a titled, keyboard-scrollable Code block" shape has one definition. YamlPreviewCard
 * stays as a thin wrapper (its `catalog.preview` label and default export are unchanged).
 */
export default function CodePreviewCard({
  title,
  label,
  text,
  embedded,
}: {
  title: string;
  /** The Code block's accessible name — the ScrollArea/Code precedent (a plain aria-label). */
  label: string;
  text: string;
  embedded?: boolean;
}) {
  const body = (
    <Stack gap="sm">
      <Title order={3}>{title}</Title>
      {/* A keyboard-focusable scroll region (the YamlDiffView rule): long content overflows a
          narrow column, and a scrollable region must be reachable by keyboard. */}
      <ScrollArea.Autosize mah="70vh" viewportProps={{ tabIndex: 0 }}>
        <Code block aria-label={label}>
          {text}
        </Code>
      </ScrollArea.Autosize>
    </Stack>
  );
  // `embedded` = no card of its own — a host (the quick-view drawer) supplies the surface.
  return embedded ? (
    body
  ) : (
    <Paper withBorder p="md" radius="md">
      {body}
    </Paper>
  );
}
