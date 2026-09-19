import { describe, expect, test } from "vitest";
import { MAX_SOURCE_URL_LENGTH, normalizeSourceUrl, sourceUrlProblem } from "./sourceUrl";

describe("sourceUrlProblem", () => {
  test("a blank value has no problem — the field is optional", () => {
    expect(sourceUrlProblem("")).toBeNull();
    expect(sourceUrlProblem("   ")).toBeNull();
  });

  test("a valid https URL has no problem", () => {
    expect(sourceUrlProblem("https://example.com/catalog-info.yaml")).toBeNull();
  });

  test("a non-https scheme is a grammar problem", () => {
    expect(sourceUrlProblem("http://example.com/catalog-info.yaml")).toBe("grammar");
  });

  test("embedded userinfo is a grammar problem", () => {
    expect(sourceUrlProblem("https://user:pass@example.com/catalog-info.yaml")).toBe("grammar");
  });

  test("an unparseable value is a grammar problem", () => {
    expect(sourceUrlProblem("not a url")).toBe("grammar");
  });

  test("a URL past the length ceiling is a length problem", () => {
    const overLong = `https://example.com/${"a".repeat(MAX_SOURCE_URL_LENGTH)}`;
    expect(sourceUrlProblem(overLong)).toBe("length");
  });
});

describe("normalizeSourceUrl", () => {
  test("rewrites a GitHub blob link to its raw.githubusercontent.com form", () => {
    expect(
      normalizeSourceUrl("https://github.com/acme/service/blob/main/catalog-info.yaml"),
    ).toBe("https://raw.githubusercontent.com/acme/service/main/catalog-info.yaml");
    expect(
      normalizeSourceUrl("https://github.com/acme/service/blob/v1.2/nested/dir/catalog-info.yaml"),
    ).toBe("https://raw.githubusercontent.com/acme/service/v1.2/nested/dir/catalog-info.yaml");
  });

  test("rewrites a GitLab blob link to its raw form (self-hosted included)", () => {
    expect(
      normalizeSourceUrl("https://gitlab.com/acme/service/-/blob/main/catalog-info.yaml"),
    ).toBe("https://gitlab.com/acme/service/-/raw/main/catalog-info.yaml");
    expect(
      normalizeSourceUrl("https://git.corp.example/group/sub/repo/-/blob/main/catalog-info.yaml"),
    ).toBe("https://git.corp.example/group/sub/repo/-/raw/main/catalog-info.yaml");
  });

  test("leaves raw links and everything else untouched (trim aside)", () => {
    const raw = "https://raw.githubusercontent.com/acme/service/main/catalog-info.yaml";
    expect(normalizeSourceUrl(raw)).toBe(raw);
    expect(normalizeSourceUrl("  https://example.com/catalog-info.yaml  ")).toBe(
      "https://example.com/catalog-info.yaml",
    );
    expect(normalizeSourceUrl("https://example.com/blob/of/text.yaml")).toBe(
      "https://example.com/blob/of/text.yaml",
    );
  });
});
