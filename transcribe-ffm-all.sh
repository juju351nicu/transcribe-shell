#!/usr/bin/env bash
# transcribe-ffm-all launcher (Mac / Linux).
#   ./transcribe-ffm-all.sh -d "/path/to/folder" [options]
#
# 注意: jar に同梱されているネイティブライブラリは Windows 用だけなので、Mac / Linux では
# whisper-ffm をその OS でビルドして publishToMavenLocal し直すまでこのコマンドは失敗する。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/transcribe-shell-0.0.1-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
  echo "jar が見つかりません: $JAR" >&2
  echo "  先に次を実行してください: (cd \"$SCRIPT_DIR\" && ./mvnw -DskipTests package)" >&2
  exit 1
fi

exec java --enable-native-access=ALL-UNNAMED "-Dspring.shell.interactive.enabled=false" \
  -jar "$JAR" transcribe-ffm-all "$@"
