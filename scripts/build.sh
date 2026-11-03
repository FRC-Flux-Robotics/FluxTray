#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p libs build/classes/main build/classes/test build/libs dist

if [ -f "$ROOT_DIR/../FluxFig/dist/fluxfig.jar" ]; then
  cp "$ROOT_DIR/../FluxFig/dist/fluxfig.jar" "$ROOT_DIR/libs/fluxfig-1.0.0.jar"
fi

rm -rf build/classes/main build/classes/test
mkdir -p build/classes/main build/classes/test build/libs dist

echo "[1/3] Compiling FluxTray (Java 17+ compatible bytecode)..."
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -cp libs/fluxfig-1.0.0.jar -d build/classes/main $(find src/main/java -name "*.java" | sort)
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -cp build/classes/main:libs/fluxfig-1.0.0.jar -d build/classes/test $(find src/test/java -name "*.java" | sort)

echo "[2/3] Running FluxTray unit, MockRobotServer & SystemCore Watch Metric tests..."
java -ea -Djava.awt.headless=true -cp build/classes/main:build/classes/test:libs/fluxfig-1.0.0.jar frc.flux.tray.FluxTrayTest

echo "[3/3] Packaging self-contained executable build/libs/FluxTray.jar & dist/FluxTray.jar..."
(
  cd build/classes/main
  jar xf "$ROOT_DIR/libs/fluxfig-1.0.0.jar" frc
)
jar --create --date=1980-01-01T00:00:02Z --file build/libs/FluxTray.jar --main-class frc.flux.tray.FluxTrayMain -C build/classes/main .
cp build/libs/FluxTray.jar dist/FluxTray.jar

echo "Done! Executable JAR: $ROOT_DIR/dist/FluxTray.jar"
