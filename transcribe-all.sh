#!/usr/bin/env bash
# transcribe-all launcher (Mac / Linux).
#   ./transcribe-all.sh [-d "/path/to/folder"] [options]
#
# Without -d it scans transcribe.default-dir (on Mac/Linux: ~/Music).
# The literal "transcribe-all" subcommand is required; without it Spring Shell fails with
# CommandNotFoundException. The jar is resolved from this script's own directory.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/transcribe-shell-0.0.1-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
  echo "jar が見つかりません: $JAR" >&2
  echo "  先に次を実行してください: (cd \"$SCRIPT_DIR\" && ./mvnw -DskipTests package)" >&2
  exit 1
fi

exec java "-Dspring.shell.interactive.enabled=false" -jar "$JAR" transcribe-all "$@"
