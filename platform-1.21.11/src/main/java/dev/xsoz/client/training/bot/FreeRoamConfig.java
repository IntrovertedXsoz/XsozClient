package dev.xsoz.client.training.bot;

import dev.xsoz.client.training.KitSpec;
import dev.xsoz.client.training.session.SkyArena;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Everything the player picks on the Free Roam page. Plain data (saved with Gson). */
public final class FreeRoamConfig {
    /** One thing the bots (and your kit) may use. Pick any mix. */
    public enum Ability {
        CRYSTALS("Crystals", "End crystals on obsidian - the core of crystal PvP."),
        ANCHORS("Anchors", "Respawn anchors charged with glowstone. They blow up outside the Nether."),
        SWORD("Sword", "Sword fighting: crits, sprint hits, combos."),
        AXE_SHIELD("Axe + shield", "Blocking with a shield, and an axe to break the other shield."),
        MACE("Mace", "Wind-charge jumps and mace smashes from above."),
        ELYTRA("Elytra", "Flying in with fireworks - with a mace, dives from the sky.");

        public final String title;
        public final String detail;

        Ability(String t, String d) {
            title = t;
            detail = d;
        }
    }

    /** Ready-made mixes (also what the Sparring and Gapple drills use). */
    public enum Fight {
        CRYSTAL("Crystal + Anchor", KitSpec.Mode.CRYSTAL, "Crystals, anchors, obsidian, totems, pearls - classic crystal PvP.", Ability.CRYSTALS, Ability.ANCHORS),
        SWORD("Sword", KitSpec.Mode.SWORD, "Swords, axes and shields. Crits, sprint hits, shield breaks, gapples.", Ability.SWORD, Ability.AXE_SHIELD),
        MACE("Mace + Elytra", KitSpec.Mode.MACE, "Wind-charge jumps, elytra dives and mace smashes, with a sword up close.", Ability.MACE, Ability.ELYTRA, Ability.SWORD),
        EVERYTHING("Everything", KitSpec.Mode.EVERYTHING, "Crystals, anchors, sword, mace and elytra - anything goes.", Ability.values());

        public final String title;
        public final KitSpec.Mode kit;
        public final String detail;
        private final Ability[] abilities;

        Fight(String t, KitSpec.Mode kit, String d, Ability... a) {
            title = t;
            this.kit = kit;
            detail = d;
            abilities = a;
        }

        public EnumSet<Ability> abilities() {
            EnumSet<Ability> s = EnumSet.noneOf(Ability.class);
            s.addAll(Arrays.asList(abilities));
            return s;
        }

        /** The ready-made mix that is exactly this set, or null. */
        public static Fight exactly(Set<Ability> set) {
            for (Fight f : values()) if (f.abilities().equals(set)) return f;
            return null;
        }

        /** The smallest ready-made mix that contains the whole set (its kit is the starting point). */
        public static Fight covering(Set<Ability> set) {
            for (Fight f : values()) if (f.abilities().containsAll(set)) return f;
            return EVERYTHING;
        }
    }

    /** How the bots get their personalities. */
    public enum PersonalityMode {
        MIXED("Mixed", "Every bot gets a random personality."),
        SAME("All the same", "Every bot has the personality you pick."),
        PICK("Pick each", "Choose a personality for each bot.");

        public final String title;
        public final String detail;

        PersonalityMode(String t, String d) {
            title = t;
            detail = d;
        }
    }

    public enum Terrain {
        STONE("Stone (no map)", "A flat, tall stone arena - nothing but you and them."),
        GRASS("Grass", "Flat grass and dirt. Every crystal digs a crater."),
        CRATERS("Craters", "A field already torn up by a fight: holes to crystal into, ledges to hide behind."),
        HILLS("Hills", "Rolling hills up to 7 blocks high - height and line of sight matter."),
        RUINS("Ruins", "Broken stone-brick walls, pillars and bunkers. Cover everywhere."),
        OBSIDIAN("Obsidian floor", "Crystals can go anywhere and nothing breaks. Pure crystal speed."),
        DESERT("Desert", "Sand dunes, sandstone ruins and cacti."),
        BIRCH("Birch forest", "Birch trees everywhere - break line of sight, fight around trunks."),
        SNOWY("Snowy taiga", "Snow, spruce trees and icy patches."),
        NETHER("Nether", "Netherrack, basalt pillars and soul sand - anchors feel right at home.");

        public final String title;
        public final String detail;

        Terrain(String t, String d) {
            title = t;
            detail = d;
        }

        /** What the ground under the map is made of. */
        public SkyArena.Theme theme() {
            return switch (this) {
                case STONE, RUINS, OBSIDIAN -> SkyArena.Theme.STONE;
                case DESERT -> SkyArena.Theme.DESERT;
                case SNOWY -> SkyArena.Theme.SNOWY;
                case NETHER -> SkyArena.Theme.NETHER;
                default -> SkyArena.Theme.GRASS;
            };
        }
    }

    public enum Size {
        SMALL("Small", 24), MEDIUM("Medium", 36), LARGE("Large", 52);

        public final String title;
        public final int radius;

        Size(String t, int r) {
            title = t;
            radius = r;
        }
    }

