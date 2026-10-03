package dev.xsoz.client.gui;

import dev.xsoz.client.XsozClient;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.trainer.crystal.TrainerScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * The Xsoz main menu: a black hole in the middle of the screen with Minecraft blocks orbiting and
 * falling in ({@link BlackHole}). Drawn with plain fills and item icons - cheap on any GPU.
 */
public final class XsozTitleScreen extends Screen {
    /** Set by the "Classic menu" button so the vanilla title screen is allowed through once. */
    public static boolean allowVanillaOnce;
    /** Set by the intro: the menu fades in out of black. */
    public static long fadeFromBlackAt;

    private final Anim intro = new Anim(0f, 4f);

    public XsozTitleScreen() {
        super(Text.literal("Xsoz Client"));
    }

    @Override
    protected void init() {
        int bw = 170;
        int bh = 20;
        int x = Math.max(24, width / 10);
        int y = Math.max(96, height / 2 - 20);
        int gap = 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Singleplayer"), b -> client.setScreen(new SelectWorldScreen(this)))
                .dimensions(x, y, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Multiplayer"), b -> client.setScreen(new MultiplayerScreen(this)))
                .dimensions(x, y + gap, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Crystal Trainer"), b -> client.setScreen(new TrainerScreen(this)))
                .dimensions(x, y + gap * 2, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Mods"), b -> client.setScreen(new dev.xsoz.client.gui.ModsScreen(this)))
                .dimensions(x, y + gap * 3, bw, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Options"), b -> client.setScreen(new OptionsScreen(this, client.options)))
                .dimensions(x, y + gap * 4, bw / 2 - 2, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Quit"), b -> client.scheduleStop())
                .dimensions(x + bw / 2 + 2, y + gap * 4, bw / 2 - 2, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Classic menu"), b -> {
            allowVanillaOnce = true;
            client.setScreen(new TitleScreen());
        }).dimensions(width - 90, height - 34, 82, 16).build());
        intro.target(1f);
    }

    private final BlackHole hole = new BlackHole();

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        int railW = Math.max(24, width / 10) + 190;
        // the hole sits in the middle of the space right of the buttons
        float cx = Math.max(width / 2f, (railW - 10 + width) / 2f);
        hole.render(c, width, height, cx, height / 2f, mouseX, mouseY);
        // left rail, so the buttons stay readable over the disk
        Gfx.hGradient(c, 0, 0, railW, height, 0xD0090A0C, 0x00090A0C);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        renderContent(c);
        long since = System.currentTimeMillis() - fadeFromBlackAt;
        if (since < 1500) c.fill(0, 0, width, height, ((int) (255 * (1f - since / 1500f)) << 24));
    }

    private void renderContent(DrawContext c) {
        float a = Anim.easeOutCubic(intro.get());
        int x = Math.max(24, width / 10);
        int logoY = Math.max(4, Math.max(96, height / 2 - 20) - 90) + Math.round((1 - a) * 10);
        Gfx.display(c, "XSOZ", x - 1, logoY, 4.2f, Theme.alpha(Theme.accent(), a));
        Gfx.text(c, "C L I E N T", x + 2, logoY + 44, Theme.alpha(Theme.TEXT_2, a));
        Gfx.rect(c, x + 2, logoY + 57, 40, 2, Theme.alpha(Theme.accent(), a));
        Gfx.text(c, "Competitive PvP, coached.", x + 2, logoY + 64, Theme.alpha(Theme.TEXT_3, a));

        // account chip (top right)
        String name = dev.xsoz.client.training.TrainingStore.progress().displayName();
        if (name == null) name = client.getSession() == null ? "Player" : client.getSession().getUsername();
        name = "Hi, " + name;
        int cw = Gfx.width(name) + 26;
        Gfx.round(c, width - cw - 10, 10, cw, 18, 9, 0xC0151719);
        Gfx.round(c, width - cw - 4, 15, 8, 8, 4, Theme.accent());
        Gfx.text(c, name, width - cw + 10, 15, Theme.TEXT);

        // footer
        Gfx.text(c, "Xsoz Client " + XsozClient.VERSION + "  -  Minecraft 1.21.11", 8, height - 22, Theme.TEXT_3);
        Gfx.text(c, "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.", 8, height - 12, 0xFF5D677C);
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean shouldPause() { return false; }
}
