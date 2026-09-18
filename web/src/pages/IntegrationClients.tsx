import { useEffect, useState } from "react";
import { Navigate } from "react-router-dom";
import { Alert, Badge, Button, Group, Menu, Modal, Paper, Stack, Table, Text, TextInput } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconKey, IconKeyOff, IconPlus } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import {
  createIntegrationClient,
  type IntegrationClient,
  type IntegrationClientCreated,
  listIntegrationClients,
  revokeIntegrationClient,
} from "../api/integrationClients";
import { ownsCurrentSession, sessionBoundary } from "../api/http";
import { isAdmin } from "../api/session";
import { subscribeAuthLifecycle } from "../auth";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RevealablePassword from "../components/RevealablePassword";
import RowActionsMenu from "../components/RowActionsMenu";
import TableLoadingRow from "../components/TableLoadingRow";
import { charCountDescription } from "../utils/charCount";
import { formatDateTime } from "../utils/relativeTime";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";

const MAX_NAME = 100;
const COLUMN_COUNT = 5;

export default function IntegrationClients() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [name, setName] = useState("");
  const [created, setCreated] = useState<IntegrationClientCreated | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<IntegrationClient | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);
  const [revoking, setRevoking] = useState(false);
  const admin = isAdmin();

  useEffect(() => subscribeAuthLifecycle(() => {
    // The one-time key and every pending form/modal belong to the login family that created
    // them. The shell may stay mounted across a cross-tab admin-to-admin replacement.
    setPage(1);
    setPageSize(20);
    setName("");
    setCreated(null);
    setCreateError(null);
    setCreating(false);
    setRevokeTarget(null);
    setRevokeError(null);
    setRevoking(false);
  }), []);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["integrationClients", page, pageSize],
    queryFn: () => listIntegrationClients(page, pageSize),
    placeholderData: keepPreviousData,
    enabled: admin,
  });

  if (!admin) return <Navigate to="/" replace />;

  const trimmedName = name.trim();
  const nameValid = trimmedName.length > 0 && trimmedName.length <= MAX_NAME && !/[\r\n]/.test(trimmedName);

  async function addClient() {
    const owner = sessionBoundary();
    setCreating(true);
    setCreateError(null);
    try {
      const response = await createIntegrationClient(trimmedName);
      if (!ownsCurrentSession(owner)) return;
      // Show the only copy immediately. A slow or failed list refresh must never hide it.
      setCreated(response);
      setName("");
      setCreating(false);
      // Ownership was checked immediately before this call. A response arriving after a
      // replacement session never invalidates that session's cache.
      void queryClient.invalidateQueries({ queryKey: ["integrationClients"] });
    } catch (err) {
      if (!ownsCurrentSession(owner)) return;
      setCreateError(
        saveErrorMessage(err, t, {
          forbidden: "integration.error.permission",
          invalid: "integration.error.invalidName",
          failedStatus: "integration.error.createFailedStatus",
          failed: "integration.error.createFailed",
        }),
      );
    } finally {
      if (ownsCurrentSession(owner)) setCreating(false);
    }
  }

  async function revokeClient() {
    if (!revokeTarget) return;
    const owner = sessionBoundary();
    const target = revokeTarget;
    setRevoking(true);
    setRevokeError(null);
    try {
      await revokeIntegrationClient(target.id);
      if (!ownsCurrentSession(owner)) return;
      setCreated((current) => (current?.client.id === target.id ? null : current));
      await queryClient.invalidateQueries({ queryKey: ["integrationClients"] });
      if (!ownsCurrentSession(owner)) return;
      notifications.show({ message: t("integration.toast.revoked"), color: "teal" });
      setRevokeTarget(null);
    } catch (err) {
      if (!ownsCurrentSession(owner)) return;
      setRevokeError(
        saveErrorMessage(err, t, {
          forbidden: "integration.error.permission",
          notFound: "integration.error.gone",
          conflict: "integration.error.alreadyRevoked",
          failedStatus: "integration.error.revokeFailedStatus",
          failed: "integration.error.revokeFailed",
        }),
      );
    } finally {
      if (ownsCurrentSession(owner)) setRevoking(false);
    }
  }

  const total = data?.total ?? 0;

  return (
    <Stack gap="md">
      <PageHeader title={t("integration.title")} description={t("integration.hint")} />

      <Paper withBorder p="md" radius="md">
        <Stack gap="sm">
          <Group align="flex-end" gap="md" wrap="wrap">
            <TextInput
              label={t("integration.name")}
              value={name}
              onChange={(event) => {
                setName(event.currentTarget.value);
                setCreateError(null);
              }}
              maxLength={MAX_NAME}
              description={charCountDescription(name.length, MAX_NAME)}
              w={280}
            />
            <Button
              leftSection={<IconPlus size={16} />}
              onClick={() => void addClient()}
              loading={creating}
              disabled={!nameValid}
            >
              {t("integration.addClient")}
            </Button>
          </Group>
          {createError && <Alert color="red" variant="light">{createError}</Alert>}
        </Stack>
      </Paper>

      {created && (
        <Alert
          key={created.client.id}
          color="yellow"
          variant="light"
          title={t("integration.keyPanelTitle", { name: created.client.name })}
          withCloseButton
          closeButtonLabel={t("common.action.close")}
          onClose={() => setCreated(null)}
          styles={{ body: { minWidth: 0 }, title: { overflowWrap: "anywhere" } }}
        >
          <Stack gap="xs">
            <Text size="sm">{t("integration.keyPanelWarning")}</Text>
            <RevealablePassword password={created.apiKey} copyLabel={t("integration.copyKey")} />
          </Stack>
        </Alert>
      )}

      {isError && (
        <Alert color="red" variant="light" title={t("integration.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Table.ScrollContainer minWidth={760}>
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t("integration.name")}</Table.Th>
              <Table.Th>{t("integration.column.status")}</Table.Th>
              <Table.Th>{t("integration.column.createdBy")}</Table.Th>
              <Table.Th>{t("integration.column.lastUsed")}</Table.Th>
              <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {isLoading && !data ? (
              <TableLoadingRow colSpan={COLUMN_COUNT} />
            ) : data && data.items.length > 0 ? (
              data.items.map((client) => (
                <Table.Tr key={client.id}>
                  <Table.Td><Text size="sm" fw={500}>{client.name}</Text></Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={client.revoked ? "gray" : "teal"} size="sm">
                      {t(client.revoked ? "integration.status.revoked" : "integration.status.active")}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{client.createdByName}</Text>
                    <Text size="xs" c="dimmed">{formatDateTime(client.createdAt, i18n.language)}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c={client.lastUsedAt == null ? "dimmed" : undefined}>
                      {client.lastUsedAt == null
                        ? t("integration.neverUsed")
                        : formatDateTime(client.lastUsedAt, i18n.language)}
                    </Text>
                  </Table.Td>
                  <Table.Td style={{ width: 1 }} ta="right">
                    {!client.revoked && (
                      <RowActionsMenu label={t("common.table.operationsAria", { name: client.name })}>
                        <Menu.Item
                          color="red"
                          leftSection={<IconKeyOff size={14} />}
                          aria-label={t("integration.revokeAria", { name: client.name })}
                          onClick={() => {
                            setRevokeError(null);
                            setRevokeTarget(client);
                          }}
                        >
                          {t("integration.revoke")}
                        </Menu.Item>
                      </RowActionsMenu>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))
            ) : !isError ? (
              <Table.Tr>
                <Table.Td colSpan={COLUMN_COUNT}>
                  <EmptyState icon={IconKey} label={t("integration.empty")} />
                </Table.Td>
              </Table.Tr>
            ) : null}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>

      <PaginationBar
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={(size) => { setPageSize(size); setPage(1); }}
      />

      <Modal
        opened={revokeTarget !== null}
        onClose={() => { if (!revoking) setRevokeTarget(null); }}
        title={t("integration.revokeTitle")}
        centered
      >
        <Stack gap="md">
          {revokeTarget && <Text>{t("integration.revokeMessage", { name: revokeTarget.name })}</Text>}
          {revokeError && (
            <Alert color="red" variant="light" title={t("integration.revokeFailed")}>{revokeError}</Alert>
          )}
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={() => setRevokeTarget(null)} disabled={revoking}>
              {t("common.action.cancel")}
            </Button>
            <Button color="red" onClick={() => void revokeClient()} loading={revoking}>
              {t("integration.revoke")}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
