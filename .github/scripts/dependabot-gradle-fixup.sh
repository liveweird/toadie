#!/usr/bin/env bash
# Maintainer CLI for the Dependabot Gradle fix-up. The `dependabot-gradle-fixup` workflow COMPUTES
# the missing lockfiles and verification metadata without privilege; this script APPLIES them with
# YOUR credentials after .github/scripts/gradle-fixup-validate.mjs approved them.
#
#   dependabot-gradle-fixup.sh run <pr>
#       dispatch the workflow on the PR's branch and watch it (the pull_request run normally exists
#       already; use this after `gh pr update-branch`, or when that run was cancelled)
#   dependabot-gradle-fixup.sh apply <pr> [--dry-run] [--accept-review] [--trailer "K: v"]...
#       download the artifact of the newest successful run for the PR's exact head SHA, validate it,
#       commit it on top of the PR head and push (fast-forward only, never forced)
#   dependabot-gradle-fixup.sh local <pr> [--dry-run] [--accept-review] [--trailer "K: v"]...
#       the fallback without the workflow: run the two documented commands of
#       .claude/docs/dependency-reproducibility.md here (two fresh Gradle homes), then the same
#       validate / commit / push path
#
#   --dry-run        stop before the commit and print the staged diff
#   --accept-review  apply a REVIEW verdict (lockfile changes outside the requested bump) once you
#                    read the validator's list; a REFUSED verdict is never applied
#   --trailer        add a trailer line (for example the Co-Authored-By one) to the commit message
#
# Needs gh (authenticated), git and node. Run it from a checkout of liveweird/toadie; the working
# tree is never touched (all work happens in a detached git worktree under a temp directory).
# Rule and threat model: .claude/docs/dependency-reproducibility.md.
set -euo pipefail

REPO='liveweird/toadie'
WORKFLOW='dependabot-gradle-fixup.yml'
SPEC_FILES=(gradle/libs.versions.toml settings.gradle.kts build.gradle.kts core/build.gradle.kts server/build.gradle.kts)
GRADLE_TASKS=(build :server:koverXmlReport :server:installDist)
VALIDATOR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gradle-fixup-validate.mjs"

usage() {
  sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
}

die() {
  echo "error: $*" >&2
  exit 1
}

case "${1:-}" in
  -h | --help | help)
    usage
    exit 0
    ;;
esac

for tool in gh git node; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

# The data files of a fix-up: the validator's allow-list (the one copy in code) minus the manifest.
FIXUP_FILES=()
while IFS= read -r allowed; do
  [ "$allowed" = manifest.json ] || FIXUP_FILES+=("$allowed")
done < <(node "$VALIDATOR" --print-allow-list)
[ "${#FIXUP_FILES[@]}" -gt 0 ] || die "could not read the validator's allow-list"

command_name="${1:-}"
pr="${2:-}"
[ -n "$command_name" ] && [ -n "$pr" ] || { usage >&2; exit 1; }
shift 2
[[ "$pr" =~ ^[0-9]+$ ]] || die "the PR must be a number, got '$pr'"

dry_run=0
accept_review=0
trailers=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1 ;;
    --accept-review) accept_review=1 ;;
    --trailer)
      [ $# -ge 2 ] || die "--trailer needs a value"
      [[ "$2" =~ ^[A-Za-z][A-Za-z0-9-]*:\ [^[:cntrl:]]+$ ]] || die "--trailer must be a single line like 'Key: value' without control characters"
      trailers+=("$2")
      shift
      ;;
    *) die "unknown argument '$1'" ;;
  esac
  shift
done

root="$(git rev-parse --show-toplevel)" || die "run this from a checkout of $REPO"
origin_slug="$(git -C "$root" config --get remote.origin.url | sed -E 's#^(https://github\.com/|ssh://git@github\.com/|git@github\.com:)##; s#/$##; s#\.git$##')"
[ "$origin_slug" = "$REPO" ] || die "origin is '$origin_slug', not $REPO"

jget() { # jget <json> <dotted.path>
  PR_JSON="$1" JSON_PATH="$2" node -e 'let v = JSON.parse(process.env.PR_JSON); for (const key of process.env.JSON_PATH.split(".")) v = v == null ? v : v[key]; process.stdout.write(v == null ? "" : String(v))'
}

