package dev.xsoz.client.trainer.crystal;

import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.bot.FreeRoamConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * The Learn tab: lessons grouped by kind of PvP, each in the order it should be learnt. A lesson can
 * link to a drill that practises it, to a Free Roam fight, and to a tutorial that shows it in slow
 * motion with your own keys on screen.
 *
 * <p>Written for someone who has never heard the PvP words: every term is explained the first
 * time it shows up, in plain words.
 */
public final class Lessons {
    private Lessons() { }

    public enum Category {
        BASICS("Basics", "Before any PvP: moving, your hotbar, and how this trainer works."),
        CRYSTAL("Crystal PvP", "End crystals, anchors, totems and pearls."),
        SWORD("Sword PvP", "Swords, axes and shields."),
        MACE("Mace PvP", "Wind charges, elytra and mace smashes."),
        UHC("UHC", "No natural healing: bows, rods, lava, water and golden apples.");

        public final String title;
        public final String detail;

        Category(String t, String d) {
            title = t;
            detail = d;
        }
    }

    /**
     * @param drill    the drill that practises it (or null)
     * @param fight    a Free Roam setup to practise it against bots (or null)
     * @param tutorial the tutorial that shows it (or null)
     */
    public record Lesson(Category category, String title, String summary, List<String> points, DrillDef drill,
                         FreeRoamConfig.Fight fight, Tutorials.Id tutorial) {
    }

    private static Lesson l(Category c, String title, String summary, DrillDef drill, FreeRoamConfig.Fight fight, Tutorials.Id tut, String... points) {
        return new Lesson(c, title, summary, List.of(points), drill, fight, tut);
    }

