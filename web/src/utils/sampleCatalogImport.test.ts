/// <reference types="node" />

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";
import { parseCatalogYaml } from "./catalogImport";

const SAMPLE_PATH = resolve(
  process.cwd(),
  "../sample-data/backstage/commerce-payments/catalog-info.yaml",
);

describe("Backstage commerce/payments sample", () => {
  test("all documents pass the production YAML parser and retain the deliberate bad references", () => {
    const result = parseCatalogYaml(readFileSync(SAMPLE_PATH, "utf8"));

    expect(result.errors).toEqual([]);
    expect(result.documents).toHaveLength(34);

    const byName = new Map(result.documents.map((document) => [document.metadata.name, document]));
    expect(byName.get("catalog-service")?.spec.dependsOn).toContain(
      "template:default/nodejs-service-template",
    );
    expect(byName.get("legacy-invoicing")?.spec).toMatchObject({
      owner: "group:default/billing-squad",
      dependsOn: expect.arrayContaining(["resource:default/invoice-archive", "orders-db"]),
    });
  });
});
