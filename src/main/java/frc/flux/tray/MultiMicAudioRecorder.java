package frc.flux.tray;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * Pure-Java {@link TargetDataLine} continuous audio recorder ({@code mentor-audio.wav} + {@code
 * audio-sync.json}) capturing {@code 16 kHz}, 16-bit signed little-endian mono PCM (~{@code 1.9
 * MB/min}, zero native codec dependencies) automatically while {@code Enabled} plus a {@code
 * 15-second} post-{@code Disabled} narration window.
 */
public final class MultiMicAudioRecorder implements Closeable {

  public static final float SAMPLE_RATE_HZ = 16_000.0f;
  public static final int SAMPLE_SIZE_BITS = 16;
  public static final int CHANNELS = 1;
  public static final int BYTES_PER_SECOND =
      Math.round(SAMPLE_RATE_HZ) * CHANNELS * (SAMPLE_SIZE_BITS / 8);
  public static final AudioFormat CAPTURE_FORMAT =
      new AudioFormat(SAMPLE_RATE_HZ, SAMPLE_SIZE_BITS, CHANNELS, true, false);

  public static final long POST_DISABLE_DEBRIEF_WINDOW_MS = 15_000L;
  public static final long MAX_RECORDING_BYTES = 30L * 1024L * 1024L;
  public static final long MAX_RECORDING_DURATION_MS = 15L * 60L * 1_000L;
  static final int MAX_CONSECUTIVE_ZERO_READS = 20;
  static final long ZERO_READ_BACKOFF_MS = 10L;
  public static final int STREAM_CHUNK_BYTES = 16 * 1024;
  public static final String AUDIO_FILE_NAME = "mentor-audio.wav";
  public static final String AUDIO_SYNC_FILE_NAME = "audio-sync.json";

  private static final Pattern SPOKEN_BATTERY_PATTERN =
      Pattern.compile("(?i)\\b(?:(202[67])[- ])?(B\\d{2})\\b");

  public record VoiceCue(String role, double timeSec, String transcript) {}

  /** Port for PCM audio input (backed by {@link TargetDataLine} in production or mock PCM in tests). */
  public interface PcmCapturePort extends Closeable {
    int read(byte[] buffer, int offset, int length) throws IOException;

    /** Returns {@code false} when the underlying OS audio line is closed or inactive. */
    default boolean isHealthy() {
      return true;
    }
  }

  /** Factory opening a live PCM capture stream (returns {@code null} if no microphone is present). */
  @FunctionalInterface
  public interface PcmCaptureFactory {
    PcmCapturePort open(AudioFormat format) throws LineUnavailableException;
  }

  private final PcmCaptureFactory captureFactory;
  private final long postDisableAutoStopDelayMs;
  private final long maxRecordingBytes;
  private final long maxRecordingDurationMs;
  private final List<VoiceCue> recordedCues = new ArrayList<>();
  private final AtomicBoolean captureActive = new AtomicBoolean(false);
  private volatile Path spoolRootDir = null;
  private volatile Consumer<String> errorListener = null;
  private volatile String lastCaptureError = null;
  private boolean recording = false;
  private boolean micAvailable = false;
  private long enableStartEpochMs = 0L;
  private long disableStartEpochMs = 0L;
  private long recordingGeneration = 0L;
  private Path tempPcmFile = null;
  private PcmCapturePort activePort = null;
  private Thread captureThread = null;
  private ScheduledExecutorService autoStopScheduler = null;
  private ScheduledFuture<?> postDisableStopTask = null;

  public MultiMicAudioRecorder() {
    this(MultiMicAudioRecorder::openSystemTargetDataLine);
  }

  public MultiMicAudioRecorder(PcmCaptureFactory captureFactory) {
    this(
        captureFactory,
        POST_DISABLE_DEBRIEF_WINDOW_MS,
        MAX_RECORDING_BYTES,
        MAX_RECORDING_DURATION_MS);
  }

