import { CloseButton, TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";

/** The standard filter input: contains-placeholder (overridable) plus a one-click clear button. */
export default function ClearableTextInput({
  label,
  value,
  onChange,
  clearLabel,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  clearLabel: string;
  placeholder?: string;
}) {
  const { t } = useTranslation();
  return (
    <TextInput
      label={label}
      placeholder={placeholder ?? t("common.filter.contains")}
      value={value}
      onChange={(e) => onChange(e.currentTarget.value)}
      rightSection={
        value ? (
          <CloseButton
            size="sm"
            aria-label={clearLabel}
            tabIndex={-1}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => onChange("")}
          />
        ) : null
      }
      rightSectionPointerEvents="auto"
    />
  );
}
