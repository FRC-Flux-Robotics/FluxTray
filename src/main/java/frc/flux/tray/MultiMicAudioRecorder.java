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
import java.util.concurrent.atomic.AtomicBoolean;
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
public final class MultiMicAudioRecorder {

  public static final float SAMPLE_RATE_HZ = 16_000.0f;
  public static final int SAMPLE_SIZE_BITS = 16;
  public static final int CHANNELS = 1;
  public static final int BYTES_PER_SECOND =
      Math.round(SAMPLE_RATE_HZ) * CHANNELS * (SAMPLE_SIZE_BITS / 8);
  public static final AudioFormat CAPTURE_FORMAT =
      new AudioFormat(SAMPLE_RATE_HZ, SAMPLE_SIZE_BITS, CHANNELS, true, false);

  public static final long POST_DISABLE_DEBRIEF_WINDOW_MS = 15_000L;
  public static final int STREAM_CHUNK_BYTES = 16 * 1024;
  public static final String AUDIO_FILE_NAME = "mentor-audio.wav";
  public static final String AUDIO_SYNC_FILE_NAME = "audio-sync.json";

  private static final Pattern SPOKEN_BATTERY_PATTERN =
      Pattern.compile("(?i)\\b(?:(202[67])[- ])?(B\\d{2})\\b");

  public record VoiceCue(String role, double timeSec, String transcript) {}

  /** Port for PCM audio input (backed by {@link TargetDataLine} in production or mock PCM in tests). */
  public interface PcmCapturePort extends Closeable {
    int read(byte[] buffer, int offset, int length) throws IOException;
  }

  /** Factory opening a live PCM capture stream (returns {@code null} if no microphone is present). */
  @FunctionalInterface
  public interface PcmCaptureFactory {
    PcmCapturePort open(AudioFormat format) throws LineUnavailableException;
  }

  private final PcmCaptureFactory captureFactory;
  private final List<VoiceCue> recordedCues = new ArrayList<>();
  private final AtomicBoolean captureActive = new AtomicBoolean(false);
  private boolean recording = false;
  private boolean micAvailable = false;
  private long enableStartEpochMs = 0L;
  private long disableStartEpochMs = 0L;
  private Path tempPcmFile = null;
  private PcmCapturePort activePort = null;
  private Thread captureThread = null;

  public MultiMicAudioRecorder() {
    this(MultiMicAudioRecorder::openSystemTargetDataLine);
  }

  public MultiMicAudioRecorder(PcmCaptureFactory captureFactory) {
    this.captureFactory =
        captureFactory != null ? captureFactory : MultiMicAudioRecorder::openSystemTargetDataLine;
  }

  private static PcmCapturePort openSystemTargetDataLine(AudioFormat format)
      throws LineUnavailableException {
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
    if (!AudioSystem.isLineSupported(info)) {
      return null;
    }
    TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
    line.open(format, STREAM_CHUNK_BYTES * 2);
    line.start();
    return new PcmCapturePort() {
      @Override
      public int read(byte[] buffer, int offset, int length) {
        return line.read(buffer, offset, length);
      }

      @Override
      public void close() {
        line.stop();
        line.close();
      }
    };
  }

  public synchronized void onRobotStateChanged(boolean enabled, long nowMs) {
    if (enabled && !recording) {
      stopCaptureWorkerQuietly();
      cleanupTempPcmQuietly();
      recording = true;
      enableStartEpochMs = nowMs;
      disableStartEpochMs = 0L;
      recordedCues.clear();
      startCaptureWorker();
    } else if (!enabled && recording && disableStartEpochMs == 0L) {
      disableStartEpochMs = nowMs;
    }
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
      cleanupTempPcmQuietly();
    }
    return wavFile;
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

  private void startCaptureWorker() {
    try {
      PcmCapturePort port = captureFactory.open(CAPTURE_FORMAT);
      if (port == null) {
        micAvailable = false;
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
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(pcmFile), STREAM_CHUNK_BYTES)) {
                  while (captureActive.get()) {
                    int n = port.read(buf, 0, buf.length);
                    if (n < 0) {
                      break;
                    }
                    if (n > 0) {
                      out.write(buf, 0, n);
                    }
                  }
                  out.flush();
                } catch (IOException ignored) {
                }
              },
              "fluxtray-audio-capture");
      t.setDaemon(true);
      this.captureThread = t;
      t.start();
    } catch (Exception ignored) {
      micAvailable = false;
      stopCaptureWorkerQuietly();
      cleanupTempPcmQuietly();
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
    if (t != null) {
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
