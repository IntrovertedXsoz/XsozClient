package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.DrillDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Fight IQ: a random 13x13 fight scene, a dummy wearing the player's own kit, and the player.
 * Place ONE crystal where it does the most NET damage (damage to the dummy minus damage to you),
 * then break it from a safe spot.
 *
 * <h2>How "best" is found (what an auto-crystal does)</h2>
 * Every block the player could use right now is a candidate: existing obsidian / bedrock with air
 * above, AND every air block the player could put obsidian into (it needs a face to place against).
 * Both must be within block reach (4.5 blocks from the eyes). For each candidate the explosion is
 * simulated with the game's own exposure raycast (any block between the blast and a body blocks
 * that ray - stone included, for this one blast), the vanilla falloff, the world difficulty, and
 * real armour + Blast Protection on both sides. Buildable spots are scored with the obsidian
 * temporarily in place, since the block you build can itself shield the target.
 *
 * <h2>How an attempt is judged</h2>
 * <ul>
 *   <li><b>Placement</b>: your crystal's net damage / the best net damage at the moment you placed.</li>
 *   <li><b>Safety</b>: damage to you when you broke it (from wherever you stood). Stone cover is
 *   flagged: it shields this blast and breaks, so the next crystal hits you in full.</li>
 *   <li><b>Speed</b>: scene start to break, against the time limit.</li>
 * </ul>
 * The feedback names the first thing that cost the rep.
 */
public class CrystalSpotDrill extends Drill {
    private enum Phase { PLACE, BREAK, REVIEW }

    /** One candidate spot: the block the crystal sits on, whether obsidian must be built first. */
    public record Eval(BlockPos block, boolean build, float target, float self) {
        public float net() { return target - self; }
    }

    static final int SIZE = 13;
    static final double PLACE_REACH = 4.5;
    private final boolean buildMode;
    private BlockPos origin;
    private MannequinEntity dummy;
    private Vec3d dummyAt;
    private float dummyYaw;
    private Eval best;
    private Phase phase = Phase.REVIEW;
    private int sceneAt;
    private int reviewUntil;
    private int crystalId = -1;
    private int nextStepAt;
    private final List<BlockPos> holes = new ArrayList<>();
    private int holeIndex;
    // the placed crystal's numbers
    private float placedQ;
    private float placedTarget;
    private float placedSelf;
    private boolean stoneCover;
    private float nextBlastSelf;
    // miss reasons, for the summary
    private int missPlacement;
    private int missSafety;
    private int missSpeed;
    public volatile String liveLine = "";
    public volatile int liveColor = 0xFFECEEF2;
    public volatile String reviewLine = "";

    public CrystalSpotDrill(Mode mode, double p, boolean hints) {
        this(DrillDef.CRYSTAL_SPOT, mode, p, hints, false);
    }

    protected CrystalSpotDrill(DrillDef def, Mode mode, double p, boolean hints, boolean buildMode) {
        super(def, mode, p, hints);
        this.buildMode = buildMode;
    }

    // ------------------------------------------------------------------ GameTest access

    public BlockPos bestBlockForTests() { return best == null ? null : best.block; }

    public float bestDamageForTests() { return best == null ? 0f : best.target; }

    public Eval bestForTests() { return best; }

    public MannequinEntity dummyForTests() { return dummy; }

    public List<Eval> evaluateForTests() { return evaluate(player()); }