  MultiMicAudioRecorder(
      PcmCaptureFactory captureFactory,
      long postDisableAutoStopDelayMs,
      long maxRecordingBytes,
      long maxRecordingDurationMs) {
    this.captureFactory =
        captureFactory != null ? captureFactory : MultiMicAudioRecorder::openSystemTargetDataLine;
    this.postDisableAutoStopDelayMs = Math.max(1L, postDisableAutoStopDelayMs);
    this.maxRecordingBytes = Math.max(1024L, maxRecordingBytes);
    this.maxRecordingDurationMs = Math.max(1L, maxRecordingDurationMs);
  }

  public void setSpoolRootDir(Path spoolRootDir) {
    this.spoolRootDir = spoolRootDir;
  }

  public Path spoolRootDir() {
    return spoolRootDir;
  }

  public void setErrorListener(Consumer<String> errorListener) {
    this.errorListener = errorListener;
  }

  public String lastCaptureError() {
    return lastCaptureError;
  }

  private static PcmCapturePort openSystemTargetDataLine(AudioFormat format)
      throws LineUnavailableException {
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
    if (!AudioSystem.isLineSupported(info)) {
      throw new LineUnavailableException(
          "No 16 kHz 16-bit mono microphone input line available on this system");
    }
    TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
    line.open(format, STREAM_CHUNK_BYTES * 2);
    line.start();
    return wrapTargetDataLine(line);
  }

  static PcmCapturePort wrapTargetDataLine(TargetDataLine line) {
    return new PcmCapturePort() {
      @Override
      public int read(byte[] buffer, int offset, int length) {
        return line.read(buffer, offset, length);
      }

      @Override
      public boolean isHealthy() {
        return line.isOpen() && line.isActive();
      }

      @Override
      public void close() {
        try {
          line.stop();
        } finally {
          line.close();
        }
      }
    };
  }

  public synchronized void onRobotStateChanged(boolean enabled, long nowMs) {
    if (enabled) {
      cancelPostDisableStopTask();
      if (recording && disableStartEpochMs > 0L) {
        // Re-enabled inside the 15 s post-disable debrief window: close and flush Run 1 to the
        // durable spool first so Run 2 starts with clean enable/disable timestamps and a full debrief.
        if (spoolRootDir != null) {
          try {
            flushToSpool(spoolRootDir);
          } catch (IOException ex) {
            notifyCaptureError("Failed to spool Run 1 audio on re-enable: " + ex.getMessage());
            stopCaptureWorkerQuietly();
            cleanupTempPcmQuietly();
            recording = false;
            enableStartEpochMs = 0L;
            disableStartEpochMs = 0L;
          }
        } else {
          stopCaptureWorkerQuietly();
          cleanupTempPcmQuietly();
          recording = false;
          enableStartEpochMs = 0L;
          disableStartEpochMs = 0L;
        }
      }
      if (!recording) {
        stopCaptureWorkerQuietly();
        cleanupTempPcmQuietly();
        recordingGeneration++;
        recording = true;
        enableStartEpochMs = nowMs;
        disableStartEpochMs = 0L;
        recordedCues.clear();
        lastCaptureError = null;
        startCaptureWorker(recordingGeneration);
      }
    } else if (recording && disableStartEpochMs == 0L) {
      disableStartEpochMs = nowMs;
      schedulePostDisableAutoStop(recordingGeneration);
    }
  }

  /**
   * Flushes the active recording into a unique durable spool subdirectory {@code
   * <spoolRoot>/<enableEpochMs>/} so a failed downstream pull never deletes mentor narration.
   */
  public synchronized Path flushToSpool(Path spoolRoot) throws IOException {
    Files.createDirectories(spoolRoot);
    String baseName =
        enableStartEpochMs > 0L
            ? Long.toString(enableStartEpochMs)
            : "run_" + System.currentTimeMillis();
    Path runSpoolDir = spoolRoot.resolve(baseName);
    int suffix = 1;
    while (Files.exists(runSpoolDir)) {
      runSpoolDir = spoolRoot.resolve(baseName + "_" + suffix);
      suffix++;
    }
    flushAudioFile(runSpoolDir);
    return runSpoolDir;
  }

