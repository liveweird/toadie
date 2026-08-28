import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate, useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Container, Group, Paper, Stack, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getUser, updateUser } from "../api/users";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import EditPageLoadState from "../components/EditPageLoadState";
import UserFormFields from "../components/UserFormFields";
import { EMPTY_USER_FORM, rolesOf, userFormValidation, type UserFormValues } from "../utils/userForm";
import { isLastAdminConflict, loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

export default function EditUser() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const form = useForm<UserFormValues>({
    initialValues: EMPTY_USER_FORM,
    validate: userFormValidation(t),
  });

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["user", id],
    queryFn: () => getUser(id),
    enabled: idIsValid && isAdmin(),
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize({ name: data.name, email: data.email, admin: data.roles.includes("ADMIN") });
  }

  if (!isAdmin()) return <Navigate to="/" replace />;
  if (!idIsValid) return <Navigate to="/users" replace />;

  async function onSubmit(values: UserFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateUser(id, {
        name: values.name.trim(),
        email: values.email.trim(),
        roles: rolesOf(values),
      });
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      await queryClient.invalidateQueries({ queryKey: ["user", id] });
      showSuccessToast(t("users.toast.updated"));
      navigate("/users", { replace: true });
    } catch (err) {
      setError(
        isLastAdminConflict(err)
          ? t("users.lastAdminError")
          : saveErrorMessage(err, t, {
              notFound: "users.userGone",
              conflict: "users.emailAlreadyInUse",
              failedStatus: "common.error.saveFailedStatus",
              failed: "common.error.saveFailedNetwork",
            }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  return (
    <Container size="sm" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <Stack>
          <Title order={2}>{t("users.editUser")}</Title>
          {isLoading || isError ? (
            <EditPageLoadState
              isLoading={isLoading}
              message={notFound ? t("users.userNotFound") : loadErrorMessage(fetchError, t)}
              backTo="/users"
              backLabel={t("users.backToUsers")}
            />
          ) : (
            <form onSubmit={form.onSubmit(onSubmit)} noValidate>
              <Stack>
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
                    {t("common.action.save")}
                  </Button>
                </Group>
              </Stack>
            </form>
          )}
        </Stack>
      </Paper>
    </Container>
  );
}
