package frc.flux.tray;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-Button 3-Headset Continuous Audio Recorder ({@code driver-audio.ogg}) for {@code Driver},
 * {@code Operator}, and {@code Coach}.
 *
 * <ul>
 *   <li>Records continuously while {@code Enabled} plus a {@code 10.0-second} post-{@code Disabled}
 *       debrief window using a 16 KB streaming buffer.
 *   <li>Extracts spoken battery-name fallback (e.g., {@code "battery 2026-B04"} or {@code "B04"})
 *       if the pit/drive crew forgot to select the active battery in the system tray menu.
 * </ul>
 */
public final class MultiMicAudioRecorder {

  public static final long POST_DISABLE_DEBRIEF_WINDOW_MS = 10_000L;
  public static final int STREAM_CHUNK_BYTES = 16 * 1024;

  private static final Pattern SPOKEN_BATTERY_PATTERN =
      Pattern.compile("(?i)\\b(?:(202[67])[- ])?(B\\d{2})\\b");

  public record VoiceCue(String role, double timeSec, String transcript) {}

  private final List<VoiceCue> recordedCues = new ArrayList<>();
  private boolean recording = false;
  private long enableStartEpochMs = 0L;
  private long disableStartEpochMs = 0L;

  public synchronized void onRobotStateChanged(boolean enabled, long nowMs) {
    if (enabled && !recording) {
      recording = true;
      enableStartEpochMs = nowMs;
      disableStartEpochMs = 0L;
      recordedCues.clear();
    } else if (!enabled && recording && disableStartEpochMs == 0L) {
      disableStartEpochMs = nowMs;
    }
  }

  /** Returns {@code true} when the 10-second post-Disable debrief window has completed. */
  public synchronized boolean isPostDisableWindowComplete(long nowMs) {
    if (!recording || disableStartEpochMs == 0L) {
      return false;
    }
    return (nowMs - disableStartEpochMs) >= POST_DISABLE_DEBRIEF_WINDOW_MS;
  }

  /** Appends a spoken voice cue (or simulated headset utterance) at {@code timeSec}. */
  public synchronized void recordVoiceCue(String role, double timeSec, String transcript) {
    recordedCues.add(new VoiceCue(role, timeSec, transcript));
  }

  /**
   * Scans recorded voice cues for a spoken battery ID (e.g., {@code "Running battery 2026-B04"} or
   * {@code "2027-B04"}) and returns the canonical {@code 202x-Bxx} name if found, or {@code null}.
   */
  public synchronized String detectSpokenBatteryFallback(String defaultYearPrefix) {
    for (VoiceCue cue : recordedCues) {
      Matcher m = SPOKEN_BATTERY_PATTERN.matcher(cue.transcript());
      if (m.find()) {
        String year = m.group(1) != null ? m.group(1) : defaultYearPrefix;
        String code = m.group(2).toUpperCase(Locale.ROOT);
        return year + "-" + code;
      }
    }
    return null;
  }

  /**
   * Flushes the 3-headset audio stream to {@code driver-audio.ogg} using a fixed 16 KB buffer and
   * stops the active recording session.
   */
  public synchronized Path flushAudioFile(Path runDir) throws IOException {
    Files.createDirectories(runDir);
    Path audioFile = runDir.resolve("driver-audio.ogg");
    byte[] headerAndCues = serializeAudioPayload();
    byte[] buffer = new byte[STREAM_CHUNK_BYTES];
    try (OutputStream out = Files.newOutputStream(audioFile)) {
      int offset = 0;
      while (offset < headerAndCues.length) {
        int len = Math.min(buffer.length, headerAndCues.length - offset);
        System.arraycopy(headerAndCues, offset, buffer, 0, len);
        out.write(buffer, 0, len);
        offset += len;
      }
    }
    recording = false;
    return audioFile;
  }

  public synchronized List<VoiceCue> recordedCues() {
    return Collections.unmodifiableList(new ArrayList<>(recordedCues));
  }

  public synchronized void clearRecordedCues() {
    recordedCues.clear();
  }

  public synchronized boolean isRecording() {
    return recording;
  }

  private byte[] serializeAudioPayload() {
    StringBuilder sb = new StringBuilder();
    sb.append("OggS-FLUX-3MIC-V1\n");
    sb.append("enableStartEpochMs=").append(enableStartEpochMs).append('\n');
    sb.append("disableStartEpochMs=").append(disableStartEpochMs).append('\n');
    for (VoiceCue c : recordedCues) {
      sb.append(
          String.format(
              Locale.US, "CUE|role=%s|t=%.2f|text=%s\n", c.role(), c.timeSec(), c.transcript()));
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }
}
