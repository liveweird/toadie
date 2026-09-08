import { useTranslation } from "react-i18next";
import CodePreviewCard from "./CodePreviewCard";

/**
 * The live catalog-info.yaml pane beside the editor form — a thin wrapper over the shared
 * CodePreviewCard (extracted alongside the Blueprint editor's JSON preview, its second use).
 * The editor pages wrap it (plus the ReferenceCheckPanel) in a sticky Stack so the whole
 * column stays in view while the (long) form scrolls.
 */
export default function YamlPreviewCard({ yaml, embedded }: { yaml: string; embedded?: boolean }) {
  const { t } = useTranslation();
  return <CodePreviewCard title={t("catalog.preview")} label={t("catalog.preview")} text={yaml} embedded={embedded} />;
}