    public static final List<Lesson> ALL = List.of(
            // ------------------------------------------------------------------ basics
            l(Category.BASICS, "Minecraft basics", "Everything you need before any PvP. Skip this if you already play.", null, null, null,
                    "Move with W A S D, jump with Space, sneak with Shift, and sprint with Ctrl (or turn on Toggle Sprint in the options).",
                    "Look around with the mouse. Left-click hits and breaks things. Right-click places blocks and uses items.",
                    "The 9 boxes at the bottom of the screen are your hotbar. Press 1 to 9 (or scroll) to pick one.",
                    "Next to your hotbar is your offhand slot. Whatever is in it stays in your other hand.",
                    "A Totem of Undying saves you from dying once. It gets used up, so you need another one ready.",
                    "Golden apples (gapples) heal you and give you extra yellow hearts. Hold right-click to eat one. It takes about 1.6 seconds."),
            l(Category.BASICS, "How the trainer works", "How to use this trainer, step by step.", null, null, null,
                    "Make a practice world: Singleplayer > Create New World > More > World Type: Superflat. Drills only run in your own worlds.",
                    "Open Training and start with the first drill. Every drill explains what to do before it starts.",
                    "Practise at any speed. 'Natural' speeds up when you do well and slows down when you miss.",
                    "When you feel ready, take the Level Up test. Pass it once and it stays passed.",
                    "Passing drills unlocks the next ones and raises your rank.",
                    "Tutorials show a move in slow motion with your own keys on screen, then let you try it yourself."),

            // ------------------------------------------------------------------ crystal PvP
            l(Category.CRYSTAL, "Your hotbar and keys", "Set up your hotbar once and keep it that way.", DrillDef.KEYBIND_REFLEX, null, null,
                    "Always keep a totem in your offhand. If something would kill you while you hold one, it saves you.",
                    "Put each item in the same hotbar slot every time. Your fingers learn where things are, so you stop looking down.",
                    "Combo Binds (Mods > Combat) lets one key switch to an item and use it straight away.",
                    "A steady frame rate matters more than a high one. If your game stutters, turn the render distance down."),
            l(Category.CRYSTAL, "Grabbing the right item", "Pick the item you need without looking for it.", DrillDef.LAYOUT_RECALL, null, null,
                    "Think of the item and press its key. Don't search the hotbar with your eyes.",
                    "To refill a slot, open your inventory, put your mouse over the item and press that slot's number key. It's much faster than dragging."),
            l(Category.CRYSTAL, "Placing and breaking crystals", "This is the main move in crystal PvP. Everything else builds on it.", DrillDef.CRYSTAL_CYCLE, null,
                    Tutorials.Id.CRYSTAL_BASICS,
                    "A crystal can only be placed on obsidian or bedrock, and the block above has to be empty.",
                    "Right-click to place it, then left-click it straight away to blow it up. Keep your mouse still between the two clicks.",
                    "Don't left-click empty air. A missed click stops your attacks for a moment.",
                    "Fast players place and break in 2 to 4 ticks. A tick is 1/20 of a second."),
            l(Category.CRYSTAL, "Getting your totem back", "When a totem saves you, your offhand is empty. Fill it again before anything else.",
                    DrillDef.RETOTEM, null, Tutorials.Id.RETOTEM,
                    "When a totem saves you, it gets used up. Players call this a 'pop'.",
                    "Right after a pop, put a new totem in your offhand. Do that before you place another crystal.",
                    "Aim for less than 6 ticks (0.3 seconds) from the pop to the new totem.",
                    "When the fight calms down, move more totems into your hotbar so you don't run out."),
            l(Category.CRYSTAL, "Eating gapples safely", "Golden apples win long fights, but only if nobody hits you while you eat.", DrillDef.SAFE_GAPPLE, null, null,
                    "Eating takes about 1.6 seconds, and you walk slowly while you eat.",
                    "Get some distance first, or get behind a block, then eat.",
                    "Eat right after you take a hit, not right before one."),
            l(Category.CRYSTAL, "Keeping the right distance", "How far you stand decides who takes more damage from the same crystal.", DrillDef.RANGE_CONTROL, null, null,
                    "Place crystals when you're 3 to 6 blocks away from them. That's where they hurt them a lot and you only a little.",
                    "Closer than 2 blocks, your own crystals hurt you badly.",
                    "If they run at you, back up. If they back away, walk after them or throw a pearl to catch up.",
                    "Height matters too. A crystal lower than their feet is partly blocked by the ground."),
            l(Category.CRYSTAL, "Choosing where to place a crystal", "Where the crystal goes decides how much damage it does.", DrillDef.CRYSTAL_SPOT, null, null,
                    "An explosion only fully hurts what it can 'see'. Blocks between the crystal and a player soak up part of the damage.",
                    "The best spot is close to them, with nothing between the crystal and their body, and something between it and yours.",
                    "Blow it up from behind a block, or from farther away than they are."),
            l(Category.CRYSTAL, "Players hiding in holes", "Someone standing in a hole is safe from crystals at their feet.", DrillDef.FACE_PLACE, null, Tutorials.Id.SURROUND,
                    "A 'hole' is a spot with blocks on all four sides of your feet. Players also make one by placing obsidian around themselves. That's called surrounding.",
                    "Crystals at foot level do almost nothing to someone in a hole.",
                    "Put a block of obsidian on top of their surround and a crystal on that, so it blows up at their head. This is called face placing.",
                    "Other ways in: an anchor above their head, or mining one of the blocks around them with a pickaxe."),
            l(Category.CRYSTAL, "Anchors", "Respawn anchors are a second kind of explosive, and they work where crystals can't.", DrillDef.ANCHOR_CHAIN, null, Tutorials.Id.ANCHOR,
                    "Outside the Nether, a respawn anchor blows up when you use it.",
                    "Place the anchor, right-click it with glowstone to charge it, then right-click it again with any other item to blow it up.",
                    "Anchors can go anywhere you can place a block, even above someone's head while they sit in a hole."),
            l(Category.CRYSTAL, "Shields", "A raised shield blocks explosions coming from the front.", DrillDef.SHIELD_READ, null, null,
                    "When their shield is up, a crystal in front of them does nothing. Move to their side or behind them first.",
                    "An anchor behind someone who is blocking is the usual way to hurt them."),
            l(Category.CRYSTAL, "Using pearls", "Pearls get you out of bad fights and back into good ones.", DrillDef.PEARL_AIM, null, null,
                    "If they throw a pearl to get away, throw one to where theirs lands, or walk there before they can heal.",
                    "Low on health and out of totems? Pearl away and heal. Getting away isn't losing.",
                    "Every distance needs a different angle. Practise the angles on the Pearl Aim pads."),
            l(Category.CRYSTAL, "When to attack", "Some moments give you free damage. Learn to spot them.", null, FreeRoamConfig.Fight.CRYSTAL, null,
                    "Right after they pop a totem, their offhand is empty for a moment. Crystal them again straight away.",
                    "If you see them throwing XP bottles, they're fixing their armour. It's at its weakest right then, so go in.",
                    "Backing off right after they pop is the most common way to lose a fight you were winning."),
            l(Category.CRYSTAL, "Armour and mending", "In crystal fights your armour breaks before your health runs out.", null, null, null,
                    "Blast Protection makes crystals hurt you a lot less.",
                    "To repair armour with Mending, look straight down and throw XP bottles at your feet. Only do it when nobody is close.",
                    "The live coach tells you when a piece of armour drops under 25%. Get away, repair it, then come back."),
            l(Category.CRYSTAL, "Hit crystal", "Knock them into the air with your sword, then crystal them before they land.", DrillDef.HIT_CRYSTAL, null, Tutorials.Id.HIT_CRYSTAL,
                    "A sprint hit knocks a player up and back. While they're in the air, nothing at their feet protects them.",
                    "Hit, then place and break a crystal next to them before they land. You have about half a second."),
            l(Category.CRYSTAL, "Double tap", "Two explosions on one player, timed so both count.", DrillDef.DOUBLE_TAP, null, Tutorials.Id.CRYSTAL_DTAP,
                    "After a player gets hurt, for the next 10 ticks (half a second) a new hit only counts for the damage above the last one. A second crystal inside that time is mostly wasted.",
                    "So place the next crystal straight away, but blow it up right as that half second ends."),
            l(Category.CRYSTAL, "Anchor double tap", "Blow up two anchors in the same spot, one right after the other.", null, null, Tutorials.Id.ANCHOR_DTAP,
                    "When your anchor explodes, you can still click the spot where it was and place a new anchor there, floating in the air.",
                    "Charge it with glowstone and blow it up again: two big explosions in a row."),

            // ------------------------------------------------------------------ sword PvP
            l(Category.SWORD, "Sword basics", "Wait for your sword to charge. Clicking fast does almost no damage.", null, FreeRoamConfig.Fight.SWORD, null,
                    "After each swing your sword needs about 0.6 seconds to charge again. The little bar under your crosshair shows it.",
                    "Hitting before it's full does a lot less damage and barely knocks them back.",
                    "Hit, wait for the charge, hit again. A steady rhythm beats fast clicking."),
            l(Category.SWORD, "Critical hits", "Hit someone while you're falling and you do 50% more damage.", null, FreeRoamConfig.Fight.SWORD, Tutorials.Id.CRIT,
                    "Jump, then hit them on the way down. Little stars come off them when it works.",
                    "A critical hit can't happen while you sprint, so it won't knock them back as far.",
                    "Jump so that you come down right when your sword is fully charged."),
            l(Category.SWORD, "Sprint hits and W-tapping", "A hit while sprinting knocks them back further. Let go of W to do it again.", null, FreeRoamConfig.Fight.SWORD, Tutorials.Id.WTAP,
                    "Your first hit while sprinting knocks them back extra far.",
                    "That hit stops your sprint. Let go of W for a split second and press it again to sprint again before your next hit. That's a W-tap.",
                    "If they keep getting knocked back, they can't reach you to hit back."),
            l(Category.SWORD, "Distance in sword fights", "Your sword reaches 3 blocks. Use that to hit first.", null, FreeRoamConfig.Fight.SWORD, null,
                    "Step into range right as your sword finishes charging, so your hit lands first.",
                    "Moving left and right (strafing) makes you harder to hit.",
                    "If they keep hitting you first, back up a little and let them come to you."),
            l(Category.SWORD, "Blocking with a shield", "A raised shield blocks hits from the front.", null, FreeRoamConfig.Fight.SWORD, null,
                    "Hold right-click to raise your shield. You walk slowly while it's up.",
                    "The shield takes a moment to come up, so raise it before the hit comes, not when it lands.",
                    "You can't attack with the shield up. Lower it to hit back."),
            l(Category.SWORD, "Breaking shields with an axe", "An axe hit turns off their shield for 5 seconds.", null, FreeRoamConfig.Fight.SWORD, Tutorials.Id.SHIELD_BREAK,
                    "When they block, switch to your axe and hit the shield. It goes down for 5 seconds.",
                    "Switch back to your sword and attack while they can't block.",
                    "Against someone with a shield, always keep an axe in your hotbar."),
            l(Category.SWORD, "Healing in sword fights", "Eat when they can't reach you.", DrillDef.SAFE_GAPPLE, FreeRoamConfig.Fight.SWORD, null,
                    "Golden apples heal you and give you extra hearts, but eating takes 1.6 seconds.",
                    "Get away first, by running or with a pearl, then eat.",
                    "Watch their health too. If they run off to eat, chase them."));

    /** The lessons of one category, in order. */
    public static List<Lesson> of(Category c) {
        List<Lesson> out = new ArrayList<>();
        for (Lesson l : ALL) if (l.category() == c) out.add(l);
        return out;
    }
}
