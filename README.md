# FluxTray (`FluxTray.jar`) — Driver Station System Tray Agent & Offline SystemCore Watch Auditor

**`FluxTray`** is the lightweight (~35 MB RAM) pure-Java 17+ (`--release 17`, running on both WPILib 2026 JDK 17 and WPILib 2027 JDK 25) Driver Station system tray utility and offline SystemCore / roboRIO telemetry collector for **FRC Team 10413 (FLUX Robotics)**.

---

## 1. Core Capabilities

1. **Live NT4 State Detection, Defense-in-Depth `/tmp/flux-robot-state` Lockout & `Enabled -> Disabled` Auto-Trigger ([`Nt4RobotStateClient.java`](src/main/java/frc/flux/tray/Nt4RobotStateClient.java), [`RobotSafetyLockout.java`](src/main/java/frc/flux/tray/RobotSafetyLockout.java)):**
   * Connects over pure-Java JDK 17 WebSockets to `ws://<host>:5810/nt/FluxTray` reading `/FMSInfo/FMSControlData` (`Enabled` bit `0x01`, `FMS` bit `0x10`) and checks robot-side `/tmp/flux-robot-state` over SSH before every mutating transfer and every `scp` launch.
   * Initializes `lastDisabledTimestampMs = -1L` so automatic pulls trigger strictly once per observed `Enabled -> Disabled` transition after `>= 3.0s` continuously Disabled.
2. **Atomic `robotCommand` Package / Config Deployer, 5-Slot Full-State Rollback & JVM Restart ([`PackageDeployer.java`](src/main/java/frc/flux/tray/PackageDeployer.java), [`SshRobotTransport.java`](src/main/java/frc/flux/tray/SshRobotTransport.java)):**
   * Multi-host roboRIO discovery (`10.104.13.2`, `172.22.11.2`, `roboRIO-10413-FRC.local`, `lvuser@`), split timeouts (`15 s` SSH command / `300 s` SCP transfer), OS-safe `UserKnownHostsFile=NUL|/dev/null`, SFTP-mode `scp`, and safe probing of `~/logs`, `/U/logs`, and `/media/sda1`.
   * Stages JAR + `deploy/` uploads to `.fluxtray.tmp` and atomically swaps onto the exact JAR target executed by `/home/lvuser/robotCommand` (e.g., `Flux-RoboRIO-2027.jar`), snapshots both JAR and `deploy/` inside `jars/prev_1..5` & `prev_stable` (never overwritten by dirty `EXPERIMENTAL` packages), and runs `/usr/local/frc/bin/frcKillRobot.sh -t -r` after every deploy, config push, or rollback.
   * **Sub-50 ms Mode & Profile Switch (`--set-mode PRODUCTION|TUNING`):** Writes `deploy/configs/active-profile.txt` preserving the connected robot's detected identity (`FUEL`, `CORAL`, `COMP_2027`).
   * **End-of-Session Tuning Harvest & Version Promotion (`--promote-tuning`):** Pulls `/tmp/fluxfig-tuned-snapshot.txt`, promotes `vN+1` into `03_FluxFig_Configs/*.csv` + `releases.csv`, deletes `/tmp/fluxfig-tuned-snapshot.txt`, re-uploads locked `mode=PRODUCTION` CSVs, and restarts robot code.
3. **Real `16 kHz` PCM Audio Capture (`mentor-audio.wav`) & Phone Video Drop Convention (`logs/video/`) ([`MultiMicAudioRecorder.java`](src/main/java/frc/flux/tray/MultiMicAudioRecorder.java)):**
   * Captures real pure-Java `javax.sound.sampled.TargetDataLine` `16 kHz`, 16-bit mono PCM (`~1.9 MB/min`, zero native codecs) automatically while `Enabled` plus a `15 s` post-`Disabled` narration window into `mentor-audio.wav` + `audio-sync.json` (`enableEpochMs`, `disableEpochMs`) inside the run ZIP.
   * **Phone Video Drop Convention (`logs/video/`):** Video is recorded separately on a phone (not the Driver Station laptop webcam) and uploaded into the synced Google Drive `logs/video/` folder alongside the `match-logs_*.zip` bundles in `logs/`.
