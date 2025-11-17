#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "${ENV_FILE}"
  set +a
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to run the GitHub MCP server. Please install Docker Desktop or Docker Engine."
  exit 1
fi

if [[ -z "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]]; then
  cat <<'EOF'
Missing GitHub token.

Please export the GITHUB_PERSONAL_ACCESS_TOKEN environment variable (or add it to mcp/github-server/.env)
with a Personal Access Token that has the scopes you want to grant to the MCP server.
Create a token at: https://github.com/settings/personal-access-tokens/new
EOF
  exit 1
fi

IMAGE="ghcr.io/github/github-mcp-server:latest"
DOCKER_ENV=(-e "GITHUB_PERSONAL_ACCESS_TOKEN=${GITHUB_PERSONAL_ACCESS_TOKEN}")

if [[ -n "${GITHUB_TOOLSETS:-}" ]]; then
  DOCKER_ENV+=(-e "GITHUB_TOOLSETS=${GITHUB_TOOLSETS}")
fi

if [[ -n "${GITHUB_HOST:-}" ]]; then
  DOCKER_ENV+=(-e "GITHUB_HOST=${GITHUB_HOST}")
fi

echo "Starting GitHub MCP server via ${IMAGE}"
exec docker run --rm -i \
  "${DOCKER_ENV[@]}" \
  "${IMAGE}"
