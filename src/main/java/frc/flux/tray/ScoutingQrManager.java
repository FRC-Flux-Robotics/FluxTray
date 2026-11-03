package frc.flux.tray;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;

/**
 * Zero-Hardware Offline Match Stand Scouting PWA exporter, {@code HMAC-SHA256} anti-replay
 * verifier, 2D finder-matrix image encoder/decoder, and {@code scouting-matches.csv} ledger for
 * {@code FluxTray.jar} (100% Rule {@code E301} &amp; {@code G303}/{@code R905} compliant — zero
 * Wi-Fi required).
 */
public final class ScoutingQrManager {

  public static final String PAYLOAD_PREFIX = "FLUXSCOUT1";
  public static final String DEFAULT_TEAM_SECRET_HEX =
      "10413f10x2027c0de99aabbccddeeff00112233445566778899aabbccddeeff";
  public static final String SCOUTING_CSV_HEADER =
      "eventCode,matchNumber,allianceStation,teamNumber,scouterId,autoStartZone,autoScored,teleopCycles,defenseRating,endgameStatus,sequenceNumber,timestampEpochSec,queueNotes,hmacHex";

  private static final int MATRIX_GRID_SIZE = 45; // 45x45 grid with 3x 7x7 corner finders -> ~223 bytes capacity
  private static final int FINDER_SIZE = 7;

  /** 5-Tap Match Stand Scouting record synced via signed on-screen QR code. */
  public record ScoutingMatchEntry(
      String eventCode,
      int matchNumber,
      String allianceStation,
      int teamNumber,
      String scouterId,
      String autoStartZone,
      int autoScored,
      int teleopCycles,
      int defenseRating,
      String endgameStatus,
      long sequenceNumber,
      long timestampEpochSec,
      String queueNotes,
      String hmacHex) {

    public String deduplicationKey() {
      return eventCode.trim().toUpperCase(Locale.ROOT)
          + "|"
          + matchNumber
          + "|"
          + allianceStation.trim().toUpperCase(Locale.ROOT)
          + "|"
          + scouterId.trim().toLowerCase(Locale.ROOT);
    }

    public String toCsvRow() {
      String safeNotes =
          queueNotes == null ? "" : queueNotes.replace("\"", "'").replace(",", ";").trim();
      return String.format(
          Locale.US,
          "%s,%d,%s,%d,%s,%s,%d,%d,%d,%s,%d,%d,\"%s\",%s",
          eventCode.trim(),
          matchNumber,
          allianceStation.trim(),
          teamNumber,
          scouterId.trim(),
          autoStartZone.trim(),
          autoScored,
          teleopCycles,
          defenseRating,
          endgameStatus.trim(),
          sequenceNumber,
          timestampEpochSec,
          safeNotes,
          hmacHex.trim());
    }
  }

  private ScoutingQrManager() {}

  /**
   * Generates a one-time physical-button enrollment URL that provisions the team secret into the
   * student's browser {@code localStorage} and immediately strips {@code #enroll} from the URL bar
   * via {@code history.replaceState}.
   */
  public static String generateEnrollmentUri(
      String basePwaUrl, String scouterId, String secretKeyHex) {
    if (scouterId == null || scouterId.isBlank()) {
      throw new IllegalArgumentException("scouterId must not be blank");
    }
    if (secretKeyHex == null || secretKeyHex.length() < 32) {
      throw new IllegalArgumentException("secretKeyHex must be at least 128 bits (32 hex chars)");
    }
    String base = basePwaUrl == null || basePwaUrl.isBlank() ? "https://frc-flux-robotics.github.io/scout.html" : basePwaUrl.trim();
    return base + "#enroll?student=" + scouterId.trim() + "&key=" + secretKeyHex.trim();
  }