    public enum Teams {
        FFA("Free for all", "Everyone fights everyone - the bots fight each other too."),
        VS_YOU("Bots vs you", "Every bot is on one team, against you."),
        TEAMS("Teams", "You and your bot teammates against the other bots.");

        public final String title;
        public final String detail;

        Teams(String t, String d) {
            title = t;
            detail = d;
        }
    }

    /** What the bots and your kit use. Saved as a list (Gson can't build an EnumSet). */
    public List<Ability> abilities = new ArrayList<>(List.of(Ability.CRYSTALS, Ability.ANCHORS));
    public PersonalityMode personalityMode = PersonalityMode.MIXED;
    public Personality samePersonality = Personality.ALL_ROUNDER;
    /** PICK mode: one per bot slot. */
    public List<Personality> picks = new ArrayList<>();
    public Terrain terrain = Terrain.STONE;
    public Size size = Size.MEDIUM;
    public Teams teams = Teams.VS_YOU;
    /** Bots in total (enemies + allies). */
    public int bots = 1;
    /** Teams mode: how many of the bots fight on your side. */
    public int allies = 0;
    public int level = 3;
    /** Each bot a level around the chosen one (one lower, the same or one higher). */
    public boolean mixedLevels;
    /** Bots get one level better each time you kill one, one level easier each time you die. */
    public boolean adaptive;
    /** Watch mode: you fly around as a spectator and watch the bots fight (click one to see through its eyes). */
    public boolean watch;
    /** Reaction override in ms, or 0 for the level's own. */
    public int reactionMs = 0;

    public FreeRoamConfig copy() {
        FreeRoamConfig c = new FreeRoamConfig();
        c.abilities = new ArrayList<>(abilities());
        c.personalityMode = personalityMode;
        c.samePersonality = samePersonality;
        c.picks = new ArrayList<>(picks == null ? List.of() : picks);
        c.terrain = terrain;
        c.size = size;
        c.teams = teams;
        c.bots = bots;
        c.allies = allies;
        c.level = level;
        c.reactionMs = reactionMs;
        c.mixedLevels = mixedLevels;
        c.adaptive = adaptive;
        c.watch = watch;
        return c;
    }

    public EnumSet<Ability> abilities() {
        EnumSet<Ability> s = EnumSet.noneOf(Ability.class);
        if (abilities != null) for (Ability a : abilities) if (a != null) s.add(a);
        if (s.isEmpty()) s.addAll(Fight.CRYSTAL.abilities());
        return s;
    }

    public boolean has(Ability a) { return abilities().contains(a); }

    /** Turns one ability on or off (at least one stays on). */
    public void toggle(Ability a) {
        EnumSet<Ability> s = abilities();
        if (s.contains(a)) {
            if (s.size() > 1) s.remove(a);
        } else {
            s.add(a);
        }
        abilities = new ArrayList<>(s);
    }

    /** "Crystals + Anchors + Sword", or the mix's name. */
    public String abilitiesTitle() {
        EnumSet<Ability> s = abilities();
        Fight f = Fight.exactly(s);
        if (f != null) return f.title;
        List<String> n = new ArrayList<>();
        for (Ability a : s) n.add(a.title);
        return String.join(" + ", n);
    }

    /** The personality of bot i (0-based). */
    public Personality personalityOf(int i, java.util.Random rng) {
        PersonalityMode m = personalityMode == null ? PersonalityMode.MIXED : personalityMode;
        EnumSet<Ability> a = abilities();
        List<Personality> fit = Personality.forAbilities(a);
        Personality p = switch (m) {
            case SAME -> samePersonality == null ? Personality.ALL_ROUNDER : samePersonality;
            case PICK -> picks != null && i < picks.size() && picks.get(i) != null ? picks.get(i) : Personality.ALL_ROUNDER;
            case MIXED -> fit.get(rng.nextInt(fit.size()));
        };
        return p.fits(a) ? p : Personality.ALL_ROUNDER; // an elytra lover in a crystal fight plays all-round
    }

    /** PICK mode: the list, grown to the bot count. */
    public List<Personality> picks() {
        if (picks == null) picks = new ArrayList<>();
        while (picks.size() < 8) picks.add(Personality.values()[picks.size() % Personality.values().length]);
        return picks;
    }

    /** Allies actually used (Teams mode keeps at least one enemy). */
    public int alliesUsed() { return teams == Teams.TEAMS ? Math.max(0, Math.min(allies, bots - 1)) : 0; }

    /** The level of bot i: the chosen one, or (mixed) one around it. Hacker stays Hacker-only. */
    public BotLevel levelOf(int i, java.util.Random rng) {
        BotLevel base = BotLevel.of(level);
        if (!mixedLevels || adaptive || base == BotLevel.HACKER) return base;
        int n = Math.max(1, Math.min(BotLevel.GODLIKE.n(), level + rng.nextInt(3) - 1));
        return BotLevel.of(n);
    }

    /** Team of bot i (0-based). The player is team 0. */
    public int teamOf(int i) {
        return switch (teams) {
            case FFA -> i + 1;
            case VS_YOU -> watch ? i + 1 : 1;
            case TEAMS -> i < alliesUsed() ? 0 : 1;
        };
    }
}
