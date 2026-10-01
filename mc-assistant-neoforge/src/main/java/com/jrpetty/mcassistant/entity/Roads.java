package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The road between a village and the colony it founded, laid a few steps at a time from
 * the mother's avenue to the colony's: a worn path three wide that keeps to the lie of the
 * land, banked up over a dip and cut through a bump, trees cleared from it, a plank bridge
 * with rails over water, a lamp every so often, and a signpost at each end.
 *
 * <p>Most of a road is out in the wilds, where nobody is; the ground at the road's head is
 * kept awake while it is being laid (ChunkLoad), a few chunks at a time.
 */
public final class Roads {

    private Roads() {}

    /** Where a road leaves a town: along the avenue, past the first ring of blocks. */
    static final int EXIT = TownPlan.RING + TownPlan.PERIOD + 2;
    /** Steps laid per visit, and how often a visit is. */
    private static final int STEPS = 6;
    private static final int EVERY = 100;
    /** A lamp every so many steps. */
    private static final int LAMP_EVERY = 24;

    /** The window of ground kept awake at each road's head, by colony. */
    private static final Map<UUID, BlockPos> WINDOW = new ConcurrentHashMap<>();

    public static void reset() {
        WINDOW.clear();
        LINES.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 53) return;
        Guard.run("roads", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) tick(level);
        });
    }

    static void tick(ServerLevel level) {
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            Villages.Village colony = Villages.get(link.getKey());
            Villages.Village mother = Villages.get(link.getValue());
            if (colony == null || mother == null) continue;
            if (!colony.dim().equals(level.dimension()) || !mother.dim().equals(level.dimension())) continue;
            lay(level, mother, colony, STEPS);
        }
    }

    // ------------------------------------------------------------------ where it runs

    /** Where a road leaves this town toward that one: the end of the avenue that points that way. */
    static BlockPos exit(BlockPos heart, BlockPos toward) {
        int dx = toward.getX() - heart.getX(), dz = toward.getZ() - heart.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return heart.offset(Integer.signum(dx) * EXIT, 0, 0);
        return heart.offset(0, 0, Integer.signum(dz) * EXIT);
    }

    private static final Map<UUID, List<int[]>> LINES = new ConcurrentHashMap<>();

    /** Every step of the road, mother to colony: a straight line between the two avenues' ends. */
    static List<int[]> line(Villages.Village mother, Villages.Village colony) {
        return LINES.computeIfAbsent(colony.id(), k -> {
            BlockPos a = exit(mother.centre(), colony.centre());
            BlockPos b = exit(colony.centre(), mother.centre());
            return bresenham(a.getX(), a.getZ(), b.getX(), b.getZ());
        });
    }

    static List<int[]> bresenham(int x0, int z0, int x1, int z1) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dz = -Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx + dz;
        int x = x0, z = z0;
        while (true) {
            out.add(new int[]{ x, z });
            if (x == x1 && z == z1) break;
            int e2 = 2 * err;
            if (e2 >= dz) { err += dz; x += sx; }
            if (e2 <= dx) { err += dx; z += sz; }
        }
        return out;
    }

    /** How far along its road a colony is (0..1), or -1 if it has none begun. */
    public static double progress(UUID colony) {
        int[] r = Ledger.road(colony);
        Villages.Village c = Villages.get(colony);
        if (r == null || c == null) return -1;
        if (r[2] == 1) return 1.0;
        List<int[]> l = LINES.get(colony);
        return l == null || l.isEmpty() ? 0 : Math.min(1.0, r[0] / (double) l.size());
    }

    // ------------------------------------------------------------------ laying it

    /** Lay up to so many more steps of a colony's road. Returns how many were laid. */
    public static int lay(ServerLevel level, Villages.Village mother, Villages.Village colony, int steps) {
        UUID id = colony.id();
        int[] state = Ledger.road(id);
        if (state != null && state[2] == 1) return 0;
        List<int[]> line = line(mother, colony);
        int next = state == null ? 0 : state[0];
        int lastY = state == null ? mother.centre().getY() : state[1];
        if (state == null) signpost(level, mother, colony, true);
        boolean alongX = Math.abs(line.get(line.size() - 1)[0] - line.get(0)[0])
            >= Math.abs(line.get(line.size() - 1)[1] - line.get(0)[1]);
        int laid = 0;
        while (laid < steps && next < line.size()) {
            int[] p = line.get(next);
            BlockPos head = new BlockPos(p[0], lastY, p[1]);
            if (!loaded(level, p[0], p[1])) {
                keepAwake(level, id, head);
                break;
            }
            lastY = step(level, mother, colony, p[0], p[1], lastY, alongX, next);
            next++;
            laid++;
        }
        boolean done = next >= line.size();
        Ledger.road(id, next, lastY, done);
        if (done) {
            signpost(level, mother, colony, false);
            release(level, id);
            long day = level.getDayTime() / 24000L;
            Villages.tell(mother.id(), day, "the road to " + Villages.name(id) + " was finished");
            Villages.tell(id, day, "the road to " + Villages.name(mother.id()) + " was finished");
        }
        return laid;
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (!level.hasChunk((x + dx) >> 4, (z + dz) >> 4)) return false;
            }
        }
        return true;
    }

    private static UUID owner(UUID colony) {
        return UUID.nameUUIDFromBytes(("mca-road-" + colony).getBytes());
    }

    /** Keep the ground at the road's head awake, a window that moves along with it. */
    private static void keepAwake(ServerLevel level, UUID colony, BlockPos head) {
        BlockPos was = WINDOW.get(colony);
        if (was != null && was.distSqr(head) < 24 * 24) return;
        if (was != null) ChunkLoad.setLoaded(level, owner(colony), was, 1, false);
        ChunkLoad.setLoaded(level, owner(colony), head, 1, true);
        WINDOW.put(colony, head.immutable());
    }

    private static void release(ServerLevel level, UUID colony) {
        BlockPos was = WINDOW.remove(colony);
        if (was != null) ChunkLoad.setLoaded(level, owner(colony), was, 1, false);
    }

    /** Is this spot inside some town (where the town's own streets are its business)? */
    private static boolean inATown(ServerLevel level, int x, int z) {
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int reach = Villages.townReach(v.id());
            if (Math.abs(x - v.centre().getX()) <= reach && Math.abs(z - v.centre().getZ()) <= reach) return true;
        }
        return false;
    }

    /** One step of the road: its three-wide surface (or a bridge), cleared above. Returns its height. */
    static int step(ServerLevel level, Villages.Village mother, Villages.Village colony, int x, int z, int lastY,
                    boolean alongX, int index) {
        if (inATown(level, x, z)) return lastY;
        Ground g = ground(level, x, z);
        if (g == null) return lastY;
        if (g.water) {
            int deck = g.y + 1;
            for (int side = -2; side <= 2; side++) {
                int bx = alongX ? x : x + side, bz = alongX ? z + side : z;
                BlockPos d = new BlockPos(bx, deck, bz);
                if (level.getBlockState(d).isAir() || level.getBlockState(d).canBeReplaced()) {
                    level.setBlock(d, Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
                }
                clearAbove(level, d, 3);
                if (Math.abs(side) == 2 && level.getBlockState(d.above()).isAir()) {
                    level.setBlock(d.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                    if (index % 8 == 0 && level.getBlockState(d.above(2)).isAir()) level.setBlock(d.above(2), Blocks.LANTERN.defaultBlockState(), 3);
                }
            }
            return deck;
        }
        // Keep to the land, a block up or down a step at most: banked up over a dip, cut through a bump.
        int want = Math.max(lastY - 1, Math.min(lastY + 1, g.y));
        if (Math.abs(g.y - want) > 4) want = g.y;
        for (int[] c : brush(x, z)) {
            Ground here = c[0] == x && c[1] == z ? g : ground(level, c[0], c[1]);
            if (here == null || here.water) continue;
            BlockPos top = new BlockPos(c[0], want, c[1]);
            if (here.y < want) {
                for (int y = here.y + 1; y < want; y++) level.setBlock(new BlockPos(c[0], y, c[1]), Blocks.COBBLESTONE.defaultBlockState(), 2);
            } else if (here.y > want) {
                for (int y = want + 1; y <= here.y; y++) level.setBlock(new BlockPos(c[0], y, c[1]), Blocks.AIR.defaultBlockState(), 2);
            }
            level.setBlock(top, Blocks.DIRT_PATH.defaultBlockState(), 2);
            clearAbove(level, top, 3);
        }
        // A lamp now and then, beside the road, out in the wilds.
        if (index % LAMP_EVERY == LAMP_EVERY / 2) {
            int lx = alongX ? x : x + 2, lz = alongX ? z + 2 : z;
            Ground lg = ground(level, lx, lz);
            if (lg != null && !lg.water && Math.abs(lg.y - want) <= 1) {
                BlockPos post = new BlockPos(lx, lg.y + 1, lz);
                if (level.getBlockState(post).isAir() && level.getBlockState(post.above()).isAir() && level.getBlockState(post.above(2)).isAir()) {
                    level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                    level.setBlock(post.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                    level.setBlock(post.above(2), Blocks.LANTERN.defaultBlockState(), 3);
                }
            }
        }
        return want;
    }

    /** The road's width: the step and the four round it. */
    private static int[][] brush(int x, int z) {
        return new int[][]{ { x, z }, { x + 1, z }, { x - 1, z }, { x, z + 1 }, { x, z - 1 } };
    }

    /** The ground at a column: the top of the earth under any trees and plants, or the water's surface. */
    record Ground(int y, boolean water) {}

    @Nullable
    static Ground ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int floor = level.getMinBuildHeight() + 1;
        while (y > floor) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            if (!st.getFluidState().isEmpty()) {
                // Water is bridged; lava is not a place for a road at all.
                return st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) ? new Ground(y, true) : null;
            }
            if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.canBeReplaced() || st.is(Blocks.SNOW)
                    || st.is(Blocks.BAMBOO) || st.is(Blocks.CACTUS) || st.is(BlockTags.FLOWERS)) {
                y--;
                continue;
            }
            return new Ground(y, false);
        }
        return null;
    }

    /** Trees, plants and snow off the road, up to a man's height and a bit. */
    private static void clearAbove(ServerLevel level, BlockPos top, int high) {
        for (int h = 1; h <= high; h++) {
            BlockPos p = top.above(h);
            BlockState st = level.getBlockState(p);
            if (st.isAir()) continue;
            if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || (st.canBeReplaced() && st.getFluidState().isEmpty())
                    || st.is(Blocks.SNOW) || st.is(Blocks.BAMBOO) || st.is(BlockTags.FLOWERS)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /** A signpost where the road leaves a town: the town it goes to, and how far. */
    static void signpost(ServerLevel level, Villages.Village mother, Villages.Village colony, boolean atMother) {
        Villages.Village here = atMother ? mother : colony, there = atMother ? colony : mother;
        BlockPos at = exit(here.centre(), there.centre());
        int dx = Integer.signum(at.getX() - here.centre().getX()), dz = Integer.signum(at.getZ() - here.centre().getZ());
        // Beside the avenue's end, on the right going out.
        int px = at.getX() + (dz != 0 ? -dz * 3 : 0), pz = at.getZ() + (dx != 0 ? dx * 3 : 0);
        if (!level.hasChunk(px >> 4, pz >> 4)) return;
        Ground g = ground(level, px, pz);
        if (g == null || g.water) return;
        BlockPos post = new BlockPos(px, g.y + 1, pz);
        if (!level.getBlockState(post).isAir() || !level.getBlockState(post.above()).isAir()) return;
        level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        Direction out = Direction.getNearest((double) dx, 0.0, (double) dz);
        if (out.getAxis() == Direction.Axis.Y) out = Direction.NORTH;
        BlockPos sign = post.above();
        level.setBlock(sign, Blocks.SPRUCE_SIGN.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION,
                net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(out.getOpposite())), 3);
        int far = (int) Math.round(Math.sqrt(here.centre().distSqr(there.centre())));
        if (level.getBlockEntity(sign) instanceof SignBlockEntity s) {
            SignText text = new SignText()
                .setMessage(0, Component.literal("To"))
                .setMessage(1, Component.literal(Villages.name(there.id())))
                .setMessage(2, Component.literal(far + " blocks"))
                .setMessage(3, Component.literal(atMother ? "(our colony)" : "(our mother town)"));
            s.setText(text, true);
            s.setText(text, false);
            s.setWaxed(true);
        }
    }
}
