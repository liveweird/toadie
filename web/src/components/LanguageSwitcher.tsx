import { Box, Button, Menu } from "@mantine/core";
import { IconCheck, IconChevronDown, IconLanguage } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { setUserLanguage } from "../api/users";
import { getUserId } from "../api/session";
import { asSupportedLanguage, NATIVE_LANGUAGE_NAMES, SUPPORTED_LANGUAGES } from "../i18n";

/**
 * The header language menu. Entries are the languages' NATIVE names, readable before
 * switching; the trigger shows the current code. Since V18 this is also the self-service
 * writer of the SERVER-side user language (one synced language: it drives the UI at
 * sign-in and every email sent to the user) — the save is fire-and-forget: the UI switches
 * regardless, and a failed sync self-heals at the next switch or stays visible only in
 * email language.
 */
export default function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const current = asSupportedLanguage(i18n.resolvedLanguage);

  function pick(lng: string) {
    void i18n.changeLanguage(lng);
    // The switcher only mounts in the authenticated shell, but keep the guard.
    const userId = getUserId();
    if (userId !== null) {
      setUserLanguage(userId, lng).catch((e: unknown) => console.error("Language sync failed", e));
    }
  }

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
            onClick={() => pick(lng)}
            leftSection={lng === current ? <IconCheck size={14} /> : <Box w={14} />}
          >
            {NATIVE_LANGUAGE_NAMES[lng]}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}
