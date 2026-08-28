import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useLocation, useNavigate } from "react-router-dom";
import { Alert, Anchor, Button, PasswordInput, Stack, TextInput } from "@mantine/core";
import { isEmail, isNotEmpty, useForm } from "@mantine/form";
import { login } from "../api/auth";
import { saveErrorMessage } from "../utils/saveError";
import { consumeSignedOut, notifyAuthChange } from "../auth";
import AuthCard from "../components/AuthCard";
import { MAX_EMAIL_LENGTH } from "../utils/userForm";

type LocationState = { from?: { pathname?: string } } | null;

export default function Login() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [signedOut, setSignedOut] = useState<boolean>(() => consumeSignedOut());

  const form = useForm({
    initialValues: { email: "", password: "" },
    validate: {
      email: isEmail(t("auth.invalidEmail")),
      password: isNotEmpty(t("auth.passwordRequired")),
    },
  });

  async function onSubmit(values: { email: string; password: string }) {
    setError(null);
    setSignedOut(false);
    setSubmitting(true);
    try {
      await login(values);
      notifyAuthChange();
      const from = (location.state as LocationState)?.from?.pathname;
      navigate(from ?? "/", { replace: true });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          unauthorized: "auth.invalidCredentials",
          tooManyRequests: "auth.accountLocked",
          failedStatus: "auth.loginFailedStatus",
          failed: "auth.loginFailedGeneric",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title={t("auth.signIn")}>
      <form onSubmit={form.onSubmit(onSubmit)} noValidate>
        <Stack>
          <TextInput
            label={t("common.field.email")}
            type="email"
            autoFocus
            autoComplete="email"
            maxLength={MAX_EMAIL_LENGTH}
            {...form.getInputProps("email")}
          />
          <PasswordInput
            label={t("auth.password")}
            autoComplete="current-password"
            {...form.getInputProps("password")}
          />
          {signedOut && !form.isDirty() && !error && (
            // No explicit color: the theme's primary palette, not Mantine's stock blue.
            <Alert variant="light">{t("auth.signedOut")}</Alert>
          )}
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Button type="submit" loading={submitting} fullWidth>
            {t("auth.signIn")}
          </Button>
          <Anchor component={RouterLink} to="/reset-password" size="sm" ta="center">
            {t("auth.forgotPassword")}
          </Anchor>
        </Stack>
      </form>
    </AuthCard>
  );
}
