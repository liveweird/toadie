import { useTranslation } from "react-i18next";
import { Badge, CloseButton } from "@mantine/core";
import { IconStack2 } from "@tabler/icons-react";

/**
 * The "Top level" marker beside Expand all while the large-graph gate's transient fold overlay
 * is active (the Hierarchy's Pinned badge idiom): gray — neutral state, not caution — with a
 * labelled clear button. Shared by Graph and EntityGraph.
 */
export default function TopLevelBadge({ onClear }: { onClear: () => void }) {
  const { t } = useTranslation();
  return (
    <Badge
      variant="light"
      color="gray"
      size="lg"
      // Badge uppercases by default; the other neutral-state badges show their text as written.
      tt="none"
      leftSection={<IconStack2 size={12} />}
      rightSection={
        <CloseButton
          size="xs"
          variant="transparent"
          aria-label={t("common.graphLayout.topLevelClearAria")}
          onClick={onClear}
        />
      }
    >
      {t("common.graphLayout.topLevelBadge")}
    </Badge>
  );
}
