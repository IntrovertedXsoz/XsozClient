package dev.xsoz.client.gui;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.update.Updates;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * The update window over the menu: asks before anything is downloaded ("Yes", "No", "Don't ask
 * again"), shows the download, then says to restart. Also the answer to Settings' "Check for
 * updates" (checking, up to date, or couldn't check).
 */
public final class UpdateScreen extends Screen {
    private enum State { CHECKING, PROMPT, DOWNLOADING, DONE, ERROR, UP_TO_DATE }

    private final Screen parent;
    private State state;
    private Updates.Release release;
    private String message = "";

    /** Asks about an update that was found. */
    public UpdateScreen(Screen parent, Updates.Release release) {
        super(Text.literal("Update"));
        this.parent = parent;
        this.release = release;
        this.state = State.PROMPT;
    }

    private UpdateScreen(Screen parent) {
        super(Text.literal("Update"));
        this.parent = parent;
        this.state = State.CHECKING;
    }

    /** Settings' "Check for updates": checks now (whatever "Don't ask again" says) and shows the answer. */
    public static UpdateScreen checkNow(Screen parent) {
        UpdateScreen s = new UpdateScreen(parent);
        Updates.check(r -> {
            if (r.release() != null) {
                s.release = r.release();
                s.to(State.PROMPT);
            } else if (r.error() != null) {
                s.message = "Couldn't check for updates: " + r.error() + ".";
                s.to(State.ERROR);
            } else {
                s.message = "You're on the newest version for the " + Updates.channel().title + " channel (" + Updates.current().pretty() + ").";
                s.to(State.UP_TO_DATE);
            }
        });
        return s;
    }

    private void to(State s) {
        state = s;
        if (client != null) clearAndInit();
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        switch (state) {
            case CHECKING -> out.add("Checking for updates...");
            case PROMPT -> {
                if (release.switchBack()) {
                    out.add("Version " + release.version().pretty() + " is the newest on the " + Updates.channel().title + " channel.");
                    out.add("Your version is " + Updates.current().pretty() + ", from the " + Updates.current().kind().title + " channel.");
                    out.add("Would you like to switch to it?");
                } else {
                    out.add("An update for version " + release.version().pretty() + " was detected.");
                    out.add("Your version is " + Updates.current().pretty() + ".");
                    out.add("Would you like to update?");
                }
            }
            case DOWNLOADING -> out.add("Downloading version " + release.version().pretty() + "...");
            case DONE -> {
                out.add("Version " + release.version().pretty() + " is ready.");
                out.add("Restart Minecraft to use it.");
            }
            case ERROR, UP_TO_DATE -> out.add(message);
        }
        return out;
    }

    private int panelW() { return Math.min(340, width - 32); }

    private int panelH() { return 52 + wrapped().size() * 11 + (state == State.DOWNLOADING ? 14 : 0) + (state == State.CHECKING ? 0 : 28); }

    private List<String> wrapped() {
        List<String> out = new ArrayList<>();
        for (String l : lines()) out.addAll(Gfx.wrap(l, panelW() - 28));
        return out;
    }

    @Override
    protected void init() {
        int pw = panelW();
        int px = (width - pw) / 2;
        int py = (height - panelH()) / 2;
        int by = py + panelH() - 30;
        switch (state) {
            case PROMPT -> {
                int bw = (pw - 28 - 12) / 3;
                addDrawableChild(ButtonWidget.builder(Text.literal("Yes"), b -> startDownload()).dimensions(px + 14, by, bw, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("No"), b -> close()).dimensions(px + 14 + bw + 6, by, bw, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Don't ask again"), b -> {
                    Updates.setAskOnStart(false);
                    close();
                }).dimensions(px + 14 + (bw + 6) * 2, by, bw, 20).build());
            }
            case DONE -> {
                int bw = (pw - 28 - 6) / 2;
                addDrawableChild(ButtonWidget.builder(Text.literal("Quit Minecraft"), b -> client.scheduleStop()).dimensions(px + 14, by, bw, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Later"), b -> close()).dimensions(px + 14 + bw + 6, by, bw, 20).build());
            }
            case ERROR -> {
                int bw = (pw - 28 - 6) / 2;
                addDrawableChild(ButtonWidget.builder(Text.literal("Open the download page"), b -> {
                    net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(Updates.RELEASES_PAGE));
                }).dimensions(px + 14, by, bw, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(px + 14 + bw + 6, by, bw, 20).build());
            }
            case UP_TO_DATE -> addDrawableChild(ButtonWidget.builder(Text.literal("OK"), b -> close()).dimensions(px + (pw - 80) / 2, by, 80, 20).build());
            default -> { }
        }
    }

    private void startDownload() {
        to(State.DOWNLOADING);
        Updates.install(release, err -> {
            if (err == null) to(State.DONE);
            else {
                message = "Couldn't update: " + err + ". You can download it from the releases page instead.";
                to(State.ERROR);
            }
        });
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // the menu stays behind it, dimmed (no second blur: one per frame)
        if (parent != null && parent.width == width && parent.height == height) parent.render(c, -1, -1, delta);
        else c.fill(0, 0, width, height, 0xFF0B0D12);
        c.fill(0, 0, width, height, 0xB0000000);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        int pw = panelW();
        int ph = panelH();
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;
        Gfx.shadow(c, px, py, pw, ph, 6, 1f);
        Gfx.round(c, px, py, pw, ph, 6, 0xFA111317);
        Gfx.outline(c, px, py, pw, ph, 1, Theme.LINE_SOFT);
        Gfx.boldScaled(c, state == State.UP_TO_DATE ? "Up to date" : state == State.ERROR ? "Update" : "Update available",
                px + 14, py + 12, 1.15f, Theme.TEXT);
        Gfx.rect(c, px + 14, py + 27, 30, 2, Theme.accent());
        int y = py + 36;
        for (String l : wrapped()) {
            Gfx.text(c, l, px + 14, y, Theme.TEXT_2);
            y += 11;
        }
        if (state == State.DOWNLOADING) {
            float p = Math.max(0f, Updates.progress);
            Gfx.round(c, px + 14, y + 4, pw - 28, 6, 3, Theme.SURFACE);
            Gfx.round(c, px + 14, y + 4, Math.max(6, Math.round((pw - 28) * p)), 6, 3, Theme.accent());
        }
        if (state == State.PROMPT && Updates.channel() != dev.xsoz.client.update.Channel.STABLE) {
            Gfx.text(c, Updates.channel().title + " channel", px + pw - 14 - Gfx.width(Updates.channel().title + " channel"), py + 14, Theme.TEXT_3);
        }
        for (var child : children()) {
            if (child instanceof net.minecraft.client.gui.Drawable d) d.render(c, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return state != State.DOWNLOADING; }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
