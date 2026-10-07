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
            // Laid by hand: a road crew from the mother village works at the road's head, and the
            // road goes no faster than they do (TownJobs).
            if (!inATown(level, p[0], p[1]) && !TownJobs.atWork(level, mother, "road/" + id, head,
                    "laying the road to " + Villages.name(id), AssistantEntity.StationTask.HAUL)) break;
            // Paid for out of the mother's stores, a block at a time: when they run short the road
            // stops where it is, and goes on from there another visit, once there is more put by.
            int y = step(level, mother, colony, p[0], p[1], lastY, alongX, next);
            if (y == UNPAID) break;
            lastY = y;
            next++;
            laid++;
        }
        boolean done = next >= line.size();
        Ledger.road(id, next, lastY, done);
        if (done) {
            signpost(level, mother, colony, true);      // if the stores couldn't run to it at the start
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

    /** What step returns when the mother's stores could not pay for the step: the road waits there. */
    static final int UNPAID = Integer.MIN_VALUE;

    /**
     * One step of the road: its three-wide surface (or a bridge), cleared above. Returns its height,
     * or UNPAID if the mother's stores ran short partway (what was paid for stays laid, and the
     * step is gone over again next time, finishing what is missing).
     */
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
                    // A plank of the deck out of the stores (sawn from their logs if need be).
                    if (!Crafts.usePlanks(level, mother, 1)) return UNPAID;
                    level.setBlock(d, Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
                }
                clearAbove(level, mother, d, 3);
                if (Math.abs(side) == 2 && level.getBlockState(d.above()).isAir()) {
                    if (!Crafts.fence(level, mother)) return UNPAID;
                    level.setBlock(d.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                    // A light on the rail if the stores can spare one (a lantern, or a torch till the smith
                    // makes lanterns: Masonry); the bridge doesn't wait on it.
                    if (index % 8 == 0 && level.getBlockState(d.above(2)).isAir()) {
                        net.minecraft.world.level.block.Block light = Masonry.light(level, mother);
                        if (light != null) level.setBlock(d.above(2), light.defaultBlockState(), 3);
                    }
                }
            }
            return deck;
        }
        // Keep to the land, a block up or down a step at most: banked up over a dip, cut through a bump.
        int want = Math.max(lastY - 1, Math.min(lastY + 1, g.y));
        if (Math.abs(g.y - want) > 4) want = g.y;
        int dirt = 0, cobble = 0;
        try {
            for (int[] c : brush(x, z)) {
                Ground here = c[0] == x && c[1] == z ? g : ground(level, c[0], c[1]);
                if (here == null || here.water) continue;
                BlockPos top = new BlockPos(c[0], want, c[1]);
                if (here.y < want) {
                    // Banked up with the stores' cobblestone, and a spadeful of their earth on top
                    // to wear into the path: the ground that was there is free, made ground is not.
                    int fill = want - 1 - here.y;
                    if (fill > 0 && !TownWork.take(level, mother, s -> s.is(net.minecraft.world.item.Items.COBBLESTONE), fill)) return UNPAID;
                    for (int y = here.y + 1; y < want; y++) {
                        BlockPos f = new BlockPos(c[0], y, c[1]);
                        salvage(level, mother, level.getBlockState(f));
                        level.setBlock(f, Blocks.COBBLESTONE.defaultBlockState(), 2);
                    }
                    if (!TownWork.take(level, mother, Roads::earth, 1)) return UNPAID;
                    salvage(level, mother, level.getBlockState(top));
                } else if (here.y > want) {
                    // Cut through: the earth and stone dug out go home to the stores.
                    for (int y = want + 1; y <= here.y; y++) {
                        BlockPos f = new BlockPos(c[0], y, c[1]);
                        BlockState cut = level.getBlockState(f);
                        if (dug(cut)) dirt++;
                        else if (cut.is(Blocks.STONE) || cut.is(Blocks.COBBLESTONE)) cobble++;
                        level.setBlock(f, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                level.setBlock(top, Blocks.DIRT_PATH.defaultBlockState(), 2);
                clearAbove(level, mother, top, 3);
            }
        } finally {
            Crafts.giveBack(level, mother, net.minecraft.world.item.Items.DIRT, dirt);
            Crafts.giveBack(level, mother, net.minecraft.world.item.Items.COBBLESTONE, cobble);
        }
        // A lamp now and then, beside the road, out in the wilds: two lengths of fence and a
        // lantern (or a torch) out of the mother's stores, or no lamp there (the road doesn't wait on it).
        if (index % LAMP_EVERY == LAMP_EVERY / 2) {
            int lx = alongX ? x : x + 2, lz = alongX ? z + 2 : z;
            Ground lg = ground(level, lx, lz);
            if (lg != null && !lg.water && Math.abs(lg.y - want) <= 1) {
                BlockPos post = new BlockPos(lx, lg.y + 1, lz);
                net.minecraft.world.level.block.Block light = null;
                if (level.getBlockState(post).isAir() && level.getBlockState(post.above()).isAir() && level.getBlockState(post.above(2)).isAir()
                        && (light = lampPost(level, mother)) != null) {
                    level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                    level.setBlock(post.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                    level.setBlock(post.above(2), light.defaultBlockState(), 3);
                }
            }
        }
        Milestones.onStep(level, mother, colony, index);           // [workitems] a milestone every hundred blocks, at the ends and the crossings
        return want;
    }

    /** A lamp post's makings out of the stores: the light first (no light, no post: a lantern, or a torch
     *  till the smith makes lanterns), then its two lengths of fence; the light goes back if the fence
     *  can't be had. Returns the light it is to carry, or null. */
    @javax.annotation.Nullable
    private static net.minecraft.world.level.block.Block lampPost(ServerLevel level, Villages.Village v) {
        net.minecraft.world.level.block.Block light = Masonry.light(level, v);
        if (light == null) return null;
        if (Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES), 2) || Crafts.usePlanks(level, v, 4)) return light;
        Masonry.unlight(level, v, light);
        return null;
    }

    /** Earth from the stores, for made ground. */
    private static boolean earth(net.minecraft.world.item.ItemStack s) {
        return s.is(net.minecraft.world.item.Items.DIRT) || s.is(net.minecraft.world.item.Items.COARSE_DIRT)
            || s.is(net.minecraft.world.item.Items.GRASS_BLOCK) || s.is(net.minecraft.world.item.Items.ROOTED_DIRT);
    }

    /** Ground that digs out as earth. */
    private static boolean dug(BlockState st) {
        return st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL)
            || st.is(Blocks.MYCELIUM) || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.DIRT_PATH);
    }

    /** A log in the road's way is the stores' timber, not rubbish: home it goes. */
    private static void salvage(ServerLevel level, Villages.Village v, BlockState st) {
        if (st.is(BlockTags.LOGS)) Crafts.giveBack(level, v, st.getBlock().asItem(), 1);
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

    /** Trees, plants and snow off the road, up to a man's height and a bit. The logs go into the
     *  mother's stores. */
    private static void clearAbove(ServerLevel level, Villages.Village v, BlockPos top, int high) {
        for (int h = 1; h <= high; h++) {
            BlockPos p = top.above(h);
            BlockState st = level.getBlockState(p);
            if (st.isAir()) continue;
            if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || (st.canBeReplaced() && st.getFluidState().isEmpty())
                    || st.is(Blocks.SNOW) || st.is(Blocks.BAMBOO) || st.is(BlockTags.FLOWERS)) {
                salvage(level, v, st);
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
        // The post and its sign out of the mother's stores (the road is her people's work), or none.
        if (!Crafts.sign(level, mother)) return;
        if (!Crafts.fence(level, mother)) {
            Crafts.store(level, mother, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_SIGN));
            return;
        }
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
