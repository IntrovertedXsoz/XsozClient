package dev.xsoz.client.modules.client;

import dev.xsoz.client.gui.HudEditorScreen;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.render.Fonts;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.setting.ActionSetting;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.ColorSetting;

/** The look of the client itself: accent colour, fonts, menu and button skin. */
public final class Interface extends Module {
    public static Interface INSTANCE;

    public final ColorSetting accent = add(new ColorSetting("Accent", "Accent colour used everywhere", 0x4CD765)
            .onChange(this::apply));
    public final BoolSetting customFont = add(new BoolSetting("Custom font", "Inter for all client text (off: Minecraft font)", true)
            .onChange(this::apply));
    public final BoolSetting mainMenu = add(new BoolSetting("Custom main menu", "Replace the title screen with the Xsoz menu", true));
    public final BoolSetting buttons = add(new BoolSetting("Custom buttons", "Restyle every button in the game", true));

    public Interface() {
        super("Interface", "Accent colour, fonts, main menu and button style", Category.CLIENT, true);
        INSTANCE = this;
        add(new ActionSetting("Edit HUD layout", "Drag HUD elements around", () -> mc.setScreen(new HudEditorScreen(mc.currentScreen))));
    }

    private void apply() {
        Theme.setAccent(accent.argb());
        Fonts.enabled = customFont.on();
    }

    @Override
    protected void onEnable() { apply(); }

    @Override
    protected void onDisable() {
        Theme.setAccent(Theme.ACCENT);
        Fonts.enabled = true;
    }

    public static boolean customMainMenu() { return INSTANCE == null || (INSTANCE.isEnabled() && INSTANCE.mainMenu.on()); }

    public static boolean customButtons() { return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.buttons.on(); }
}
