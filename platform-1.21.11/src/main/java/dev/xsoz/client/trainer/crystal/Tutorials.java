package dev.xsoz.client.trainer.crystal;

import dev.xsoz.client.training.KitSpec;

/** The tutorials the Learn tab can start: what each one is called and which kit it uses. */
public final class Tutorials {
    private Tutorials() { }

    public enum Id {
        CRYSTAL_BASICS("Placing and breaking a crystal", "Watch a crystal go down and blow up, one click at a time.", "Place a crystal and break it within a second", KitSpec.Mode.CRYSTAL),
        RETOTEM("Getting your totem back", "Watch a totem pop, then the next totem go into the offhand.", "Get a new totem into your offhand after each pop", KitSpec.Mode.CRYSTAL),
        SURROUND("Surrounding yourself", "Watch obsidian go down on all four sides of your feet.", "Put obsidian on all four sides of your feet", KitSpec.Mode.CRYSTAL),
        ANCHOR("Using an anchor", "Watch an anchor get placed, charged and blown up.", "Place an anchor, charge it and blow it up", KitSpec.Mode.CRYSTAL),
        ANCHOR_DTAP("Anchor double tap", "Watch two anchors blow up in the same spot, one right after the other.", "Blow up two anchors in the same spot, quickly", KitSpec.Mode.CRYSTAL),
        CRYSTAL_DTAP("Double tap", "Watch two crystals hit one jumping player, timed so both count.", "Two crystal blasts in one jump, at least 10 ticks apart", KitSpec.Mode.CRYSTAL),
        HIT_CRYSTAL("Hit crystal", "Watch a sword hit knock the dummy up, then a crystal catch it in the air.", "Hit the dummy up, then crystal it before it lands", KitSpec.Mode.CRYSTAL),
        CRIT("Critical hits", "Watch a jump, then a hit on the way down.", "Land critical hits on the dummy", KitSpec.Mode.SWORD),
        WTAP("Sprint hits and W-tapping", "Watch two sprint hits in a row, with W let go in between.", "Land two sprint hits in a row", KitSpec.Mode.SWORD),
        SHIELD_BREAK("Breaking a shield", "Watch an axe knock out a shield, then a sword hit while it's down.", "Break the shield with your axe, then hit with your sword", KitSpec.Mode.SWORD);

        public final String title;
        public final String summary;
        public final String goal;
        public final KitSpec.Mode kit;

        Id(String title, String summary, String goal, KitSpec.Mode kit) {
            this.title = title;
            this.summary = summary;
            this.goal = goal;
            this.kit = kit;
        }
    }
}
