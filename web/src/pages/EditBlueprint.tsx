import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useParams } from "react-router-dom";
import { Button, Paper, Stack } from "@mantine/core";
import { IconRefresh } from "@tabler/icons-react";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getBlueprint, updateBlueprint } from "../api/blueprints";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import BlueprintEditor from "../components/BlueprintEditor";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import SyncBlueprintModal from "../components/SyncBlueprintModal";
import SyncStateText, { syncStateSource } from "../components/SyncStateText";
import { useBlueprintSave } from "../hooks/useBlueprintSave";
import {
  blueprintFormValidation,
  emptyBlueprintForm,
  fromBlueprintResponse,
  type BlueprintFormValues,
} from "../utils/blueprintForm";
import { blueprintsPath } from "../utils/blueprintLinks";
import { toBlueprintSyncTarget, type BlueprintSyncTarget } from "../utils/blueprintSync";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";

export default function EditBlueprint() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const idIsValid = Number.isFinite(id) && id > 0;
  const [syncTarget, setSyncTarget] = useState<BlueprintSyncTarget | null>(null);

  const form = useForm<BlueprintFormValues>({
    initialValues: emptyBlueprintForm(),
    validate: blueprintFormValidation(t),
    // Feedback when you LEAVE a field, not on every keystroke (the catalog editor's rule).
    validateInputOnBlur: true,
  });

  const save = useBlueprintSave({
    saveRequest: (body) => updateBlueprint(id, body),
    toastKey: "blueprints.toast.saved",
  });

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["blueprints", "detail", id],
    queryFn: () => getBlueprint(id),
    enabled: idIsValid && isAdmin(),
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the catalog editor's guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize(fromBlueprintResponse(data));
  }

  /**
   * Re-seed the form after a sync replaced the stored definition underneath it — the
   * `EditEntity.tsx#reseedFromServer` idiom verbatim, one level over: Mantine's `initialize`
   * is a one-shot latch, so without this the fields (and their dirty baseline) would keep
   * showing the pre-sync document and a later Save would silently revert the sync.
   */
  async function reseedFromServer() {
    try {
      const values = fromBlueprintResponse(
        await queryClient.fetchQuery({
          queryKey: ["blueprints", "detail", id],
          queryFn: () => getBlueprint(id),
          retry: false,
          staleTime: 0,
        }),
      );
      form.setInitialValues(values);
      form.setValues(values);
      form.resetDirty();
    } catch {
      // Nothing to add — a failed re-read simply leaves the (now possibly stale) form as is;
      // the next load of this page will show the real state.
    }
  }

  // Mutations are ADMIN-only; the nav leaf and the list stay visible to everyone, so this
  // route needs its own backstop (the Users-page pattern).
  if (!isAdmin()) return <Navigate to={blueprintsPath} replace />;
  if (!idIsValid) return <Navigate to={blueprintsPath} replace />;

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  if (isLoading || isError) {
    return (
      <Stack gap="md">
        <PageHeader
          title={t("blueprints.editBlueprint")}
          backTo={{ to: blueprintsPath, label: t("blueprints.backToList") }}
        />
        <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
          <EditPageLoadState
            isLoading={isLoading}
            message={notFound ? t("blueprints.notFound") : loadErrorMessage(fetchError, t)}
            backTo={blueprintsPath}
            backLabel={t("blueprints.backToList")}
          />
        </Paper>
      </Stack>
    );
  }

  const actions = data && (
    <>
      <SyncStateText file={syncStateSource(data)} />
      <Button
        variant="default"
        size="sm"
        leftSection={<IconRefresh size={14} />}
        onClick={() => setSyncTarget(toBlueprintSyncTarget(data))}
        disabled={data.sourceUrl == null}
      >
        {t("sync.action")}
      </Button>
    </>
  );

  return (
    <>
      <BlueprintEditor
        title={t("blueprints.editBlueprint")}
        submitLabel={t("common.action.save")}
        form={form}
        onSubmit={save.onSubmit}
        error={save.error}
        submitting={save.submitting}
        system={data?.system}
        actions={actions}
      />
      <SyncBlueprintModal
        target={syncTarget}
        onClose={() => setSyncTarget(null)}
        onCompleted={() => void reseedFromServer()}
      />
    </>
  );
}
