package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rest day's contests [batchC]: the fishing contest and the children's sports day.
 *
 * <p>Folk have no seasons to go by, so the town takes its turns by the week: of every three rest days, the
 * first has the fishing contest in the late morning and the second the children's sports day (the third has
 * neither; every one has its football, if the town has a pitch).
 *
 * <p><b>The fishing contest.</b> The town's fishers, and anybody whose pastime is fishing, up to six of them,
 * go down to the town's water (the fishers' own water, else the nearest pond or river with a bank to stand on)
 * and fish side by side for an hour, each with its own rod or one borrowed from the stores (given back after).
 * The catch is real: the bites come as a fisher's do, and what comes up is what comes up to anybody's line,
 * mostly fish and now and then an old bone or a bit of string. The most fish wins; the winner gets a small
 * purse out of the treasury (five coins, if it has them), and every fish caught goes into the stores.
 *
 * <p><b>The children's sports day.</b> The town's children, up to eight of them, line up at one end of the
 * park (or the square, with no park), in lanes, and race to the other end. Every child runs its own race (some
 * are quicker on the day than others); the first over the line wins a cookie out of the stores, or an apple.
 * Their parents come and stand at the finish and cheer them home.
 */
public final class Contests {

    private Contests() {}

    /** The fishing contest: from the late morning, for an hour. */
    static final long FISH_AT = 4000L, HOUR = 1000L;
    /** The sports day: late morning. */
    static final long RACE_AT = 4000L;
    /** What the winner of the fishing contest is given, out of the treasury, at most. */
    public static final int PURSE = 5;
    static final int MOST_ANGLERS = 6, MOST_RUNNERS = 8;

    /** Which of the three rest days it is: 0 the fishing contest, 1 the sports day, 2 neither. */
    static int turn(long day) {
        return Math.floorMod(Math.floorDiv(day, 7L), 3);
    }

    public static boolean fishingDay(UUID village, long day) {
        return RestDay.today(village, day) && turn(day) == 0;
    }

    public static boolean raceDay(UUID village, long day) {
        return RestDay.today(village, day) && turn(day) == 1;
    }

    private static volatile boolean quick;

    /** Tests: the fish biting fast (a minute's contest, which a test weighs in for itself sooner). */
    public static void quickForTests(boolean on) {
        quick = on;
    }

    public static void resetForTests() {
        FISHING.clear();
        ANGLERS.clear();
        RACES.clear();
        RUNNERS.clear();
        WATCHERS.clear();
        quick = false;
    }

    static boolean busy(VillageFolkEntity f) {
        return ANGLERS.containsKey(f.getUUID()) || RUNNERS.containsKey(f.getUUID());
    }

    /** A parent at the finish of the children's race. */
    static boolean watching(VillageFolkEntity f) {
        return WATCHERS.containsKey(f.getUUID());
    }

    // ================================================================== the fishing contest

    static final class Angler {
        final BlockPos spot, water;
        boolean borrowed, there;
        int fish;
        long nextBite, last;
        final List<ItemStack> caught = new ArrayList<>();

        Angler(BlockPos spot, BlockPos water) {
            this.spot = spot;
            this.water = water;
        }
    }

    static final class Fishing {
        final UUID village;
        final ResourceKey<Level> dim;
        final long start, end;
        final Map<UUID, Angler> anglers = new LinkedHashMap<>();

        Fishing(UUID village, ResourceKey<Level> dim, long start, long end) {
            this.village = village;
            this.dim = dim;
            this.start = start;
            this.end = end;
        }
    }

    private static final Map<UUID, Fishing> FISHING = new ConcurrentHashMap<>();
    private static final Map<UUID, Fishing> ANGLERS = new ConcurrentHashMap<>();

    /** Fishes by trade or by liking. */
    static boolean angler(VillageFolkEntity f) {
        return f.stationTask() == StationTask.FISH || (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.FISHING);
    }

    static boolean free(VillageFolkEntity f) {
        return f.isAlive() && !f.isShowcase() && !f.isHired() && !f.isSleeping() && f.trip() == null && f.expedition() == null
            && !Nether.away(f) && !Drover.busy(f) && f.getTarget() == null && f.talkPartner() == null;
    }

    /** Its own rod, in its hand or its pack. */
    static boolean hasRod(VillageFolkEntity f) {
        if (f.getMainHandItem().is(Items.FISHING_ROD)) return true;
        for (ItemStack s : f.getInventoryItems()) if (s.is(Items.FISHING_ROD)) return true;
        return false;
    }

