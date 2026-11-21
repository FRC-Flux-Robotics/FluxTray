package frc.flux.tray;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Fail-closed GitHub Releases self-updater for {@code FluxTray.jar}.
 *
 * <p>Checks {@code /repos/<owner>/<repo>/releases/latest}, verifies both {@code FluxTray.jar} and
 * its {@code FluxTray.jar.sha256} checksum asset, confirms the staged archive is a valid JAR
 * containing {@code FluxTrayMain.class} that passes {@code --self-test}, backs up the running JAR
 * to {@code FluxTray.jar.bak}, and spawns an OS-specific swap-and-relaunch helper so Windows NTFS
 * file locks on the running JAR are cleanly released before replacement.
 */
public final class GitHubReleaseUpdater {

  public static final String DEFAULT_REPO = "FRC-Flux-Robotics/FluxTray";
  public static final String DEFAULT_API_BASE_URL = "https://api.github.com";
  public static final String JAR_ASSET_NAME = "FluxTray.jar";
  public static final String SHA256_ASSET_NAME = "FluxTray.jar.sha256";
  public static final String FALLBACK_VERSION = "2027.1.0";
  public static final long DEFAULT_CHECK_INTERVAL_MINUTES = 30L;
  public static final long DEFAULT_INITIAL_DELAY_SECONDS = 45L;

  private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");
  private static final int MAX_HTTP_REDIRECTS = 5;

  private static final HttpClient SHARED_HTTP_CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .executor(
              Executors.newCachedThreadPool(
                  r -> {
                    Thread t = new Thread(r, "fluxtray-http");
                    t.setDaemon(true);
                    return t;
                  }))
          .build();

  /** Permanent release-artifact defect (corrupt checksum/JAR or failed self-test) that should latch. */
  private static final class ReleaseIntegrityException extends IOException {
    ReleaseIntegrityException(String message) {
      super(message);
    }

    ReleaseIntegrityException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Parsed metadata for a published GitHub Release carrying {@code FluxTray.jar} + SHA-256. */
  public record ReleaseInfo(
      String tagName,
      String version,
      boolean draft,
      boolean prerelease,
      String jarDownloadUrl,
      String sha256DownloadUrl) {}

  /** Result of one update check or staged self-update attempt. */
  public record UpdateOutcome(
      boolean updateAvailable,
      boolean stagedForRestart,
      String currentVersion,
      String latestVersion,
      Path stagedJar,
      String message) {}

  /** Port for HTTP GET requests (backed by JDK 17 {@link HttpClient} in production). */
  @FunctionalInterface
  public interface HttpGetPort {
    HttpResult get(String url, String acceptHeader, String authToken, Duration timeout)
        throws IOException;
  }

  /** Captured HTTP response status and body bytes. */
  public record HttpResult(int statusCode, byte[] body) {
    public String bodyText() {
      return body != null ? new String(body, StandardCharsets.UTF_8) : "";
    }
  }

  /** Port verifying a staged JAR boots and passes {@code --self-test} before swap. */
  @FunctionalInterface
  public interface SelfTestPort {
    boolean verifyStagedJar(Path javaExecutable, Path stagedJar) throws IOException;
  }

  /** Port launching the detached swap-and-relaunch helper after the running JVM exits. */
  @FunctionalInterface
  public interface HandoffLauncherPort {
    void launchSwapAndRelaunch(
        long currentPid,
        Path targetJar,
        Path stagedJar,
        Path backupJar,
        Path relaunchJavaExecutable,
        List<String> relaunchArgs)
        throws IOException;
  }

  private final String repoSlug;
  private final String apiBaseUrl;
  private final String currentVersion;
  private final String authToken;
  private final HttpGetPort httpGet;
  private final SelfTestPort selfTest;
  private final HandoffLauncherPort handoffLauncher;

  private volatile String lastFailedTag = null;

  public GitHubReleaseUpdater() {
    this(
        resolveDefaultRepo(),
        DEFAULT_API_BASE_URL,
        detectCurrentVersion(),
        System.getenv("FLUXTRAY_GITHUB_TOKEN"),
        null,
        null,
        null);
  }

  public GitHubReleaseUpdater(String repoSlug, String currentVersion) {
    this(
        repoSlug,
        DEFAULT_API_BASE_URL,
        currentVersion,
        System.getenv("FLUXTRAY_GITHUB_TOKEN"),
        null,
        null,
        null);
  }

