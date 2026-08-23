import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate } from "react-router-dom";
import { Alert, Badge, Button, Group, Modal, Select, Stack, Table, Text, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconKey, IconPencil, IconPlus, IconTrash, IconUsers } from "@tabler/icons-react";
import { changeUserPassword, deleteUser, listUsers, type UserResponse } from "../api/users";
import { getUserId, isAdmin } from "../api/session";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import PaginationBar from "../components/PaginationBar";
import RevealablePassword from "../components/RevealablePassword";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { isString, useStoredState } from "../hooks/useStoredState";
import { generatePassword } from "../utils/password";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";

const SORT_FIELDS = ["name", "email"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "users";

export default function Users() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const currentUserId = getUserId();
  const [nameFilter, setNameFilter] = useStoredState(`${SETTINGS_KEY}.filter.name`, "", isString);
  const [emailFilter, setEmailFilter] = useStoredState(`${SETTINGS_KEY}.filter.email`, "", isString);
  const [roleFilter, setRoleFilter] = useStoredState(`${SETTINGS_KEY}.filter.role`, "", isString);
  const activeFilterCount =
    (nameFilter.trim() ? 1 : 0) + (emailFilter.trim() ? 1 : 0) + (roleFilter ? 1 : 0);

  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const [debouncedEmail] = useDebouncedValue(emailFilter, 300);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName, debouncedEmail, roleFilter], {
      key: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
    });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["users", page, pageSize, sortParam, debouncedName, debouncedEmail, roleFilter],
    queryFn: () =>
      listUsers({
        page,
        pageSize,
        sort: sortParam,
        name: debouncedName || undefined,
        email: debouncedEmail || undefined,
        role: roleFilter || undefined,
      }),
    placeholderData: keepPreviousData,
    enabled: isAdmin(),
  });

  const deleteConfirm = useDeleteConfirm<UserResponse>({
    mutationFn: (row) => deleteUser(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["users"] }),
    successMessage: t("users.toast.deleted"),
  });

  // The reset flow: confirm → generate client-side → PUT → the one-time reveal modal.
  const [resetTarget, setResetTarget] = useState<UserResponse | null>(null);
  const [resetError, setResetError] = useState<string | null>(null);
  const [resetPending, setResetPending] = useState(false);
  const [revealed, setRevealed] = useState<{ email: string; password: string } | null>(null);

  async function confirmReset() {
    if (!resetTarget) return;
    setResetError(null);
    setResetPending(true);
    const password = generatePassword();
    try {
      await changeUserPassword(resetTarget.id, { password });
      setRevealed({ email: resetTarget.email, password });
      setResetTarget(null);
    } catch (err) {
      setResetError(
        saveErrorMessage(err, t, {
          failedStatus: "users.resetFailedStatus",
          failed: "users.resetFailedNetwork",
        }),
      );
    } finally {
      setResetPending(false);
    }
  }

  if (!isAdmin()) return <Navigate to="/" replace />;

  const total = data?.total ?? 0;
  const columnCount = 4;

  return (
    <Stack gap="md">
      <Title order={2}>{t("users.title")}</Title>

      <FilterPanel activeFilterCount={activeFilterCount} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("users.clearNameFilter")}
        />
        <ClearableTextInput
          label={t("common.field.email")}
          value={emailFilter}
          onChange={setEmailFilter}
          clearLabel={t("users.clearEmailFilter")}
        />
        <Select
          label={t("common.field.role")}
          placeholder={t("common.state.any")}
          data={[
            { value: "ADMIN", label: t("common.role.ADMIN") },
            { value: "USER", label: t("users.rolePlain") },
          ]}
          value={roleFilter || null}
          onChange={(v) => setRoleFilter(v ?? "")}
          clearable
          clearButtonProps={{ "aria-label": t("users.clearRoleFilter") }}
        />
      </FilterPanel>

      {isError && (
        <Alert color="red" variant="light" title={t("users.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Table highlightOnHover withTableBorder verticalSpacing="sm">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>
              <SortHeader
                field="name"
                label={t("common.field.name")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
              />
            </Table.Th>
            <Table.Th>
              <SortHeader
                field="email"
                label={t("common.field.email")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
              />
            </Table.Th>
            <Table.Th>{t("common.field.role")}</Table.Th>
            <Table.Th aria-label={t("common.action.edit")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((user) => {
              const self = user.id === currentUserId;
              return (
                <Table.Tr key={user.id}>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <Text size="sm" fw={500}>
                        {user.name}
                      </Text>
                      {self && (
                        <Badge size="xs" variant="light">
                          {t("users.youBadge")}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{user.email}</Text>
                  </Table.Td>
                  <Table.Td>
                    {user.roles.length > 0 ? (
                      <Badge variant="filled" color="grape" size="sm">
                        {t("common.role.ADMIN")}
                      </Badge>
                    ) : (
                      <Text size="sm" c="dimmed">
                        —
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td style={{ width: 1, whiteSpace: "nowrap" }}>
                    <Group gap={4} wrap="nowrap">
                      <Button
                        component={RouterLink}
                        to={`/users/${user.id}/edit`}
                        variant="subtle"
                        size="xs"
                        leftSection={<IconPencil size={14} />}
                        aria-label={t("users.editAria", { name: user.name })}
                      >
                        {t("common.action.edit")}
                      </Button>
                      {/* Own row: no reset (self-change needs the current password — the
                          Change password page) and no delete (the server 403s it anyway). */}
                      {!self && (
                        <>
                          <Button
                            variant="subtle"
                            size="xs"
                            leftSection={<IconKey size={14} />}
                            onClick={() => {
                              setResetError(null);
                              setResetTarget(user);
                            }}
                            aria-label={t("users.resetPasswordAria", { name: user.name })}
                          >
                            {t("users.resetPassword")}
                          </Button>
                          <Button
                            color="red"
                            variant="subtle"
                            size="xs"
                            leftSection={<IconTrash size={14} />}
                            onClick={() => deleteConfirm.requestDelete(user)}
                            aria-label={t("users.deleteAria", { name: user.name })}
                          >
                            {t("common.action.delete")}
                          </Button>
                        </>
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              );
            })
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState
                  icon={<IconUsers size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
                  label={t("users.noUsers")}
                />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>

      <PaginationBar
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
        rowsPerPageLabelKey="users.rowsPerPage"
      />

      <Group justify="flex-end">
        <Button component={RouterLink} to="/users/new" leftSection={<IconPlus size={16} />}>
          {t("users.createUser")}
        </Button>
      </Group>

      <ConfirmDeleteModal
        confirm={deleteConfirm}
        title={t("users.deleteTitle")}
        errorTitle={t("users.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, {
            conflict: "users.lastAdminError",
            failedStatus: "common.error.actionFailedStatus",
            failed: "common.error.actionFailed",
          })
        }
        body={(target) => (
          <>
            {t("users.deleteBody", { name: target.name, email: target.email })}{" "}
            {t("users.deleteUndone")}
          </>
        )}
      />

      <Modal
        opened={resetTarget !== null}
        onClose={() => {
          if (!resetPending) setResetTarget(null);
        }}
        title={t("users.resetTitle")}
        centered
      >
        {resetTarget && (
          <Stack gap="md">
            <Text>{t("users.resetBody", { name: resetTarget.name })}</Text>
            {resetError && (
              <Alert color="red" variant="light" title={t("users.resetFailedTitle")}>
                {resetError}
              </Alert>
            )}
            <Group justify="flex-end" gap="sm">
              <Button variant="default" onClick={() => setResetTarget(null)} disabled={resetPending}>
                {t("common.action.cancel")}
              </Button>
              <Button onClick={() => void confirmReset()} loading={resetPending}>
                {t("users.resetConfirm")}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>

      {/* One-time password reveal. Deliberate close only (no click-outside / Escape) so the
          password can't be lost by accident — after closing it is unrecoverable by design. */}
      <Modal
        opened={revealed !== null}
        onClose={() => setRevealed(null)}
        title={t("users.resetDoneTitle")}
        centered
        closeOnClickOutside={false}
        closeOnEscape={false}
      >
        {revealed && (
          <Stack gap="md">
            <Text>{t("users.generatedPasswordNote", { email: revealed.email })}</Text>
            <RevealablePassword password={revealed.password} copyLabel={t("users.copyPassword")} />
            <Group justify="flex-end">
              <Button onClick={() => setRevealed(null)}>{t("common.action.close")}</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}
