import { type ReactNode } from "react";
import { Center, Stack, Text, ThemeIcon } from "@mantine/core";

/** The shared zero-rows body for list tables (icon + dimmed one-liner). */
export default function EmptyState({ icon, label }: { icon: ReactNode; label: string }) {
  return (
    <Center py={48}>
      <Stack align="center" gap="sm">
        <ThemeIcon variant="light" color="gray" size={64} radius="xl">
          {icon}
        </ThemeIcon>
        <Text c="dimmed" size="sm">
          {label}
        </Text>
      </Stack>
    </Center>
  );
}
