package dev.xsoz.client.training;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xsoz.client.XsozClient;
import dev.xsoz.client.config.ConfigManager;
import dev.xsoz.client.trainer.crystal.TrainerStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config/xsoz/trainer/crystal/kits.json - one custom kit per game mode and whether training uses
 * it. Kits are equipment, not stats, so they are saved in test mode too. Client thread.
 */
public final class KitStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class Data {
        public Map<String, KitSpec> kits = new LinkedHashMap<>();
        public Map<String, Boolean> useCustom = new LinkedHashMap<>();
    }

    private static Data data;

    private KitStore() { }

    private static Path file() { return TrainerStore.dir().resolve("kits.json"); }

    public static synchronized Data data() {
        if (data == null) {
            try {
                if (Files.exists(file())) data = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Data.class);
            } catch (Exception ex) {
                XsozClient.LOG.warn("Kits unreadable, starting fresh: {}", ex.toString());
            }
            if (data == null) data = new Data();
            if (data.kits == null) data.kits = new LinkedHashMap<>();
            if (data.useCustom == null) data.useCustom = new LinkedHashMap<>();
        }
        return data;
    }

    /** The saved custom kit for a mode, or null. */
    public static KitSpec custom(KitSpec.Mode mode) { return data().kits.get(mode.name()); }

    public static boolean usesCustom(KitSpec.Mode mode) {
        return Boolean.TRUE.equals(data().useCustom.get(mode.name())) && custom(mode) != null;
    }

    public static synchronized void put(KitSpec.Mode mode, KitSpec kit, boolean use) {
        data().kits.put(mode.name(), kit.copy());
        data().useCustom.put(mode.name(), use);
        save();
    }

    public static synchronized void setUse(KitSpec.Mode mode, boolean use) {
        data().useCustom.put(mode.name(), use);
        save();
    }

    public static synchronized void save() {
        ConfigManager.writeAtomic(file(), GSON.toJson(data()));
    }
}
