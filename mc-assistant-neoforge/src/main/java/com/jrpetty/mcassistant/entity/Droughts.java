package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [disasters] The drought.
 *
 * <p>Six summer days running without rain on the town (villageDroughtDays; a late spring's too, a day later)
 * and it is a drought, until the rain comes or the summer ends. What it does:
 * <ul>
 * <li><b>The dry fields wither.</b> A crop of the town's (on a farmer's plot or the town's farmland) on dry
 *     farmland (no water within four blocks, the game's own measure: its moisture gone) is refused three
 *     growth ticks in four, the game's and the farmer's care alike; a watered field grows as ever. Wild crops
 *     and a player's farm are never touched.</li>
 * <li><b>The farmers carry water.</b> A farmer whose field is dry fills a bucket (its own, or one of the
 *     stores', given back when the drought breaks) at the nearest water and walks it out to the driest of its
 *     farmland, wetting the three-by-three it pours on: the game dries it again in time, so it goes again
 *     every minute or so.</li>
 * <li><b>Short rations.</b> If the leader's books say the larder is running down (short, or famine), the
 *     town goes on short rations till the drought breaks: a folk takes fewer rations out of the stores at a
 *     time (VillageFolkEntity.rationsWanted), so what is put by goes round. The prices of food rise with the
 *     stores' falling stock (PriceIndex), as they always do.</li>
 * <li><b>Irrigation.</b> The town answers by digging irrigation channels through every field that was dry
 *     (a straight run of water every eight rows, as the farmers cut them from the Stone Age: Waterfront), by
 *     hand on the town's works, the soil dug out going into the stores and every block of water carried in a
 *     bucket of the stores' from the river or the well. A field so watered is wet in the next drought, and
 *     grows on. Not where the land is not to be reshaped (villageReshapeLand off).</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Droughts {

    private Droughts() {}

    /** Of a dry field's growth ticks in a drought, this share is refused. */
    static final double STUNT = 0.75;
    /** A farmer carries water to its field at most this often (ticks). */
    static final long CARRY_EVERY = 1200L;
    /** How far from a field the water to fill a bucket at may be. */
    static final int WATER_REACH = 32;

    /** The towns in a drought now, and their fields (cx, cz, radius): the crops' growth looked up cheaply. */
    private static final Map<UUID, List<int[]>> DRY = new ConcurrentHashMap<>();
    /** Fields a test has set out, by town (cx, cz, radius). */
    private static final Map<UUID, List<int[]>> TEST_FIELDS = new ConcurrentHashMap<>();

    /** A farmer carrying water: what it is about, and where. */
    static final class Carry {
        int stage;                                  // 0 to the water, 1 to the field
        final BlockPos water, field;
        double best = Double.MAX_VALUE;
        long progress;
        int walkTick = -1000;

        Carry(BlockPos water, BlockPos field, long now) {
            this.water = water.immutable();
            this.field = field.immutable();
            this.progress = now;
        }
    }

    private static final Map<UUID, Carry> CARRY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CARRIED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> CARRIES = new ConcurrentHashMap<>();
    /** Farmers given a bucket out of the stores for the drought: it goes back when it breaks. */
    private static final Set<UUID> LENT = ConcurrentHashMap.newKeySet();

    static void resetForTests() {
        DRY.clear();
        TEST_FIELDS.clear();
        CARRY.clear();
        CARRIED.clear();
        CARRIES.clear();
        LENT.clear();
        DUG.clear();
        WATER_AT.clear();
    }

    // ------------------------------------------------------------------ the drought's coming and going

    /** Is it the warm part of the town's year: summer, or the last days of spring? */
    static boolean warm(UUID village, long day) {
        Seasons.Season s = Seasons.season(village, day);
        return s == Seasons.Season.SUMMER || s == Seasons.Season.SPRING && Seasons.dayInSeason(village, day) >= 5;
    }

    /** Each morning (Disasters.closeTheDay): a drought begun, or broken, and the town's rations. */
    static void daily(ServerLevel level, Villages.Village v, Disasters.Town t, long day) {
        UUID id = v.id();
        if (!t.drought) {
            int need = AssistantConfig.villageDroughtDays() + (Seasons.season(id, day) == Seasons.Season.SPRING ? 1 : 0);
            if (Disasters.on() && warm(id, day) && t.dryDays >= need) begin(level, v, t, day);
            return;
        }
        if (!warm(id, day)) {
            end(level, v, t, day, "as the summer turned, and the nights cooled");
            return;
        }
        dryFields(level, v, t);
        Leader.Plan plan = Leader.plan(id);
        if (!t.rationing && (plan == Leader.Plan.SHORT || plan == Leader.Plan.FAMINE)) {
            t.rationing = true;
            Disasters.record(id, day, "with the fields withering in the drought, the elder put the town on short rations");
        }
    }

    /** The drought begins: written down, and its dry fields marked for the farmers' water and the town's channels. */
    static void begin(ServerLevel level, Villages.Village v, Disasters.Town t, long day) {
        t.drought = true;
        t.droughtFrom = day;
        t.droughts++;
        dryFields(level, v, t);
        Disasters.record(v.id(), day, "a drought: " + t.dryDays + " days without rain, and " + (t.dryFields.isEmpty()
            ? "the fields with no water near them drying" : t.dryFields.size() + (t.dryFields.size() == 1 ? " field" : " fields")
            + " with no water near withering"));
        Disasters.dirty();
    }

    /** The drought breaks: written down, the rations lifted, the stores' buckets given back. */
    static void end(ServerLevel level, Villages.Village v, Disasters.Town t, long day, String how) {
        if (!t.drought) return;
        t.drought = false;
        t.rationing = false;
        DRY.remove(v.id());
        long days = Math.max(1, day - t.droughtFrom);
        Disasters.record(v.id(), day, "the drought broke " + how + ", after " + days + (days == 1 ? " day" : " days"));
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !LENT.remove(f.getUUID())) continue;
            if (f.removeMatching(s -> s.is(Items.BUCKET), 1) == 1 || f.removeMatching(s -> s.is(Items.WATER_BUCKET), 1) == 1) {
                Crafts.store(level, v, new ItemStack(Items.BUCKET));
            }
            CARRY.remove(f.getUUID());
        }
        Disasters.dirty();
    }

    /** The town's fields with no water near them now: (centre, radius) each, into the drought's list. */
    static void dryFields(ServerLevel level, Villages.Village v, Disasters.Town t) {
        for (int[] f : fields(v.id())) {
            BlockPos c = new BlockPos(f[0], v.centre().getY(), f[1]);
            int r = f[2];
            if (!Land.areaLoaded(level, c, r)) continue;
            int y = Waterfront.fieldLevel(level, new BlockPos(f[0], f[3], f[1]), r);
            if (y == Integer.MIN_VALUE || Waterfront.wet(level, new BlockPos(f[0], y, f[1]), r, y)) continue;
            long key = new BlockPos(f[0], y, f[1]).asLong();
            boolean have = false;
            for (long[] d : t.dryFields) if (d[0] == key) have = true;
            if (!have) t.dryFields.add(new long[]{ key, r });
        }
    }

    /** The town's fields: every farmer's plot, and any a test has set out: {cx, cz, radius, y}. */
    static List<int[]> fields(UUID village) {
        List<int[]> out = new ArrayList<>(TEST_FIELDS.getOrDefault(village, List.of()));
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() != StationTask.FARM || a.workZone() == null) continue;
            BlockPos c = a.workZone().center();
            out.add(new int[]{ c.getX(), c.getZ(), a.workZone().radius(), c.getY() });
        }
        return out;
    }

    /** Every second: the drought's fields kept for the crops' look, broken by rain, and the channels dug. */
    static void tick(ServerLevel level, Villages.Village v, Disasters.Town t) {
        if (t.drought) {
            if (!Disasters.on()) {
                end(level, v, t, level.getDayTime() / 24000L, "when the town was spared it");
            } else if (t.dryDays == 0 && t.wetSamples > 0) {
                end(level, v, t, level.getDayTime() / 24000L, "with rain at last");
            } else {
                DRY.put(v.id(), fields(v.id()));
            }
        } else {
            DRY.remove(v.id());
        }
        if (!t.dryFields.isEmpty() && level.getGameTime() % 60 < 20) irrigate(level, v, t, 4);
    }

    /** Is this town on short rations in a drought (VillageFolkEntity.rationsWanted)? */
    public static boolean rationing(@Nullable UUID village) {
        if (village == null) return false;
        Disasters.Town t = Disasters.known(village);
        return t != null && t.drought && t.rationing;
    }

    /** Is there a drought on this town now? */
    public static boolean on(@Nullable UUID village) {
        if (village == null) return false;
        Disasters.Town t = Disasters.known(village);
        return t != null && t.drought;
    }

    // ------------------------------------------------------------------ the crops

    /** A crop's growth tick: refused three times in four on a dry field of a town in a drought. */
    @SubscribeEvent
    public static void onCropGrow(CropGrowEvent.Pre e) {
        if (DRY.isEmpty() || !(e.getLevel() instanceof ServerLevel level)) return;
        if (!stunted(level, e.getPos())) return;
        if (level.getRandom().nextDouble() < STUNT) e.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
    }

    /** Is this crop withering: on dry farmland, on one of the fields of a town in a drought? */
    static boolean stunted(ServerLevel level, BlockPos crop) {
        BlockState below = level.getBlockState(crop.below());
        if (!(below.getBlock() instanceof FarmBlock) || below.getValue(FarmBlock.MOISTURE) > 0) return false;
        for (Map.Entry<UUID, List<int[]>> e : DRY.entrySet()) {
            Villages.Village v = Villages.get(e.getKey());
            if (v == null || !v.dim().equals(level.dimension())) continue;
            for (int[] f : e.getValue()) {
                if (Math.abs(crop.getX() - f[0]) <= f[2] && Math.abs(crop.getZ() - f[1]) <= f[2]) return true;
            }
            if (Villages.onFarmland(v.id(), v.centre(), crop, 0)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the farmers' water

    static boolean carrying(VillageFolkEntity f) {
        return CARRY.containsKey(f.getUUID());
    }

    /** May this farmer go for water now: a drought, by day, at its work, its own field dry, not lately? */
    private static boolean mayCarry(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.stationTask() != StationTask.FARM || f.workZone() == null || f.isBaby() || f.isSleeping()) return false;
        Disasters.Town t = Disasters.known(id);
        if (t == null || !t.drought || f.offWorkNow() || Weather.stormy(level) || f.getTarget() != null) return false;
        long dt = level.getDayTime() % 24000L;
        if (dt < 1000L || dt > 11500L) return false;
        Long last = CARRIED.get(f.getUUID());
        long now = level.getGameTime();
        if (last != null && now - last < CARRY_EVERY && now >= last) return false;
        BlockPos c = f.workZone().center();
        for (long[] d : t.dryFields) {
            BlockPos p = BlockPos.of(d[0]);
            if (Math.abs(p.getX() - c.getX()) <= 2 && Math.abs(p.getZ() - c.getZ()) <= 2) return true;
        }
        return false;
    }

    /**
     * From the folk's tick (Disasters.hold): a farmer with a dry field in a drought, to the water with a bucket,
     * and back to pour it on the driest of its farmland. True while it is about it.
     */
    static boolean carry(VillageFolkEntity f, ServerLevel level) {
        Carry c = CARRY.get(f.getUUID());
        long now = level.getGameTime();
        if (c == null) {
            if (f.tickCount % 40 != 9 || !mayCarry(f, level)) return false;
            CARRIED.put(f.getUUID(), now);
            Villages.Village v = Villages.get(f.ownerId());
            if (v == null) return false;
            BlockPos field = driest(level, f.workZone());
            BlockPos water = water(level, f.workZone().center(), WATER_REACH);
            if (field == null || water == null) return false;
            // A bucket: its own, else one of the stores' (lent till the drought breaks).
            if (f.countCarried(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) == 0) {
                if (!Crafts.take(level, v, s -> s.is(Items.BUCKET), 1)) return false;
                ItemStack left = f.insertItem(new ItemStack(Items.BUCKET));
                if (!left.isEmpty()) {
                    Crafts.store(level, v, left);
                    return false;
                }
                LENT.add(f.getUUID());
            }
            c = new Carry(water, field, now);
            c.stage = f.countCarried(s -> s.is(Items.WATER_BUCKET)) > 0 ? 1 : 0;
            CARRY.put(f.getUUID(), c);
            f.clearQueue();
            f.getNavigation().stop();
            f.brain("the field is parched: fetching water");
            if (f.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Not a drop of rain. I'll carry it myself, then.",
                    "These poor crops. Water, water.", "Another bucket for the wheat."));
            }
        }
        if (!mayStill(f, level)) {
            CARRY.remove(f.getUUID());
            return false;
        }
        BlockPos to = c.stage == 0 ? c.water : c.field;
        double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 2.5 || Math.abs(f.getY() - to.getY()) > 3.0) {
            if (d < c.best - 0.5) {
                c.best = d;
                c.progress = now;
            } else if (now - c.progress > 400 || now < c.progress) {
                CARRY.remove(f.getUUID());                       // no way there: back to its work
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - c.walkTick > 30) {
                f.walkTo(to, 1.0D);
                c.walkTick = f.tickCount;
            }
            f.hobbyNow = c.stage == 0 ? "fetching water for its parched field" : "carrying water out to its field";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        f.swing(InteractionHand.MAIN_HAND);
        if (c.stage == 0) {
            if (!fill(level, c.water) || f.removeMatching(s -> s.is(Items.BUCKET), 1) != 1) {
                CARRY.remove(f.getUUID());
                return false;
            }
            ItemStack left = f.insertItem(new ItemStack(Items.WATER_BUCKET));
            if (!left.isEmpty()) f.spawnAtLocation(left);
            level.playSound(null, c.water, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
            c.stage = 1;
            c.best = Double.MAX_VALUE;
            c.progress = now;
            return true;
        }
        // Poured on the driest of its farmland: the three-by-three round it wet through.
        if (f.removeMatching(s -> s.is(Items.WATER_BUCKET), 1) == 1) {
            ItemStack left = f.insertItem(new ItemStack(Items.BUCKET));
            if (!left.isEmpty()) f.spawnAtLocation(left);
            int wet = water(level, c.field);
            level.playSound(null, c.field, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
            level.sendParticles(ParticleTypes.SPLASH, c.field.getX() + 0.5, c.field.getY() + 1.1, c.field.getZ() + 0.5, 24, 1.0, 0.1, 1.0, 0.2);
            CARRIES.merge(f.getUUID(), 1, Integer::sum);
            f.brain("watered " + wet + " blocks of its parched field");
        }
        CARRY.remove(f.getUUID());
        return false;
    }

    private static boolean mayStill(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Disasters.Town t = id == null ? null : Disasters.known(id);
        return t != null && t.drought && !f.isSleeping() && f.getTarget() == null && !Weather.stormy(level);
    }

    /** The driest farmland on a plot (moisture gone), the nearest its middle; or null if none is dry. */
    @Nullable
    static BlockPos driest(ServerLevel level, WorkZone z) {
        BlockPos c = z.center();
        int r = z.radius();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos p = c.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (!(st.getBlock() instanceof FarmBlock) || st.getValue(FarmBlock.MOISTURE) > 0) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bd) { bd = d; best = p.immutable(); }
                }
            }
        }
        return best;
    }

    /** Pour a bucket on the farmland here: the three-by-three round it wet through. Returns the blocks wetted. */
    static int water(ServerLevel level, BlockPos at) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof FarmBlock) || st.getValue(FarmBlock.MOISTURE) >= FarmBlock.MAX_MOISTURE) continue;
            level.setBlock(p, st.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 2);
            n++;
        }
        return n;
    }

    /** The nearest open water to here (a source with air over it), or null. */
    @Nullable
    static BlockPos water(ServerLevel level, BlockPos near, int reach) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(near.offset(-reach, -4, -reach), near.offset(reach, 3, reach))) {
            if (!level.getBlockState(p).is(Blocks.WATER) || !level.getFluidState(p).isSource()) continue;
            if (!level.getBlockState(p.above()).isAir()) continue;
            double d = p.distSqr(near);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    /**
     * A bucket filled here, as the game fills one: a source with water either side of it fills again (a river,
     * a well's spring); a lone one is taken up. False if there is no water there now.
     */
    static boolean fill(ServerLevel level, BlockPos w) {
        if (!level.getBlockState(w).is(Blocks.WATER) || !level.getFluidState(w).isSource()) return false;
        int sources = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = w.relative(dir);
            if (level.getFluidState(n).is(FluidTags.WATER) && level.getFluidState(n).isSource()) sources++;
        }
        if (sources < 2) level.setBlockAndUpdate(w, Blocks.AIR.defaultBlockState());
        return true;
    }

    // ------------------------------------------------------------------ the channels

    /**
     * Dig so many blocks of irrigation through the first of the dry fields, by hand (TownJobs): a straight run
     * of water every eight rows, as Waterfront cuts them, each block's soil into the stores and its water a
     * bucket of the stores' filled at the nearest water. A field wet through is done with. Returns the blocks dug.
     */
    static int irrigate(ServerLevel level, Villages.Village v, Disasters.Town t, int most) {
        if (t.dryFields.isEmpty()) return 0;
        long day = level.getDayTime() / 24000L;
        if (!AssistantConfig.villageReshapeLand()) {
            if (!t.irrigationBarred) {
                t.irrigationBarred = true;
                Disasters.record(v.id(), day, "the town would have dug irrigation for its dry fields, but the land is not to be reshaped");
            }
            t.dryFields.clear();
            Disasters.dirty();
            return 0;
        }
        long[] fld = t.dryFields.get(0);
        BlockPos c = BlockPos.of(fld[0]);
        int r = (int) fld[1];
        if (!Land.areaLoaded(level, c, r + 2)) return 0;
        int y = Waterfront.fieldLevel(level, c, r);
        // The water to carry it from, looked for once a field (it is a long look) and again if it has gone.
        BlockPos water = WATER_AT.get(fld[0]);
        if (water == null || !level.getBlockState(water).is(Blocks.WATER)) {
            water = water(level, c, WATER_REACH + r);
            if (water != null) WATER_AT.put(fld[0], water);
        }
        if (y == Integer.MIN_VALUE || water == null) {
            t.dryFields.remove(0);
            if (y != Integer.MIN_VALUE) Disasters.record(v.id(), day, "no water within reach of the field at " + c.getX() + ", " + c.getZ()
                + " to dig irrigation from: it waits on the rain");
            Disasters.dirty();
            return 0;
        }
        // The channel's next blocks: the rows every eight across the field, at the farmland's level.
        List<BlockPos> next = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        if (r <= 4) rows.add(c.getZ());
        else for (int z = c.getZ() - r + 4; z <= c.getZ() + r - 3; z += 8) rows.add(z);
        for (int z : rows) {
            for (int x = c.getX() - r + 1; x <= c.getX() + r - 1 && next.size() < most; x++) {
                BlockPos at = new BlockPos(x, y, z);
                if (diggable(level, at)) next.add(at);
            }
        }
        if (next.isEmpty() || Waterfront.wet(level, c, r, y)) {
            t.dryFields.remove(0);
            int dug = DUG.getOrDefault(fld[0], 0);
            DUG.remove(fld[0]);
            WATER_AT.remove(fld[0]);
            if (dug > 0) {
                t.fieldsIrrigated++;
                t.irrigatedOn = day;
                Disasters.record(v.id(), day, "the town dug irrigation channels through the field at " + c.getX() + ", " + c.getZ() + ": "
                    + dug + " blocks of water carried from " + source(level, v, water) + ", so the next drought passes it by");
            }
            Disasters.dirty();
            return 0;
        }
        // A bucket of the stores' to carry the water in.
        if (Market.stock(level, v.id(), s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) == 0) return 0;
        if (!TownJobs.atWork(level, v, "irrigation", next.get(0), "digging irrigation through the field at " + c.getX() + ", " + c.getZ())) return 0;
        int n = 0;
        for (BlockPos at : next) {
            if (!fill(level, water)) {
                water = water(level, c, WATER_REACH + r);
                if (water == null || !fill(level, water)) break;
                WATER_AT.put(fld[0], water);
            }
            if (!Waterfront.dig(level, at)) continue;
            Crafts.store(level, v, new ItemStack(Items.DIRT));          // the soil dug out, into the stores
            n++;
        }
        if (n > 0) {
            level.playSound(null, next.get(0), SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 0.8F, 0.9F);
            DUG.merge(fld[0], n, Integer::sum);
            t.irrigated += n;
        }
        Disasters.dirty();
        return n;
    }

    /** Blocks dug through each field so far, by its key; and the water each is carried from. */
    private static final Map<Long, Integer> DUG = new ConcurrentHashMap<>();
    private static final Map<Long, BlockPos> WATER_AT = new ConcurrentHashMap<>();

    /** Would a channel go here (Waterfront.dig's own rule): soil, held either side, not water already? */
    private static boolean diggable(ServerLevel level, BlockPos at) {
        BlockState s = level.getBlockState(at);
        if (s.is(Blocks.WATER)) return false;
        if (!(s.getBlock() instanceof FarmBlock) && !s.is(Blocks.DIRT) && !s.is(Blocks.GRASS_BLOCK) && !s.is(Blocks.COARSE_DIRT)) return false;
        if (!level.getBlockState(at.below()).isSolid() && !level.getBlockState(at.below()).is(Blocks.WATER)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(at.relative(d));
            if (!n.isSolid() && !n.is(Blocks.WATER) && !(n.getBlock() instanceof FarmBlock)) return false;
        }
        BlockState above = level.getBlockState(at.above());
        return above.isAir() || above.is(net.minecraft.tags.BlockTags.CROPS) || above.canBeReplaced();
    }

    /** Where the water came from, in words: the well, the river, the pond. */
    static String source(ServerLevel level, Villages.Village v, BlockPos water) {
        com.jrpetty.mcassistant.village.Ledger.Building well = Villages.builtStructure(v.id(), "well");
        if (well != null && well.anchor().distSqr(water) <= 16) return "the well";
        int[] river = Floods.river(level, v);
        if (river != null && river[0] == water.getY()) return "the river";
        return "the pond";
    }

    // ------------------------------------------------------------------ the card

    @Nullable
    static String cardPart(VillageFolkEntity f) {
        if (CARRY.containsKey(f.getUUID())) return "carrying water out to its parched field";
        int n = CARRIES.getOrDefault(f.getUUID(), 0);
        if (n > 0 && on(f.ownerId())) return "has carried " + n + (n == 1 ? " bucket" : " buckets") + " of water to its field in the drought";
        return null;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a drought now (true: begun whatever the weather and the season) or broken (false). */
    public static void droughtForTests(ServerLevel level, UUID village, boolean on) {
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        Disasters.Town t = Disasters.town(village);
        long day = level.getDayTime() / 24000L;
        if (on) {
            if (t.dryDays < AssistantConfig.villageDroughtDays()) t.dryDays = AssistantConfig.villageDroughtDays();
            if (!t.drought) begin(level, v, t, day);
            DRY.put(village, fields(village));
        } else {
            end(level, v, t, day, "for the test");
        }
    }

    /** Tests: a field of the town's set out here (as a farmer's plot would be). */
    public static void fieldForTests(UUID village, BlockPos centre, int radius) {
        TEST_FIELDS.computeIfAbsent(village, k -> new ArrayList<>()).add(new int[]{ centre.getX(), centre.getZ(), radius, centre.getY() });
        if (DRY.containsKey(village)) DRY.put(village, fields(village));
    }

    /** Tests: is this crop withering in the drought? */
    public static boolean stuntedForTests(ServerLevel level, BlockPos crop) {
        return stunted(level, crop);
    }

    /** Tests: the dry fields waiting for irrigation, by centre. */
    public static List<BlockPos> dryFieldsForTests(UUID village) {
        Disasters.Town t = Disasters.known(village);
        List<BlockPos> out = new ArrayList<>();
        if (t != null) for (long[] d : t.dryFields) out.add(BlockPos.of(d[0]));
        return out;
    }

    /** Tests: the town digs its irrigation now, as far as it can. Returns the blocks of channel dug. */
    public static int irrigateForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Disasters.Town t = Disasters.known(village);
        if (v == null || t == null) return 0;
        int n = 0;
        for (int i = 0; i < 60 && !t.dryFields.isEmpty(); i++) n += irrigate(level, v, t, 8);
        return n;
    }

    /** Tests: buckets of water this farmer has carried to its field. */
    public static int carriesForTests(VillageFolkEntity f) {
        return CARRIES.getOrDefault(f.getUUID(), 0);
    }
}
