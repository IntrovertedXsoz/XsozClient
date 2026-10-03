package dev.xsoz.client.modules.visuals;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.BoolSetting;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Hide particles - crystal explosions, totem pops, crit sparks... or any single particle type the
 * game has. Purely visual: nothing about the game changes, the particles are just not drawn.
 */
public final class Particles extends Module {
    public static Particles INSTANCE;
    /** Read by the ParticleManager mixin on the render thread. */
    private static volatile Set<Identifier> hidden = Set.of();
    private static volatile boolean hideAll;

    private final BoolSetting all = add(new BoolSetting("Hide every particle", "No particles at all", false));
    private final Map<BoolSetting, List<String>> groups = new LinkedHashMap<>();
    private final Map<BoolSetting, Identifier> single = new LinkedHashMap<>();

    public Particles() {
        super("Particles", "Hide explosion flashes, totem pops, crits - or any particle you pick", Category.VISUALS, false);
        INSTANCE = this;
        group("Explosions", "Crystal, anchor and TNT blasts (the big white flash)", true,
                "explosion", "explosion_emitter", "flash", "gust", "gust_emitter_large", "gust_emitter_small", "small_gust");
        group("Totem pops", "The green/yellow totem burst", false, "totem_of_undying");
        group("Hit sparks", "Crits, sharpness sparks, damage hearts, sweeps", false,
                "crit", "enchanted_hit", "damage_indicator", "sweep_attack");
        group("Potion swirls", "Effect swirls around players", false, "entity_effect", "effect", "instant_effect", "witch");
        group("Smoke and fire", "Smoke, flames, lava pops, campfire smoke", false,
                "smoke", "large_smoke", "flame", "soul_fire_flame", "lava", "campfire_cosy_smoke", "campfire_signal_smoke", "white_smoke");
        group("Block bits", "Breaking-block and falling dust bits", false, "block", "falling_dust", "dust_pillar", "block_crumble");
        group("Portal and ender", "Portal swirls and end rod sparkles", false, "portal", "reverse_portal", "end_rod");
        group("Weather", "Rain splashes and drips", false, "rain", "splash", "dripping_water", "falling_water");
        // and every single particle type, for anything not covered above
        List<Identifier> ids = new ArrayList<>();
        for (ParticleType<?> t : Registries.PARTICLE_TYPE) ids.add(Registries.PARTICLE_TYPE.getId(t));
        ids.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        for (Identifier id : ids) {
            BoolSetting b = add(new BoolSetting("Hide " + id.getPath().replace('_', ' '), "Hide the " + id + " particle", false));
            single.put(b, id);
        }
        rebuild();
    }

    private void group(String name, String desc, boolean def, String... paths) {
        BoolSetting b = add(new BoolSetting("Hide " + name.toLowerCase(java.util.Locale.ROOT), desc, def));
        groups.put(b, List.of(paths));
    }

    /** Recomputes the hidden set (cheap; called every tick while enabled). */
    private void rebuild() {
        Set<Identifier> s = new HashSet<>();
        for (var e : groups.entrySet()) if (e.getKey().on()) for (String p : e.getValue()) s.add(Identifier.ofVanilla(p));
        for (var e : single.entrySet()) if (e.getKey().on()) s.add(e.getValue());
        hidden = s;
        hideAll = all.on();
    }

    @Override
    public void onPreTick() {
        if (isEnabled()) rebuild();
    }

    @Override
    protected void onEnable() { rebuild(); }

    @Override
    protected void onDisable() {
        hidden = Set.of();
        hideAll = false;
    }

    /** True when particles of this type must not be drawn. */
    public static boolean hides(ParticleType<?> type) {
        if (hideAll) return true;
        Set<Identifier> h = hidden;
        if (h.isEmpty()) return false;
        Identifier id = Registries.PARTICLE_TYPE.getId(type);
        return id != null && h.contains(id);
    }
}