  GitHubReleaseUpdater(
      String repoSlug,
      String apiBaseUrl,
      String currentVersion,
      String authToken,
      HttpGetPort httpGet,
      SelfTestPort selfTest,
      HandoffLauncherPort handoffLauncher) {
    this.repoSlug =
        (repoSlug != null && !repoSlug.isBlank()) ? repoSlug.trim() : DEFAULT_REPO;
    String base =
        (apiBaseUrl != null && !apiBaseUrl.isBlank())
            ? apiBaseUrl.trim()
            : DEFAULT_API_BASE_URL;
    this.apiBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.currentVersion =
        (currentVersion != null && !currentVersion.isBlank())
            ? normalizeVersion(currentVersion)
            : FALLBACK_VERSION;
    this.authToken = (authToken != null && !authToken.isBlank()) ? authToken.trim() : null;
    this.httpGet = httpGet != null ? httpGet : GitHubReleaseUpdater::jdkHttpGet;
    this.selfTest = selfTest != null ? selfTest : GitHubReleaseUpdater::runJarSelfTest;
    this.handoffLauncher =
        handoffLauncher != null ? handoffLauncher : GitHubReleaseUpdater::spawnPlatformSwapHelper;
  }

  public static String resolveDefaultRepo() {
    String prop = System.getProperty("flux.update.repo");
    if (prop != null && !prop.isBlank()) {
      return prop.trim();
    }
    return DEFAULT_REPO;
  }

  /** Returns the running {@code FluxTray.jar} manifest {@code Implementation-Version}. */
  public static String detectCurrentVersion() {
    String prop = System.getProperty("flux.version");
    if (prop != null && !prop.isBlank()) {
      return normalizeVersion(prop);
    }
    Package pkg = FluxTrayMain.class.getPackage();
    if (pkg != null && pkg.getImplementationVersion() != null && !pkg.getImplementationVersion().isBlank()) {
      return normalizeVersion(pkg.getImplementationVersion());
    }
    return FALLBACK_VERSION;
  }

  /** Resolves the live {@code .jar} path when launched via {@code java -jar}, or empty if exploded. */
  public static Optional<Path> resolveRunningJarPath() {
    try {
      CodeSource cs = FluxTrayMain.class.getProtectionDomain().getCodeSource();
      if (cs == null || cs.getLocation() == null) {
        return Optional.empty();
      }
      Path p = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
      if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
        return Optional.of(p);
      }
    } catch (URISyntaxException | RuntimeException ignored) {
    }
    return Optional.empty();
  }

  public String currentVersion() {
    return currentVersion;
  }

  public String repoSlug() {
    return repoSlug;
  }

  public String lastFailedTag() {
    return lastFailedTag;
  }

