package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town bell's own frame on the square: two posts with a little roof over them and the bell hung
 * between them, a lantern under each eave.
 *
 * <pre>
 *        slab  stair  ridge  stair  slab        (the roof)
 *        lamp  post   BELL   post   lamp
 *              post          post
 *              post          post               (stone: a post higher, a short belfry)
 *                    ringer                      (in front, facing the square)
 * </pre>
 *
 * <p>It stands a few blocks from the board, its front to the square: on ground of its own that is
 * level and clear, off the gates' ways in, clear of the board, its courtyard, the market stalls, the
 * well and the monuments, and not on a worn path. Built by the town's works (TownJobs) a piece at a
 * time out of the stores: in timber — posts of logs, a roof of the village's own wood cut from its
 * planks (six planks to four stairs, three to six slabs, the rest of each batch back into the stores)
 * — or, once the town is in the Stone Age and its masons have the bricks, in stone bricks a post
 * higher; a lantern under each eave if the smith has made them (a torch on the post if not, or none).
 * When it stands, the town's bell (wherever it hung till then: under the board, on a plinth on the
 * square, in a village the folk moved into) is taken down into the stores and hung in it; or the
 * first bell put in the stores is. A bell in a bell tower or a chapel is rung where it is: the town
 * has its belfry.
 */
public final class BellFrame {

    private BellFrame() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A frame: where it stands (the ground cell under the bell), the way of its beam, its front, and of what. */
    public static final class Frame {
        public final BlockPos origin;
        public final Direction along, front;
        boolean stone, begun;
        String wood = "oak";

        Frame(BlockPos origin, Direction along, Direction front) {
            this.origin = origin.immutable();
            this.along = along;
            this.front = front;
        }

        /** How high its posts stand. */
        public int posts() { return stone ? 4 : 3; }

        BlockPos at(int k, int h) { return origin.relative(along, k).above(h); }

        /** Where the bell hangs. */
        public BlockPos bell() { return at(0, posts() - 1); }

        /** Where the ringer stands: before it, facing the square's way in. */
        public BlockPos stand() { return origin.relative(front); }

        public boolean stone() { return stone; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("At", origin.asLong());
            t.putString("Along", along.getName());
            t.putString("Front", front.getName());
            t.putBoolean("Stone", stone);
            t.putBoolean("Begun", begun);
            t.putString("Wood", wood);
            return t;
        }

        @Nullable
        static Frame load(@Nullable CompoundTag t) {
            if (t == null || !t.contains("At")) return null;
            Direction along = Direction.byName(t.getString("Along")), front = Direction.byName(t.getString("Front"));
            if (along == null || front == null) return null;
            Frame f = new Frame(BlockPos.of(t.getLong("At")), along, front);
            f.stone = t.getBoolean("Stone");
            f.begun = t.getBoolean("Begun");
            f.wood = t.getString("Wood").isEmpty() ? "oak" : t.getString("Wood");
            return f;
        }
    }

    /** The parts of a frame, in the order they go up. */
    enum Part { POST, RIDGE, STAIR, SLAB, LIGHT, BELL }

    record Piece(Part part, int k, int h) {}

    private static final Map<UUID, Frame> FRAMES = new ConcurrentHashMap<>();
    /** When each town last looked for ground for a frame and found none, and its lights' last want. */
    private static final Map<UUID, Long> SOUGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NO_LIGHT = new ConcurrentHashMap<>();
    /** Tests: towns whose frame may be begun on their first day. */
    private static final java.util.Set<UUID> NOW = ConcurrentHashMap.newKeySet();
    /** When each town's stores were last found to have no bell for its frame. */
    private static final Map<UUID, Long> NO_BELL = new ConcurrentHashMap<>();

    public static void resetForTests() {
        FRAMES.clear();
        SOUGHT.clear();
        NO_LIGHT.clear();
        NOW.clear();
        NO_BELL.clear();
    }

    /** A town settled in: past its first day (a camp's first day is for its first buildings). */
    private static boolean settled(ServerLevel level, UUID village) {
        if (NOW.contains(village)) return true;
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(village);
        return founded >= 0 && level.getDayTime() / 24000L - founded >= 1;
    }

