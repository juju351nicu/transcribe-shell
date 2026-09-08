@echo off
rem transcribe-ffm-all launcher: run  transcribe-ffm-all -d "folder" [options]
rem Uses whisper.cpp via FFM/Panama inside the JVM (no Python, no whisper-cli).
setlocal
set "JAR=%~dp0target\transcribe-shell-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" (
  echo jar not found: %JAR%
  echo Run "mvnw.cmd -DskipTests package" in %~dp0 first.
  pause
  exit /b 1
)
java --enable-native-access=ALL-UNNAMED "-Dspring.shell.interactive.enabled=false" -jar "%JAR%" transcribe-ffm-all %*
pause
endlocal
