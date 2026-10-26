import { useId, useState, type ReactNode } from "react";
import { Box, Button, Stack } from "@mantine/core";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";

/**
 * A chart's numbers sit behind a disclosure button (`aria-expanded`/`aria-controls`) instead of crowding a 420 px panel; the body
 * is not rendered while closed. The children are the table itself - scroll region and sticky header included. Every chart keeps
 * its figures reachable this way: colour and shape are never the only carrier of a value. The two labels are the caller's
 * (already translated): the table names itself.
 */
export default function FiguresDisclosure({
  children,
  showLabel,
  hideLabel,
}: {
  children: ReactNode;
  showLabel: string;
  hideLabel: string;
}) {
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  return (
    <Stack gap="xs" align="flex-start">
      <Button
        variant="subtle"
        size="compact-sm"
        leftSection={open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
        aria-expanded={open}
        aria-controls={open ? bodyId : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        {open ? hideLabel : showLabel}
      </Button>
      <Box id={bodyId} w="100%" hidden={!open}>
        {open && children}
      </Box>
    </Stack>
  );
}