4. **Two-Folder Google Drive Watcher, Incremental Sync & Mentor UX ([`DriveWatcher.java`](src/main/java/frc/flux/tray/DriveWatcher.java), [`BatteryAndRunManager.java`](src/main/java/frc/flux/tray/BatteryAndRunManager.java)):**
   * Double-clicking `FluxTray.jar` (or running `--tray --code-dir <drive/code> --logs-dir <drive/logs>`) watches `code/` for `.fluxpkg.zip` and `code/configs/*.csv` with SHA-256 2-poll stabilization (`~2 s`), runs local `FluxFig.validateStrict()`, **pulls pending robot logs FIRST before any deploy**, atomically deploys + restarts, and restores `prev_stable` if `code/` is emptied.
   * Maintains `synced-remote-files.json` incremental remote ledger, filters local Driver Station `.dslog`/`.dsevents` to `±4 min` around each run, stages runs in `%TEMP%` with `finally` cleanup, writes raw `system-snapshot.txt` (zero fabricated defaults), and atomically renames `.zip.part -> .zip` into Drive `logs/` (no duplicate raw run folders).
   * Color-coded tray icon (**Green** Ready / **Red** Enabled / **Yellow** Syncing / **Gray** Disconnected), Windows toast notifications, persistent `fluxtray.log`, and 1-click **Pull Logs Now** / **Restore STABLE** menu items.
