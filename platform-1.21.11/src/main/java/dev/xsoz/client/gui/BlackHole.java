package dev.xsoz.client.gui;

import dev.xsoz.client.render.Theme;
import java.util.Random;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;

/**
 * The menu's black hole: a field of stars, a glowing disk of dust spinning around a black centre,
 * a bright ring of bent light around it, and Minecraft blocks orbiting, tumbling and slowly falling
 * in (new ones drift in from the edge). Drawn with plain fills and item icons - cheap on any GPU.
 *
 * <p>{@link #zoom} pulls the camera into the hole (the intro's last shot); {@link #power} fades it
 * in from nothing (the intro's first shot).
 */
public final class BlackHole {
    private static final int STARS = 140;
    private static final int DUST = 520;
    private static final int BLOCKS = 26;
    private static final Item[] BLOCK_ITEMS = {
            Items.OBSIDIAN, Items.GRASS_BLOCK, Items.STONE, Items.DIRT, Items.OAK_LOG, Items.DIAMOND_BLOCK, Items.TNT, Items.GLOWSTONE,
            Items.RESPAWN_ANCHOR, Items.CRYING_OBSIDIAN, Items.BEDROCK, Items.SAND, Items.CRAFTING_TABLE, Items.FURNACE, Items.CHEST,
            Items.GOLD_BLOCK, Items.EMERALD_BLOCK, Items.NETHERRACK, Items.END_STONE, Items.BOOKSHELF, Items.PUMPKIN, Items.MELON,
            Items.BIRCH_LOG, Items.REDSTONE_BLOCK, Items.LAPIS_BLOCK, Items.END_CRYSTAL, Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL};

    private final Random rng = new Random(42);
    private final float[] starX = new float[STARS];
    private final float[] starY = new float[STARS];
    private final float[] starP = new float[STARS];
    private final float[] dustA = new float[DUST];
    private final float[] dustR = new float[DUST];
    private final float[] dustS = new float[DUST];
    private final float[] bA = new float[BLOCKS];
    private final float[] bR = new float[BLOCKS];
    private final float[] bSpin = new float[BLOCKS];
    private final float[] bTilt = new float[BLOCKS];
    private final ItemStack[] bItem = new ItemStack[BLOCKS];
    private long last = System.nanoTime();
    /** 0..1: how formed the hole is (the intro fades it in). */
    public float power = 1f;
    /** 1 = normal; bigger pulls the camera into the hole. */
    public float zoom = 1f;

    public BlackHole() {
        for (int i = 0; i < STARS; i++) {
            starX[i] = rng.nextFloat();
            starY[i] = rng.nextFloat();
            starP[i] = rng.nextFloat() * 6.28f;
        }
        for (int i = 0; i < DUST; i++) {
            dustA[i] = rng.nextFloat() * 6.2832f;
            dustR[i] = 1.25f + (float) Math.pow(rng.nextFloat(), 1.6) * 1.9f;
            dustS[i] = 0.6f + rng.nextFloat() * 0.8f;
        }
        for (int i = 0; i < BLOCKS; i++) respawnBlock(i, true);
    }

    private void respawnBlock(int i, boolean anywhere) {
        bA[i] = rng.nextFloat() * 6.2832f;
        bR[i] = anywhere ? 1.6f + rng.nextFloat() * 2.6f : 3.6f + rng.nextFloat() * 0.8f;
        bSpin[i] = rng.nextFloat() * 6.28f;
        bTilt[i] = (rng.nextFloat() - 0.5f) * 0.5f;
        bItem[i] = new ItemStack(BLOCK_ITEMS[rng.nextInt(BLOCK_ITEMS.length)]);
    }