  /**
   * Signs a {@link ScoutingMatchEntry} and formats the compact {@code FLUXSCOUT1|...} QR payload.
   */
  public static String encodeSignedPayload(
      String eventCode,
      int matchNumber,
      String allianceStation,
      int teamNumber,
      String scouterId,
      String autoStartZone,
      int autoScored,
      int teleopCycles,
      int defenseRating,
      String endgameStatus,
      long sequenceNumber,
      long timestampEpochSec,
      String queueNotes,
      String secretKeyHex) {
    if (matchNumber <= 0) {
      throw new IllegalArgumentException("matchNumber must be > 0");
    }
    if (teamNumber <= 0) {
      throw new IllegalArgumentException("teamNumber must be > 0");
    }
    if (sequenceNumber <= 0) {
      throw new IllegalArgumentException("sequenceNumber must be > 0");
    }
    String cleanNotes = queueNotes == null ? "" : queueNotes.trim();
    String notesB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(cleanNotes.getBytes(StandardCharsets.UTF_8));
    String canonicalData =
        String.join(
            "|",
            PAYLOAD_PREFIX,
            eventCode.trim(),
            Integer.toString(matchNumber),
            allianceStation.trim(),
            Integer.toString(teamNumber),
            scouterId.trim(),
            autoStartZone.trim().toUpperCase(Locale.ROOT),
            Integer.toString(autoScored),
            Integer.toString(teleopCycles),
            Integer.toString(defenseRating),
            endgameStatus.trim().toUpperCase(Locale.ROOT),
            Long.toString(sequenceNumber),
            Long.toString(timestampEpochSec),
            notesB64);
    String hmac16 = computeHmacHex16(canonicalData, secretKeyHex);
    return canonicalData + "|" + hmac16;
  }

  /**
   * Verifies the {@code HMAC-SHA256} signature in constant time and parses a {@code FLUXSCOUT1|...}
   * QR payload into a {@link ScoutingMatchEntry}.
   */
  public static ScoutingMatchEntry verifyAndDecodePayload(String qrPayload, String secretKeyHex) {
    if (qrPayload == null || qrPayload.isBlank()) {
      throw new IllegalArgumentException("Empty QR payload");
    }
    String trimmed = qrPayload.trim();
    int lastPipe = trimmed.lastIndexOf('|');
    if (lastPipe <= 0) {
      throw new SecurityException("Malformed QR payload: missing signature delimiter");
    }
    String canonicalData = trimmed.substring(0, lastPipe);
    String providedHmac = trimmed.substring(lastPipe + 1).trim().toLowerCase(Locale.ROOT);
    String expectedHmac = computeHmacHex16(canonicalData, secretKeyHex);

    if (!MessageDigest.isEqual(
        expectedHmac.getBytes(StandardCharsets.UTF_8),
        providedHmac.getBytes(StandardCharsets.UTF_8))) {
      throw new SecurityException(
          "Invalid HMAC-SHA256 signature on scouting QR payload (tampered or wrong team key)");
    }

    String[] parts = canonicalData.split("\\|", -1);
    if (parts.length != 14 || !PAYLOAD_PREFIX.equals(parts[0])) {
      throw new IllegalArgumentException(
          "Unexpected FLUXSCOUT1 field count: expected 14 data fields, got " + parts.length);
    }

    String eventCode = parts[1];
    int matchNumber = Integer.parseInt(parts[2]);
    String allianceStation = parts[3];
    int teamNumber = Integer.parseInt(parts[4]);
    String scouterId = parts[5];
    String autoStartZone = parts[6];
    int autoScored = Integer.parseInt(parts[7]);
    int teleopCycles = Integer.parseInt(parts[8]);
    int defenseRating = Integer.parseInt(parts[9]);
    String endgameStatus = parts[10];
    long sequenceNumber = Long.parseLong(parts[11]);
    long timestampEpochSec = Long.parseLong(parts[12]);
    String notes =
        parts[13].isEmpty()
            ? ""
            : new String(Base64.getUrlDecoder().decode(parts[13]), StandardCharsets.UTF_8);

    return new ScoutingMatchEntry(
        eventCode,
        matchNumber,
        allianceStation,
        teamNumber,
        scouterId,
        autoStartZone,
        autoScored,
        teleopCycles,
        defenseRating,
        endgameStatus,
        sequenceNumber,
        timestampEpochSec,
        notes,
        expectedHmac);
  }