5. **Zero-Robot-Code Offline SystemCore & Controller Watch Auditor ([`FluxSystemSnapshot.java`](https://github.com/FRC-Flux-Robotics/FluxFig/blob/main/src/main/java/frc/lib/fluxfig/FluxSystemSnapshot.java)):**
   * Captures `system-snapshot.txt` over SSH after a run via `FluxSystemSnapshot.REMOTE_CAPTURE_SH_COMMAND` (requiring zero extra code inside the robot control loop) and evaluates **14 watch metrics across 6 categories** via `FluxSystemSnapshot.evaluate()` alongside [`FluxProcessor`](https://github.com/FRC-Flux-Robotics/FluxProcessor) (`SystemHealthWatchAnalyzer.java`), mapping every anomaly to a concrete engineering, `FluxFig` CSV, or pit maintenance decision:

| # | Watch Metric | Source (`system-snapshot.txt` / `.wpilog`) | Warning / Fail Threshold | Concrete Engineering, `FluxFig`, or Pit Decision |
| :---: | :--- | :--- | :--- | :--- |
| **1** | **Loop Period Jitter (`p99` $\Delta t$ & Max)** | `.wpilog` (`Performance/TotalLoopMs`) | `p99 <= 1.30 * loopPeriodMs` (`6.5 ms` @ 200 Hz, `26.0 ms` @ 50 Hz) | **Decide `LOOP_PERIOD_SECS` (`50 Hz` vs. `100 Hz` vs. `200 Hz`):** Only promote SystemCore main loop to `100 Hz` or `200 Hz` if `p99` stays below `1.30 * loopPeriodMs` across 5 matches. |
| **2** | **Per-Subsystem Execution (`DriveMs`, `VisionMs`, `SuperstructureMs`)** | `.wpilog` (`Performance/*Ms`) | `Drive <= 2.0 ms`, `Vision <= 2.5 ms`, `Super <= 1.5 ms` | **Pinpoint Code Bottlenecks:** Cap `maxFramesPerCameraPerLoop` in `Vision.java` if `VisionMs` spikes; move unbatched `StatusSignal.refresh()` calls into `PhoenixSignals` if `DriveMs` spikes. |
| **3** | **First-Enable Cold JIT Spike (`t = 0.0..0.5s`)** | `.wpilog` (`Performance/TotalLoopMs` in Auto `t = 0..0.5s`) | First-enable `> 12.0 ms` while median `< 0.7 * loopPeriodMs` | **Verify Pre-Match JVM JIT Warmup:** Add the newly added solver/command path (`ShotCalculator`, `SafeAutoBuilder`, `ZeroPriorBootstrapSolver`) to `robotInit()` dummy warmup iterations. |
| **4** | **250 Hz Odometry Thread Jitter (`PhoenixOdometryThread`)** | `.wpilog` (`Drive/OdometryPeriodMs`) | $\Delta t > \pm 1.50\text{ ms}$ around `4.0 ms` | **Tune CAN Status Frame Rates on `can_s0`:** Pin `PhoenixOdometryThread` to Core 3 (`taskset -c 3`) and lower non-critical status signals on `can_s0` from `50 Hz` to `4 Hz`. |
| **5** | **SocketCAN RX/TX Drops & Overruns (`can_s0`, `can_s2`, `can_s4`)** | `system-snapshot.txt` (`ip -d -s link show can_s*`) | `rx_dropped > 0`, `rx_over_errors > 0`, or `tx_dropped > 0` | **Rebalance CAN FD Buses:** Non-zero `rx_over_errors` proves the internal SPI bus (`~12,500 FPS` ceiling) is saturating—move auxiliary devices to `can_s2`/`can_s4` or lower signal rates. |
| **6** | **Forbidden Odd CAN Port Traffic (`can_s1` & `can_s3`, Issue #342)** | `system-snapshot.txt` (`rx_packets + tx_packets` on `can_s1`, `can_s3`) | `packets > 0` on `can_s1` or `can_s3` | **Fix Physical CAN Port / `mechanisms.csv` Bus Name:** `CAN 0 + CAN 1` share SPI Bus 1 and `CAN 3 + CAN 4` share SPI Bus 3; move connections strictly to even ports (`can_s0`, `can_s2`, `can_s4`). |
| **7** | **CAN Error State & Error Counters (`berr-counter`, Issue #163)** | `system-snapshot.txt` (`STATE`, `berr_rx`, `berr_tx`) | `STATE != ERROR-ACTIVE` or `berr > 0` | **Inspect CAN Wiring Before Bus-Off:** Rising `berr-counter` warns of a loose Weidmüller splice, chafed CAN wire, or missing $120\;\Omega$ terminator before a fatal `BUS-OFF` lockup. |
| **8** | **Linux Kernel `softirq` CPU % (`/proc/stat`)** | `system-snapshot.txt` (`softIrqPct`) | `softIrqPct > 25.0%` (`WARN`), `> 35.0%` (`CRITICAL`) | **Audit Interrupt Storm (`mcp2518fd` SPI + USB UVC):** Balance CAN status frame rates or lower OV9281 USB camera capture rate from `90 FPS` $\to$ `60 FPS`. |
| **9** | **Java CPU % vs. Native C++ Vision Daemon CPU % (`limelightsc0..3`)** | `system-snapshot.txt` (`totalCpuPct`, `javaProcessCpuPct`, `visionDaemonCpuPct`) | `totalCpuPct > 80.0%` while `javaProcessCpuPct < 30.0%` | **Tune Camera Pipeline Load:** Enable 2x AprilTag detector decimation during Teleop or reduce camera FPS in `cameras.csv` if vision starves Java. |
| **10** | **CM5 Die Temperature & Sticky Thermal Throttling (`0x40004`)** | `system-snapshot.txt` (`cpuTempC`, `throttledHex`) | `cpuTempC >= 80 °C` or `0x40004` (`WARN`), `>= 85 °C` (`CRITICAL`) | **Add Pit Cooling Fan & Stop Pit Video Recording:** CM5 throttles from `2.4 GHz` $\to$ `1.5 GHz` at `80 °C`; place a blower on SystemCore in the pit and throttle idle camera processing while Disabled. |
| **11** | **CM5 5V Rail Undervoltage Sticky Bit (`0x10001`)** | `system-snapshot.txt` (`throttledHex & 0x10001`) | `(throttledHex & 0x10001) != 0` | **Fix 5V / USB Camera Brownouts:** Proves the internal 5V rail dipped below `4.63 V` (resetting USB OV9281 cameras mid-match)—check USB 3.0 camera 5V current draw and 3.3V/5V SmartIO sensors. |
| **12** | **JVM Heap Sawtooth Slope (`MB/s`) & GC Pause Correlation** | `.wpilog` (`SystemStats/RAMUsageMB`) + `system-snapshot.txt` (`heapAllocRateMbPerSec`, `gcCorrelatedLoopSpikes`) | Slope `> 2.0 MB/s` or `gcCorrelatedLoopSpikes > 0` | **Eliminate Per-Loop Allocations:** Replace per-loop `ArrayList` / `Optional` / stream allocations with pre-allocated buffers and match `-Xms` to `-Xmx`. |
| **13** | **eMMC Free Space (`MB`) & Disk `iowait` %** | `system-snapshot.txt` (`freeStorageMb`, `ioWaitPct`, `logWriteLatencyMaxMs`) | `freeStorageMb < 400 MB` (`WARN`) / `< 150 MB` (`CRITICAL`), `ioWaitPct > 5.0%`, or `writeMax > 25.0 ms` | **Switch Camera Recording Preset & Clean eMMC:** Switch from continuous `15 FPS` video (`48 MB/match`) to event-driven snapshots (`4.5 MB/match`) and verify `SignalLogger.enableAutoLogging(false)` is active. |
| **14** | **ARMv8 PMUv3 Hardware Counters (`IPC`, `L1D`/`LLC Miss %`, `Involuntary CSW`)** | `system-snapshot.txt` (Bench `perf stat`) | `ipc < 0.80`, `l1dCacheMissPct > 8.0%`, `llcMissPct > 15.0%`, or `involuntaryContextSwitches > 500` | **Pre-Event Bench Qualification (`COMP_2027`):** Isolate Core 3 (`isolcpus=3` / `taskset`) for Java + 250 Hz odometry and pin `limelightsc0..3` daemons to Cores 0–2. |

6. **Zero-Hardware Offline Match Stand Scouting PWA (`/scout`) & Signed QR Scanner ([`ScoutingQrManager.java`](src/main/java/frc/flux/tray/ScoutingQrManager.java)):**
   * **Offline-Cached Mobile PWA (`--export-scout-pwa <out-dir>`):** Generates `scout.html`, `sw.js` Service Worker, `manifest.webmanifest`, and `enroll-qr.png` (100% Rule `E301` & `G303`/`R905` safe, zero Wi-Fi or custom RF hardware required).
   * **One-Time Key Enrollment (`#enroll?student=...&key=...`):** Provisions `scouterId` and the team's 256-bit `HMAC-SHA256` secret into `localStorage` and immediately strips `#enroll` via `history.replaceState`.
   * **Anti-Spoof & Anti-Replay Verification (`--scan-scout-qr <qr-image.png | FLUXSCOUT1-payload> <FLUX-10413-Hub-dir>`):** Decodes 45x45 2D Finder-Framed Matrix PNGs (with 3 corner 7x7 bullseye finders and CRC-32 check), verifies the 64-bit `HMAC-SHA256` tag in constant time, enforces strictly increasing sequence numbers per `(eventCode, matchNumber, allianceStation, scouterId)`, and writes `04_Practice_And_Match_Logs/scouting-matches.csv`.

---

## 2. Build, Test & Run

### Linux / macOS
```bash
./scripts/build.sh
java -jar dist/FluxTray.jar --self-test
```

### Driver Station CLI & System Tray Execution
Run `dist/FluxTray.jar` directly to download and package robot logs into `./FLUX-10413-Hub/04_Practice_And_Match_Logs/match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip` by default, or pass flags:
```bash
java -jar dist/FluxTray.jar
java -jar dist/FluxTray.jar --dir C:\Users\DriverStation\Desktop --file my-match-logs.zip
java -jar dist/FluxTray.jar --out C:\Users\DriverStation\Desktop\qual14-logs.zip
java -jar dist/FluxTray.jar --tray
java -jar dist/FluxTray.jar --inspect-snapshot path/to/system-snapshot.txt
java -jar dist/FluxTray.jar --deploy path/to/STABLE_v2027.2.0.fluxpkg.zip
java -jar dist/FluxTray.jar --set-mode TUNING --profile COMP_2027
java -jar dist/FluxTray.jar --promote-tuning C:\Users\DriverStation\FLUX-10413-Hub\03_FluxFig_Configs --author alex --notes "Dialed L4 elevator kP"
java -jar dist/FluxTray.jar --restore-stable
java -jar dist/FluxTray.jar --sync-pending C:\Users\DriverStation\FLUX-10413-Hub
java -jar dist/FluxTray.jar --export-scout-pwa C:\Users\DriverStation\FLUX-10413-Hub\05_Scouting_PWA
java -jar dist/FluxTray.jar --scan-scout-qr path/to/match14-qr.png C:\Users\DriverStation\FLUX-10413-Hub
```
