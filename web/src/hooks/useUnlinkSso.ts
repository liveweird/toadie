import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { unlinkUserSso, type UserResponse } from "../api/users";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The admin unlink-SSO flow: confirm → DELETE `/users/{id}/sso` → toast + list refresh. Owns the
 * target/pending/error state; the page renders the `ConfirmActionModal` from it. A 404 means the
 * row was already unlinked (or deleted) behind the admin's back — the message says so and the
 * list is refreshed so the stale SSO badge goes away, while the confirm stays open to dismiss.
 */
export function useUnlinkSso() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [target, setTarget] = useState<UserResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  function request(user: UserResponse) {
    setError(null);
    setTarget(user);
  }

  function clearTarget() {
    setTarget(null);
  }

  async function confirm() {
    if (!target) return;
    setError(null);
    setPending(true);
    try {
      await unlinkUserSso(target.id);
      setTarget(null);
      showSuccessToast(t("users.toast.ssoUnlinked"));
      await queryClient.invalidateQueries({ queryKey: ["users"] });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          notFound: "users.unlinkGone",
          failedStatus: "common.error.actionFailedStatus",
          failed: "common.error.actionFailed",
        }),
      );
      if (err instanceof ApiError && err.status === 404) {
        void queryClient.invalidateQueries({ queryKey: ["users"] });
      }
    } finally {
      setPending(false);
    }
  }

  return { target, error, pending, request, clearTarget, confirm };
}
