import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Grid, Group, Paper, Stack, Text } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import type { Blueprint } from "../api/blueprints";
import type { EntityFinding } from "../api/entities";
import CodePreviewCard from "./CodePreviewCard";
import EntityFormFields from "./EntityFormFields";
import PageHeader from "./PageHeader";
import { dedupeEntityFindings, indexEntityFindings } from "../utils/entityFieldFindings";
import { toEntityRequest, type EntityFormValues } from "../utils/entityForm";
import { entitiesPath } from "../utils/entityLinks";
import classes from "../theme.module.css";

/**
 * The Entity editor shell — the `BlueprintEditor` split (7/5 grid, sticky live JSON preview,
 * sticky Cancel/Save bar, no tabs) plus one addition: an Alert when there are findings to show
 * — from the loaded entity being STALE (`staleFindings`, non-empty when the blueprint changed
 * since this entity was last saved) and/or from a just-rejected strict save (`saveFindings`,
 * the 400 body's `findings`, Phase 4 ownership included). The two lists are merged and deduped
 * by field+code (`dedupeEntityFindings`) for both the Alert body and the per-field paint
 * (`EntityFormFields`'s `findings` prop) — a save rejection outranks staleness for the Alert's
 * TITLE (`entities.rejectedTitle` vs `entities.staleTitle`), since it is the more urgent, more
 * recent signal. Absent on create unless the very first save is rejected — a brand-new entity
 * has no stored state to be stale about.
 */
export default function EntityEditor({
  title,
  submitLabel,
  blueprint,
  form,
  onSubmit,
  error,
  submitting,
  staleFindings = [],
  saveFindings = [],
  computedTeam = [],
}: {
  title: string;
  submitLabel: string;
  blueprint: Blueprint;
  form: UseFormReturnType<EntityFormValues>;
  onSubmit: (values: EntityFormValues) => Promise<void>;
  error: string | null;
  submitting: boolean;
  staleFindings?: readonly EntityFinding[];
  saveFindings?: readonly EntityFinding[];
  computedTeam?: readonly string[];
}) {
  const { t } = useTranslation();
  const preview = JSON.stringify(toEntityRequest(form.values, blueprint), null, 2);

  const allFindings = useMemo(
    () => dedupeEntityFindings(staleFindings, saveFindings),
    [staleFindings, saveFindings],
  );
  const findingsIndex = useMemo(() => indexEntityFindings(allFindings), [allFindings]);

  return (
    <Stack gap="md">
      <PageHeader
        title={title}
        description={t("entities.editor.blueprintLabel", { identifier: blueprint.identifier })}
        backTo={{ to: entitiesPath(blueprint.identifier), label: t("entities.backToList") }}
      />
      {allFindings.length > 0 && (
        <Alert
          color="orange"
          variant="light"
          title={saveFindings.length > 0 ? t("entities.rejectedTitle") : t("entities.staleTitle")}
        >
          <Stack gap={4}>
            <Text size="sm">{saveFindings.length > 0 ? t("entities.rejectedBody") : t("entities.staleBody")}</Text>
            {allFindings.map((finding, i) => (
              <Text size="sm" key={`${finding.field}-${finding.code}-${i}`}>
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
                <EntityFormFields form={form} blueprint={blueprint} computedTeam={computedTeam} findings={findingsIndex} />
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
