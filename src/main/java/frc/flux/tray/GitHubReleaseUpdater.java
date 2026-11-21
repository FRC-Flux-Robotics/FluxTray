package frc.flux.tray;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Optional;
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
        throw new IOException("Malformed " + SHA256_ASSET_NAME + " checksum asset");
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
        throw new IOException(
            "SHA-256 mismatch for "
                + latest.tagName()
                + ": expected="
                + expectedSha256
                + ", actual="
                + actualSha256);
      }

      Files.write(stagedJar, jarRes.body());
      verifyJarArchiveStructure(stagedJar);

      Path cliJavaExe = resolveJavaExecutable(false);
      if (!selfTest.verifyStagedJar(cliJavaExe, stagedJar)) {
        throw new IOException("Staged " + latest.tagName() + " failed --self-test verification");
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
    } catch (IOException | RuntimeException ex) {
      lastFailedTag = latest.tagName();
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
    String cur = normalizeVersion(currentVersion);
    String cand = normalizeVersion(candidateVersion);
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
    // Same numeric core: a clean release (no -suffix) is newer than a suffixed build.
    boolean curHasSuffix = cur.contains("-");
    boolean candHasSuffix = cand.contains("-");
    return curHasSuffix && !candHasSuffix;
  }

  private static int[] parseNumericCore(String normalized) {
    String core = normalized.contains("-") ? normalized.substring(0, normalized.indexOf('-')) : normalized;
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

  private static HttpResult jdkHttpGet(
      String url, String acceptHeader, String authToken, Duration timeout) throws IOException {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "FluxTray/" + detectCurrentVersion())
            .header("Accept", acceptHeader != null ? acceptHeader : "*/*")
            .GET();
    if (authToken != null && !authToken.isBlank()) {
      req.header("Authorization", "Bearer " + authToken);
    }
    try {
      HttpResponse<byte[]> response =
          client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new HttpResult(response.statusCode(), response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted fetching " + url, e);
    }
  }

  private static boolean runJarSelfTest(Path javaExe, Path stagedJar) throws IOException {
    Process p =
        new ProcessBuilder(javaExe.toString(), "-jar", stagedJar.toAbsolutePath().toString(), "--self-test")
            .redirectErrorStream(true)
            .start();
    try (InputStream ignored = p.getInputStream()) {
      ignored.readAllBytes();
      boolean finished = p.waitFor(20, TimeUnit.SECONDS);
      if (!finished) {
        p.destroyForcibly();
        return false;
      }
      return p.exitValue() == 0;
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted running staged JAR --self-test", e);
    }
  }

  /** Builds the Windows {@code .cmd} swap-and-relaunch script (with automatic backup rollback). */
  static String buildWindowsSwapScript(
      long oldPid,
      Path targetJar,
      Path stagedJar,
      Path backupJar,
      Path relaunchJavaExe,
      List<String> relaunchArgs) {
    StringBuilder argsBuilder = new StringBuilder();
    for (String arg : relaunchArgs) {
      argsBuilder.append(" \"").append(arg.replace("\"", "\"\"")).append("\"");
    }
    return "@echo off\r\n"
        + "setlocal\r\n"
        + "set \"OLD_PID=" + oldPid + "\"\r\n"
        + "set \"TARGET=" + targetJar.toAbsolutePath() + "\"\r\n"
        + "set \"STAGED=" + stagedJar.toAbsolutePath() + "\"\r\n"
        + "set \"BACKUP=" + backupJar.toAbsolutePath() + "\"\r\n"
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
        + "start \"\" \"" + relaunchJavaExe.toAbsolutePath() + "\" -jar \"%TARGET%\"" + argsBuilder + "\r\n";
  }

  /** Builds the POSIX {@code /bin/sh} swap-and-relaunch script (with automatic backup rollback). */
  static String buildPosixSwapScript(
      long oldPid,
      Path targetJar,
      Path stagedJar,
      Path backupJar,
      Path relaunchJavaExe,
      List<String> relaunchArgs) {
    StringBuilder argsBuilder = new StringBuilder();
    for (String arg : relaunchArgs) {
      argsBuilder.append(' ').append(SshRobotTransport.shQuote(arg));
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
        + " -jar \"$TARGET\""
        + argsBuilder
        + " </dev/null >/dev/null 2>&1 &\n";
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
      new ProcessBuilder("cmd.exe", "/c", "start", "", "/B", script.toAbsolutePath().toString())
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

  /** Extracts each JSON object inside a named top-level array field (e.g., {@code "assets": [...]}). */
  private static List<String> extractArrayObjects(String json, String fieldName) {
    List<String> objects = new ArrayList<>();
    String keyToken = "\"" + fieldName + "\"";
    int keyIdx = json.indexOf(keyToken);
    if (keyIdx < 0) {
      return objects;
    }
    int openBracket = json.indexOf('[', keyIdx + keyToken.length());
    if (openBracket < 0) {
      return objects;
    }
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
}
