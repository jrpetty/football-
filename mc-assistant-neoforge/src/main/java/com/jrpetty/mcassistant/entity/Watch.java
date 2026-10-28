package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a walled village keeps for its own safety, put in by the village itself once its
 * wall is up (Raids decides when they are wanted):
 * <ul>
 * <li><b>Gates.</b> The wall leaves a gap on each avenue. Each gets a gate: stone posts, a
 *     lintel, and three wooden doors between them. The doors stand open by day and are
 *     shut at dusk (and whenever the bell rings). Folk open a door to pass, as anybody
 *     would; a zombie can't.</li>
 * <li><b>The watch's posts.</b> Two places on each side of the wall where a guard stands
 *     between the battlements, with a ladder up the inside of the wall to each.</li>
 * <li><b>The alarm bell.</b> A bell on a stone plinth on the square, rung when trouble
 *     comes (the chapel's bell rings with it, once there is one).</li>
 * </ul>
 */
public final class Watch {

    private Watch() {}

    /** The wall's line, from the heart (the fortify's radius). */
    static final int R = TownPlan.PLAZA;
    /** Where the watch stands on each side of the wall, either side of the gate. */
    static final int[] POSTS = { -7, 7 };
    /** Where else along the wall a post may go, if the first place won't do (odd: between the
     *  battlements), nearest first. */
    static final int[] POST_TRIES = { 7, 9, 5, 11 };
    /** The alarm bell's place on the square. */
    static final int[] BELL_AT = { -5, 5 };

    static final Direction[] SIDES = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    /** A guard's place on the wall: where it stands, the foot of its ladder, and the way it looks out. */
    public record Post(BlockPos stand, BlockPos foot, Direction out) {}

    /** A gate: its doors (their lower halves) and the cell just inside it. */
    public record Gate(Direction out, List<BlockPos> doors, BlockPos inside) {}

    private static final Map<UUID, List<Post>> POSTS_OF = new ConcurrentHashMap<>();
    private static final Map<UUID, List<Gate>> GATES_OF = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SEEN = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SHUT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        POSTS_OF.clear();
        GATES_OF.clear();
        SEEN.clear();
        SHUT.clear();
        CHAPEL_BELL.clear();
    }

    /** The wall's anchor (the ground at the heart), or null if the village has no wall. */
    @Nullable
    static BlockPos wall(UUID village) {
        if (!Villages.hasBuilt(village, "fortify")) return null;
        BlockPos at = Villages.builtAt(village, "fortify");
        if (at != null) return at;
        // Built, but where was never written down (its site gone before it was finished): the
        // wall always rings the heart, so the heart is its anchor.
        Villages.Village v = Villages.get(village);
        return v == null ? null : v.centre();
    }

    /** A cell of the wall: on this side, this far along it (along runs clockwise). */
    static BlockPos cell(BlockPos anchor, Direction side, int along) {
        return anchor.relative(side, R).relative(side.getClockWise(), along);
    }

    /** The floor of a column near this height: the lowest place with solid ground under it, room
     *  (air, or a door) for two above, and nothing over it but sky — not a cave under the street.
     *  (The long game's south gate was looked for in a cave under the avenue, "blocked by stone",
     *  and its east one was more than six blocks below a heart on a hilltop.) */
    @Nullable
    static BlockPos floorAt(ServerLevel level, int x, int z, int aroundY) {
        for (int y = aroundY - 10; y <= aroundY + 10; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState under = level.getBlockState(p.below());
            if (!under.isSolid() || under.getBlock() instanceof DoorBlock) continue;
            if (!roomy(level.getBlockState(p)) || !roomy(level.getBlockState(p.above()))) continue;
            if (!open(level, p, aroundY + 14)) continue;
            return p;
        }
        return null;
    }

    /** Nothing over this spot but what a gate or a post puts there (a door, a ladder) and a tree's
     *  leaves: it is out on the ground, not under it. */
    private static boolean open(ServerLevel level, BlockPos p, int upTo) {
        for (int y = p.getY() + 2; y <= upTo; y++) {
            BlockState s = level.getBlockState(new BlockPos(p.getX(), y, p.getZ()));
            if (s.isAir() || !s.isSolid() || s.getBlock() instanceof DoorBlock || s.is(Blocks.LADDER)
                || s.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            return false;
        }
        return true;
    }

    /** Room to stand: air, a door, a plant — or the post's own ladder (once it is up, the foot of
     *  the post is a ladder; that must not make the post vanish when the posts are looked over).
     *  Not water: no gate hangs in a river. */
    private static boolean roomy(BlockState s) {
        return s.isAir() || s.getBlock() instanceof DoorBlock || (s.canBeReplaced() && s.getFluidState().isEmpty())
            || s.is(Blocks.LADDER);
    }

    /** The top of the wall in a column: the highest block of masonry with masonry under it (the
     *  wall is three high), looked for well above and below the anchor's height, since the wall
     *  follows the ground. Lanterns, slabs of other stuff, snow and air above it are passed over. */
    @Nullable
    static BlockPos wallTop(ServerLevel level, int x, int z, int aroundY) {
        for (int y = aroundY + 12; y >= aroundY - 12; y--) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !s.isSolid() || s.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                || s.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            // The first real block from the top is the wall's, or the ground's: no looking on
            // down through the earth to the bedrock stone under it (which is no wall to stand on).
            return masonry(s) && masonry(level.getBlockState(p.below())) ? p : null;
        }
        return null;
    }

    static boolean masonry(BlockState s) {
        if (s.isAir() || !s.isSolid()) return false;
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.contains("ore") || path.contains("grass") || path.contains("dirt") || path.contains("leaves")) return false;
        return path.contains("stone") || path.contains("cobble") || path.contains("brick")
            || path.contains("andesite") || path.contains("diorite") || path.contains("granite") || path.contains("deepslate")
            || path.contains("tuff") || path.contains("blackstone")
            // A wall the builders put up out of whatever they had when the stone ran short
            // (BuildGoal.takeStyled): planks, logs, clay. It is no less a wall, and a wall of
            // planks with no gates or posts left the long game's village with no watch at all.
            || path.endsWith("planks") || path.endsWith("_log") || path.endsWith("_wood")
            || path.contains("terracotta") || path.contains("concrete") || path.contains("quartz");
    }

    // ------------------------------------------------------------------ the posts

    /** The watch's posts on a village's wall, worked out (and their ladders put up) once in a while. */
    public static List<Post> posts(ServerLevel level, UUID village) {
        BlockPos a = wall(village);
        if (a == null) return List.of();
        return postsAt(level, village, a);
    }

    /** The posts on a wall anchored here (the showcase's has no village behind it). */
    public static List<Post> postsAt(ServerLevel level, UUID village, BlockPos a) {
        List<Post> known = POSTS_OF.get(village);
        if (known != null) return known;
        List<Post> out = new ArrayList<>();
        for (Direction side : SIDES) {
            for (int sign = -1; sign <= 1; sign += 2) {
                // Either side of the gate: the usual place, or the next that will do (the wall
                // went round a house there, or a tree stands against it inside).
                for (int along : POST_TRIES) {
                    Post post = postAt(level, a, side, sign * along);
                    if (post != null) { out.add(post); break; }
                }
            }
        }
        List<Post> done = List.copyOf(out);
        if (!done.isEmpty()) POSTS_OF.put(village, done);
        return done;
    }

    @Nullable
    private static Post postAt(ServerLevel level, BlockPos a, Direction side, int along) {
        BlockPos c = cell(a, side, along);
        if (!level.isLoaded(c)) return null;
        BlockPos top = wallTop(level, c.getX(), c.getZ(), a.getY());
        if (top == null) return null;
        BlockPos stand = top.above();
        if (!level.getBlockState(stand).isAir() || !level.getBlockState(stand.above()).isAir()) return null;
        BlockPos in = c.relative(side.getOpposite());
        BlockPos foot = floorAt(level, in.getX(), in.getZ(), a.getY());
        if (foot == null || foot.getY() > top.getY()) return null;
        return new Post(stand, foot, side);
    }

    /** A ladder up the inside of the wall at a post, where there is room for one. Paid from the
     *  stores now, a rung at a time: a ladder put by, else a plank sawn into one (for nothing only
     *  in the showcase). It goes up as far as the stores pay, and the rest another day. */
    static void ladder(ServerLevel level, Villages.Village v, Post p, boolean free) {
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, p.out().getOpposite());
        boolean there = free;
        for (int y = p.foot().getY(); y < p.stand().getY(); y++) {
            BlockPos at = new BlockPos(p.foot().getX(), y, p.foot().getZ());
            BlockState s = level.getBlockState(at);
            if (s.is(Blocks.LADDER)) continue;
            if (!s.isAir() && !s.canBeReplaced()) return;
            if (!ladder.canSurvive(level, at)) return;
            // Fixed to the wall by the watch (or a spare hand), there at the foot of it.
            if (!there && !(there = TownJobs.atWork(level, v, "watch", p.foot(), "fixing a ladder to the wall",
                    AssistantEntity.StationTask.GUARD))) return;
            if (!free && !TownWork.take(level, v, st -> st.is(Items.LADDER), 1)
                    && !TownWork.take(level, v, st -> st.is(ItemTags.PLANKS), 1)) return;
            level.setBlock(at, ladder, 3);
        }
    }

    // ------------------------------------------------------------------ the gates

    /** The village's gates as they stand: hung once there is wall either side of a gap. */
    public static List<Gate> gates(ServerLevel level, UUID village) {
        List<Gate> g = GATES_OF.get(village);
        return g == null ? List.of() : g;
    }

    /**
     * Look the wall over: hang any gate that is missing (out of the stores, or for nothing in
     * the showcase), and put up the posts' ladders. Returns how many gates stand.
     */
    public static int keep(ServerLevel level, Villages.Village v, boolean free) {
        BlockPos a = wall(v.id());
        if (a == null) return 0;
        return keepAt(level, v, a, free);
    }

    /** As keep, for a wall anchored here. */
    public static int keepAt(ServerLevel level, Villages.Village v, BlockPos a, boolean free) {
        List<Gate> out = new ArrayList<>();
        for (Direction side : SIDES) {
            Gate gate = gate(level, v, a, side, free);
            if (gate != null) out.add(gate);
        }
        GATES_OF.put(v.id(), List.copyOf(out));
        POSTS_OF.remove(v.id());
        for (Post p : postsAt(level, v.id(), a)) ladder(level, v, p, free);
        return out.size();
    }

    /**
     * Why the wall has no gates or posts, side by side, for the village's status (the real-world
     * runs): a wall the builders put up had none in a fifty-day game, and a stamped one always had.
     */
    public static String trouble(ServerLevel level, UUID village) {
        BlockPos a = wall(village);
        if (a == null) return "no wall";
        StringBuilder sb = new StringBuilder("wall at ").append(a.toShortString()).append(':');
        for (Direction side : SIDES) {
            sb.append(' ').append(side.getName()).append('=');
            String why = null;
            for (int along = -2; along <= 2 && why == null; along++) {
                if (!level.isLoaded(cell(a, side, along))) why = "unloaded";
            }
            if (why == null) {
                boolean hung = false;
                for (int along = -1; along <= 1; along++) if (doorIn(level, cell(a, side, along), a.getY()) != null) hung = true;
                if (hung) why = "hung";
            }
            if (why == null) {
                StringBuilder tops = new StringBuilder();
                for (int along : new int[]{ -3, 3 }) {
                    BlockPos c = cell(a, side, along);
                    BlockPos t = wallTop(level, c.getX(), c.getZ(), a.getY());
                    if (t == null) {
                        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) - 1;
                        tops.append(" no-wall@").append(along).append('(')
                            .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(c.getX(), y, c.getZ())).getBlock()).getPath())
                            .append(" y").append(y - a.getY()).append(')');
                    }
                }
                if (tops.length() > 0 && tops.toString().split("no-wall").length > 2) why = "no wall either side" + tops;
            }
            if (why == null) {
                for (int along = -2; along <= 2 && why == null; along++) {
                    BlockPos c = cell(a, side, along);
                    BlockPos f = floorAt(level, c.getX(), c.getZ(), a.getY());
                    if (f == null) why = "no floor at " + along;
                    else for (int h = 0; h <= 2 && why == null; h++) {
                        BlockState st = level.getBlockState(f.above(h));
                        if (!st.isAir() && !st.canBeReplaced()) {
                            why = "blocked at " + along + " by " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                        }
                    }
                }
            }
            sb.append(why == null ? "ok" : why);
        }
        int noTop = 0, blocked = 0, noFoot = 0;
        for (Direction side : SIDES) {
            for (int along : POSTS) {
                BlockPos c = cell(a, side, along);
                if (!level.isLoaded(c)) continue;
                BlockPos top = wallTop(level, c.getX(), c.getZ(), a.getY());
                if (top == null) { noTop++; continue; }
                if (!level.getBlockState(top.above()).isAir() || !level.getBlockState(top.above(2)).isAir()) { blocked++; continue; }
                BlockPos in = c.relative(side.getOpposite());
                BlockPos foot = floorAt(level, in.getX(), in.getZ(), a.getY());
                if (foot == null || foot.getY() > top.getY()) noFoot++;
            }
        }
        sb.append("; posts: ").append(noTop).append(" no wall top, ").append(blocked).append(" blocked, ").append(noFoot).append(" no foot");
        return sb.toString();
    }

    /** The lower half of a door in this column near this height, or null. */
    @Nullable
    private static BlockPos doorIn(ServerLevel level, BlockPos c, int aroundY) {
        for (int y = aroundY - 6; y <= aroundY + 6; y++) {
            BlockPos p = new BlockPos(c.getX(), y, c.getZ());
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) return p;
        }
        return null;
    }

    @Nullable
    private static Gate gate(ServerLevel level, Villages.Village v, BlockPos a, Direction side, boolean free) {
        for (int along = -2; along <= 2; along++) if (!level.isLoaded(cell(a, side, along))) return null;
        // Hung already: its doors are what is in the gap.
        List<BlockPos> doors = new ArrayList<>();
        for (int along = -1; along <= 1; along++) {
            BlockPos d = doorIn(level, cell(a, side, along), a.getY());
            if (d != null) doors.add(d);
        }
        if (!doors.isEmpty()) {
            return new Gate(side, List.copyOf(doors), doors.get(doors.size() / 2).relative(side.getOpposite(), 2));
        }
        // Only where there is wall either side: a gate in a gap with no wall round it is a door in a field.
        boolean walled = false;
        for (int along : new int[]{ -3, 3, -4, 4 }) {
            BlockPos c = cell(a, side, along);
            if (wallTop(level, c.getX(), c.getZ(), a.getY()) != null) walled = true;
        }
        if (!walled) return null;
        List<BlockPos> floors = new ArrayList<>();
        for (int along = -2; along <= 2; along++) {
            BlockPos c = cell(a, side, along);
            BlockPos f = floorAt(level, c.getX(), c.getZ(), a.getY());
            if (f == null) return null;
            floors.add(f);
        }
        // Everything it needs must be free: where a player has built something, no gate.
        for (BlockPos f : floors) {
            for (int h = 0; h <= 2; h++) {
                BlockState s = level.getBlockState(f.above(h));
                if (!s.isAir() && !s.canBeReplaced()) return null;
            }
        }
        // Six planks for the doors (or two logs: the stores keep the woodcutters' logs, and seldom
        // planks), ten stone for the posts and the lintel, and three slabs to cap them: the masons'
        // stone bricks if the stores run to it (Masonry), else plain cobblestone, three of it cut into
        // six slabs and the other three put back.
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState cap = Blocks.STONE_BRICK_SLAB.defaultBlockState();
        if (!free) {
            Map<net.minecraft.world.item.Item, Integer> dressed = new java.util.LinkedHashMap<>();
            dressed.put(Items.STONE_BRICKS, 10);
            dressed.put(Items.STONE_BRICK_SLAB, 3);
            boolean fine = Masonry.canAll(level, v, dressed);
            if (!fine && Market.stock(level, v.id(), st -> st.is(Items.COBBLESTONE)) < 13) return null;
            if (!TownJobs.atWork(level, v, "watch", floors.get(2), "hanging the " + side.getName() + " gate", AssistantEntity.StationTask.GUARD)) return null;
            boolean wood = TownWork.take(level, v, st -> st.is(ItemTags.PLANKS), 6)
                || TownWork.take(level, v, st -> st.is(ItemTags.LOGS), 2);
            if (!wood) return null;
            boolean paid = fine && Masonry.takeAll(level, v, dressed);
            if (!paid && TownWork.take(level, v, st -> st.is(Items.COBBLESTONE), 13)) {
                TownWork.give(level, v, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE_SLAB, 3));
                stone = Blocks.COBBLESTONE.defaultBlockState();
                cap = Blocks.COBBLESTONE_SLAB.defaultBlockState();
                paid = true;
            }
            if (!paid) {
                TownWork.give(level, v, new net.minecraft.world.item.ItemStack(Items.OAK_PLANKS, 6));
                return null;
            }
        }
        for (int i : new int[]{ 0, 4 }) {
            for (int h = 0; h <= 2; h++) level.setBlock(floors.get(i).above(h), stone, 3);
        }
        for (int i = 1; i <= 3; i++) {
            BlockPos f = floors.get(i);
            level.setBlock(f.above(2), stone, 3);
            BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, side)
                .setValue(DoorBlock.HINGE, i == 1 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT)
                .setValue(DoorBlock.OPEN, true);
            level.setBlock(f, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
            level.setBlock(f.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            doors.add(f);
        }
        for (int i : new int[]{ 0, 2, 4 }) {
            BlockPos top = floors.get(i).above(3);
            if (level.getBlockState(top).isAir()) level.setBlock(top, cap, 3);
        }
        if (!free) Villages.tell(v.id(), level.getDayTime() / 24000L, "the " + side.getName() + " gate was hung");
        return new Gate(side, List.copyOf(doors), floors.get(2).relative(side.getOpposite(), 2));
    }

    /** Shut (or open) every gate of the village. Returns how many doors moved. */
    public static int shut(ServerLevel level, UUID village, boolean shut) {
        int moved = 0;
        for (Gate g : gates(level, village)) {
            for (BlockPos d : g.doors()) {
                BlockState s = level.getBlockState(d);
                if (!(s.getBlock() instanceof DoorBlock door)) continue;
                if (s.getValue(DoorBlock.OPEN) == !shut) continue;
                door.setOpen(null, level, s, d, !shut);
                moved++;
            }
        }
        SHUT.put(village, shut);
        return moved;
    }

    /** Has the watch set the gates since the server started? (After a restart they may stand shut by day.) */
    public static boolean knows(UUID village) {
        return SHUT.containsKey(village);
    }

    /** Are the gates shut now (as far as the watch last left them)? */
    public static boolean isShut(UUID village) {
        return SHUT.getOrDefault(village, false);
    }

    /** The nearest gate to a spot, or null. */
    @Nullable
    public static Gate nearestGate(ServerLevel level, UUID village, BlockPos from) {
        Gate best = null;
        double bd = Double.MAX_VALUE;
        for (Gate g : gates(level, village)) {
            double d = g.inside().distSqr(from);
            if (d < bd) { bd = d; best = g; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the bell

    private static final Map<UUID, BlockPos> CHAPEL_BELL = new ConcurrentHashMap<>();

    /** The alarm bell on the square, put up if it is missing. Returns where it hangs, or null. */
    @Nullable
    public static BlockPos bell(ServerLevel level, Villages.Village v, boolean free) {
        // The town bell's own frame on the square (BellFrame): the alarm is rung on the town's bell, and no
        // second bell is hung for it.
        if (!free && BellFrame.of(v.id()) != null) return TownBell.bellAt(level, v);
        BlockPos at = v.centre().offset(BELL_AT[0], 0, BELL_AT[1]);
        if (!level.isLoaded(at)) return null;
        for (int y = v.centre().getY() - 6; y <= v.centre().getY() + 7; y++) {
            BlockPos p = new BlockPos(at.getX(), y, at.getZ());
            if (level.getBlockState(p).is(Blocks.BELL)) return p;
        }
        BlockPos floor = floorAt(level, at.getX(), at.getZ(), v.centre().getY());
        if (floor == null) return null;
        // The bell itself out of the stores too, now, and not cast from nothing: no bell put by,
        // no bell on the square (and no plinth for it) until one is. Nobody in a village can make a
        // bell; one found, bought or brought by a player is hung, and without one the watch shouts.
        BlockState plinth = Blocks.STONE_BRICKS.defaultBlockState();
        if (!free) {
            if (Market.stock(level, v.id(), st -> st.is(Items.BELL)) == 0) return null;
            if (!TownJobs.atWork(level, v, "watch", floor, "hanging the alarm bell", AssistantEntity.StationTask.GUARD)) return null;
            if (!TownWork.take(level, v, st -> st.is(Items.BELL), 1)) return null;
            // Its plinth of the masons' stone bricks, or of cobble: what the stores paid is what is laid.
            if (TownWork.take(level, v, st -> st.is(Items.STONE_BRICKS), 1)) {
                plinth = Blocks.STONE_BRICKS.defaultBlockState();
            } else if (TownWork.take(level, v, st -> st.is(Items.COBBLESTONE), 1)) {
                plinth = Blocks.COBBLESTONE.defaultBlockState();
            } else {
                TownWork.give(level, v, new net.minecraft.world.item.ItemStack(Items.BELL));
                return null;
            }
        }
        level.setBlock(floor, plinth, 3);
        BlockPos b = floor.above();
        level.setBlock(b, Blocks.BELL.defaultBlockState()
            .setValue(BellBlock.FACING, Direction.NORTH)
            .setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR), 3);
        return b;
    }

    /** The chapel's bell, if the village has a chapel with one. */
    @Nullable
    private static BlockPos chapelBell(ServerLevel level, UUID village) {
        BlockPos known = CHAPEL_BELL.get(village);
        if (known != null && level.getBlockState(known).is(Blocks.BELL)) return known;
        BlockPos chapel = Villages.builtAt(village, "chapel");
        if (chapel == null || !level.isLoaded(chapel)) return null;
        for (BlockPos p : BlockPos.betweenClosed(chapel.offset(-11, 6, -11), chapel.offset(11, 12, 11))) {
            if (level.getBlockState(p).is(Blocks.BELL)) {
                CHAPEL_BELL.put(village, p.immutable());
                return p.immutable();
            }
        }
        return null;
    }

    /** Ring the alarm: the square's bell, and the chapel's. */
    public static void ring(ServerLevel level, Villages.Village v) {
        List<BlockPos> bells = new ArrayList<>();
        BlockPos square = bell(level, v, false);
        if (square != null) bells.add(square);
        BlockPos chapel = chapelBell(level, v.id());
        if (chapel != null) bells.add(chapel);
        for (BlockPos b : bells) {
            BlockState s = level.getBlockState(b);
            if (s.getBlock() instanceof BellBlock bell) bell.attemptToRing(level, b, s.getValue(BellBlock.FACING));
        }
        if (bells.isEmpty()) {
            level.playSound(null, v.centre(), net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                net.minecraft.sounds.SoundSource.BLOCKS, 3.0F, 1.0F);
        }
    }

    /** Is this spot inside the wall? */
    public static boolean inside(UUID village, BlockPos heart, BlockPos p) {
        return Math.max(Math.abs(p.getX() - heart.getX()), Math.abs(p.getZ() - heart.getZ())) < R;
    }
}
