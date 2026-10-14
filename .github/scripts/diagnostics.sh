#!/usr/bin/env bash
# Attaches CI logs to a DRAFT release named "ci-diagnostics" (drafts are visible only to the repository
# owner/collaborators). Used to inspect build failures when job logs cannot be viewed directly.
# Usage: diagnostics.sh <job-name> <files-or-dirs...>
set -u
JOB="$1"; shift
[ -n "${GH_TOKEN:-}" ] || exit 0
[ "${GITHUB_EVENT_NAME:-}" = "pull_request" ] && exit 0
TAG="ci-diagnostics"
NAME="${JOB}-run${GITHUB_RUN_NUMBER:-0}-attempt${GITHUB_RUN_ATTEMPT:-1}.tgz"
existing=()
for f in "$@"; do [ -e "$f" ] && existing+=("$f"); done
[ ${#existing[@]} -gt 0 ] || exit 0
tar -czf "/tmp/$NAME" "${existing[@]}" 2>/dev/null || exit 0
gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$GITHUB_REPOSITORY" --draft --title "CI diagnostics (private draft)" --notes "Build logs for debugging. Safe to delete." >/dev/null 2>&1 || true
gh release upload "$TAG" "/tmp/$NAME" --repo "$GITHUB_REPOSITORY" --clobber >/dev/null 2>&1 || true
echo "diagnostics uploaded as $NAME"
