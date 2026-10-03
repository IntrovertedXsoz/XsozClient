package dev.xsoz.client.trainer.crystal;

/** The seven crystal PvP skills every fight is graded on. */
public enum Skill {
    CYCLE("Cycle speed", "How fast you place and break each crystal"),
    TOTEM("Totem discipline", "How fast your offhand totem comes back after a pop"),
    SPACING("Spacing", "Holding the 2.5-6 block crystal range"),
    SAFETY("Self-damage control", "Not blowing yourself up"),
    PUNISH("Punishing", "Turning their pops, repairs and eats into damage"),
    ADAPT("Adapting", "Answering shields and pearls"),
    TRADING("Trading", "Damage and pops you deal versus take");

    public final String title;
    public final String blurb;

    Skill(String title, String blurb) {
        this.title = title;
        this.blurb = blurb;
    }
}
