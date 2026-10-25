#!/usr/bin/env bash
# Container-image check for the CI "image" job (the Quality gate's "Container image").
#
#   1. builds the Dockerfile for linux/amd64 AND linux/arm64 (no output, no push: the point is to
#      prove both architectures build; the build stages run once on the build platform, only the
#      runtime stage's `useradd` is emulated under QEMU);
#   2. exports the amd64 image from the build cache and scans its OS layer with the same pinned
#      Trivy the Gradle advisory scan uses, BLOCKING on a fixed HIGH/CRITICAL OS vulnerability.
#
# Nothing is published anywhere: registry publishing is parked in BACKLOG.md. Runs unchanged on a
# developer machine (set RUNNER_TEMP to a scratch directory; needs docker with buildx and
# `--privileged` support). Every image below is pinned by tag AND digest; Dependabot does not read
# images inside scripts, so bump them by hand (.claude/docs/dependency-reproducibility.md).
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP must name a writable scratch directory}"

BINFMT_IMAGE='tonistiigi/binfmt:qemu-v10.2.3-68@sha256:400a4873b838d1b89194d982c45e5fb3cda4593fbfd7e08a02e76b03b21166f0'
BUILDKIT_IMAGE='moby/buildkit:v0.34.1@sha256:99368f3e46177c6405b9740e4461d91cee004128daeecca78d4ceac50ec1da5c'
TRIVY_IMAGE='ghcr.io/aquasecurity/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'

BUILDER='toadie-image-check'
TAR="$RUNNER_TEMP/image-amd64.tar"
REPORT="$RUNNER_TEMP/image-vulnerabilities.json"

# Same cache-dir shape as the Gradle advisory scan; on a fresh runner it only receives the vulnerability DB.
mkdir -p "$RUNNER_TEMP/trivy-cache"
cleanup() {
  docker buildx rm "$BUILDER" >/dev/null 2>&1 || true
  rm -f "$TAR"
}
trap cleanup EXIT

echo "::group::QEMU emulators (arm64)"
docker run --privileged --rm "$BINFMT_IMAGE" --install arm64
echo "::endgroup::"

echo "::group::buildx builder"
docker buildx rm "$BUILDER" >/dev/null 2>&1 || true
docker buildx create --name "$BUILDER" --driver docker-container \
  --driver-opt "image=$BUILDKIT_IMAGE" --bootstrap
echo "::endgroup::"

echo "::group::Build linux/amd64 and linux/arm64"
docker buildx build --builder "$BUILDER" --platform linux/amd64,linux/arm64 \
  --provenance=false --sbom=false .
echo "::endgroup::"

echo "::group::Export the amd64 image"
docker buildx build --builder "$BUILDER" --platform linux/amd64 \
  --provenance=false --sbom=false --output "type=docker,dest=$TAR" .
echo "::endgroup::"

# A fixed HIGH/CRITICAL vulnerability in the base image's OS packages fails the job; the fix is a
# cleaner base image, never an ignore file (.claude/docs/dependency-reproducibility.md). The JSON
# report is the uploaded artifact. A failed Trivy DB download leaves no report: re-run the job.
scan_status=0
docker run --rm \
  -v "$RUNNER_TEMP:/out" \
  -v "$RUNNER_TEMP/trivy-cache:/cache" \
  "$TRIVY_IMAGE" \
  image --input /out/image-amd64.tar --scanners vuln --pkg-types os --ignore-unfixed \
  --severity HIGH,CRITICAL --exit-code 1 --format json --output /out/image-vulnerabilities.json \
  --cache-dir /cache --no-progress || scan_status=$?

if [ -f "$REPORT" ]; then
  docker run --rm -v "$RUNNER_TEMP:/out" "$TRIVY_IMAGE" \
    convert --format table /out/image-vulnerabilities.json
  # A scan that silently found no OS to inspect would pass for the wrong reason.
  python3 - "$REPORT" <<'PY'
import json
import sys

with open(sys.argv[1]) as report:
    results = json.load(report).get("Results") or []
os_results = [result for result in results if result.get("Class") == "os-pkgs"]
if not os_results:
    raise SystemExit("The scan reported no OS-package target: the image's OS was not inspected")
print("Scanned OS targets:", ", ".join(result["Target"] for result in os_results))
PY
elif [ "$scan_status" -eq 0 ]; then
  # Exit 0 with no report is not a clean scan: nothing proves the OS layer was inspected.
  echo "Trivy exited 0 but wrote no report: the image was not scanned" >&2
  exit 1
fi

exit "$scan_status"