    /** Draws the whole scene into the box (usually the full screen); cx, cy is the hole's centre. */
    public void render(DrawContext c, int w, int h, float cx, float cy, float mouseX, float mouseY) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        double t = System.currentTimeMillis() / 1000.0;
        // space
        c.fillGradient(0, 0, w, h, 0xFF05060A, 0xFF0B0D14);
        float par = 0.012f;
        float ox = (mouseX - w / 2f) * -par;
        float oy = (mouseY - h / 2f) * -par;
        for (int i = 0; i < STARS; i++) {
            float tw = 0.45f + 0.55f * (float) Math.sin(t * 1.7 + starP[i]);
            int a = (int) (tw * 200 * power);
            int sx = (int) (starX[i] * w + ox * (0.4f + starP[i] / 10f));
            int sy = (int) (starY[i] * h + oy * (0.4f + starP[i] / 10f));
            int size = starP[i] > 5.5f ? 2 : 1;
            c.fill(sx, sy, sx + size, sy + size, (a << 24) | 0xDDE6FF);
        }
        float unit = Math.min(w, h) * 0.16f * zoom;
        float hole = unit * 0.82f * power;
        // soft glow behind
        glow(c, cx, cy, unit * 3.2f, Theme.accent(), 0.05f * power);
        glow(c, cx, cy, unit * 2.0f, 0xFFFF9A3C, 0.07f * power);
        // the disk: back half first, then the hole, then the front half over it
        float tilt = 0.28f;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < DUST; i++) {
                if (pass == 0) dustA[i] += dt * dustS[i] * 0.9f / (dustR[i] * dustR[i]) * 2.2f;
                float s = MathHelper.sin(dustA[i]);
                boolean front = s > 0;
                if (front != (pass == 1)) continue;
                float r = dustR[i] * unit;
                float x = cx + MathHelper.cos(dustA[i]) * r;
                float y = cy + s * r * tilt;
                float heat = 1f - (dustR[i] - 1.25f) / 1.9f;
                int col = lerpColor(0xFFB2481A, 0xFFFFF1C9, heat);
                int a = (int) ((80 + heat * 150) * power * (front ? 1f : 0.7f));
                int size = heat > 0.7f ? 2 : 1;
                c.fill((int) x, (int) y, (int) x + size, (int) y + size, (a << 24) | (col & 0xFFFFFF));
            }
            if (pass == 0) {
                drawBlocks(c, cx, cy, unit, tilt, false, dt);
                // the shadow and the bright ring of bent light
                ring(c, cx, cy, hole * 1.18f, 2.2f, 0xFFFFD9A0, 0.55f * power);
                ring(c, cx, cy, hole * 1.06f, 1.6f, 0xFFFFFFFF, 0.8f * power);
                disc(c, cx, cy, hole, 0xFF000000);
            }
        }
        drawBlocks(c, cx, cy, unit, tilt, true, 0f);
        // light bent over the top of the hole
        for (int i = 0; i < 90; i++) {
            double a = Math.PI + i / 89.0 * Math.PI;
            float r = hole * 1.32f;
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r * 0.92f;
            int al = (int) (120 * power * (0.6 + 0.4 * Math.sin(t * 2 + i * 0.2)));
            c.fill((int) x, (int) y, (int) x + 2, (int) y + 1, (al << 24) | 0xFFC98A);
        }
    }

    /** Blocks orbit in the disk plane, tumble, and spiral slowly in; behind the hole first, then in front. */
    private void drawBlocks(DrawContext c, float cx, float cy, float unit, float tilt, boolean frontPass, float dt) {
        for (int i = 0; i < BLOCKS; i++) {
            if (!frontPass) {
                bA[i] += dt * 0.55f / Math.max(0.6f, bR[i]);
                bR[i] -= dt * 0.035f * (1.2f / Math.max(0.5f, bR[i]));
                bSpin[i] += dt * 1.4f;
                if (bR[i] < 0.95f) respawnBlock(i, false);
            }
            float s = MathHelper.sin(bA[i]);
            boolean front = s > 0;
            if (front != frontPass) continue;
            float r = bR[i] * unit;
            float x = cx + MathHelper.cos(bA[i]) * r;
            float y = cy + s * r * tilt + bTilt[i] * r;
            // closer = bigger; falling in = smaller and squeezed
            float scale = (0.75f + 0.45f * s) * Math.min(1.6f, unit / 60f) * MathHelper.clamp((bR[i] - 0.95f) / 0.5f, 0.15f, 1f) * power;
            if (scale < 0.05f) continue;
            var m = c.getMatrices();
            m.pushMatrix();
            m.translate(x, y);
            m.rotate(bSpin[i] * 0.4f);
            m.scale(scale, scale);
            c.drawItem(bItem[i], -8, -8);
            m.popMatrix();
        }
    }

    private static void disc(DrawContext c, float cx, float cy, float r, int color) {
        int ri = (int) r;
        for (int dy = -ri; dy <= ri; dy++) {
            int half = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            c.fill((int) cx - half, (int) cy + dy, (int) cx + half, (int) cy + dy + 1, color);
        }
    }

    private static void ring(DrawContext c, float cx, float cy, float r, float thick, int color, float alpha) {
        int n = Math.max(60, (int) (r * 6));
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            int al = (int) (alpha * 255 * (0.75 + 0.25 * Math.sin(a * 3 + System.currentTimeMillis() / 500.0)));
            c.fill((int) x, (int) y, (int) (x + thick), (int) (y + thick), (MathHelper.clamp(al, 0, 255) << 24) | (color & 0xFFFFFF));
        }
    }

    private static void glow(DrawContext c, float cx, float cy, float r, int color, float strength) {
        for (int i = 7; i >= 1; i--) {
            int rr = (int) (r * i / 7);
            dev.xsoz.client.render.Gfx.round(c, (int) cx - rr, (int) cy - rr, rr * 2, rr * 2, Math.min(rr, 80),
                    Theme.withAlpha(color, (int) (255 * strength / 7)));
        }
    }

    private static int lerpColor(int a, int b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