  /** Returns {@code true} when the 15-second post-Disable debrief window has completed. */
  public synchronized boolean isPostDisableWindowComplete(long nowMs) {
    if (!recording || disableStartEpochMs == 0L) {
      return false;
    }
    return (nowMs - disableStartEpochMs) >= POST_DISABLE_DEBRIEF_WINDOW_MS;
  }

  public synchronized long remainingPostDisableMs(long nowMs) {
    if (!recording || disableStartEpochMs == 0L) {
      return 0L;
    }
    return Math.max(0L, POST_DISABLE_DEBRIEF_WINDOW_MS - (nowMs - disableStartEpochMs));
  }

  /** Appends a spoken voice cue (or simulated utterance) at {@code timeSec}. */
  public synchronized void recordVoiceCue(String role, double timeSec, String transcript) {
    recordedCues.add(new VoiceCue(role, timeSec, transcript));
  }

  /**
   * Scans recorded voice cues for a spoken battery ID (e.g., {@code "Running battery 2026-B04"})
   * and returns the canonical {@code 202x-Bxx} name if found, or {@code null}.
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
   * Stops live PCM capture, writes a playable {@code 16 kHz} 16-bit mono RIFF/WAVE file ({@code
   * mentor-audio.wav}) alongside {@code audio-sync.json} into {@code runDir}, and deletes the
   * temporary raw PCM buffer.
   */
  public synchronized Path flushAudioFile(Path runDir) throws IOException {
    Files.createDirectories(runDir);
    cancelPostDisableStopTask();
    stopCaptureWorkerQuietly();

    Path wavFile = runDir.resolve(AUDIO_FILE_NAME);
    Path syncFile = runDir.resolve(AUDIO_SYNC_FILE_NAME);
    long pcmBytes = 0L;
    try {
      if (tempPcmFile != null && Files.isRegularFile(tempPcmFile)) {
        pcmBytes = Files.size(tempPcmFile);
      }
      // Guarantee even 16-bit frame alignment.
      pcmBytes &= ~1L;
      writeWavFile(tempPcmFile, pcmBytes, wavFile);
      double durationSec = pcmBytes / (double) BYTES_PER_SECOND;
      String syncJson =
          String.format(
              Locale.US,
              "{\n"
                  + "  \"audioFile\": \"%s\",\n"
                  + "  \"sampleRateHz\": %d,\n"
                  + "  \"bitsPerSample\": %d,\n"
                  + "  \"channels\": %d,\n"
                  + "  \"enableEpochMs\": %d,\n"
                  + "  \"disableEpochMs\": %d,\n"
                  + "  \"postDisableWindowMs\": %d,\n"
                  + "  \"durationSec\": %.3f,\n"
                  + "  \"pcmBytes\": %d,\n"
                  + "  \"micAvailable\": %s\n"
                  + "}\n",
              AUDIO_FILE_NAME,
              Math.round(SAMPLE_RATE_HZ),
              SAMPLE_SIZE_BITS,
              CHANNELS,
              enableStartEpochMs,
              disableStartEpochMs,
              POST_DISABLE_DEBRIEF_WINDOW_MS,
              durationSec,
              pcmBytes,
              micAvailable);
      Files.writeString(syncFile, syncJson, StandardCharsets.UTF_8);
    } finally {
      recording = false;
      enableStartEpochMs = 0L;
      disableStartEpochMs = 0L;
      cleanupTempPcmQuietly();
    }
    return wavFile;
  }

  @Override
  public synchronized void close() {
    cancelPostDisableStopTask();
    if (autoStopScheduler != null) {
      autoStopScheduler.shutdownNow();
      autoStopScheduler = null;
    }
    recordingGeneration++;
    recording = false;
    enableStartEpochMs = 0L;
    disableStartEpochMs = 0L;
    stopCaptureWorkerQuietly();
    cleanupTempPcmQuietly();
  }

  synchronized Path currentTempPcmFile() {
    return tempPcmFile;
  }