    /** The town's frame, planned or standing, or null. */
    @Nullable
    public static Frame of(@Nullable UUID village) {
        if (village == null) return null;
        Frame f = FRAMES.get(village);
        if (f != null) return f;
        f = Frame.load(TownCalendar.frame(village));
        if (f != null) FRAMES.put(village, f);
        return f;
    }

    private static void save(UUID village, @Nullable Frame f) {
        if (f == null) FRAMES.remove(village);
        else FRAMES.put(village, f);
        TownCalendar.frame(village, f == null ? null : f.save());
    }

    /** The pieces of a frame, bottom up: the posts, the ridge, the stairs, the eaves, the lights, the bell. */
    static List<Piece> pieces(Frame f) {
        List<Piece> out = new ArrayList<>();
        int top = f.posts();
        for (int h = 0; h < top; h++) {
            out.add(new Piece(Part.POST, -1, h));
            out.add(new Piece(Part.POST, 1, h));
        }
        out.add(new Piece(Part.RIDGE, 0, top));
        out.add(new Piece(Part.STAIR, -1, top));
        out.add(new Piece(Part.STAIR, 1, top));
        out.add(new Piece(Part.SLAB, -2, top));
        out.add(new Piece(Part.SLAB, 2, top));
        out.add(new Piece(Part.LIGHT, -2, top - 1));
        out.add(new Piece(Part.LIGHT, 2, top - 1));
        out.add(new Piece(Part.BELL, 0, top - 1));
        return out;
    }

    /** Is this piece up (or, for a light, put up some other way: a torch on the post)? */
    static boolean done(ServerLevel level, Frame f, Piece p) {
        BlockState s = level.getBlockState(f.at(p.k(), p.h()));
        return switch (p.part()) {
            case POST, RIDGE -> s.isFaceSturdy(level, f.at(p.k(), p.h()), Direction.UP);
            case STAIR -> s.is(BlockTags.STAIRS);
            case SLAB -> s.is(BlockTags.SLABS);
            case LIGHT -> s.is(Blocks.LANTERN) || level.getBlockState(f.at(p.k(), 1)).is(Blocks.WALL_TORCH);
            case BELL -> s.is(Blocks.BELL);
        };
    }

    /** Its frame stands (all but the bell, and any light it could not be given). */
    public static boolean standing(ServerLevel level, Frame f) {
        for (Piece p : pieces(f)) {
            if (p.part() == Part.BELL || p.part() == Part.LIGHT) continue;
            if (!done(level, f, p)) return false;
        }
        return true;
    }

    /** Is the town's bell hung in its frame? */
    public static boolean hung(ServerLevel level, @Nullable Frame f) {
        return f != null && level.isLoaded(f.bell()) && level.getBlockState(f.bell()).is(Blocks.BELL);
    }

    /** Is there a frame planned for this town that is not finished (its bell not in it)? Its works go quicker then. */
    static boolean building(ServerLevel level, UUID village) {
        Frame f = of(village);
        return f != null && (f.begun || NOW.contains(village)) && !hung(level, f);
    }

    // ------------------------------------------------------------------ where it goes

