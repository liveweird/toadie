import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useLocation, useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Alert, Anchor, Button, PasswordInput, Stack, Text } from "@mantine/core";
import { useForm } from "@mantine/form";
import { confirmPasswordReset } from "../api/auth";
import { ApiError } from "../api/http";
import { clearSession } from "../api/session";
import { notifyAuthChange } from "../auth";
import AuthCard from "../components/AuthCard";
import { passwordFieldsValidation } from "../utils/userForm";
import { saveErrorMessage } from "../utils/saveError";

/** Token stays only in memory after removing the fragment; loading this page never consumes it. */
export default function ConfirmPasswordReset() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [token, setToken] = useState(() => new URLSearchParams(location.hash.slice(1)).get("token") ?? "");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [completed, setCompleted] = useState(false);
  const form = useForm({
    initialValues: { password: "", confirm: "" },
    validate: passwordFieldsValidation(t),
  });
  useEffect(() => {
    if (location.hash || location.search) navigate({ pathname: location.pathname, search: "", hash: "" }, { replace: true });
  }, [location.hash, location.search, location.pathname, navigate]);

  async function onSubmit(values: { password: string }) {
    setError(null);
    setSubmitting(true);
    try {
      await confirmPasswordReset(token, values.password);
      setToken("");
      form.reset();
      clearSession();
      queryClient.clear();
      setCompleted(true);
      notifyAuthChange();
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) setToken("");
      setError(saveErrorMessage(err, t, {
        unauthorized: "auth.resetLinkInvalid",
        invalid: "users.validation.passwordLength",
        tooManyRequests: "auth.resetConfirmThrottled",
        failed: "auth.resetFailedGeneric",
      }));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title={t("auth.resetChooseTitle")}>
      {completed ? (
        <Alert color="teal" variant="light">{t("auth.resetCompleted")}</Alert>
      ) : /^[A-Za-z0-9_-]{43}$/.test(token) ? (
        <form onSubmit={form.onSubmit(onSubmit)} noValidate>
          <Stack>
            <Text size="sm" c="dimmed">{t("auth.resetChooseIntro")}</Text>
            <PasswordInput label={t("users.newPassword")} autoComplete="new-password" required {...form.getInputProps("password")} />
            <PasswordInput label={t("users.confirmPassword")} autoComplete="new-password" required {...form.getInputProps("confirm")} />
            {error && <Alert color="red" variant="light">{error}</Alert>}
            <Button type="submit" loading={submitting} fullWidth>{t("auth.resetConfirmSubmit")}</Button>
          </Stack>
        </form>
      ) : (
        <Stack>
          <Alert color="red" variant="light">{t("auth.resetLinkInvalid")}</Alert>
          <Anchor component={RouterLink} to="/reset-password">{t("auth.resetRequestAnother")}</Anchor>
        </Stack>
      )}
      <Anchor component={RouterLink} to="/login" size="sm" ta="center">{t("auth.backToSignIn")}</Anchor>
    </AuthCard>
  );
}
