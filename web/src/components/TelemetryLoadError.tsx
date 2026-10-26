import { useTranslation } from "react-i18next";
import { Alert } from "@mantine/core";
import { loadErrorMessage } from "../utils/saveError";

/** A telemetry read that failed: the inline red Alert (never `error.message`), optionally titled when figures are still on screen. */
export default function TelemetryLoadError({ error, title }: { error: unknown; title?: string }) {
  const { t } = useTranslation();
  return (
    <Alert color="red" variant="light" title={title}>
      {loadErrorMessage(error, t)}
    </Alert>
  );
}
