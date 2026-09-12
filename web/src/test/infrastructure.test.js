import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";

// Read repository configuration as test data without exposing it through Vite's dev server.
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const composeSource = readFileSync(resolve(repoRoot, "docker-compose.yaml"), "utf8");
const ciSource = readFileSync(resolve(repoRoot, ".github/workflows/ci.yml"), "utf8");
const e2eSource = readFileSync(resolve(repoRoot, ".github/workflows/e2e.yml"), "utf8");
const toolchainSource = readFileSync(resolve(repoRoot, "mise.toml"), "utf8");
const dockerfileSource = readFileSync(resolve(repoRoot, "Dockerfile"), "utf8");

describe("deployment and verification safety defaults", () => {
  it("uses the full Temurin version from the local toolchain in CI", () => {
    const ci = parse(ciSource);
    const javaSetup = ci.jobs.backend.steps.find((step) => step.uses?.startsWith("actions/setup-java@"));
    const localVersion = toolchainSource.match(/^java = "temurin-([^"]+)"$/m)?.[1];
    expect(localVersion).toBeTruthy();
    expect(javaSetup.with.distribution).toBe("temurin");
    expect(javaSetup.with["java-version"]).toBe(localVersion);
  });

  it("publishes every demo service on loopback, never on the LAN", () => {
    const compose = parse(composeSource);
    const ports = Object.values(compose.services).flatMap((service) => service.ports ?? []);
    expect(ports).toHaveLength(3);
    for (const port of ports) expect(port).toMatch(/^127\.0\.0\.1:\d+:\d+$/);
  });

  it("runs every quality gate on pushes, pull requests, and merge queues", () => {
    const ci = parse(ciSource);
    for (const event of ["push", "pull_request", "merge_group"]) {
      expect(ci.on).toHaveProperty(event, null); // no path/branch skips on a required gate
    }
    expect(ci.permissions).toEqual({ contents: "read" });
    expect(ci.jobs["quality-gate"].needs).toEqual(["backend", "frontend", "e2e"]);
    expect(ci.jobs["quality-gate"].if).toBe("${{ always() }}");
    const gate = ci.jobs["quality-gate"].steps?.[0].run;
    for (const job of ["BACKEND", "FRONTEND", "E2E"]) expect(gate).toContain(`test "$${job}" = success`);
    expect(ci.jobs.e2e.uses).toBe("./.github/workflows/e2e.yml");
  });

  it("keeps backend, frontend, and contract checks in the automatic workflow", () => {
    const ci = parse(ciSource);
    const backend = ci.jobs.backend.steps?.map((step) => step.run ?? "").join("\n");
    const frontend = ci.jobs.frontend.steps?.map((step) => step.run ?? "").join("\n");
    expect(backend).toContain("./gradlew build :server:koverXmlReport");
    for (const script of ["lint:api", "check:api", "build", "lint", "knip", "test:coverage"]) {
      expect(frontend).toContain(`npm run ${script}`);
    }
  });

  it("cleans only the explicit disposable CI project even after setup failure", () => {
    const workflow = parse(e2eSource);
    expect(workflow.on).toHaveProperty("workflow_call");
    const steps = workflow.jobs.e2e.steps ?? [];
    expect(steps.some((step) => step.run === "docker compose -p toadie-ci up -d --build")).toBe(true);
    const cleanup = steps.find((step) => step.run?.includes("down --volumes"));
    expect(cleanup?.if).toBe("${{ always() }}");
    expect(cleanup?.run).toBe("docker compose -p toadie-ci down --volumes --remove-orphans");
    expect(steps.some((step) => step.run?.includes("npm run typecheck && npm run check:scenarios"))).toBe(true);
  });

  it("runs the container as a non-root user", () => {
    const runtimeStage = dockerfileSource.slice(dockerfileSource.indexOf("AS runtime"));
    const userLine = runtimeStage.match(/^USER\s+(\S+)$/m)?.[1];
    expect(userLine).toBeTruthy();
    expect(userLine).not.toBe("root");
  });

  it("pins both Temurin build and runtime tags to the local toolchain's exact patch", () => {
    const localVersion = toolchainSource.match(/^java = "temurin-([^"]+)"$/m)?.[1];
    expect(localVersion).toBeTruthy();
    // mise.toml spells "21.0.11+10.0.LTS"; Docker Hub's eclipse-temurin tags spell the same
    // build as "21.0.11_10" (underscore, LTS suffix dropped) — reduce both to that form.
    const dockerTag = localVersion.replace("+", "_").replace(/\.\d+\.LTS$/, "");
    const buildTags = [...dockerfileSource.matchAll(/eclipse-temurin:(\S+)-(?:jdk|jre)/g)].map((m) => m[1]);
    expect(buildTags).toHaveLength(2); // the server build stage and the runtime stage
    for (const tag of buildTags) expect(tag).toBe(dockerTag);
  });

  it("gives the compose app service a container healthcheck", () => {
    const compose = parse(composeSource);
    expect(compose.services.app.healthcheck).toBeTruthy();
    expect(compose.services.app.healthcheck.test).toBeTruthy();
  });
});
