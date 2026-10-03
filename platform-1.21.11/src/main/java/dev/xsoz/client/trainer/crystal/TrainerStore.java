package dev.xsoz.client.trainer.crystal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.xsoz.client.XsozClient;
import dev.xsoz.client.config.ConfigManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists the profile and every recorded fight under config/xsoz/trainer/crystal. Disk writes run
 * on one low-priority background thread so a fight ending never stalls a frame.
 */
public final class TrainerStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "xsoz-trainer-io");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static TrainerProfile profile;

    private TrainerStore() { }

    public static Path dir() { return ConfigManager.dir().resolve("trainer").resolve("crystal"); }

    private static Path fightsDir() { return dir().resolve("fights"); }

    public static synchronized TrainerProfile profile() {
        if (profile == null) {
            Path f = dir().resolve("profile.json");
            try {
                if (Files.exists(f)) profile = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), TrainerProfile.class);
            } catch (Exception ex) {
                XsozClient.LOG.warn("Trainer profile unreadable, starting fresh: {}", ex.toString());
            }
            if (profile == null) profile = new TrainerProfile();
        }
        return profile;
    }

    public static void saveProfile() {
        String json = PRETTY.toJson(profile());
        IO.execute(() -> ConfigManager.writeAtomic(dir().resolve("profile.json"), json));
    }

    public static void saveFight(FightRecord record, FightReport report) {
        JsonObject o = new JsonObject();
        o.add("record", GSON.toJsonTree(record));
        o.add("report", GSON.toJsonTree(report));
        String json = GSON.toJson(o);
        IO.execute(() -> ConfigManager.writeAtomic(fightsDir().resolve(record.id + ".json"), json));
    }

    /** Loads a stored fight (blocking - call from a screen, not a tick). */
    public static Loaded loadFight(String id) {
        Path f = fightsDir().resolve(id + ".json");
        try {
            if (!Files.exists(f)) return null;
            JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
            FightRecord rec = GSON.fromJson(o.get("record"), FightRecord.class);
            FightReport rep = FightAnalyzer.analyze(rec);
            return new Loaded(rec, rep);
        } catch (IOException | RuntimeException ex) {
            XsozClient.LOG.warn("Could not load fight {}: {}", id, ex.toString());
            return null;
        }
    }

    public static void deleteFight(String id) {
        IO.execute(() -> {
            try {
                Files.deleteIfExists(fightsDir().resolve(id + ".json"));
            } catch (IOException ignored) {
                // a stale file is harmless
            }
        });
    }

    public record Loaded(FightRecord record, FightReport report) {
    }
}
