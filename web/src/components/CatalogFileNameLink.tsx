import { Anchor } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { editCatalogFilePath } from "../utils/catalogFileLinks";

/**
 * A stored file's name, as the way into its editor — the Files list, the Hierarchy tree and
 * the Errors report all render it, so the three surfaces cannot drift apart. (The Graph
 * reaches the same route through `onNodeClick`: a canvas node cannot be an anchor.)
 *
 * A real link, not an onClick: cmd/middle-click opens a new tab, it takes keyboard focus, and
 * the target previews on hover. The accessible name says what the link DOES — a screen-reader
 * user tabbing a column of names would otherwise hear only the names again; the name itself
 * stays a plain text node, so text locators keep working.
 */
export default function CatalogFileNameLink({ id, name }: { id: number; name: string }) {
  const { t } = useTranslation();
  return (
    <Anchor
      component={RouterLink}
      to={editCatalogFilePath(id)}
      size="sm"
      fw={500}
      aria-label={t("common.action.editAria", { name })}
    >
      {name}
    </Anchor>
  );
}
