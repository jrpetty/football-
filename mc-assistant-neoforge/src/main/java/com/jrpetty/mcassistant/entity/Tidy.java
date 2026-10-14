package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tidy] The town cares for its looks, and its folk keep it so themselves.
 *
 * <ul>
 * <li><b>The tidy crew.</b> The town's streets are walked a few thousand columns a visit (Eyesores), and what spoils
 *     them is put right worst first by a hand at the spot: the street sweeper (Sweepers) when there is nothing lying
 *     about for its broom, and otherwise whoever the town can spare for its works (TownJobs "tidy", the couriers
 *     first). A spill filled, a hole filled with ground that matches round it (turf on top on a lawn, a path's earth
 *     in a path), a pillar a folk left behind taken down and a stray block taken up, scaffolding struck, a pothole in
 *     the street mended, a half-felled tree finished and a sapling of its kind planted in its place, leaves hanging in
 *     the air cleared, a torch dropped on the ground taken up, a scar in the turf grassed over; the founders' camp
 *     beds taken up once everybody has a bed in a house, the chests left out on the square once the storehouse
 *     stands. Everything taken up goes into the stores; everything laid comes out of them.</li>
 * <li><b>Inside the buildings</b> (Floors): no grass or earth where a floor is drawn, nothing growing on a floor.</li>
 * <li><b>Never</b> a building or a building going up, a door's way in, the fields, the mine, a worker's ground, the
 *     square's well and monuments, nor anything a player put there (PlayerBlocks).</li>
 * <li><b>The town's looks</b> (out of a hundred): tidiness (the eyesores, and grass inside the buildings), greenery,
 *     its streets lit, its flowers, its buildings finished. On the board and in the storehouse's books; the folk
 *     notice it (contentment: proud of a town that looks a picture, put out by a mess, and they say so); when it falls
 *     to untidy the elder calls a clean-up day (three crews out instead of one); and a proud town (well kept or
 *     better) plants flower beds along its streets, trims the leaves hanging over them and lights its dark corners.</li>
 * </ul>
 */
public final class Tidy {

    private Tidy() {}

    /** How often a town's crew is looked at (ticks), and how many columns of the town are walked each time. */
    static final long EVERY = 100L;
    static final int WALK = 2500;
    /** How near the spot a fix is put right along with the one the hand was sent to. */
    static final int ALONG = 4;
    /** A spot nobody could get to, or put right, is left this long. */
    static final long LEAVE = 6000L;
    /** The looks below which the elder calls a clean-up day; days between them. */
    static final int UNTIDY = 50, PROUD = 70;
    public static final int UNTIDY_FOR_TESTS = UNTIDY;
    static final long CLEANUP_EVERY = 3;
    /** A hole filled this deep at most (a deeper one is a shaft, or a cave's mouth: somebody's to see to by hand). */
    static final int DEEPEST = 8;

    /** A town's crew: what it is going for, what it has done today, and its looks. */
    static final class Crew {
        long looked = -100000L;
        @Nullable Floors.Fix floor;
        final Map<String, Eyesores.Spot> going = new HashMap<>();
        /** Spots left alone a while (nobody could get to them, or nothing in the stores to put them right with), till when. */
        final Map<Long, Long> leave = new HashMap<>();
        int floored, plantsUp;
        final Map<Eyesores.Kind, Integer> done = new java.util.EnumMap<>(Eyesores.Kind.class);
        int flowers, trimmed, lamps;
        long day = -1L;
        @Nullable Looks looks;
        long cleanupCalled = -100L;
    }

    /** The town's looks, out of a hundred, and what they are made of. */
    public record Looks(int score, int tidiness, int greenery, int lighting, int flowers, int finished, int eyesores, int grassInside,
                        String word, String worst) {}

