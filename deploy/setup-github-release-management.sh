#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.1.0 — GitHub Repository, Release Branches & Protection Bootstrap
# Configures:
#   1. Git remote `origin` pointing to the operator's GitHub repository
#   2. GitFlow release branches: `main` (production), `develop` (integration),
#      and `release/v1.1` (staging qualification)
#   3. Pushes all branches and release tags (`v1.1.0-rc.1`, `v1.1.0`) to GitHub
#   4. If GitHub CLI (`gh`) is authenticated, applies branch protection rules on
#      `main` and `develop` and creates `staging` and `production` environments
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

REMOTE_URL="${1:-}"
APPLY_GH_PROTECTION="${2:-true}"

if [[ -z "${REMOTE_URL}" ]]; then
    echo "Usage: ./deploy/setup-github-release-management.sh <https://github.com/OWNER/REPO.git> [true|false]" >&2
    exit 2
fi

cd "${REPO_ROOT}"

echo "==> [1/4] Configuring Git remote 'origin' -> ${REMOTE_URL}..."
if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "${REMOTE_URL}"
else
    git remote add origin "${REMOTE_URL}"
fi
git remote -v

echo "==> [2/4] Synchronizing GitFlow branches (main, develop, release/v1.1)..."
CURRENT_HEAD="$(git rev-parse HEAD)"
git branch -f main "${CURRENT_HEAD}"
git branch -f develop "${CURRENT_HEAD}"
git branch -f release/v1.1 "${CURRENT_HEAD}"

echo "==> [3/4] Pushing branches (main, develop, release/v1.1) and tags to origin..."
git push -u origin main
git push -u origin develop
git push -u origin release/v1.1
git push origin --tags

if [[ "${APPLY_GH_PROTECTION}" == "true" ]] && command -v gh >/dev/null 2>&1; then
    if gh auth status >/dev/null 2>&1; then
        OWNER_REPO="$(printf '%s' "${REMOTE_URL}" | sed -E 's#^https?://github\.com/##; s#^git@github\.com:##; s#\.git$##')"
        echo "==> [4/4] Configuring GitHub environments and branch protection on ${OWNER_REPO}..."

        gh api --method PUT "repos/${OWNER_REPO}/environments/staging" >/dev/null || true
        gh api --method PUT "repos/${OWNER_REPO}/environments/production" >/dev/null || true

        for BR in main develop; do
            gh api --method PUT "repos/${OWNER_REPO}/branches/${BR}/protection" \
                -H "Accept: application/vnd.github+json" \
                --input - <<'EOF' >/dev/null
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "Stage 1: Manifest, Expand-Contract DDL & Zero-Licensing",
      "Stage 2A: Standalone .1800 Reader (MIG-003 Zero-Spring)",
      "Stage 2B: Standalone Java 25 Swing Desktop Client (SWG-001/SWG-002)",
      "Stage 3A: Angular 22 Signals, ESLint & Vitest",
      "Stage 3B-5: Backend Unit, ArchUnit & Real Dual-PG18 Integration"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 1
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
EOF
            echo "    [OK] Protected branch '${BR}' on ${OWNER_REPO}."
        done
    else
        echo "==> [4/4] 'gh' CLI is not logged in; apply branch protection rules in the GitHub UI (Settings -> Branches) or run 'gh auth login' and re-run."
    fi
else
    echo "==> [4/4] Branches pushed. Configure branch protection in GitHub Settings -> Branches."
fi

echo "[PASS] GitHub release management setup complete for ${REMOTE_URL}."
