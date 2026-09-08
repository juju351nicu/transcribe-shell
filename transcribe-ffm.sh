#!/usr/bin/env bash
# transcribe-ffm launcher (Mac / Linux).
#   ./transcribe-ffm.sh "/path/to/xxx.mp3" [options]
#
# whisper.cpp を JVM 内から FFM で呼ぶ（Python も whisper-cli も不要）。
# --enable-native-access は FFM の downcall に必要。付けないと JDK が警告を出す。
#
# 注意: jar に同梱されているネイティブライブラリは Windows 用だけなので、Mac / Linux では
# whisper-ffm をその OS でビルドして publishToMavenLocal し直すまでこのコマンドは失敗する。
# その場合は ./transcribe.sh（transcribe.whisper.engine=cpp = 外部の whisper-cli）を使う。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/transcribe-shell-0.0.1-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
  echo "jar が見つかりません: $JAR" >&2
  echo "  先に次を実行してください: (cd \"$SCRIPT_DIR\" && ./mvnw -DskipTests package)" >&2
  exit 1
fi

exec java --enable-native-access=ALL-UNNAMED "-Dspring.shell.interactive.enabled=false" \
  -jar "$JAR" transcribe-ffm "$@"
