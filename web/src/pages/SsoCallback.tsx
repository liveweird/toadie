import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useLocation, useNavigate } from "react-router-dom";
import { Alert, Anchor, Stack } from "@mantine/core";
import { exchangeSsoCode, takeSsoReturnLocation } from "../api/auth";
import { ApiError } from "../api/http";
import { notifyAuthChange } from "../auth";
import AuthCard from "../components/AuthCard";
import LoadingBlock from "../components/LoadingBlock";
import { saveErrorMessage } from "../utils/saveError";

/**
 * The landing page of a successful SSO callback (`/login/sso#code=<hand-off>`). The one-time code
 * is read from the fragment and stripped from history at once, then exchanged exactly once —
 * the ref survives React StrictMode's second effect run, which would otherwise burn the
 * single-use code twice and fail a good sign-in. A signed-in browser still exchanges (the route
 * sits outside RedirectIfAuthed). Server rules: `.claude/docs/security.md` "SSO login (OIDC)".
 */
export default function SsoCallback() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  // Captured from the fragment at first render; the effect below then strips it from history.
  const [code] = useState(() => new URLSearchParams(location.hash.slice(1)).get("code") ?? "");
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);
  // Navigation after the exchange only makes sense while this page is still on screen.
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    if (!started.current) {
      started.current = true;
      navigate({ pathname: location.pathname }, { replace: true });
      if (code) {
        exchangeSsoCode(code).then(
          () => {
            notifyAuthChange();
            if (mounted.current) navigate(takeSsoReturnLocation(), { replace: true });
          },
          (err: unknown) => {
            if (!mounted.current) return;
            setError(
              err instanceof ApiError && err.status === 401
                ? t("auth.ssoLinkInvalid")
                : saveErrorMessage(err, t, {
                    tooManyRequests: "auth.ssoExchangeThrottled",
                    failed: "auth.ssoCallbackFailed",
                  }),
            );
          },
        );
      }
    }
    return () => {
      mounted.current = false;
    };
  }, [code, location.pathname, navigate, t]);

  // No code in the fragment is the same dead end as a refused one — and needs no request.
  const failure = error ?? (code ? null : t("auth.ssoLinkInvalid"));

  return (
    <AuthCard title={t("auth.ssoCallbackTitle")}>
      {failure ? (
        <Stack>
          <Alert color="red" variant="light">{failure}</Alert>
          <Anchor component={RouterLink} to="/login" size="sm" ta="center">{t("auth.backToSignIn")}</Anchor>
        </Stack>
      ) : (
        <LoadingBlock />
      )}
    </AuthCard>
  );
}
