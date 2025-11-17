Helper script for launching the official [github/github-mcp-server](https://github.com/github/github-mcp-server) Docker image.

1. Copy `.env.example` to `.env` (or create `.env` yourself) and add at least `GITHUB_PERSONAL_ACCESS_TOKEN=...`.
2. Run `./mcp/github-server/run-github-server.sh`.
3. Leave `MCP_SERVER_SCRIPT` unset and the desktop client will run this helper automatically, printing the list of tools via `McpToolInspector`.

Add optional variables to `.env`:
- `GITHUB_TOOLSETS` – comma separated list of tool packs to load.
- `GITHUB_HOST` – set to your GitHub Enterprise hostname if needed.