  synchronized boolean isCaptureThreadAlive() {
    return captureThread != null && captureThread.isAlive();
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

  public synchronized boolean isMicAvailable() {
    return micAvailable;
  }

  public synchronized long enableStartEpochMs() {
    return enableStartEpochMs;
  }

  public synchronized long disableStartEpochMs() {
    return disableStartEpochMs;
  }

  private void schedulePostDisableAutoStop(long expectedGeneration) {
    cancelPostDisableStopTask();
    if (autoStopScheduler == null || autoStopScheduler.isShutdown()) {
      autoStopScheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "fluxtray-audio-autostop");
                t.setDaemon(true);
                return t;
              });
    }
    postDisableStopTask =
        autoStopScheduler.schedule(
            () -> autoStopAndSpoolIfCurrent(expectedGeneration, "post-disable debrief window elapsed"),
            postDisableAutoStopDelayMs,
            TimeUnit.MILLISECONDS);
  }

  private void cancelPostDisableStopTask() {
    ScheduledFuture<?> task = postDisableStopTask;
    postDisableStopTask = null;
    if (task != null) {
      task.cancel(false);
    }
  }

  private synchronized void autoStopAndSpoolIfCurrent(long expectedGeneration, String reason) {
    if (!recording || recordingGeneration != expectedGeneration) {
      return;
    }
    cancelPostDisableStopTask();
    Path spool = spoolRootDir;
    if (spool != null) {
      try {
        flushToSpool(spool);
        return;
      } catch (IOException ex) {
        notifyCaptureError("Failed to auto-spool audio (" + reason + "): " + ex.getMessage());
      }
    }
    stopCaptureWorkerQuietly();
    cleanupTempPcmQuietly();
    recording = false;
    enableStartEpochMs = 0L;
    disableStartEpochMs = 0L;
  }

  private void startCaptureWorker(long expectedGeneration) {
    try {
      PcmCapturePort port = captureFactory.open(CAPTURE_FORMAT);
      if (port == null) {
        micAvailable = false;
        notifyCaptureError("No microphone input available — mentor audio will be silent.");
        return;
      }
      Path pcmFile = Files.createTempFile("fluxtray-audio-", ".pcm");
      this.activePort = port;
      this.tempPcmFile = pcmFile;
      this.micAvailable = true;
      this.captureActive.set(true);

      Thread t =
          new Thread(
              () -> {
                byte[] buf = new byte[STREAM_CHUNK_BYTES];
                long totalBytesWritten = 0L;
                long startNano = System.nanoTime();
                int consecutiveZeroReads = 0;
                boolean capReached = false;
                boolean abortedOnDeviceFault = false;
                try (OutputStream out =
                    new BufferedOutputStream(Files.newOutputStream(pcmFile), STREAM_CHUNK_BYTES)) {
                  while (captureActive.get()) {
                    if (!port.isHealthy()) {
                      abortedOnDeviceFault = true;
                      notifyCaptureError(
                          "Microphone disconnected or inactive — stopping audio capture.");
                      break;
                    }
                    long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
                    if (totalBytesWritten >= maxRecordingBytes
                        || elapsedMs >= maxRecordingDurationMs) {
                      capReached = true;
                      break;
                    }
                    int n = port.read(buf, 0, buf.length);
                    if (n < 0) {
                      break;
                    }
                    if (n == 0) {
                      consecutiveZeroReads++;
                      if (!port.isHealthy()) {
                        abortedOnDeviceFault = true;
                        notifyCaptureError(
                            "Microphone disconnected or inactive — stopping audio capture.");
                        break;
                      }
                      if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                        abortedOnDeviceFault = true;
                        notifyCaptureError(
                            "Microphone stalled (consecutive 0-byte reads; USB headset unplugged) — stopping audio capture.");
                        break;
                      }
                      try {
                        Thread.sleep(ZERO_READ_BACKOFF_MS);
                      } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                      }
                      continue;
                    }
                    consecutiveZeroReads = 0;
                    int toWrite = (int) Math.min(n, maxRecordingBytes - totalBytesWritten);
                    if (toWrite > 0) {
                      out.write(buf, 0, toWrite);
                      totalBytesWritten += toWrite;
                    }
                    if (totalBytesWritten >= maxRecordingBytes) {
                      capReached = true;
                      break;
                    }
                  }
                  out.flush();
                } catch (IOException ex) {
                  if (captureActive.get()) {
                    abortedOnDeviceFault = true;
                    notifyCaptureError("Microphone stream interrupted: " + ex.getMessage());
                  }
                }
                if (abortedOnDeviceFault && captureActive.get()) {
                  try {
                    port.close();
                  } catch (IOException ignored) {
                  }
                }
                if (capReached && captureActive.get()) {
                  notifyCaptureError(
                      "Microphone recording reached safety cap ("
                          + (maxRecordingBytes / (1024L * 1024L))
                          + " MB / "
                          + (maxRecordingDurationMs / 60_000L)
                          + " min) — auto-stopping and spooling.");
                  autoStopAndSpoolIfCurrent(expectedGeneration, "max recording cap reached");
                }
              },
              "fluxtray-audio-capture");
      t.setDaemon(true);
      this.captureThread = t;
      t.start();
    } catch (Exception ex) {
      micAvailable = false;
      stopCaptureWorkerQuietly();
      cleanupTempPcmQuietly();
      String detail = ex.getMessage() != null && !ex.getMessage().isBlank()
          ? ex.getMessage()
          : ex.getClass().getSimpleName();
      notifyCaptureError("Microphone capture failed (" + detail + ") — mentor audio will be silent.");
    }
  }

  private void notifyCaptureError(String message) {
    lastCaptureError = message;
    Consumer<String> listener = errorListener;
    if (listener != null) {
      try {
        listener.accept(message);
      } catch (Exception ignored) {
      }
    }
  }

  private void stopCaptureWorkerQuietly() {
    captureActive.set(false);
    PcmCapturePort port = activePort;
    activePort = null;
    if (port != null) {
      try {
        port.close();
      } catch (IOException ignored) {
      }
    }
    Thread t = captureThread;
    captureThread = null;
    if (t != null && t != Thread.currentThread()) {
      try {
        t.join(2_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void cleanupTempPcmQuietly() {
    Path pcm = tempPcmFile;
    tempPcmFile = null;
    if (pcm != null) {
      try {
        Files.deleteIfExists(pcm);
      } catch (IOException ignored) {
      }
    }
  }

  private static void writeWavFile(Path rawPcmPath, long pcmBytes, Path wavFile) throws IOException {
    int dataLen = (int) Math.min(Integer.MAX_VALUE - 44L, Math.max(0L, pcmBytes));
    int riffChunkSize = 36 + dataLen;
    int sampleRate = Math.round(SAMPLE_RATE_HZ);
    short blockAlign = (short) (CHANNELS * (SAMPLE_SIZE_BITS / 8));
    int byteRate = sampleRate * blockAlign;

    ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    hdr.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    hdr.putInt(riffChunkSize);
    hdr.put("WAVE".getBytes(StandardCharsets.US_ASCII));
    hdr.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    hdr.putInt(16); // PCM fmt chunk length
    hdr.putShort((short) 1); // PCM linear quantization
    hdr.putShort((short) CHANNELS);
    hdr.putInt(sampleRate);
    hdr.putInt(byteRate);
    hdr.putShort(blockAlign);
    hdr.putShort((short) SAMPLE_SIZE_BITS);
    hdr.put("data".getBytes(StandardCharsets.US_ASCII));
    hdr.putInt(dataLen);

    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(wavFile), STREAM_CHUNK_BYTES)) {
      out.write(hdr.array());
      if (rawPcmPath != null && dataLen > 0 && Files.isRegularFile(rawPcmPath)) {
        byte[] buf = new byte[STREAM_CHUNK_BYTES];
        int remaining = dataLen;
        try (InputStream in = Files.newInputStream(rawPcmPath)) {
          while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0) {
              break;
            }
            out.write(buf, 0, n);
            remaining -= n;
          }
        }
      }
      out.flush();
    }
  }
}
