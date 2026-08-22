import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router-dom";
import { Alert, Button, PasswordInput, Stack, TextInput } from "@mantine/core";
import { isEmail, isNotEmpty, useForm } from "@mantine/form";
import { ApiError } from "../api/http";
import { login } from "../api/auth";
import { consumeSignedOut, notifyAuthChange } from "../auth";
import AuthCard from "../components/AuthCard";

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
      if (err instanceof ApiError) {
        setError(
          err.status === 401
            ? t("auth.invalidCredentials")
            : err.status === 429
              ? t("auth.accountLocked")
              : t("auth.loginFailedStatus", { status: err.status }),
        );
      } else {
        setError(t("auth.loginFailedGeneric"));
      }
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
            maxLength={254}
            {...form.getInputProps("email")}
          />
          <PasswordInput
            label={t("auth.password")}
            autoComplete="current-password"
            {...form.getInputProps("password")}
          />
          {signedOut && !form.isDirty() && !error && (
            <Alert color="blue" variant="light">
              {t("auth.signedOut")}
            </Alert>
          )}
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Button type="submit" loading={submitting} fullWidth>
            {t("auth.signIn")}
          </Button>
        </Stack>
      </form>
    </AuthCard>
  );
}
