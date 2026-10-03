package dev.xsoz.client.module;

public enum Category {
    TRAINER("Trainer", "Coaching that makes you better"),
    COMBAT("Combat", "PvP mechanics and feedback"),
    MOVEMENT("Movement", "Input and movement helpers"),
    VISUALS("Visuals", "What you see"),
    HUD("HUD", "On-screen read-outs"),
    PERFORMANCE("Performance", "Frames, memory and the OS"),
    CLIENT("Client", "Menu, theme and fonts");

    public final String title;
    public final String blurb;

    Category(String title, String blurb) {
        this.title = title;
        this.blurb = blurb;
    }
}
