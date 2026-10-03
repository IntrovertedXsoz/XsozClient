package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.MathHelper;

/**
 * What a tutorial shows on screen: the caption, your own keys lighting up as the demo "presses"
 * them, the slow-motion / paused badge, the fade between the demo and your turn, and your
 * progress while you try. Also steers the camera during the demo. Client thread.
 */
public final class TutorialHud {
    private TutorialHud() { }

    public static void render(DrawContext c, TutorialDrill t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int sw = c.getScaledWindowWidth();
        int sh = c.getScaledWindowHeight();
        steerCamera(mc, t);
        if (t.waiting && mc.options.jumpKey.wasPressed()) t.continuePressed = true;

        // slow motion / paused badge
        if (t.phase == TutorialDrill.Phase.DEMO) {
            String badge = t.waiting ? "PAUSED" : t.rate < 19.5f ? String.format(Locale.ROOT, "SLOW MOTION  %d%%", Math.round(t.rate / 20f * 100)) : "DEMO";
            int bw = Gfx.widthBold(badge) + 16;
            Gfx.round(c, sw / 2 - bw / 2, 6, bw, 16, 8, t.waiting ? 0xE06E4B12 : 0xD0101114);
            Gfx.textBold(c, badge, sw / 2 - Gfx.widthBold(badge) / 2, 10, t.waiting ? 0xFFFFD166 : Theme.accent());
        } else if (t.phase == TutorialDrill.Phase.TRY) {
            String head = "YOUR TURN  " + t.hits() + " / 3";
            List<String> goal = Gfx.wrap(t.id.goal, Math.min(sw - 60, 300));
            int bw = 40;
            for (String g : goal) bw = Math.max(bw, Gfx.width(g));
            bw = Math.max(bw, Gfx.widthBold(head)) + 24;
            int bh = 18 + goal.size() * 10;
            Gfx.round(c, sw / 2 - bw / 2, 4, bw, bh, 6, 0xF0101114);
            Gfx.textBold(c, head, sw / 2 - Gfx.widthBold(head) / 2, 8, Theme.accent());
            for (int i = 0; i < goal.size(); i++) Gfx.textCentered(c, goal.get(i), sw / 2, 20 + i * 10, Theme.TEXT_2);
            long age = System.currentTimeMillis() - t.feedbackAt;
            long show = 2600 + 250L * t.feedback.split(" ").length; // long enough to read
            if (!t.feedback.isEmpty() && age < show) {
                float a = age < show - 600 ? 1f : 1f - (age - (show - 600)) / 600f;
                Gfx.textCentered(c, t.feedback, sw / 2, 30 + goal.size() * 10, Theme.alpha(t.feedbackColor, a));
            }
        }

        // keys
        List<TutorialDrill.KeyCue> row = t.keyRow;
        if (!row.isEmpty() && t.phase == TutorialDrill.Phase.DEMO) {
            long now = System.currentTimeMillis();
            int gap = 6;
            int total = 0;
            int[] widths = new int[row.size()];
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(34, Math.max(Gfx.widthBold(keyName(mc, row.get(i))), Gfx.width(row.get(i).label())) + 14);
                total += widths[i] + gap;
            }
            int x = sw / 2 - total / 2;
            int y = sh - 98;
            for (int i = 0; i < row.size(); i++) {
                boolean down = i < t.pressedUntil.length && t.pressedUntil[i] > now;
                int w = widths[i];
                int dy = down ? 2 : 0;
                Gfx.round(c, x, y + 3, w, 22, 5, 0xC0000000);
                Gfx.round(c, x, y + dy, w, 22, 5, down ? Theme.accent() : 0xE0202228);
                Gfx.outline(c, x, y + dy, w, 22, 1, down ? 0xFFFFFFFF : Theme.LINE);
                String name = keyName(mc, row.get(i));
                Gfx.textBold(c, name, x + w / 2 - Gfx.widthBold(name) / 2, y + 7 + dy, down ? 0xFF101114 : Theme.TEXT);
                Gfx.text(c, row.get(i).label(), x + w / 2 - Gfx.width(row.get(i).label()) / 2, y + 28, down ? Theme.TEXT : Theme.TEXT_3);
                x += w + gap;
            }
        }

        // caption
        String cap = t.caption;
        if (cap != null && !cap.isEmpty() && t.phase != TutorialDrill.Phase.TRY) {
            int w = Math.min(sw - 40, 340);
            List<String> lines = Gfx.wrap(cap, w - 24);
            int h = lines.size() * 11 + (t.waiting ? 26 : 14);
            int x = sw / 2 - w / 2;
            int y = 28;
            Gfx.shadow(c, x, y, w, h, 5, 1f);
            Gfx.round(c, x, y, w, h, 6, 0xEE101114);
            Gfx.round(c, x, y, 3, h, 1, Theme.accent());
            int ly = y + 8;
            for (String l : lines) {
                Gfx.textCentered(c, l, sw / 2, ly, Theme.TEXT);
                ly += 11;
            }
            if (t.waiting) {
                float pulse = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 220.0);
                String k = mc.options.jumpKey.getBoundKeyLocalizedText().getString();
                Gfx.textCentered(c, "Press " + k + " to carry on", sw / 2, ly + 3, Theme.alpha(Theme.accent(), pulse));
            }
        }

        // the fade between the demo and your turn
        if (t.fade > 0.01f) c.fill(0, 0, sw, sh, ((int) (MathHelper.clamp(t.fade, 0f, 1f) * 255) << 24));
    }

    /** During the demo the camera eases toward what the demo looks at. */
    private static void steerCamera(MinecraftClient mc, TutorialDrill t) {
        if (!t.camOn || mc.player == null || t.phase != TutorialDrill.Phase.DEMO) return;
        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        float dy = MathHelper.wrapDegrees(t.camYaw - yaw);
        float k = 0.12f;
        mc.player.setYaw(yaw + dy * k);
        mc.player.setPitch(pitch + (t.camPitch - pitch) * k);
        mc.player.setHeadYaw(mc.player.getYaw());
    }

    private static String keyName(MinecraftClient mc, TutorialDrill.KeyCue cue) {
        KeyBinding kb = switch (cue.key()) {
            case ATTACK -> mc.options.attackKey;
            case USE -> mc.options.useKey;
            case JUMP -> mc.options.jumpKey;
            case FORWARD -> mc.options.forwardKey;
            case SPRINT -> mc.options.sprintKey;
            case SWAP -> mc.options.swapHandsKey;
            case HOTBAR -> mc.options.hotbarKeys[Math.max(0, Math.min(8, cue.slot()))];
        };
        String s = kb.getBoundKeyLocalizedText().getString();
        if (s.equalsIgnoreCase("Left Button")) return "Left click";
        if (s.equalsIgnoreCase("Right Button")) return "Right click";
        if (s.equalsIgnoreCase("Middle Button")) return "Middle click";
        return s;
    }
}
