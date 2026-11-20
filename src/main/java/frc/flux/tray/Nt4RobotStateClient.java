package frc.flux.tray;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java JDK 17 NetworkTables 4 WebSocket client ({@code ws://<host>:5810/nt/FluxTray}) that
 * subscribes to {@code /FMSInfo/FMSControlData}, decodes NT4 MessagePack binary frames, and feeds
 * live {@code Enabled}/{@code FMS} bits directly into {@link RobotSafetyLockout}.
 */
public final class Nt4RobotStateClient implements Closeable {

  public static final int DEFAULT_NT4_PORT = 5810;
  public static final long DEFAULT_PING_INTERVAL_MS = 2_000L;
  public static final long DEFAULT_IDLE_TIMEOUT_MS = 5_000L;
  public static final long DEFAULT_RECONNECT_BACKOFF_MS = 1_000L;
  public static final String FMS_CONTROL_DATA_TOPIC = "/FMSInfo/FMSControlData";
  public static final int FMS_CONTROL_ENABLED_BIT = 0x01;
  public static final int FMS_CONTROL_AUTONOMOUS_BIT = 0x02;
  public static final int FMS_CONTROL_TEST_BIT = 0x04;
  public static final int FMS_CONTROL_ESTOP_BIT = 0x08;
  public static final int FMS_CONTROL_FMS_ATTACHED_BIT = 0x10;
  public static final int FMS_CONTROL_DS_ATTACHED_BIT = 0x20;

  private static final Pattern JSON_INT_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+)");
  private static final Pattern JSON_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern JSON_METHOD_PATTERN =
      Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

  @FunctionalInterface
  public interface RobotStateListener {
    void onRobotState(
        boolean enabled, boolean fmsAttached, int controlWord, String connectedHost, long nowMs);
  }

  public record Nt4Frame(int topicId, long timestampUs, int typeId, Object value) {}

  private final List<String> candidateHosts;
  private final int port;
  private final RobotSafetyLockout lockout;
  private final RobotStateListener listener;
  private final LongSupplier clockMs;
  private final long pingIntervalMs;
  private final long idleTimeoutMs;
  private final long reconnectBackoffMs;
  private final HttpClient httpClient;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private final AtomicReference<String> connectedHost = new AtomicReference<>(null);
  private final AtomicInteger lastControlWord = new AtomicInteger(-1);
  private final AtomicReference<WebSocket> activeSocket = new AtomicReference<>(null);
  private final AtomicInteger fmsControlTopicId = new AtomicInteger(-1);
  private final AtomicLong lastActivityNano = new AtomicLong(0L);
  private final Map<Integer, String> announcedTopics = new ConcurrentHashMap<>();
  private volatile Thread workerThread;
  private volatile Runnable onConnectionChange;

  public Nt4RobotStateClient(
      List<String> candidateHosts,
      int port,
      RobotSafetyLockout lockout,
      RobotStateListener listener) {
    this(candidateHosts, port, lockout, listener, System::currentTimeMillis);
  }

  public Nt4RobotStateClient(
      List<String> candidateHosts,
      int port,
      RobotSafetyLockout lockout,
      RobotStateListener listener,
      LongSupplier clockMs) {
    this(
        candidateHosts,
        port,
        lockout,
        listener,
        clockMs,
        DEFAULT_PING_INTERVAL_MS,
        DEFAULT_IDLE_TIMEOUT_MS,
        DEFAULT_RECONNECT_BACKOFF_MS);
  }

  public Nt4RobotStateClient(
      List<String> candidateHosts,
      int port,
      RobotSafetyLockout lockout,
      RobotStateListener listener,
      LongSupplier clockMs,
      long pingIntervalMs,
      long idleTimeoutMs,
      long reconnectBackoffMs) {
    this.candidateHosts =
        (candidateHosts == null || candidateHosts.isEmpty())
            ? SshRobotTransport.ROBORIO_CANDIDATE_HOSTS
            : List.copyOf(candidateHosts);
    this.port = port > 0 ? port : DEFAULT_NT4_PORT;
    this.lockout = lockout;
    this.listener = listener;
    this.clockMs = clockMs != null ? clockMs : System::currentTimeMillis;
    this.pingIntervalMs = Math.max(50L, pingIntervalMs);
    this.idleTimeoutMs = Math.max(this.pingIntervalMs + 50L, idleTimeoutMs);
    this.reconnectBackoffMs = Math.max(25L, reconnectBackoffMs);
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  public void setConnectionChangeListener(Runnable onConnectionChange) {
    this.onConnectionChange = onConnectionChange;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    Thread t = new Thread(this::connectionLoop, "fluxtray-nt4-client");
    t.setDaemon(true);
    workerThread = t;
    t.start();
  }

  public boolean isConnected() {
    return connected.get();
  }

  public String connectedHost() {
    return connectedHost.get();
  }

  public int lastControlWord() {
    return lastControlWord.get();
  }

  @Override
  public void close() {
    running.set(false);
    WebSocket ws = activeSocket.getAndSet(null);
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "FluxTray shutdown").get(1, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        ws.abort();
      }
    }
    connected.set(false);
    connectedHost.set(null);
    Thread t = workerThread;
    if (t != null) {
      t.interrupt();
    }
  }

  private void connectionLoop() {
    int hostIndex = 0;
    while (running.get()) {
      String host = candidateHosts.get(hostIndex % candidateHosts.size());
      hostIndex++;
      CountDownLatch closedLatch = new CountDownLatch(1);
      try {
        fmsControlTopicId.set(-1);
        announcedTopics.clear();
        URI uri =
            URI.create("ws://" + host + ":" + port + "/nt/FluxTray-" + ProcessHandle.current().pid());
        WsListener wsListener = new WsListener(host, closedLatch);
        WebSocket ws =
            httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .subprotocols("v4.1.networktables.first.wpi.edu", "networktables.first.wpi.edu")
                .buildAsync(uri, wsListener)
                .get(3, TimeUnit.SECONDS);
        activeSocket.set(ws);
        connected.set(true);
        connectedHost.set(host);
        notifyConnectionChanged();

        String subscribeJson =
            "[{\"method\":\"subscribe\",\"params\":{\"topics\":[\""
                + FMS_CONTROL_DATA_TOPIC
                + "\"],\"subuid\":1,\"options\":{\"periodic\":0.05,\"all\":false,\"topicsonly\":false,\"prefix\":false}}}]";
        ws.sendText(subscribeJson, true).get(2, TimeUnit.SECONDS);

        long lastPingNano = System.nanoTime();
        lastActivityNano.set(lastPingNano);
        long pollSliceMs = Math.max(25L, Math.min(250L, pingIntervalMs / 2L));
        long pingSendTimeoutMs = Math.min(2_000L, Math.max(250L, idleTimeoutMs));
        while (running.get() && !closedLatch.await(pollSliceMs, TimeUnit.MILLISECONDS)) {
          long nowNano = System.nanoTime();
          long idleMs = TimeUnit.NANOSECONDS.toMillis(nowNano - lastActivityNano.get());
          if (idleMs > idleTimeoutMs) {
            ws.abort();
            break;
          }
          long sincePingMs = TimeUnit.NANOSECONDS.toMillis(nowNano - lastPingNano);
          if (sincePingMs >= pingIntervalMs) {
            lastPingNano = nowNano;
            ByteBuffer pingPayload = ByteBuffer.allocate(8).putLong(nowNano).flip();
            try {
              ws.sendPing(pingPayload).get(pingSendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception pingEx) {
              ws.abort();
              break;
            }
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception ignored) {
        // Host unavailable or NT4 server restarting; rotate to next candidate after backoff.
      } finally {
        WebSocket old = activeSocket.getAndSet(null);
        if (old != null) {
          old.abort();
        }
        boolean wasConnected = connected.getAndSet(false);
        connectedHost.set(null);
        if (wasConnected) {
          notifyConnectionChanged();
        }
      }

      if (running.get()) {
        try {
          Thread.sleep(reconnectBackoffMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  private void notifyConnectionChanged() {
    Runnable cb = onConnectionChange;
    if (cb != null) {
      try {
        cb.run();
      } catch (Exception ignored) {
      }
    }
  }

  private final class WsListener implements WebSocket.Listener {
    private final String host;
    private final CountDownLatch closedLatch;
    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

    private WsListener(String host, CountDownLatch closedLatch) {
      this.host = host;
      this.closedLatch = closedLatch;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      lastActivityNano.set(System.nanoTime());
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      lastActivityNano.set(System.nanoTime());
      textBuffer.append(data);
      if (last) {
        String msg = textBuffer.toString();
        textBuffer.setLength(0);
        handleJsonControlMessage(msg);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      lastActivityNano.set(System.nanoTime());
      byte[] chunk = new byte[data.remaining()];
      data.get(chunk);
      binaryBuffer.write(chunk, 0, chunk.length);
      if (last) {
        byte[] payload = binaryBuffer.toByteArray();
        binaryBuffer.reset();
        handleBinaryMessage(payload, host);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
      lastActivityNano.set(System.nanoTime());
      return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
      lastActivityNano.set(System.nanoTime());
      return WebSocket.Listener.super.onPong(webSocket, message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closedLatch.countDown();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      closedLatch.countDown();
    }
  }

  void handleJsonControlMessage(String json) {
    if (json == null || json.isBlank()) {
      return;
    }
    for (String obj : splitTopLevelJsonObjects(json)) {
      Matcher methodMatcher = JSON_METHOD_PATTERN.matcher(obj);
      if (!methodMatcher.find()) {
        continue;
      }
      String method = methodMatcher.group(1);
      Matcher idMatcher = JSON_INT_ID_PATTERN.matcher(obj);
      if (!idMatcher.find()) {
        continue;
      }
      int id = Integer.parseInt(idMatcher.group(1));
      if ("announce".equals(method)) {
        Matcher nameMatcher = JSON_NAME_PATTERN.matcher(obj);
        if (nameMatcher.find()) {
          String name = nameMatcher.group(1);
          announcedTopics.put(id, name);
          if (FMS_CONTROL_DATA_TOPIC.equals(name) || "FMSInfo/FMSControlData".equals(name)) {
            fmsControlTopicId.set(id);
          }
        }
      } else if ("unannounce".equals(method)) {
        announcedTopics.remove(id);
        if (fmsControlTopicId.get() == id) {
          fmsControlTopicId.set(-1);
        }
      }
    }
  }

  void handleBinaryMessage(byte[] payload, String host) {
    int targetId = fmsControlTopicId.get();
    if (targetId < 0) {
      return;
    }
    List<Nt4Frame> frames;
    try {
      frames = decodeMsgPackFrames(payload);
    } catch (IllegalArgumentException truncatedOrMalformed) {
      // Fail closed: discard truncated/corrupt MsgPack packets so EOF never flips ENABLED to DISABLED.
      return;
    }
    for (Nt4Frame frame : frames) {
      if (frame.topicId() == targetId && frame.value() instanceof Number n) {
        int controlWord = n.intValue();
        lastControlWord.set(controlWord);
        boolean isEnabled = (controlWord & FMS_CONTROL_ENABLED_BIT) != 0;
        boolean isFms = (controlWord & FMS_CONTROL_FMS_ATTACHED_BIT) != 0;
        long nowMs = clockMs.getAsLong();
        if (lockout != null) {
          lockout.updateState(isEnabled, isFms, nowMs);
        }
        if (listener != null) {
          listener.onRobotState(isEnabled, isFms, controlWord, host, nowMs);
        }
      }
    }
  }

  /** Decodes one or more concatenated NT4 MessagePack 4-element value arrays. */
  public static List<Nt4Frame> decodeMsgPackFrames(byte[] bytes) {
    List<Nt4Frame> frames = new ArrayList<>();
    if (bytes == null || bytes.length == 0) {
      return frames;
    }
    MsgPackCursor cursor = new MsgPackCursor(bytes);
    while (cursor.hasRemaining()) {
      Object item = cursor.readValue();
      if (item instanceof List<?> arr && arr.size() >= 4) {
        if (arr.get(0) instanceof Number idNum
            && arr.get(1) instanceof Number tsNum
            && arr.get(2) instanceof Number typeNum) {
          frames.add(
              new Nt4Frame(idNum.intValue(), tsNum.longValue(), typeNum.intValue(), arr.get(3)));
        }
      }
    }
    return frames;
  }

  private static List<String> splitTopLevelJsonObjects(String json) {
    List<String> objects = new ArrayList<>();
    int depth = 0;
    int start = -1;
    boolean inQuotes = false;
    boolean escaped = false;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\' && inQuotes) {
        escaped = true;
        continue;
      }
      if (c == '"') {
        inQuotes = !inQuotes;
        continue;
      }
      if (inQuotes) {
        continue;
      }
      if (c == '{') {
        if (depth == 0) {
          start = i;
        }
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0 && start >= 0) {
          objects.add(json.substring(start, i + 1));
          start = -1;
        }
      }
    }
    return objects;
  }

  private static final class MsgPackCursor {
    private final byte[] buf;
    private int pos = 0;

    private MsgPackCursor(byte[] buf) {
      this.buf = buf;
    }

    boolean hasRemaining() {
      return pos < buf.length;
    }

    Object readValue() {
      if (pos >= buf.length) {
        throw new IllegalArgumentException("Unexpected EOF reading NT4 MsgPack value");
      }
      int b = buf[pos++] & 0xFF;
      if (b <= 0x7F) {
        return (long) b;
      }
      if (b >= 0xE0) {
        return (long) (byte) b;
      }
      if ((b & 0xF0) == 0x90) {
        return readArray(b & 0x0F);
      }
      if ((b & 0xF0) == 0x80) {
        skipMap(b & 0x0F);
        return null;
      }
      if ((b & 0xE0) == 0xA0) {
        return readString(b & 0x1F);
      }
      return switch (b) {
        case 0xC0 -> null;
        case 0xC2 -> Boolean.FALSE;
        case 0xC3 -> Boolean.TRUE;
        case 0xC4 -> readBytes(readU8());
        case 0xC5 -> readBytes(readU16());
        case 0xC6 -> readBytes(readLen32());
        case 0xC7 -> {
          int len = readU8();
          skip(1 + len);
          yield null;
        }
        case 0xC8 -> {
          int len = readU16();
          skip(1 + len);
          yield null;
        }
        case 0xC9 -> {
          int len = readLen32();
          skip(1 + len);
          yield null;
        }
        case 0xCA -> (double) Float.intBitsToFloat(readI32());
        case 0xCB -> Double.longBitsToDouble(readI64());
        case 0xCC -> (long) readU8();
        case 0xCD -> (long) readU16();
        case 0xCE -> readU32();
        case 0xCF -> readI64();
        case 0xD0 -> (long) (byte) readU8();
        case 0xD1 -> (long) (short) readU16();
        case 0xD2 -> (long) readI32();
        case 0xD3 -> readI64();
        case 0xD4 -> {
          skip(2);
          yield null;
        }
        case 0xD5 -> {
          skip(3);
          yield null;
        }
        case 0xD6 -> {
          skip(5);
          yield null;
        }
        case 0xD7 -> {
          skip(9);
          yield null;
        }
        case 0xD8 -> {
          skip(17);
          yield null;
        }
        case 0xD9 -> readString(readU8());
        case 0xDA -> readString(readU16());
        case 0xDB -> readString(readLen32());
        case 0xDC -> readArray(readU16());
        case 0xDD -> readArray(readLen32());
        case 0xDE -> {
          skipMap(readU16());
          yield null;
        }
        case 0xDF -> {
          skipMap(readLen32());
          yield null;
        }
        default ->
            throw new IllegalArgumentException(
                "Unsupported NT4 MsgPack format byte: 0x" + Integer.toHexString(b));
      };
    }

    private List<Object> readArray(int len) {
      if (len < 0) {
        throw new IllegalArgumentException("Negative MsgPack array length: " + len);
      }
      int safeCapacity = Math.min(len, buf.length - pos);
      List<Object> list = new ArrayList<>(safeCapacity);
      for (int i = 0; i < len; i++) {
        list.add(readValue());
      }
      return list;
    }

    private void skipMap(int pairs) {
      if (pairs < 0) {
        throw new IllegalArgumentException("Negative MsgPack map length: " + pairs);
      }
      for (int i = 0; i < pairs; i++) {
        readValue();
        readValue();
      }
    }

    private String readString(int len) {
      byte[] raw = readBytes(len);
      return new String(raw, StandardCharsets.UTF_8);
    }

    private byte[] readBytes(int len) {
      if (len < 0 || len > buf.length - pos) {
        throw new IllegalArgumentException(
            "Unexpected EOF reading " + len + " MsgPack bytes (remaining=" + (buf.length - pos) + ")");
      }
      byte[] out = new byte[len];
      System.arraycopy(buf, pos, out, 0, len);
      pos += len;
      return out;
    }

    private void skip(int len) {
      if (len < 0 || len > buf.length - pos) {
        throw new IllegalArgumentException(
            "Unexpected EOF skipping " + len + " MsgPack bytes (remaining=" + (buf.length - pos) + ")");
      }
      pos += len;
    }

    private int readLen32() {
      long raw = readU32();
      if (raw > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("MsgPack length overflow: " + raw);
      }
      return (int) raw;
    }

    private int readU8() {
      if (pos >= buf.length) {
        throw new IllegalArgumentException("Unexpected EOF in MsgPackCursor.readU8()");
      }
      return buf[pos++] & 0xFF;
    }

    private int readU16() {
      return (readU8() << 8) | readU8();
    }

    private int readI32() {
      return (readU8() << 24) | (readU8() << 16) | (readU8() << 8) | readU8();
    }

    private long readU32() {
      return Integer.toUnsignedLong(readI32());
    }

    private long readI64() {
      long hi = Integer.toUnsignedLong(readI32());
      long lo = Integer.toUnsignedLong(readI32());
      return (hi << 32) | lo;
    }
  }
}