    private double threshold() { return 0.85; }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        BlockPos base = pl.getBlockPos();
        origin = base.add(-SIZE / 2, -1, -SIZE / 2);
        s.snapshotBox(origin.add(-3, -2, -3), origin.add(SIZE + 2, 8, SIZE + 2));
        giveKit(pl, GameMode.SURVIVAL);
        newScene();
    }

    // ------------------------------------------------------------------ scene generation

    private boolean inside(int x, int z) { return x >= 0 && x < SIZE && z >= 0 && z < SIZE; }

    private BlockPos cell(int x, int y, int z) { return origin.add(x, y, z); }

    /** Feet position at the top of a column, if a player fits there. */
    private BlockPos standOn(int x, int z) {
        for (int y = 2; y >= 1; y--) {
            BlockPos feet = cell(x, y, z);
            if (!Scene.standable(s.world, feet)) continue;
            // never on a lone 1x1 pillar or wall top (looks like floating, and nobody fights there)
            int level = 0;
            for (Direction d : Direction.Type.HORIZONTAL) {
                if (Scene.standable(s.world, feet.offset(d))) level++;
            }
            if (level >= 2) return feet;
        }
        return null;
    }

    private void newScene() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        best = null;
        for (int attempt = 0; attempt < 20 && best == null; attempt++) tryScene(pl);
        if (best == null) fallbackScene(pl);
        // last line of defence: whatever happened, nobody stands inside a block or in the air
        if (!Scene.standable(s.world, BlockPos.ofFloored(dummyAt)) || !Scene.standable(s.world, pl.getBlockPos())) fallbackScene(pl);
        refillKit();
        cue();
        pl.changeGameMode(GameMode.SURVIVAL);
        phase = Phase.PLACE;
        sceneAt = ticks;
        nextStepAt = ticks + 60;
        crystalId = -1;
        liveLine = "";
        reviewLine = "";
        status = buildMode ? "Build the spot with obsidian, crystal it, break it safely."
                : "Most damage to the dummy, least to you. Then break it safely.";
    }

    private void tryScene(ServerPlayerEntity pl) {
        buildTerrain();
        BlockPos d = null;
        for (int guard = 0; guard < 80 && d == null; guard++) {
            int x = randBetween(3, SIZE - 4);
            int z = randBetween(3, SIZE - 4);
            BlockPos feet = standOn(x, z);
            if (feet == null) continue;
            // level 2+: the dummy likes cover - prefer a spot next to a wall
            if (level == 2 && rng.nextInt(3) > 0 && !nextToWall(feet)) continue;
            if (level >= 3 && !buildMode && rng.nextInt(3) > 0 && !nextToWall(feet)) continue;
            d = feet;
        }
        if (d == null) return; // never fall back to a cell that is inside a wall
        holes.clear();
        if (buildMode && level >= 3) buildHole(d);
        BlockPos p = null;
        for (int guard = 0; guard < 120 && p == null; guard++) {
            BlockPos f = standOn(randBetween(0, SIZE - 1), randBetween(0, SIZE - 1));
            if (f == null) continue;
            double dist = Math.sqrt(f.getSquaredDistance(d));
            if (dist < 4 || dist > 7) continue;
            p = f;
        }
        if (p == null) return;
        placeActors(pl, d, p);
        List<Eval> evals = evaluate(pl);
        Eval top = bestOf(evals);
        boolean hole = buildMode && level >= 3;
        // against full Protection IV netherite a hole caps what any crystal can do - judge it relative
        if (top == null || top.net() < (hole ? 0.8f : buildMode ? 2f : 3f) || top.target < (hole ? 1f : buildMode ? 3f : 4f)) return;
        if (buildMode && !top.build) return;
        if (!buildMode && level == 1) {
            long close = evals.stream().filter(e -> e.net() >= top.net() * 0.9f).count();
            if (close > 4) return; // too many right answers teaches nothing
        }
        best = top;
    }

    /** A clean scene that always works: flat floor, no walls - nobody can end up inside a block. */
    private void fallbackScene(ServerPlayerEntity pl) {
        discardCrystals();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                set(cell(x, -1, z), Blocks.STONE.getDefaultState());
                set(cell(x, 0, z), Blocks.STONE.getDefaultState());
                for (int y = 1; y <= 6; y++) set(cell(x, y, z), Blocks.AIR.getDefaultState());
            }
        }
        holes.clear();
        if (buildMode) {
            // one crater by the dummy: obsidian down in it is the answer
            set(cell(6, 0, 8), Blocks.AIR.getDefaultState());
        } else {
            set(cell(6, 0, 8), Blocks.OBSIDIAN.getDefaultState());
        }
        placeActors(pl, cell(6, 1, 9), cell(6, 1, 4));
        best = bestOf(evaluate(pl));
        if (best == null) best = new Eval(cell(6, 0, 8), false, 5f, 1f);
    }

    private void placeActors(ServerPlayerEntity pl, BlockPos dummyFeet, BlockPos playerFeet) {
        dummyAt = Vec3d.ofBottomCenter(dummyFeet);
        Vec3d playerAt = Vec3d.ofBottomCenter(playerFeet);
        dummyYaw = Scene.yawTowards(dummyAt, playerAt);
        if (dummy == null || dummy.isRemoved()) dummy = Scene.spawnDummy(s, dummyAt, dummyYaw);
        Scene.pin(dummy, dummyAt, dummyYaw);
        Scene.teleport(pl, playerAt, Scene.yawTowards(playerAt, dummyAt), 25f);
    }

    private boolean nextToWall(BlockPos feet) {
        for (Direction dir : Direction.Type.HORIZONTAL) if (!s.world.getBlockState(feet.offset(dir)).isAir()) return true;
        return false;
    }

    private BlockState wallMaterial() {
        int r = rng.nextInt(100);
        // Hole Breaker: nothing ready-made to crystal on - the spot has to be built
        if (buildMode) return r < 60 ? Blocks.STONE.getDefaultState() : Blocks.CRYING_OBSIDIAN.getDefaultState();
        if (level == 1) return r < 50 ? Blocks.OBSIDIAN.getDefaultState() : Blocks.STONE.getDefaultState();
        if (r < 40) return Blocks.OBSIDIAN.getDefaultState();
        if (r < 80) return Blocks.STONE.getDefaultState();
        return Blocks.CRYING_OBSIDIAN.getDefaultState();
    }

    private void buildTerrain() {
        discardCrystals();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                set(cell(x, 0, z), Blocks.STONE.getDefaultState());
                for (int y = 1; y <= 6; y++) set(cell(x, y, z), Blocks.AIR.getDefaultState());
            }
        }
        // walls - occlusion is half of crystal IQ
        int walls = level >= 3 ? randBetween(7, 10) : level == 2 ? randBetween(5, 8) : randBetween(2, 4);
        if (buildMode) walls = level >= 2 ? randBetween(3, 5) : randBetween(1, 3);
        for (int w = 0; w < walls; w++) {
            int x = randBetween(0, SIZE - 1);
            int z = randBetween(0, SIZE - 1);
            boolean alongX = rng.nextBoolean();
            int len = randBetween(2, 4);
            int h = randBetween(1, 2);
            BlockState mat = wallMaterial();
            for (int i = 0; i < len; i++) {
                int cx = alongX ? x + i : x;
                int cz = alongX ? z : z + i;
                if (!inside(cx, cz)) continue;
                for (int y = 1; y <= h; y++) set(cell(cx, y, cz), mat);
            }
        }
        // level 2+: raised platforms the dummy (or you) can stand on
        if (level >= 2 && !buildMode) {
            int plats = randBetween(1, 2);
            for (int i = 0; i < plats; i++) {
                int x = randBetween(1, SIZE - 3);
                int z = randBetween(1, SIZE - 3);
                for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) set(cell(x + a, 1, z + b), Blocks.STONE.getDefaultState());
            }
        }
        if (buildMode) {
            // craters, like a real fight leaves them: obsidian built DOWN in one puts the crystal at
            // feet level - that is where the big damage is
            int craters = randBetween(4, 7);
            for (int i = 0; i < craters; i++) {
                int x = randBetween(1, SIZE - 2);
                int z = randBetween(1, SIZE - 2);
                int r = rng.nextInt(3) == 0 ? 1 : 0;
                for (int a = -r; a <= r; a++) {
                    for (int b = -r; b <= r; b++) {
                        if (!inside(x + a, z + b) || Math.abs(a) + Math.abs(b) > 1) continue;
                        set(cell(x + a, 0, z + b), Blocks.AIR.getDefaultState());
                        set(cell(x + a, -1, z + b), Blocks.STONE.getDefaultState());
                    }
                }
            }
            return;
        }
        // crystal-able spots at three heights
        int spots = level == 1 ? randBetween(6, 10) : randBetween(8, 13);
        for (int i = 0; i < spots; i++) {
            int x = randBetween(0, SIZE - 1);
            int z = randBetween(0, SIZE - 1);
            BlockState top = rng.nextInt(10) < 8 ? Blocks.OBSIDIAN.getDefaultState() : Blocks.BEDROCK.getDefaultState();
            int roll = rng.nextInt(20);
            if (roll < 10) {
                set(cell(x, 0, z), top);
            } else if (roll < 17) {
                set(cell(x, 1, z), top);
            } else {
                set(cell(x, 1, z), Blocks.STONE.getDefaultState());
                set(cell(x, 2, z), top);
            }
        }
    }

    /** Crying obsidian (blast proof, crystals can't go on it) around the dummy's feet. */
    private void buildHole(BlockPos feet) {
        set(feet.down(), Blocks.CRYING_OBSIDIAN.getDefaultState());
        BlockState surround = Blocks.CRYING_OBSIDIAN.getDefaultState();
        for (Direction dir : Direction.Type.HORIZONTAL) set(feet.offset(dir), surround);
        for (int y = 0; y <= 2; y++) set(feet.up(y), Blocks.AIR.getDefaultState());
        holes.add(feet.toImmutable());
    }

    // ------------------------------------------------------------------ evaluation

    private static Eval bestOf(List<Eval> evals) {
        Eval top = null;
        for (Eval e : evals) {
            if (e.target < 2f || e.self >= e.target) continue;
            if (top == null || e.net() > top.net() || e.net() == top.net() && e.target > top.target) top = e;
        }
        return top;
    }

    /** Every spot the player could crystal right now (or build and crystal), scored. */
    private List<Eval> evaluate(ServerPlayerEntity pl) {
        List<Eval> out = new ArrayList<>();
        if (pl == null || dummy == null) return out;
        Box dummyBox = dummy.getBoundingBox();
        Box playerBox = pl.getBoundingBox();
        Vec3d eye = pl.getEyePos();
        Scene.Armour mine = Scene.armourOf(s.world, pl);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                for (int y = -1; y <= 4; y++) {
                    BlockPos b = cell(x, y, z);
                    if (Scene.reachTo(eye, b) > PLACE_REACH) continue;
                    BlockState st = s.world.getBlockState(b);
                    boolean ready = st.isOf(Blocks.OBSIDIAN) || st.isOf(Blocks.BEDROCK);
                    boolean buildable = !ready && st.isAir() && hasSupport(b)
                            && !new Box(b).intersects(dummyBox) && !new Box(b).intersects(playerBox);
                    if (!ready && !buildable) continue;
                    if (!s.world.getBlockState(b.up()).isAir()) continue;
                    Box cb = Scene.crystalBox(b);
                    if (cb.intersects(dummyBox) || cb.intersects(playerBox)) continue;
                    Vec3d c = Scene.crystalCenter(b);
                    if (c.distanceTo(dummyAt) > 12) continue;
                    if (buildable) s.world.setBlockState(b, Blocks.OBSIDIAN.getDefaultState(), 0);
                    float t = Scene.damage(c, CombatMath.CRYSTAL_POWER, dummy, mine);
                    float self = t <= 0.2f ? 0f : Scene.damage(c, CombatMath.CRYSTAL_POWER, pl, mine);
                    if (buildable) s.world.setBlockState(b, st, 0);
                    if (t <= 0.2f) continue;
                    out.add(new Eval(b.toImmutable(), buildable, t, self));
                }
            }
        }
        return out;
    }

    private boolean hasSupport(BlockPos b) {
        for (Direction d : Direction.values()) {
            if (!s.world.getBlockState(b.offset(d)).isAir()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ play

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        moveDummy();
        Scene.pin(dummy, dummyAt, dummyYaw);
        if (phase == Phase.REVIEW) {
            if (best != null && ticks % 3 == 0) {
                Scene.particles(s.world, ParticleTypes.END_ROD, Scene.crystalCenter(best.block).add(0, 0.3, 0), 4, 0.15);
                if (best.build) Scene.particles(s.world, ParticleTypes.FLAME, Vec3d.ofCenter(best.block), 3, 0.3);
            }
            if (ticks >= reviewUntil) newScene();
            return;
        }
        if (phase == Phase.PLACE && hints && best != null && ticks - sceneAt > 40 && ticks % 10 == 0) {
            // hints: a faint marker on the best spot after 2 s
            Scene.particles(s.world, ParticleTypes.WAX_ON, Scene.crystalCenter(best.block), 1, 0.1);
        }
        double seconds = (ticks - sceneAt) / 20.0;
        if (seconds > speed()) {
            missSpeed++;
            rep(false, 0, String.format(Locale.ROOT, "Speed: out of time (%.1f s)", speed()));
            pl.changeGameMode(GameMode.ADVENTURE);
            review(pl);
        }
    }

    /** Level 3 (and two-hole Hole Breaker): the dummy relocates every few seconds. */
    private void moveDummy() {
        if (phase == Phase.REVIEW || ticks < nextStepAt) return;
        if (buildMode && holes.size() > 1) {
            nextStepAt = ticks + 80;
            holeIndex = (holeIndex + 1) % holes.size();
            dummyAt = Vec3d.ofBottomCenter(holes.get(holeIndex));
        } else if (!buildMode && level >= 3) {
            nextStepAt = ticks + 60;
            BlockPos cur = BlockPos.ofFloored(dummyAt);
            for (int guard = 0; guard < 30; guard++) {
                int x = cur.getX() - origin.getX() + randBetween(-3, 3);
                int z = cur.getZ() - origin.getZ() + randBetween(-3, 3);
                if (!inside(x, z)) continue;
                BlockPos f = standOn(x, z);
                ServerPlayerEntity pl = player();
                if (f == null || pl == null || f.getSquaredDistance(pl.getBlockPos()) < 9) continue;
                dummyAt = Vec3d.ofBottomCenter(f);
                break;
            }
        } else {
            return;
        }
        ServerPlayerEntity pl = player();
        if (pl != null) dummyYaw = Scene.yawTowards(dummyAt, new Vec3d(pl.getX(), pl.getY(), pl.getZ()));
        Scene.particles(s.world, ParticleTypes.CLOUD, dummyAt.add(0, 0.2, 0), 6, 0.3);
    }

    private void review(ServerPlayerEntity pl) {
        phase = Phase.REVIEW;
        reviewUntil = ticks + 50;
        if (best != null) {
            reviewLine = String.format(Locale.ROOT, "Best: %.1f to dummy, %.1f to you%s", best.target, best.self,
                    best.build ? " - build obsidian there (flames)" : " (sparkles)");
        }
        status = "Best spot: white sparkles.";
    }

    private void discardCrystals() {
        if (origin == null) return;
        Box box = new Box(origin).expand(SIZE + 3);
        for (EndCrystalEntity c : s.world.getEntitiesByClass(EndCrystalEntity.class, box, x -> true)) c.discard();
        for (ItemEntity i : s.world.getEntitiesByClass(ItemEntity.class, box, x -> true)) i.discard();
    }

    @Override
    public void onCrystalSpawn(EndCrystalEntity crystal) {
        ServerPlayerEntity pl = player();
        if (phase != Phase.PLACE || pl == null) {
            crystal.discard();
            return;
        }
        Vec3d c = new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ());
        Scene.Armour mine = Scene.armourOf(s.world, pl);
        // judge against the best spot available at THIS moment, from where the player stands now
        Eval now = bestOf(evaluateIgnoring(pl, crystal));
        if (now != null) best = now;
        placedTarget = Scene.damage(c, CombatMath.CRYSTAL_POWER, dummy, mine);
        placedSelf = Scene.damage(c, CombatMath.CRYSTAL_POWER, pl, mine);
        float bestNet = best == null ? 0f : best.net();
        float net = placedTarget - placedSelf;
        placedQ = bestNet <= 0.1f ? 1f : Math.max(0f, Math.min(1.25f, net / bestNet));
        nextBlastSelf = Scene.damageAfterWeakCoverBreaks(s.world, c, CombatMath.CRYSTAL_POWER, pl, mine);
        stoneCover = nextBlastSelf >= placedSelf + 3f;
        crystalId = crystal.getId();
        phase = Phase.BREAK;
        String warn = placedSelf >= placedTarget ? "  -  it hurts you more than them!"
                : stoneCover ? String.format(Locale.ROOT, "  -  stone cover: next blast hits you for %.1f", nextBlastSelf) : "";
        liveLine = String.format(Locale.ROOT, "Placement %d%%  -  dummy %.1f / you %.1f%s", Math.round(placedQ * 100), placedTarget, placedSelf, warn);
        liveColor = placedQ >= threshold() && warn.isEmpty() ? Theme.accent() : 0xFFFFB020;
        status = "Now break it - from cover, or further from it than the dummy.";
    }

    /** Evaluation while the player's own crystal stands there (it must not block its own spot). */
    private List<Eval> evaluateIgnoring(ServerPlayerEntity pl, EndCrystalEntity crystal) {
        BlockPos under = crystal.getBlockPos().down();
        List<Eval> evals = evaluate(pl);
        boolean present = evals.stream().anyMatch(e -> e.block.equals(under));
        if (!present) {
            Vec3d c = Scene.crystalCenter(under);
            Scene.Armour mine = Scene.armourOf(s.world, pl);
            evals.add(new Eval(under, false, Scene.damage(c, CombatMath.CRYSTAL_POWER, dummy, mine), Scene.damage(c, CombatMath.CRYSTAL_POWER, pl, mine)));
        }
        return evals;
    }

    @Override
    public boolean onCrystalBreak(EndCrystalEntity crystal, Entity attacker) {
        ServerPlayerEntity pl = player();
        if (phase != Phase.BREAK || crystal.getId() != crystalId || attacker != pl || pl == null) return true;
        Vec3d c = new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ());
        Scene.Armour mine = Scene.armourOf(s.world, pl);
        float self = Scene.damage(c, CombatMath.CRYSTAL_POWER, pl, mine);
        float target = Scene.damage(c, CombatMath.CRYSTAL_POWER, dummy, mine);
        boolean safe = self <= 4.0f && self < target;
        boolean good = placedQ >= threshold();
        double seconds = (ticks - sceneAt) / 20.0;
        boolean inTime = seconds <= speed();
        String note;
        if (!good) {
            missPlacement++;
            note = String.format(Locale.ROOT, "Placement: %d%% of the best net damage (%.1f vs %.1f)",
                    Math.round(placedQ * 100), placedTarget - placedSelf, best == null ? 0f : best.net());
        } else if (!safe) {
            missSafety++;
            note = String.format(Locale.ROOT, "Safety: you took %.1f breaking it - break from cover or further away", self);
        } else if (!inTime) {
            missSpeed++;
            note = String.format(Locale.ROOT, "Speed: %.1f s (limit %.1f s) - placement was %d%%", seconds, speed(), Math.round(placedQ * 100));
        } else {
            note = String.format(Locale.ROOT, "%d%% placement, dealt %.1f, took %.1f, %.1f s%s", Math.round(placedQ * 100), target, self, seconds,
                    stoneCover ? " (careful: stone cover)" : "");
        }
        rep(good && safe && inTime, placedQ, note);
        crystal.discard();
        review(pl);
        return true;
    }

    @Override
    public String summaryLine() {
        int misses = missPlacement + missSafety + missSpeed;
        if (misses == 0) return reps.isEmpty() ? "" : "No misses";
        return String.format(Locale.ROOT, "Misses: %d placement, %d safety, %d speed", missPlacement, missSafety, missSpeed);
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        String l = liveLine;
        if (!l.isEmpty()) Gfx.text(c, l, x, y, liveColor);
        String r = reviewLine;
        if (!r.isEmpty()) Gfx.text(c, r, x, y + 11, Theme.TEXT_2);
    }
}