    /**
     * Ground for the frame on the square, a few blocks from the board, its front to the square: the
     * nearest to the board's lectern of the places that will do, or null if the square has none.
     */
    @Nullable
    static Frame site(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        BlockPos lectern = VillageBoards.lectern(id);
        BlockPos board = VillageBoards.boardOf(id);
        Direction facing = VillageBoards.facingOf(id);
        if (lectern == null || board == null || facing == null) return null;
        // The board's own ground and the front of it where its reader stands, a block round.
        Direction right = VillageBoardBlock.right(facing);
        BlockPos b0 = board.relative(right, -2).relative(facing, -2), b1 = board.relative(right, VillageBoardBlock.WIDE + 1).relative(facing, 3);
        int[] boardRect = { Math.min(b0.getX(), b1.getX()), Math.max(b0.getX(), b1.getX()), Math.min(b0.getZ(), b1.getZ()), Math.max(b0.getZ(), b1.getZ()) };
        int[] court = Villages.courtRect(id);
        int reach = TownPlan.PLAZA - 2;
        Frame best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (Direction along : new Direction[]{ Direction.EAST, Direction.SOUTH }) {
                    // Its front to the square: toward the heart, across its beam.
                    int across = along == Direction.EAST ? dz : dx;
                    if (across == 0) continue;
                    Direction front = along == Direction.EAST ? (dz > 0 ? Direction.NORTH : Direction.SOUTH)
                        : (dx > 0 ? Direction.WEST : Direction.EAST);
                    if (!clearOfThePlan(heart, dx, dz, along, front, boardRect, court)) continue;
                    BlockPos o = Watch.floorAt(level, heart.getX() + dx, heart.getZ() + dz, heart.getY());
                    if (o == null || !clearGround(level, o, along, front)) continue;
                    double score = Math.abs(Math.sqrt(o.distSqr(lectern)) - 8.0) + (along.getAxis() == right.getAxis() ? 0 : 1.5);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Frame(o, along, front);
                    }
                }
            }
        }
        return best;
    }

    /** Is a frame here (its five columns and its ringer's place) clear of what the town plan keeps the square for? */
    static boolean clearOfThePlan(BlockPos heart, int dx, int dz, Direction along, Direction front, int[] boardRect, @Nullable int[] court) {
        List<int[]> cells = new ArrayList<>();
        for (int k = -2; k <= 2; k++) cells.add(new int[]{ dx + along.getStepX() * k, dz + along.getStepZ() * k });
        cells.add(new int[]{ dx + front.getStepX(), dz + front.getStepZ() });
        int reach = TownPlan.PLAZA - 2;
        for (int[] c : cells) {
            int x = c[0], z = c[1], ax = Math.abs(x), az = Math.abs(z);
            if (ax > reach || az > reach) return false;                              // inside the wall, off its ladders' row
            if (Math.max(ax, az) <= 4) return false;                                 // the founders' camp and the stores
            if ((ax <= 2 && az >= 8) || (az <= 2 && ax >= 8)) return false;          // the ways in from the gates
            for (TownPlan.Lot spot : TownPlan.squareSpots()) {                       // the well and the monuments
                if (Math.abs(x - spot.x()) <= spot.halfAcross() && Math.abs(z - spot.z()) <= spot.halfDeep()) return false;
            }
            for (int sx : new int[]{ -8, 8 }) {
                for (int sz : new int[]{ -8, 8 }) {
                    if (Math.abs(x - sx) <= 1 && Math.abs(z - sz) <= 2) return false;   // a market stall
                    int shopper = sx > 0 ? sx - 3 : sx + 3;                             // where its customers stand
                    if (x == shopper && Math.abs(z - sz) <= 1) return false;
                }
            }
            int wx = heart.getX() + x, wz = heart.getZ() + z;
            if (wx >= boardRect[0] && wx <= boardRect[1] && wz >= boardRect[2] && wz <= boardRect[3]) return false;
            if (court != null && wx >= court[0] - 1 && wx <= court[1] + 1 && wz >= court[2] - 1 && wz <= court[3] + 1) return false;
        }
        return true;
    }

    /** Level, clear ground for it: every column's floor at the one height, room up to its roof, no path under it. */
    static boolean clearGround(ServerLevel level, BlockPos o, Direction along, Direction front) {
        for (int k = -2; k <= 2; k++) {
            BlockPos col = o.relative(along, k);
            if (!level.isLoaded(col)) return false;
            BlockState under = level.getBlockState(col.below());
            if (!under.isFaceSturdy(level, col.below(), Direction.UP) || under.is(Blocks.DIRT_PATH)) return false;
            for (int h = 0; h <= 4; h++) {
                BlockState s = level.getBlockState(col.above(h));
                if (!(s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty()))) return false;
            }
        }
        BlockPos stand = o.relative(front);
        BlockState under = level.getBlockState(stand.below());
        if (!under.isFaceSturdy(level, stand.below(), Direction.UP)) return false;
        for (int h = 0; h <= 1; h++) {
            BlockState s = level.getBlockState(stand.above(h));
            if (!(s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty()))) return false;
        }
        return true;
    }

    /** The town's frame, planned now if it has none (once in a while, when there is ground for one). */
    @Nullable
    static Frame planned(ServerLevel level, Villages.Village v) {
        Frame f = of(v.id());
        if (f != null) return f;
        long now = level.getGameTime();
        Long sought = SOUGHT.get(v.id());
        if (sought != null && now - sought < 6000L) return null;
        SOUGHT.put(v.id(), now);
        f = site(level, v);
        if (f == null) {
            LOG.info("[MCA-BELL] {}: no ground on the square for a bell frame", Villages.name(v.id()));
            return null;
        }
        f.wood = Masonry.woodOf(level, v);
        save(v.id(), f);
        LOG.info("[MCA-BELL] {}: the bell frame is to stand at {}, along {}, facing {}", Villages.name(v.id()),
            f.origin.toShortString(), f.along, f.front);
        return f;
    }

    // ------------------------------------------------------------------ building it

    private static boolean clear(BlockState s) {
        return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    /**
     * A step of the work, from the town bell's look round (by day, every couple of seconds while it is
     * going up): a piece put up by a hand at the town's works, paid for out of the stores; then the
     * town's bell taken down from wherever it hangs into the stores; then hung in the frame.
     * {@code bell} is where the town's bell hangs now (null: it has none).
     */
    static void work(ServerLevel level, Villages.Village v, @Nullable BlockPos bell) {
        UUID id = v.id();
        Frame f = planned(level, v);
        if (f == null || hung(level, f)) return;
        if (!level.isLoaded(f.origin)) return;
        long now = level.getGameTime();
        for (Piece p : pieces(f)) {
            if (p.part() == Part.BELL || done(level, f, p)) continue;
            if (p.part() == Part.LIGHT && now - NO_LIGHT.getOrDefault(id, -100000L) < 6000L) continue;
            BlockPos at = f.at(p.k(), p.h());
            BlockState there = level.getBlockState(at);
            if (p.part() == Part.LIGHT && !(clear(there) && clear(level.getBlockState(f.at(p.k(), 1))))) continue;   // no room for a light
            if (!clear(there) && p.part() != Part.LIGHT) {
                // Something has been put where the frame goes: look for other ground.
                LOG.info("[MCA-BELL] {}: the bell frame's ground at {} is taken ({}): looking again", Villages.name(id),
                    at.toShortString(), there.getBlock().getName().getString());
                save(id, null);
                return;
            }
            if (!f.begun && (!settled(level, id) || !choose(level, v, f))) return;   // its first day, or not the makings yet
            if (!TownJobs.atWork(level, v, "bellframe", f.origin, "building the town bell's frame")) return;
            BlockState put = pay(level, v, f, p);
            if (put == null) {
                if (p.part() == Part.LIGHT) NO_LIGHT.put(id, now);
                return;
            }
            BlockPos where = p.part() == Part.LIGHT && put.is(Blocks.WALL_TORCH) ? f.at(p.k(), 1) : at;
            level.setBlock(where, put, 3);
            level.playSound(null, where, put.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
            if (!f.begun) {
                f.begun = true;
                save(id, f);
            }
            return;
        }
        // The frame stands. The town's bell to it: down from where it hangs, into the stores, and up in the frame.
        if (bell != null && !bell.equals(f.bell())) {
            if (!TownJobs.atWork(level, v, "bellframe", bell, "taking the town bell down, to hang it in its frame")) return;
            takeDown(level, v, bell);
            return;
        }
        // A frame waiting for its bell: the stores looked in now and then for one put by.
        if (now - NO_BELL.getOrDefault(id, -100000L) < 600L) return;
        if (Market.stock(level, id, s -> s.is(Items.BELL)) == 0) {
            NO_BELL.put(id, now);
            return;
        }
        if (!clear(level.getBlockState(f.bell()))) return;
        if (!TownJobs.atWork(level, v, "bellframe", f.origin, "hanging the town bell in its frame")) return;
        if (!TownWork.take(level, v, s -> s.is(Items.BELL), 1)) return;
        level.setBlock(f.bell(), Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, f.along)
            .setValue(BellBlock.ATTACHMENT, BellAttachType.DOUBLE_WALL), 3);
        level.playSound(null, f.bell(), net.minecraft.sounds.SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.0F, 1.0F);
        Villages.tell(id, level.getDayTime() / 24000L, "The town bell was hung in its own frame on the square");
        LOG.info("[MCA-BELL] {}: the town bell hung in its frame at {}", Villages.name(id), f.bell().toShortString());
    }

    /**
     * Timber or stone, chosen when the first piece goes up: stone bricks once the town is in the Stone Age
     * and the stores can pay for them all (a short belfry, a post higher), else timber if they can pay for
     * that. False while they can pay for neither.
     */
    private static boolean choose(ServerLevel level, Villages.Village v, Frame f) {
        boolean stoneAge = Villages.ageOf(v.id()).ordinal() >= Villages.Age.STONE.ordinal();
        if (stoneAge && Masonry.canAll(level, v, Map.of(Items.STONE_BRICKS, 9, Items.STONE_BRICK_STAIRS, 2, Items.STONE_BRICK_SLAB, 2))) {
            f.stone = true;
            return true;
        }
        int logs = Crafts.stock(level, v, s -> s.is(ItemTags.LOGS));
        int planks = Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS));
        if (logs >= 6 && planks + 4 * (logs - 6) >= 10) {
            f.stone = false;
            f.wood = Masonry.woodOf(level, v);
            return true;
        }
        return false;
    }

    /** A piece paid for out of the stores: what goes up, or null if the stores cannot pay for it now. */
    @Nullable
    private static BlockState pay(ServerLevel level, Villages.Village v, Frame f, Piece p) {
        Direction inward = p.k() < 0 ? f.along : f.along.getOpposite();      // a stair's tall side, toward the ridge
        switch (p.part()) {
            case POST -> {
                if (f.stone) return Masonry.take(level, v, Items.STONE_BRICKS, 1) ? Blocks.STONE_BRICKS.defaultBlockState() : null;
                Item log = Masonry.woodBlock(f.wood, "_log").asItem();
                ItemStack got = Crafts.takeOne(level, v, s -> s.is(log));
                if (got.isEmpty()) got = Crafts.takeOne(level, v, s -> s.is(ItemTags.LOGS));
                return got.isEmpty() ? null : Block.byItem(got.getItem()).defaultBlockState();
            }
            case RIDGE -> {
                if (f.stone) return Masonry.take(level, v, Items.STONE_BRICKS, 1) ? Blocks.STONE_BRICKS.defaultBlockState() : null;
                Item plank = Masonry.woodBlock(f.wood, "_planks").asItem();
                ItemStack got = Crafts.takeOne(level, v, s -> s.is(plank));
                if (got.isEmpty() && Crafts.planks(level, v, 1)) got = Crafts.takeOne(level, v, s -> s.is(ItemTags.PLANKS));
                return got.isEmpty() ? null : Block.byItem(got.getItem()).defaultBlockState();
            }
            case STAIR -> {
                Block b = f.stone ? (Masonry.take(level, v, Items.STONE_BRICK_STAIRS, 1) ? Blocks.STONE_BRICK_STAIRS : null)
                    : cut(level, v, f.wood, "_stairs", 6, 4);
                return b == null ? null : b.defaultBlockState().setValue(StairBlock.FACING, inward).setValue(StairBlock.HALF, Half.BOTTOM);
            }
            case SLAB -> {
                Block b = f.stone ? (Masonry.take(level, v, Items.STONE_BRICK_SLAB, 1) ? Blocks.STONE_BRICK_SLAB : null)
                    : cut(level, v, f.wood, "_slab", 3, 6);
                return b == null ? null : b.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
            }
            case LIGHT -> {
                Block light = Masonry.light(level, v);
                if (light == Blocks.LANTERN) return Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
                if (light == Blocks.TORCH) {
                    // A torch on the post's outer side, for want of a lantern.
                    return Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, p.k() < 0 ? f.along.getOpposite() : f.along);
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * A wooden stair or slab of the village's wood: one put by in the stores, else a batch cut from its
     * planks (any planks if it has none of its own wood: oak's), the rest of the batch into the stores.
     */
    @Nullable
    private static Block cut(ServerLevel level, Villages.Village v, String wood, String suffix, int planks, int yield) {
        Block made = Masonry.woodBlock(wood, suffix);
        Item item = made.asItem();
        if (!Crafts.takeOne(level, v, s -> s.is(item)).isEmpty()) return made;
        Item plank = Masonry.woodBlock(wood, "_planks").asItem();
        if (Crafts.stock(level, v, s -> s.is(plank)) >= planks) {
            if (!Crafts.take(level, v, s -> s.is(plank), planks)) return null;
        } else {
            if (!Crafts.usePlanks(level, v, planks)) return null;
            made = Masonry.woodBlock("oak", suffix);
        }
        Crafts.giveBack(level, v, made.asItem(), yield - 1);
        return made;
    }

    /**
     * The town's bell taken down from where it hung, into the stores (it goes up in its frame next); and,
     * if it stood on a plinth of its own on the ground, the plinth's stone back into the stores with it.
     */
    private static void takeDown(ServerLevel level, Villages.Village v, BlockPos bell) {
        BlockState s = level.getBlockState(bell);
        if (!s.is(Blocks.BELL)) return;
        boolean onFloor = s.getValue(BellBlock.ATTACHMENT) == BellAttachType.FLOOR;
        level.setBlock(bell, Blocks.AIR.defaultBlockState(), 3);
        TownWork.give(level, v, new ItemStack(Items.BELL));
        BlockPos under = bell.below();
        BlockState plinth = level.getBlockState(under);
        if (onFloor && (plinth.is(Blocks.STONE_BRICKS) || plinth.is(Blocks.COBBLESTONE)) && alone(level, under)) {
            level.setBlock(under, Blocks.AIR.defaultBlockState(), 3);
            TownWork.give(level, v, new ItemStack(plinth.getBlock().asItem()));
        }
        TownBell.forget(v.id());
        LOG.info("[MCA-BELL] {}: the town bell taken down from {}, to hang in its frame", Villages.name(v.id()), bell.toShortString());
    }

    /** A block standing on its own: nothing of its kind beside it (a plinth, not a wall or a floor). */
    private static boolean alone(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) if (!level.getBlockState(p.relative(d)).isAir()) return false;
        return true;
    }

    // ------------------------------------------------------------------ what is said of it

    /** For /village bell and the smoke test: "FRAME-AT x y z ALONG east FACING north" and how far on it is. */
    static String status(ServerLevel level, Villages.Village v) {
        Frame f = of(v.id());
        if (f == null) return "FRAME-NONE (not planned yet).";
        int up = 0, all = 0;
        for (Piece p : pieces(f)) {
            all++;
            if (done(level, f, p)) up++;
        }
        StringBuilder sb = new StringBuilder("FRAME-AT ").append(f.origin.getX()).append(' ').append(f.origin.getY()).append(' ')
            .append(f.origin.getZ()).append(" ALONG ").append(f.along.getName()).append(" FACING ").append(f.front.getName())
            .append(hung(level, f) ? " DONE" : standing(level, f) ? " STANDING (waiting for its bell)" : " BUILDING")
            .append(" (").append(up).append('/').append(all).append(" pieces, ").append(f.begun ? (f.stone ? "stone" : f.wood + " timber") : "not begun")
            .append(").");
        List<BlockPos> stores = Villages.storeChests(level, v.id());
        if (!stores.isEmpty()) sb.append(" STORES ").append(stores.get(0).getX()).append(' ').append(stores.get(0).getY()).append(' ')
            .append(stores.get(0).getZ()).append('.');
        return sb.toString();
    }

    /** For the tests: plan the frame now, to be begun even on the town's first day, and return it. */
    @Nullable
    public static Frame planForTests(ServerLevel level, Villages.Village v) {
        SOUGHT.remove(v.id());
        NOW.add(v.id());
        return planned(level, v);
    }
}
