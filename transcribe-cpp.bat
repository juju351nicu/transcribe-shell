@echo off
rem transcribe-cpp launcher: run  transcribe-cpp "path\to.mp3"  (or drag a .mp3 onto this file).
rem Uses whisper.cpp via FFM/Panama inside the JVM (no Python, no whisper-cli).
rem --enable-native-access is required for FFM downcalls (otherwise the JDK prints a warning).
rem JAR is resolved from this script's own folder (%~dp0).
setlocal
set "JAR=%~dp0target\transcribe-shell-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" (
  echo jar not found: %JAR%
  echo Run "mvnw.cmd -DskipTests package" in %~dp0 first.
  pause
  exit /b 1
)
java --enable-native-access=ALL-UNNAMED "-Dspring.shell.interactive.enabled=false" -jar "%JAR%" transcribe-cpp %*
pause
endlocal
