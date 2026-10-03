package dev.xsoz.client.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xsoz.client.XsozClient;
import dev.xsoz.client.config.ConfigManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

/**
 * Updates from the GitHub releases of the client: on start (unless the player said "Don't ask
 * again") and from Settings, it reads the release list, picks the newest release on the player's
 * channel, and - only when the player says yes - downloads the mod jar from it and puts it in
 * place of this one. Nothing about the player is sent: one plain request to GitHub's public API.
 *
 * <p>A release is found by its tag ({@link Version}) and must carry the mod jar (a {@code .jar}
 * asset). The running jar can't always be replaced while the game has it open, so the new one is
 * swapped in when Minecraft closes: a tiny hidden PowerShell step waits for the game to exit and
 * moves it over. If that step never ran (the PC was shut down first), the next start does it again.
 */
public final class Updates {
    public static final String REPO = "IntrovertedXsoz/XsozClient";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=40";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** A release worth offering. switchBack: the player left a channel and this is the newest on theirs, though older. */
    public record Release(Version version, String tag, String jarUrl, long jarSize, String page, boolean switchBack) {
    }

    /** What a check found. */
    public record Result(Release release, String error) {
        public boolean upToDate() { return release == null && error == null; }
    }

    private static boolean checkedThisLaunch;
    private static volatile boolean checking;
    /** Download progress 0..1, or -1 when not downloading. */
    public static volatile float progress = -1f;

    private Updates() { }

    // ------------------------------------------------------------------ settings

    private static JsonObject prefs() {
        JsonObject e = ConfigManager.extra();
        if (!e.has("updates") || !e.get("updates").isJsonObject()) e.add("updates", new JsonObject());
        return e.getAsJsonObject("updates");
    }

    /** The player's channel; until they pick one, the channel of the build they installed. */
    public static Channel channel() {
        JsonObject p = prefs();
        return p.has("channel") ? Channel.of(p.get("channel").getAsString()) : current().kind();
    }

    public static void setChannel(Channel c) {
        prefs().addProperty("channel", c.name());
        ConfigManager.markDirty();
    }

    /** Ask about updates when the game starts ("Don't ask again" turns this off). */
    public static boolean askOnStart() {
        JsonObject p = prefs();
        return !p.has("ask") || p.get("ask").getAsBoolean();
    }

    public static void setAskOnStart(boolean on) {
        prefs().addProperty("ask", on);
        ConfigManager.markDirty();
    }

    public static Version current() {
        Version v = Version.parse(XsozClient.VERSION);
        return v != null ? v : new Version(0, 0, 0, Channel.STABLE, 0);
    }

    // ------------------------------------------------------------------ checking

    /** The main menu showed: check once per launch, if the player wants that. */
    public static void onMenuShown(Consumer<Result> whenFound) {
        if (checkedThisLaunch || !askOnStart()) return;
        Path jar = ownJar();
        if (jar == null || !jar.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return; // a development build: nothing to update
        checkedThisLaunch = true;
        check(r -> {
            if (r.release() != null) whenFound.accept(r);
            else if (r.error() != null) XsozClient.LOG.info("Update check skipped: {}", r.error());
        });
    }

    public static boolean checking() { return checking; }

    /** Reads the release list off the game thread; answers on the game thread. */
    public static void check(Consumer<Result> done) {
        if (checking) return;
        checking = true;
        Channel ch = channel();
        Version cur = current();
        HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "XsozClient/" + XsozClient.VERSION)
                .GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(res -> {
                    if (res.statusCode() == 403 || res.statusCode() == 429) return new Result(null, "GitHub is busy, try again in a while");
                    if (res.statusCode() != 200) return new Result(null, "GitHub answered " + res.statusCode());
                    return new Result(pick(JsonParser.parseString(res.body()).getAsJsonArray(), ch, cur), null);
                })
                .exceptionally(ex -> new Result(null, "no connection to GitHub"))
                .thenAccept(r -> MinecraftClient.getInstance().execute(() -> {
                    checking = false;
                    done.accept(r);
                }));
    }

