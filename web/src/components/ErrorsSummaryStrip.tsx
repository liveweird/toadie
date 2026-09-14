import { useTranslation } from "react-i18next";
import type { ErrorsReport } from "../api/catalogFiles";
import { ERROR_CLASSES, type ErrorClass } from "../utils/errorClasses";
import { countByClass } from "../utils/errorGroups";
import ReportSummaryStrip from "./ReportSummaryStrip";

/**
 * The Errors page's summary strip (v1.20.0): three stat tiles — files checked, references
 * checked, errors shown — and the error-CLASS chips, rendered through the shared
 * `ReportSummaryStrip` (extracted v2.5.0 for the Port-world Errors report's own adapter,
 * `EntityErrorsSummaryStrip`). Filtering stays client-side over the fetched findings (the
 * nine shared filters are the server's); with no class selected the table shows no rows.
 */
export default function ErrorsSummaryStrip({
  report,
  classes: selected,
  setClasses,
  shownErrors,
}: {
  report: ErrorsReport | undefined;
  classes: string[];
  setClasses: (next: string[]) => void;
  shownErrors: number;
}) {
  const { t } = useTranslation();
  const counts = countByClass(report?.findings ?? []);
  return (
    <ReportSummaryStrip<ErrorClass>
      tiles={[
        { value: report?.checkedFiles ?? "—", label: t("errors.summary.filesLabel") },
        { value: report?.checkedReferences ?? "—", label: t("errors.summary.referencesLabel") },
        {
          value: report ? shownErrors : "—",
          label: t("errors.summary.errorsLabel"),
          color: report ? (shownErrors > 0 ? "red" : "teal") : undefined,
        },
      ]}
      allClasses={ERROR_CLASSES}
      selectedClasses={selected}
      setClasses={setClasses}
      counts={counts}
      classLabel={(errorClass) => t(`errors.class.${errorClass}`)}
      groupAriaLabel={t("errors.classesLabel")}
    />
  );
}
