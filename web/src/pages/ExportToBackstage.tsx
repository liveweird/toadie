import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Button, Group, Paper, Select, Stack, Switch, Text, Title } from "@mantine/core";
import { IconDownload, IconFileExport, IconPlayerPlay } from "@tabler/icons-react";
import { useMutation } from "@tanstack/react-query";
import { getCatalogDocuments, type CatalogDocumentsSelection } from "../api/conversionMappings";
import CatalogFilesBatchModal from "../components/CatalogFilesBatchModal";
import CodePreviewCard from "../components/CodePreviewCard";
import ConversionRowsTable from "../components/ConversionRowsTable";
import EntityGraphToolbar from "../components/EntityGraphToolbar";
import EntityQueryBar from "../components/EntityQueryBar";
import { useBlueprints } from "../hooks/useBlueprints";
import { useConversionMappings } from "../hooks/useConversionMappings";
import { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import { useEntityQuery } from "../hooks/useEntityQuery";
import { useHierarchies } from "../hooks/useHierarchies";
import { useQueryDiagnostics } from "../hooks/useQueryDiagnostics";
import { expectedFileStates } from "../utils/catalogBatches";
import { catalogInfoMultiYaml, downloadYaml } from "../utils/catalogYaml";
import { IMPORT_CHUNK_SIZE } from "../utils/ontologyImport";
import { conversionMappingsPath, editConversionMappingPath } from "../utils/conversionLinks";
import { conversionErrorMessage } from "../utils/conversionErrors";
import { exportDisplayRows } from "../utils/conversionRows";
import { queryProblemDiagnostics } from "../utils/queryDiagnostics";
import { blueprintTierLookup } from "../utils/tiers";

/**
 * Export to Backstage (`/ontology/export-backstage`, conversion phase 5b): pick a conversion
 * mapping, narrow the entities with the Entity graph's own filters (Search/Team/tier Focus, the
 * blueprint pills and the entity query bar — the SAME shared query state, persisted per account),
 * and Convert. Nothing is stored by converting: the result is the per-entity report, a YAML
 * preview of the documents (downloadable) and the explicit "Create / update catalog files" step,
 * a dry run then a confirm (`CatalogFilesBatchModal`). A conversion is a pure read, so it runs
 * on the button, never per keystroke, and its result is a snapshot of the last run.
 */
export default function ExportToBackstage() {
  const { t } = useTranslation();
  const { mappings, loading: mappingsLoading } = useConversionMappings();
  const { blueprints, loading: blueprintsLoading } = useBlueprints();
  const activeBlueprints = useMemo(() => blueprints.map((b) => b.identifier), [blueprints]);
  const tierOf = useMemo(() => blueprintTierLookup(blueprints), [blueprints]);
  const filters = useEntityGraphFilterState("backstageExport", activeBlueprints, blueprintsLoading, tierOf);
  const query = useEntityQuery();
  const { diagnostics: liveDiagnostics } = useQueryDiagnostics(query.draft);
  const { hierarchies } = useHierarchies();
  const completionSchema = useMemo(
    () => ({ blueprints, hierarchies: hierarchies.map((h) => h.value) }),
    [blueprints, hierarchies],
  );

  const [pickedId, setPickedId] = useState<number | null>(null);
  const [merge, setMerge] = useState(true);
  const [creating, setCreating] = useState(false);

  // Default: the only mapping; otherwise the user's pick (a deleted pick falls back to none).
  const mapping =
    mappings.find((m) => m.id === pickedId) ?? (pickedId === null && mappings.length === 1 ? mappings[0] : null);
  const stale = mapping != null && mapping.findings.length > 0;

  // The selection the page would convert RIGHT NOW. A result belongs to the selection it was run
  // with: changing the mapping, merge or any filter after a Convert drops the result (it would
  // otherwise describe a different selection than the controls show, and "Create / update" would
  // act on it).
  const selection: CatalogDocumentsSelection = {
    blueprints: filters.values.blueprints,
    q: filters.values.q,
    team: filters.values.team,
    query: query.applied || undefined,
    merge,
  };
  const selectionKey = JSON.stringify([mapping?.id ?? null, selection]);

  const convert = useMutation({
    mutationFn: (run: { key: string; mappingId: number; selection: CatalogDocumentsSelection }) =>
      getCatalogDocuments(run.mappingId, run.selection),
  });
  const current = convert.variables?.key === selectionKey;

  // A refused run carries the query's own diagnostics — the bar is the error surface for them.
  const refusedRun = convert.isError && current ? queryProblemDiagnostics(convert.error) : [];
  const runDiagnostics = query.draft === query.applied ? refusedRun : [];
  const diagnostics = runDiagnostics.length > 0 ? runDiagnostics : liveDiagnostics;

  const result = current ? convert.data : undefined;
  const rows = useMemo(() => (result ? exportDisplayRows(result.rows) : []), [result]);
  const yaml = useMemo(() => (result ? catalogInfoMultiYaml(result.files) : ""), [result]);
  const canConvert = mapping != null && !stale && filters.ready && !filters.noBlueprints;

  return (
    <Stack gap="md">
      <EntityGraphToolbar
        title={t("conversion.export.title")}
        viewKey="backstageExport"
        filters={filters}
        queryOpen={query.open}
        onQueryOpenChange={query.setOpen}
        queryForcedOpen={runDiagnostics.length > 0}
        query={
          <EntityQueryBar
            value={query.draft}
            onChange={query.setDraft}
            onRun={query.run}
            onClear={query.clear}
            diagnostics={diagnostics}
            completionSchema={completionSchema}
            applied={query.applied}
            draft={query.draft}
            onPick={query.runText}
          />
        }
      />
      <Text c="dimmed" size="sm">
        {t("conversion.export.intro")}
      </Text>

      <Paper withBorder p="md" radius="md">
        <Stack gap="sm">
          <Group align="flex-end" gap="md" wrap="wrap">
            <Select
              label={t("conversion.export.mappingLabel")}
              placeholder={t("conversion.export.mappingPlaceholder")}
              data={mappings.map((m) => ({
                value: String(m.id),
                label: m.findings.length > 0 ? `${m.name} ${t("conversion.export.mappingStaleSuffix")}` : m.name,
              }))}
              value={mapping ? String(mapping.id) : null}
              onChange={(value) => setPickedId(value === null ? null : Number(value))}
              disabled={mappingsLoading || mappings.length === 0}
              allowDeselect={false}
              searchable
              w={320}
            />
            <Switch
              label={t("conversion.export.merge")}
              description={t("conversion.export.mergeHint")}
              checked={merge}
              onChange={(event) => setMerge(event.currentTarget.checked)}
              maw={420}
            />
            <Button
              leftSection={<IconPlayerPlay size={16} />}
              onClick={() => convert.mutate({ key: selectionKey, mappingId: mapping!.id, selection })}
              disabled={!canConvert}
              loading={convert.isPending}
            >
              {t("conversion.export.convert")}
            </Button>
          </Group>
          {!mappingsLoading && mappings.length === 0 && (
            <Text size="sm" c="dimmed">
              {t("conversion.export.noMappings")}{" "}
              <Anchor component={RouterLink} to={conversionMappingsPath} size="sm">
                {t("conversion.export.manageMappings")}
              </Anchor>
            </Text>
          )}
          {stale && (
            <Alert color="red" variant="light">
              {t("conversion.export.mappingStaleAlert")}{" "}
              <Anchor component={RouterLink} to={editConversionMappingPath(mapping.id)} size="sm">
                {t("conversion.export.manageMappings")}
              </Anchor>
            </Alert>
          )}
          {filters.noBlueprints && (
            <Text size="sm" c="dimmed">
              {t("conversion.export.noBlueprints")}
            </Text>
          )}
          <Text size="xs" c="dimmed">
            {t("conversion.export.queryHint")}
          </Text>
        </Stack>
      </Paper>

      {convert.isError && current && refusedRun.length === 0 && (
        <Alert color="red" variant="light" title={t("conversion.export.loadFailed")}>
          {conversionErrorMessage(convert.error, t, "export")}
        </Alert>
      )}

      {result && (
        <Stack gap="md">
          <Group justify="space-between" align="center" wrap="wrap" gap="sm">
            <Title order={3}>{t("conversion.export.resultsTitle")}</Title>
            <Group gap="sm">
              <Button
                variant="default"
                leftSection={<IconDownload size={16} />}
                disabled={result.files.length === 0}
                onClick={() => downloadYaml(yaml, "toadie-backstage-export.yaml")}
              >
                {t("conversion.export.download")}
              </Button>
              <Button
                leftSection={<IconFileExport size={16} />}
                disabled={result.files.length === 0}
                onClick={() => setCreating(true)}
              >
                {t("conversion.export.createFiles")}
              </Button>
            </Group>
          </Group>
          <ConversionRowsTable
            rows={rows}
            label={t("conversion.export.tableLabel")}
            fromLabel={t("conversion.export.fromColumn")}
            toLabel={t("conversion.export.toColumn")}
          />
          {result.files.length > IMPORT_CHUNK_SIZE && (
            <Text size="xs" c="dimmed">
              {t("conversion.export.chunkNote", { size: IMPORT_CHUNK_SIZE })}
            </Text>
          )}
          {result.files.length > 0 ? (
            <CodePreviewCard title={t("conversion.export.yamlTitle")} label={t("conversion.export.yamlTitle")} text={yaml} />
          ) : (
            <Text c="dimmed" size="sm">
              {t("conversion.export.yamlEmpty")}
            </Text>
          )}
        </Stack>
      )}

      {creating && convert.variables && (
        // The dialog converts AGAIN with the selection that produced the shown result, once on
        // open, so what it previews (and Confirm imports) is merged against the catalog as it is
        // now; each file carries the revision it was merged over.
        <CatalogFilesBatchModal
          convert={async () => {
            const { files, rows: converted } = await getCatalogDocuments(convert.variables!.mappingId, convert.variables!.selection);
            return { files, expected: expectedFileStates(converted, files.length) };
          }}
          onClose={() => setCreating(false)}
        />
      )}
    </Stack>
  );
}