    /** The newest release on the channel that is newer than this one - or, if this build isn't on the channel any more, the newest one that is. */
    static Release pick(JsonArray releases, Channel ch, Version cur) {
        Release best = null;
        for (JsonElement el : releases) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("draft") && r.get("draft").getAsBoolean()) continue;
            Version v = Version.parse(str(r, "tag_name"));
            if (v == null || !ch.gets(v.kind())) continue;
            String url = null;
            long size = -1;
            if (r.has("assets")) {
                for (JsonElement a : r.getAsJsonArray("assets")) {
                    JsonObject ao = a.getAsJsonObject();
                    String name = str(ao, "name").toLowerCase(Locale.ROOT);
                    if (name.endsWith(".jar") && !name.contains("sources")) {
                        url = str(ao, "browser_download_url");
                        size = ao.has("size") ? ao.get("size").getAsLong() : -1;
                        break;
                    }
                }
            }
            if (url == null || url.isEmpty()) continue; // no mod jar on it: not installable from here
            if (best == null || v.newerThan(best.version())) best = new Release(v, str(r, "tag_name"), url, size, str(r, "html_url"), false);
        }
        if (best == null) return null;
        if (best.version().newerThan(cur)) return best;
        // this build is from a channel the player left (Experimental, then picked Stable): offer the way back
        if (!ch.gets(cur.kind()) && !best.version().equals(cur)) {
            return new Release(best.version(), best.tag(), best.jarUrl(), best.jarSize(), best.page(), true);
        }
        return null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    // ------------------------------------------------------------------ installing

    /** Where this mod's jar is, or null when it isn't running from a jar (a development build). */
    public static Path ownJar() {
        try {
            var c = FabricLoader.getInstance().getModContainer("xsozclient");
            if (c.isEmpty()) return null;
            List<Path> paths = c.get().getOrigin().getPaths();
            return paths.isEmpty() ? null : paths.get(0);
        } catch (UnsupportedOperationException ex) {
            return null; // inside another jar
        }
    }

    private static Path updateDir(Path jar) { return jar.getParent().resolve(".xsoz-update"); }

    /**
     * Downloads the release's jar, checks it really is this mod, and puts it in place. Answers on
     * the game thread with null (done: restart to use it) or what went wrong.
     */
    public static void install(Release r, Consumer<String> done) {
        Path jar = ownJar();
        if (jar == null || !jar.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            done.accept("This copy isn't installed from a jar (a development build), so it can't update itself.");
            return;
        }
        progress = 0f;
        CompletableFuture.supplyAsync(() -> {
            try {
                Path dir = updateDir(jar);
                Files.createDirectories(dir);
                Path part = dir.resolve("xsozclient-" + r.tag() + ".jar.part");
                Path got = dir.resolve("xsozclient-" + r.tag() + ".jar");
                HttpRequest req = HttpRequest.newBuilder(URI.create(r.jarUrl()))
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "XsozClient/" + XsozClient.VERSION)
                        .GET().build();
                HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) return "the download failed (GitHub answered " + res.statusCode() + ")";
                long total = r.jarSize() > 0 ? r.jarSize() : res.headers().firstValueAsLong("content-length").orElse(-1);
                long n = 0;
                try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        out.write(buf, 0, k);
                        n += k;
                        if (total > 0) progress = Math.min(0.99f, n / (float) total);
                    }
                }
                if (r.jarSize() > 0 && n != r.jarSize()) return "the download was cut off";
                if (!isThisMod(part)) return "the downloaded file isn't Xsoz Client";
                Files.move(part, got, StandardCopyOption.REPLACE_EXISTING);
                return swapIn(got, jar, r.version());
            } catch (IOException | InterruptedException ex) {
                return "the download failed (" + ex.getClass().getSimpleName() + ")";
            }
        }).exceptionally(ex -> "the update failed (" + ex.getClass().getSimpleName() + ")")
                .thenAccept(err -> MinecraftClient.getInstance().execute(() -> {
                    progress = -1f;
                    done.accept(err);
                }));
    }

    /** A real mod jar with our id in it, not an error page. */
    private static boolean isThisMod(Path p) {
        try (ZipFile z = new ZipFile(p.toFile())) {
            var e = z.getEntry("fabric.mod.json");
            if (e == null) return false;
            String json = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            return "xsozclient".equals(JsonParser.parseString(json).getAsJsonObject().get("id").getAsString());
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Puts the new jar where the old one is. Straight away if Windows lets go of the old file
     * (moved aside as .old: the game only loads .jar files); otherwise when the game closes.
     */
    private static String swapIn(Path got, Path jar, Version v) {
        Path old = jar.resolveSibling(jar.getFileName() + ".old");
        try {
            Files.move(jar, old, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(got, jar, StandardCopyOption.REPLACE_EXISTING);
                return null;
            } catch (IOException ex) {
                Files.move(old, jar, StandardCopyOption.REPLACE_EXISTING); // put it back
            }
        } catch (IOException ignored) {
            // locked while the game runs: swap when it closes
        }
        try {
            Path dir = updateDir(jar);
            ConfigManager.writeAtomic(dir.resolve("pending.txt"), v.pretty() + "\n" + got.getFileName() + "\n");
            return startSwapOnExit(got, jar) ? null : "the update was downloaded but can't be put in place - close Minecraft and try again";
        } catch (RuntimeException ex) {
            return "the update was downloaded but can't be put in place";
        }
    }

    /** A hidden PowerShell step: waits for this game to close, then moves the new jar over the old one. */
    private static boolean startSwapOnExit(Path got, Path jar) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return false;
        try {
            Path script = updateDir(jar).resolve("apply.ps1");
            String ps = String.join("\r\n",
                    "param([int]$GamePid, [string]$From, [string]$To)",
                    "try { Wait-Process -Id $GamePid -Timeout 3600 -ErrorAction SilentlyContinue } catch { }",
                    "for ($i = 0; $i -lt 120; $i++) {",
                    "  try { Move-Item -LiteralPath $From -Destination $To -Force -ErrorAction Stop; break } catch { Start-Sleep -Milliseconds 500 }",
                    "}",
                    "$p = Join-Path (Split-Path -Parent $From) 'pending.txt'",
                    "if (-not (Test-Path -LiteralPath $From)) { Remove-Item -LiteralPath $p -ErrorAction SilentlyContinue }",
                    "");
            Files.writeString(script, ps, StandardCharsets.UTF_8);
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", script.toString(), "-GamePid", Long.toString(ProcessHandle.current().pid()),
                    "-From", got.toString(), "-To", jar.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException ex) {
            XsozClient.LOG.warn("Could not start the update step: {}", ex.toString());
            return false;
        }
    }

    /**
     * On start: clears what an earlier update left behind, and if an update was downloaded but
     * never put in place (the game was killed, the PC shut down), queues it again for this exit.
     */
    public static void tidyOnStart() {
        try {
            Path jar = ownJar();
            if (jar == null || !Files.isRegularFile(jar)) return;
            Files.deleteIfExists(jar.resolveSibling(jar.getFileName() + ".old"));
            Path dir = updateDir(jar);
            Path pending = dir.resolve("pending.txt");
            if (!Files.exists(pending)) return;
            List<String> lines = Files.readAllLines(pending, StandardCharsets.UTF_8);
            Version v = lines.isEmpty() ? null : versionOfPretty(lines.get(0));
            Path got = lines.size() > 1 ? dir.resolve(lines.get(1).trim()) : null;
            if (v == null || got == null || !Files.exists(got) || v.equals(current())) { // done, or nothing to do
                Files.deleteIfExists(pending);
                if (got != null) Files.deleteIfExists(got);
                return;
            }
            startSwapOnExit(got, jar);
        } catch (IOException | RuntimeException ex) {
            XsozClient.LOG.warn("Update tidy-up: {}", ex.toString());
        }
    }

    private static Version versionOfPretty(String s) {
        String[] p = s.trim().split(" ");
        if (p.length == 1) return Version.parse(p[0]);
        String kind = p[1].equalsIgnoreCase("Dev") ? "dev" : "exp";
        return Version.parse(p[0] + "-" + kind + (p.length > 2 ? "." + p[2] : ""));
    }
}
