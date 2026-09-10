import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Badge, Button, Grid, Group, Paper, Stack } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import BlueprintFormFields from "./BlueprintFormFields";
import CodePreviewCard from "./CodePreviewCard";
import PageHeader from "./PageHeader";
import { useBlueprintRowExpansion } from "../hooks/useBlueprintRowExpansion";
import { toBlueprintRequest, type BlueprintFormValues } from "../utils/blueprintForm";
import { blueprintsPath } from "../utils/blueprintLinks";
import classes from "../theme.module.css";

/**
 * The Blueprint editor shell shared by the create and edit pages — the CatalogFileEditor
 * split (7/5 grid, sticky live preview, sticky Cancel/Save bar, no tabs), minus the
 * findings/history panels a blueprint definition has no equivalent of: there are no soft
 * findings (nothing here resolves against other stored content the way a catalog reference
 * does) and no per-file change history yet. Owns the row-fold state (v1.23.2,
 * `hooks/useBlueprintRowExpansion.ts`) shared by every family's `EditorRowList` and wires it
 * into the validation-failure branch of `form.onSubmit`, so a blocked save reveals every
 * offending row instead of stranding its error behind a collapsed header.
 */
export default function BlueprintEditor({
  title,
  submitLabel,
  form,
  onSubmit,
  error,
  submitting,
  system = false,
}: {
  title: string;
  submitLabel: string;
  form: UseFormReturnType<BlueprintFormValues>;
  onSubmit: (values: BlueprintFormValues) => Promise<void>;
  error: string | null;
  submitting: boolean;
  /** A system blueprint (`_team`/`_user`, Phase 4 v1.26.0) — never true from CreateBlueprint,
   *  since a system row can only ever be edited, not created. */
  system?: boolean;
}) {
  const { t } = useTranslation();
  const expansion = useBlueprintRowExpansion(form);
  const preview = JSON.stringify(toBlueprintRequest(form.values), null, 2);
  return (
    <Stack gap="md">
      <PageHeader
        title={title}
        description={
          system && (
            <Badge variant="outline" color="gray">
              {t("blueprints.systemBadge")}
            </Badge>
          )
        }
        backTo={{ to: blueprintsPath, label: t("blueprints.backToList") }}
      />
      <Grid>
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Paper withBorder p="lg" radius="md">
            <form onSubmit={form.onSubmit(onSubmit, (errors) => expansion.revealErrors(errors))} noValidate>
              <Stack>
                <BlueprintFormFields form={form} expansion={expansion} system={system} />
                {error && (
                  <Alert color="red" variant="light">
                    {error}
                  </Alert>
                )}
                <Group justify="flex-end" gap="sm" className={classes.stickyActions}>
                  <Button component={RouterLink} to={blueprintsPath} variant="default">
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
            <CodePreviewCard title={t("blueprints.preview")} label={t("blueprints.preview")} text={preview} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}