  /**
   * Verifies a signed QR payload, checks anti-replay monotonic sequence numbers against {@code
   * 04_Practice_And_Match_Logs/scouting-matches.csv}, and writes the updated CSV ledger.
   */
  public static ScoutingMatchEntry ingestVerifiedPayload(
      String qrPayload, Path hubDir, String secretKeyHex) throws IOException {
    ScoutingMatchEntry incoming = verifyAndDecodePayload(qrPayload, secretKeyHex);
    Path logsDir = hubDir.resolve("04_Practice_And_Match_Logs");
    Files.createDirectories(logsDir);
    Path csvPath = logsDir.resolve("scouting-matches.csv");

    Map<String, ScoutingMatchEntry> entriesByKey = new LinkedHashMap<>();
    if (Files.isRegularFile(csvPath)) {
      List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
      for (int i = 1; i < lines.size(); i++) {
        String line = lines.get(i).trim();
        if (line.isEmpty()) continue;
        ScoutingMatchEntry existing = parseCsvRow(line);
        entriesByKey.put(existing.deduplicationKey(), existing);
      }
    }

    String key = incoming.deduplicationKey();
    ScoutingMatchEntry existing = entriesByKey.get(key);
    if (existing != null && incoming.sequenceNumber() <= existing.sequenceNumber()) {
      throw new SecurityException(
          "Replay rejected for "
              + key
              + ": incoming seq="
              + incoming.sequenceNumber()
              + " <= existing seq="
              + existing.sequenceNumber());
    }

    entriesByKey.put(key, incoming);

    StringBuilder sb = new StringBuilder();
    sb.append(SCOUTING_CSV_HEADER).append('\n');
    for (ScoutingMatchEntry e : entriesByKey.values()) {
      sb.append(e.toCsvRow()).append('\n');
    }
    Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
    return incoming;
  }

