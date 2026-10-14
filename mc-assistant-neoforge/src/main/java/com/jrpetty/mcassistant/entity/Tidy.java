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

    /** How often a town's crew is looked at (ticks). */
    static final long EVERY = 100L;
    /** How often the town is walked on, and how many of its columns each time: a slice a second, so no one tick carries
     *  a whole town's walk (the largest town, 183 columns square, is gone over in about a minute). */
    static final long WALK_EVERY = 20L;
    static final int WALK = 500;
    /** How near the spot a fix is put right along with the one the hand was sent to. */
    static final int ALONG = 4;
    /** A spot nobody could get to, or put right, is left this long. */
    static final long LEAVE = 6000L;
    /** Crews out on a clean-up day (TownLooks), and on any other. */
    static final int CLEANUP_CREWS = 3;
    /** A dark corner with no room for a lamp post beside it is looked at again only after this many times the usual wait. */
    static final int NO_ROOM_LEAVE = 4;
    /** Block light a street cell wants after dark (the game's monsters spawn below it). */
    static final int LIT = 8;
    /** A flower bed every so many blocks along a verge (a bed, not a hedge of flowers). */
    static final int FLOWER_EVERY = 4;
    /** The sweeper puts an eyesore right within this many blocks of it (and this far up or down). */
    static final double BROOM_REACH = 3.0, BROOM_UP = 5.0;
    /** The sweeper gives an eyesore up when it has got no nearer in this long. */
    static final long BROOM_STUCK = 400L;

    /** A town's crew: what it is going for, and what it has done today. */
    static final class Crew {
        long looked = -100000L, walked = -100000L;
        @Nullable Floors.Fix floor;
        final Map<String, Eyesores.Spot> going = new HashMap<>();
        /** Spots left alone a while (nobody could get to them, or nothing in the stores to put them right with), till when. */
        final Map<Long, Long> leave = new HashMap<>();
        int floored, plantsUp;
        final Map<Eyesores.Kind, Integer> done = new java.util.EnumMap<>(Eyesores.Kind.class);
        int flowers, trimmed, lamps;
        long day = -1L;

        int doneToday() {
            int n = floored + plantsUp + flowers + trimmed + lamps;
            for (int k : done.values()) n += k;
            return n;
        }
    }

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
        TownLooks.resetForTests();
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
        }
        return c;
    }

    // ------------------------------------------------------------------ the round

    /** Villages whose round has gone wrong once (said once in the log, not every five seconds). */
    private static final java.util.Set<UUID> FAULTED = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** From the town's rounds (the folk's tick, beside TownWork.tick): a walk on through the town, and the crew's next piece of work. */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Crew c = crew(level, v.id());
        boolean walk = now - c.walked >= WALK_EVERY || now < c.walked, work = now - c.looked >= EVERY || now < c.looked;
        if (!walk && !work) return;
        if (!level.isLoaded(v.centre())) return;
        try {
            if (walk) {
                c.walked = now;
                walkOn(level, v);
            }
            if (work) {
                c.looked = now;
                round(level, v, c, now);
            }
        } catch (RuntimeException e) {
            // Never a crash in a folk's tick for the look of the town: said once, and the round tried again next time.
            if (FAULTED.add(v.id())) LOG.error("[MCA-TIDY] the tidy round of {} went wrong", Villages.name(v.id()), e);
        }
    }

    /** A slice more of the town walked over; a whole walk done, the town's looks measured afresh. */
    private static void walkOn(ServerLevel level, Villages.Village v) {
        Eyesores.Walk before = Eyesores.last(v.id());
        Eyesores.step(level, v, WALK);
        Eyesores.Walk walk = Eyesores.last(v.id());
        if (walk != null && walk != before) TownLooks.measured(level, v, TownLooks.measure(level, v, walk));
    }

    private static void round(ServerLevel level, Villages.Village v, Crew c, long now) {
        if (Raids.underAlarm(v.id())) return;
        c.leave.values().removeIf(until -> until < now);
        if (floors(level, v, c)) return;
        int crews = TownLooks.cleanupDay(level, v.id()) ? CLEANUP_CREWS : 1;
        for (int k = 0; k < crews; k++) {
            if (!eyesore(level, v, c, k == 0 ? "tidy" : "tidy/" + (k + 1))) break;
        }
        TownLooks.Looks l = TownLooks.looks(v.id());
        if (l != null && l.score() >= TownLooks.PROUD && c.going.isEmpty()) proud(level, v, c);
    }

    /** How much the crews (and the sweepers) have tidied in the town today. */
    static int doneToday(ServerLevel level, UUID village) {
        return crew(level, village).doneToday();
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
            if (TidyFixes.fix(level, v, o)) {
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
        if (d <= BROOM_REACH && Math.abs(s.pos().getY() - f.getY()) <= BROOM_UP) {
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
        } else if (now - pr[1] > BROOM_STUCK || now < pr[1]) {
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

    // ------------------------------------------------------------------ a proud town

    /**
     * A town that is well kept or better, and has nothing worse to see to: the leaves hanging over its streets trimmed,
     * its dark corners lit, flowers along its streets. One thing a visit.
     */
    private static void proud(ServerLevel level, Villages.Village v, Crew c) {
        Eyesores.Walk w = Eyesores.last(v.id());
        if (w == null) return;
        if (trim(level, v, c, w) || light(level, v, c, w)) return;
        flowerBed(level, v, c, w);
    }

    /** Leaves over the street, at head height: trimmed back. True while at it. */
    private static boolean trim(ServerLevel level, Villages.Village v, Crew c, Eyesores.Walk w) {
        for (BlockPos p : w.overhang) {
            if (!(level.getBlockState(p).getBlock() instanceof LeavesBlock) || c.leave.containsKey(p.asLong())) continue;
            if (!TownJobs.atWork(level, v, "tidy", p, "trimming the leaves over the street", AssistantEntity.StationTask.HAUL)) return true;
            if (TidyFixes.takeUp(level, v, p)) c.trimmed++;
            return true;
        }
        return false;
    }

    /** A dark stretch of street: a light on a post at its side, of the stores' fence and torch. True while at it. */
    private static boolean light(ServerLevel level, Villages.Village v, Crew c, Eyesores.Walk w) {
        for (BlockPos p : w.dark) {
            if (c.leave.containsKey(p.asLong()) || level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p) >= LIT) continue;
            BlockPos post = verge(level, v, p);
            if (post == null) {
                c.leave.put(p.asLong(), level.getGameTime() + LEAVE * NO_ROOM_LEAVE);
                continue;
            }
            if (Market.stock(level, v.id(), s -> s.is(Items.TORCH)) == 0) return false;
            if (!TownJobs.atWork(level, v, "tidy", post, "lighting a dark corner of the street", AssistantEntity.StationTask.HAUL)) return true;
            if (!Crafts.fence(level, v)) {
                c.leave.put(p.asLong(), level.getGameTime() + LEAVE);
                return true;
            }
            if (!TownWork.take(level, v, s -> s.is(Items.TORCH), 1)) {
                TidyFixes.store(level, v, post, new ItemStack(Items.OAK_FENCE));
                return true;
            }
            level.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(post.above(), Blocks.TORCH.defaultBlockState());
            c.lamps++;
            return true;
        }
        return false;
    }

    /** A flower bed on a verge by the street, up to two for every building, out of the stores' flowers (or bone meal). */
    private static void flowerBed(ServerLevel level, Villages.Village v, Crew c, Eyesores.Walk w) {
        int want = Math.max(TownLooks.LEAST_FLOWERS, TownLooks.FLOWERS_A_BUILDING * Ledger.buildings(v.id()).size());
        if (w.flowers + c.flowers >= want) return;
        BlockPos h = v.centre();
        for (BlockPos p : w.verges) {
            if (c.leave.containsKey(p.asLong()) || !level.getBlockState(p).isAir() || !level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK)) continue;
            if (Math.floorMod((p.getX() - h.getX()) + (p.getZ() - h.getZ()), FLOWER_EVERY) != 0) continue;
            if (!TidyFixes.mayChange(level, v, p, TownBlocks.Why.GARDEN)) continue;
            BlockState flower = flowerFromTheStores(level, v);
            if (flower == null) return;
            if (!TownJobs.atWork(level, v, "tidy", p, "planting a flower bed by the street", AssistantEntity.StationTask.HAUL)) {
                TidyFixes.store(level, v, p, new ItemStack(flower.getBlock().asItem()));
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
                if (!TidyFixes.mayChange(level, v, g.above(), TownBlocks.Why.LAMP) || !TidyFixes.mayChange(level, v, g.above(2), TownBlocks.Why.LAMP)) continue;
                return g.above();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the command

    /**
     * /village tidy: the town's looks and what spoils them (walked now), each eyesore a line "SPOT kind x y z" for the
     * client smoke; /village tidy now (ops): the crew's rounds done on the spot first, no hand walked over.
     */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
        return net.minecraft.commands.Commands.literal("tidy").executes(ctx -> tell(ctx, false))
            .then(net.minecraft.commands.Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> tell(ctx, true)));
    }

    private static int tell(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx, boolean now) {
        Villages.Village v = Quarters.near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        if (now) {
            boolean was = TownJobs.instantNow();
            TownJobs.instantForTests(true);
            try {
                for (int i = 0; i < 40; i++) tickForTests(level, v);
            } finally {
                TownJobs.instantForTests(was);
            }
        }
        TidyAudit.Report r = TidyAudit.check(level, v.id());
        if (r == null) return 0;
        StringBuilder sb = new StringBuilder("THE TOWN'S LOOKS " + Villages.name(v.id()) + ": ");
        sb.append(String.join(" ", r.lines()));
        String board = TownLooks.boardLine(level, v.id());
        if (board != null) sb.append(" Board: ").append(board.substring(board.indexOf('|') + 1));
        int shown = 0;
        for (Eyesores.Spot s : r.spots()) {
            if (shown++ >= 12) break;
            sb.append("\nSPOT ").append(s.kind().name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(s.pos().getX()).append(' ')
                .append(s.pos().getY()).append(' ').append(s.pos().getZ()).append(" (").append(s.note()).append(")");
        }
        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
        return 1;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town's crew looked at now (as every five seconds). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        Crew c = crew(level, v.id());
        c.looked = c.walked = -100000L;
        tick(level, v);
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
}
