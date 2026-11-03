# AGENTS.md — FluxTray Standalone Agent Guide (FRC Team 10413)

## 1. Repository Overview & Scope
`FluxTray` (`frc.flux.tray`) is the **standalone zero-dependency Java 17+ (`--release 17`) Driver Station system tray agent, SFTP package/config switcher, post-match log puller, offline scouting QR/PWA manager, and 14-metric SystemCore/roboRIO watch auditor** for **FRC Team 10413 (FLUX Robotics)**:
- [`README.md`](README.md): Core capabilities, 14 watch metrics table, and CLI commands.
- [`libs/fluxfig-1.0.0.jar`](libs/fluxfig-1.0.0.jar): Bundled `FluxFig` library providing strict pre-deploy CSV validation (`FluxFig.validateStrict()`), tuning snapshot promotion (`promoteSnapshotToDirectory`), and `system-snapshot.txt` parsing/evaluation (`FluxSystemSnapshot`).
- [`dist/FluxTray.jar`](dist/FluxTray.jar): Self-contained executable fat-jar (`java -jar dist/FluxTray.jar`). Running with no arguments (or `--pull-logs`) downloads and packages robot + Driver Station logs by default into `./FLUX-10413-Hub/04_Practice_And_Match_Logs/match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip` (overridable via `--dir`, `--file`, or `--out`).

## 2. Safety & Build Standards
1. **Strict `Enabled` / `FMS` Transfer Lockout (`RobotSafetyLockout`):**
   - Never initiate or continue any SFTP/SSH deploy, config push, rollback, or log pull while the robot is `Enabled` or connected to `FMS`.
2. **Java 17+ Compatible Bytecode (`--release 17`):**
   - Always compile with `javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror` so `dist/FluxTray.jar` runs on any Driver Station or pit laptop with WPILib 2026 (JDK 17) or WPILib 2027 (JDK 25).
3. **Verification Command:**
   ```bash
   ./scripts/build.sh
   ```
