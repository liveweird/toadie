import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Grid, Group, Paper, Stack, Text } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import type { Blueprint } from "../api/blueprints";
import type { EntityFinding } from "../api/entities";
import CodePreviewCard from "./CodePreviewCard";
import EntityFormFields from "./EntityFormFields";
import PageHeader from "./PageHeader";
import { toEntityRequest, type EntityFormValues } from "../utils/entityForm";
import { entitiesPath } from "../utils/entityLinks";
import classes from "../theme.module.css";

/**
 * The Entity editor shell — the `BlueprintEditor` split (7/5 grid, sticky live JSON preview,
 * sticky Cancel/Save bar, no tabs) plus one addition: an orange findings `Alert` on the edit
 * page when the entity is STALE (its stored `findings` are non-empty — the blueprint changed
 * since this entity was last saved, so a strict save will be rejected until the listed
 * properties/relations are fixed). Absent on create — a brand-new entity has no stored state.
 */
export default function EntityEditor({
  title,
  submitLabel,
  blueprint,
  form,
  onSubmit,
  error,
  submitting,
  staleFindings,
}: {
  title: string;
  submitLabel: string;
  blueprint: Blueprint;
  form: UseFormReturnType<EntityFormValues>;
  onSubmit: (values: EntityFormValues) => Promise<void>;
  error: string | null;
  submitting: boolean;
  staleFindings?: readonly EntityFinding[];
}) {
  const { t } = useTranslation();
  const preview = JSON.stringify(toEntityRequest(form.values, blueprint), null, 2);
  return (
    <Stack gap="md">
      <PageHeader
        title={title}
        description={t("entities.editor.blueprintLabel", { identifier: blueprint.identifier })}
        backTo={{ to: entitiesPath(blueprint.identifier), label: t("entities.backToList") }}
      />
      {staleFindings && staleFindings.length > 0 && (
        <Alert color="orange" variant="light" title={t("entities.staleTitle")}>
          <Stack gap={4}>
            <Text size="sm">{t("entities.staleBody")}</Text>
            {staleFindings.map((finding, i) => (
              <Text size="sm" key={`${finding.field}-${i}`}>
                {finding.field}: {finding.message}
              </Text>
            ))}
          </Stack>
        </Alert>
      )}
      <Grid>
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Paper withBorder p="lg" radius="md">
            <form onSubmit={form.onSubmit(onSubmit)} noValidate>
              <Stack>
                <EntityFormFields form={form} blueprint={blueprint} />
                {error && (
                  <Alert color="red" variant="light">
                    {error}
                  </Alert>
                )}
                <Group justify="flex-end" gap="sm" className={classes.stickyActions}>
                  <Button component={RouterLink} to={entitiesPath(blueprint.identifier)} variant="default">
                    {t("common.action.cancel")}
                  </Button>
                  <Button type="submit" loading={submitting}>
                    {submitLabel}
                  </Button>
                </Group>
              </Stack>
            </form>
          </Paper>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 5 }}>
          <Stack className={classes.stickyAside}>
            <CodePreviewCard title={t("entities.preview")} label={t("entities.preview")} text={preview} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}