  /** Queries GitHub Releases for the latest stable release metadata. */
  public Optional<ReleaseInfo> fetchLatestRelease() throws IOException {
    String url = apiBaseUrl + "/repos/" + repoSlug + "/releases/latest";
    HttpResult res =
        httpGet.get(url, "application/vnd.github+json", authToken, Duration.ofSeconds(15));
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      throw new IOException(
          "GitHub Releases query failed (HTTP " + res.statusCode() + ") for " + url);
    }
    return parseLatestReleaseJson(res.bodyText());
  }

  /** Checks whether a newer stable GitHub Release is available without downloading it. */
  public UpdateOutcome checkForUpdate() throws IOException {
    Optional<ReleaseInfo> latestOpt = fetchLatestRelease();
    if (latestOpt.isEmpty()) {
      return new UpdateOutcome(
          false, false, currentVersion, currentVersion, null, "No published GitHub Release found.");
    }
    ReleaseInfo latest = latestOpt.get();
    if (latest.draft() || latest.prerelease()) {
      return new UpdateOutcome(
          false,
          false,
          currentVersion,
          latest.version(),
          null,
          "Skipping non-stable release " + latest.tagName());
    }
    if (!isNewerVersion(currentVersion, latest.version())) {
      return new UpdateOutcome(
          false,
          false,
          currentVersion,
          latest.version(),
          null,
          "FluxTray " + currentVersion + " is up to date (latest: " + latest.version() + ").");
    }
    return new UpdateOutcome(
        true,
        false,
        currentVersion,
        latest.version(),
        null,
        "Update available: " + currentVersion + " -> " + latest.version() + " (" + latest.tagName() + ")");
  }

  /**
   * Checks GitHub Releases, downloads and verifies {@code FluxTray.jar} against {@code
   * FluxTray.jar.sha256}, runs {@code --self-test} on the staged JAR, backs up {@code targetJar},
   * and launches the detached swap-and-relaunch helper.
   *
   * <p>The caller MUST verify robot lockout (Disabled, off-FMS, not recording audio, not syncing)
   * before calling this method and exit the JVM only when {@link UpdateOutcome#stagedForRestart()}
   * is {@code true}.
   */
  public UpdateOutcome checkAndStageUpdate(
      Path targetJar,
      List<String> relaunchArgs,
      boolean preferJavaw,
      java.util.function.BooleanSupplier safeToRestartCheck)
      throws IOException {
    if (targetJar == null || !Files.isRegularFile(targetJar)) {
      return new UpdateOutcome(
          false,
          false,
          currentVersion,
          currentVersion,
          null,
          "Not running from a packaged FluxTray.jar; skipping auto-update.");
    }
    Optional<ReleaseInfo> latestOpt = fetchLatestRelease();
    if (latestOpt.isEmpty()) {
      return new UpdateOutcome(
          false, false, currentVersion, currentVersion, null, "No published GitHub Release found.");
    }
    ReleaseInfo latest = latestOpt.get();
    if (latest.draft() || latest.prerelease()) {
      return new UpdateOutcome(
          false,
          false,
          currentVersion,
          latest.version(),
          null,
          "Skipping draft/prerelease " + latest.tagName());
    }
    if (!isNewerVersion(currentVersion, latest.version())) {
      return new UpdateOutcome(
          false,
          false,
          currentVersion,
          latest.version(),
          null,
          "FluxTray " + currentVersion + " is already up to date.");
    }
    if (latest.tagName().equals(lastFailedTag)) {
      return new UpdateOutcome(
          true,
          false,
          currentVersion,
          latest.version(),
          null,
          "Skipping previously failed release " + latest.tagName() + " until restart.");
    }
    if (latest.jarDownloadUrl() == null
        || latest.jarDownloadUrl().isBlank()
        || latest.sha256DownloadUrl() == null
        || latest.sha256DownloadUrl().isBlank()) {
      lastFailedTag = latest.tagName();
      throw new IOException(
          "Refusing unsigned/unverified release "
              + latest.tagName()
              + ": missing FluxTray.jar or FluxTray.jar.sha256 asset.");
    }

    Path absTarget = targetJar.toAbsolutePath().normalize();
    Path parentDir = absTarget.getParent();
    Path stagedJar = parentDir.resolve(absTarget.getFileName().toString() + ".new");
    Path backupJar = parentDir.resolve(absTarget.getFileName().toString() + ".bak");

    try {
      HttpResult shaRes =
          httpGet.get(
              latest.sha256DownloadUrl(),
              "application/octet-stream",
              authToken,
              Duration.ofSeconds(20));
      if (shaRes.statusCode() < 200 || shaRes.statusCode() >= 300) {
        throw new IOException(
            "Failed to download " + SHA256_ASSET_NAME + " (HTTP " + shaRes.statusCode() + ")");
      }
      String expectedSha256 = parseSha256Digest(shaRes.bodyText());
      if (expectedSha256 == null) {
        throw new ReleaseIntegrityException("Malformed " + SHA256_ASSET_NAME + " checksum asset");
      }

      HttpResult jarRes =
          httpGet.get(
              latest.jarDownloadUrl(),
              "application/octet-stream",
              authToken,
              Duration.ofSeconds(120));
      if (jarRes.statusCode() < 200 || jarRes.statusCode() >= 300 || jarRes.body() == null || jarRes.body().length == 0) {
        throw new IOException(
            "Failed to download " + JAR_ASSET_NAME + " (HTTP " + jarRes.statusCode() + ")");
      }

      String actualSha256 = sha256Hex(jarRes.body());
      if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
        throw new ReleaseIntegrityException(
            "SHA-256 mismatch for "
                + latest.tagName()
                + ": expected="
                + expectedSha256
                + ", actual="
                + actualSha256);
      }

      Files.write(stagedJar, jarRes.body());
      try {
        verifyJarArchiveStructure(stagedJar);
      } catch (IOException jarEx) {
        throw new ReleaseIntegrityException(jarEx.getMessage(), jarEx);
      }

      Path cliJavaExe = resolveJavaExecutable(false);
      if (!selfTest.verifyStagedJar(cliJavaExe, stagedJar)) {
        throw new ReleaseIntegrityException(
            "Staged " + latest.tagName() + " failed --self-test verification");
      }

      if (safeToRestartCheck != null && !safeToRestartCheck.getAsBoolean()) {
        Files.deleteIfExists(stagedJar);
        return new UpdateOutcome(
            true,
            false,
            currentVersion,
            latest.version(),
            null,
            "Deferred restart for " + latest.tagName() + ": robot became active.");
      }

      Files.copy(absTarget, backupJar, StandardCopyOption.REPLACE_EXISTING);
      Path relaunchJavaExe = resolveJavaExecutable(preferJavaw);
      handoffLauncher.launchSwapAndRelaunch(
          ProcessHandle.current().pid(),
          absTarget,
          stagedJar,
          backupJar,
          relaunchJavaExe,
          relaunchArgs != null ? List.copyOf(relaunchArgs) : List.of());

      return new UpdateOutcome(
          true,
          true,
          currentVersion,
          latest.version(),
          stagedJar,
          "Verified "
              + latest.tagName()
              + " (sha256="
              + actualSha256.substring(0, 12)
              + ") and scheduled restart.");
    } catch (ReleaseIntegrityException ex) {
      lastFailedTag = latest.tagName();
      try {
        Files.deleteIfExists(stagedJar);
      } catch (IOException ignored) {
      }
      throw ex;
    } catch (IOException | RuntimeException ex) {
      // Do not latch transient network/IO failures so later checks can retry once online.
      try {
        Files.deleteIfExists(stagedJar);
      } catch (IOException ignored) {
      }
      throw ex;
    }
  }

  /** Strips leading {@code v} and whitespace from a release tag/version string. */
  public static String normalizeVersion(String raw) {
    if (raw == null) {
      return "";
    }
    String s = raw.trim();
    if (s.startsWith("v") || s.startsWith("V")) {
      s = s.substring(1).trim();
    }
    return s;
  }

  /** Returns {@code true} strictly when {@code candidateVersion} is newer than {@code currentVersion}. */
  public static boolean isNewerVersion(String currentVersion, String candidateVersion) {
    String cur = stripBuildMetadata(normalizeVersion(currentVersion));
    String cand = stripBuildMetadata(normalizeVersion(candidateVersion));
    if (cand.isEmpty() || cur.equalsIgnoreCase(cand)) {
      return false;
    }
    int[] curNums = parseNumericCore(cur);
    int[] candNums = parseNumericCore(cand);
    int maxLen = Math.max(curNums.length, candNums.length);
    for (int i = 0; i < maxLen; i++) {
      int a = i < curNums.length ? curNums[i] : 0;
      int b = i < candNums.length ? candNums[i] : 0;
      if (b != a) {
        return b > a;
      }
    }
    String curSuffix = extractPreReleaseSuffix(cur);
    String candSuffix = extractPreReleaseSuffix(cand);
    if (curSuffix == null && candSuffix == null) {
      return false;
    }
    if (curSuffix != null && candSuffix == null) {
      return true;
    }
    if (curSuffix == null) {
      return false;
    }
    return comparePreReleaseSuffix(candSuffix, curSuffix) > 0;
  }

  private static String stripBuildMetadata(String normalized) {
    int plus = normalized.indexOf('+');
    return plus >= 0 ? normalized.substring(0, plus).trim() : normalized;
  }

  private static String extractPreReleaseSuffix(String stripped) {
    int dash = stripped.indexOf('-');
    if (dash < 0 || dash + 1 >= stripped.length()) {
      return null;
    }
    return stripped.substring(dash + 1);
  }

  private static int comparePreReleaseSuffix(String a, String b) {
    String[] aTokens = a.split("[.-]");
    String[] bTokens = b.split("[.-]");
    int minLen = Math.min(aTokens.length, bTokens.length);
    for (int i = 0; i < minLen; i++) {
      String ta = aTokens[i];
      String tb = bTokens[i];
      if (isAsciiDigits(ta) && isAsciiDigits(tb)) {
        int cmp = compareUnsignedNumericStrings(ta, tb);
        if (cmp != 0) {
          return cmp;
        }
      } else {
        int cmp = ta.compareToIgnoreCase(tb);
        if (cmp != 0) {
          return cmp;
        }
      }
    }
    return Integer.compare(aTokens.length, bTokens.length);
  }

  private static boolean isAsciiDigits(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  private static int compareUnsignedNumericStrings(String a, String b) {
    String na = stripLeadingZeros(a);
    String nb = stripLeadingZeros(b);
    if (na.length() != nb.length()) {
      return Integer.compare(na.length(), nb.length());
    }
    return na.compareTo(nb);
  }

  private static String stripLeadingZeros(String digits) {
    int idx = 0;
    while (idx + 1 < digits.length() && digits.charAt(idx) == '0') {
      idx++;
    }
    return digits.substring(idx);
  }

  private static int[] parseNumericCore(String normalized) {
    String stripped = stripBuildMetadata(normalized);
    String core = stripped.contains("-") ? stripped.substring(0, stripped.indexOf('-')) : stripped;
    String[] parts = core.split("\\.");
    int[] nums = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      String digits = parts[i].replaceAll("[^0-9]", "");
      if (!digits.isEmpty()) {
        try {
          nums[i] = Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
          nums[i] = 0;
        }
      }
    }
    return nums;
  }

  /** Parses the GitHub {@code /releases/latest} JSON response including its nested {@code assets} array. */
  static Optional<ReleaseInfo> parseLatestReleaseJson(String json) {
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }
    MapFields top = parseTopLevelFields(json);
    String tag = top.tagName;
    if (tag == null || tag.isBlank()) {
      return Optional.empty();
    }
    String jarUrl = null;
    String shaUrl = null;
    for (String assetObj : extractArrayObjects(json, "assets")) {
      var fields = PackageDeployer.parseFlatJson(assetObj);
      String name = fields.getOrDefault("name", "");
      String url = fields.getOrDefault("browser_download_url", "");
      if (JAR_ASSET_NAME.equalsIgnoreCase(name)) {
        jarUrl = url;
      } else if (SHA256_ASSET_NAME.equalsIgnoreCase(name)) {
        shaUrl = url;
      }
    }
    return Optional.of(
        new ReleaseInfo(
            tag.trim(),
            normalizeVersion(tag),
            top.draft,
            top.prerelease,
            jarUrl,
            shaUrl));
  }

  static String parseSha256Digest(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    Matcher m = SHA256_HEX_PATTERN.matcher(text.trim());
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
  }

  static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(String.format(Locale.US, "%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static void verifyJarArchiveStructure(Path jarFile) throws IOException {
    try (ZipFile zf = new ZipFile(jarFile.toFile())) {
      if (zf.getEntry("META-INF/MANIFEST.MF") == null) {
        throw new IOException("Downloaded JAR is missing META-INF/MANIFEST.MF");
      }
      if (zf.getEntry("frc/flux/tray/FluxTrayMain.class") == null) {
        throw new IOException("Downloaded JAR is missing frc/flux/tray/FluxTrayMain.class");
      }
    }
  }

  static Path resolveJavaExecutable(boolean preferJavaw) {
    boolean isWindows =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    String binName = isWindows ? (preferJavaw ? "javaw.exe" : "java.exe") : "java";
    Path candidate = Path.of(System.getProperty("java.home"), "bin", binName);
    if (Files.isRegularFile(candidate)) {
      return candidate;
    }
    return Path.of(binName);
  }

  static boolean isSameOrigin(URI a, URI b) {
    if (a == null || b == null) {
      return false;
    }
    String sA = a.getScheme() != null ? a.getScheme().toLowerCase(Locale.ROOT) : "";
    String sB = b.getScheme() != null ? b.getScheme().toLowerCase(Locale.ROOT) : "";
    String hA = a.getHost() != null ? a.getHost().toLowerCase(Locale.ROOT) : "";
    String hB = b.getHost() != null ? b.getHost().toLowerCase(Locale.ROOT) : "";
    return !sA.isEmpty()
        && sA.equals(sB)
        && !hA.isEmpty()
        && hA.equals(hB)
        && effectivePort(a) == effectivePort(b);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() > 0) {
      return uri.getPort();
    }
    String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
    return "https".equals(scheme) ? 443 : ("http".equals(scheme) ? 80 : -1);
  }

  private static HttpResult jdkHttpGet(
      String url, String acceptHeader, String authToken, Duration timeout) throws IOException {
    URI initialUri = URI.create(url);
    URI currentUri = initialUri;
    for (int hop = 0; hop <= MAX_HTTP_REDIRECTS; hop++) {
      HttpRequest.Builder req =
          HttpRequest.newBuilder(currentUri)
              .timeout(timeout)
              .header("User-Agent", "FluxTray/" + detectCurrentVersion())
              .header("Accept", acceptHeader != null ? acceptHeader : "*/*")
              .GET();
      if (authToken != null && !authToken.isBlank() && isSameOrigin(initialUri, currentUri)) {
        req.header("Authorization", "Bearer " + authToken);
      }
      HttpResponse<byte[]> response;
      try {
        response = SHARED_HTTP_CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted fetching " + currentUri, e);
      }
      int status = response.statusCode();
      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        Optional<String> loc = response.headers().firstValue("Location");
        if (loc.isEmpty() || loc.get().isBlank()) {
          return new HttpResult(status, response.body());
        }
        URI nextUri = currentUri.resolve(loc.get().trim());
        if ("https".equalsIgnoreCase(currentUri.getScheme())
            && !"https".equalsIgnoreCase(nextUri.getScheme())) {
          throw new IOException("Blocked insecure HTTPS -> HTTP redirect to " + nextUri);
        }
        currentUri = nextUri;
        continue;
      }
      return new HttpResult(status, response.body());
    }
    throw new IOException("Too many redirects fetching " + url);
  }

  static boolean runJarSelfTest(Path javaExe, Path stagedJar) throws IOException {
    return runJarSelfTest(javaExe, stagedJar, Duration.ofSeconds(20));
  }

  static boolean runJarSelfTest(Path javaExe, Path stagedJar, Duration timeout) throws IOException {
    Process p =
        new ProcessBuilder(
                javaExe.toString(), "-jar", stagedJar.toAbsolutePath().toString(), "--self-test")
            .redirectErrorStream(true)
            .start();
    Thread drainer =
        new Thread(
            () -> {
              try (InputStream in = p.getInputStream()) {
                in.transferTo(OutputStream.nullOutputStream());
              } catch (IOException ignored) {
              }
            },
            "fluxtray-selftest-drain");
    drainer.setDaemon(true);
    drainer.start();
    try {
      long timeoutMs = Math.max(1L, timeout.toMillis());
      boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!finished) {
        p.destroyForcibly();
        return false;
      }
      drainer.join(2_000L);
      return p.exitValue() == 0;
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted running staged JAR --self-test", e);
    } finally {
      p.destroyForcibly();
      drainer.interrupt();
      try {
        drainer.join(1_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static Map<String, String> collectFluxSystemProperties() {
    Map<String, String> props = new TreeMap<>();
    for (String name : System.getProperties().stringPropertyNames()) {
      if (name.startsWith("flux.")) {
        String val = System.getProperty(name);
        if (val != null) {
          props.put(name, val);
        }
      }
    }
    return props;
  }

  static String escapeWindowsBatchSetValue(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\r", "").replace("\n", "").replace("\"", "").replace("%", "%%");
  }

  static String quoteWindowsBatchArg(String arg) {
    String safe = arg == null ? "" : arg.replace("\r", "").replace("\n", "");
    StringBuilder sb = new StringBuilder(safe.length() + 8);
    sb.append('"');
    int backslashes = 0;
    for (int i = 0; i < safe.length(); i++) {
      char c = safe.charAt(i);
      if (c == '\\') {
        backslashes++;
      } else if (c == '"') {
        for (int b = 0; b < backslashes * 2 + 1; b++) {
          sb.append('\\');
        }
        sb.append('"');
        backslashes = 0;
      } else {
        for (int b = 0; b < backslashes; b++) {
          sb.append('\\');
        }
        backslashes = 0;
        if (c == '%') {
          sb.append("%%");
        } else {
          sb.append(c);
        }
      }
    }
    for (int b = 0; b < backslashes * 2; b++) {
      sb.append('\\');
    }
    sb.append('"');
    return sb.toString();
  }

  static List<String> buildWindowsHelperLaunchCommand(Path script) {
    return List.of("cmd.exe", "/d", "/c", script.toAbsolutePath().toString());
  }

  /** Builds the Windows {@code .cmd} swap-and-relaunch script (with automatic backup rollback). */
  static String buildWindowsSwapScript(
      long oldPid,
      Path targetJar,
      Path stagedJar,
      Path backupJar,
      Path relaunchJavaExe,
      List<String> relaunchArgs) {
    return buildWindowsSwapScript(
        oldPid,
        targetJar,
        stagedJar,
        backupJar,
        relaunchJavaExe,
        relaunchArgs,
        collectFluxSystemProperties());
  }

  static String buildWindowsSwapScript(
      long oldPid,
      Path targetJar,
      Path stagedJar,
      Path backupJar,
      Path relaunchJavaExe,
      List<String> relaunchArgs,
      Map<String, String> fluxJvmProperties) {
    StringBuilder jvmPropsBuilder = new StringBuilder();
    if (fluxJvmProperties != null) {
      for (Map.Entry<String, String> entry : new TreeMap<>(fluxJvmProperties).entrySet()) {
        jvmPropsBuilder
            .append(' ')
            .append(quoteWindowsBatchArg("-D" + entry.getKey() + "=" + entry.getValue()));
      }
    }
    StringBuilder argsBuilder = new StringBuilder();
    if (relaunchArgs != null) {
      for (String arg : relaunchArgs) {
        argsBuilder.append(' ').append(quoteWindowsBatchArg(arg));
      }
    }
    return "@echo off\r\n"
        + "setlocal DisableDelayedExpansion\r\n"
        + "set \"OLD_PID=" + oldPid + "\"\r\n"
        + "set \"TARGET=" + escapeWindowsBatchSetValue(targetJar.toAbsolutePath().toString()) + "\"\r\n"
        + "set \"STAGED=" + escapeWindowsBatchSetValue(stagedJar.toAbsolutePath().toString()) + "\"\r\n"
        + "set \"BACKUP=" + escapeWindowsBatchSetValue(backupJar.toAbsolutePath().toString()) + "\"\r\n"
        + "set \"JAVA_EXE=" + escapeWindowsBatchSetValue(relaunchJavaExe.toAbsolutePath().toString()) + "\"\r\n"
        + "for /L %%i in (1,1,20) do (\r\n"
        + "  tasklist /FI \"PID eq %OLD_PID%\" 2>NUL | find /I \"%OLD_PID%\" >NUL\r\n"
        + "  if errorlevel 1 goto wait_done\r\n"
        + "  ping -n 2 127.0.0.1 >NUL\r\n"
        + ")\r\n"
        + ":wait_done\r\n"
        + "set \"SWAPPED=0\"\r\n"
        + "for /L %%r in (1,1,5) do (\r\n"
        + "  move /Y \"%STAGED%\" \"%TARGET%\" >NUL 2>&1\r\n"
        + "  if not errorlevel 1 (\r\n"
        + "    set \"SWAPPED=1\"\r\n"
        + "    goto launch\r\n"
        + "  )\r\n"
        + "  ping -n 2 127.0.0.1 >NUL\r\n"
        + ")\r\n"
        + ":launch\r\n"
        + "if \"%SWAPPED%\"==\"0\" (\r\n"
        + "  if exist \"%BACKUP%\" copy /Y \"%BACKUP%\" \"%TARGET%\" >NUL 2>&1\r\n"
        + ")\r\n"
        + "start \"\" \"%JAVA_EXE%\""
        + jvmPropsBuilder
        + " -jar \"%TARGET%\""
        + argsBuilder
        + "\r\n"
        + "(goto) 2>nul & del /F /Q \"%~f0\"\r\n";
  }

  /** Builds the POSIX {@code /bin/sh} swap-and-relaunch script (with automatic backup rollback). */
  static String buildPosixSwapScript(
      long oldPid,
      Path targetJar,
      Path stagedJar,
      Path backupJar,
      Path relaunchJavaExe,
      List<String> relaunchArgs) {
    return buildPosixSwapScript(
        oldPid,
        targetJar,
        stagedJar,
        backupJar,
        relaunchJavaExe,
        relaunchArgs,
        collectFluxSystemProperties());
  }

  static String buildPosixSwapScript(
      long oldPid,
      Path targetJar,
      Path stagedJar,
      Path backupJar,
      Path relaunchJavaExe,
      List<String> relaunchArgs,
      Map<String, String> fluxJvmProperties) {
    StringBuilder jvmPropsBuilder = new StringBuilder();
    if (fluxJvmProperties != null) {
      for (Map.Entry<String, String> entry : new TreeMap<>(fluxJvmProperties).entrySet()) {
        jvmPropsBuilder
            .append(' ')
            .append(SshRobotTransport.shQuote("-D" + entry.getKey() + "=" + entry.getValue()));
      }
    }
    StringBuilder argsBuilder = new StringBuilder();
    if (relaunchArgs != null) {
      for (String arg : relaunchArgs) {
        argsBuilder.append(' ').append(SshRobotTransport.shQuote(arg));
      }
    }
    return "#!/bin/sh\n"
        + "OLD_PID=" + oldPid + "\n"
        + "TARGET=" + SshRobotTransport.shQuote(targetJar.toAbsolutePath().toString()) + "\n"
        + "STAGED=" + SshRobotTransport.shQuote(stagedJar.toAbsolutePath().toString()) + "\n"
        + "BACKUP=" + SshRobotTransport.shQuote(backupJar.toAbsolutePath().toString()) + "\n"
        + "i=0\n"
        + "while [ \"$i\" -lt 30 ] && kill -0 \"$OLD_PID\" 2>/dev/null; do\n"
        + "  sleep 0.5 2>/dev/null || sleep 1\n"
        + "  i=$((i + 1))\n"
        + "done\n"
        + "if ! mv -f \"$STAGED\" \"$TARGET\" 2>/dev/null; then\n"
        + "  [ -f \"$BACKUP\" ] && cp -f \"$BACKUP\" \"$TARGET\" 2>/dev/null || true\n"
        + "fi\n"
        + "nohup "
        + SshRobotTransport.shQuote(relaunchJavaExe.toAbsolutePath().toString())
        + jvmPropsBuilder
        + " -jar \"$TARGET\""
        + argsBuilder
        + " </dev/null >/dev/null 2>&1 &\n"
        + "rm -f \"$0\"\n";
  }

  private static void spawnPlatformSwapHelper(
      long currentPid,
      Path targetJar,
      Path stagedJar,
      Path backupJar,
      Path relaunchJavaExe,
      List<String> relaunchArgs)
      throws IOException {
    boolean isWindows =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    if (isWindows) {
      Path script = Files.createTempFile("fluxtray-apply-update-", ".cmd");
      Files.writeString(
          script,
          buildWindowsSwapScript(
              currentPid, targetJar, stagedJar, backupJar, relaunchJavaExe, relaunchArgs),
          StandardCharsets.UTF_8);
      File nul = new File("NUL");
      new ProcessBuilder(buildWindowsHelperLaunchCommand(script))
          .redirectInput(ProcessBuilder.Redirect.from(nul))
          .redirectOutput(ProcessBuilder.Redirect.to(nul))
          .redirectError(ProcessBuilder.Redirect.to(nul))
          .start();
    } else {
      Path script = Files.createTempFile("fluxtray-apply-update-", ".sh");
      Files.writeString(
          script,
          buildPosixSwapScript(
              currentPid, targetJar, stagedJar, backupJar, relaunchJavaExe, relaunchArgs),
          StandardCharsets.UTF_8);
      new ProcessBuilder("/bin/sh", script.toAbsolutePath().toString()).start();
    }
  }

  private record MapFields(String tagName, boolean draft, boolean prerelease) {}

  private static MapFields parseTopLevelFields(String json) {
    var flat = PackageDeployer.parseFlatJson(json);
    String tag = flat.get("tag_name");
    boolean draft = "true".equalsIgnoreCase(flat.get("draft"));
    boolean prerelease = "true".equalsIgnoreCase(flat.get("prerelease"));
    return new MapFields(tag, draft, prerelease);
  }

  /** Extracts each JSON object inside a named top-level array field (ignoring string literals). */
  static List<String> extractArrayObjects(String json, String fieldName) {
    List<String> objects = new ArrayList<>();
    if (json == null || fieldName == null) {
      return objects;
    }
    int len = json.length();
    int i = skipJsonWhitespace(json, 0);
    if (i >= len || json.charAt(i) != '{') {
      return objects;
    }
    i++;
    while (i < len) {
      i = skipJsonWhitespace(json, i);
      if (i >= len || json.charAt(i) == '}') {
        break;
      }
      if (json.charAt(i) != '"') {
        break;
      }
      StringBuilder keyBuf = new StringBuilder();
      int afterKey = readJsonStringToken(json, i, keyBuf);
      if (afterKey < 0) {
        break;
      }
      i = skipJsonWhitespace(json, afterKey);
      if (i >= len || json.charAt(i) != ':') {
        break;
      }
      i = skipJsonWhitespace(json, i + 1);
      if (i >= len) {
        break;
      }
      if (fieldName.equals(keyBuf.toString()) && json.charAt(i) == '[') {
        return collectTopLevelObjectsInArray(json, i);
      }
      int afterVal = skipJsonValue(json, i);
      if (afterVal < 0) {
        break;
      }
      i = skipJsonWhitespace(json, afterVal);
      if (i < len && json.charAt(i) == ',') {
        i++;
      } else {
        break;
      }
    }
    return objects;
  }

  private static List<String> collectTopLevelObjectsInArray(String json, int openBracket) {
    List<String> objects = new ArrayList<>();
    int depth = 0;
    int objStart = -1;
    boolean inQuotes = false;
    boolean escaped = false;
    for (int i = openBracket + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (inQuotes) {
        if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inQuotes = false;
        }
        continue;
      }
      if (c == '"') {
        inQuotes = true;
      } else if (c == '{') {
        if (depth == 0) {
          objStart = i;
        }
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0 && objStart >= 0) {
          objects.add(json.substring(objStart, i + 1));
          objStart = -1;
        }
      } else if (c == ']' && depth == 0) {
        break;
      }
    }
    return objects;
  }

  private static int skipJsonWhitespace(String s, int idx) {
    while (idx < s.length() && Character.isWhitespace(s.charAt(idx))) {
      idx++;
    }
    return idx;
  }

  private static int readJsonStringToken(String s, int openQuote, StringBuilder out) {
    int i = openQuote + 1;
    while (i < s.length()) {
      char c = s.charAt(i++);
      if (c == '\\') {
        if (i >= s.length()) {
          return -1;
        }
        char esc = s.charAt(i++);
        switch (esc) {
          case '"', '\\', '/' -> out.append(esc);
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'u' -> {
            if (i + 4 > s.length()) {
              return -1;
            }
            try {
              out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            } catch (NumberFormatException e) {
              return -1;
            }
            i += 4;
          }
          default -> out.append(esc);
        }
      } else if (c == '"') {
        return i;
      } else {
        out.append(c);
      }
    }
    return -1;
  }

  private static int skipJsonValue(String s, int idx) {
    if (idx >= s.length()) {
      return -1;
    }
    char c = s.charAt(idx);
    if (c == '"') {
      return readJsonStringToken(s, idx, new StringBuilder());
    }
    if (c == '{' || c == '[') {
      int depth = 0;
      boolean inQuotes = false;
      boolean escaped = false;
      for (int i = idx; i < s.length(); i++) {
        char ch = s.charAt(i);
        if (escaped) {
          escaped = false;
          continue;
        }
        if (inQuotes) {
          if (ch == '\\') {
            escaped = true;
          } else if (ch == '"') {
            inQuotes = false;
          }
          continue;
        }
        if (ch == '"') {
          inQuotes = true;
        } else if (ch == '{' || ch == '[') {
          depth++;
        } else if (ch == '}' || ch == ']') {
          depth--;
          if (depth == 0) {
            return i + 1;
          }
        }
      }
      return -1;
    }
    int i = idx;
    while (i < s.length()) {
      char ch = s.charAt(i);
      if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) {
        break;
      }
      i++;
    }
    return i > idx ? i : -1;
  }
}
