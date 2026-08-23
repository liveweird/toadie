import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate, useNavigate } from "react-router-dom";
import { Alert, Button, Container, Group, Modal, Paper, Stack, Text, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { IconMail } from "@tabler/icons-react";
import { createUser } from "../api/users";
import { isAdmin } from "../api/session";
import RevealablePassword from "../components/RevealablePassword";
import UserFormFields from "../components/UserFormFields";
import { generatePassword } from "../utils/password";
import { EMPTY_USER_FORM, rolesOf, userFormValidation, type UserFormValues } from "../utils/userForm";
import { saveErrorMessage } from "../utils/saveError";

export default function CreateUser() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Set on successful creation; the confirmation modal is the ONLY place this password ever
  // appears (the server stores just a bcrypt hash), so closing the modal discards it for good.
  const [created, setCreated] = useState<{ name: string; email: string; password: string } | null>(
    null,
  );

  const form = useForm<UserFormValues>({
    initialValues: EMPTY_USER_FORM,
    validate: userFormValidation(t),
  });

  if (!isAdmin()) return <Navigate to="/" replace />;

  async function onSubmit(values: UserFormValues) {
    setError(null);
    setSubmitting(true);
    const password = generatePassword();
    try {
      await createUser({
        name: values.name.trim(),
        email: values.email.trim(),
        password,
        roles: rolesOf(values),
      });
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      setCreated({ name: values.name.trim(), email: values.email.trim().toLowerCase(), password });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          conflict: "users.emailAlreadyInUse",
          failedStatus: "users.createFailedStatus",
          failed: "users.createFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  function closeConfirmation() {
    setCreated(null); // the plaintext is dropped here, for good
    navigate("/users", { replace: true });
  }

  // A client-side mail draft for handing the credentials over (no mail infra involved);
  // RFC 6068 mandates CRLF line breaks in mailto bodies.
  const mailtoHref = created
    ? `mailto:${encodeURIComponent(created.email).replace(/%40/g, "@")}` +
      `?subject=${encodeURIComponent(t("users.onboardingEmailSubject"))}` +
      `&body=${encodeURIComponent(
        t("users.onboardingEmailBody", {
          name: created.name,
          url: window.location.origin,
          password: created.password,
        }).replace(/\n/g, "\r\n"),
      )}`
    : undefined;

  return (
    <Container size="sm" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <form onSubmit={form.onSubmit(onSubmit)} noValidate>
          <Stack>
            <Title order={2}>{t("users.createUser")}</Title>
            {/* No password field: it is generated on submit and revealed exactly once. */}
            <UserFormFields form={form} />
            {error && (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            )}
            <Group justify="flex-end" gap="sm">
              <Button component={RouterLink} to="/users" variant="default">
                {t("common.action.cancel")}
              </Button>
              <Button type="submit" loading={submitting}>
                {t("common.action.create")}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>

      {/* One-time password reveal. Deliberate close only (no click-outside / Escape) so the
          password can't be lost by accident — after closing it is unrecoverable by design. */}
      <Modal
        opened={created !== null}
        onClose={closeConfirmation}
        title={t("users.createdTitle")}
        centered
        closeOnClickOutside={false}
        closeOnEscape={false}
      >
        {created && (
          <Stack gap="md">
            <Text>{t("users.generatedPasswordNote", { email: created.email })}</Text>
            <RevealablePassword password={created.password} copyLabel={t("users.copyPassword")} />
            <Group justify="space-between">
              <Button
                component="a"
                href={mailtoHref}
                variant="light"
                leftSection={<IconMail size={16} />}
              >
                {t("users.composeOnboardingEmail")}
              </Button>
              <Button onClick={closeConfirmation}>{t("common.action.close")}</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Container>
  );
}
