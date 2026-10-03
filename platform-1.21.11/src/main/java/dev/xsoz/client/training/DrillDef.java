package dev.xsoz.client.training;

import java.util.List;
import java.util.Locale;

/**
 * The Crystal PvP training curriculum: every drill, its tier, what must be passed first, and the
 * two ends of its speed range - {@code easy} (slider at 0%) and {@code limit} (the human limit
 * Level Up uses). Pure data; the rules are unit tested. The research behind every number is in
 * docs/trainer-design-process.md.
 */
public enum DrillDef {
    // ---- Tier 1: Foundations (any order) ------------------------------------------------------
    KEYBIND_REFLEX(1, "Keybind Reflex",
            "Your hotbar turns to red wool. Every 2-8 s one slot turns green - press that slot's key before it turns back.",
            List.of("Keep your eyes on the crosshair; read the hotbar with peripheral vision.",
                    "Don't look at your keyboard. Your fingers must know where each slot lives.",
                    "Wrong slot counts as a miss - accuracy first, then speed."),
            "Green window", "ms", 3000, 450, 40, 20, 18,
            "18/20 hits with the window at 450 ms, median reaction 380 ms or faster, no sound cue"),
    LAYOUT_RECALL(1, "Layout Recall",
            "Your real kit in your hotbar. Something happens - an icon, a sound, a fight moment - and you grab the item it calls for before it's too late.",
            List.of("This is the step after Keybind Reflex: you react to WHAT you need, not where it is.",
                    "In a fight you don't read: you hear the pop, see the obsidian, feel the armour going. Train that.",
                    "Level 3 is the real thing: whole situations on fresh terrain, item AND move."),
            "Cue window", "ms", 3500, 700, 40, 20, 18,
            "18/20 hits with a 700 ms window, median 600 ms or faster"),
    // ---- Tier 2: Core mechanics -----------------------------------------------------------------
    CRYSTAL_CYCLE(2, "Crystal Cycle",
            "A 3x3 obsidian pad in front of you. Place a crystal, break it, again - each cycle is timed in ticks.",
            List.of("Keep the crosshair parked on the obsidian, never in the air (a miss locks attacks for 10 ticks).",
                    "Place and break with two different fingers so both can land in the same tick.",
                    "Turn on Crystal Optimizer if your servers allow it."),
            "Cycle limit", "ticks", 20, 4, 40, 20, 18,
            "18/20 cycles inside 4 ticks and an average of 3.2 ticks or less"),
    RETOTEM(2, "Retotem",
            "At random moments you pop a totem. Get the next one into your offhand before the window closes.",
            List.of("Keep a totem in your swap slot. The pop sound and the empty offhand are your cue.",
                    "Retotem before you do anything else - before you crystal again."),
            "Retotem window", "ticks", 40, 6, 25, 12, 11,
            "11/12 retotems inside 6 ticks (300 ms)"),
    HOTBAR_REFILL(2, "Hotbar Refill",
            "A slot of your kit is emptied into your inventory. Put it back in the right slot, fast.",
            List.of("Open the inventory, hover the item, press the slot's number key - don't drag.",
                    "Refill between trades, never mid-exchange."),
            "Refill window", "s", 10, 1.6, 20, 10, 9,
            "9/10 refills inside 1.6 s"),
    // ---- Tier 3: Combinations ---------------------------------------------------------------------
    OBBY_CRYSTAL(3, "Obsidian + Crystal",
            "A lime block appears in the floor. Place obsidian on it, then a crystal on that obsidian.",
            List.of("Obsidian and crystal must land at least one tick apart - the obsidian has to exist first.",
                    "Aim once: the crystal goes on the block you just placed, don't re-aim.",
                    "Everything you place is cleared after each spot, so the arena is always clean."),
            "Time per spot", "s", 5, 0.7, 40, 15, 13,
            "13/15 spots inside 0.7 s"),
    ANCHOR_CHAIN(3, "Anchor Chain",
            "A lime block appears in the floor. Place an anchor on it, charge it with glowstone, detonate it - without blowing yourself up.",
            List.of("Three presses, three ticks: anchor, glowstone, then anything that isn't glowstone.",
                    "Every detonation is simulated against YOU: too close, slightly too close or just right.",
                    "Detonate from max reach, or put a block between you and the anchor first (safe anchor)."),
            "Time per anchor", "s", 6, 0.85, 30, 10, 9,
            "9/10 anchors inside 0.85 s, none of them too close"),
    SAFE_GAPPLE(3, "Gapple Timing",
            "A bot chases you across a big arena with its sword. Break away - sprint, use cover, pearl - and only eat your gapple once you have space. Every gapple is judged: clean (8+ blocks, not hit while eating) or caught.",
            List.of("Never eat with someone on top of you: eating takes 1.6 s, slows you down and ties up your hands.",
                    "Pearl over or behind cover, then eat. Distance first, food second.",
                    "Level 3: the bot resets the same way - learn to chase someone who is running to eat."),
            "Chaser reaction", "ms", 650, 150, 20, 10, 8,
            "8/10 clean resets against a 150 ms chaser"),
    HIT_CRYSTAL(3, "Hit-Crystal",
            "Sword-hit the dummy so it flies up, then crystal it before it lands. Timed from your hit to the blast.",
            List.of("A target in the air takes the whole blast - no ground block hiding its legs - and can't step out of line.",
                    "Hit, switch, place, break: the swap to the crystal is where the time goes.",
                    "Sprint-hit for more knockback (and a longer air time)."),
            "Hit to blast", "ms", 1300, 400, 30, 12, 10,
            "10/12 air crystals inside 400 ms of the hit"),
    PEARL_AIM(3, "Pearl Aim",
            "Stand still and land pearls on pads at semi-close, normal, far and very far range. Pads close fast up close and stay longer far away. How fast can you land 70?",
            List.of("Pearls follow the same arc every time: learn one pitch per distance band.",
                    "You can't move in this drill - aim is all that counts.",
                    "Throw with your pearl key while already looking at the right pitch."),
            "Throw window", "s", 6, 1.5, 70, 20, 16,
            "16/20 pads hit, with a 1.5 s window at normal range"),
    // ---- Tier 4: Fight IQ ---------------------------------------------------------------------------
    CRYSTAL_SPOT(4, "Crystal Placement",
            "A random fight scene and a dummy wearing your kit. Find the spot - existing obsidian or one you build - that hurts the dummy most and you least, crystal it, and break it from safety. Judged against the best spot an auto-crystal would pick.",
            List.of("Damage falls off with distance and is blocked by blocks: closer AND in line of sight.",
                    "Stone stops one blast and then breaks - obsidian keeps protecting you. Don't trust stone cover.",
                    "You're judged on placement (net damage vs the best spot), safety and speed - the feedback says which one cost you."),
            "Time per scene", "s", 9, 2.5, 30, 12, 10,
            "10/12 scenes at 85%+ of the best net damage, broken safely, inside 2.5 s"),
    FACE_PLACE(4, "Build & Crystal",
            "No obsidian anywhere. Build the spot yourself - the obsidian + crystal that hurts the dummy most and you least - then crystal it and break it safely.",
            List.of("A crystal sits ON its block: only bodies above that block's top can see the blast. Build at their feet level, not higher.",
                    "Closer to them than to you, with nothing solid in between.",
                    "In a hole, full armour shrugs off almost everything - find the angle that still hurts (face place)."),
            "Time per scene", "s", 12, 3.5, 20, 8, 7,
            "7/8 scenes at 85%+ of the best net damage inside 3.5 s"),
    DOUBLE_TAP(4, "Double Tap",
            "The dummy gets launched. Land two crystal blasts in one air time - the second at least 10 ticks after the first, before it lands.",
            List.of("A second blast inside the 10-tick hurt window only deals the difference - it's mostly wasted.",
                    "Blast early in the jump, keep the next crystal ready, blast again as the window opens (the bar turns green).",
                    "Pre-place the second crystal while the first one is flying."),
            "Launch every", "s", 4.0, 1.6, 20, 10, 8,
            "8/10 true double taps"),
    SHIELD_READ(4, "Shield Read",
            "The dummy blocks with its shield, facing you. A raised shield stops any blast from its front half - anchor it from the side or back, and don't take more than you deal.",
            List.of("A shield blocks explosions from the front half only.",
                    "Move to its side or back, then place-charge-detonate.",
                    "When the shield drops (level 2), the front is open - punish it fast."),
            "Time per scene", "s", 12, 3.5, 25, 10, 8,
            "8/10 unblocked anchors at 80%+ of the best net damage inside 3.5 s"),
    RANGE_CONTROL(4, "Range Control",
            "The dummy strafes around. Stay 3-6 blocks from it - crystal range - the whole time.",
            List.of("Mirror its strafes; close in when it backs off, back off when it rushes.",
                    "SnapTap helps direction changes."),
            "Dummy speed", "b/s", 2.0, 5.6, 6, 3, 3,
            "3/3 runs of 20 s in range 75%+ of the time at sprint speed"),
    SPARRING(4, "Sparring Bot",
            "A real crystal PvP fight against a bot wearing your kit: it walks, places obsidian, crystals you, swings its sword and pops totems. Pop all of its totems before it pops all of yours.",
            List.of("Everything is real: crystals explode, blocks break, you pop real totems (the world is restored afterwards).",
                    "Keep your offhand totem up - the bot punishes a missing totem immediately.",
                    "Bot reaction is how long it waits before each action: lower = harder."),
            "Bot reaction", "ms", 700, 150, 5, 3, 2,
            "Win 2 of 3 rounds against a 150 ms bot"),
    // ---- Not in the curriculum: the open practice area (Free Roam tab) ---------------------------
    FREE_ROAM(0, "Free Roam",
            "Fight bots as long as you like: pick the mode, the map, how many bots, the teams and how good they are.",
            List.of("Bots know every technique - crystals, anchors, swords, maces, pearls, gapples, mending. The level is how fast and clean they play.",
                    "Drop any item to stop."),
            "Bot reaction", "ms", 700, 90, 1, 1, 1,
            "Free play - nothing to pass"),
    // ---- Not in the curriculum: tutorials from the Learn tab --------------------------------------
    TUTORIAL(0, "Tutorial",
            "Watch it in slow motion with your keys on screen, then try it yourself.",
            List.of("Press Space to carry on when it pauses to explain."),
            "Tries", "", 1, 2, 3, 3, 3,
            "Do it 3 times");

