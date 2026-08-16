@echo off
rem Launcher for the 'transcribe-all' command. Usage: transcribe-all -d "folder" [options]
rem JAR is resolved from this script's own folder (%~dp0), so the jar you run is the jar
rem built in this folder - not a copy that happens to live somewhere else.
setlocal
set "JAR=%~dp0target\transcribe-shell-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" (
  echo jar not found: %JAR%
  echo Run "mvnw.cmd -DskipTests package" in %~dp0 first.
  pause
  exit /b 1
)
java "-Dspring.shell.interactive.enabled=false" -jar "%JAR%" transcribe-all %*
pause
endlocal
