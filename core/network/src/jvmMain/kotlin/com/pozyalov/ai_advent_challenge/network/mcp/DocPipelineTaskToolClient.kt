package com.pozyalov.ai_advent_challenge.network.mcp

class DocPipelineTaskToolClient(
    scriptPath: String? = System.getProperty("ai.advent.docpipeline.mcp.script")
        ?: "mcp/doc-pipeline-server/run-doc-pipeline-server.sh"
) : TaskToolClient by ScriptedTaskToolClient(
    displayName = "Doc Pipeline",
    scriptPath = scriptPath,
    missingServerMessage = "Doc Pipeline MCP сервер не найден: укажите путь через ai.advent.docpipeline.mcp.script или соберите mcp/docPipelineServer."
)
