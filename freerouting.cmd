@echo off
rem freerouting.cmd - Windows launcher for the locally built freerouting jar.
rem
rem This is the Windows counterpart to the repo-root ./freerouting bash
rem script. It does NOT reimplement that script's rebuild-if-stale or
rem xvfb-fallback logic:
rem
rem   - Rebuilding on the fly needs Gradle, which means assuming a JDK and a
rem     writable Gradle cache are already set up on whatever Windows
rem     workstation runs Altium. That is a much bigger assumption on Windows
rem     than on the Linux/CI boxes the bash script targets, so this script
rem     instead just requires build\libs\freerouting-executable.jar to
rem     already exist (build it once with "gradlew executableJar") and fails
rem     with a clear message if it does not.
rem   - xvfb is an X11-only concept. A normal interactive Windows session
rem     always has a desktop, so there is nothing to fall back to. (A
rem     scheduled task or service running in Session 0 with no desktop is the
rem     one Windows situation this does NOT cover - freerouting's batch modes
rem     still construct a Swing window and need a real desktop session, same
rem     as the bash script's own comment about DISPLAY explains.)
rem
rem What IS carried over, because it matters just as much here: running from
rem a private SNAPSHOT COPY of the jar, never from build\libs directly. From
rem the bash script's own comment on why:
rem
rem   A JVM loads classes out of its jar lazily, for as long as it runs.
rem   Rebuilding while an instance is open therefore breaks that instance:
rem   every class it has not needed yet fails to load, as NoClassDefFoundError
rem   naming whatever it reached for next - and the reported class is
rem   arbitrary, so it looks like a code bug rather than a swapped file. Even
rem   the uncaught-exception handler falls over, because it cannot load
rem   log4j's ThrowableProxy to format the report.
rem
rem Usage:
rem   freerouting.cmd [application args...]
rem   e.g. freerouting.cmd -de board.frpcb.json -do board.rte -rm reroute
rem
rem See docs/frpcb-format.md and the ./freerouting bash script's own --help
rem for what the application args mean.

setlocal enabledelayedexpansion

rem Locate the jar. FREEROUTING_JAR wins if set (an absolute override);
rem otherwise assume this script sits at the repo root, next to build\libs,
rem exactly like ./freerouting does for its own jar path.
if defined FREEROUTING_JAR (
    set "JAR=%FREEROUTING_JAR%"
) else (
    set "JAR=%~dp0build\libs\freerouting-executable.jar"
)

if not exist "%JAR%" (
    echo freerouting.cmd: jar not found at "%JAR%" 1>&2
    echo Build it first with: gradlew executableJar 1>&2
    echo or set FREEROUTING_JAR to its full path. 1>&2
    exit /b 1
)

rem Snapshot the jar to a private temp copy so a concurrent rebuild in
rem another window cannot swap out the file this JVM has open (see the
rem rationale above - this is the one piece of the bash script that matters
rem just as much on Windows).
set "SNAPSHOT_DIR=%TEMP%\freerouting-run-%RANDOM%%RANDOM%"
mkdir "%SNAPSHOT_DIR%" 2>nul
set "SNAPSHOT_JAR=%SNAPSHOT_DIR%\freerouting-run.jar"
copy /y "%JAR%" "%SNAPSHOT_JAR%" >nul
if errorlevel 1 (
    echo freerouting.cmd: could not snapshot "%JAR%" to "%SNAPSHOT_JAR%" 1>&2
    exit /b 1
)

java -jar "%SNAPSHOT_JAR%" %*
set "EXIT_CODE=%ERRORLEVEL%"

del /f /q "%SNAPSHOT_JAR%" >nul 2>nul
rmdir "%SNAPSHOT_DIR%" >nul 2>nul

rem Forward the application's real exit code faithfully - a scripted caller
rem (see altium-scripts/ExportFrpcb.pas's RouteWithFreerouting) depends on
rem this to tell a successful run from a failed one.
exit /b %EXIT_CODE%
