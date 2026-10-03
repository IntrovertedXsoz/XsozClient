package dev.xsoz.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xsoz.client.XsozClient;
import dev.xsoz.client.module.HudModule;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.setting.ActionSetting;
import dev.xsoz.client.setting.Setting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * config/xsoz/config.json inside the game directory - which the launcher isolates, so this never
 * lands in the user's own .minecraft. Writes are atomic (temp file + move) and debounced.
 */
public final class ConfigManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static boolean dirty;
    private static int ticksSinceDirty;
    private static JsonObject extra = new JsonObject();

    private ConfigManager() { }

    public static Path dir() {
        // FabricLoader's config dir is valid during mod init, before MinecraftClient finishes building.
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xsoz");
    }

    private static Path file() { return dir().resolve("config.json"); }

    public static void markDirty() {
        dirty = true;
        ticksSinceDirty = 0;
    }

    /** Free-form section for screens (panel positions etc.). */
    public static JsonObject extra() { return extra; }

    public static void tick() {
        if (!dirty) return;
        if (++ticksSinceDirty >= 40) save();
    }

    public static void load() {
        Path f = file();
        boolean fresh = !Files.exists(f);
        JsonObject root = new JsonObject();
        if (!fresh) {
            try {
                root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception ex) {
                XsozClient.LOG.warn("Config unreadable, keeping defaults and a copy: {}", ex.toString());
                try {
                    Files.copy(f, dir().resolve("config.broken.json"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // nothing more to do
                }
                fresh = true;
            }
        }

        JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
        for (Module m : ModuleManager.all()) {
            JsonObject o = modules.has(m.id()) ? modules.getAsJsonObject(m.id()) : null;
            if (o != null && o.has("settings")) {
                JsonObject s = o.getAsJsonObject("settings");
                for (Setting<?> setting : m.settings()) {
                    if (s.has(setting.name())) setting.fromJson(s.get(setting.name()));
                }
            }
            if (o != null && o.has("bind")) m.setBind(o.get("bind").getAsInt());
            if (m instanceof HudModule h && o != null && o.has("x") && o.has("y")) {
                h.setFraction(o.get("x").getAsFloat(), o.get("y").getAsFloat());
            }
            boolean on = o != null && o.has("enabled") ? o.get("enabled").getAsBoolean() : m.defaultEnabled();
            m.restoreEnabled(on);
        }
        extra = root.has("extra") ? root.getAsJsonObject("extra") : new JsonObject();
        dirty = fresh;
    }

    public static void save() {
        dirty = false;
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonObject modules = new JsonObject();
        for (Module m : ModuleManager.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", m.isEnabled());
            o.addProperty("bind", m.bind());
            if (m instanceof HudModule h) {
                o.addProperty("x", h.fx());
                o.addProperty("y", h.fy());
            }
            JsonObject s = new JsonObject();
            for (Setting<?> setting : m.settings()) {
                if (setting instanceof ActionSetting) continue;
                JsonElement e = setting.toJson();
                s.add(setting.name(), e);
            }
            o.add("settings", s);
            modules.add(m.id(), o);
        }
        root.add("modules", modules);
        root.add("extra", extra);
        writeAtomic(file(), GSON.toJson(root));
    }

    public static void writeAtomic(Path target, String text) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            XsozClient.LOG.warn("Could not write {}: {}", target, ex.toString());
        }
    }
}
