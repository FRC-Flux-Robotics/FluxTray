@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0\.."
set "ROOT_DIR=%CD%"

set "JDK_BIN="
where javac >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "JAVAC_CMD=javac"
    set "JAVA_CMD=java"
    set "JAR_CMD=jar"
) else (
    for %%Y in (2027 2026 2025) do (
        if exist "C:\Users\Public\wpilib\%%Y\jdk\bin\javac.exe" (
            set "JDK_BIN=C:\Users\Public\wpilib\%%Y\jdk\bin"
            goto :found_jdk
        )
    )
    echo [ERROR] Could not find JDK 17+ in PATH or C:\Users\Public\wpilib\202*\jdk\bin
    exit /b 1
)

:found_jdk
if defined JDK_BIN (
    set "JAVAC_CMD=%JDK_BIN%\javac.exe"
    set "JAVA_CMD=%JDK_BIN%\java.exe"
    set "JAR_CMD=%JDK_BIN%\jar.exe"
)

if not exist "libs" mkdir "libs"
if not exist "build\classes\main" mkdir "build\classes\main"
if not exist "build\classes\test" mkdir "build\classes\test"
if not exist "build\libs" mkdir "build\libs"
if not exist "dist" mkdir "dist"

if exist "..\FluxFig\dist\fluxfig.jar" copy /y "..\FluxFig\dist\fluxfig.jar" "libs\fluxfig-1.0.0.jar" >nul

echo [1/3] Compiling FluxTray (Java 17+ compatible bytecode)...
dir /s /b src\main\java\*.java > build\sources.txt
"%JAVAC_CMD%" --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -cp "libs\fluxfig-1.0.0.jar" -d build\classes\main @build\sources.txt
if errorlevel 1 exit /b 1

dir /s /b src\test\java\*.java > build\test-sources.txt
"%JAVAC_CMD%" --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -cp "build\classes\main;libs\fluxfig-1.0.0.jar" -d build\classes\test @build\test-sources.txt
if errorlevel 1 exit /b 1

echo [2/3] Running FluxTray unit, MockRobotServer ^& SystemCore Watch Metric tests...
"%JAVA_CMD%" -ea -cp "build\classes\main;build\classes\test;libs\fluxfig-1.0.0.jar" frc.flux.tray.FluxTrayTest
if errorlevel 1 exit /b 1

echo [3/3] Packaging executable dist\FluxTray.jar...
pushd build\classes\main
"%JAR_CMD%" xf "%ROOT_DIR%\libs\fluxfig-1.0.0.jar" frc
popd
"%JAR_CMD%" --create --date=1980-01-01T00:00:02Z --file build\libs\FluxTray.jar --main-class frc.flux.tray.FluxTrayMain -C build\classes\main .
copy /y build\libs\FluxTray.jar dist\FluxTray.jar >nul

echo Done! Executable JAR: %ROOT_DIR%\dist\FluxTray.jar
