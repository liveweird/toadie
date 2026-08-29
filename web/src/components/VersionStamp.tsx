import { Text, type TextProps } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { APP_VERSION } from "../changelog/version";

// __APP_COMMIT__ / __APP_COMMIT_TIME__ come from `define` in vite.config.ts.
// The timestamp is git's %cI (strict ISO, committer-local); slicing to minutes
// avoids timezone re-interpretation. APP_VERSION is the newest changelog entry.
// With `to` set, the stamp renders as a router link (the navbar instance points
// at /changelog); without it, plain text (the login screen, where /changelog is
// behind auth).
export default function VersionStamp({ to, ...props }: TextProps & { to?: string }) {
  const { t } = useTranslation();
  const time = __APP_COMMIT_TIME__.slice(0, 16).replace("T", " ");
  const stamp = (
    <>
      {`v${APP_VERSION} · ${__APP_COMMIT__}`}
      {time && ` · ${time}`}
    </>
  );
  if (to) {
    return (
      <Text
        component={RouterLink}
        to={to}
        td="none"
        size="xs"
        c="dimmed"
        title={t("common.buildInfo")}
        {...props}
      >
        {stamp}
      </Text>
    );
  }
  return (
    <Text size="xs" c="dimmed" title={t("common.buildInfo")} {...props}>
      {stamp}
    </Text>
  );
}
