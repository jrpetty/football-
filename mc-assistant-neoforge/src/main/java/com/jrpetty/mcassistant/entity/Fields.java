package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town's tended fields: crops that grow faster under a farmer's hand, bone meal, a composter, the
 * field lit for the night, and folk got loose that have stuck fast.
 *
 * <p>The hundred days' town of fifteen had five farmers growing twenty to fifty meals a day between them,
 * when three meals a head is forty-five: new fields far out, a farmer's every crop three seconds' work,
 * a nine-by-nine of wheat at the game's own pace growing eleven meals a day, the bone meal in its pack
 * never used. So:
 * <ul>
 * <li><b>Tended fields grow faster</b> (villageCropGrowth, three times by default): on the farmland of a
 *     farmer's plot the server gives the crops extra growth ticks, the way the game's own random ticks
 *     come (the crop's own randomTick, which still wants its light and its water: a dark or a dry field
 *     gains nothing by it), and only where the game gives random ticks at all (a player near, or the
 *     town's ground kept awake). A handful of columns a field a second, never more than {@link #PICKS_MOST}.
 *     A farmer's care adds up to one more: a quarter each for its level (ten or more), a field nearly
 *     all watered, a field lit, and a composter on it. Wild crops and a player's own farm are never
 *     touched.</li>
 * <li><b>Bone meal</b>: a farmer at its field puts one on a growing crop every few seconds, out of its
 *     pack (it keeps sixteen), crushing the stores' bones (the watch's and the hunters') or taking the
 *     composter's.</li>
 * <li><b>A composter</b> by its work chest, of the stores' planks, filled with the seed past what it
 *     keeps and its poisonous potatoes: bone meal, as a player makes it.</li>
 * <li><b>The field lit</b>: torches out of the stores round its edge, set by the farmer at its field, so
 *     nothing spawns in it at night to trample it or meet the farmer at dawn (an open field grows by
 *     night anyway, by the sky's light; a roofed one wants the torches to grow at all).</li>
 * <li><b>Stuck fast</b>: a folk standing in ground that no longer ticks (out past the chunks the town keeps
 *     awake: the hundred days' hunter stood frozen there for twenty-five days, a mouth that neither ate
 *     nor hunted) is brought home; one on its shift that has done no work in half a day and gone nowhere
 *     (a fisher "stepping out of a wedge" for twenty days) is put back on its plot.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Fields {

    private Fields() {}

    /** The most columns of one field given an extra growth tick in a second. */
    public static final int PICKS_MOST = 48;
    /** A farmer's bone meal kept in its pack, and bones. */
    public static final int BONE_MEAL_KEPT = 16;

    /** A farmer's care for its field, looked at once a minute: the growth it adds, and why. */
    record Care(double bonus, boolean level, boolean watered, boolean lit, boolean composter) {}

    private static final Map<UUID, Care> CARE = new ConcurrentHashMap<>();
    private static final Map<UUID, double[]> CARRY = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> COMPOSTER = new ConcurrentHashMap<>();
    /** Growth picks given, per farmer, in the last second; and all told (tests, the cost). */
    private static final Map<UUID, Integer> PICKS = new ConcurrentHashMap<>();
    /** Folk seen in ground that does not tick, how many looks running; and where each stood, and when. */
    private static final Map<UUID, Integer> FROZEN = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> STILL = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CARE.clear();
        CARRY.clear();
        COMPOSTER.clear();
        PICKS.clear();
        FROZEN.clear();
        STILL.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        com.jrpetty.mcassistant.Guard.run("the town's fields", () -> tick(event.getServer()));
    }

    static void tick(MinecraftServer server) {
        int t = server.getTickCount();
        if (t % 20 != 7) return;
        for (Villages.Village v : Villages.every()) {
            ServerLevel level = server.getLevel(v.dim());
            if (level == null) continue;
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || f.showcaseFolk()) continue;
                if (f.stationTask() == StationTask.FARM && f.workZone() != null && !f.isBaby()) {
                    grow(level, f, f.workZone());
                    if (t % 100 == 7) boneMeal(level, v, f);
                    if (t % 1200 == 7) {
                        care(level, f);
                        compost(level, v, f);
                        lightTheField(level, v, f);
                    }
                }
                if (t % 1200 == 607) unstick(level, v, f);
            }
        }
    }

    // ------------------------------------------------------------------ growth

    /** How much faster its field grows than the wild: the config's, and its care on top (1.0: the game's pace). */
    public static double multiplier(VillageFolkEntity f) {
        double base = AssistantConfig.villageCropGrowth();
        if (base <= 1.0) return 1.0;
        Care c = CARE.get(f.getUUID());
        return base + (c == null ? 0.0 : c.bonus());
    }

    /** The town's word on its fields, for the books: "the fields grow at 3x (tended)". */
    public static String word(UUID village) {
        double base = AssistantConfig.villageCropGrowth(), most = base;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.FARM) most = Math.max(most, multiplier(f));
        }
        if (base <= 1.0) return "the fields grow at the game's own pace";
        return String.format(Locale.ROOT, "the fields grow at %.1fx (tended)%s", base,
            most > base + 0.01 ? String.format(Locale.ROOT, ", up to %.2fx where they are best kept", most) : "");
    }

    /**
     * A second of a tended field: as many columns as the extra growth comes to (the game gives every block
     * {@code randomTickSpeed} chances in 4096 a tick; a tended crop gets that many again, (multiplier - 1)
     * times), each its crop's own random tick.
     */
    static void grow(ServerLevel level, VillageFolkEntity f, WorkZone z) {
        double m = multiplier(f);
        if (m <= 1.0) return;
        int speed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (speed <= 0) return;
        int r = z.radius();
        double want = (2 * r + 1) * (2 * r + 1) * speed * 20.0 / 4096.0 * (m - 1.0);
        double[] carry = CARRY.computeIfAbsent(f.getUUID(), k -> new double[1]);
        carry[0] += want;
        int picks = (int) Math.min(PICKS_MOST, Math.floor(carry[0]));
        carry[0] = Math.min(PICKS_MOST, carry[0] - picks);
        PICKS.put(f.getUUID(), picks);
        BlockPos c = z.center();
        var rnd = level.getRandom();
        long lastChunk = Long.MIN_VALUE;
        boolean ticks = false;
        for (int i = 0; i < picks; i++) {
            int x = c.getX() + rnd.nextInt(2 * r + 1) - r, zz = c.getZ() + rnd.nextInt(2 * r + 1) - r;
            long chunk = net.minecraft.world.level.ChunkPos.asLong(x >> 4, zz >> 4);
            if (chunk != lastChunk) {
                lastChunk = chunk;
                ticks = level.hasChunk(x >> 4, zz >> 4) && ticksNaturally(level, x >> 4, zz >> 4);
            }
            if (!ticks) continue;                                   // the game grows nothing there now: nor do we
            BlockPos crop = cropIn(level, x, c.getY(), zz);
            if (crop != null) level.getBlockState(crop).randomTick(level, crop, rnd);
        }
    }

    /**
     * Does the game give this chunk its random ticks now? Only where it would (a player near enough, or
     * the chunk kept awake and ticking, as the town's are: ChunkLoad), so a tended field grows three times
     * as fast as the game's own, never where the game grows nothing at all.
     */
    static boolean ticksNaturally(ServerLevel level, int cx, int cz) {
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(cx, cz);
        if (!level.isNaturalSpawningAllowed(cp) || !level.shouldTickBlocksAt(cp.toLong())) return false;
        if (level.getChunkSource().chunkMap.getDistanceManager().shouldForceTicks(cp.toLong())) return true;
        double mx = cp.getMiddleBlockX(), mz = cp.getMiddleBlockZ();
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - mx, dz = p.getZ() - mz;
            if (dx * dx + dz * dz < 128.0 * 128.0) return true;
        }
        return false;
    }

    /** The crop on farmland in this column near the field's height, or null. */
    @Nullable
    static BlockPos cropIn(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 4; dy >= -4; dy--) {
            p.set(x, y + dy, z);
            BlockState st = level.getBlockState(p);
            if ((st.getBlock() instanceof CropBlock || st.getBlock() instanceof StemBlock)
                    && level.getBlockState(p.below()).getBlock() instanceof FarmBlock) {
                return p.immutable();
            }
        }
        return null;
    }

    /** Tests: growth picks given its field in the last second. */
    public static int picksForTests(VillageFolkEntity f) {
        return PICKS.getOrDefault(f.getUUID(), 0);
    }

    /** How ripe a field is (percent of its crops) before its farmer's break waits on the harvest. */
    static final int RIPE_NO_BREAK = 25;

    /** A farmer with a quarter of its field ripe at its last look puts its break off till it is in (breakNow). */
    public static boolean harvestWaits(VillageFolkEntity f) {
        return f.stationTask() == StationTask.FARM && f.workZone() != null && f.ripePercentNow() >= RIPE_NO_BREAK;
    }

    // ------------------------------------------------------------------ care

    static void care(ServerLevel level, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null) return;
        BlockPos c = z.center();
        int r = z.radius(), farmland = 0, wet = 0, crops = 0, bright = 0;
        var rnd = level.getRandom();
        for (int i = 0; i < 24; i++) {
            int x = c.getX() + rnd.nextInt(2 * r + 1) - r, zz = c.getZ() + rnd.nextInt(2 * r + 1) - r;
            if (!level.hasChunk(x >> 4, zz >> 4)) continue;
            BlockPos crop = cropIn(level, x, c.getY(), zz);
            if (crop == null) continue;
            crops++;
            farmland++;
            if (level.getBlockState(crop.below()).getValue(FarmBlock.MOISTURE) >= FarmBlock.MAX_MOISTURE) wet++;
            if (level.getBrightness(LightLayer.BLOCK, crop) >= 9) bright++;
        }
        boolean lv = f.veteranLevel() >= 10;
        boolean watered = farmland >= 4 && wet * 10 >= farmland * 9;
        boolean lit = crops >= 4 && bright * 2 >= crops;
        BlockPos comp = COMPOSTER.get(f.getUUID());
        boolean composter = comp != null && level.getBlockState(comp).is(Blocks.COMPOSTER);
        double bonus = (lv ? 0.25 : 0) + (watered ? 0.25 : 0) + (lit ? 0.25 : 0) + (composter ? 0.25 : 0);
        CARE.put(f.getUUID(), new Care(bonus, lv, watered, lit, composter));
    }

    /** Its care, in words, for its card. */
    @Nullable
    public static String careLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.FARM) return null;
        Care c = CARE.get(f.getUUID());
        String m = String.format(Locale.ROOT, "%.2fx", multiplier(f));
        if (c == null) return "its field grows at " + m;
        return "its field grows at " + m + (c.watered() ? ", well watered" : "") + (c.lit() ? ", lit at night" : "")
            + (c.composter() ? ", with a composter" : "");
    }

    // ------------------------------------------------------------------ bone meal

    static void boneMeal(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null || f.isSleeping() || !z.containsColumn(f.blockPosition())) {
            // At the stores (its put-away, its rations): bones for the meal, out of the watch's and the hunters'.
            if (f.countCarried(s -> s.is(Items.BONE_MEAL) || s.is(Items.BONE)) == 0 && f.usesVillageStores()) {
                BlockPos stores = f.storesSpot(level, v.id());
                if (stores != null && stores.distSqr(f.blockPosition()) <= 12 * 12 && Crafts.take(level, v, s -> s.is(Items.BONE), 4)) {
                    f.insertItem(new ItemStack(Items.BONE, 4));
                }
            }
            return;
        }
        // A bone crushed into three bone meal, in hand, as anybody crafts it.
        if (f.countCarried(s -> s.is(Items.BONE_MEAL)) == 0 && f.removeMatching(s -> s.is(Items.BONE), 1) == 1) {
            ItemStack left = f.insertItem(new ItemStack(Items.BONE_MEAL, 3));
            if (!left.isEmpty()) f.spawnAtLocation(left);
        }
        ItemStack meal = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (s.is(Items.BONE_MEAL) && !s.isEmpty()) { meal = s; break; }
        if (meal.isEmpty()) return;
        BlockPos at = f.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 2, 4))) {
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof CropBlock crop) || crop.isMaxAge(st) || !z.containsColumn(p)) continue;
            BlockPos grow = p.immutable();
            if (net.minecraft.world.item.BoneMealItem.growCrop(meal, level, grow)) {
                level.levelEvent(1505, grow, 15);                       // the game's own green sparkle
                f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                f.brain("bone meal on the field");
            }
            return;
        }
    }

    // ------------------------------------------------------------------ the composter

    static void compost(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos chest = f.productionChest();
        if (chest == null || f.isSleeping()) return;
        BlockPos at = COMPOSTER.get(f.getUUID());
        if (at == null || !level.getBlockState(at).is(Blocks.COMPOSTER)) {
            at = null;
            for (BlockPos p : BlockPos.betweenClosed(chest.offset(-2, -1, -2), chest.offset(2, 1, 2))) {
                if (level.getBlockState(p).is(Blocks.COMPOSTER)) { at = p.immutable(); break; }
            }
            if (at == null) {
                // One set down beside its work chest, of the stores' planks (seven slabs: four planks).
                BlockPos spot = null;
                for (int[] d : new int[][]{ {1, 0}, {-1, 0}, {0, 1}, {0, -1} }) {
                    BlockPos p = chest.offset(d[0], 0, d[1]);
                    if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) {
                        spot = p;
                        break;
                    }
                }
                if (spot == null || f.blockPosition().distSqr(chest) > 8 * 8 || !Crafts.usePlanks(level, v, 4)) return;
                level.setBlockAndUpdate(spot, Blocks.COMPOSTER.defaultBlockState());
                at = spot;
                f.brain("set a composter down by its work chest");
            }
            COMPOSTER.put(f.getUUID(), at);
        }
        if (f.blockPosition().distSqr(at) > 24 * 24) return;
        BlockState st = level.getBlockState(at);
        // Ready: the bone meal out, into its pack.
        if (st.getValue(ComposterBlock.LEVEL) >= ComposterBlock.READY) {
            ComposterBlock.extractProduce(f, st, level, at);
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2.0), e -> e.getItem().is(Items.BONE_MEAL))) {
                ItemStack left = f.insertItem(e.getItem().copy());
                if (left.isEmpty()) e.discard(); else e.setItem(left);
            }
            f.brain("bone meal out of the composter");
            return;
        }
        // In with the seed past what it keeps, and its poisonous potatoes: up to sixteen a look.
        int keep = AssistantEntity.SEED_MOST;
        for (int i = 0; i < 16 && st.getValue(ComposterBlock.LEVEL) < 7; i++) {
            int seeds = f.countCarried(s -> s.is(Items.WHEAT_SEEDS)), beet = f.countCarried(s -> s.is(Items.BEETROOT_SEEDS));
            java.util.function.Predicate<ItemStack> spare = seeds > keep ? s -> s.is(Items.WHEAT_SEEDS)
                : beet > keep ? s -> s.is(Items.BEETROOT_SEEDS)
                : f.countCarried(s -> s.is(Items.POISONOUS_POTATO)) > 0 ? s -> s.is(Items.POISONOUS_POTATO) : null;
            if (spare == null || f.removeMatching(spare, 1) != 1) break;
            ItemStack one = seeds > keep ? new ItemStack(Items.WHEAT_SEEDS) : beet > keep ? new ItemStack(Items.BEETROOT_SEEDS)
                : new ItemStack(Items.POISONOUS_POTATO);
            st = ComposterBlock.insertItem(f, st, level, one, at);
        }
    }

    // ------------------------------------------------------------------ the field lit

    static void lightTheField(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null || f.isSleeping()) return;
        BlockPos c = z.center();
        int r = z.radius(), edge = r + 1, laid = 0;
        if (Math.abs(f.getBlockX() - c.getX()) > r + 6 || Math.abs(f.getBlockZ() - c.getZ()) > r + 6) return;   // set by hand, at the field
        java.util.List<Integer> along = new java.util.ArrayList<>();
        for (int o = -r + 4; o <= r - 4; o += 9) along.add(o);
        if (along.isEmpty()) along.add(0);
        for (int side = 0; side < 4 && laid < 2; side++) {
            for (int o : along) {
                if (laid >= 2) break;
                int x = c.getX() + (side == 0 ? edge : side == 1 ? -edge : o), zz = c.getZ() + (side == 2 ? edge : side == 3 ? -edge : o);
                if (!level.hasChunk(x >> 4, zz >> 4)) continue;
                BlockPos spot = torchSpot(level, x, c.getY(), zz);
                if (spot == null) continue;
                boolean have = f.removeMatching(s -> s.is(Items.TORCH), 1) == 1
                    || Crafts.take(level, v, s -> s.is(Items.TORCH), 1);
                if (!have) return;
                level.setBlockAndUpdate(spot, Blocks.TORCH.defaultBlockState());
                laid++;
            }
        }
        if (laid > 0) f.brain("lit the edge of its field (" + laid + (laid == 1 ? " torch)" : " torches)"));
    }

    /** Ground to stand a torch on at the field's edge, if none stands near it already; null if none. */
    @Nullable
    private static BlockPos torchSpot(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 3; dy >= -3; dy--) {
            p.set(x, y + dy, z);
            BlockState st = level.getBlockState(p), below = level.getBlockState(p.below());
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) return null;      // lit already
            if (!st.isAir() || !level.getFluidState(p).isEmpty()) continue;
            if (below.getBlock() instanceof FarmBlock || !below.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) continue;
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
                if (level.getBlockState(q).is(Blocks.TORCH)) return null;
            }
            return p.immutable();
        }
        return null;
    }

    // ------------------------------------------------------------------ stuck fast

    static void unstick(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.isBaby() || f.isSleeping() || f.isHired() || f.trip() != null || f.expedition() != null
                || Nether.away(f) || JobSeekers.busy(f) || Drover.busy(f)) {
            FROZEN.remove(f.getUUID());
            STILL.remove(f.getUUID());
            return;
        }
        BlockPos here = f.blockPosition();
        // In ground that no longer ticks: it cannot walk out of it itself.
        if (!level.isPositionEntityTicking(here)) {
            int n = FROZEN.merge(f.getUUID(), 1, Integer::sum);
            if (n >= 2) {
                FROZEN.remove(f.getUUID());
                BlockPos home = f.bedPos() != null ? f.bedPos() : v.centre();
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home.getX(), home.getZ());
                f.clearQueue();
                f.teleportTo(home.getX() + 0.5, Math.max(y, home.getY()), home.getZ() + 0.5);
                f.brain("stood frozen out past the town's ground — brought home");
            }
            return;
        }
        FROZEN.remove(f.getUUID());
        // On its shift, no work in half a day, and gone nowhere in five minutes: back on its plot.
        long now = level.getGameTime();
        long[] s = STILL.get(f.getUUID());
        if (s == null || Math.abs(s[0] - here.getX()) + Math.abs(s[1] - here.getZ()) > 3) {
            STILL.put(f.getUUID(), new long[]{ here.getX(), here.getZ(), now });
            return;
        }
        if (now - s[2] < 6000 || !f.onShift() || f.ticksSinceWork() < 12000 || f.workZone() == null) return;
        if (f.workZone().containsColumn(here)) return;                // at its own work, whatever it is about
        STILL.remove(f.getUUID());
        f.clearQueue();
        f.unstickToPlot();
    }
}
