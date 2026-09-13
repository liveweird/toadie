import { SegmentedControl } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { isWorld, WORLD_INFO, WORLDS, type World } from "../utils/navigation";

/**
 * The `Backstage | Port` segmented control at the top of the sidebar (v2.3.0): the ONE
 * control that switches the product world the sidebar shows. Mantine renders a
 * `role="radiogroup"` of native radios, so tests/e2e address it by that role and the option
 * labels rather than a button role.
 */
export default function WorldSwitch({
  world,
  onChange,
}: {
  world: World;
  onChange: (world: World) => void;
}) {
  const { t } = useTranslation();
  return (
    <SegmentedControl
      fullWidth
      size="xs"
      aria-label={t("appShell.world.switch")}
      value={world}
      data={WORLDS.map((w) => ({ value: w, label: t(WORLD_INFO[w].label) }))}
      onChange={(v) => {
        if (isWorld(v)) onChange(v);
      }}
    />
  );
}
