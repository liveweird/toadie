import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import { useForm } from "@mantine/form";
import type { Blueprint } from "../api/blueprints";
import EntityFormFields from "./EntityFormFields";
import { emptyEntityForm, type EntityFormValues } from "../utils/entityForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const BLUEPRINT_NO_COMPUTED = {
  id: 1,
  identifier: "team",
  title: "Team",
  schema: { properties: {}, required: [] },
  relations: {},
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
} as unknown as Blueprint;

const BLUEPRINT_WITH_COMPUTED = {
  ...BLUEPRINT_NO_COMPUTED,
  identifier: "service",
  mirrorProperties: { domain_title: { title: "Domain title", path: "system.domain.$title" } },
} as unknown as Blueprint;

function Harness({ blueprint, computed }: { blueprint: Blueprint; computed?: Record<string, unknown> }) {
  const form = useForm<EntityFormValues>({ initialValues: emptyEntityForm(blueprint) });
  return <EntityFormFields form={form} blueprint={blueprint} computed={computed} />;
}

describe("EntityFormFields — Computed section (v1.27.0)", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("no computed prop renders no Computed group, even when the blueprint declares computed properties", () => {
    renderWithProviders(<Harness blueprint={BLUEPRINT_WITH_COMPUTED} />);
    expect(screen.queryByRole("group", { name: "Computed" })).not.toBeInTheDocument();
  });

  test("a computed prop for a blueprint with no computed properties renders no Computed group", () => {
    renderWithProviders(<Harness blueprint={BLUEPRINT_NO_COMPUTED} computed={{}} />);
    expect(screen.queryByRole("group", { name: "Computed" })).not.toBeInTheDocument();
  });

  test("a computed prop with declared computed properties renders the group LAST, with its value", () => {
    renderWithProviders(<Harness blueprint={BLUEPRINT_WITH_COMPUTED} computed={{ domain_title: "Commerce" }} />);

    const computedGroup = screen.getByRole("group", { name: "Computed" });
    expect(screen.getByText("Domain title")).toBeInTheDocument();
    expect(screen.getByText("Commerce")).toBeInTheDocument();

    const groups = screen.getAllByRole("group");
    expect(groups[groups.length - 1]).toBe(computedGroup);
  });
});
