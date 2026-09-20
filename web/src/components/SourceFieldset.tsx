import type { ReactNode } from "react";
import { Fieldset, TextInput } from "@mantine/core";
import type { GetInputPropsReturnType } from "@mantine/form";
import { BELOW_INPUT } from "../utils/charCount";
import { MAX_SOURCE_URL_LENGTH } from "../utils/sourceUrl";

/**
 * The source reference fieldset — provenance (the https URL of the file/entity/blueprint's
 * repo copy), deliberately its own fieldset rather than the document fields: it is envelope
 * state beside the document, never part of the stored content. Byte-identical across the
 * catalog file, entity, and blueprint editors (`CatalogFileFormFields.tsx`,
 * `EntityFormFields.tsx`, `BlueprintFormFields.tsx`) except for i18n strings and the
 * placeholder, which each caller supplies already translated — this component carries no
 * namespace knowledge. Backs each list's Last-sync column and its "Sync from source" action.
 */
export default function SourceFieldset({
  legend,
  label,
  hint,
  placeholder,
  inputProps,
}: {
  legend: ReactNode;
  label: ReactNode;
  hint: ReactNode;
  placeholder: string;
  /** `form.getInputProps("sourceUrl")` from the caller's own form. */
  inputProps: GetInputPropsReturnType;
}) {
  return (
    <Fieldset legend={legend}>
      <TextInput
        label={label}
        placeholder={placeholder}
        maxLength={MAX_SOURCE_URL_LENGTH}
        description={hint}
        inputWrapperOrder={[...BELOW_INPUT]}
        {...inputProps}
      />
    </Fieldset>
  );
}