    private static final Map<UUID, Crew> CREWS = new ConcurrentHashMap<>();
    /** The sweepers' own spots (a sweeper with nothing for its broom tidies), by sweeper. */
    private static final Map<UUID, Eyesores.Spot> BROOM = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> BROOM_PROGRESS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CREWS.clear();
        BROOM.clear();
        BROOM_PROGRESS.clear();
        Floors.resetForTests();
        Eyesores.resetForTests();
    }

    static Crew crew(ServerLevel level, UUID id) {
        Crew c = CREWS.computeIfAbsent(id, k -> new Crew());
        long today = level.getDayTime() / 24000L;
        if (c.day != today) {
            c.day = today;
            c.floored = 0;
            c.plantsUp = 0;
            c.done.clear();
            c.flowers = c.trimmed = c.lamps = 0;
            String kept = Ledger.note(id, "tidy_cleanup");
            try {
                if (kept != null) c.cleanupCalled = Long.parseLong(kept);
            } catch (NumberFormatException ignored) {
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ the round

    /** From the town's rounds (the folk's tick, beside TownWork.tick): a walk on through the town, and the crew's next piece of work. */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Crew c = crew(level, v.id());
        if (now - c.looked < EVERY && now >= c.looked) return;
        c.looked = now;
        if (!level.isLoaded(v.centre())) return;
        Eyesores.Walk before = Eyesores.last(v.id());
        Eyesores.step(level, v, WALK);
        Eyesores.Walk walk = Eyesores.last(v.id());
        if (walk != null && walk != before) {
            c.looks = measure(level, v, walk);
            noticed(level, v, c);
        }
        if (Raids.underAlarm(v.id())) return;
        c.leave.values().removeIf(until -> until < now);
        if (floors(level, v, c)) return;
        int crews = cleanupDay(level, v.id()) ? 3 : 1;
        for (int k = 0; k < crews; k++) {
            if (!eyesore(level, v, c, k == 0 ? "tidy" : "tidy/" + (k + 1))) break;
        }
        if (c.looks != null && c.looks.score() >= PROUD) proud(level, v, c);
    }

    /** Inside the buildings: earth under a floor floored, a plant on a floor taken up. True while at it. */
    private static boolean floors(ServerLevel level, Villages.Village v, Crew c) {
        if (c.floor != null && !stillWanted(level, c.floor)) c.floor = null;
        if (c.floor == null) c.floor = Floors.next(level, v);
        Floors.Fix f = c.floor;
        if (f == null) return false;
        if (c.leave.containsKey(f.pos().asLong())) {
            c.floor = null;
            return false;
        }
        String what = f.lay() ? "laying the floor in the " + Villages.spoken(f.building()) : "clearing the weeds off the " + Villages.spoken(f.building()) + "'s floor";
        if (!TownJobs.atWork(level, v, "tidy", f.pos(), what, AssistantEntity.StationTask.HAUL)) return true;
        for (Floors.Fix g : Floors.near(level, v, f, ALONG)) {
            if (!Floors.put(level, v, g)) {                           // nothing in the stores to floor it with: another day
                c.leave.put(g.pos().asLong(), level.getGameTime() + LEAVE);
                break;
            }
            if (g.lay()) c.floored++; else c.plantsUp++;
        }
        c.floor = null;
        return true;
    }

    private static boolean stillWanted(ServerLevel level, Floors.Fix f) {
        BlockState st = level.getBlockState(f.pos());
        return f.lay() ? BuildGoal.bareEarth(st) : BuildGoal.isWildPlant(st);
    }

    /** The worst eyesore nobody is going for, nearest the heart among the worst. */
    @Nullable
    private static Eyesores.Spot pick(ServerLevel level, Villages.Village v, Crew c, @Nullable UUID sweeper) {
        Eyesores.Walk w = Eyesores.last(v.id());
        if (w == null || w.spots.isEmpty()) return null;
        java.util.Set<Long> taken = new java.util.HashSet<>();
        for (Eyesores.Spot s : c.going.values()) taken.add(s.pos().asLong());
        for (Map.Entry<UUID, Eyesores.Spot> e : BROOM.entrySet()) if (!e.getKey().equals(sweeper)) taken.add(e.getValue().pos().asLong());
        BlockPos h = v.centre();
        List<Eyesores.Spot> order = new ArrayList<>(w.spots);
        order.sort(Comparator.comparingInt((Eyesores.Spot s) -> -s.kind().worst).thenComparingDouble(s -> s.pos().distSqr(h)));
        for (Eyesores.Spot s : order) {
            long k = s.pos().asLong();
            if (taken.contains(k) || c.leave.containsKey(k)) continue;
            if (!Eyesores.still(level, v, s)) continue;
            return s;
        }
        return null;
    }

    /** One crew's piece of work on the town's eyesores. False if there is nothing for it. */
    private static boolean eyesore(ServerLevel level, Villages.Village v, Crew c, String works) {
        Eyesores.Spot s = c.going.get(works);
        if (s != null && !Eyesores.still(level, v, s)) s = null;
        if (s == null) s = pick(level, v, c, null);
        if (s == null) {
            c.going.remove(works);
            return false;
        }
        c.going.put(works, s);
        if (!TownJobs.atWork(level, v, works, s.pos(), what(s), AssistantEntity.StationTask.HAUL)) return true;
        putRight(level, v, c, s, null);
        c.going.remove(works);
        return true;
    }

    /** At the spot: put it right, and whatever else spoils the ground within a few blocks of it. */
    private static void putRight(ServerLevel level, Villages.Village v, Crew c, Eyesores.Spot s, @Nullable VillageFolkEntity by) {
        List<Eyesores.Spot> along = new ArrayList<>();
        along.add(s);
        Eyesores.Walk w = Eyesores.last(v.id());
        if (w != null) {
            for (Eyesores.Spot o : w.spots) {
                if (o != s && o.pos().distManhattan(s.pos()) <= ALONG && Eyesores.still(level, v, o)) along.add(o);
            }
        }
        for (Eyesores.Spot o : along) {
            if (fix(level, v, o)) {
                c.done.merge(o.kind(), 1, Integer::sum);
                if (by != null) by.brain("tidied " + o.kind().words + " (" + o.note() + ")");
            } else {
                c.leave.put(o.pos().asLong(), level.getGameTime() + LEAVE);
            }
            w = Eyesores.last(v.id());
            if (w != null) w.spots.remove(o);
        }
        if (by != null && level.getRandom().nextInt(5) == 0) {
            FolkTalk.speak(by, FolkTalk.pick(level.getRandom(), "There. That's better.", "Can't have the town looking like that.",
                "Who leaves " + s.kind().words + " in the middle of town?", "A place for everything."));
        }
    }

    private static String what(Eyesores.Spot s) {
        return switch (s.kind()) {
            case SPILL -> "clearing up a spill";
            case HOLE -> "filling in a hole";
            case PILLAR -> "taking down a pillar somebody left";
            case CAMP_BED -> "taking up the camp's beds";
            case CHEST -> "taking in a chest left out";
            case SCAFFOLD -> "striking the scaffolding";
            case ROAD -> "mending the street";
            case STRAY -> "taking up a stray block";
            case HALF_TREE -> "finishing a half-felled tree, and replanting";
            case TORCH -> "taking up a torch left on the ground";
            case SCAR -> "grassing over a scar";
            case LEAVES -> "clearing leaves left hanging";
        };
    }

    // ------------------------------------------------------------------ the sweeper's tidying

    /**
     * A beat of the street sweeper's (Sweepers.work), once there is nothing lying about for its broom: the next
     * eyesore, walked to and put right. True while it is at it; false if there is nothing to tidy.
     */
    static boolean broom(VillageFolkEntity f, ServerLevel level, Villages.Village v, Couriers.Office o) {
        Crew c = crew(level, v.id());
        long now = level.getGameTime();
        Eyesores.Spot s = BROOM.get(f.getUUID());
        if (s != null && !Eyesores.still(level, v, s)) s = null;
        if (s == null) {
            s = pick(level, v, c, f.getUUID());
            if (s == null) {
                BROOM.remove(f.getUUID());
                return false;
            }
            BROOM.put(f.getUUID(), s);
            BROOM_PROGRESS.put(f.getUUID(), new long[]{ Long.MAX_VALUE, now });
        }
        double dx = s.pos().getX() + 0.5 - f.getX(), dz = s.pos().getZ() + 0.5 - f.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= 3.0 && Math.abs(s.pos().getY() - f.getY()) <= 5) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(s.pos().getX() + 0.5, s.pos().getY() + 0.5, s.pos().getZ() + 0.5);
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            putRight(level, v, c, s, f);
            BROOM.remove(f.getUUID());
            o.doing.put(f.getUUID(), "tidying the town: " + what(s));
            return true;
        }
        long[] pr = BROOM_PROGRESS.computeIfAbsent(f.getUUID(), k -> new long[]{ Long.MAX_VALUE, now });
        long dd = (long) (d * 10);
        if (dd < pr[0] - 5) {
            pr[0] = dd;
            pr[1] = now;
        } else if (now - pr[1] > 400L || now < pr[1]) {
            c.leave.put(s.pos().asLong(), now + LEAVE);
            BROOM.remove(f.getUUID());
            f.brain("could not get to " + s.kind().words + "; left it a while");
            return true;
        }
        if (f.getNavigation().isDone()) f.walkTo(s.pos().above(), 1.0D);
        o.doing.put(f.getUUID(), "tidying the town: going to " + what(s));
        f.brain("tidying: going to " + s.kind().words);
        return true;
    }

    /** Is there anything about the town for the sweeper to tidy (as last walked)? */
    static boolean anyFor(Villages.Village v) {
        Eyesores.Walk w = Eyesores.last(v.id());
        return w != null && !w.spots.isEmpty();
    }

    /** The sweeper's broom put away: no eyesore it was going to. */
    static void forget(VillageFolkEntity f) {
        BROOM.remove(f.getUUID());
        BROOM_PROGRESS.remove(f.getUUID());
    }

    // ------------------------------------------------------------------ putting it right

    /** [integrity] Every block the crew changes in the town goes through here: never a player's block. To be
     *  routed through TownBlocks.mayPlace (the one gate) once that lands. */
    static boolean mayChange(ServerLevel level, Villages.Village v, BlockPos pos) {
        return !PlayerBlocks.placed(level, pos);
    }

    /** Taken up: what it gives into the stores, and the block gone. */
    private static boolean takeUp(ServerLevel level, Villages.Village v, BlockPos p) {
        if (!mayChange(level, v, p)) return false;
        BlockState st = level.getBlockState(p);
        if (st.isAir()) return true;
        for (ItemStack drop : Block.getDrops(st, level, p, level.getBlockEntity(p))) TownWork.give(level, v, drop);
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        return true;
    }

    /** Put one eyesore right. False if it could not be (nothing in the stores to do it with, or not the crew's to touch). */
    static boolean fix(ServerLevel level, Villages.Village v, Eyesores.Spot s) {
        BlockPos p = s.pos();
        if (!mayChange(level, v, p)) return false;
        BlockState st = level.getBlockState(p);
        switch (s.kind()) {
            case SPILL -> {
                BlockState fill = ground(level, v, p, true);
                if (fill == null) return false;
                level.setBlockAndUpdate(p, fill);
                return true;
            }
            case HOLE, ROAD -> {
                int want = s.kind() == Eyesores.Kind.ROAD ? minAround(level, p) : medianAround(level, p);
                if (want - p.getY() > DEEPEST) return false;
                boolean any = false;
                for (int y = p.getY() + 1; y <= want; y++) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    BlockState at = level.getBlockState(q);
                    if (!at.canBeReplaced() || !mayChange(level, v, q)) break;
                    BlockState fill = ground(level, v, q, y == want);
                    if (fill == null) return any;
                    if (!at.isAir()) level.setBlock(q, Blocks.AIR.defaultBlockState(), 2 | 16);
                    level.setBlockAndUpdate(q, fill);
                    any = true;
                }
                return any;
            }
            case PILLAR -> {
                int floor = maxAround(level, p);
                boolean any = false;
                for (int y = p.getY(); y > floor; y--) any |= takeUp(level, v, new BlockPos(p.getX(), y, p.getZ()));
                BlockPos top = new BlockPos(p.getX(), floor, p.getZ());
                if (level.getBlockState(top).is(Blocks.DIRT) && level.getBlockState(top.above()).isAir()) level.setBlockAndUpdate(top, Blocks.GRASS_BLOCK.defaultBlockState());
                return any;
            }
            case STRAY, LEAVES, TORCH -> {
                return takeUp(level, v, p);
            }
            case SCAFFOLD -> {
                boolean any = false;
                BlockPos q = p;
                while (level.getBlockState(q).is(Blocks.SCAFFOLDING) && q.getY() > p.getY() - 32) {
                    any |= takeUp(level, v, q);
                    q = q.below();
                }
                return any;
            }
            case CAMP_BED -> {
                return campBed(level, v, p);
            }
            case CHEST -> {
                if (!(level.getBlockEntity(p) instanceof Container box)) return false;
                for (int i = 0; i < box.getContainerSize(); i++) {
                    ItemStack in = box.getItem(i);
                    if (in.isEmpty()) continue;
                    ItemStack go = in.copy();
                    TownWork.give(level, v, go);
                    box.setItem(i, go);                       // what found no room stays in it
                    if (!go.isEmpty()) return false;
                }
                Retiring.unmark(level, p);
                return takeUp(level, v, p);
            }
            case HALF_TREE -> {
                int base = p.getY();
                while (base > p.getY() - 12 && level.getBlockState(new BlockPos(p.getX(), base - 1, p.getZ())).is(BlockTags.LOGS)) base--;
                Block log = st.getBlock();
                for (int y = p.getY(); y >= base; y--) takeUp(level, v, new BlockPos(p.getX(), y, p.getZ()));
                BlockPos ground = new BlockPos(p.getX(), base - 1, p.getZ());
                if (level.getBlockState(ground).is(Blocks.DIRT)) level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                BlockPos c = v.centre();
                int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
                if (!TownPlan.isStreet(dx, dz) && !TownPlan.isSquare(dx, dz)) replant(level, v, ground.above(), log);
                return true;
            }
            case SCAR -> {
                if (!st.is(Blocks.DIRT) && !st.is(Blocks.COARSE_DIRT) && !st.is(Blocks.ROOTED_DIRT)) {
                    if (!TownWork.take(level, v, it -> it.is(Items.DIRT), 1)) return false;
                    for (ItemStack drop : Block.getDrops(st, level, p, null)) TownWork.give(level, v, drop);
                }
                BlockState up = level.getBlockState(p.above());
                if (!up.isAir()) {
                    if (!up.canBeReplaced()) return false;
                    level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlockAndUpdate(p, Blocks.GRASS_BLOCK.defaultBlockState());
                return true;
            }
        }
        return false;
    }

    /** A camp bed taken up into the stores: never one somebody is in; one somebody calls its own only if there is a
     *  bed in a house for it (it is told to find that one tonight). */
    private static boolean campBed(ServerLevel level, Villages.Village v, BlockPos head) {
        BlockState st = level.getBlockState(head);
        if (!(st.getBlock() instanceof BedBlock) || st.getValue(BedBlock.OCCUPIED)) return false;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!head.equals(a.bedPos())) continue;
            if (a.isSleeping()) return false;
            int spare = Villages.bedsMadeUp(level, v.id()) - claimedInHouses(level, v);
            if (spare <= 0) return false;
            a.forgetBed();
            if (a instanceof VillageFolkEntity f) f.brain("the camp is being taken up: a bed in a house tonight");
        }
        BlockPos other = st.getValue(BedBlock.PART) == BedPart.HEAD
            ? head.relative(st.getValue(BedBlock.FACING).getOpposite()) : head.relative(st.getValue(BedBlock.FACING));
        if (!mayChange(level, v, other)) return false;
        level.setBlock(other, Blocks.AIR.defaultBlockState(), 2 | 16);
        level.setBlock(head, Blocks.AIR.defaultBlockState(), 3);
        TownWork.give(level, v, new ItemStack(st.getBlock().asItem()));
        return true;
    }

    private static int claimedInHouses(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            BlockPos b = a.bedPos();
            if (b != null && Math.max(Math.abs(b.getX() - v.centre().getX()), Math.abs(b.getZ() - v.centre().getZ()))
                    > com.jrpetty.mcassistant.VillageSpawner.CAMP_REACH) n++;
        }
        return n;
    }

    /** A sapling of the felled tree's own kind, out of the stores (any sapling failing that), where it stood. */
    private static void replant(ServerLevel level, Villages.Village v, BlockPos at, Block log) {
        if (!level.getBlockState(at).isAir()) return;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(log);
        Item own = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(key.getNamespace(),
            key.getPath().replace("stripped_", "").replace("_log", "_sapling").replace("_wood", "_sapling")));
        ItemStack got = own != Items.AIR ? Crafts.takeOne(level, v, s -> s.is(own)) : ItemStack.EMPTY;
        if (got.isEmpty()) got = Crafts.takeOne(level, v, s -> s.is(ItemTags.SAPLINGS));
        if (got.isEmpty() || !(got.getItem() instanceof BlockItem bi)) return;
        BlockState sapling = bi.getBlock().defaultBlockState();
        if (!sapling.canSurvive(level, at)) {
            TownWork.give(level, v, got);
            return;
        }
        level.setBlockAndUpdate(at, sapling);
    }

    /**
     * Ground to lay here, out of the stores: on top ({@code top}) what the ground round it is (turf, a path, the
     * street's stone, sand), below it earth or cobble. Null if the stores have nothing for it.
     */
    @Nullable
    static BlockState ground(ServerLevel level, Villages.Village v, BlockPos p, boolean top) {
        if (top) {
            Map<Block, Integer> round = new HashMap<>();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int y = Eyesores.top(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ());
                Block b = level.getBlockState(new BlockPos(p.getX() + d.getStepX(), y, p.getZ() + d.getStepZ())).getBlock();
                round.merge(b, 1, Integer::sum);
            }
            Block most = null;
            int best = 0;
            for (Map.Entry<Block, Integer> e : round.entrySet()) {
                if (e.getValue() > best && e.getKey() != Blocks.WATER && e.getKey() != Blocks.LAVA) {
                    best = e.getValue();
                    most = e.getKey();
                }
            }
            if (most == Blocks.GRASS_BLOCK || most == Blocks.DIRT_PATH || most == Blocks.FARMLAND) {
                if (TownWork.take(level, v, s -> s.is(Items.DIRT), 1)) return most == Blocks.DIRT_PATH ? Blocks.DIRT_PATH.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState();
            } else if (most != null && most.asItem() != Items.AIR && Eyesores.rough(most.defaultBlockState())
                    || most == Blocks.STONE_BRICKS || most == Blocks.SANDSTONE) {
                Item item = most.asItem();
                if (TownWork.take(level, v, s -> s.is(item), 1)) return most.defaultBlockState();
            }
        }
        if (TownWork.take(level, v, s -> s.is(Items.DIRT), 1)) return top ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return Blocks.COBBLESTONE.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.COBBLED_DEEPSLATE), 1)) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        return null;
    }

    private static int[] around(ServerLevel level, BlockPos p) {
        int[] h = new int[8];
        int i = 0;
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) if (ox != 0 || oz != 0) h[i++] = Eyesores.top(level, p.getX() + ox, p.getZ() + oz);
        java.util.Arrays.sort(h);
        return h;
    }

    private static int medianAround(ServerLevel level, BlockPos p) {
        return around(level, p)[4];
    }

    private static int maxAround(ServerLevel level, BlockPos p) {
        int m = Integer.MIN_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) m = Math.max(m, Eyesores.top(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ()));
        return m;
    }

    private static int minAround(ServerLevel level, BlockPos p) {
        int m = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) m = Math.min(m, Eyesores.top(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ()));
        return m;
    }

    // ------------------------------------------------------------------ a proud town

    /**
     * A town that is well kept or better, and has nothing worse to see to: flowers along its streets, the leaves
     * hanging over them trimmed, its dark corners lit. One thing a visit.
     */
    private static void proud(ServerLevel level, Villages.Village v, Crew c) {
        if (!c.going.isEmpty()) return;
        Eyesores.Walk w = Eyesores.last(v.id());
        if (w == null) return;
        BlockPos h = v.centre();
        // Leaves over the street, at head height: trimmed back.
        for (BlockPos p : w.overhang) {
            if (!(level.getBlockState(p).getBlock() instanceof LeavesBlock) || c.leave.containsKey(p.asLong())) continue;
            if (!TownJobs.atWork(level, v, "tidy", p, "trimming the leaves over the street", AssistantEntity.StationTask.HAUL)) return;
            if (takeUp(level, v, p)) c.trimmed++;
            return;
        }
        // A dark stretch of street: a light on a post at its side.
        for (BlockPos p : w.dark) {
            if (c.leave.containsKey(p.asLong())) continue;
            if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p) >= 8) continue;
            BlockPos post = verge(level, v, p);
            if (post == null) {
                c.leave.put(p.asLong(), level.getGameTime() + LEAVE * 4);
                continue;
            }
            if (Market.stock(level, v.id(), s -> s.is(Items.TORCH)) == 0) break;
            if (!TownJobs.atWork(level, v, "tidy", post, "lighting a dark corner of the street", AssistantEntity.StationTask.HAUL)) return;
            if (!Crafts.fence(level, v)) { c.leave.put(p.asLong(), level.getGameTime() + LEAVE); return; }
            if (!TownWork.take(level, v, s -> s.is(Items.TORCH), 1)) { TownWork.give(level, v, new ItemStack(Items.OAK_FENCE)); return; }
            level.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(post.above(), Blocks.TORCH.defaultBlockState());
            c.lamps++;
            return;
        }
        // Flower beds along the streets, up to two for every building.
        int want = Math.max(6, 2 * Ledger.buildings(v.id()).size());
        if (w.flowers + c.flowers >= want) return;
        for (BlockPos p : w.verges) {
            if (c.leave.containsKey(p.asLong()) || !level.getBlockState(p).isAir() || !level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK)) continue;
            if (((p.getX() - h.getX()) + (p.getZ() - h.getZ()) & 3) != 0) continue;     // a bed every few blocks, not a hedge of them
            BlockState flower = flowerFromTheStores(level, v);
            if (flower == null) return;
            if (!TownJobs.atWork(level, v, "tidy", p, "planting a flower bed by the street", AssistantEntity.StationTask.HAUL)) {
                TownWork.give(level, v, new ItemStack(flower.getBlock().asItem()));
                return;
            }
            level.setBlockAndUpdate(p, flower);
            c.flowers++;
            return;
        }
    }

    @Nullable
    private static BlockState flowerFromTheStores(ServerLevel level, Villages.Village v) {
        ItemStack got = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
        if (!got.isEmpty() && got.getItem() instanceof BlockItem bi) return bi.getBlock().defaultBlockState();
        // A bonemealed tuft of the meadow's own flowers.
        if (TownWork.take(level, v, s -> s.is(Items.BONE_MEAL), 1)) {
            Block[] meadow = { Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY };
            return meadow[level.getRandom().nextInt(meadow.length)].defaultBlockState();
        }
        return null;
    }

    /** A spot at the street's side, beside this street cell, for a lamp post: open ground, not kept off. */
    @Nullable
    private static BlockPos verge(ServerLevel level, Villages.Village v, BlockPos street) {
        BlockPos c = v.centre();
        for (int r = 1; r <= 2; r++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int x = street.getX() + d.getStepX() * r, z = street.getZ() + d.getStepZ() * r;
                int dx = x - c.getX(), dz = z - c.getZ();
                if (TownPlan.isStreet(dx, dz) || TownPlan.isSquare(dx, dz) || !Eyesores.open(v.id(), dx, dz)) continue;
                int y = Eyesores.top(level, x, z);
                BlockPos g = new BlockPos(x, y, z);
                if (Math.abs(y + 1 - street.getY()) > 1 || !level.getBlockState(g).isFaceSturdy(level, g, Direction.UP)) continue;
                if (!level.getBlockState(g.above()).canBeReplaced() || !level.getBlockState(g.above(2)).isAir()
                        || !level.getBlockState(g.above()).getFluidState().isEmpty()) continue;
                if (!mayChange(level, v, g.above())) continue;
                return g.above();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the looks

    /** Weight of one eyesore in the looks (a spill far worse than a scar). */
    private static double weight(Eyesores.Kind k) {
        return k.worst / 20.0;
    }

    /** The town's looks, measured off a whole walk of it. */
    static Looks measure(ServerLevel level, Villages.Village v, Eyesores.Walk w) {
        UUID id = v.id();
        int reach = Villages.townReach(id);
        int columns = (2 * reach + 1) * (2 * reach + 1);
        double weighed = 0;
        Eyesores.Kind worstKind = null;
        int worstN = 0;
        for (Map.Entry<Eyesores.Kind, Integer> e : w.counts.entrySet()) {
            weighed += weight(e.getKey()) * e.getValue();
            if (e.getValue() * e.getKey().worst > worstN) {
                worstN = e.getValue() * e.getKey().worst;
                worstKind = e.getKey();
            }
        }
        int[] inside = Floors.count(level, id);
        int grassInside = inside[0] + inside[1];
        weighed += grassInside * 0.5;
        int lying = Sweepers.lying(level, v);
        weighed += lying * 0.3;
        double allowance = Math.max(10.0, columns / 200.0);
        int tidiness = (int) Math.round(40.0 * Math.max(0.0, 1.0 - weighed / allowance));
        int greenery = w.green + w.bare == 0 ? 10 : (int) Math.round(15.0 * Math.min(1.0, w.green / (double) (w.green + w.bare) / 0.8));
        int lighting = w.streets == 0 ? 10 : (int) Math.round(15.0 * Math.min(1.0, w.lit / (double) w.streets / 0.6));
        int buildings = Ledger.buildings(id).size();
        int flowers = (int) Math.round(10.0 * Math.min(1.0, w.flowers / (double) Math.max(6, 2 * buildings)));
        int sites = Villages.sitesOf(id).size();
        int finished = buildings + sites == 0 ? 10 : (int) Math.round(20.0 * buildings / (double) (buildings + sites));
        int score = Math.max(0, Math.min(100, tidiness + greenery + lighting + flowers + finished));
        String worst = grassInside > 0 && (worstKind == null || grassInside * 40 > worstN) ? "grass growing inside the buildings"
            : worstKind == null ? "" : worstKind.words + (w.count(worstKind) > 1 ? " (" + w.count(worstKind) + ")" : "");
        return new Looks(score, tidiness, greenery, lighting, flowers, finished, w.total(), grassInside, word(score), worst);
    }

    public static String word(int score) {
        return score >= 85 ? "a picture" : score >= PROUD ? "well kept" : score >= UNTIDY ? "tidy enough" : score >= 30 ? "untidy" : "a mess";
    }

    /** The town's looks as last measured, or null before its first whole walk. */
    @Nullable
    public static Looks looks(UUID village) {
        Crew c = CREWS.get(village);
        return c == null ? null : c.looks;
    }

    /** Is today the town's clean-up day (called by its elder)? */
    public static boolean cleanupDay(ServerLevel level, UUID village) {
        Crew c = CREWS.get(village);
        return c != null && c.cleanupCalled == level.getDayTime() / 24000L;
    }

    /** A new measure of the looks: folk notice, and an untidy town has its elder call a clean-up day. */
    private static void noticed(ServerLevel level, Villages.Village v, Crew c) {
        Looks l = c.looks;
        if (l == null) return;
        long day = level.getDayTime() / 24000L;
        long t = level.getDayTime() % 24000L;
        if (l.score() < UNTIDY && day - c.cleanupCalled >= CLEANUP_EVERY && t < 6000L && Villages.headcount(v.id()) >= 6) {
            c.cleanupCalled = day;
            Ledger.note(v.id(), "tidy_cleanup", Long.toString(day));
            String elder = Villages.elderName(v.id());
            Villages.tell(v.id(), day, (elder.isEmpty() ? "The town" : "Elder " + elder) + " called a clean-up day: the town had become "
                + l.word() + (l.worst().isEmpty() ? "" : " (" + l.worst() + ")"));
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && level.getRandom().nextInt(6) == 0) {
                    FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Clean-up day! Grab a broom.", "About time somebody tidied this place.",
                        "Many hands make light work."));
                }
            }
        }
        // Somebody passing remarks on it, now and then.
        if (level.getRandom().nextInt(4) == 0) {
            List<AssistantEntity> folk = Villages.folkOf(v.id());
            if (!folk.isEmpty() && folk.get(level.getRandom().nextInt(folk.size())) instanceof VillageFolkEntity f && !f.isBaby() && !f.isSleeping()) {
                String line = l.score() >= 85 ? FolkTalk.pick(level.getRandom(), "Doesn't the town look a picture?", "I do like what we've done with the place.")
                    : l.score() < 30 ? FolkTalk.pick(level.getRandom(), "The square's a mess. Does nobody sweep round here?", "Look at the state of this town.")
                    : l.score() < UNTIDY ? (l.worst().isEmpty() ? "The place could do with a tidy."
                        : FolkTalk.pick(level.getRandom(), "The place could do with a tidy.", "Somebody ought to see to " + l.worst() + "."))
                    : null;
                if (line != null) FolkTalk.speak(f, line);
            }
        }
    }

    /** [tidy] Contentment's say: proud of a town that looks a picture, put out by a mess. */
    public static int contentment(@Nullable UUID village, List<String> good, List<String> bad) {
        Looks l = village == null ? null : looks(village);
        if (l == null) return 0;
        if (l.score() >= 85) { good.add("the town looks a picture"); return 3; }
        if (l.score() >= PROUD) { good.add("a well-kept town"); return 1; }
        if (l.score() < 30) { bad.add("the town's a mess"); return -4; }
        if (l.score() < UNTIDY) { bad.add("the town's untidy"); return -2; }
        return 0;
    }

    /** The board's line: the looks, what spoils them, the crew's day. Null before the first walk. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        Looks l = looks(village);
        if (l == null) return null;
        Crew c = crew(level, village);
        int done = c.floored + c.plantsUp + c.flowers + c.trimmed + c.lamps;
        for (int n : c.done.values()) done += n;
        return "Looks: " + l.score() + " (" + l.word() + ")" + (l.worst().isEmpty() ? "" : "; to see to: " + l.worst())
            + (cleanupDay(level, village) ? "; today is clean-up day" : "")
            + (done > 0 ? "; tidied today: " + done : "") + ".";
    }

    /** Into the storehouse's report (Sweepers.report): the looks and the tidying, for the town's books. */
    static void report(ServerLevel level, UUID village, net.minecraft.nbt.CompoundTag out) {
        Looks l = looks(village);
        if (l == null) return;
        Crew c = crew(level, village);
        out.putInt("looks", l.score());
        out.putString("looks_word", l.word());
        out.putString("looks_worst", l.worst());
        out.putInt("eyesores", l.eyesores());
        out.putInt("grass_inside", l.grassInside());
        int done = c.floored + c.plantsUp;
        for (int n : c.done.values()) done += n;
        out.putInt("tidied", done);
        out.putString("looks_parts", "tidiness " + l.tidiness() + "/40, greenery " + l.greenery() + "/15, lighting " + l.lighting()
            + "/15, flowers " + l.flowers() + "/10, finished " + l.finished() + "/20");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town's crew looked at now (as every five seconds). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        crew(level, v.id()).looked = -100000L;
        tick(level, v);
    }

    /** Tests: the town walked at once and its looks measured. */
    public static Looks measureForTests(ServerLevel level, Villages.Village v) {
        Eyesores.Walk w = Eyesores.walkNow(level, v);
        Crew c = crew(level, v.id());
        c.looks = measure(level, v, w);
        return c.looks;
    }

    /** Tests: today's floors, {cells floored, plants taken up}. */
    public static int[] floorsForTests(ServerLevel level, UUID village) {
        Crew c = crew(level, village);
        return new int[]{ c.floored, c.plantsUp };
    }

    /** Tests: today's tidying by kind, and the proud town's {flowers, trimmed, lamps}. */
    public static Map<Eyesores.Kind, Integer> doneForTests(ServerLevel level, UUID village) {
        Map<Eyesores.Kind, Integer> out = new java.util.EnumMap<>(Eyesores.Kind.class);
        out.putAll(crew(level, village).done);
        return out;
    }

    public static int[] proudForTests(ServerLevel level, UUID village) {
        Crew c = crew(level, village);
        return new int[]{ c.flowers, c.trimmed, c.lamps };
    }

    /** Tests: as though the elder had called a clean-up day today (true) or none for days (false). */
    public static void cleanupForTests(ServerLevel level, UUID village, boolean today) {
        crew(level, village).cleanupCalled = today ? level.getDayTime() / 24000L : -100L;
    }
}
