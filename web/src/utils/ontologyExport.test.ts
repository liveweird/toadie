import { describe, expect, test } from "vitest";
import type { Blueprint } from "../api/blueprints";
import type { Entity } from "../api/entities";
import {
  blueprintExportDocument,
  blueprintsExportJson,
  entityExportDocument,
  entityExportFileName,
  entityExportJson,
} from "./ontologyExport";
import { parseOntologySources } from "./ontologyImport";

const BLUEPRINT: Blueprint = {
  id: 1,
  identifier: "service",
  title: "Service",
  description: "A deployable service",
  icon: "Server",
  schema: { properties: { language: { type: "string", title: "Language" } }, required: [] },
  relations: { owningTeam: { title: "Owned by", target: "_team", required: false, many: false } },
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 1,
  updatedAt: 1,
  system: false,
};

const DIRECT_ENTITY: Entity = {
  id: 5,
  blueprint: "service",
  blueprintId: 1,
  identifier: "checkout",
  title: "Checkout",
  icon: "Rocket",
  team: ["platform"],
  properties: { language: "kotlin" },
  relations: { owningTeam: "platform" },
  findings: [],
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 1,
  updatedAt: 1,
};

describe("blueprintExportDocument", () => {
  test("carries identity + the Port document fields, omitting undefined members", () => {
    const doc = blueprintExportDocument(BLUEPRINT);
    expect(doc).toEqual({
      identifier: "service",
      title: "Service",
      description: "A deployable service",
      icon: "Server",
      schema: BLUEPRINT.schema,
      relations: BLUEPRINT.relations,
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
    });
    expect(doc).not.toHaveProperty("id");
    expect(doc).not.toHaveProperty("system");
    expect(doc).not.toHaveProperty("createdBy");
    expect(doc).not.toHaveProperty("ownership");
    expect(doc).not.toHaveProperty("hierarchyRelations");
  });

  test("carries ownership and hierarchyRelations when set", () => {
    const withOwnership: Blueprint = {
      ...BLUEPRINT,
      ownership: { type: "Direct" },
      hierarchyRelations: { composition: "owningTeam" },
    };
    const doc = blueprintExportDocument(withOwnership);
    expect(doc.ownership).toEqual({ type: "Direct" });
    expect(doc.hierarchyRelations).toEqual({ composition: "owningTeam" });
  });

  test("omits hierarchyRelations when the map is present but empty", () => {
    const withEmptyMap: Blueprint = { ...BLUEPRINT, hierarchyRelations: {} };
    const doc = blueprintExportDocument(withEmptyMap);
    expect(doc).not.toHaveProperty("hierarchyRelations");
  });
});

describe("entityExportDocument", () => {
  test("keeps team for a Direct/absent-ownership blueprint and strips computed properties", () => {
    const withComputed: Blueprint = {
      ...BLUEPRINT,
      mirrorProperties: { teamName: { title: "Team name", path: "owningTeam.$title" } },
    };
    const entityWithComputed: Entity = { ...DIRECT_ENTITY, properties: { language: "kotlin", teamName: "Platform" } };

    const doc = entityExportDocument(entityWithComputed, withComputed);

    expect(doc).toEqual({
      blueprint: "service",
      identifier: "checkout",
      title: "Checkout",
      icon: "Rocket",
      team: ["platform"],
      properties: { language: "kotlin" },
      relations: { owningTeam: "platform" },
    });
  });

  test("drops team for an Inherited-ownership blueprint", () => {
    const inherited: Blueprint = { ...BLUEPRINT, ownership: { type: "Inherited", path: "owningTeam" } };
    const doc = entityExportDocument(DIRECT_ENTITY, inherited);
    expect(doc).not.toHaveProperty("team");
  });

  test("carries exactly the wire fields, in order, for a Direct-ownership entity with an icon", () => {
    const doc = entityExportDocument(DIRECT_ENTITY, BLUEPRINT);
    expect(Object.keys(doc)).toEqual(["blueprint", "identifier", "title", "icon", "team", "properties", "relations"]);
  });
});

describe("entityExportFileName", () => {
  test("names the file after the blueprint and identifier", () => {
    expect(entityExportFileName(DIRECT_ENTITY)).toBe("toadie-entity-service-checkout.json");
  });

  test("replaces every identifier character outside the safe filename charset with an underscore", () => {
    const entity: Entity = { ...DIRECT_ENTITY, identifier: "a/b:c@d" };
    expect(entityExportFileName(entity)).toBe("toadie-entity-service-a_b_c_d.json");
  });

  test("sanitizes the blueprint half too (its grammar allows @ : / =)", () => {
    const entity: Entity = { ...DIRECT_ENTITY, blueprint: "payments/core:v2" };
    expect(entityExportFileName(entity)).toBe("toadie-entity-payments_core_v2-checkout.json");
  });
});

describe("the export round trip", () => {
  test("blueprintsExportJson parses back with zero stripped keys", () => {
    const json = blueprintsExportJson([BLUEPRINT]);
    const { documents, errors } = parseOntologySources([{ label: "export", text: json }]);
    expect(errors).toEqual([]);
    expect(documents).toHaveLength(1);
    expect(documents[0].stripped).toEqual([]);
    expect(documents[0].body).toEqual(blueprintExportDocument(BLUEPRINT));
  });

  test("entityExportJson parses back as one bare entity document, with zero stripped keys", () => {
    const json = entityExportJson(DIRECT_ENTITY, BLUEPRINT);
    const { documents, errors } = parseOntologySources([{ label: "export", text: json }], [BLUEPRINT]);
    expect(errors).toEqual([]);
    expect(documents).toHaveLength(1);
    expect(documents[0].stripped).toEqual([]);
    expect(documents[0].strippedComputed).toEqual([]);
    expect(documents[0].body).toEqual(entityExportDocument(DIRECT_ENTITY, BLUEPRINT));
  });
});
