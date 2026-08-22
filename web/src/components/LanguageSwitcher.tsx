import { Box, Button, Menu } from "@mantine/core";
import { IconCheck, IconChevronDown, IconLanguage } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { asSupportedLanguage, NATIVE_LANGUAGE_NAMES, SUPPORTED_LANGUAGES } from "../i18n";

/**
 * The header language menu. Entries are the languages' NATIVE names, readable before
 * switching; the trigger shows the current code. Device-local only (i18next caches the
 * choice to localStorage) — there is no server-side user language in the skeleton.
 */
export default function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const current = asSupportedLanguage(i18n.resolvedLanguage);

  return (
    <Menu position="bottom-end" withinPortal>
      <Menu.Target>
        <Button
          variant="default"
          size="xs"
          aria-label={t("common.language.label")}
          leftSection={<IconLanguage size={14} />}
          rightSection={<IconChevronDown size={12} />}
        >
          {current.toUpperCase()}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        {SUPPORTED_LANGUAGES.map((lng) => (
          <Menu.Item
            key={lng}
            onClick={() => void i18n.changeLanguage(lng)}
            leftSection={lng === current ? <IconCheck size={14} /> : <Box w={14} />}
          >
            {NATIVE_LANGUAGE_NAMES[lng]}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}
