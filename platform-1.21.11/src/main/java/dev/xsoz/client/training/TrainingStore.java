package dev.xsoz.client.training;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xsoz.client.XsozClient;
import dev.xsoz.client.config.ConfigManager;
import dev.xsoz.client.trainer.crystal.TrainerStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/xsoz/trainer/crystal/training.json - passes, bests, XP, experience level. */
public final class TrainingStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static TrainingProgress progress;

    private TrainingStore() { }

    private static Path file() { return TrainerStore.dir().resolve("training.json"); }

    public static synchronized TrainingProgress progress() {
        if (progress == null) {
            try {
                if (Files.exists(file())) progress = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), TrainingProgress.class);
            } catch (Exception ex) {
                XsozClient.LOG.warn("Training progress unreadable, starting fresh: {}", ex.toString());
            }
            if (progress == null) progress = new TrainingProgress();
        }
        return progress;
    }

    public static synchronized void save() {
        ConfigManager.writeAtomic(file(), GSON.toJson(progress()));
    }
}
