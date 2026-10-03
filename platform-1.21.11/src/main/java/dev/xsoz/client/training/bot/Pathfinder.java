package dev.xsoz.client.training.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * A* over the blocks a player can stand on: walk to any of the 8 neighbours, step up one block
 * (with headroom for the jump), drop down up to three. Diagonals may not cut corners. Bounded, so a
 * search never costs more than a few milliseconds.
 */
public final class Pathfinder {
    private Pathfinder() { }

    private record Node(BlockPos pos, double g, double f) {
    }

    public static boolean solid(ServerWorld w, BlockPos p) {
        BlockState st = w.getBlockState(p);
        return !st.isAir() && !st.getCollisionShape(w, p).isEmpty();
    }

    public static boolean passable(ServerWorld w, BlockPos p) {
        BlockState st = w.getBlockState(p);
        return st.getCollisionShape(w, p).isEmpty() && !st.isOf(net.minecraft.block.Blocks.LAVA) && !st.isOf(net.minecraft.block.Blocks.FIRE);
    }

    /** Feet position a body can occupy: solid below, two passable blocks. */
    public static boolean standable(ServerWorld w, BlockPos feet) {
        return solid(w, feet.down()) && passable(w, feet) && passable(w, feet.up());
    }

    /** Where a body standing over p ends up: the first standable spot going down (max 4). */
    public static BlockPos ground(ServerWorld w, BlockPos p) {
        BlockPos q = p;
        for (int i = 0; i < 5; i++) {
            if (standable(w, q)) return q;
            q = q.down();
        }
        return null;
    }

    /**
     * Path from start to goal (both feet positions), or null. The list excludes start. If the goal
     * itself is unreachable within the budget, the path to the closest point reached is returned.
     */
    public static List<BlockPos> find(ServerWorld w, BlockPos start, BlockPos goal, int maxNodes, BlockPos center, int radius) {
        return find(w, start, goal, maxNodes, center, radius, null);
    }

    /** As above; spots matching avoid (e.g. holes) cost a lot more to walk through. */
    public static List<BlockPos> find(ServerWorld w, BlockPos start, BlockPos goal, int maxNodes, BlockPos center, int radius,
                                      java.util.function.Predicate<BlockPos> avoid) {
        if (start.equals(goal)) return new ArrayList<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> from = new HashMap<>();
        open.add(new Node(start, 0, h(start, goal)));
        best.put(start, 0.0);
        BlockPos closest = start;
        double closestH = h(start, goal);
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            if (n.g > best.getOrDefault(n.pos, Double.MAX_VALUE) + 1e-9) continue;
            expanded++;
            if (n.pos.equals(goal)) {
                closest = goal;
                break;
            }
            double hh = h(n.pos, goal);
            if (hh < closestH) {
                closestH = hh;
                closest = n.pos;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos side = n.pos.add(dx, 0, dz);
                    if (Math.abs(side.getX() - center.getX()) > radius || Math.abs(side.getZ() - center.getZ()) > radius) continue;
                    boolean diag = dx != 0 && dz != 0;
                    // no corner cutting
                    if (diag && (!passable(w, n.pos.add(dx, 0, 0)) || !passable(w, n.pos.add(0, 0, dz))
                            || !passable(w, n.pos.add(dx, 1, 0)) || !passable(w, n.pos.add(0, 1, dz)))) continue;
                    BlockPos next = null;
                    double cost = diag ? 1.414 : 1.0;
                    if (standable(w, side)) {
                        next = side;
                    } else if (standable(w, side.up()) && passable(w, n.pos.up(2))) {
                        next = side.up();
                        cost += 0.6; // a jump
                    } else if (passable(w, side) && passable(w, side.up())) {
                        for (int drop = 1; drop <= 3; drop++) {
                            if (standable(w, side.down(drop))) {
                                next = side.down(drop);
                                cost += 0.3 * drop;
                                break;
                            }
                        }
                    }
                    if (next == null) continue;
                    if (avoid != null && !next.equals(goal) && avoid.test(next)) cost += 6;
                    double g = n.g + cost;
                    if (g < best.getOrDefault(next, Double.MAX_VALUE)) {
                        best.put(next, g);
                        from.put(next, n.pos);
                        open.add(new Node(next, g, g + h(next, goal)));
                    }
                }
            }
        }
        if (closest.equals(start)) return null;
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos p = closest; p != null && !p.equals(start); p = from.get(p)) path.add(p);
        Collections.reverse(path);
        return path;
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(a.getY() - b.getY()) * 0.5;
    }
}