  /**
   * Renders a compact UTF-8 payload into a high-contrast 2D Finder-Framed Matrix {@link
   * BufferedImage} (with 3 corner 7x7 bullseye finders and CRC-32 integrity check) suitable for
   * zero-dependency webcam/image scanning.
   */
  public static BufferedImage encodeVisualMatrixImage(String payload, int modulePixels) {
    byte[] data = payload.getBytes(StandardCharsets.UTF_8);
    int maxBytes = ((MATRIX_GRID_SIZE * MATRIX_GRID_SIZE) - (3 * 64) - 48) / 8;
    if (data.length > maxBytes) {
      throw new IllegalArgumentException(
          "Payload length (" + data.length + " bytes) exceeds 2D matrix capacity (" + maxBytes + ")");
    }

    CRC32 crc = new CRC32();
    crc.update(data);
    long crcVal = crc.getValue();

    // Header: 16-bit length + 32-bit CRC32 + payload bytes
    byte[] packet = new byte[6 + data.length];
    packet[0] = (byte) ((data.length >>> 8) & 0xFF);
    packet[1] = (byte) (data.length & 0xFF);
    packet[2] = (byte) ((crcVal >>> 24) & 0xFF);
    packet[3] = (byte) ((crcVal >>> 16) & 0xFF);
    packet[4] = (byte) ((crcVal >>> 8) & 0xFF);
    packet[5] = (byte) (crcVal & 0xFF);
    System.arraycopy(data, 0, packet, 6, data.length);

    boolean[][] grid = new boolean[MATRIX_GRID_SIZE][MATRIX_GRID_SIZE];
    drawFinderPattern(grid, 0, 0);
    drawFinderPattern(grid, MATRIX_GRID_SIZE - FINDER_SIZE, 0);
    drawFinderPattern(grid, 0, MATRIX_GRID_SIZE - FINDER_SIZE);

    int bitIdx = 0;
    int totalBits = packet.length * 8;
    for (int r = 0; r < MATRIX_GRID_SIZE; r++) {
      for (int c = 0; c < MATRIX_GRID_SIZE; c++) {
        if (isReservedFinderZone(r, c)) continue;
        if (bitIdx < totalBits) {
          int bytePos = bitIdx >>> 3;
          int bitOffset = 7 - (bitIdx & 7);
          boolean bit = ((packet[bytePos] >>> bitOffset) & 1) == 1;
          grid[r][c] = bit;
          bitIdx++;
        } else {
          // Checkerboard padding for visual alignment
          grid[r][c] = ((r + c) & 1) == 0;
        }
      }
    }

    int quietZone = 4;
    int totalModules = MATRIX_GRID_SIZE + quietZone * 2;
    int imgSize = totalModules * modulePixels;
    BufferedImage img = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, imgSize, imgSize);
    g.setColor(Color.BLACK);
    for (int r = 0; r < MATRIX_GRID_SIZE; r++) {
      for (int c = 0; c < MATRIX_GRID_SIZE; c++) {
        if (grid[r][c]) {
          g.fillRect(
              (c + quietZone) * modulePixels,
              (r + quietZone) * modulePixels,
              modulePixels,
              modulePixels);
        }
      }
    }
    g.dispose();
    return img;
  }

  /**
   * Decodes a 2D Finder-Framed Matrix {@link BufferedImage} (from a webcam snapshot or PNG file)
   * back into its UTF-8 payload string and verifies its CRC-32 checksum.
   */
  public static String decodeVisualMatrixImage(BufferedImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    int minX = w, minY = h, maxX = -1, maxY = -1;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if (isDarkPixel(image.getRGB(x, y))) {
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX <= minX || maxY <= minY) {
      throw new IllegalArgumentException("No 2D matrix bounding box detected in image");
    }

    double stepX = (double) (maxX - minX + 1) / MATRIX_GRID_SIZE;
    double stepY = (double) (maxY - minY + 1) / MATRIX_GRID_SIZE;

    List<Boolean> bits = new ArrayList<>();
    for (int r = 0; r < MATRIX_GRID_SIZE; r++) {
      for (int c = 0; c < MATRIX_GRID_SIZE; c++) {
        if (isReservedFinderZone(r, c)) continue;
        int sampleX = (int) Math.round(minX + (c + 0.5) * stepX);
        int sampleY = (int) Math.round(minY + (r + 0.5) * stepY);
        sampleX = Math.max(0, Math.min(w - 1, sampleX));
        sampleY = Math.max(0, Math.min(h - 1, sampleY));
        bits.add(isDarkPixel(image.getRGB(sampleX, sampleY)));
      }
    }

    byte[] raw = new byte[bits.size() / 8];
    for (int i = 0; i < raw.length * 8; i++) {
      if (bits.get(i)) {
        raw[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
      }
    }

    int len = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
    if (len <= 0 || 6 + len > raw.length) {
      throw new IllegalArgumentException("Decoded invalid payload length from 2D matrix: " + len);
    }
    long expectedCrc =
        ((long) (raw[2] & 0xFF) << 24)
            | ((long) (raw[3] & 0xFF) << 16)
            | ((long) (raw[4] & 0xFF) << 8)
            | ((long) (raw[5] & 0xFF));

    byte[] payloadBytes = new byte[len];
    System.arraycopy(raw, 6, payloadBytes, 0, len);
    CRC32 crc = new CRC32();
    crc.update(payloadBytes);
    if (crc.getValue() != expectedCrc) {
      throw new IllegalStateException(
          String.format(
              Locale.US,
              "2D matrix CRC-32 mismatch: expected=0x%08x, actual=0x%08x",
              expectedCrc,
              crc.getValue()));
    }
    return new String(payloadBytes, StandardCharsets.UTF_8);
  }

  public static void writeVisualMatrixPng(String payload, Path outPng) throws IOException {
    if (outPng.getParent() != null) {
      Files.createDirectories(outPng.getParent());
    }
    BufferedImage img = encodeVisualMatrixImage(payload, 8);
    ImageIO.write(img, "PNG", outPng.toFile());
  }

  public static String readVisualMatrixPng(Path inPng) throws IOException {
    BufferedImage img = ImageIO.read(inPng.toFile());
    if (img == null) {
      throw new IOException("Could not decode PNG image: " + inPng);
    }
    return decodeVisualMatrixImage(img);
  }

  /**
   * Exports the self-contained, 100% offline-cached Mobile Match Stand Scouting PWA ({@code
   * scout.html} + {@code sw.js} Service Worker + {@code manifest.webmanifest} + {@code
   * enroll-qr.png}) into {@code outDir}.
   */
  public static Path exportOfflineScoutingPwa(Path outDir, String secretKeyHex) throws IOException {
    Files.createDirectories(outDir);
    String keyHex =
        (secretKeyHex == null || secretKeyHex.isBlank())
            ? DEFAULT_TEAM_SECRET_HEX
            : secretKeyHex.trim();

    Path scoutHtml = outDir.resolve("scout.html");
    Path swJs = outDir.resolve("sw.js");
    Path manifestJson = outDir.resolve("manifest.webmanifest");
    Path enrollPng = outDir.resolve("enroll-qr.png");

    Files.writeString(manifestJson, buildWebManifestJson(), StandardCharsets.UTF_8);
    Files.writeString(swJs, buildServiceWorkerJs(), StandardCharsets.UTF_8);
    Files.writeString(scoutHtml, buildScoutHtml(keyHex), StandardCharsets.UTF_8);

    String enrollUri = generateEnrollmentUri("https://frc-flux-robotics.github.io/scout.html", "scouter-1", keyHex);
    writeVisualMatrixPng(enrollUri, enrollPng);
    return scoutHtml;
  }

  public static String computeHmacHex16(String canonicalData, String secretKeyHex) {
    try {
      String key =
          (secretKeyHex == null || secretKeyHex.isBlank())
              ? DEFAULT_TEAM_SECRET_HEX
              : secretKeyHex.trim();
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(canonicalData.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(16);
      for (int i = 0; i < 8; i++) {
        sb.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xFF));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Failed to compute HmacSHA256", e);
    }
  }

  private static void drawFinderPattern(boolean[][] grid, int startR, int startC) {
    for (int r = 0; r < FINDER_SIZE; r++) {
      for (int c = 0; c < FINDER_SIZE; c++) {
        boolean border = (r == 0 || r == FINDER_SIZE - 1 || c == 0 || c == FINDER_SIZE - 1);
        boolean center = (r >= 2 && r <= 4 && c >= 2 && c <= 4);
        grid[startR + r][startC + c] = border || center;
      }
    }
  }

  private static boolean isReservedFinderZone(int r, int c) {
    if (r < FINDER_SIZE + 1 && c < FINDER_SIZE + 1) return true;
    if (r < FINDER_SIZE + 1 && c >= MATRIX_GRID_SIZE - FINDER_SIZE - 1) return true;
    if (r >= MATRIX_GRID_SIZE - FINDER_SIZE - 1 && c < FINDER_SIZE + 1) return true;
    return false;
  }

  private static boolean isDarkPixel(int rgb) {
    int r = (rgb >>> 16) & 0xFF;
    int g = (rgb >>> 8) & 0xFF;
    int b = rgb & 0xFF;
    return (r + g + b) < 384;
  }

  private static ScoutingMatchEntry parseCsvRow(String line) {
    List<String> cols = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        inQuotes = !inQuotes;
      } else if (ch == ',' && !inQuotes) {
        cols.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(ch);
      }
    }
    cols.add(cur.toString());
    return new ScoutingMatchEntry(
        cols.get(0),
        Integer.parseInt(cols.get(1)),
        cols.get(2),
        Integer.parseInt(cols.get(3)),
        cols.get(4),
        cols.get(5),
        Integer.parseInt(cols.get(6)),
        Integer.parseInt(cols.get(7)),
        Integer.parseInt(cols.get(8)),
        cols.get(9),
        Long.parseLong(cols.get(10)),
        Long.parseLong(cols.get(11)),
        cols.get(12),
        cols.get(13));
  }

  private static String buildWebManifestJson() {
    return """
        {
          "name": "FLUX 10413 Offline Match Stand Scouting (/scout)",
          "short_name": "FLUX Scout",
          "start_url": "./scout.html",
          "display": "standalone",
          "background_color": "#212020",
          "theme_color": "#254BA0"
        }
        """;
  }

  private static String buildServiceWorkerJs() {
    return """
        const CACHE_NAME = 'flux-10413-scout-v1';
        const ASSETS = ['./scout.html', './manifest.webmanifest'];
        self.addEventListener('install', event => {
          event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(ASSETS)));
          self.skipWaiting();
        });
        self.addEventListener('activate', event => {
          event.waitUntil(self.clients.claim());
        });
        self.addEventListener('fetch', event => {
          event.respondWith(
            caches.match(event.request).then(cached => cached || fetch(event.request))
          );
        });
        """;
  }

  private static String buildScoutHtml(String defaultTeamSecretHex) {
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <title>FLUX 10413 — /scout Offline Match Stand Scouting</title>
          <link rel="manifest" href="./manifest.webmanifest" />
          <style>
            :root {
              --black: #212020;
              --royal: #254BA0;
              --electric: #2B7BFF;
              --silver: #BFBFBF;
              --light: #F2F3F5;
              --white: #FFFFFF;
              --green: #16A34A;
            }
            * { box-sizing: border-box; font-family: 'Space Grotesk', system-ui, -apple-system, sans-serif; }
            body { margin: 0; background: var(--black); color: var(--white); padding: 14px; }
            .card { background: #2B2A2A; border: 1.5px solid var(--royal); border-radius: 12px; padding: 14px; margin-bottom: 12px; }
            h1 { font-size: 18px; margin: 0 0 4px 0; color: var(--white); letter-spacing: 0.03em; }
            .sub { font-size: 12px; color: var(--silver); margin-bottom: 10px; }
            .row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 8px; }
            label { font-size: 11.5px; font-weight: 700; color: var(--silver); text-transform: uppercase; display: block; margin-bottom: 4px; }
            input, select { width: 100%; padding: 9px 10px; border-radius: 8px; border: 1px solid #444; background: #181818; color: var(--white); font-size: 14px; font-weight: 600; }
            .tap-group { display: flex; gap: 6px; flex-wrap: wrap; }
            .tap-btn { flex: 1; min-width: 72px; padding: 10px 8px; border-radius: 8px; border: 1.5px solid #444; background: #181818; color: var(--white); font-weight: 700; font-size: 13px; cursor: pointer; text-align: center; }
            .tap-btn.active { background: var(--royal); border-color: var(--electric); }
            .counter { display: flex; align-items: center; gap: 10px; }
            .counter button { width: 44px; height: 42px; border-radius: 8px; border: none; background: var(--royal); color: var(--white); font-size: 20px; font-weight: 700; cursor: pointer; }
            .counter span { font-size: 22px; font-weight: 700; min-width: 38px; text-align: center; }
            .submit-btn { width: 100%; padding: 13px; border-radius: 10px; border: none; background: var(--electric); color: var(--white); font-size: 15px; font-weight: 700; cursor: pointer; margin-top: 6px; }
            #qr-box { text-align: center; display: none; }
            canvas { background: #FFF; padding: 8px; border-radius: 8px; max-width: 100%; height: auto; }
            .payload-box { font-family: ui-monospace, monospace; font-size: 11px; word-break: break-all; background: #141414; padding: 8px; border-radius: 6px; margin-top: 8px; color: #A7F3D0; }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>FLUX 10413 · /scout STAND SCOUTING</h1>
            <div class="sub" id="status-bar">100% Offline PWA · E301/G303 Safe · Signed QR Sync</div>
            <div class="row">
              <div><label>Event Code</label><input id="eventCode" value="2027NYTR" /></div>
              <div><label>Match #</label><input id="matchNumber" type="number" value="1" min="1" /></div>
            </div>
            <div class="row">
              <div>
                <label>Alliance Station</label>
                <select id="allianceStation">
                  <option>Red 1</option><option>Red 2</option><option>Red 3</option>
                  <option>Blue 1</option><option>Blue 2</option><option selected>Blue 3</option>
                </select>
              </div>
              <div><label>Team #</label><input id="teamNumber" type="number" value="10413" min="1" /></div>
            </div>
          </div>

          <div class="card">
            <label>1. Auto Starting Zone</label>
            <div class="tap-group" id="autoZoneGroup">
              <button type="button" class="tap-btn" onclick="setChoice('autoZone','LEFT',this)">LEFT</button>
              <button type="button" class="tap-btn active" onclick="setChoice('autoZone','CENTER',this)">CENTER</button>
              <button type="button" class="tap-btn" onclick="setChoice('autoZone','RIGHT',this)">RIGHT</button>
            </div>
            <div class="row" style="margin-top:10px;">
              <div>
                <label>2. Auto Pieces Scored</label>
                <div class="counter">
                  <button type="button" onclick="stepVal('autoScored',-1)">-</button>
                  <span id="autoScored">2</span>
                  <button type="button" onclick="stepVal('autoScored',1)">+</button>
                </div>
              </div>
              <div>
                <label>3. Teleop Cycles</label>
                <div class="counter">
                  <button type="button" onclick="stepVal('teleopCycles',-1)">-</button>
                  <span id="teleopCycles">8</span>
                  <button type="button" onclick="stepVal('teleopCycles',1)">+</button>
                </div>
              </div>
            </div>
            <label style="margin-top:10px;">4. Defense Rating (0 = None, 5 = Lockdown)</label>
            <div class="tap-group" id="defGroup">
              <button type="button" class="tap-btn active" onclick="setChoice('defense','0',this)">0</button>
              <button type="button" class="tap-btn" onclick="setChoice('defense','1',this)">1</button>
              <button type="button" class="tap-btn" onclick="setChoice('defense','2',this)">2</button>
              <button type="button" class="tap-btn" onclick="setChoice('defense','3',this)">3</button>
              <button type="button" class="tap-btn" onclick="setChoice('defense','4',this)">4</button>
              <button type="button" class="tap-btn" onclick="setChoice('defense','5',this)">5</button>
            </div>
            <label style="margin-top:10px;">5. Endgame Status</label>
            <div class="tap-group" id="endgameGroup">
              <button type="button" class="tap-btn" onclick="setChoice('endgame','NONE',this)">NONE</button>
              <button type="button" class="tap-btn" onclick="setChoice('endgame','PARK',this)">PARK</button>
              <button type="button" class="tap-btn" onclick="setChoice('endgame','SHALLOW_CLIMB',this)">SHALLOW</button>
              <button type="button" class="tap-btn active" onclick="setChoice('endgame','DEEP_CLIMB',this)">DEEP</button>
            </div>
            <label style="margin-top:10px;">Last-Second Queue / Partner Notes</label>
            <input id="queueNotes" placeholder="e.g. Needs center lane in Auto, fast feeder..." />
            <button type="button" class="submit-btn" onclick="generateSignedQr()">GENERATE SIGNED MATCH QR</button>
          </div>

          <div class="card" id="qr-box">
            <label>Scan with Pit / Driver Station Laptop Webcam (FluxTray)</label>
            <canvas id="qr-canvas" width="360" height="360"></canvas>
            <div class="payload-box" id="qr-payload"></div>
          </div>

          <script>
            if ('serviceWorker' in navigator) {
              navigator.serviceWorker.register('./sw.js').catch(() => {});
            }
            const DEFAULT_KEY = "__DEFAULT_TEAM_SECRET_HEX__";
            const state = { autoZone: 'CENTER', defense: '0', endgame: 'DEEP_CLIMB' };

            (function checkEnrollHash() {
              if (location.hash.startsWith('#enroll?')) {
                const params = new URLSearchParams(location.hash.slice(8));
                if (params.get('student')) localStorage.setItem('flux_scouter_id', params.get('student'));
                if (params.get('key')) localStorage.setItem('flux_team_key', params.get('key'));
                history.replaceState(null, '', location.pathname + location.search);
                document.getElementById('status-bar').textContent =
                  'Enrolled: ' + (localStorage.getItem('flux_scouter_id') || 'scouter-1') + ' (Key Provisioned)';
              }
            })();

            function setChoice(key, val, btn) {
              state[key] = val;
              btn.parentElement.querySelectorAll('.tap-btn').forEach(b => b.classList.remove('active'));
              btn.classList.add('active');
            }
            function stepVal(id, delta) {
              const el = document.getElementById(id);
              el.textContent = Math.max(0, parseInt(el.textContent || '0', 10) + delta);
            }
            function b64Url(str) {
              return btoa(unescape(encodeURIComponent(str))).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');
            }
            async function hmacHex16(canonical, keyStr) {
              const enc = new TextEncoder();
              const cryptoKey = await crypto.subtle.importKey(
                'raw', enc.encode(keyStr), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
              );
              const sig = new Uint8Array(await crypto.subtle.sign('HMAC', cryptoKey, enc.encode(canonical)));
              return Array.from(sig.slice(0, 8)).map(b => b.toString(16).padStart(2, '0')).join('');
            }
            async function generateSignedQr() {
              const seq = (parseInt(localStorage.getItem('flux_seq') || '0', 10) + 1);
              localStorage.setItem('flux_seq', String(seq));
              const scouter = localStorage.getItem('flux_scouter_id') || 'scouter-1';
              const key = localStorage.getItem('flux_team_key') || DEFAULT_KEY;
              const ts = Math.floor(Date.now() / 1000);
              const notesB64 = b64Url(document.getElementById('queueNotes').value.trim());
              const canonical = [
                'FLUXSCOUT1',
                document.getElementById('eventCode').value.trim(),
                document.getElementById('matchNumber').value.trim(),
                document.getElementById('allianceStation').value.trim(),
                document.getElementById('teamNumber').value.trim(),
                scouter,
                state.autoZone,
                document.getElementById('autoScored').textContent.trim(),
                document.getElementById('teleopCycles').textContent.trim(),
                state.defense,
                state.endgame,
                String(seq),
                String(ts),
                notesB64
              ].join('|');
              const hmac = await hmacHex16(canonical, key);
              const fullPayload = canonical + '|' + hmac;
              document.getElementById('qr-payload').textContent = fullPayload;
              document.getElementById('qr-box').style.display = 'block';
              drawFinderMatrix(fullPayload);
            }
            function crc32Bytes(arr) {
              let c = 0xFFFFFFFF;
              for (let i = 0; i < arr.length; i++) {
                c ^= arr[i];
                for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xEDB88320 & -(c & 1));
              }
              return (c ^ 0xFFFFFFFF) >>> 0;
            }
            function drawFinderMatrix(payload) {
              const canvas = document.getElementById('qr-canvas');
              const ctx = canvas.getContext('2d');
              const N = 45, F = 7, Q = 4;
              const mod = Math.floor(canvas.width / (N + Q * 2));
              ctx.fillStyle = '#FFFFFF';
              ctx.fillRect(0, 0, canvas.width, canvas.height);
              ctx.fillStyle = '#000000';
              const data = new TextEncoder().encode(payload);
              const crc = crc32Bytes(data);
              const packet = new Uint8Array(6 + data.length);
              packet[0] = (data.length >>> 8) & 0xFF;
              packet[1] = data.length & 0xFF;
              packet[2] = (crc >>> 24) & 0xFF;
              packet[3] = (crc >>> 16) & 0xFF;
              packet[4] = (crc >>> 8) & 0xFF;
              packet[5] = crc & 0xFF;
              packet.set(data, 6);
              function isFinder(r, c) {
                return (r < F + 1 && c < F + 1) || (r < F + 1 && c >= N - F - 1) || (r >= N - F - 1 && c < F + 1);
              }
              function paintFinder(sr, sc) {
                for (let r = 0; r < F; r++) for (let c = 0; c < F; c++) {
                  if (r === 0 || r === F - 1 || c === 0 || c === F - 1 || (r >= 2 && r <= 4 && c >= 2 && c <= 4)) {
                    ctx.fillRect((sc + c + Q) * mod, (sr + r + Q) * mod, mod, mod);
                  }
                }
              }
              paintFinder(0, 0); paintFinder(N - F, 0); paintFinder(0, N - F);
              let bitIdx = 0;
              for (let r = 0; r < N; r++) for (let c = 0; c < N; c++) {
                if (isFinder(r, c)) continue;
                const bit = bitIdx < packet.length * 8
                  ? ((packet[bitIdx >> 3] >> (7 - (bitIdx & 7))) & 1) === 1
                  : ((r + c) & 1) === 0;
                bitIdx++;
                if (bit) ctx.fillRect((c + Q) * mod, (r + Q) * mod, mod, mod);
              }
            }
          </script>
        </body>
        </html>
        """
        .replace("__DEFAULT_TEAM_SECRET_HEX__", defaultTeamSecretHex);
  }
}