    /**
     * The contest begun (Sport's programme, on the day, or a test): the anglers chosen, each a place on the bank
     * of the town's water and a rod. Returns how it went, in a few words.
     */
    static String startFishing(ServerLevel level, Villages.Village v) {
        if (FISHING.containsKey(v.id())) return "already on";
        List<VillageFolkEntity> entrants = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !free(f) || !angler(f) || Sport.busyElsewhere(f, "contests")) continue;
            if (f.stationTask() == StationTask.GUARD) continue;
            if (f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) > 120.0 * 120.0) continue;
            entrants.add(f);
        }
        if (entrants.size() < 2) return "too few anglers: " + entrants.size();
        if (entrants.size() > MOST_ANGLERS) entrants = new ArrayList<>(entrants.subList(0, MOST_ANGLERS));
        BlockPos water = null;
        for (VillageFolkEntity f : entrants) {
            if (f.stationTask() == StationTask.FISH) { water = com.jrpetty.mcassistant.entity.goal.FishGoal.waterFor(f); if (water != null) break; }
        }
        if (water == null) water = findWater(level, v.centre());
        if (water == null) return "no water to fish in";
        List<BlockPos[]> banks = banks(level, water, entrants.size());
        if (banks.isEmpty()) return "no bank to stand on";
        long now = level.getGameTime();
        Fishing c = new Fishing(v.id(), level.dimension(), now, now + (quick ? 1200 : HOUR));
        int i = 0;
        for (VillageFolkEntity f : entrants) {
            boolean borrowed = false;
            if (!hasRod(f)) {
                ItemStack rod = Crafts.takeOne(level, v, s -> s.is(Items.FISHING_ROD));
                if (rod.isEmpty()) continue;                                      // no rod: it watches instead
                ItemStack left = f.insertItem(rod);
                if (!left.isEmpty()) { Crafts.store(level, v, left); continue; }
                borrowed = true;
            }
            BlockPos[] b = banks.get(i++ % banks.size());
            Angler a = new Angler(b[0], b[1]);
            a.borrowed = borrowed;
            a.nextBite = now + (quick ? 30 : 100 + level.getRandom().nextInt(200));
            c.anglers.put(f.getUUID(), a);
            f.clearQueue();
            f.getNavigation().stop();
        }
        if (c.anglers.size() < 2) {
            for (Map.Entry<UUID, Angler> e : c.anglers.entrySet()) giveBack(level, v, e.getKey(), e.getValue());
            return "too few rods";
        }
        FISHING.put(v.id(), c);
        for (UUID u : c.anglers.keySet()) ANGLERS.put(u, c);
        VillageFolkEntity first = Football.live(level, c.anglers.keySet().iterator().next());
        if (first != null) FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "The fishing contest! Lines in, everybody!",
            "Biggest catch wins. May the best rod win!", "I can feel it. Today's my day."));
        return "the fishing contest is on: " + c.anglers.size() + " anglers";
    }

    /** Open water with water round it (a pond, a river: not a puddle), nearest the heart, within reach of the town. */
    @Nullable
    static BlockPos findWater(ServerLevel level, BlockPos heart) {
        for (int r = 4; r <= 64; r += 3) {
            for (int i = -r; i <= r; i += 2) {
                for (int[] d : new int[][]{ { i, -r }, { i, r }, { -r, i }, { r, i } }) {
                    int x = heart.getX() + d[0], z = heart.getZ() + d[1];
                    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                    BlockPos w = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                    if (!open(level, w)) continue;
                    int round = 0;
                    for (Direction dir : Direction.Plane.HORIZONTAL) if (level.getFluidState(w.relative(dir)).is(FluidTags.WATER)) round++;
                    if (round >= 2 && !banks(level, w, 1).isEmpty()) return w;
                }
            }
        }
        return null;
    }

    /** Water open to the sky, with nothing over it. */
    static boolean open(ServerLevel level, BlockPos w) {
        return level.getFluidState(w).is(FluidTags.WATER) && level.getFluidState(w.above()).isEmpty()
            && level.getBlockState(w.above()).canBeReplaced();
    }

    /** Places to stand on the bank of this water and cast into it: {stand, the water before it}. Nearest first. */
    static List<BlockPos[]> banks(ServerLevel level, BlockPos water, int want) {
        List<BlockPos[]> out = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (int r = 0; r <= 8 && out.size() < Math.max(want, 1); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos w = water.offset(dx, 0, dz);
                    if (!open(level, w)) continue;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos stand = w.relative(dir).above();
                        if (!level.getFluidState(stand).isEmpty() || !level.getFluidState(stand.below()).isEmpty()) continue;
                        if (!level.getBlockState(stand.below()).isFaceSturdy(level, stand.below(), Direction.UP)) continue;
                        if (!level.getBlockState(stand).getCollisionShape(level, stand).isEmpty()) continue;
                        if (!level.getBlockState(stand.above()).getCollisionShape(level, stand.above()).isEmpty()) continue;
                        boolean near = false;
                        for (long t : taken) if (BlockPos.of(t).distManhattan(stand) < 2) near = true;
                        if (near) continue;
                        taken.add(stand.asLong());
                        out.add(new BlockPos[]{ stand.immutable(), w.immutable() });
                        if (out.size() >= want) return out;
                    }
                }
            }
        }
        return out;
    }

    /** An angler at the contest: to its place on the bank, its rod out, a cast, and whatever bites. */
    static boolean fish(VillageFolkEntity f, ServerLevel level, Fishing c) {
        Angler a = c.anglers.get(f.getUUID());
        if (a == null) return false;
        f.hobbyNow = "fishing in the fishing contest";
        f.lastLeisureTick = f.tickCount;
        Vec3 stand = Vec3.atBottomCenterOf(a.spot);
        if (f.position().distanceToSqr(stand) > 2.25) {
            if (f.getNavigation().isDone() || f.tickCount % 60 == 0) f.getNavigation().moveTo(stand.x, stand.y, stand.z, 1.0);
            // Somewhere it cannot get to: it casts from where it stands, if the water is within a line's reach.
            if (f.position().distanceToSqr(Vec3.atCenterOf(a.water)) > 36.0) return true;
        }
        f.getNavigation().stop();
        a.there = true;
        holdRod(f);
        f.getLookControl().setLookAt(a.water.getX() + 0.5, a.water.getY() + 0.5, a.water.getZ() + 0.5);
        long now = level.getGameTime();
        if (now % 10 == 0) {
            level.sendParticles(ParticleTypes.FISHING, a.water.getX() + 0.5, a.water.getY() + 0.95, a.water.getZ() + 0.5, 1, 0.05, 0.0, 0.05, 0.0);
        }
        if (now < a.nextBite) return true;
        // The bite, and the catch: weighted as anybody's line brings it up.
        RandomSource r = f.getRandom();
        int roll = r.nextInt(100);
        ItemStack loot = roll < 55 ? new ItemStack(Items.COD) : roll < 78 ? new ItemStack(Items.SALMON)
            : roll < 84 ? new ItemStack(Items.PUFFERFISH) : roll < 88 ? new ItemStack(Items.TROPICAL_FISH)
            : roll < 92 ? new ItemStack(Items.STRING) : roll < 95 ? new ItemStack(Items.BONE)
            : roll < 98 ? new ItemStack(Items.LEATHER) : new ItemStack(Items.INK_SAC);
        boolean isFish = roll < 88;
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, a.water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6F, 1.0F);
        level.sendParticles(ParticleTypes.SPLASH, a.water.getX() + 0.5, a.water.getY() + 1.0, a.water.getZ() + 0.5, 10, 0.15, 0.1, 0.15, 0.2);
        a.caught.add(loot);
        a.last = now;
        if (isFish) {
            a.fish++;
            f.note(AssistantEntity.Deed.FISH_CAUGHT, 1);
            if (r.nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Got one!", "That's " + a.fish + "!", "A beauty!", "Ha! Beat that."));
        } else if (r.nextInt(2) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(r, "An old boot. Well, near enough.", "That doesn't count, does it?"));
        }
        ItemStack rod = f.getMainHandItem();
        if (rod.is(Items.FISHING_ROD)) rod.hurtAndBreak(1, f, EquipmentSlot.MAINHAND);
        int wait = quick ? 20 + r.nextInt(30) : f.pacedTicks(100 + r.nextInt(300), 50);
        a.nextBite = now + wait;
        return true;
    }

    /** Its rod in its hand, out of its pack. */
    static void holdRod(VillageFolkEntity f) {
        if (f.getMainHandItem().is(Items.FISHING_ROD)) return;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.FISHING_ROD)) {
                ItemStack old = f.getMainHandItem();
                f.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
                inv.set(i, old);
                return;
            }
        }
    }

    /** A borrowed rod back into the stores (out of the hand or the pack). */
    static void giveBack(ServerLevel level, Villages.Village v, UUID u, Angler a) {
        if (!a.borrowed) return;
        VillageFolkEntity f = Football.live(level, u);
        if (f == null) return;
        if (f.getMainHandItem().is(Items.FISHING_ROD)) {
            Crafts.store(level, v, f.getMainHandItem().copy());
            f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.FISHING_ROD)) {
                Crafts.store(level, v, inv.get(i).copy());
                inv.set(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    /** The hour up (Sport's clock): the weigh-in, the purse, the fish into the stores. */
    static void referee(ServerLevel level, Fishing c) {
        if (level.getGameTime() < c.end) return;
        weighIn(level, c);
    }

    @Nullable
    static String weighIn(ServerLevel level, Fishing c) {
        if (FISHING.remove(c.village) == null) return null;
        for (UUID u : c.anglers.keySet()) ANGLERS.remove(u, c);
        Villages.Village v = Villages.get(c.village);
        if (v == null) return null;
        long day = level.getDayTime() / 24000L;
        UUID best = null;
        int total = 0;
        Angler top = null;
        for (Map.Entry<UUID, Angler> e : c.anglers.entrySet()) {
            Angler a = e.getValue();
            total += a.fish;
            for (ItemStack s : a.caught) Crafts.store(level, v, s);
            giveBack(level, v, e.getKey(), a);
            if (a.fish > 0 && (top == null || a.fish > top.fish || a.fish == top.fish && a.last < top.last)) { top = a; best = e.getKey(); }
        }
        VillageFolkEntity winner = Football.live(level, best);
        String name = winner != null ? winner.displayNameCap() : null;
        if (name == null) {
            String line = "the fishing contest: " + total + " fish caught by " + c.anglers.size() + " anglers, and nobody's the winner";
            Villages.tell(c.village, day, line);
            League.record(c.village, "fishing", "day " + day + ", nobody caught a thing");
            return line;
        }
        int purse = Math.min(PURSE, Ledger.coins(c.village));
        if (purse > 0) {
            purse = Ledger.takeCoins(c.village, purse);
            winner.earn(purse);
            Economy.spent(c.village, purse);
        }
        String line = name + " won the fishing contest with " + top.fish + " fish (" + total + " caught in all, into the stores)"
            + (purse > 0 ? ", and a purse of " + purse + " coins" : "");
        Villages.tell(c.village, day, line);
        League.record(c.village, "fishing", "day " + day + ", " + name + " with " + top.fish + " fish" + (purse > 0 ? " (" + purse + " coins)" : ""));
        winner.persona().remember(day, "I won the fishing contest with " + top.fish + " fish", 5);
        FolkTalk.speak(winner, FolkTalk.pick(level.getRandom(), "I won! " + top.fish + " fish!", "Champion angler, that's me.",
            "Beginner's luck. Don't tell anybody."));
        return line;
    }

    // ================================================================== the children's sports day

    /** A straight track, so many lanes wide, from its start line to its finish line. */
    record Track(BlockPos start, Direction along, Direction across, int length, int lanes) {
        BlockPos startOf(int lane) { return start.relative(across, lane % lanes); }
        BlockPos finishOf(int lane) { return start.relative(across, lane % lanes).relative(along, length); }
        /** How far down the track a point is. */
        double run(Vec3 p) {
            return (p.x - (start.getX() + 0.5)) * along.getStepX() + (p.z - (start.getZ() + 0.5)) * along.getStepZ();
        }
    }

    static final int LINEUP = 0, RUN = 1;

    static final class Race {
        final UUID village;
        final ResourceKey<Level> dim;
        final Track track;
        final String where;
        final List<UUID> runners = new ArrayList<>();
        final Map<UUID, Double> pace = new LinkedHashMap<>();
        final Set<UUID> parents = new HashSet<>();
        int stage = LINEUP;
        long stageAt;
        @Nullable UUID winner;

        Race(UUID village, ResourceKey<Level> dim, Track track, String where) {
            this.village = village;
            this.dim = dim;
            this.track = track;
            this.where = where;
        }
    }

    private static final Map<UUID, Race> RACES = new ConcurrentHashMap<>();
    private static final Map<UUID, Race> RUNNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Race> WATCHERS = new ConcurrentHashMap<>();

    /** The race set up: the children called to the start, their parents to the finish. How it went, in a few words. */
    static String startRace(ServerLevel level, Villages.Village v) {
        if (RACES.containsKey(v.id())) return "already on";
        List<VillageFolkEntity> kids = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isBaby() || !free(f) || Sport.busyElsewhere(f, "contests")) continue;
            if (f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) > 120.0 * 120.0) continue;
            kids.add(f);
        }
        if (kids.size() < 2) return "too few children: " + kids.size();
        if (kids.size() > MOST_RUNNERS) kids = new ArrayList<>(kids.subList(0, MOST_RUNNERS));
        int lanes = Math.min(4, kids.size());
        Track track = null;
        String where = "the square";
        List<Ledger.Building> parks = Park.parks(v.id());
        if (!parks.isEmpty()) {
            track = findTrack(level, Park.layout(parks.get(0)).centre(), lanes, 8);
            if (track != null) where = "the park";
        }
        if (track == null) track = findTrack(level, v.centre(), lanes, 12);
        if (track == null) return "no clear ground to race on";
        Race race = new Race(v.id(), level.dimension(), track, where);
        RandomSource r = level.getRandom();
        for (VillageFolkEntity f : kids) {
            race.runners.add(f.getUUID());
            race.pace.put(f.getUUID(), 0.95 + r.nextDouble() * 0.4);        // quicker on the day, or not
            for (UUID p : f.parentIds()) {
                VillageFolkEntity parent = Football.live(level, p);
                if (parent != null && !parent.isBaby() && free(parent) && parent.stationTask() != StationTask.GUARD) race.parents.add(p);
            }
            f.clearQueue();
            f.getNavigation().stop();
        }
        race.stageAt = level.getGameTime();
        RACES.put(v.id(), race);
        for (UUID u : race.runners) RUNNERS.put(u, race);
        for (UUID u : race.parents) WATCHERS.put(u, race);
        return "the children's race is on in " + where + ": " + kids.size() + " runners, " + race.parents.size() + " parents watching";
    }

    /** A straight run of level, open ground through or beside this spot, so many lanes wide: the longest there is. */
    @Nullable
    static Track findTrack(ServerLevel level, BlockPos centre, int lanes, int reach) {
        for (int length = 14; length >= 8; length -= 2) {
            for (Direction along : new Direction[]{ Direction.EAST, Direction.SOUTH }) {
                Direction across = along.getClockWise();
                for (int k = 0; k <= 2 * reach; k++) {
                    int off = (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2);
                    BlockPos start = centre.relative(along, -length / 2).relative(across, off - (lanes - 1) / 2);
                    Integer y = clear(level, start, along, across, length, lanes, centre.getY());
                    if (y != null) return new Track(new BlockPos(start.getX(), y, start.getZ()), along, across, length, lanes);
                }
            }
        }
        return null;
    }

    /** Is every lane of this track open, level ground (a step of one at most)? Its height if so. */
    @Nullable
    static Integer clear(ServerLevel level, BlockPos start, Direction along, Direction across, int length, int lanes, int nearY) {
        int first = Integer.MIN_VALUE;
        for (int lane = 0; lane < lanes; lane++) {
            int prev = Integer.MIN_VALUE;
            for (int s = 0; s <= length; s++) {
                BlockPos c = start.relative(across, lane).relative(along, s);
                if (level.getChunkSource().getChunkNow(c.getX() >> 4, c.getZ() >> 4) == null) return null;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                if (Math.abs(y - nearY) > 3) return null;
                if (prev != Integer.MIN_VALUE && Math.abs(y - prev) > 1) return null;
                BlockPos feet = new BlockPos(c.getX(), y, c.getZ());
                if (!level.getFluidState(feet.below()).isEmpty() || !level.getFluidState(feet).isEmpty()) return null;
                if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return null;
                if (first == Integer.MIN_VALUE) first = y;
                prev = y;
            }
        }
        return first;
    }

    /** A runner: to its lane at the start, then off down it at its own pace. */
    static boolean run(VillageFolkEntity f, ServerLevel level, Race race) {
        int lane = race.runners.indexOf(f.getUUID());
        if (lane < 0) return false;
        f.hobbyNow = "racing in the children's sports day";
        f.lastLeisureTick = f.tickCount;
        if (race.stage == LINEUP) {
            BlockPos s = race.track.startOf(lane);
            if (f.position().distanceToSqr(s.getX() + 0.5, f.getY(), s.getZ() + 0.5) > 1.0) {
                if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.getNavigation().moveTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 1.0);
            } else {
                f.getNavigation().stop();
                BlockPos end = race.track.finishOf(lane);
                f.getLookControl().setLookAt(end.getX() + 0.5, end.getY() + 1.0, end.getZ() + 0.5);
            }
            return true;
        }
        BlockPos end = race.track.finishOf(lane).relative(race.track.along(), 2);
        if (f.getNavigation().isDone() || f.tickCount % 20 == 0) {
            f.getNavigation().moveTo(end.getX() + 0.5, end.getY(), end.getZ() + 0.5, race.pace.getOrDefault(f.getUUID(), 1.0));
        }
        if (f.getRandom().nextInt(200) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Wheee!", "I'm winning!", "Wait for me!"));
        return true;
    }

    /** A parent: to the finish, beside the track, cheering its child home. */
    static boolean cheer(VillageFolkEntity f, ServerLevel level, Race race) {
        if (!free(f)) return false;
        f.hobbyNow = "watching the children's race";
        f.lastLeisureTick = f.tickCount;
        int k = new ArrayList<>(race.parents).indexOf(f.getUUID());
        BlockPos spot = race.track.finishOf(0).relative(race.track.along(), 1).relative(race.track.across(), -1 - (k % 4)).relative(race.track.along(), k / 4);
        if (f.position().distanceToSqr(spot.getX() + 0.5, f.getY(), spot.getZ() + 0.5) > 2.0) {
            if (f.getNavigation().isDone() || f.tickCount % 60 == 0) f.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
            return true;
        }
        f.getNavigation().stop();
        BlockPos s = race.track.startOf(0);
        f.getLookControl().setLookAt(s.getX() + 0.5, s.getY() + 1.0, s.getZ() + 0.5);
        if (race.stage == RUN && f.getRandom().nextInt(120) == 0) {
            for (UUID u : race.runners) {
                VillageFolkEntity kid = Football.live(level, u);
                if (kid != null && kid.parentIds().contains(f.getUUID())) {
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Go on, " + kid.displayNameCap() + "!", "Run, " + kid.displayNameCap() + ", run!",
                        "Faster, " + kid.displayNameCap() + "!"));
                    break;
                }
            }
        }
        return true;
    }

    /** Every tick (Sport's clock): the start when the children are lined up, the winner over the line. */
    static void referee(ServerLevel level, Race race) {
        long now = level.getGameTime();
        if (race.stage == LINEUP) {
            boolean ready = true;
            for (int i = 0; i < race.runners.size(); i++) {
                VillageFolkEntity f = Football.live(level, race.runners.get(i));
                BlockPos s = race.track.startOf(i);
                if (f != null && f.position().distanceToSqr(s.getX() + 0.5, f.getY(), s.getZ() + 0.5) > 2.25) ready = false;
            }
            if (!ready && now - race.stageAt < 500) return;
            race.stage = RUN;
            race.stageAt = now;
            VillageFolkEntity starter = race.parents.isEmpty() ? null : Football.live(level, race.parents.iterator().next());
            if (starter == null) starter = Football.live(level, race.runners.get(0));
            if (starter != null) FolkTalk.speak(starter, "Ready... steady... GO!");
            BlockPos s = race.track.startOf(0);
            level.playSound(null, s, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.NEUTRAL, 1.0F, 1.2F);
            return;
        }
        UUID first = null;
        double furthest = -100;
        for (UUID u : race.runners) {
            VillageFolkEntity f = Football.live(level, u);
            if (f == null) continue;
            double d = race.track.run(f.position());
            if (d >= race.track.length() - 0.5 && (first == null || d > furthest)) { first = u; furthest = d; }
            else if (first == null && d > furthest) furthest = d;
        }
        if (first == null && now - race.stageAt < 600) return;
        if (first == null) {
            // Nobody over the line in half a minute (a child stuck behind a bench): the furthest down the track wins.
            double best = -100;
            for (UUID u : race.runners) {
                VillageFolkEntity f = Football.live(level, u);
                if (f != null && race.track.run(f.position()) > best) { best = race.track.run(f.position()); first = u; }
            }
        }
        finish(level, race, first);
    }

    /** The winner: its prize out of the stores, into the chronicle; the race over. */
    @Nullable
    static String finish(ServerLevel level, Race race, @Nullable UUID winner) {
        if (RACES.remove(race.village) == null) return null;
        for (UUID u : race.runners) RUNNERS.remove(u, race);
        for (UUID u : race.parents) WATCHERS.remove(u, race);
        race.winner = winner;
        Villages.Village v = Villages.get(race.village);
        VillageFolkEntity f = Football.live(level, winner);
        if (v == null || f == null) return null;
        long day = level.getDayTime() / 24000L;
        ItemStack prize = Crafts.takeOne(level, v, s -> s.is(Items.COOKIE));
        if (prize.isEmpty()) prize = Crafts.takeOne(level, v, s -> s.is(Items.APPLE));
        String what = prize.isEmpty() ? null : prize.is(Items.COOKIE) ? "a cookie" : "an apple";
        if (!prize.isEmpty()) {
            ItemStack left = f.insertItem(prize);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        String line = f.displayNameCap() + " won the children's race across " + race.where + " (" + race.runners.size() + " ran)"
            + (what != null ? ", " + what + " for the prize" : ", and the cheers for a prize: nothing sweet in the stores");
        Villages.tell(race.village, day, line);
        League.record(race.village, "race", "day " + day + ", " + f.displayNameCap() + " first of " + race.runners.size() + (what != null ? " (" + what + ")" : ""));
        f.persona().remember(day, "I won the race on sports day", 5);
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "I won! I won!", "I'm the fastest!", "Did you see me?"));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getEyeY() + 0.3, f.getZ(), 10, 0.4, 0.3, 0.4, 0.1);
        return line;
    }

    // ================================================================== the folk's look, and the clock

    /** A folk in a contest (an angler, a runner, a parent at the finish): about it. True while it is. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Fishing c = ANGLERS.get(f.getUUID());
        if (c != null && c.dim.equals(level.dimension())) return fish(f, level, c);
        Race r = RUNNERS.get(f.getUUID());
        if (r != null && r.dim.equals(level.dimension())) return run(f, level, r);
        Race w = WATCHERS.get(f.getUUID());
        if (w != null && w.dim.equals(level.dimension()) && !Sport.busyElsewhere(f, "contests")) return cheer(f, level, w);
        return false;
    }

    /** Every tick: the races' starts and finishes; every second, the fishing contests' hour. */
    static void tick(ServerLevel level) {
        for (Race r : new ArrayList<>(RACES.values())) if (r.dim.equals(level.dimension())) referee(level, r);
        if (level.getGameTime() % 20 == 0) {
            for (Fishing c : new ArrayList<>(FISHING.values())) if (c.dim.equals(level.dimension())) referee(level, c);
        }
    }

    /** The board's word on a contest on now, or null. */
    @Nullable
    static String now(UUID village) {
        Fishing c = FISHING.get(village);
        if (c != null) {
            int fish = 0;
            for (Angler a : c.anglers.values()) fish += a.fish;
            return "the fishing contest at the water's edge, " + c.anglers.size() + " anglers, " + fish + " fish so far";
        }
        Race r = RACES.get(village);
        if (r != null) return "the children's race in " + r.where;
        return null;
    }

    // ================================================================== tests

    public static String fishingForTests(ServerLevel level, Villages.Village v) {
        return startFishing(level, v);
    }

    /** Tests: {anglers there, fish caught so far}, or null with no contest. */
    @Nullable
    public static int[] fishingStateForTests(UUID village) {
        Fishing c = FISHING.get(village);
        if (c == null) return null;
        int there = 0, fish = 0;
        for (Angler a : c.anglers.values()) { if (a.there) there++; fish += a.fish; }
        return new int[]{ there, fish };
    }

    /** Tests: the weigh-in now. */
    @Nullable
    public static String weighInForTests(ServerLevel level, UUID village) {
        Fishing c = FISHING.get(village);
        return c == null ? null : weighIn(level, c);
    }

    public static String raceForTests(ServerLevel level, Villages.Village v) {
        return startRace(level, v);
    }

    /** Tests: the race's stage (0 lining up, 1 running), or -1 when it is over. */
    public static int raceStageForTests(UUID village) {
        Race r = RACES.get(village);
        return r == null ? -1 : r.stage;
    }

    public static boolean watchingRaceForTests(UUID folk) {
        return WATCHERS.containsKey(folk);
    }
}
