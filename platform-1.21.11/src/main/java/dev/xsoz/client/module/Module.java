package dev.xsoz.client.module;

import dev.xsoz.client.setting.Setting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.MinecraftClient;

/**
 * A toggleable client feature. Modules never send input on the player's behalf: they render,
 * read the player's own state, or change how the client itself behaves.
 */
public abstract class Module {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    private final String name;
    private final String description;
    private final Category category;
    private final boolean defaultEnabled;
    private boolean enabled;
    private int bind = -1;
    private boolean contested;
    private final List<Setting<?>> settings = new ArrayList<>();

    protected Module(String name, String description, Category category, boolean defaultEnabled) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.defaultEnabled = defaultEnabled;
        this.enabled = false;
    }

    protected <S extends Setting<?>> S add(S s) {
        settings.add(s);
        return s;
    }

    /**
     * Marks the module as one that some servers rule against. Such modules ship OFF and show a
     * single muted line in their expanded settings - nothing more.
     */
    protected void contested() { this.contested = true; }

    public boolean isContested() { return contested; }

    public String name() { return name; }

    public String id() { return name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_'); }

    public String description() { return description; }

    public Category category() { return category; }

    public boolean defaultEnabled() { return defaultEnabled; }

    public boolean isEnabled() { return enabled; }

    public List<Setting<?>> settings() { return Collections.unmodifiableList(settings); }

    public int bind() { return bind; }

    public void setBind(int key) { this.bind = key; }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        try {
            if (on) onEnable();
            else onDisable();
        } catch (RuntimeException ex) {
            dev.xsoz.client.XsozClient.LOG.warn("Module {} failed to {}: {}", name, on ? "enable" : "disable", ex.toString());
        }
        dev.xsoz.client.config.ConfigManager.markDirty();
    }

    /** Marks the module on without running onEnable (its effect is already in place). */
    protected void silentlyEnable() { this.enabled = true; }

    /** Applies persisted state without running side effects twice. */
    public void restoreEnabled(boolean on) { setEnabled(on); }

    protected void onEnable() { }

    protected void onDisable() { }

    /**
     * Called once the game has finished starting (options and window exist). Mod init runs before
     * GameOptions is built, so modules that change video options apply themselves here.
     */
    public void onClientReady() { }

    /** Called every client tick while enabled, BEFORE the player ticks (input-shaping modules). */
    public void onPreTick() { }

    /** Called every client tick while enabled (end of tick). */
    public void onTick() { }

    /** A short status shown beside the name in the module list, or null. */
    public String suffix() { return null; }

    protected boolean inGame() { return mc.player != null && mc.world != null; }
}
