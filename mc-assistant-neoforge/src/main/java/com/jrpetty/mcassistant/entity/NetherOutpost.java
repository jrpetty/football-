package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.portal.PortalShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [nether] The runners' outpost, and the ways they make out from it.
 *
 * <p><b>The room.</b> On their first run the runners wall in the portal on the Nether side: a room of the stores'
 * cobblestone round it (a ghast's fireball does not go through cobblestone the way it does netherrack), its floor made
 * good where the Nether left it open or burning, its roof over the portal's top, the netherrack inside cut away, a wooden
 * door in its front wall, and its lights: soul lanterns hung from the roof when the town has them (a piglin keeps its
 * distance from soul fire), else torches. A block at a time, each of the team placing from its own pack, the walls
 * first. Kept with the town (Ledger "nether.outpost"); short of cobblestone, it is finished on the next run. Every run
 * after looks it over and makes good what a ghast knocked out (mend).
 *
 * <p><b>The camp.</b> On a run of more than a day the team sleeps in the outpost: in at dusk, the door shut, one on watch
 * by turns, out again at first light. Hurt, a runner falls back to it (NetherRuns.safety).
 *
 * <p><b>The portal.</b> A portal gone dark (a ghast's fireball puts one out) is lit again with the flint and steel the
 * leader carries (relight), on either side.
 *
 * <p><b>The ways out</b> (cut): where there is no walking to what they are after, the runners make the way a block at a
 * time, as a player does: the netherrack ahead cut two high (three where it climbs), a floor of cobblestone laid where
 * there is none (a bridge over a drop or the lava), the lava beside the way walled off before it is opened, a rail of
 * cobblestone along an edge, and a light every eight blocks. What they make stays, and the next run walks it: a lit,
 * walled way toward the fortress, a little further every run.
 */
public final class NetherOutpost {

    private NetherOutpost() {}

    /** The room as built round a portal: the portal's lowest, first block, the way along it, its front, its size. */
    public record Room(BlockPos portal, Direction.Axis axis, Direction front, int w, int h, long day, boolean built) {
        /** A spot in the room's own terms: along the portal, out in front of it, up from its floor. */
        public BlockPos at(int a, int c, int up) {
            Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
            return portal.relative(along, a).relative(front, c).above(up);
        }

        /** Somewhere to stand inside, in front of the portal. */
        public BlockPos inside() {
            return at(Math.max(0, w / 2 - 1), 1, 0);
        }

        /** Is this one of the portal's blocks (or its frame)? */
        public boolean has(BlockPos p) {
            for (int a = -1; a <= w; a++) for (int up = -1; up <= h; up++) if (at(a, 0, up).equals(p)) return true;
            return false;
        }

        /** Outside the door, a step out. */
        public BlockPos outside() {
            return at(0, 4, 0);
        }

        String encode() {
            return portal.getX() + "|" + portal.getY() + "|" + portal.getZ() + "|" + axis.getName() + "|" + front.getName() + "|" + w + "|" + h + "|" + day + "|"
                + (built ? 1 : 0);
        }

        @Nullable
        static Room decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 9) return null;
            try {
                Direction.Axis axis = Direction.Axis.byName(p[3]);
                Direction front = Direction.byName(p[4]);
                if (axis == null || front == null) return null;
                return new Room(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), axis, front,
                    Integer.parseInt(p[5]), Integer.parseInt(p[6]), Long.parseLong(p[7]), "1".equals(p[8]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        Room done(long when) {
            return new Room(portal, axis, front, w, h, when, true);
        }
    }

    /** A block of the room's work: where, and what (FLOOR, WALL, ROOF to fill; CLEAR to cut; DOOR; LIGHT). */
    record Cell(BlockPos pos, int kind) {}

    static final int FLOOR = 0, WALL = 1, ROOF = 2, CLEAR = 3, DOOR = 4, LIGHT = 5;

    /** The room's work list while it is being built, by town. */
    private static final Map<UUID, List<Cell>> PLANS = new ConcurrentHashMap<>();
    /** Tests: building done quickly (a block a step whatever the reach). */
    private static boolean quick;

    static void resetForTests() {
        PLANS.clear();
        quick = false;
    }

    public static void quickForTests(boolean on) {
        quick = on;
    }

    /** The town's outpost as kept, or null. */
    @Nullable
    public static Room room(@Nullable UUID village) {
        String s = village == null ? null : Ledger.note(village, "nether.outpost");
        return s == null || s.isEmpty() ? null : Room.decode(s);
    }

    /** Has the town its outpost, walled in? */
    public static boolean built(@Nullable UUID village) {
        Room r = room(village);
        return r != null && r.built();
    }

    static void keep(UUID village, Room r) {
        Ledger.note(village, "nether.outpost", r.encode());
    }

    // ------------------------------------------------------------------ the room, planned

    /** The portal round a block of it: its lowest, first block (along its axis), its width and height. Null if it is no portal. */
    @Nullable
    static Room measure(ServerLevel level, BlockPos any, Direction front) {
        BlockState st = level.getBlockState(any);
        if (!st.is(Blocks.NETHER_PORTAL)) return null;
        Direction.Axis axis = st.getValue(NetherPortalBlock.AXIS);
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        BlockPos p = any;
        while (level.getBlockState(p.below()).is(Blocks.NETHER_PORTAL)) p = p.below();
        while (level.getBlockState(p.relative(along.getOpposite())).is(Blocks.NETHER_PORTAL)) p = p.relative(along.getOpposite());
        int w = 1, h = 1;
        while (w < 21 && level.getBlockState(p.relative(along, w)).is(Blocks.NETHER_PORTAL)) w++;
        while (h < 21 && level.getBlockState(p.above(h)).is(Blocks.NETHER_PORTAL)) h++;
        return new Room(p.immutable(), axis, front, w, h, -1, false);
    }

    /** The portal's open side: the one of its two faces with the most ground to stand on in front of it. */
    static Direction openSide(ServerLevel level, BlockPos portal) {
        BlockState st = level.getBlockState(portal);
        Direction.Axis axis = st.hasProperty(NetherPortalBlock.AXIS) ? st.getValue(NetherPortalBlock.AXIS) : Direction.Axis.X;
        Direction a = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        int sa = 0, sb = 0;
        for (int k = 1; k <= 5; k++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (CaveDwellers.standable(level, portal.relative(a, k).above(dy))) sa++;
                if (CaveDwellers.standable(level, portal.relative(a.getOpposite(), k).above(dy))) sb++;
            }
        }
        return sa >= sb ? a : a.getOpposite();
    }

    /** The room's work, in the order it is done: the floor, the walls from the bottom up, the roof, the inside cut out,
     *  the door, the lights. */
    static List<Cell> plan(Room r) {
        List<Cell> out = new ArrayList<>();
        int w = r.w(), h = r.h();
        // The floor under everything inside but the frame itself.
        for (int a = -2; a <= w + 1; a++) {
            for (int c = -1; c <= 2; c++) {
                if (c == 0 && a >= -1 && a <= w) continue;
                out.add(new Cell(r.at(a, c, -1), FLOOR));
            }
        }
        // The walls, a ring round it all, from the floor's level up to the portal's top.
        for (int up = -1; up <= h; up++) {
            for (int a = -3; a <= w + 2; a++) {
                for (int c = -2; c <= 3; c++) {
                    boolean ring = a == -3 || a == w + 2 || c == -2 || c == 3;
                    if (!ring) continue;
                    boolean door = c == 3 && a == 0 && (up == 0 || up == 1);
                    out.add(new Cell(r.at(a, c, up), door ? CLEAR : WALL));
                }
            }
        }
        // The roof.
        for (int a = -3; a <= w + 2; a++) for (int c = -2; c <= 3; c++) out.add(new Cell(r.at(a, c, h + 1), ROOF));
        // The inside, cut out.
        for (int up = 0; up <= h; up++) {
            for (int a = -2; a <= w + 1; a++) {
                for (int c = -1; c <= 2; c++) {
                    if (c == 0 && a >= -1 && a <= w) continue;
                    out.add(new Cell(r.at(a, c, up), CLEAR));
                }
            }
        }
        out.add(new Cell(r.at(0, 3, 0), DOOR));
        out.add(new Cell(r.at(-2, 2, h), LIGHT));
        out.add(new Cell(r.at(w + 1, 2, h), LIGHT));
        out.add(new Cell(r.at(-2, -1, h), LIGHT));
        return out;
    }

    /** Is this block solid enough for a wall or a roof (netherrack, cobblestone, the Nether's stone): nothing to do there. */
    static boolean solid(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (st.isAir() || !level.getFluidState(p).isEmpty() || st.canBeReplaced() || st.is(BlockTags.FIRE)) return false;
        return st.isCollisionShapeFullBlock(level, p) || st.is(Blocks.NETHER_PORTAL) || st.is(Blocks.OBSIDIAN);
    }

    /** A floor good to stand on: solid on top, dry, not burning. */
    static boolean floor(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.isFaceSturdy(level, p, Direction.UP) && level.getFluidState(p).isEmpty() && !st.is(Blocks.MAGMA_BLOCK);
    }

    /** May the runners cut this out of the inside of the room (the Nether's own: its stone, sand, wart, roots)? */
    static boolean natural(BlockState st) {
        return st.is(BlockTags.BASE_STONE_NETHER) || st.is(BlockTags.NYLIUM) || st.is(BlockTags.WART_BLOCKS) || st.is(BlockTags.SOUL_FIRE_BASE_BLOCKS)
            || st.is(Blocks.GRAVEL) || st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.GLOWSTONE) || st.is(Blocks.NETHER_QUARTZ_ORE)
            || st.is(Blocks.NETHER_GOLD_ORE) || st.is(Blocks.SHROOMLIGHT) || st.is(Blocks.BONE_BLOCK) || st.is(Blocks.COBBLESTONE)
            || st.canBeReplaced() || st.is(BlockTags.FIRE) || st.is(Blocks.NETHER_SPROUTS) || st.is(Blocks.CRIMSON_ROOTS) || st.is(Blocks.WARPED_ROOTS)
            || st.is(Blocks.CRIMSON_FUNGUS) || st.is(Blocks.WARPED_FUNGUS) || st.is(Blocks.TWISTING_VINES) || st.is(Blocks.WEEPING_VINES);
    }

    /** Is this cell of the room's work still to do? */
    static boolean todo(ServerLevel level, Cell c) {
        BlockPos p = c.pos();
        BlockState st = level.getBlockState(p);
        return switch (c.kind()) {
            case FLOOR -> !floor(level, p) && (st.isAir() || st.canBeReplaced() || !level.getFluidState(p).isEmpty() || st.is(BlockTags.FIRE) || st.is(Blocks.MAGMA_BLOCK));
            case WALL, ROOF -> !solid(level, p) && !st.is(BlockTags.DOORS);
            case CLEAR -> !st.isAir() && !st.is(BlockTags.DOORS) && !st.is(Blocks.NETHER_PORTAL) && (natural(st) || !level.getFluidState(p).isEmpty());
            case DOOR -> !st.is(BlockTags.DOORS);
            case LIGHT -> !st.is(Blocks.SOUL_LANTERN) && !st.is(Blocks.LANTERN) && !st.is(Blocks.TORCH) && !st.is(Blocks.SOUL_TORCH)
                && !st.is(Blocks.WALL_TORCH) && !st.is(Blocks.SOUL_WALL_TORCH);
            default -> false;
        };
    }

    /**
     * The room begun round the portal the team came through: planned (the open side its front) and kept with the town.
     * False if there is no portal there to build round.
     */
    static boolean begin(ServerLevel level, VillageFolkEntity lead, NetherRuns.Run r) {
        BlockPos portal = r.netherPortal;
        if (portal == null) return false;
        Room had = room(r.village);
        Room room = had != null && had.has(portal) ? had : measure(level, portal, openSide(level, portal));
        if (room == null) return false;
        keep(r.village, room);
        PLANS.put(r.village, plan(room));
        FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "First things first: we wall this portal in. Cobblestone out, everybody.",
            "Before anything else, a room round the portal. A ghast's fireball goes through netherrack like paper."));
        r.event("the runners walled in the portal on the far side");
        NetherRuns.LOG.info("[MCA-NETHER] {} begins the outpost round the portal at {} ({} by {}, front {})", lead.displayNameCap(),
            room.portal().toShortString(), room.w(), room.h(), room.front().getName());
        return true;
    }

    /**
     * One runner's part of the room (each step): the next block of the work it can reach, placed out of its own pack or
     * cut out, a block at a time; the walls first. True while there is work left it can do; the leader marks the room
     * built when none is left (or the cobblestone has run out, to be finished on the next run).
     */
    static boolean work(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, boolean leading) {
        Room room = room(r.village);
        List<Cell> plan = PLANS.get(r.village);
        if (room == null) return false;
        if (plan == null) {
            plan = plan(room);
            PLANS.put(r.village, plan);
        }
        int left = 0, fills = 0;
        Cell best = null;
        double near = Double.MAX_VALUE;
        boolean cobble = f.countMatching(s -> s.is(Items.COBBLESTONE)) > 0;
        for (Cell c : plan) {
            if (!todo(level, c)) continue;
            left++;
            boolean fill = c.kind() == FLOOR || c.kind() == WALL || c.kind() == ROOF;
            if (fill) fills++;
            if (fill && !cobble) continue;
            if (c.kind() == DOOR && f.countMatching(s -> s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)) == 0) continue;
            if (c.kind() == LIGHT && light(f) == null) continue;
            // The order matters (the walls before the inside is opened up): only the earliest kind left is worked.
            if (best != null && c.kind() > best.kind()) continue;
            double d = f.distanceToSqr(c.pos().getX() + 0.5, c.pos().getY() + 0.5, c.pos().getZ() + 0.5);
            if (best == null || c.kind() < best.kind() || d < near) {
                best = c;
                near = d;
            }
        }
        if (best == null) {
            if (leading) {
                boolean teamCobble = false;
                for (VillageFolkEntity m : NetherRuns.here(level, r)) if (m.countMatching(s -> s.is(Items.COBBLESTONE)) > 0) teamCobble = true;
                if (left == 0 || fills > 0 && !teamCobble || left > 0 && !teamCobble) {
                    finish(level, f, r, room, left);
                    return false;
                }
            }
            f.hobbyNow = "at the outpost, out of cobblestone; the others are finishing it";
            return left > 0;
        }
        BlockPos p = best.pos();
        double reach = quick ? 64.0 : 5.5 * 5.5;
        if (near > reach) {
            if (f.getNavigation().isDone() || level.getGameTime() - leg.stillTick > 40) {
                BlockPos stand = room.inside();
                f.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0D);
                leg.stillTick = level.getGameTime();
            }
            if (near > 12 * 12 && level.getGameTime() - leg.stillTick > 200) {
                f.getMoveControl().setWantedPosition(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0D);
            }
            f.hobbyNow = "walling in the portal on the far side";
            return true;
        }
        f.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        switch (best.kind()) {
            case FLOOR, WALL, ROOF -> place(level, f, r, p, Blocks.COBBLESTONE.defaultBlockState());
            case CLEAR -> {
                BlockState st = level.getBlockState(p);
                if (!level.getFluidState(p).isEmpty() && f.countMatching(s -> s.is(Items.COBBLESTONE)) > 0) {
                    place(level, f, r, p, Blocks.COBBLESTONE.defaultBlockState());     // lava inside: stopped, then cut out
                } else {
                    NetherWork.mine(level, f, r, NetherRuns.runOf(f) == null ? null : r.legs.get(f.getUUID()), p, st, false);
                }
            }
            case DOOR -> door(level, f, room, p);
            case LIGHT -> hang(level, f, r, p);
            default -> { }
        }
        f.hobbyNow = "walling in the portal on the far side";
        return true;
    }

    /** A block of cobblestone out of its pack, put here. */
    static boolean place(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, BlockPos p, BlockState st) {
        if (f.removeMatching(s -> s.is(Items.COBBLESTONE), 1) < 1) return false;
        level.setBlockAndUpdate(p, st);
        f.placeSound(p);
        f.swing(InteractionHand.MAIN_HAND);
        Economy.usedGiven(f, new ItemStack(Items.COBBLESTONE), 1);
        r.placed++;
        return true;
    }

    /** The room's door, out of its pack: a wooden door, facing out. */
    static void door(ServerLevel level, VillageFolkEntity f, Room room, BlockPos p) {
        ItemStack held = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)) { held = s; break; }
        if (held.isEmpty() || !(held.getItem() instanceof net.minecraft.world.item.BlockItem bi) || !(bi.getBlock() instanceof DoorBlock door)) return;
        if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) {
            for (BlockPos q : new BlockPos[]{ p, p.above() }) if (!level.getBlockState(q).isAir()) level.destroyBlock(q, false, f);
        }
        held.shrink(1);
        BlockState lower = door.defaultBlockState().setValue(DoorBlock.FACING, room.front().getOpposite())
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(p, lower, 3);
        level.setBlock(p.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        f.placeSound(p);
        f.swing(InteractionHand.MAIN_HAND);
    }

    /** The best light it carries for the outpost (a soul lantern keeps piglins off), or null. */
    @Nullable
    static Block light(VillageFolkEntity f) {
        if (f.countMatching(s -> s.is(Items.SOUL_LANTERN)) > 0) return Blocks.SOUL_LANTERN;
        if (f.countMatching(s -> s.is(Items.LANTERN)) > 0) return Blocks.LANTERN;
        if (f.countMatching(s -> s.is(Items.SOUL_TORCH)) > 0) return Blocks.SOUL_TORCH;
        if (f.countMatching(s -> s.is(Items.TORCH)) > 0) return Blocks.TORCH;
        return null;
    }

    /** A light up: a lantern hung from the roof over it, or a torch on the floor under it. */
    static void hang(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, BlockPos p) {
        Block light = light(f);
        if (light == null) return;
        BlockPos at = p;
        BlockState st;
        if (light == Blocks.SOUL_LANTERN || light == Blocks.LANTERN) {
            st = light.defaultBlockState().setValue(LanternBlock.HANGING, true);
            if (!st.canSurvive(level, at)) {
                st = light.defaultBlockState();
                at = floorUnder(level, p);
            }
        } else {
            st = light.defaultBlockState();
            at = floorUnder(level, p);
        }
        if (at == null || !level.getBlockState(at).isAir() || !st.canSurvive(level, at)) {
            // Nowhere it holds: given up on (marked with a torch-less nothing: the next cell).
            PLANS.computeIfPresent(r.village, (k, l) -> { l.removeIf(c -> c.pos().equals(p) && c.kind() == LIGHT); return l; });
            return;
        }
        net.minecraft.world.item.Item it = light.asItem();
        if (f.removeMatching(s -> s.is(it), 1) < 1) return;
        level.setBlockAndUpdate(at, st);
        f.placeSound(at);
        f.swing(InteractionHand.MAIN_HAND);
        r.torches++;
        if (at != p) PLANS.computeIfPresent(r.village, (k, l) -> { l.removeIf(c -> c.pos().equals(p) && c.kind() == LIGHT); return l; });
    }

    /** The open block over the floor below this one (where a torch would stand). */
    @Nullable
    static BlockPos floorUnder(ServerLevel level, BlockPos p) {
        for (int dy = 0; dy >= -5; dy--) {
            BlockPos q = p.above(dy);
            if (level.getBlockState(q).isAir() && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) return q;
        }
        return null;
    }

    /** The room done (or as done as the cobblestone allowed): kept with the town, noted, told. */
    static void finish(ServerLevel level, VillageFolkEntity lead, NetherRuns.Run r, Room room, int left) {
        long day = level.getDayTime() / 24000L;
        boolean whole = left == 0 || countLeft(level, r.village, room) == 0;
        Room done = whole ? room.done(room.day() < 0 ? day : room.day()) : room;
        keep(r.village, done);
        PLANS.remove(r.village);
        r.outpostBuilt = whole;
        r.found.add(new NetherRuns.Find(NetherRuns.Kind.OUTPOST, "the outpost" + (whole ? "" : " (half built)"), room.portal(), day, lead.displayNameCap(),
            r.placed, whole ? 1 : 0));
        if (whole) {
            Villages.tell(r.village, day, "the Nether runners walled in the portal on the far side: the town has an outpost in the Nether");
            FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "There. Four walls, a roof and a door between us and the Nether. Now, to work.",
                "The outpost's done. Whatever happens out there, this is where we come back to."));
        } else {
            Villages.tell(r.village, day, "the Nether runners began an outpost round the portal on the far side, and ran out of cobblestone");
            FolkTalk.speak(lead, "That's all the cobblestone. It'll keep the worst off; we finish it next time.");
        }
        NetherRuns.LOG.info("[MCA-NETHER] the outpost of {} {} ({} blocks placed, {} left)", Villages.name(r.village), whole ? "built" : "half built", r.placed, left);
    }

    private static int countLeft(ServerLevel level, UUID village, Room room) {
        int n = 0;
        for (Cell c : plan(room)) if (c.kind() != LIGHT && c.kind() != DOOR && todo(level, c)) n++;
        return n;
    }

    /** Tests: how much of the room's work is left (the walls, floor, roof and inside; the door and lights besides). */
    public static int leftForTests(ServerLevel level, UUID village) {
        Room room = room(village);
        return room == null ? -1 : countLeft(level, village, room);
    }

    /** Every run after the first: the room looked over, and what a ghast knocked out made good (the walls, the roof, the
     *  floor) out of the team's cobblestone, by the leader on its way past. */
    static void mend(ServerLevel level, VillageFolkEntity lead, NetherRuns.Run r) {
        Room room = room(r.village);
        if (room == null) return;
        int fixed = 0;
        for (Cell c : plan(room)) {
            if (c.kind() != WALL && c.kind() != ROOF && c.kind() != FLOOR || !todo(level, c)) continue;
            if (!place(level, lead, r, c.pos(), Blocks.COBBLESTONE.defaultBlockState())) break;
            fixed++;
            if (fixed >= 24) break;
        }
        if (fixed > 0) {
            r.event("the outpost was mended (" + fixed + " blocks a ghast had knocked out)");
            FolkTalk.speak(lead, "Something's had a go at the walls. Patched.");
        }
    }

    /** Where a runner just through steps to, out of the portal: inside the room, or two out on the open side. */
    @Nullable
    static BlockPos stepOut(ServerLevel level, NetherRuns.Run r, BlockPos from) {
        Room room = room(r.village);
        if (room != null && room.has(from)) return room.inside();
        BlockPos portal = r.netherPortal != null ? r.netherPortal : from;
        Direction side = openSide(level, portal);
        for (int k = 2; k <= 4; k++) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos q = portal.relative(side, k).above(dy);
                if (CaveDwellers.standable(level, q)) return q;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the night in the outpost

    static final long DUSK = 12500, DAWN = 300;

    /** Dusk on a run with more of it to come: into the outpost for the night. */
    static boolean nightfall(ServerLevel level, NetherRuns.Run r) {
        long now = level.getDayTime(), t = now % 24000L;
        return (t >= DUSK || t < DAWN) && r.turnAt > now + 3000L;
    }

    /** The leader takes the team into the outpost for the night. */
    static void pitch(ServerLevel level, VillageFolkEntity lead, NetherRuns.Run r) {
        r.phase(NetherRuns.Run.Phase.CAMP, level.getGameTime());
        r.camped = level.getDayTime();
        r.nights++;
        r.task = null;
        FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "That's the day. Into the outpost — door shut, and we take turns on watch.",
            "Back to the outpost for the night, everybody."));
        NetherRuns.keep(r);
    }

    /** A runner's night: into the room, the door shut behind it, asleep but for the one on watch; out at first light. */
    static void camp(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, boolean leading) {
        long now = level.getDayTime(), t = now % 24000L;
        if (t >= DAWN && t < DUSK && now - r.camped > 4000L) {
            if (leading) {
                for (VillageFolkEntity m : NetherRuns.here(level, r)) {
                    m.setShiftKeyDown(false);
                    m.eatFromPack();
                }
                r.phase(NetherRuns.Run.Phase.WORK, level.getGameTime());
                FolkTalk.speak(f, "Morning — or what passes for it down here. Up, and on.");
            }
            return;
        }
        Room room = room(r.village);
        BlockPos in = room != null && room.built() ? room.inside() : r.netherPortal != null ? r.netherPortal : f.blockPosition();
        List<VillageFolkEntity> team = NetherRuns.here(level, r);
        int i = Math.max(0, team.indexOf(f));
        BlockPos spot = room != null && room.built() ? room.at(i % 2 == 0 ? -2 : room.w() + 1, 1 + i / 2, 0) : in;
        if (f.blockPosition().distSqr(spot) > 2) {
            if (f.getNavigation().isDone()) f.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D);
            f.hobbyNow = "making for the outpost for the night";
            return;
        }
        f.getNavigation().stop();
        if (!leg.retreating && f.getHealth() < f.getMaxHealth()) f.eatFromPack();
        int n = Math.max(1, team.size());
        boolean watch = team.indexOf(f) == (int) ((now / 2000L) % n);
        f.setShiftKeyDown(!watch);
        f.hobbyNow = watch ? "keeping watch in the outpost, in the Nether" : "asleep in the outpost, in the Nether";
        if (leading && room != null && room.built()) shutTheDoor(level, room);
    }

    private static void shutTheDoor(ServerLevel level, Room room) {
        BlockPos p = room.at(0, 3, 0);
        BlockState st = level.getBlockState(p);
        if (st.getBlock() instanceof DoorBlock door && st.getValue(DoorBlock.OPEN)) door.setOpen(null, level, st, p, false);
    }

    /** At the plan's hour, does the team stay on another half day? When it has its food, its arrows and its health, and
     *  the work is going well; twice at most, never past three days. */
    static boolean stayOn(ServerLevel level, VillageFolkEntity lead, NetherRuns.Run r) {
        if (r.days < 1.0 || r.homeward || r.days + 0.5 > NetherPlan.LONGEST) return false;
        if (r.mined + r.blazes + r.barters < 6 * Math.max(1, (int) r.days)) return false;
        if (NetherWork.nothingLeft(level, lead, r)) return false;
        for (VillageFolkEntity m : NetherRuns.members(level.getServer(), r)) {
            if (m.countMatching(CaveDwellers::food) < NetherPlan.MEALS + 1 || m.getHealth() < m.getMaxHealth() * 0.7F) return false;
        }
        r.days += 0.5;
        r.turnAt += 12000L;
        NetherRuns.keep(r);
        FolkTalk.speak(lead, "It's going well, and we've food yet. Another half day, everybody.");
        return true;
    }

    // ------------------------------------------------------------------ the portal, lit again

    /**
     * A portal gone dark near here lit again with flint and steel out of this one's pack (a use off it), on either side
     * of the gateway. True if there is a lit portal there now.
     */
    static boolean relight(ServerLevel level, VillageFolkEntity f, @Nullable BlockPos near) {
        if (near == null) return false;
        if (NetherRuns.portalNear(level, near, 4) != null) return true;
        Optional<PortalShape> shape = Optional.empty();
        for (BlockPos p : BlockPos.betweenClosed(near.offset(-4, -3, -4), near.offset(4, 4, 4))) {
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).is(Blocks.OBSIDIAN)) continue;
            for (Direction.Axis axis : new Direction.Axis[]{ Direction.Axis.X, Direction.Axis.Z }) {
                shape = PortalShape.findEmptyPortalShape(level, p.immutable(), axis);
                if (shape.isPresent()) break;
            }
            if (shape.isPresent()) break;
        }
        if (shape.isEmpty()) return false;
        ItemStack steel = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (s.is(Items.FLINT_AND_STEEL)) { steel = s; break; }
        if (steel.isEmpty()) return false;
        steel.setDamageValue(steel.getDamageValue() + 1);
        if (steel.getDamageValue() >= steel.getMaxDamage()) steel.shrink(1);
        shape.get().createPortalBlocks();
        level.playSound(null, f.blockPosition(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, "Gone dark — hold on. A strike of the flint... there.");
        NetherRuns.LOG.info("[MCA-NETHER] {} lit the portal again near {}", f.displayNameCap(), near.toShortString());
        return true;
    }

    // ------------------------------------------------------------------ the ways out

    /** A block the way may be cut through: the Nether's own rock, sand and growth (never the fortress's chests, a spawner,
     *  bedrock, obsidian, a portal, ancient debris but with a diamond pick). */
    static boolean cuttable(ServerLevel level, BlockPos p, VillageFolkEntity f) {
        BlockState st = level.getBlockState(p);
        if (st.isAir() || st.is(Blocks.BEDROCK) || st.is(Blocks.OBSIDIAN) || st.is(Blocks.CRYING_OBSIDIAN) || st.is(Blocks.NETHER_PORTAL)
            || st.is(Blocks.SPAWNER) || st.hasBlockEntity() || st.getDestroySpeed(level, p) < 0) return false;
        if (st.is(Blocks.ANCIENT_DEBRIS)) return CaveDwellers.bestPick(f).isCorrectToolForDrops(st);
        return natural(st) || st.is(Blocks.NETHER_BRICKS) || st.is(Blocks.NETHER_BRICK_FENCE) || st.is(BlockTags.STAIRS) || st.is(Blocks.BLACKSTONE)
            || st.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || st.is(Blocks.GILDED_BLACKSTONE) || st.is(Blocks.BASALT) || st.is(Blocks.SMOOTH_BASALT);
    }

    /** Lava in a block. */
    static boolean lava(ServerLevel level, BlockPos p) {
        return level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA);
    }

    /**
     * One block of the way toward somewhere, from where this runner stands: the way along the longer of the two
     * directions, a step up or down toward it; the lava round the new block walled off first; the block (and the head's
     * room over it, and over its own head when it climbs) cut out; a floor laid where there is none; a rail at a drop's
     * edge; a light every eight. Then it steps on. Returns 1 when it stepped, 0 while it works at it, -1 when it cannot
     * (bedrock, a portal, no cobblestone for a bridge it needs, or there).
     */
    static int cut(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, BlockPos to) {
        BlockPos feet = f.blockPosition();
        int dx = to.getX() - feet.getX(), dz = to.getZ() - feet.getZ(), dyAll = to.getY() - feet.getY();
        if (Math.abs(dx) + Math.abs(dz) <= 1 && Math.abs(dyAll) <= 1) return -1;
        Direction dir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (dx == 0 && dz == 0) dir = f.getDirection();
        int dy = dyAll > 0 ? 1 : dyAll < 0 ? -1 : 0;
        BlockPos next = feet.relative(dir).above(dy);
        if (next.getY() <= level.getMinBuildHeight() + 6 || next.getY() >= 120) dy = 0;
        next = feet.relative(dir).above(dy);
        List<BlockPos> open = new ArrayList<>();
        open.add(next);
        open.add(next.above());
        if (dy > 0) {
            open.add(feet.above(2));
            open.add(next.above(2));
        }
        // The lava round the way walled off before it is opened up.
        for (BlockPos o : open) {
            for (Direction d : Direction.values()) {
                BlockPos q = o.relative(d);
                if (open.contains(q) || q.equals(feet) || q.equals(feet.above())) continue;
                if (lava(level, q) || level.getBlockState(q).is(BlockTags.FIRE)) {
                    if (!NetherOutpost.place(level, f, r, q.immutable(), Blocks.COBBLESTONE.defaultBlockState())) return -1;
                    say(f, leg, "Lava! Walling it off — keep back.");
                    return 0;
                }
            }
            if (lava(level, o)) {
                if (!place(level, f, r, o, Blocks.COBBLESTONE.defaultBlockState())) return -1;
                return 0;
            }
        }
        // Cut it out, a block a step (as long as the pick takes).
        for (BlockPos o : open) {
            BlockState st = level.getBlockState(o);
            if (st.isAir() || st.canBeReplaced() && level.getFluidState(o).isEmpty()) continue;
            if (!cuttable(level, o, f)) return -1;
            if (!NetherWork.dig(level, f, r, leg, o)) return 0;
            r.cut++;
            return 0;
        }
        // A floor under it where there is none (a bridge), and a rail at the edge of a drop.
        BlockPos floor = next.below();
        if (!floor(level, floor)) {
            if (!place(level, f, r, floor, Blocks.COBBLESTONE.defaultBlockState())) return -1;
            say(f, leg, FolkTalk.pick(f.getRandom(), "Bridging — stay behind me.", "Cobble down. Watch the edge."));
            return 0;
        }
        for (Direction side : new Direction[]{ dir.getClockWise(), dir.getCounterClockWise() }) {
            BlockPos edge = next.relative(side);
            if (level.getBlockState(edge).isAir() && level.getBlockState(edge.below()).isAir() && level.getBlockState(edge.below(2)).isAir()) {
                if (place(level, f, r, edge, Blocks.COBBLESTONE.defaultBlockState())) return 0;
            }
        }
        // A light every eight blocks of the way.
        leg.cutSteps++;
        if (leg.cutSteps % 8 == 0) {
            Block light = f.countMatching(s -> s.is(Items.TORCH)) > 0 ? Blocks.TORCH : f.countMatching(s -> s.is(Items.SOUL_TORCH)) > 0 ? Blocks.SOUL_TORCH : null;
            BlockPos at = feet;
            if (light != null && level.getBlockState(at).isAir() && light.defaultBlockState().canSurvive(level, at)
                    && f.removeMatching(s -> s.is(light.asItem()), 1) == 1) {
                level.setBlockAndUpdate(at, light.defaultBlockState());
                r.torches++;
            }
        }
        // And on.
        f.getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0D);
        if (dy > 0) f.getJumpControl().jump();
        f.getNavigation().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0D);
        return 1;
    }

    private static void say(VillageFolkEntity f, NetherRuns.Leg leg, String line) {
        long now = f.level().getGameTime();
        if (now - leg.spoke < 200) return;
        leg.spoke = now;
        FolkTalk.speak(f, line);
    }

    // ------------------------------------------------------------------ the chart

    /**
     * The runners' chart, for a player (NetherGuests.chart): a written book of the outpost and what the runners have
     * found, each with where it lies in the Nether and where that is in the town's own world (eight times as far).
     */
    static List<String> chart(UUID village) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.get(village);
        Room room = room(village);
        StringBuilder page = new StringBuilder("The Nether, as the runners of " + Villages.name(village) + " know it.\n\n");
        if (room != null) {
            BlockPos p = room.portal();
            page.append("The outpost (the portal walled in): ").append(p.getX()).append(" ").append(p.getY()).append(" ").append(p.getZ())
                .append(" in the Nether; over the gateway at home.\n");
        } else {
            page.append("No outpost yet.\n");
        }
        out.add(page.toString());
        page = new StringBuilder();
        for (NetherRuns.Find x : NetherRuns.report(village)) {
            if (x.kind() == NetherRuns.Kind.QUARTZ || x.kind() == NetherRuns.Kind.GLOWSTONE || x.kind() == NetherRuns.Kind.OUTPOST) continue;
            BlockPos a = x.at();
            String line = capital(x.label()) + ": " + a.getX() + " " + a.getY() + " " + a.getZ() + " (at home: " + a.getX() * 8 + " " + a.getZ() * 8 + ")"
                + (room != null ? ", " + (int) Math.sqrt(a.distSqr(room.portal())) + " blocks " + Guide.direction(room.portal(), a) + " of the outpost" : "") + ".\n";
            if (page.length() + line.length() > 230) {
                out.add(page.toString());
                page = new StringBuilder();
            }
            page.append(line);
        }
        if (page.length() > 0) out.add(page.toString());
        String high = highwayPlan(village);
        if (!high.isEmpty()) out.add(high);
        if (v != null) out.add("Mind: one block in the Nether is eight at home. Wear gold, and never strike a piglin.");
        return out;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the highway, planned

    /**
     * The Nether highway, as the runners would make it to a colony or an ally: one block in the Nether is eight in the
     * world, so the other town's gateway (when it builds one) wants a portal in the Nether at its coordinates over eight,
     * and a cobbled, lit, walled way between the two portals (cut: the same way the runners make toward the fortress)
     * is an eighth of the overland road. The plan in words, for the chart and the Nether page: "" with no town to go to.
     */
    public static String highwayPlan(UUID village) {
        Villages.Village v = Villages.get(village);
        Room room = room(village);
        if (v == null || room == null) return "";
        // The town's colony or its mother first, else an ally (a pact), the nearest of them: the far towns are the point.
        Villages.Village far = null;
        double best = 0;
        int rank = 0;
        Map<UUID, UUID> links = Ledger.links();
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(village) || !o.dim().equals(v.dim())) continue;
            boolean kin = village.equals(links.get(o.id())) || o.id().equals(links.get(village));
            boolean ally = !kin && Envoys.pact(village, o.id());
            int k = kin ? 2 : ally ? 1 : 0;
            if (k == 0) continue;
            double d = Math.sqrt(Scouts.flat(v.centre(), o.centre()));
            if (far == null || k > rank || k == rank && d < best) {
                far = o;
                best = d;
                rank = k;
            }
        }
        if (far == null) return "";
        BlockPos there = new BlockPos(far.centre().getX() / 8, room.portal().getY(), far.centre().getZ() / 8);
        int way = (int) Math.sqrt(room.portal().distSqr(there));
        return "The highway, planned: " + Villages.name(far.id()) + " lies " + (int) best + " blocks off at home; in the Nether its gateway would come out near "
            + there.getX() + " " + there.getZ() + ", " + way + " blocks " + Guide.direction(room.portal(), there) + " of the outpost. A walled, lit way of "
            + way + " blocks of cobblestone, an eighth of the road, once " + Villages.name(far.id()) + " lights a gateway of its own.";
    }
}