pr_json="$(gh pr view "$pr" --repo "$REPO" --json headRefName,headRefOid,headRepository,headRepositoryOwner,title,author)"
branch="$(jget "$pr_json" headRefName)"
head_sha="$(jget "$pr_json" headRefOid)"
title="$(jget "$pr_json" title)"
author="$(jget "$pr_json" author.login)"
head_repo="$(jget "$pr_json" headRepositoryOwner.login)/$(jget "$pr_json" headRepository.name)"

[ "$head_repo" = "$REPO" ] || die "PR #$pr comes from '$head_repo', not $REPO: a fork is never fixed up"
[[ "$head_sha" =~ ^[0-9a-f]{40}$ ]] || die "could not read the PR head SHA"
[ "$author" = "app/dependabot" ] || echo "warning: PR #$pr is authored by '$author', not Dependabot" >&2
case "$branch" in
  dependabot/gradle/*) ;;
  *) echo "warning: branch '$branch' is not a dependabot/gradle/ branch" >&2 ;;
esac

work="$(mktemp -d)"
worktree="$work/worktree"
cleanup() {
  git -C "$root" worktree remove --force "$worktree" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

fetch_head() {
  git -C "$root" fetch --quiet origin master
  git -C "$root" fetch --quiet origin "refs/pull/$pr/head"
  git -C "$root" cat-file -e "${head_sha}^{commit}" 2>/dev/null || die "the PR head $head_sha is not fetchable (the branch moved? re-run)"
}

add_worktree() {
  git -C "$root" worktree add --quiet --detach "$worktree" "$head_sha"
}

# Validate $work/artifact against the head, then stage it in the worktree and commit/push.
validate_and_apply() { # validate_and_apply <run description>
  local description="$1" head_dir="$work/head" rc=0
  mkdir -p "$head_dir"
  git -C "$root" archive "$head_sha" -- "${FIXUP_FILES[@]}" gradle/libs.versions.toml settings.gradle.kts | tar -x -C "$head_dir"
  git -C "$root" diff "origin/master...$head_sha" -- "${SPEC_FILES[@]}" >"$work/spec.diff"

  node "$VALIDATOR" --head "$head_dir" --artifact "$work/artifact" --spec-diff "$work/spec.diff" | tee "$work/validator.out" || rc=$?
  case "$rc" in
    0) ;;
    2) [ "$accept_review" -eq 1 ] || die "the validator asks for REVIEW: read the lockfile changes above, then re-run with --accept-review" ;;
    *) die "the validator refused the artifact (exit $rc); nothing was applied" ;;
  esac

  [ -d "$worktree" ] || add_worktree
  local file
  for file in "${FIXUP_FILES[@]}"; do
    cp "$work/artifact/$file" "$worktree/$file"
  done
  git -C "$worktree" add -- "${FIXUP_FILES[@]}"
  if git -C "$worktree" diff --cached --quiet; then
    echo "already complete: the PR head already contains these lockfiles and checksums"
    return 0
  fi
  if [ "$dry_run" -eq 1 ]; then
    git -C "$worktree" --no-pager diff --cached --stat
    git -C "$worktree" --no-pager diff --cached
    echo "dry run: nothing committed or pushed"
    return 0
  fi

  {
    echo "build(deps): locks and checksums for ${title#chore(deps): }"
    echo
    echo "Applied from $description"
    echo "PR head: $head_sha"
    echo "Validator: $(grep -E '^(lockfiles:|metadata additions:|VERDICT:)' "$work/validator.out" | tr '\n' ';' | sed 's/; informational, never gating//; s/;$//; s/;/; /g')"
    if [ "${#trailers[@]}" -gt 0 ]; then
      echo
      printf '%s\n' "${trailers[@]}"
    fi
  } >"$work/message.txt"
  # The worktree holds the PR head's files: never run a hook configured for this repository on them.
  git -C "$worktree" -c core.hooksPath=/dev/null commit --quiet -F "$work/message.txt"
  # Plain fast-forward push: a rejection means the head moved and this artifact is stale.
  git -C "$worktree" -c core.hooksPath=/dev/null push origin "HEAD:refs/heads/$branch"
  echo "pushed. Watch the checks with:"
  echo "  gh pr checks $pr --repo $REPO --watch"
}

case "$command_name" in
  run)
    previous="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$branch" --event workflow_dispatch --limit 1 --json databaseId --jq '.[0].databaseId // 0')"
    gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$branch"
    run_id="$previous"
    for _ in $(seq 1 30); do
      sleep 2
      run_id="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$branch" --event workflow_dispatch --limit 1 --json databaseId --jq '.[0].databaseId // 0')"
      [ "$run_id" != "$previous" ] && break
    done
    [ "$run_id" != "$previous" ] || die "the dispatched run did not appear; look at the Actions tab"
    gh run watch "$run_id" --repo "$REPO" --exit-status
    ;;

  apply)
    fetch_head
    runs="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --commit "$head_sha" --status success --limit 10 --json databaseId,url,event,headBranch)"
    artifact_dir="$work/artifact"
    run_url=""
    # Newest first: take the first successful run of THIS workflow for the PR's branch and head that
    # actually holds the artifact named after the head SHA.
    while IFS=$'\t' read -r candidate candidate_url; do
      rm -rf "$artifact_dir"
      if gh run download "$candidate" --repo "$REPO" -n "gradle-fixup-$head_sha" -D "$artifact_dir" >/dev/null 2>&1; then
        run_url="$candidate_url"
        break
      fi
    done < <(PR_JSON="$runs" BRANCH="$branch" node -e 'for (const run of JSON.parse(process.env.PR_JSON)) if ((run.event === "pull_request" || run.event === "workflow_dispatch") && run.headBranch === process.env.BRANCH) console.log(run.databaseId + "\t" + run.url)')
    [ -n "$run_url" ] || die "no successful $WORKFLOW run for branch $branch at head $head_sha holds the artifact. Wait for the pull_request run, or start one with '$0 run $pr', or use '$0 local $pr'"
    manifest_problem="$(MANIFEST="$artifact_dir/manifest.json" HEAD_SHA="$head_sha" node -e '
      let manifest;
      try { manifest = JSON.parse(require("fs").readFileSync(process.env.MANIFEST, "utf8")); } catch { console.log("manifest.json is missing or not JSON"); process.exit(0); }
      if (manifest === null || typeof manifest !== "object" || Array.isArray(manifest)) console.log("manifest.json is not an object");
      else if (typeof manifest.headSha !== "string" || !/^[0-9a-f]{40}$/.test(manifest.headSha)) console.log("manifest.json has no valid headSha");
      else if (manifest.headSha !== process.env.HEAD_SHA) console.log("the artifact was built for " + manifest.headSha + ", not the PR head " + process.env.HEAD_SHA);
    ')"
    [ -z "$manifest_problem" ] || die "$manifest_problem"
    validate_and_apply "workflow run $run_url"
    ;;

  local)
    echo "warning: local mode RUNS the PR's build (the bumped dependency's code) on this machine with your credentials, SSH keys and Docker socket; the validator below is no boundary against it" >&2
    fetch_head
    add_worktree
    if [ -z "${DOCKER_HOST:-}" ] && command -v docker >/dev/null 2>&1; then
      DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
      [ -n "$DOCKER_HOST" ] && export DOCKER_HOST
    fi
    # The two commands of .claude/docs/dependency-reproducibility.md, each from its own empty home.
    (cd "$worktree" && GRADLE_USER_HOME="$work/gradle-home-generate" ./gradlew --write-locks --write-verification-metadata sha256 "${GRADLE_TASKS[@]}" --no-daemon)
    mkdir -p "$work/artifact"
    (cd "$worktree" && tar -cf - "${FIXUP_FILES[@]}") | tar -xf - -C "$work/artifact"
    (cd "$worktree" && GRADLE_USER_HOME="$work/gradle-home-prove" ./gradlew --dependency-verification strict "${GRADLE_TASKS[@]}" --no-daemon)
    for file in "${FIXUP_FILES[@]}"; do
      cmp "$worktree/$file" "$work/artifact/$file" || die "the strict proof build changed $file"
    done
    printf '{"headSha":"%s","runId":"local"}\n' "$head_sha" >"$work/artifact/manifest.json"
    validate_and_apply "a local regeneration (two empty Gradle homes, strict proof)"
    ;;

  *)
    usage >&2
    die "unknown command '$command_name'"
    ;;
esac
