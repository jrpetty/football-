package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [disasters] Fire's causes and the care a town takes against them.
 *
 * <ul>
 * <li><b>A spark from the forge.</b> Once a minute the town's lit furnaces are looked at (the smithy's, the
 *     smeltery's, a house's kitchen, the bakery's: every furnace and smoker its buildings' drawings put in).
 *     One with timber, wool or anything else that burns right beside it may throw a spark into the air beside
 *     that and set it alight: about one a fortnight in a careless town (villageSparkDays: ten days), twice as
 *     likely in a dry spell and three times in a drought, half as likely with a cauldron of water by it, and
 *     the more that burns round it the likelier. With stone all round it, never.</li>
 * <li><b>The fire watch.</b> On a dry night (three days without rain, a drought, or the three nights after a
 *     fire) one of the watch walks the round of the town's forges and the watchtower, a light in its hand. A
 *     spark near it is seen and stamped out before it catches.</li>
 * <li><b>What a fire teaches.</b> After a fire the town takes care: when it was a forge's spark, stone round
 *     the forges (every burnable block of a town building's drawing beside a furnace replaced with stone out of
 *     the stores, the timber taken out going back into them); after any fire, a cauldron of water by each
 *     workshop (the smithy, the smeltery, the workshop, the bakery, the café, the tavern, the brewery: a
 *     cauldron out of the stores or seven of their iron, never their last sixteen, filled from a bucket of
 *     theirs or at the water near), which a hand at the next fire fills a bucket at; and, a town of the Iron
 *     Age with two fires behind it, a fire station: a small stone house with the town's bell for fire, if it
 *     has one, and a rack (a chest) of four buckets out of the stores or made of their iron (its last sixteen
 *     kept), which the brigade and the chain take first and hang back after. Each is the town's works, by
 *     hand (TownJobs), out of the stores.</li>
 * </ul>
 */
public final class FireSafety {

    private FireSafety() {}

    /** The fire station's drawing. */
    public static final String STATION = "firestation";
    /** The buckets the fire station keeps on its rack. */
    static final int RACK = 4;
    /** The iron the town's care against fire never takes from the stores (a fire's own buckets may). */
    static final int IRON_KEPT = 16;
    /** How often the forges are looked at for a spark, and the works seen to (ticks). */
    static final long SPARK_LOOK = 1200L, WORKS = 200L;
    /** A spark is remembered this long, so the fire it starts is put down to it. */
    static final long SPARK_LATELY = 1200L;
    /** The workshops that keep a cauldron of water by them once the town has had a fire. */
    static final List<String> WORKSHOPS = List.of("smithy", "smeltery", "workshop", "bakery", "cafe", "tavern", "brewery");

    /** A spark lately: where, when, and from what. */
    record Spark(BlockPos at, long when, String from) {}

    private static final Map<UUID, Spark> SPARKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>(), WORKED = new ConcurrentHashMap<>();
    /** Tonight's fire watch, by town; and its round. */
    private static final Map<UUID, UUID> WATCHER = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> ROUND = new ConcurrentHashMap<>();
    /** The forges of each building, by its anchor (read off its drawing once). */
    private static final Map<Long, List<BlockPos>> FORGES = new ConcurrentHashMap<>();
    /** Tests: the next spark's roll always comes up (true), or as the dice fall (null). */
    private static volatile Boolean sparkForTests;

    static void resetForTests() {
        SPARKS.clear();
        LOOKED.clear();
        WORKED.clear();
        WATCHER.clear();
        ROUND.clear();
        FORGES.clear();
        sparkForTests = null;
    }

    // ------------------------------------------------------------------ the town's look

