import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { lockfiles } from "./repoLockfiles.js";

// Read repository configuration as test data without exposing it through Vite's dev server.
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const composeSource = readFileSync(resolve(repoRoot, "docker-compose.yaml"), "utf8");
const ssoOverlaySource = readFileSync(resolve(repoRoot, "e2e/compose.sso.yaml"), "utf8");
const ciSource = readFileSync(resolve(repoRoot, ".github/workflows/ci.yml"), "utf8");
const imageCheckPath = resolve(repoRoot, ".github/scripts/image-check.sh");
const imageCheckSource = readFileSync(imageCheckPath, "utf8");
const e2eSource = readFileSync(resolve(repoRoot, ".github/workflows/e2e.yml"), "utf8");
const toolchainSource = readFileSync(resolve(repoRoot, "mise.toml"), "utf8");
const dockerfileSource = readFileSync(resolve(repoRoot, "Dockerfile"), "utf8");
const dependabotSource = readFileSync(resolve(repoRoot, ".github/dependabot.yml"), "utf8");
const appDeploymentSource = readFileSync(resolve(repoRoot, "k8s/app-deployment.yaml"), "utf8");
const applicationYamlSource = readFileSync(resolve(repoRoot, "server/src/main/resources/application.yaml"), "utf8");
const openTelemetrySource = readFileSync(resolve(repoRoot, "core/src/commonMain/kotlin/OpenTelemetry.kt"), "utf8");
const postgresDeploymentSource = readFileSync(resolve(repoRoot, "k8s/postgres-deployment.yaml"), "utf8");
const fixupWorkflowSource = readFileSync(resolve(repoRoot, ".github/workflows/dependabot-gradle-fixup.yml"), "utf8");
const fixupValidatorPath = resolve(repoRoot, ".github/scripts/gradle-fixup-validate.mjs");
const fixupCliPath = resolve(repoRoot, ".github/scripts/dependabot-gradle-fixup.sh");

