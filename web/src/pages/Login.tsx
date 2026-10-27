import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useLocation, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Alert, Anchor, Button, Center, Divider, PasswordInput, PinInput, Stack, Text, TextInput } from "@mantine/core";
import { isEmail, isNotEmpty, useForm } from "@mantine/form";
import { isMfaChallenge, login, loginOptions, startSsoLogin, verifyMfa, type MfaChallenge } from "../api/auth";
import { saveErrorMessage } from "../utils/saveError";
import { consumeSignedOut, internalReturnLocation, notifyAuthChange } from "../auth";
import AuthCard from "../components/AuthCard";
import { MAX_EMAIL_LENGTH } from "../utils/userForm";

const SSO_ERRORS = ["cancelled", "expired", "failed", "not_allowed"] as const;
type SsoError = (typeof SSO_ERRORS)[number];

/** The `#sso_error=<code>` the SSO callback redirects to; an absent fragment is null, an unrecognised code reads as `failed`. */
function ssoErrorFromHash(hash: string): SsoError | null {
  const code = new URLSearchParams(hash.slice(1)).get("sso_error");
  if (code === null) return null;
  return SSO_ERRORS.find((known) => known === code) ?? "failed";
}

export default function Login() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [signedOut, setSignedOut] = useState<boolean>(() => consumeSignedOut());
  // Email MFA: non-null switches the card to the code-entry step.
  const [challenge, setChallenge] = useState<MfaChallenge | null>(null);
  const [code, setCode] = useState("");
  // Read once from the fragment, which the effect below then strips from the address bar.
  const [ssoError, setSsoError] = useState<SsoError | null>(() => ssoErrorFromHash(location.hash));
  // A failed read just hides the button: the password form never depends on it.
  const options = useQuery({ queryKey: ["loginOptions"], queryFn: loginOptions, retry: false, staleTime: 60_000 });
  const sso = options.data?.sso ?? null;

  useEffect(() => {
    if (location.hash.includes("sso_error")) {
      navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: location.state });
    }
  }, [location.hash, location.pathname, location.search, location.state, navigate]);

  const form = useForm({
    initialValues: { email: "", password: "" },
    validate: {
      email: isEmail(t("auth.invalidEmail")),
      password: isNotEmpty(t("auth.passwordRequired")),
    },
  });

  function finishSignIn() {
    notifyAuthChange();
    navigate(internalReturnLocation(location.state), { replace: true });
  }

  async function onSubmit(values: { email: string; password: string }) {
    setError(null);
    setSsoError(null);
    setSignedOut(false);
    setSubmitting(true);
    try {
      const data = await login(values);
      if (isMfaChallenge(data)) {
        setChallenge(data);
        setCode("");
        return;
      }
      finishSignIn();
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          unauthorized: "auth.invalidCredentials",
          tooManyRequests: "auth.accountLocked",
          unavailable: "auth.mfaUnavailable",
          failedStatus: "auth.loginFailedStatus",
          failed: "auth.loginFailedGeneric",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  async function onVerify(event: React.FormEvent) {
    event.preventDefault();
    if (!challenge) return;
    setError(null);
    setSubmitting(true);
    try {
      await verifyMfa(challenge.challengeId, code);
      finishSignIn();
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          unauthorized: "auth.mfaInvalidCode",
          tooManyRequests: "auth.mfaTooManyAttempts",
          failedStatus: "auth.loginFailedStatus",
          failed: "auth.loginFailedGeneric",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  function backToSignIn() {
    setChallenge(null);
    setCode("");
    setError(null);
  }

  if (challenge) {
    return (
      <AuthCard title={t("auth.mfaTitle")}>
        <form onSubmit={onVerify} noValidate>
          <Stack>
            <Text size="sm" c="dimmed">
              {t("auth.mfaExplainer", { email: form.values.email })}
            </Text>
            <Center>
              <PinInput
                length={6}
                type="number"
                oneTimeCode
                autoFocus
                aria-label={t("auth.mfaCodeLabel")}
                value={code}
                onChange={setCode}
              />
            </Center>
            {error && (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            )}
            <Button type="submit" loading={submitting} disabled={code.length < 6} fullWidth>
              {t("auth.mfaVerify")}
            </Button>
            <Anchor component="button" type="button" size="sm" ta="center" onClick={backToSignIn}>
              {t("auth.backToSignIn")}
            </Anchor>
          </Stack>
        </form>
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t("auth.signIn")}>
      {ssoError && (
        <Alert color="red" variant="light">
          {t(`auth.ssoError.${ssoError}`)}
        </Alert>
      )}
      {sso && (
        <>
          <Button
            variant="default"
            fullWidth
            onClick={() => startSsoLogin((location.state as { from?: Location } | null)?.from)}
          >
            {t("auth.ssoSignIn", { label: sso.label })}
          </Button>
          <Divider label={t("auth.ssoOr")} labelPosition="center" />
        </>
      )}
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