    /** Every second: the forges looked at for a spark (once a minute), the fire watch kept, the works seen to. */
    static void tick(ServerLevel level, Villages.Village v, Disasters.Town t) {
        long now = level.getGameTime();
        UUID id = v.id();
        if (Disasters.on() && now - LOOKED.getOrDefault(id, -100000L) >= SPARK_LOOK) {
            LOOKED.put(id, now);
            spark(level, v, t, false);
        }
        keepTheWatch(level, v);
        if (now - WORKED.getOrDefault(id, -100000L) >= WORKS) {
            WORKED.put(id, now);
            if (t.stoneForges) stoneRound(level, v, t, 4);
            if (t.cauldrons) cauldrons(level, v, t);
            if (t.station) stockStation(level, v, t);
        }
    }

    // ------------------------------------------------------------------ the spark

    /** The furnaces and smokers of a building's drawing. */
    static List<BlockPos> forgesOf(Ledger.Building b) {
        return FORGES.computeIfAbsent(b.anchor().asLong(), k -> {
            List<BlockPos> out = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                if (p.part() == BuildGoal.Part.FURNACE || p.part() == BuildGoal.Part.SMOKER) out.add(p.pos());
            }
            return List.copyOf(out);
        });
    }

    static boolean lit(BlockState st) {
        return st.getBlock() instanceof AbstractFurnaceBlock && st.getValue(AbstractFurnaceBlock.LIT);
    }

    /** What burns right beside a forge (its own block aside). */
    static List<BlockPos> burnables(ServerLevel level, BlockPos forge) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(forge.offset(-1, -1, -1), forge.offset(1, 1, 1))) {
            if (q.equals(forge)) continue;
            BlockState st = level.getBlockState(q);
            if (!st.isAir() && st.isFlammable(level, q, Direction.UP)) out.add(q.immutable());
        }
        return out;
    }

    /** Where a spark from this forge would catch: an empty cell beside something that burns, near the forge; or null. */
    @Nullable
    static BlockPos sparkSpot(ServerLevel level, BlockPos forge, List<BlockPos> burnables) {
        List<BlockPos> shuffled = new ArrayList<>(burnables);
        Collections.shuffle(shuffled, new java.util.Random(level.getRandom().nextLong()));
        for (BlockPos b : shuffled) {
            for (Direction d : Direction.values()) {
                BlockPos a = b.relative(d);
                if (Math.max(Math.abs(a.getX() - forge.getX()), Math.max(Math.abs(a.getY() - forge.getY()), Math.abs(a.getZ() - forge.getZ()))) > 2) continue;
                if (!level.getBlockState(a).isAir() || !BaseFireBlock.canBePlacedAt(level, a, Direction.UP)) continue;
                return a.immutable();
            }
        }
        return null;
    }

    /**
     * A spark, perhaps: one of the town's lit forges with something that burns beside it, and the dice. With
     * {@code force}, the dice always come up (the tests, /village disasters fire). Returns where the fire caught,
     * or null (no spark, a careful town, or the fire watch stamped it out).
     */
    @Nullable
    static BlockPos spark(ServerLevel level, Villages.Village v, Disasters.Town t, boolean force) {
        UUID id = v.id();
        List<Object[]> lit = new ArrayList<>();                         // {forge, building, burnables}
        for (Ledger.Building b : Ledger.buildings(id)) {
            for (BlockPos f : forgesOf(b)) {
                if (!level.isLoaded(f) || !lit(level.getBlockState(f))) continue;
                List<BlockPos> burn = burnables(level, f);
                if (!burn.isEmpty()) lit.add(new Object[]{ f, b, burn });
            }
        }
        if (lit.isEmpty()) return null;
        Object[] pick = lit.get(level.getRandom().nextInt(lit.size()));
        BlockPos forge = (BlockPos) pick[0];
        Ledger.Building b = (Ledger.Building) pick[1];
        @SuppressWarnings("unchecked") List<BlockPos> burn = (List<BlockPos>) pick[2];
        if (!force && !Boolean.TRUE.equals(sparkForTests)) {
            double weather = t.drought ? 3.0 : t.dryDays >= 3 ? 2.0 : Disasters.raining(level, v) ? 0.5 : 1.0;
            double much = Math.min(2.0, Math.max(0.5, burn.size() / 3.0));
            double water = cauldronNear(level, forge) ? 0.5 : 1.0;
            double p = weather * much * water / (AssistantConfig.villageSparkDays() * (24000.0 / SPARK_LOOK));
            if (level.getRandom().nextDouble() >= p) return null;
        }
        BlockPos at = sparkSpot(level, forge, burn);
        if (at == null) return null;
        String from = FireBrigade.named(b.structure()) + "'s " + (level.getBlockState(forge).is(Blocks.SMOKER) ? "oven" : "forge");
        long day = level.getDayTime() / 24000L;
        // The fire watch, near enough to see it, stamps it out before it catches.
        VillageFolkEntity watch = watcher(level, id);
        if (watch != null && watch.blockPosition().distSqr(forge) <= 16 * 16) {
            t.stamped++;
            watch.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            FolkTalk.speak(watch, FolkTalk.pick(level.getRandom(), "A spark! Got it.", "Whoa — not tonight, you don't.", "That was close. Stamped it out."));
            Disasters.record(id, day, "a spark from " + from + " was stamped out by " + watch.displayNameCap() + " on the fire watch");
            return null;
        }
        level.setBlockAndUpdate(at, BaseFireBlock.getState(level, at));
        level.playSound(null, at, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6F, 1.4F);
        SPARKS.put(id, new Spark(at, level.getGameTime(), from));
        t.sparks++;
        Disasters.dirty();
        return at;
    }

    /** A spark lately near here, and what it came from ("a spark from the smithy's forge"); else null. */
    @Nullable
    static String sparkCause(UUID village, BlockPos p, long now) {
        Spark s = SPARKS.get(village);
        if (s == null || now - s.when() > SPARK_LATELY || now < s.when() || s.at().distManhattan(p) > 8) return null;
        return "a spark from " + s.from();
    }

    /** A cauldron near a forge: the town is careful there. */
    static boolean cauldronNear(ServerLevel level, BlockPos forge) {
        for (BlockPos q : BlockPos.betweenClosed(forge.offset(-6, -2, -6), forge.offset(6, 2, 6))) {
            BlockState st = level.getBlockState(q);
            if (st.is(Blocks.WATER_CAULDRON) || st.is(Blocks.CAULDRON) || FieldTools.isBarrel(st)) return true;   // [fields] or a rain barrel
        }
        return false;
    }

    // ------------------------------------------------------------------ the fire watch

    /** Is tonight a fire watch's night in this town: dark, dry, and no alarm? */
    static boolean watchNight(ServerLevel level, UUID village) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        return t >= 13000L && t < 23000L && Disasters.dryWeather(village, level.getDayTime() / 24000L) && !Raids.underAlarm(village);
    }

    /** Tonight's fire watch kept: one of the watch named to it, as long as the night is dry. */
    static void keepTheWatch(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!Disasters.on() || !watchNight(level, id)) {
            UUID was = WATCHER.remove(id);
            if (was != null) ROUND.remove(was);
            return;
        }
        if (watcher(level, id) != null) return;
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.GUARD || !FireBrigade.fit(f) || FireBrigade.onIt(f)) continue;
            if (best == null || f.displayNameCap().compareTo(best.displayNameCap()) < 0) best = f;
        }
        if (best == null) return;
        WATCHER.put(id, best.getUUID());
        best.brain("on the fire watch tonight: the night is dry");
        if (best.getRandom().nextInt(2) == 0) FolkTalk.speak(best, FolkTalk.pick(best.getRandom(), "Dry as tinder tonight. I'll walk the forges.",
            "Fire watch for me tonight."));
    }

    @Nullable
    static VillageFolkEntity watcher(ServerLevel level, UUID village) {
        UUID u = WATCHER.get(village);
        if (u == null) return null;
        if (level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive() && village.equals(f.ownerId())) return f;
        WATCHER.remove(village);
        return null;
    }

    /** Tonight's fire watch, by name, or null. */
    @Nullable
    static String watcherName(UUID village) {
        UUID u = WATCHER.get(village);
        if (u == null) return null;
        VillageFolkEntity f = Homes.loaded(village, u);
        return f == null ? null : f.displayNameCap();
    }

    static boolean onRound(VillageFolkEntity f) {
        return ROUND.containsKey(f.getUUID());
    }

    /**
     * From the folk's tick (Disasters.hold): the fire watch's round of the town's forges and the watchtower,
     * stopping a while at each. True while it is on it.
     */
    static boolean round(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || !f.getUUID().equals(WATCHER.get(id)) || !watchNight(level, id) || f.getTarget() != null
                || FireBrigade.onIt(f) || f.isSleeping()) {
            ROUND.remove(f.getUUID());
            return false;
        }
        List<BlockPos> stops = stops(id);
        if (stops.isEmpty()) return false;
        int[] r = ROUND.computeIfAbsent(f.getUUID(), k -> new int[]{ 0, -1000, -1000 });   // {stop, arrived tick, walk tick}
        BlockPos to = stops.get(Math.floorMod(r[0], stops.size()));
        double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
        if (dx * dx + dz * dz > 9.0) {
            if (f.getNavigation().isDone() || f.tickCount - r[2] > 60) {
                f.walkTo(to, 0.8D);
                r[2] = f.tickCount;
            }
            r[1] = -1000;
            f.hobbyNow = "on the fire watch: walking the round of the forges";
            return true;
        }
        if (r[1] < 0) r[1] = f.tickCount;
        f.getNavigation().stop();
        f.hobbyNow = "on the fire watch: looking the forge over";
        if (f.tickCount - r[1] > 160) {
            r[0]++;
            r[1] = -1000;
        }
        return true;
    }

    /** The fire watch's stops: before each workshop with a forge, and the watchtower. */
    static List<BlockPos> stops(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!WORKSHOPS.contains(b.structure()) && !b.structure().equals("watchtower")) continue;
            out.add(front(b, 2));
        }
        return out;
    }

    /** A spot before a building's front, this far out past its footprint. */
    static BlockPos front(Ledger.Building b, int out) {
        int[] half = BuildGoal.footprint(b.structure());
        boolean turned = b.facing().getAxis() == Direction.Axis.X;
        int depth = turned ? half[0] : half[1];
        return b.anchor().relative(b.facing().getOpposite(), depth + out);
    }

    // ------------------------------------------------------------------ what a fire teaches

    /** After a fire (FireBrigade.close): the fire watch the next three nights, and the town's new care. */
    static void afterFire(ServerLevel level, Villages.Village v, String cause, String where, long day) {
        Disasters.Town t = Disasters.town(v.id());
        t.lastFireDay = day;
        t.watchUntil = day + 3;
        String at = where.replaceFirst("^(at|on|in) ", "");
        if (cause.startsWith("a spark") && !t.stoneForges) {
            t.stoneForges = true;
            Disasters.record(v.id(), day, "after the fire at " + at + ", the town resolved to lay stone round its forges");
        }
        if (!t.cauldrons) {
            t.cauldrons = true;
            Disasters.record(v.id(), day, "after the fire, the town resolved to keep a cauldron of water by each workshop");
        }
        if (!t.station && wantsAStation(v.id())) {
            t.station = true;
            Disasters.record(v.id(), day, "with " + Annals.fires(v.id()) + " fires behind it, the town wants a fire station");
        }
        Disasters.dirty();
    }

    /** An Iron Age town with two fires behind it (or one that burnt a building) wants a fire station. */
    static boolean wantsAStation(UUID village) {
        Disasters.Town t = Disasters.known(village);
        return Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal()
            && (Annals.fires(village) >= 2 || t != null && t.burnt >= 12);
    }

    /** On the town's list of buildings (Villages.projectsWantedInOrder): the fire station, once it wants one. */
    public static boolean wanted(UUID village) {
        Disasters.Town t = Disasters.known(village);
        return t != null && t.station && Villages.builtStructure(village, STATION) == null;
    }

    /** Why the fire station (Villages.whyBuild). */
    public static String why(UUID village) {
        return "a fire station: the town's fire buckets on a rack and its fire bell, after " + Annals.fires(village) + " fires";
    }

    /** Stone laid round the forges: every burnable block of a building's drawing beside a furnace, a few at a time. */
    static int stoneRound(ServerLevel level, Villages.Village v, Disasters.Town t, int most) {
        int n = 0;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            List<BlockPos> forges = forgesOf(b);
            if (forges.isEmpty() || !level.isLoaded(b.anchor())) continue;
            List<BlockPos> near = new ArrayList<>();
            for (BlockPos f : forges) near.addAll(burnables(level, f));
            if (near.isEmpty()) continue;                                   // careful already
            // Only the building's own fabric (its walls, floors and beams), never its furniture.
            Set<Long> fabric = new HashSet<>();
            for (BuildGoal.Placement p : BuildGoal.plan(Rebuilding.drawing(v.id(), b), b.anchor(), b.facing(), 13)) {
                if (p.part() == BuildGoal.Part.BLOCK) fabric.add(p.pos().asLong());
            }
            {
                for (BlockPos q : near) {
                    if (n >= most) return n;
                    BlockState st = level.getBlockState(q);
                    if (!fabric.contains(q.asLong()) || !st.isCollisionShapeFullBlock(level, q) || !st.isFlammable(level, q, Direction.UP)) continue;
                    if (!TownJobs.atWork(level, v, "firesafety", q, "laying stone round the forge at " + FireBrigade.named(b.structure()))) return n;
                    Block stone = Crafts.masonry(level, v);
                    if (stone == null) return n;
                    level.setBlock(q, stone.defaultBlockState(), 3);
                    Crafts.giveBack(level, v, st.getBlock().asItem(), 1);
                    level.playSound(null, q, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                    t.stoneLaid++;
                    n++;
                }
            }
        }
        if (n > 0) Disasters.dirty();
        return n;
    }

    /** A cauldron of water set by each workshop that has none, out of the stores, by hand. Returns how many were set. */
    static int cauldrons(ServerLevel level, Villages.Village v, Disasters.Town t) {
        int n = 0;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!WORKSHOPS.contains(b.structure()) && !b.structure().equals(STATION) || !level.isLoaded(b.anchor())) continue;
            boolean has = false;
            for (long k : t.cauldronsAt) {
                BlockPos p = BlockPos.of(k);
                if (p.distSqr(b.anchor()) <= 10 * 10 && (level.getBlockState(p).is(Blocks.CAULDRON) || level.getBlockState(p).is(Blocks.WATER_CAULDRON)
                        || FieldTools.isBarrel(level.getBlockState(p)))) has = true;              // [fields] or the rain barrel set instead
            }
            if (has) continue;
            BlockPos spot = cauldronSpot(level, b);
            if (spot == null) continue;
            // A cauldron put by, or seven iron, and never the town's last sixteen (its tools and its age want them).
            boolean pot = Market.stock(level, v.id(), s -> s.is(Items.CAULDRON)) > 0 || Market.stock(level, v.id(), s -> s.is(Items.IRON_INGOT)) >= 7 + IRON_KEPT;
            // [fields] No iron to spare for a cauldron: a rain barrel out of the stores by it instead (FieldTools).
            if (!pot && FieldTools.barrelInstead(level, v, spot, FireBrigade.named(b.structure()))) {
                t.cauldronsAt.add(spot.asLong());
                n++;
                Disasters.record(v.id(), level.getDayTime() / 24000L, "a rain barrel was set by " + FireBrigade.named(b.structure())
                    + " against fire, there being no iron for a cauldron");
                continue;
            }
            if (!pot) return n;
            if (!TownJobs.atWork(level, v, "firesafety", spot, "setting a cauldron of water by " + FireBrigade.named(b.structure()))) return n;
            if (!Crafts.take(level, v, s -> s.is(Items.CAULDRON), 1)
                    && !(Market.stock(level, v.id(), s -> s.is(Items.IRON_INGOT)) >= 7 + IRON_KEPT && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 7))) return n;
            // Its water: a bucket of the stores' (the bucket back empty), else filled at the water near, else the rain's.
            BlockState put = Blocks.CAULDRON.defaultBlockState();
            if (Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1)) {
                Crafts.store(level, v, new ItemStack(Items.BUCKET));
                put = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, LayeredCauldronBlock.MAX_FILL_LEVEL);
            } else if (Market.stock(level, v.id(), s -> s.is(Items.BUCKET)) > 0) {
                BlockPos w = Droughts.water(level, spot, 24);
                if (w != null && Droughts.fill(level, w)) {
                    put = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, LayeredCauldronBlock.MAX_FILL_LEVEL);
                }
            }
            level.setBlockAndUpdate(spot, put);
            level.playSound(null, spot, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            t.cauldronsAt.add(spot.asLong());
            n++;
            Disasters.record(v.id(), level.getDayTime() / 24000L, "a cauldron " + (put.is(Blocks.WATER_CAULDRON) ? "of water " : "")
                + "was set by " + FireBrigade.named(b.structure()) + " against fire");
        }
        if (n > 0) Disasters.dirty();
        return n;
    }

    /** Where a workshop's cauldron goes: on the ground before its front, beside the way in. */
    @Nullable
    static BlockPos cauldronSpot(ServerLevel level, Ledger.Building b) {
        BlockPos door = front(b, 1);
        Direction side = b.facing().getClockWise();
        for (int along : new int[]{ 2, -2, 3, -3, 1, -1 }) {
            BlockPos col = door.relative(side, along);
            BlockPos s = Land.surface(level, col.getX(), col.getZ());
            if (s == null || Math.abs(s.getY() - b.anchor().getY()) > 2) continue;
            if (!level.getBlockState(s).isAir() || !level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)) continue;
            if (!level.getFluidState(s).isEmpty()) continue;
            return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ the fire station

    /** The fire station's rack: the chests of its drawing (its own, not the stores'). */
    static List<BlockPos> racks(ServerLevel level, UUID village) {
        Ledger.Building b = Villages.builtStructure(village, STATION);
        List<BlockPos> out = new ArrayList<>();
        if (b == null || !level.isLoaded(b.anchor())) return out;
        for (BuildGoal.Placement p : BuildGoal.plan(STATION, b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CHEST && level.getBlockEntity(p.pos()) instanceof Container) out.add(p.pos());
        }
        return out;
    }

    /** The fire station's bell, if it has one hung. */
    @Nullable
    static BlockPos stationBell(ServerLevel level, Villages.Village v) {
        Ledger.Building b = Villages.builtStructure(v.id(), STATION);
        if (b == null || !level.isLoaded(b.anchor())) return null;
        for (BuildGoal.Placement p : BuildGoal.plan(STATION, b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.BELL && level.getBlockState(p.pos()).is(Blocks.BELL)) return p.pos();
        }
        return null;
    }

    /** The rack kept at four buckets: the stores' buckets, or buckets made of their iron (three each, a dozen kept). */
    static void stockStation(ServerLevel level, Villages.Village v, Disasters.Town t) {
        List<BlockPos> racks = racks(level, v.id());
        if (racks.isEmpty()) return;
        int have = 0;
        for (BlockPos r : racks) {
            if (level.getBlockEntity(r) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) if (bucket(c.getItem(i))) have += c.getItem(i).getCount();
            }
        }
        if (have >= RACK) return;
        if (!TownJobs.atWork(level, v, "firesafety", racks.get(0), "hanging buckets on the fire station's rack")) return;
        boolean any = false;
        while (have < RACK) {
            boolean got = Crafts.take(level, v, s -> s.is(Items.BUCKET), 1)
                || Market.stock(level, v.id(), s -> s.is(Items.IRON_INGOT)) >= 3 + IRON_KEPT && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 3);
            if (!got) break;
            if (!putIn(level, racks, new ItemStack(Items.BUCKET))) {
                Crafts.store(level, v, new ItemStack(Items.BUCKET));
                break;
            }
            have++;
            any = true;
        }
        if (any && have >= RACK) Disasters.record(v.id(), level.getDayTime() / 24000L, "the fire station's rack was hung with " + RACK + " buckets");
    }

    static boolean bucket(ItemStack s) {
        return s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET);
    }

    private static boolean putIn(ServerLevel level, List<BlockPos> racks, ItemStack s) {
        for (BlockPos r : racks) {
            if (!(level.getBlockEntity(r) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) {
                    c.setItem(i, s);
                    c.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    /** A bucket off the fire station's rack (a full one first), or nothing. */
    static ItemStack takeBucket(ServerLevel level, Villages.Village v) {
        for (boolean full : new boolean[]{ true, false }) {
            for (BlockPos r : racks(level, v.id())) {
                if (!(level.getBlockEntity(r) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (full ? !s.is(Items.WATER_BUCKET) : !s.is(Items.BUCKET)) continue;
                    ItemStack one = s.split(1);
                    c.setChanged();
                    return one;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** A bucket back after a fire: on the fire station's rack if there is room, else into the stores. */
    static void putBack(ServerLevel level, Villages.Village v, ItemStack s) {
        if (s.isEmpty()) return;
        if (putIn(level, racks(level, v.id()), s)) return;
        Crafts.store(level, v, s);
        Villages.forgetStock();
    }

    // ------------------------------------------------------------------ the card

    @Nullable
    static String cardPart(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id != null && f.getUUID().equals(WATCHER.get(id))) return "on the fire watch tonight, walking the forges";
        return null;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a spark now from one of the town's lit forges with something burnable beside it. Returns where it caught, or null. */
    @Nullable
    public static BlockPos sparkForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? null : spark(level, v, Disasters.town(village), true);
    }

    /** Tests: the dice for a spark always come up (true) or fall as they will (null). */
    public static void alwaysSparkForTests(@Nullable Boolean on) {
        sparkForTests = on;
    }

    /** Tests: the town's care after its fires: {stone round the forges (rule), stone laid, cauldrons (rule), cauldrons set, station wanted}. */
    public static int[] careForTests(UUID village) {
        Disasters.Town t = Disasters.known(village);
        if (t == null) return new int[5];
        return new int[]{ t.stoneForges ? 1 : 0, t.stoneLaid, t.cauldrons ? 1 : 0, t.cauldronsAt.size(), t.station ? 1 : 0 };
    }

    /** Tests: the town's works against fire done now (stone round the forges, cauldrons, the station's rack). */
    public static void worksForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Disasters.Town t = Disasters.known(village);
        if (v == null || t == null) return;
        if (t.stoneForges) for (int i = 0; i < 20 && stoneRound(level, v, t, 8) > 0; i++) { }
        if (t.cauldrons) cauldrons(level, v, t);
        if (t.station) stockStation(level, v, t);
    }

    /** Tests: is the fire spot in the forge's reach a burnable's neighbour (the spark's rule)? The burnables by a forge. */
    public static int burnablesForTests(ServerLevel level, BlockPos forge) {
        return burnables(level, forge).size();
    }
}