// Whole whitespace-delimited tokens of a text (quotes stripped): a plain substring check would let
// "gradle.lockfile" hide inside "settings-gradle.lockfile".
const tokens = (text) => new Set(text.split(/\s+/).map((token) => token.replace(/^["']|["']$/g, "")));

// A runtime tag follows the toolchain tag ("21.0.12_8") when it is the same feature/update line,
// optionally as a respin ("21.0.12.N_M"); a different update (21.0.13_9) must fail.
function runtimeFollowsToolchain(runtimeTag, toolchainTag) {
  const base = toolchainTag.split("_")[0];
  return new RegExp(`^${base.replaceAll(".", "\\.")}(\\.\\d+)?_\\d+$`).test(runtimeTag);
}

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
    expect(ci.jobs["quality-gate"].needs).toEqual(["backend", "frontend", "gradle-vulnerability-scan", "image", "e2e"]);
    expect(ci.jobs["quality-gate"].if).toBe("${{ always() }}");
    const gate = ci.jobs["quality-gate"].steps?.[0].run;
    for (const job of ["BACKEND", "FRONTEND", "GRADLE_SCAN", "IMAGE", "E2E"]) expect(gate).toContain(`test "$${job}" = success`);
    expect(ci.jobs.e2e.uses).toBe("./.github/workflows/e2e.yml");
  });

  it("keeps backend, frontend, and contract checks in the automatic workflow", () => {
    const ci = parse(ciSource);
    const backend = ci.jobs.backend.steps?.map((step) => step.run ?? "").join("\n");
    const frontend = ci.jobs.frontend.steps?.map((step) => step.run ?? "").join("\n");
    expect(backend).toContain("./gradlew build :server:koverXmlReport");
    // Strict dependency verification: a missing or mismatched artifact checksum fails the build.
    expect(backend).toContain("--dependency-verification strict");
    expect(backend).toContain("git diff --exit-code -- gradle/verification-metadata.xml");
    for (const script of ["lint:api", "check:api", "build", "lint", "knip", "test:coverage"]) {
      expect(frontend).toContain(`npm run ${script}`);
    }
  });

  it("scans every Gradle lockfile for HIGH/CRITICAL advisories with a digest-pinned Trivy", () => {
    const ci = parse(ciSource);
    const scan = ci.jobs["gradle-vulnerability-scan"].steps.map((step) => step.run ?? "").join("\n");
    expect(scan).toMatch(/ghcr\.io\/aquasecurity\/trivy:[\d.]+@sha256:[0-9a-f]{64}/);
    expect(scan).toContain('row[0] in ("HIGH", "CRITICAL")');
    expect(scan).toContain("Lockfiles missing from the scan");
    expect(existsSync(resolve(repoRoot, "gradle/verification-metadata.xml"))).toBe(true);

    // A lockfile is only covered when it appears as a WHOLE whitespace-delimited token (`tokens`). The
    // lockfiles are globbed from the repository, so a newly added one is demanded automatically.
    const copied = tokens(scan.split("\n").filter((line) => line.trim().startsWith("cp ")).join("\n"));
    const backend = ci.jobs.backend.steps.map((step) => step.run ?? "").find((run) => run.includes("git diff --exit-code"));
    const tripwire = tokens(backend ?? "");
    const dockerCopies = tokens(dockerfileSource.split("\n").filter((line) => line.startsWith("COPY ")).join("\n"));
    const locks = lockfiles(repoRoot);
    expect(locks).toContain("gradle.lockfile");
    expect(locks).toContain("server/buildscript-gradle.lockfile");
    for (const lock of locks) {
      expect(copied, `Trivy cp lines miss ${lock}`).toContain(lock);
      expect(tripwire, `CI git diff list misses ${lock}`).toContain(lock);
      expect(dockerCopies, `Dockerfile COPY lines miss ${lock}`).toContain(lock);
    }
    expect(tripwire).toContain("gradle/verification-metadata.xml");
  });

  it("builds both architectures and scans the image OS layer on every run", () => {
    const ci = parse(ciSource);
    const image = ci.jobs.image;
    expect(image.name).toBe("Container image");
    expect(image["timeout-minutes"]).toBe(30);
    expect(image.if).toBeUndefined(); // every event, like the other gate jobs
    expect(image.steps.some((step) => step.run === "bash .github/scripts/image-check.sh")).toBe(true);
    const checkout = image.steps.find((step) => step.uses?.startsWith("actions/checkout@"));
    expect(checkout.with["persist-credentials"]).toBe(false);
    const upload = image.steps.find((step) => step.uses?.startsWith("actions/upload-artifact@"));
    expect(upload.if).toBe("${{ always() }}");
    expect(upload.with["retention-days"]).toBe(7);
    // No secrets or write token reach this job: it never publishes (registry publishing is parked).
    expect(ciSource).not.toMatch(/\bsecrets\./);
    expect(ci.permissions).toEqual({ contents: "read" });

    // The script is syntactically valid bash (execFileSync throws on a non-zero exit).
    execFileSync("bash", ["-n", imageCheckPath]);
    // The fix-up validator and CLI parse too (the validator is Node-only, the CLI plain bash).
    execFileSync(process.execPath, ["--check", fixupValidatorPath]);
    execFileSync("bash", ["-n", fixupCliPath]);

    // Comments cannot satisfy a flag: match the code only, with line continuations joined.
    const code = imageCheckSource
      .split("\n")
      .filter((line) => !/^\s*#/.test(line))
      .join("\n")
      .replace(/\\\n/g, " ");
    expect(code).toContain("--platform linux/amd64,linux/arm64");
    expect(code).toContain("--install arm64");
    const trivyScan = code.split("\n").find((line) => line.includes("image --input"));
    expect(trivyScan).toBeTruthy();
    for (const flag of ["--scanners vuln", "--pkg-types os", "--ignore-unfixed", "--severity HIGH,CRITICAL", "--exit-code 1"]) {
      expect(trivyScan).toContain(flag);
    }
    // The blocking property: Trivy's exit status is captured, and the script exits with it after
    // the report guards; a clean exit with a missing report or without an os-pkgs target still fails.
    expect(code).toContain("|| scan_status=$?");
    expect(code).toContain('exit "$scan_status"');
    expect(code).toContain('"os-pkgs"');
    expect(code).toContain("no OS-package target");
    expect(code).toMatch(/elif \[ "\$scan_status" -eq 0 \]; then[\s\S]*?exit 1/);

    // Every image the script runs is pinned by tag AND digest.
    expect(imageCheckSource).toMatch(/moby\/buildkit:v[\d.]+@sha256:[0-9a-f]{64}/);
    expect(imageCheckSource).toMatch(/tonistiigi\/binfmt:qemu-v[\d.]+-\d+@sha256:[0-9a-f]{64}/);
    const trivyPin = /ghcr\.io\/aquasecurity\/trivy:[\d.]+@sha256:[0-9a-f]{64}/;
    const scriptTrivy = imageCheckSource.match(trivyPin)?.[0];
    const advisoryScan = ci.jobs["gradle-vulnerability-scan"].steps.map((step) => step.run ?? "").join("\n");
    expect(scriptTrivy).toBeTruthy();
    expect(advisoryScan.match(trivyPin)?.[0]).toBe(scriptTrivy); // one Trivy version for both scans

    // Nothing is published.
    expect(code).not.toMatch(/push|type=registry|type=image|docker login/);
    expect(code).toMatch(/--output "type=docker,dest=/); // the only output: a local tar
  });

  it("fails the API lint on warnings for both the contract and the conformant fixture", () => {
    const lintApi = JSON.parse(readFileSync(resolve(repoRoot, "web/package.json"), "utf8")).scripts["lint:api"];
    expect(lintApi).toContain("--fail-severity=warn");
    const invocations = lintApi.split("&&").map((part) => part.trim());
    expect(invocations).toHaveLength(2); // the contract and the fixture
    for (const invocation of invocations) expect(invocation).toContain("--fail-severity=warn");
  });

  it("supplies the pre-change GraphQL schema baseline with full Git history", () => {
    const backend = parse(ciSource).jobs.backend;
    const checkout = backend.steps.find((step) => step.uses?.startsWith("actions/checkout@"));
    expect(checkout.with["fetch-depth"]).toBe(0);
    const baseline = backend.env.GRAPHQL_BASELINE_REVISION;
    for (const eventBase of ["pull_request.base.sha", "merge_group.base_sha", "event.before"]) {
      expect(baseline).toContain(eventBase);
    }
    expect(baseline).not.toContain("github.sha");
    // A force-pushed branch's unreachable before SHA falls back to its merge-base, for push events only.
    const resolver = backend.steps.find((step) => step.id === "baseline");
    expect(resolver.run).toContain('"$GITHUB_EVENT_NAME" = "push"');
    expect(resolver.run).toContain("git cat-file -e");
    expect(resolver.run).toContain("git merge-base origin/master HEAD");
    const build = backend.steps.find((step) => step.run?.startsWith("./gradlew build"));
    expect(build.env.GRAPHQL_BASELINE_REVISION).toBe("${{ steps.baseline.outputs.revision }}");
    const smokeSteps = parse(e2eSource).jobs.e2e.steps;
    expect(smokeSteps.some((step) => step.run === "npm run smoke:graphql -- compose")).toBe(true);
  });

  it("cleans only the explicit disposable CI project even after setup failure", () => {
    const workflow = parse(e2eSource);
    expect(workflow.on).toHaveProperty("workflow_call");
    const steps = workflow.jobs.e2e.steps ?? [];
    // Every compose call carries the SSO overlay pair, so the stub identity provider is started, logged and removed too.
    const files = "-f docker-compose.yaml -f e2e/compose.sso.yaml";
    expect(steps.some((step) => step.run === `docker compose -p toadie-ci ${files} up -d --build`)).toBe(true);
    const cleanup = steps.find((step) => step.run?.includes("down --volumes"));
    expect(cleanup?.if).toBe("${{ always() }}");
    expect(cleanup?.run).toBe(`docker compose -p toadie-ci ${files} down --volumes --remove-orphans`);
    const logs = steps.find((step) => step.run?.includes("logs --no-color"));
    expect(logs?.run).toBe(`docker compose -p toadie-ci ${files} logs --no-color > e2e/stack.log`);
    const wait = steps.find((step) => step.run?.includes("/readyz"));
    expect(wait?.run).toContain("http://localhost:18090/healthz");
    expect(steps.some((step) => step.run?.includes("npm run typecheck && npm run check:scenarios"))).toBe(true);
    // No compose invocation of the project may omit the overlay.
    for (const step of steps) if (step.run?.includes("docker compose")) expect(step.run).toContain(files);
  });

  it("keeps the SSO e2e overlay to the stub identity provider on the Dockerfile's Node major, with no fixed container name", () => {
    const overlay = parse(ssoOverlaySource);
    // Tag-pinned like compose's postgres/mailpit (Dependabot tracks only the Dockerfile's digest pins), on the same Node
    // major as the Dockerfile's SPA build stage, so a Dockerfile-only bump never turns this red.
    const dockerfileNode = dockerfileSource.match(/^FROM (?:--platform=\$BUILDPLATFORM )?node:(\d+)-alpine@sha256:[0-9a-f]{64} AS web$/m)?.[1];
    expect(dockerfileNode).toBeTruthy();
    expect(overlay.services["stub-idp"].image).toBe(`node:${dockerfileNode}-alpine`);
    expect(Object.keys(overlay.services).sort()).toEqual(["app", "stub-idp"]);
    for (const service of Object.values(overlay.services)) expect(service).not.toHaveProperty("container_name");
    // Loopback only, like every demo service.
    for (const port of overlay.services["stub-idp"].ports) expect(port).toMatch(/^127\.0\.0\.1:/);
    // The stub's issuer is the app's SSO_ISSUER, and the app waits for the stub.
    expect(overlay.services["stub-idp"].environment.STUB_IDP_ISSUER).toBe(overlay.services.app.environment.SSO_ISSUER);
    expect(overlay.services.app.depends_on["stub-idp"].condition).toBe("service_healthy");
    // The healthcheck names the IPv4 loopback: alpine's `localhost` is ::1 first and busybox wget never falls back.
    expect(overlay.services["stub-idp"].healthcheck.test.join(" ")).toContain("http://127.0.0.1:");
    // The overlay relies on the base file running the app in development mode (the only mode accepting an http issuer).
    expect(parse(composeSource).services.app.environment.KTOR_DEVELOPMENT).toBe("true");
  });

  it("runs the container as a non-root user", () => {
    const runtimeStage = dockerfileSource.slice(dockerfileSource.indexOf("AS runtime"));
    const userLine = runtimeStage.match(/^USER\s+(\S+)$/m)?.[1];
    expect(userLine).toBeTruthy();
    expect(userLine).not.toBe("root");
  });

  it("bakes MALLOC_ARENA_MAX=2 into the runtime stage so compose and k8s both inherit it", () => {
    // Native-memory bound (.claude/docs/security.md "Entity read memory budget" - Heap layout): without it
    // glibc keeps 8 x cores malloc arenas, ~2 MiB resident per JVM thread.
    const runtimeStage = dockerfileSource.slice(dockerfileSource.indexOf("AS runtime"));
    expect(runtimeStage).toMatch(/^ENV MALLOC_ARENA_MAX=2$/m);
    // Neither deployment descriptor may override it with a looser value.
    expect(composeSource).not.toContain("MALLOC_ARENA_MAX");
    expect(appDeploymentSource).not.toContain("MALLOC_ARENA_MAX");
  });

  it("passes the optional telemetry build identity through compose into the runtime stage, declared after every COPY and RUN", () => {
    // observability.md "Telemetry": the sampler records TOADIE_BUILD_VERSION/TOADIE_BUILD_COMMIT on its process row; empty = null.
    const runtimeStage = dockerfileSource.slice(dockerfileSource.indexOf("AS runtime"));
    const lines = runtimeStage.split("\n");
    const lastBuildStep = Math.max(...lines.map((line, i) => (/^(COPY|RUN)\s/.test(line) ? i : -1)));
    for (const name of ["TOADIE_BUILD_VERSION", "TOADIE_BUILD_COMMIT"]) {
      const arg = lines.findIndex((line) => line === `ARG ${name}=""`);
      expect(arg, `${name} ARG in the runtime stage`).toBeGreaterThan(lastBuildStep);
      expect(runtimeStage).toContain(`${name}=$${name}`);
      // Compose forwards the host variable, empty when unset, so `docker compose up --build` leaves the columns null.
      expect(parse(composeSource).services.app.build.args[name]).toBe("${" + name + ":-}");
    }
  });

  it("pins the Temurin build stage to the local toolchain's exact patch and the runtime to the same update", () => {
    const localVersion = toolchainSource.match(/^java = "temurin-([^"]+)"$/m)?.[1];
    expect(localVersion).toBeTruthy();
    // mise.toml spells "21.0.11+10.0.LTS"; Docker Hub's eclipse-temurin tags spell the same
    // build as "21.0.11_10" (underscore, LTS suffix dropped) — reduce both to that form.
    const dockerTag = localVersion.replace("+", "_").replace(/\.\d+\.LTS$/, "");
    const tags = (kind) => [...dockerfileSource.matchAll(new RegExp(`eclipse-temurin:(\\S+?)-${kind}(?:@sha256:[0-9a-f]{64})?\\b`, "g"))].map((m) => m[1]);
    // The build JDK must match mise/CI exactly; the runtime JRE may run a respin ahead (below).
    expect(tags("jdk")).toEqual([dockerTag]);
    expect(tags("jre")).toHaveLength(1);
    expect(runtimeFollowsToolchain(tags("jre")[0], dockerTag)).toBe(true);
  });

  it("lets the runtime JRE run a respin of the toolchain's update, never a different update", () => {
    expect(runtimeFollowsToolchain("21.0.12_8", "21.0.12_8")).toBe(true);
    expect(runtimeFollowsToolchain("21.0.12.1_1", "21.0.12_8")).toBe(true);
    expect(runtimeFollowsToolchain("21.0.12.2_3", "21.0.12_8")).toBe(true);
    expect(runtimeFollowsToolchain("21.0.13_9", "21.0.12_8")).toBe(false);
    expect(runtimeFollowsToolchain("21.0.13.1_1", "21.0.12_8")).toBe(false);
    expect(runtimeFollowsToolchain("21.0.120_8", "21.0.12_8")).toBe(false);
    expect(runtimeFollowsToolchain("17.0.12_8", "21.0.12_8")).toBe(false);
    expect(runtimeFollowsToolchain("21.0.12.1", "21.0.12_8")).toBe(false);
  });

  it("pins every Dockerfile base image by sha256 digest", () => {
    const froms = dockerfileSource.split("\n").filter((line) => /^FROM\s/.test(line));
    expect(froms).toHaveLength(3);
    for (const line of froms) expect(line).toMatch(/^FROM\s+(--platform=\$BUILDPLATFORM\s+)?\S+@sha256:[0-9a-f]{64}(\s+AS\s+\S+)?$/);
  });

  it("cross-builds: the two build stages run on the build platform, only the runtime stage is per-platform", () => {
    // web/dist and the installDist JARs are platform-independent, so a multi-arch build compiles them
    // once; the runtime stage (and its emulated useradd) is the only stage that follows the target.
    const froms = dockerfileSource.split("\n").filter((line) => /^FROM\s/.test(line));
    const stage = (name) => froms.find((line) => line.endsWith(` AS ${name}`));
    expect(stage("web")).toMatch(/^FROM --platform=\$BUILDPLATFORM node:/);
    expect(stage("server")).toMatch(/^FROM --platform=\$BUILDPLATFORM eclipse-temurin:/);
    expect(stage("runtime")).not.toContain("--platform");
  });

  it("keeps the Node major identical across mise.toml, the Dockerfile and both workflows", () => {
    const miseMajor = toolchainSource.match(/^node = "(\d+)/m)?.[1];
    expect(miseMajor).toBeTruthy();
    expect(dockerfileSource.match(/^FROM (?:--platform=\$BUILDPLATFORM )?node:(\d+)-alpine/m)?.[1]).toBe(miseMajor);
    const setupNodeVersions = (source) =>
      Object.values(parse(source).jobs)
        .flatMap((job) => job.steps ?? [])
        .filter((step) => step.uses?.startsWith("actions/setup-node@"))
        .map((step) => String(step.with["node-version"]));
    for (const source of [ciSource, e2eSource]) {
      const versions = setupNodeVersions(source);
      expect(versions.length).toBeGreaterThan(0);
      for (const version of versions) expect(version).toBe(miseMajor);
    }
  });

  it("gives the image its own HEALTHCHECK probing /readyz", () => {
    const runtimeStage = dockerfileSource.slice(dockerfileSource.indexOf("AS runtime"));
    const healthcheck = runtimeStage.match(/^HEALTHCHECK\s.*$/m)?.[0];
    expect(healthcheck).toBeTruthy();
    expect(healthcheck).toContain("CMD");
    expect(healthcheck).toContain("/readyz");
  });

  it("makes the image HEALTHCHECK a real check in production mode: the forwarded-proto header and an exact 200", () => {
    // The image runs KTOR_DEVELOPMENT=false, where HttpsRedirect answers a plain-HTTP /readyz with a 301.
    // `curl --fail` passes on a 3xx, so a bare probe stays green whatever the database does. The app honours
    // X-Forwarded-Proto only behind HTTP_BEHIND_PROXY=true (plugins/Http.kt), which the k8s Deployment sets too.
    const runtimeStage = dockerfileSource.slice(dockerfileSource.indexOf("AS runtime"));
    expect(runtimeStage).toMatch(/^ENV KTOR_DEVELOPMENT=false$/m);
    const healthcheck = runtimeStage.match(/^HEALTHCHECK\s.*$/m)[0];
    expect(healthcheck).toMatch(/X-Forwarded-Proto: https/);
    expect(healthcheck).toContain("%{http_code}");
    expect(healthcheck).toMatch(/= 200\b/);
    expect(healthcheck).not.toContain("--fail");
    const readiness = parse(appDeploymentSource).spec.template.spec.containers[0].readinessProbe.httpGet;
    expect(readiness.httpHeaders).toContainEqual({ name: "X-Forwarded-Proto", value: "https" });
  });

  it("passes the OpenTelemetry exporter selectors through compose and the k8s Deployment with the code's defaults, and no endpoint", () => {
    // The defaults are parsed from the code, never restated here.
    const defaults = Object.fromEntries(
      [...openTelemetrySource.matchAll(/"otel\.(\w+)\.exporter"\s+to\s+"([^"]+)"/g)].map(([, signal, value]) => [signal, value]),
    );
    expect(Object.keys(defaults).sort()).toEqual(["logs", "metrics", "traces"]);

    const environment = parse(composeSource).services.app.environment;
    const env = parse(appDeploymentSource).spec.template.spec.containers[0].env;
    for (const [signal, value] of Object.entries(defaults)) {
      const name = `OTEL_${signal.toUpperCase()}_EXPORTER`;
      expect(environment[name]).toBe(`\${${name}:-${value}}`);
      expect(env.find((entry) => entry.name === name)?.value).toBe(value);
    }

    // A default endpoint is not neutral for gRPC, so the operator adds it.
    expect(environment).not.toHaveProperty("OTEL_EXPORTER_OTLP_ENDPOINT");
    expect(env.find((entry) => entry.name === "OTEL_EXPORTER_OTLP_ENDPOINT")).toBeUndefined();
  });

  it("keeps SSO opt-in: every k8s SSO key is optional, the client secret comes from the Secret, and the base compose stack has none", () => {
    const env = parse(appDeploymentSource).spec.template.spec.containers[0].env;
    const sso = env.filter((entry) => entry.name.startsWith("SSO_"));
    expect(sso.map((entry) => entry.name).sort()).toEqual([
      "SSO_ALLOWED_EMAIL_DOMAINS",
      "SSO_CLIENT_ID",
      "SSO_CLIENT_SECRET",
      "SSO_ENABLED",
      "SSO_ISSUER",
      "SSO_LABEL",
      "SSO_REDIRECT_URI",
    ]);
    for (const entry of sso) {
      const ref = entry.valueFrom.secretKeyRef ?? entry.valueFrom.configMapKeyRef;
      expect(ref.optional, entry.name).toBe(true);
      expect(ref.key).toBe(entry.name);
      expect(ref.name).toBe(entry.name === "SSO_CLIENT_SECRET" ? "toadie-secrets" : "toadie-config");
    }
    expect(sso.find((entry) => entry.name === "SSO_CLIENT_SECRET").valueFrom).toHaveProperty("secretKeyRef");
    // SSO exists in compose only through the e2e overlay.
    expect(composeSource).not.toContain("SSO_");
  });

  it("gives the compose app service a container healthcheck", () => {
    const compose = parse(composeSource);
    expect(compose.services.app.healthcheck).toBeTruthy();
    expect(compose.services.app.healthcheck.test).toBeTruthy();
  });

  it("keeps Dependabot covering every dependency-bearing directory", () => {
    const dependabot = parse(dependabotSource);
    const pairs = dependabot.updates.map((update) => [update.directory, update["package-ecosystem"]]);
    expect(pairs).toEqual(
      expect.arrayContaining([
        ["/web", "npm"],
        ["/e2e", "npm"],
        ["/", "gradle"],
        ["/", "github-actions"],
        ["/", "docker"],
      ]),
    );
    expect(pairs).toHaveLength(5);
  });

  it("the app Deployment recreates rather than rolls (single-instance posture)", () => {
    const app = parse(appDeploymentSource);
    expect(app.spec.replicas).toBe(1);
    expect(app.spec.strategy.type).toBe("Recreate");
    const postgres = parse(postgresDeploymentSource);
    expect(postgres.spec.replicas).toBe(1);
    expect(postgres.spec.strategy.type).toBe("Recreate");
  });

  it("keeps the k8s startupProbe budget above Ktor's startup timeout, which must cover Flyway's cold-start wait", () => {
    // The relationship is explained in .claude/docs/persistence.md "Cold start: Flyway waits for PostgreSQL".
    const probe = parse(appDeploymentSource).spec.template.spec.containers[0].startupProbe;
    const probeBudgetMillis = probe.periodSeconds * probe.failureThreshold * 1000;
    const startupTimeoutMillis = Number(parse(applicationYamlSource).ktor.application.startupTimeoutMillis);
    expect(Number.isInteger(startupTimeoutMillis)).toBe(true);
    // Ktor's 10 s default fails a boot whose Flyway is still waiting for PostgreSQL.
    expect(startupTimeoutMillis).toBeGreaterThan(10_000);
    expect(probeBudgetMillis).toBeGreaterThan(startupTimeoutMillis);
  });

  it("the app pod and container declare full security context (non-root, read-only root, no capabilities)", () => {
    const app = parse(appDeploymentSource);
    const podSecurityContext = app.spec.template.spec.securityContext;
    expect(podSecurityContext.runAsNonRoot).toBe(true);
    expect(podSecurityContext.runAsUser).toBe(10001);
    expect(podSecurityContext.runAsGroup).toBe(10001);
    expect(podSecurityContext.seccompProfile.type).toBe("RuntimeDefault");

    const appContainer = app.spec.template.spec.containers[0];
    const containerSecurityContext = appContainer.securityContext;
    expect(containerSecurityContext.allowPrivilegeEscalation).toBe(false);
    expect(containerSecurityContext.readOnlyRootFilesystem).toBe(true);
    expect(containerSecurityContext.capabilities.drop).toEqual(["ALL"]);

    const tmpVolume = app.spec.template.spec.volumes.find((v) => v.name === "tmp");
    expect(tmpVolume).toBeDefined();
    expect(tmpVolume.emptyDir).toBeDefined();

    const tmpVolumeMount = appContainer.volumeMounts.find((v) => v.name === "tmp");
    expect(tmpVolumeMount).toBeDefined();
    expect(tmpVolumeMount.mountPath).toBe("/tmp");
  });
});

describe("Dependabot Gradle fix-up", () => {
  const workflow = parse(fixupWorkflowSource);
  const job = workflow.jobs.regenerate;
  const stepOf = (text) => job.steps.find((step) => (step.run ?? "").includes(text));
  const gradleHome = (step) => step.run.match(/GRADLE_USER_HOME="\$RUNNER_TEMP\/([^"]+)"/)?.[1];
  const buildTasks = parse(ciSource)
    .jobs.backend.steps.map((step) => step.run ?? "")
    .join("\n")
    .match(/\.\/gradlew (build :server:koverXmlReport :server:installDist)/)?.[1];

  it("runs unprivileged: two triggers, read-only token, no secrets, no write commands, no caches", () => {
    expect(Object.keys(workflow.on)).toEqual(["pull_request", "workflow_dispatch"]);
    expect(workflow.permissions).toEqual({ contents: "read" });
    expect(Object.values(workflow.jobs).filter((candidate) => candidate.permissions !== undefined)).toEqual([]);
    // Job-level and keyed on the PR number: a run the job guard skips (a fork's lookalike branch, a
    // maintainer's reopen) must not cancel the real one, and a branch name is attacker-chosen.
    expect(workflow.concurrency).toBeUndefined();
    expect(job.concurrency["cancel-in-progress"]).toBe(true);
    expect(job.concurrency.group).toContain("github.event.pull_request.number");
    expect(job.concurrency.group).not.toContain("head_ref");
    for (const step of job.steps) expect(step.run ?? "", "an expression inside run: would be script injection").not.toContain("${{");
    for (const forbidden of [
      /\bsecrets\b/,
      /contents:\s*write/,
      /actions:\s*write/,
      /pull-requests:\s*write/,
      /github\.token|GITHUB_TOKEN/,
      /git push|gh pr |gh workflow run|gh api/,
      /actions\/cache|gradle\/actions|download-artifact/,
      /pull_request_target|workflow_run/,
    ]) {
      expect(fixupWorkflowSource).not.toMatch(forbidden);
    }
    expect(job["timeout-minutes"]).toBeLessThanOrEqual(60);
  });

  it("only runs for Dependabot's own Gradle branches (or a manual dispatch)", () => {
    expect(job.if).toContain("github.event_name == 'workflow_dispatch'");
    expect(job.if).toContain("github.actor == 'dependabot[bot]'");
    expect(job.if).toContain("github.event.pull_request.head.repo.full_name == github.repository");
    expect(job.if).toContain("startsWith(github.head_ref, 'dependabot/gradle/')");
  });

  it("checks out the PR head, not the merge ref, with full history and no stored credentials", () => {
    const checkout = job.steps.find((step) => step.uses?.startsWith("actions/checkout@"));
    expect(checkout.with.ref).toContain("github.event.pull_request.head.sha");
    expect(checkout.with.ref).toContain("github.sha"); // a dispatch is pinned to its SHA too, never a movable ref
    expect(checkout.with.ref).not.toContain("github.ref");
    expect(checkout.with["persist-credentials"]).toBe(false);
    expect(checkout.with["fetch-depth"]).toBe(0);
    const javaSetup = job.steps.find((step) => step.uses?.startsWith("actions/setup-java@"));
    expect(javaSetup.with.distribution).toBe("temurin");
    expect(javaSetup.with["java-version"]).toBe(toolchainSource.match(/^java = "temurin-([^"]+)"$/m)?.[1]);
    expect(javaSetup.with).not.toHaveProperty("cache");
  });

  it("generates from one empty Gradle home and proves from another, strictly and without write flags", () => {
    expect(buildTasks).toBeTruthy();
    const generate = stepOf("--write-locks");
    const prove = stepOf("--dependency-verification strict");
    expect(generate.run).toContain("--write-locks --write-verification-metadata sha256");
    expect(generate.run).toContain(`${buildTasks} --no-daemon`);
    expect(prove.run).not.toContain("--write-");
    expect(prove.run).toContain(`${buildTasks} --no-daemon`);
    expect(gradleHome(generate)).toBeTruthy();
    expect(gradleHome(prove)).toBeTruthy();
    expect(gradleHome(generate)).not.toBe(gradleHome(prove));
    // Generate, snapshot, prove, tripwire - in that order.
    const order = ["--write-locks", "tar -cf", "--dependency-verification strict", "cmp "].map((text) => job.steps.indexOf(stepOf(text)));
    expect(order).toEqual([...order].sort((a, b) => a - b));
    expect(new Set(order).size).toBe(4);
    expect(job.env.GRAPHQL_BASELINE_REVISION).toContain("github.event.pull_request.base.sha");
  });

  it("snapshots exactly the lockfiles and the verification metadata", () => {
    const files = tokens(job.env.FIXUP_FILES);
    files.delete("");
    expect([...files].sort()).toEqual([...lockfiles(repoRoot), "gradle/verification-metadata.xml"].sort());
  });

  it("uploads only the snapshot, for a week, named by the head SHA", () => {
    const upload = job.steps.find((step) => step.uses?.startsWith("actions/upload-artifact@"));
    expect(upload.with.name).toContain("env.HEAD_SHA");
    expect(upload.with["retention-days"]).toBeLessThanOrEqual(7);
    expect(upload.with["if-no-files-found"]).toBe("error");
    expect(upload.with.path).toMatch(/runner\.temp[^/]*\/fixup\/?$/);
    expect(job.env.HEAD_SHA).toContain("github.event.pull_request.head.sha");
  });

  it("stays out of the Quality gate and keeps Dependabot from rebasing over the fix-up", () => {
    expect(parse(ciSource).jobs["quality-gate"].needs).toEqual(["backend", "frontend", "gradle-vulnerability-scan", "image", "e2e"]);
    const gradle = parse(dependabotSource).updates.find((update) => update["package-ecosystem"] === "gradle");
    expect(gradle["rebase-strategy"]).toBe("disabled");
  });

  it("ships a maintainer CLI that parses and documents itself without touching the network", () => {
    execFileSync("bash", ["-n", fixupCliPath]);
    const help = execFileSync("bash", [fixupCliPath, "--help"], { encoding: "utf8" });
    expect(help).toContain("apply <pr>");
    expect(help).toContain("--accept-review");
    const cli = readFileSync(fixupCliPath, "utf8");
    expect(cli).toMatch(/set -euo pipefail/);
    const pushes = cli.split("\n").filter((line) => /\bpush\b/.test(line) && !/^\s*#/.test(line));
    expect(pushes.length).toBeGreaterThan(0);
    // A rejected push means a stale artifact, never a force: no -f/--force, no "+refspec" (also quoted), no deleting or mirroring.
    for (const line of pushes) expect(line).not.toMatch(/--force|--mirror|--delete|\s-f\b|[\s"']\+/);
    expect(cli).toContain("--print-allow-list"); // the file list is the validator's, not a third hard-coded copy
    expect(cli).toContain("core.hooksPath=/dev/null"); // no repository hook runs on the PR head's files
  });
});
