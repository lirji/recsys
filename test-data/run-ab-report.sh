#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
mkdir -p "$PROJECT_ROOT/.dev/test-data/ab-board-v1"
cd "$PROJECT_ROOT"
mvn -B -DskipTests -pl recsys-offline -am package > .dev/test-data/ab-board-v1/maven-package.log 2>&1
exec python3 "$SCRIPT_DIR/ab-board-demo.py" report "$@"
