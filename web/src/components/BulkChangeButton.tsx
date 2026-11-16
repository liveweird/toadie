import { Button, type ButtonProps } from "@mantine/core";
import { IconEdit } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { entityBulkChangePath } from "../utils/entityLinks";

/**
 * The bulk-change entry point (2.38.0), the same link on the Entities list, the Entity graph
 * and the Entity hierarchy: it carries the surface's scope — the ONE blueprint it shows, its
 * search text and team filter — into `/entities/bulk-change?blueprint=&q=&team=`. A plain
 * router link (cmd/middle-click opens a tab), `default` variant so the page's primary action
 * keeps the amber. With no blueprint to carry it is still enabled: the destination offers the
 * blueprint pills to pick from.
 */
export default function BulkChangeButton({
  blueprint,
  q,
  team,
  size,
}: {
  blueprint?: string | null;
  q?: string | null;
  team?: string | null;
  /** The canvases' title row uses `xs`, like its toggles; the Entities header keeps the default. */
  size?: ButtonProps["size"];
}) {
  const { t } = useTranslation();
  return (
    <Button
      component={RouterLink}
      to={entityBulkChangePath({ blueprint, q, team })}
      variant="default"
      size={size}
      leftSection={<IconEdit size={16} />}
    >
      {t("entityBulk.open")}
    </Button>
  );
}