    public final int tier;
    public final String title;
    public final String summary;
    public final List<String> tips;
    public final String speedLabel;
    public final String unit;
    public final double easy;
    public final double limit;
    public final int reps;
    public final int levelUpReps;
    public final int levelUpMinHits;
    public final String passRule;
    /** Practice ends on this many hits instead of after a fixed number of tries (0 = off). */
    public final int goalHits;

    DrillDef(int tier, String title, String summary, List<String> tips, String speedLabel, String unit,
             double easy, double limit, int reps, int levelUpReps, int levelUpMinHits, String passRule) {
        this.tier = tier;
        this.title = title;
        this.summary = summary;
        this.tips = tips;
        this.speedLabel = speedLabel;
        this.unit = unit;
        this.easy = easy;
        this.limit = limit;
        this.reps = reps;
        this.levelUpReps = levelUpReps;
        this.levelUpMinHits = levelUpMinHits;
        this.passRule = passRule;
        this.goalHits = name().equals("PEARL_AIM") ? 70 : name().equals("TUTORIAL") ? 3 : 0;
    }

    /** One difficulty level: a short name and exactly what changes. */
    public record Level(String name, String detail) {
    }

    /** Difficulty levels (1-3). Level 1 is the drill as described; Level Up tests every level in turn. */
    public List<Level> levels() {
        return switch (this) {
            case KEYBIND_REFLEX -> List.of(
                    new Level("Clean", "One green slot at a time."),
                    new Level("Decoys", "Yellow slots flash too. Only green counts - pressing a yellow one is a miss."),
                    new Level("Memory", "Decoys, and the green slot only flashes for 150 ms: remember where it was."));
            case LAYOUT_RECALL -> List.of(
                    new Level("Icons", "The item flashes as an icon - nothing to read. Every kit item, XP bottles included."),
                    new Level("Cues", "Real cues, no icons: a totem pop, the armour-break sound, a hit with no golden hearts, obsidian by the enemy, the enemy under a roof, an uncharged anchor, the enemy in your face or in the open, being boxed in."),
                    new Level("Situations", "Whole fight moments on fresh terrain: pearl down off a ledge (into the hole for a bonus), get out and mend, retotem then gapple, anchor someone under a roof, escape a trap."));
            case CRYSTAL_CYCLE -> List.of(
                    new Level("One pad", "One 3x3 obsidian pad in front of you."),
                    new Level("Moving pad", "The pad jumps to a new place every 5 cycles."),
                    new Level("Flick", "Two pads, left and right: alternate between them every cycle."));
            case RETOTEM -> List.of(
                    new Level("Single pops", "One pop at a time; your hotbar totem is refilled for you."),
                    new Level("Double pops", "Pops can come in pairs 0.5-1 s apart, like a real crystal chain."),
                    new Level("Full restock", "Double pops, and your hotbar totem is NOT refilled - restock it from your inventory between pops."));
            case HOTBAR_REFILL -> List.of(
                    new Level("One slot", "One kit slot empties at a time."),
                    new Level("Two slots", "Two slots empty at once - refill both."),
                    new Level("Messy inventory", "Three slots at once, hidden among junk items in your inventory."));
            case OBBY_CRYSTAL -> List.of(
                    new Level("Flat", "The lime block is on the floor, 2-4 blocks in front of you."),
                    new Level("Heights", "Spots can be raised or sunk a block and up to 5 blocks away, in any direction."),
                    new Level("Moving", "Two lime blocks that keep sliding around at a human pace - crystal both while they move."));
            case ANCHOR_CHAIN -> List.of(
                    new Level("Flat", "One spot on the floor. \"Slightly too close\" still counts; \"too close\" is a miss."),
                    new Level("Safe anchors", "Only \"just right\" counts: detonate from range or behind a block."),
                    new Level("Chain", "Two spots in a row, at different heights; both must be safe."));
            case SAFE_GAPPLE -> List.of(
                    new Level("Open field", "A casual chaser on open grass. Outrun it or pearl away, then eat."),
                    new Level("Ruins", "A good chaser, and ruins to break line of sight around."),
                    new Level("It resets too", "A pro chaser that also pearls off and eats when it's low - and chases you down when you do."));
            case PEARL_AIM -> List.of(
                    new Level("All ranges", "Pads at semi-close, normal, far and very far range. Pads are 5 blocks wide."),
                    new Level("Small pads", "Same ranges, pads are 3 blocks wide."),
                    new Level("Pillar", "You stand on a 10-block pillar; small pads down on the ground, some in pits or on steps."));
            case HIT_CRYSTAL -> List.of(
                    new Level("Obsidian ready", "Obsidian already next to the dummy."),
                    new Level("Build it", "No obsidian: place it while they fly - hit, obsidian, crystal, break."),
                    new Level("Moving", "No obsidian, and the dummy strafes side to side between your hits."));
            case DOUBLE_TAP -> List.of(
                    new Level("Standing", "The dummy is launched from where it stands, obsidian beside it."),
                    new Level("Faster", "Launches come quicker after you land - less time to set up."),
                    new Level("Strafing", "The dummy strafes between launches - re-aim for every jump."));
            case CRYSTAL_SPOT -> List.of(
                    new Level("Open", "A few walls, the dummy stands in the open."),
                    new Level("Cover", "More walls, mixed stone and obsidian; the dummy uses cover and stands on different heights."),
                    new Level("Moving", "Dense cover, and the dummy steps to a new spot every 3 s - the best spot changes with it."));
            case FACE_PLACE -> List.of(
                    new Level("Open ground", "The dummy stands in the open; a few stone and crying-obsidian walls."),
                    new Level("Cover", "The dummy hugs walls - the easy angles are blocked."),
                    new Level("In a hole", "The dummy sits in a crying-obsidian hole. Damage is low against full armour: find the best angle anyway."));
            case SHIELD_READ -> List.of(
                    new Level("Wall of shield", "The dummy blocks the whole time, standing still, always facing you."),
                    new Level("Shield drops", "It lowers its shield for short moments and swings its sword at you when it does. It turns its shield toward anchors and crystals you place and backs away from them - slowly, a raised shield slows you down."),
                    new Level("Moving and hiding", "Big arena with walls: it moves between cover, backs into walls so you can't get behind it, loses you behind walls and looks for you, drops its shield and hits."));
            case RANGE_CONTROL -> List.of(
                    new Level("Strafing", "The dummy walks, strafes, rushes and backs off."),
                    new Level("Sprint-jumping", "It also sprints, jumps and looks around at random."),
                    new Level("Slippery", "Sprint-jumps, sharp direction changes and sudden 6-block dashes like a pearl."));
            case SPARRING -> List.of(
                    new Level("Sparring partner", "Plays like a casual player who knows every trick (crystals, anchors, sword hits, pearls, gapples, mending, surrounds) but picks weaker spots. 3 totems."),
                    new Level("Fighter", "Like a pro: near-best spots, times crystals for your hurt window, leads your movement. 5 totems."),
                    new Level("Tryhard", "Always the best spot, retotems in 2 ticks, never wastes a move. 8 totems."));
            case FREE_ROAM -> java.util.Arrays.stream(dev.xsoz.client.training.bot.BotLevel.values())
                    .map(l -> new Level(l.title, l.detail)).toList();
            case TUTORIAL -> List.of(new Level("Watch and try", "A slow-motion demo, then three tries of your own."));
        };
    }

