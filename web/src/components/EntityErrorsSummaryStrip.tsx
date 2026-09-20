import { useTranslation } from "react-i18next";
import type { EntityErrorsReport } from "../api/entities";
import { ENTITY_ERROR_CLASSES, countByEntityClass, type EntityErrorClass } from "../utils/entityErrorClasses";
import ReportSummaryStrip from "./ReportSummaryStrip";

const EMPTY_REPORT: EntityErrorsReport = {
  entities: [],
  blueprints: [],
  savedQueries: [],
  checkedEntities: 0,
  checkedBlueprints: 0,
  checkedSavedQueries: 0,
};

/**
 * The Port-world Errors report's summary strip (v2.5.0) — the `ErrorsSummaryStrip` shape over
 * the entity/blueprint/saved-query registries: three stat tiles (entities checked, blueprints
 * checked, errors shown) and the five class chips, rendered through the shared
 * `ReportSummaryStrip`. Class counts come from the UNFILTERED report; `shownErrors` is the
 * count AFTER the page's own class filter (the `ErrorsSummaryStrip` convention).
 */
export default function EntityErrorsSummaryStrip({
  report,
  classes: selected,
  setClasses,
  shownErrors,
}: {
  report: EntityErrorsReport | undefined;
  classes: string[];
  setClasses: (next: string[]) => void;
  shownErrors: number;
}) {
  const { t } = useTranslation();
  const counts = countByEntityClass(report ?? EMPTY_REPORT);
  return (
    <ReportSummaryStrip<EntityErrorClass>
      tiles={[
        { value: report?.checkedEntities ?? "—", label: t("entityErrors.summary.entitiesLabel") },
        { value: report?.checkedBlueprints ?? "—", label: t("entityErrors.summary.blueprintsLabel") },
        {
          value: report ? shownErrors : "—",
          label: t("entityErrors.summary.errorsLabel"),
          color: report ? (shownErrors > 0 ? "red" : "teal") : undefined,
        },
      ]}
      allClasses={ENTITY_ERROR_CLASSES}
      selectedClasses={selected}
      setClasses={setClasses}
      counts={counts}
      classLabel={(entityClass) => t(`entityErrors.class.${entityClass}`)}
      groupAriaLabel={t("entityErrors.classesLabel")}
    />
  );
}
