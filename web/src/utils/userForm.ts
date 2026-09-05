import type { TFunction } from "i18next";
import type { SupportedLanguage } from "../i18n";
import { utf8ByteLength } from "./password";

// Server limits (users/Validation.kt + auth/Passwords.kt) mirrored client-side.
export const MAX_USER_NAME_LENGTH = 50;
export const MAX_EMAIL_LENGTH = 254;
export const MIN_PASSWORD_LENGTH = 10;
/** The bcrypt ceiling — counts BYTES, not characters (multibyte input bites earlier). */
export const MAX_PASSWORD_BYTES = 71;

/** The same new-password policy for authenticated changes and email-link confirmation. */
export function passwordFieldsValidation(t: TFunction) {
  return {
    password: (value: string) => {
      if (value.length < MIN_PASSWORD_LENGTH) return t("users.validation.passwordLength");
      if (utf8ByteLength(value) > MAX_PASSWORD_BYTES) return t("users.validation.passwordTooLong");
      return null;
    },
    confirm: (value: string, values: { password: string }) =>
      value === values.password ? null : t("users.validation.passwordsMismatch"),
  };
}

// Linear-time shape check (no catastrophic backtracking); the server's rule is looser
// (just '@' + no control chars) — this is UX-level guidance, not the gate.
const EMAIL_RE = /^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/;

/** The form values shared by CreateUser and EditUser (one possible extra role → a checkbox). */
export type UserFormValues = {
  name: string;
  email: string;
  admin: boolean;
  /** The ONE synced per-user language (V18) — its own endpoint on edit, POST field on create. */
  language: SupportedLanguage;
};

export const EMPTY_USER_FORM: UserFormValues = { name: "", email: "", admin: false, language: "en" };

/** Validation rules shared by the create and edit user pages (mirrors the server's checks). */
export function userFormValidation(t: TFunction) {
  return {
    name: (value: string) => {
      const v = value.trim();
      return v.length >= 1 && v.length <= MAX_USER_NAME_LENGTH ? null : t("users.validation.nameLength");
    },
    email: (value: string) => {
      const v = value.trim();
      if (v.length > MAX_EMAIL_LENGTH) return t("users.validation.emailTooLong");
      return EMAIL_RE.test(v) ? null : t("users.validation.emailInvalid");
    },
  };
}

/** The wire roles set from the checkbox (additional roles only — the standing shape). */
export function rolesOf(values: UserFormValues): "ADMIN"[] {
  return values.admin ? ["ADMIN"] : [];
}
