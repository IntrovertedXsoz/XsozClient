package dev.xsoz.client.modules.performance;

import com.google.gson.JsonObject;
import dev.xsoz.client.config.ConfigManager;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.BoolSetting;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.particle.ParticlesMode;

/**
 * A PvP performance preset over vanilla's own video options: everything that costs frames and
 * does not help you fight is turned down. Your previous values are remembered and put back when
 * the module is turned off. Render and simulation distance are never raised.
 */
public final class FpsBoost extends Module {
    private final BoolSetting clouds = add(new BoolSetting("No clouds", "Clouds off", true));
    private final BoolSetting particles = add(new BoolSetting("Fewer particles", "Particles: decreased (explosions stay readable)", true));
    private final BoolSetting shadows = add(new BoolSetting("No entity shadows", "Entity shadows off", true));
    private final BoolSetting biomeBlend = add(new BoolSetting("No biome blend", "Biome blend radius 0", true));
    private final BoolSetting vignette = add(new BoolSetting("No vignette", "Vignette off", true));
    private final BoolSetting menuBlur = add(new BoolSetting("No menu blur", "Menu background blur off (expensive on integrated GPUs)", true));
    private final BoolSetting weather = add(new BoolSetting("Less weather", "Smaller rain/snow radius", true));
    private final BoolSetting distance = add(new BoolSetting("Cap render distance", "Render distance at most 8 chunks (never raised)", false));

    public FpsBoost() {
        super("FPS Boost", "One-click PvP video preset; turns everything that costs frames down", Category.PERFORMANCE, false);
    }

    @Override
    protected void onEnable() {
        GameOptions o = mc.options;
        if (o == null) return;
        JsonObject prev = new JsonObject();
        prev.addProperty("clouds", o.getCloudRenderMode().getValue().name());
        prev.addProperty("particles", o.getParticles().getValue().name());
        prev.addProperty("shadows", o.getEntityShadows().getValue());
        prev.addProperty("biomeBlend", o.getBiomeBlendRadius().getValue());
        prev.addProperty("vignette", o.getVignette().getValue());
        prev.addProperty("menuBlur", o.getMenuBackgroundBlurriness().getValue());
        prev.addProperty("weather", o.getWeatherRadius().getValue());
        prev.addProperty("distance", o.getViewDistance().getValue());
        if (!ConfigManager.extra().has("fpsBoostRestore")) ConfigManager.extra().add("fpsBoostRestore", prev);

        if (clouds.on()) o.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        if (particles.on() && o.getParticles().getValue() == ParticlesMode.ALL) o.getParticles().setValue(ParticlesMode.DECREASED);
        if (shadows.on()) o.getEntityShadows().setValue(false);
        if (biomeBlend.on()) o.getBiomeBlendRadius().setValue(0);
        if (vignette.on()) o.getVignette().setValue(false);
        if (menuBlur.on()) o.getMenuBackgroundBlurriness().setValue(0);
        if (weather.on()) o.getWeatherRadius().setValue(Math.min(o.getWeatherRadius().getValue(), 3));
        if (distance.on() && o.getViewDistance().getValue() > 8) o.getViewDistance().setValue(8);
        o.write();
    }

    @Override
    protected void onDisable() {
        GameOptions o = mc.options;
        if (o == null || !ConfigManager.extra().has("fpsBoostRestore")) return;
        JsonObject p = ConfigManager.extra().getAsJsonObject("fpsBoostRestore");
        try {
            o.getCloudRenderMode().setValue(CloudRenderMode.valueOf(p.get("clouds").getAsString()));
            o.getParticles().setValue(ParticlesMode.valueOf(p.get("particles").getAsString()));
            o.getEntityShadows().setValue(p.get("shadows").getAsBoolean());
            o.getBiomeBlendRadius().setValue(p.get("biomeBlend").getAsInt());
            o.getVignette().setValue(p.get("vignette").getAsBoolean());
            o.getMenuBackgroundBlurriness().setValue(p.get("menuBlur").getAsInt());
            o.getWeatherRadius().setValue(p.get("weather").getAsInt());
            o.getViewDistance().setValue(p.get("distance").getAsInt());
            o.write();
        } catch (RuntimeException ex) {
            dev.xsoz.client.XsozClient.LOG.warn("FPS Boost could not restore every option: {}", ex.toString());
        }
        ConfigManager.extra().remove("fpsBoostRestore");
        ConfigManager.markDirty();
    }

    @Override
    public void onClientReady() {
        if (!ConfigManager.extra().has("fpsBoostRestore")) onEnable();
    }

    /** Persisted "enabled" must not re-apply the preset on every launch (values already set). */
    @Override
    public void restoreEnabled(boolean on) {
        if (on && ConfigManager.extra().has("fpsBoostRestore")) {
            silentlyEnable();
        } else {
            super.restoreEnabled(on);
        }
    }
}
