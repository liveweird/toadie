import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Code,
  Divider,
  Drawer,
  Group,
  Stack,
  Text,
  Title,
} from "@mantine/core";
import { IconFileExport, IconPencil, IconRefresh, IconUpload } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { getCatalogFile, type CatalogFileResponse } from "../api/catalogFiles";
import { useCatalogDownloads } from "../hooks/useCatalogDownloads";
import { useDocumentCheck } from "../hooks/useDocumentCheck";
import { useQuickViewParam } from "../hooks/useQuickViewParam";
import { editCatalogFilePath } from "../utils/catalogFileLinks";
import { catalogInfoYaml } from "../utils/catalogYaml";
import { formatDateTime } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";
import KindBadge from "./KindBadge";
import LoadingBlock from "./LoadingBlock";
import OverwriteWithYamlModal from "./OverwriteWithYamlModal";
import ReferenceCheckPanel from "./ReferenceCheckPanel";
import SyncCatalogFileModal from "./SyncCatalogFileModal";
import SyncStateText from "./SyncStateText";
import YamlPreviewCard from "./YamlPreviewCard";

const DRAWER_WIDTH = 520;

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group gap="sm" wrap="nowrap" align="flex-start">
      <Text size="xs" c="dimmed" w={110} style={{ flexShrink: 0 }} pt={2}>
        {label}
      </Text>
      <div style={{ minWidth: 0, flex: 1 }}>{children}</div>
    </Group>
  );
}

function Summary({ file }: { file: CatalogFileResponse }) {
  const { t, i18n } = useTranslation();
  const dash = (
    <Text size="sm" c="dimmed">
      —
    </Text>
  );
  const text = (value: string | null | undefined) =>
    value ? <Text size="sm">{value}</Text> : dash;
  const labels = Object.entries(file.metadata.labels ?? {});
  const annotations = Object.keys(file.metadata.annotations ?? {}).length;
  return (
    <Stack gap={6}>
      <Row label={t("catalog.field.namespace")}>{text(file.metadata.namespace || "default")}</Row>
      <Row label={t("catalog.field.title")}>{text(file.metadata.title)}</Row>
      <Row label={t("catalog.field.description")}>
        {file.metadata.description ? (
          <Text size="sm">{file.metadata.description}</Text>
        ) : (
          <Text size="sm" c="dimmed">
            {t("catalog.drawer.noDescription")}
          </Text>
        )}
      </Row>
      <Row label={t("catalog.field.owner")}>{file.spec.owner ? <Code>{file.spec.owner}</Code> : dash}</Row>
      <Row label={t("catalog.field.type")}>{text(file.spec.type)}</Row>
      <Row label={t("catalog.field.lifecycle")}>{text(file.spec.lifecycle)}</Row>
      <Row label={t("catalog.field.tags")}>
        {file.metadata.tags && file.metadata.tags.length > 0 ? (
          <Group gap={4}>
            {file.metadata.tags.map((tag) => (
              <Badge key={tag} size="xs" color="gray" tt="none">
                {tag}
              </Badge>
            ))}
          </Group>
        ) : (
          dash
        )}
      </Row>
      <Row label={t("catalog.section.labels")}>
        {labels.length > 0 ? (
          <Group gap={4}>
            {labels.map(([key, value]) => (
              <Code key={key}>
                {key}={value}
              </Code>
            ))}
          </Group>
        ) : (
          dash
        )}
      </Row>
      <Row label={t("catalog.section.annotations")}>{text(annotations > 0 ? String(annotations) : null)}</Row>
      <Row label={t("catalog.field.source")}>
        {file.sourceUrl ? (
          <Anchor href={file.sourceUrl} target="_blank" rel="noopener" size="sm" style={{ wordBreak: "break-all" }}>
            {file.sourceUrl}
          </Anchor>
        ) : (
          <Text size="sm" c="dimmed">
            {t("catalog.sync.noSource")}
          </Text>
        )}
      </Row>
      <Row label={t("catalog.field.lastSync")}>
        <SyncStateText file={file} />
      </Row>
      <Row label={t("catalog.field.createdBy")}>{text(file.creatorName)}</Row>
      <Row label={t("catalog.field.created")}>{text(formatDateTime(file.createdAt, i18n.language))}</Row>
      <Row label={t("catalog.field.updated")}>{text(formatDateTime(file.updatedAt, i18n.language))}</Row>
    </Stack>
  );
}

