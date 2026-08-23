import { Text } from "@mantine/core";
import { useTranslation } from "react-i18next";

// The shared "123 / 4000" character counter under capped text fields. Dimmed while under the
// limit, red when over — over-limit is reachable only through programmatic value pushes, since
// native maxLength blocks typing/paste. Pure display: whether the counter appears at all is
// the caller's call — utils/charCount.tsx owns the nearLimit visibility rule.
export default function CharCount({ current, max }: { current: number; max: number }) {
  const { t } = useTranslation();
  return (
    <Text size="xs" c={current > max ? "red" : "dimmed"} ta="right" component="span" display="block">
      {t("common.charCount", { current, max })}
    </Text>
  );
}
