import type { TFunction } from "i18next";
import type { TagCategory, TagCategoryBody } from "../api/tagCategories";
import { MAX_ENTITY_PART_LENGTH, TAG_RE } from "./catalogFileForm";
import { saveErrorMessage } from "./saveError";

// The server's caps (tags/TagCategory.kt) — mirror, don't invent.
export const MAX_CATEGORY_NAME_LENGTH = MAX_ENTITY_PART_LENGTH;
const MAX_CATEGORY_TAGS = 100;

export type TagCategoryFormValues = {
  name: string;
  tags: string[];
  kinds: string[];
};

export function emptyTagCategoryForm(): TagCategoryFormValues {
  return { name: "", tags: [], kinds: [] };
}

export function toTagCategoryFormValues(category: TagCategory): TagCategoryFormValues {
  return { name: category.name, tags: [...category.tags], kinds: [...category.kinds] };
}

export function toTagCategoryBody(values: TagCategoryFormValues): TagCategoryBody {
  return {
    name: values.name.trim(),
    tags: values.tags.map((tag) => tag.trim()),
    kinds: values.kinds,
  };
}

/** Validation rules for the create/edit modal (mirrors the server's checks). */
export function tagCategoryFormValidation(t: TFunction) {
  return {
    name: (value: string) => {
      const v = value.trim();
      return v.length >= 1 && v.length <= MAX_CATEGORY_NAME_LENGTH ? null : t("tags.validation.name");
    },
    tags: (values: string[]) => {
      if (values.length === 0) return t("tags.validation.tagsRequired");
      if (values.length > MAX_CATEGORY_TAGS) return t("tags.validation.tagsCount");
      const bad = values
        .map((tag) => tag.trim())
        .find((tag) => tag.length < 1 || tag.length > MAX_ENTITY_PART_LENGTH || !TAG_RE.test(tag));
      if (bad !== undefined) return t("tags.validation.tag", { tag: bad });
      const folded = values.map((tag) => tag.trim().toLowerCase());
      return folded.length === new Set(folded).size ? null : t("tags.validation.tagsDuplicate");
    },
    kinds: (values: string[]) => (values.length > 0 ? null : t("tags.validation.kindsRequired")),
  };
}

/**
 * The category save's fixed error vocabulary. The server's two conflict causes (name taken,
 * tag owned by another category) share ONE combined 409 message.
 */
export function tagCategorySaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    conflict: "tags.saveConflict",
    invalid: "tags.saveInvalid",
    notFound: "tags.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}