function Loaded({ file }: { file: CatalogFileResponse }) {
  const { t } = useTranslation();
  const downloads = useCatalogDownloads();
  const [overwriteOpen, setOverwriteOpen] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);
  const document = { kind: file.kind, metadata: file.metadata, spec: file.spec };
  const { findings, checked } = useDocumentCheck(document);
  const target = {
    id: file.id,
    kind: file.kind,
    name: file.metadata.name,
    namespace: file.metadata.namespace || "default",
    sourceUrl: file.sourceUrl,
    updatedAt: file.updatedAt,
    lastSyncedAt: file.lastSyncedAt,
  };
  return (
    <>
      <Stack gap="lg">
        <Stack gap="xs">
          <Title order={3}>{t("catalog.drawer.summary")}</Title>
          <Summary file={file} />
        </Stack>
        <Divider />
        <ReferenceCheckPanel findings={findings} checked={checked} embedded />
        <Divider />
        <YamlPreviewCard yaml={catalogInfoYaml(document)} embedded />
        {downloads.downloadError != null && (
          <Alert color="red" variant="light" title={t("catalog.downloadFailed")}>
            {loadErrorMessage(downloads.downloadError, t)}
          </Alert>
        )}
        <Group justify="flex-end" gap="sm" wrap="wrap">
          <Button
            variant="default"
            size="sm"
            leftSection={<IconFileExport size={14} />}
            onClick={() => void downloads.handleDownload({ id: file.id })}
            loading={downloads.downloadingId === file.id}
          >
            {t("catalog.exportFile")}
          </Button>
          <Button variant="default" size="sm" leftSection={<IconUpload size={14} />} onClick={() => setOverwriteOpen(true)}>
            {t("catalog.overwrite.action")}
          </Button>
          <Button
            variant="default"
            size="sm"
            leftSection={<IconRefresh size={14} />}
            onClick={() => setSyncOpen(true)}
            disabled={file.sourceUrl == null}
          >
            {t("catalog.sync.action")}
          </Button>
          {/* Edit navigates WITHOUT closing: the list's history entry keeps `?file=`, so Back
              from the editor lands on the list with the drawer open again. */}
          <Button
            component={RouterLink}
            to={editCatalogFilePath(file.id)}
            size="sm"
            leftSection={<IconPencil size={14} />}
          >
            {t("common.action.edit")}
          </Button>
        </Group>
      </Stack>
      {/* The two whole-file modals sit ABOVE the drawer; a completed write refreshes the
          detail through the ["catalogFiles"] invalidation they already issue. */}
      <OverwriteWithYamlModal file={overwriteOpen ? target : null} onClose={() => setOverwriteOpen(false)} />
      <SyncCatalogFileModal file={syncOpen ? target : null} onClose={() => setSyncOpen(false)} />
    </>
  );
}

/**
 * The quick-view drawer (v1.21.0): a file's summary, live findings, and YAML at the right of
 * the Files list or the Hierarchy without leaving them, opened from the row menu's "Quick
 * view" and addressed by `?file=<id>` (hooks/useQuickViewParam.ts). It fetches the file
 * itself under the editor's detail key, so a catalog mutation refreshes it and a shared link
 * works for anyone. Edit hands over to the editor; Export/Overwrite/Sync act here (Sync
 * disabled without a source — the drawer KNOWS the source state, satisfying the `sync?`
 * rule); Delete stays in the row menu beside its confirm. A missing file reads as
 * "not found" with only Close.
 */
export default function CatalogFileDrawer() {
  const { t } = useTranslation();
  const { fileId, close } = useQuickViewParam();
  const detail = useQuery({
    queryKey: ["catalogFiles", "detail", fileId],
    queryFn: () => getCatalogFile(fileId!),
    enabled: fileId !== null,
  });
  const notFound = detail.isError && detail.error instanceof ApiError && detail.error.status === 404;
  const file = detail.data;
  return (
    <Drawer
      opened={fileId !== null}
      onClose={close}
      position="right"
      size={DRAWER_WIDTH}
      padding="md"
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      title={
        file ? (
          <Group gap="sm" wrap="nowrap">
            <KindBadge kind={file.kind} />
            <Text fw={600} truncate>
              {file.metadata.name}
            </Text>
          </Group>
        ) : (
          <Text fw={600}>{t("catalog.quickView")}</Text>
        )
      }
    >
      {detail.isPending ? (
        <LoadingBlock />
      ) : detail.isError ? (
        <Alert color="red" variant="light">
          {notFound ? t("catalog.fileNotFound") : loadErrorMessage(detail.error, t)}
        </Alert>
      ) : file ? (
        <Loaded file={file} />
      ) : null}
    </Drawer>
  );
}
