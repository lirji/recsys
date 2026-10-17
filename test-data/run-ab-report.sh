#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STATE_NAME=ab-board-v1
for argument in "$@"; do
  if [[ "$argument" == more-users || "$argument" == --scenario=more-users ]]; then
    STATE_NAME=ab-board-v2
  fi
done
mkdir -p "$PROJECT_ROOT/.dev/test-data/$STATE_NAME"
cd "$PROJECT_ROOT"
mvn -B -DskipTests -pl recsys-offline -am package > ".dev/test-data/$STATE_NAME/maven-package.log" 2>&1
exec python3 "$SCRIPT_DIR/ab-board-demo.py" report "$@"
