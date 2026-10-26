import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Grid, Group, Paper, Stack, Textarea, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import type { MappingDrift, MappingFinding } from "../api/conversionMappings";
import { MAX_MAPPING_NAME_LENGTH, type ConversionMappingFormValues } from "../utils/conversionMappingForm";
import { conversionMappingsPath } from "../utils/conversionLinks";
import MappingFindingsPanel from "./MappingFindingsPanel";
import PageHeader from "./PageHeader";
import classes from "../theme.module.css";

/**
 * The conversion-mapping editor shell shared by the create and edit pages — the blueprint
 * editor's split (form beside a sticky aside, sticky Cancel/Save), with the aside holding the
 * findings instead of a preview, since the document IS the JSON the form edits. Blocking findings
 * (a refused save's, else the stored mapping's) and report-only drift render side by side; the
 * server is the only gate on the document's meaning.
 */
export default function ConversionMappingEditor({
  title,
  submitLabel,
  form,
  onSubmit,
  error,
  submitting,
  findings,
  rejectedFindings,
  drift,
}: {
  title: string;
  submitLabel: string;
  form: UseFormReturnType<ConversionMappingFormValues>;
  onSubmit: (values: ConversionMappingFormValues) => Promise<void>;
  error: string | null;
  submitting: boolean;
  /** The stored mapping's recomputed blocking findings (edit only; empty on create). */
  findings: readonly MappingFinding[];
  /** The just-refused save's findings; they win over the stored ones while present. */
  rejectedFindings: readonly MappingFinding[];
  drift: readonly MappingDrift[];
}) {
  const { t } = useTranslation();
  const rejected = rejectedFindings.length > 0;
  return (
    <Stack gap="md">
      <PageHeader
        title={title}
        backTo={{ to: conversionMappingsPath, label: t("conversion.mappings.backToList") }}
      />
      <Grid>
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Paper withBorder p="lg" radius="md">
            <form onSubmit={form.onSubmit(onSubmit)} noValidate>
              <Stack>
                <TextInput
                  label={t("conversion.mappings.field.name")}
                  description={t("conversion.mappings.field.nameHint")}
                  withAsterisk
                  maxLength={MAX_MAPPING_NAME_LENGTH + 20}
                  {...form.getInputProps("name")}
                />
                <Textarea
                  label={t("conversion.mappings.field.document")}
                  description={t("conversion.mappings.field.documentHint")}
                  withAsterisk
                  autosize
                  minRows={18}
                  maxRows={40}
                  spellCheck={false}
                  styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)", fontSize: "var(--mantine-font-size-xs)" } }}
                  {...form.getInputProps("document")}
                />
                {error && (
                  <Alert color="red" variant="light">
                    {error}
                  </Alert>
                )}
                <Group justify="flex-end" gap="sm" className={classes.stickyActions}>
                  <Button component={RouterLink} to={conversionMappingsPath} variant="default">
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
            <MappingFindingsPanel findings={rejected ? rejectedFindings : findings} drift={drift} rejected={rejected} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}