    /** What has to be PASSED (Level Up) before this drill can be practised. */
    public List<DrillDef> prerequisites() {
        return switch (this) {
            case KEYBIND_REFLEX, LAYOUT_RECALL -> List.of();
            case CRYSTAL_CYCLE, RETOTEM -> List.of(KEYBIND_REFLEX);
            case HOTBAR_REFILL, PEARL_AIM -> List.of(LAYOUT_RECALL);
            case OBBY_CRYSTAL, ANCHOR_CHAIN -> List.of(CRYSTAL_CYCLE);
            case SAFE_GAPPLE -> List.of(RETOTEM, HOTBAR_REFILL);
            case CRYSTAL_SPOT, RANGE_CONTROL -> List.of(OBBY_CRYSTAL);
            case HIT_CRYSTAL -> List.of(CRYSTAL_CYCLE);
            case DOUBLE_TAP -> List.of(HIT_CRYSTAL);
            case FACE_PLACE -> List.of(CRYSTAL_SPOT);
            case SHIELD_READ -> List.of(ANCHOR_CHAIN);
            case SPARRING -> List.of(OBBY_CRYSTAL);
            case FREE_ROAM, TUTORIAL -> List.of();
        };
    }

    /** Speed value for slider position p (0..1); p = 1 is the Level Up value. */
    public double value(double p) {
        p = Math.max(0, Math.min(1, p));
        return easy + (limit - easy) * p;
    }

    public String format(double v) {
        boolean whole = unit.equals("ms") || unit.equals("ticks");
        String n = whole ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
        return n + " " + unit;
    }

    public static List<DrillDef> tier(int t) {
        return java.util.Arrays.stream(values()).filter(d -> d.tier == t).toList();
    }

    public static final String[] TIER_NAMES = {"", "Foundations", "Core mechanics", "Combinations", "Fight IQ"};
}
