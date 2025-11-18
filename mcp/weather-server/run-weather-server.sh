#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DIST_DIR="${PROJECT_ROOT}/mcp/weatherServer/build/install/weatherServer"
LIB_DIR="${DIST_DIR}/lib"
MAIN_CLASS="com.pozyalov.ai_advent_challenge.mcp.weather.MainKt"

if [[ ! -d "${LIB_DIR}" ]]; then
  cat <<'MSG'
Weather MCP server binary not found.
Run ./gradlew :mcp:weatherServer:installDist first to build the distribution.
MSG
  exit 1
fi

CLASSPATH=""
for jar in "${LIB_DIR}"/*.jar; do
  if [[ -z "${CLASSPATH}" ]]; then
    CLASSPATH="${jar}"
  else
    CLASSPATH="${CLASSPATH}:${jar}"
  fi
done

if [[ -z "${CLASSPATH}" ]]; then
  echo "No JAR files were found in ${LIB_DIR}. Re-run ./gradlew :mcp:weatherServer:installDist."
  exit 1
fi

JAVA_CMD="${JAVA_HOME:-}/bin/java"
if [[ ! -x "${JAVA_CMD}" ]]; then
  JAVA_CMD="java"
fi

exec "${JAVA_CMD}" -classpath "${CLASSPATH}" "${MAIN_CLASS}"
