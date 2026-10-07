package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The allotments [batchE]: a fenced square of four small plots by the fields (its own building, drawn in
 * allotments.txt), for the households with no garden of their own (Families: the households that have not
 * had theirs). The town gives each such household a plot, as plots come free; a household that plants its
 * garden after all gives its plot up for the next.
 *
 * <p>Two evenings in three one of the household walks over after supper and works its plot by hand: the
 * bare earth turned into furrows with a hoe (its own, or one of the stores' borrowed and put back the worse
 * for it), seed put in (carrots, potatoes, beetroot or wheat: out of the household's own chest first, then
 * the stores, never the farmers' last twelve), and whatever is ripe pulled up, one of it put back in the
 * ground for the next crop. What it pulled up it carries home and puts in the household's chest. The crops
 * are the game's own and grow at the game's pace, watered by the channel down the plots (its water poured
 * from a bucket of the stores' when it is dry).
 */
public final class Allotments {

    private Allotments() {}

    /** A town of this many wants allotments, once two households have no garden. */
    static final int FROM = 18;
    /** The four plots, each three by three: the corner of each nearest the left and the front, in the drawing. */
    static final int[][] PLOTS = { { -3, 1 }, { 1, 1 }, { -3, -3 }, { 1, -3 } };
    /** Where somebody stands to work each plot: on the path beside it. */
    static final int[][] STAND = { { -2, 0 }, { 2, 0 }, { 0, -2 }, { 0, -2 } };
    /** The water channel down the back half. */
    static final int[][] CHANNEL = { { 0, 1 }, { 0, 2 }, { 0, 3 } };
    /** A pause between one thing done on a plot and the next. */
    static final int PACE = 30;

    /** What a seed grows into, and what is pulled up of it. */
    static final Item[] SEEDS = { Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS, Items.WHEAT_SEEDS };

    /** Somebody at its plot this evening. */
    private static final class Evening {
        final long day;
        final Ledger.Building b;
        final int plot;
        int stage;                      // 0 there, 1 at it, 2 home with it, 3 done
        int walkTick = -1000, workTick = -1000, started;
        final Map<Item, Integer> picked = new HashMap<>();

        Evening(long day, Ledger.Building b, int plot, int started) {
            this.day = day;
            this.b = b;
            this.plot = plot;
            this.started = started;
        }
    }

    private static final Map<UUID, Evening> EVENINGS = new ConcurrentHashMap<>();
    /** Whose turn it is on each plot tonight (building anchor and plot), so two of a household do not both go. */
    private static final Map<String, UUID> TONIGHT = new ConcurrentHashMap<>();

    static void resetForTests() {
        EVENINGS.clear();
        TONIGHT.clear();
    }

    // ------------------------------------------------------------------ the households

    /** The village's allotments, oldest first. */
    static List<Ledger.Building> allotments(UUID village) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(TownLook.ALLOTMENTS)) out.add(b);
        return out;
    }

    /** Households living somewhere with no garden of their own. */
    static int gardenless(UUID village) {
        int n = 0;
        for (Homes.Home h : Homes.homes(village).values()) if (!h.members.isEmpty() && !Families.gardened(village, h.anchor.asLong())) n++;
        return n;
    }

    static boolean wanted(UUID village) {
        return gardenless(village) >= 2;
    }

    private static String key(Ledger.Building b, int plot) {
        return "allot/" + b.anchor().asLong() + "/" + plot;
    }

    /** The household (its house's anchor) a plot is let to, or -1. */
    static long holder(UUID village, Ledger.Building b, int plot) {
        String s = Ledger.note(village, key(b, plot));
        if (s == null || s.isEmpty()) return -1L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** The plot let to this household, as {building, plot}, or null. */
    @Nullable
    static Object[] plotOf(UUID village, long home) {
        for (Ledger.Building b : allotments(village)) {
            for (int i = 0; i < PLOTS.length; i++) if (holder(village, b, i) == home) return new Object[]{ b, i };
        }
        return null;
    }

    /**
     * The town's part: a plot given up by a household that has its garden now (or that is gone), and a free
     * plot let to the next household with none. True if a plot changed hands.
     */
    static boolean let(UUID village) {
        Map<Long, Homes.Home> homes = Homes.homes(village);
        for (Ledger.Building b : allotments(village)) {
            for (int i = 0; i < PLOTS.length; i++) {
                long h = holder(village, b, i);
                if (h == -1L) continue;
                Homes.Home home = homes.get(h);
                if (home == null || home.members.isEmpty() || Families.gardened(village, h)) {
                    Ledger.note(village, key(b, i), "");
                    return true;
                }
            }
        }
        for (Homes.Home home : homes.values()) {
            if (home.members.isEmpty() || Families.gardened(village, home.anchor.asLong())) continue;
            if (plotOf(village, home.anchor.asLong()) != null) continue;
            for (Ledger.Building b : allotments(village)) {
                for (int i = 0; i < PLOTS.length; i++) {
                    if (holder(village, b, i) != -1L) continue;
                    Ledger.note(village, key(b, i), Long.toString(home.anchor.asLong()));
                    return true;
                }
            }
            return false;                                                    // every plot let
        }
        return false;
    }

    /** One visit: the plots let, and the channel watered when it has run dry. True if anything was done. */
    static boolean tick(ServerLevel level, Villages.Village v) {
        String today = "/" + level.getDayTime() / 24000L;
        TONIGHT.keySet().removeIf(k -> !k.endsWith(today));               // last night's turns forgotten
        if (let(v.id())) return true;
        for (Ledger.Building b : allotments(v.id())) {
            if (!level.isLoaded(b.anchor())) continue;
            for (int[] c : CHANNEL) {
                BlockPos p = TownLook.cell(b, c[0], -1, c[1]);
                if (level.getFluidState(p).is(FluidTags.WATER) || !level.getBlockState(p).isAir()) continue;
                if (Market.stock(level, v.id(), s -> s.is(Items.WATER_BUCKET)) < 1) return false;
                if (!TownJobs.atWork(level, v, "allotments", p, "watering the allotments")) return false;
                if (!Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1)) return false;
                Crafts.store(level, v, new ItemStack(Items.BUCKET));
                level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the plot's cells

    /** The earth of a plot (where the furrows are), its nine cells. */
    static List<BlockPos> soil(Ledger.Building b, int plot) {
        List<BlockPos> out = new ArrayList<>();
        int[] p = PLOTS[plot];
        for (int dz = p[1] + 2; dz >= p[1]; dz--) {
            for (int dx = p[0]; dx <= p[0] + 2; dx++) out.add(TownLook.cell(b, dx, -1, dz));
        }
        return out;
    }

    static BlockPos stand(Ledger.Building b, int plot) {
        return TownLook.cell(b, STAND[plot][0], 0, STAND[plot][1]);
    }

    /** Is this ripe: a crop grown all the way? */
    static boolean ripe(BlockState st) {
        return st.getBlock() instanceof CropBlock crop && crop.isMaxAge(st);
    }

    /** Is this earth to be turned: bare earth or grass with nothing growing on it? */
    static boolean untilled(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return (st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.ROOTED_DIRT))
            && TownLook.open(level.getBlockState(p.above()));
    }

    /** Is there anything to do on this plot: a crop ripe, earth to turn, a furrow to sow? */
    static boolean needsWork(ServerLevel level, Ledger.Building b, int plot) {
        if (!level.isLoaded(b.anchor())) return false;
        for (BlockPos p : soil(b, plot)) {
            if (ripe(level.getBlockState(p.above())) || untilled(level, p) || bare(level, p)) return true;
        }
        return false;
    }

    /** Is this a furrow waiting for seed? */
    static boolean bare(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).is(Blocks.FARMLAND) && level.getBlockState(p.above()).isAir();
    }

    /**
     * One thing done on a plot by this folk: the ripest first (a crop pulled up, its seed back in), then the
     * earth turned, then a furrow sown. Returns what it did, or null if there was nothing it could do.
     */
    @Nullable
    static String work(ServerLevel level, Villages.Village v, VillageFolkEntity f, Ledger.Building b, int plot, Map<Item, Integer> picked,
                       @Nullable Homes.Home home) {
        List<BlockPos> soil = soil(b, plot);
        for (BlockPos p : soil) {
            BlockState crop = level.getBlockState(p.above());
            if (!ripe(crop)) continue;
            List<ItemStack> drops = Block.getDrops(crop, level, p.above(), null);
            level.destroyBlock(p.above(), false);
            Item seed = crop.getBlock().asItem();
            for (ItemStack d : drops) {
                if (seed != Items.AIR && d.is(seed) && !level.getBlockState(p.above()).getBlock().equals(crop.getBlock())) {
                    d.shrink(1);                                              // one back in the ground for the next crop
                    level.setBlock(p.above(), crop.getBlock().defaultBlockState(), 3);
                }
                if (d.isEmpty()) continue;
                picked.merge(d.getItem(), d.getCount(), Integer::sum);
                ItemStack left = f.insertItem(d);
                if (!left.isEmpty()) Block.popResource(level, f.blockPosition(), left);
            }
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, p.above(), SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
            return "lifting the " + crop.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT) + " on its allotment";
        }
        for (BlockPos p : soil) {
            if (!untilled(level, p)) continue;
            if (!hoe(level, v, f)) return null;
            if (!level.getBlockState(p.above()).isAir()) level.destroyBlock(p.above(), false);
            level.setBlock(p, Blocks.FARMLAND.defaultBlockState(), 3);
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, p, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.8F, 1.0F);
            return "turning the earth on its allotment";
        }
        for (int i = 0; i < soil.size(); i++) {
            BlockPos p = soil.get(i);
            if (!bare(level, p)) continue;
            ItemStack seed = seed(level, v, home, i);
            if (seed.isEmpty()) return null;
            Block grows = Block.byItem(seed.getItem());
            if (!(grows instanceof CropBlock)) {
                Crafts.store(level, v, seed);
                return null;
            }
            level.setBlock(p.above(), grows.defaultBlockState(), 3);
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, p.above(), SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8F, 1.0F);
            return "sowing its allotment";
        }
        return null;
    }

    /**
     * A seed for a furrow: of the kind its place in the plot gives it (a row of carrots, of potatoes, of
     * beetroot...), else whatever there is: out of the household's chest first, then the stores, leaving the
     * farmers their last twelve of each.
     */
    static ItemStack seed(ServerLevel level, Villages.Village v, @Nullable Homes.Home home, int cell) {
        Item first = SEEDS[(cell / 3) % SEEDS.length];
        List<Item> order = new ArrayList<>();
        order.add(first);
        for (Item it : SEEDS) if (it != first) order.add(it);
        if (home != null) {
            BlockPos chest = Homes.chestOf(level, v.id(), home);
            if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
                for (Item it : order) {
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        ItemStack s = c.getItem(i);
                        if (!s.is(it) || !s.getComponentsPatch().isEmpty()) continue;
                        ItemStack one = s.split(1);
                        c.setChanged();
                        return one;
                    }
                }
            }
        }
        for (Item it : order) {
            if (Market.stock(level, v.id(), s -> s.is(it)) <= 12) continue;
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(it));
            if (!one.isEmpty()) return one;
        }
        return ItemStack.EMPTY;
    }

    /**
     * A hoe's stroke: its own hoe if it carries one, else one of the stores' borrowed and put back. Either is
     * the worse for it, a stroke at a time, as in a player's hand. False with no hoe to be had.
     */
    static boolean hoe(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) {
            if (s.getItem() instanceof HoeItem) {
                wear(s);
                return true;
            }
        }
        ItemStack borrowed = Crafts.takeOne(level, v, s -> s.getItem() instanceof HoeItem);
        if (borrowed.isEmpty()) return false;
        wear(borrowed);
        if (!borrowed.isEmpty()) Crafts.store(level, v, borrowed);
        return true;
    }

    private static void wear(ItemStack hoe) {
        hoe.setDamageValue(hoe.getDamageValue() + 1);
        if (hoe.getDamageValue() >= hoe.getMaxDamage()) hoe.shrink(1);
    }

    /** What it picked tonight, out of its pack and into the household's chest. Returns how many went in. */
    static int bringHome(ServerLevel level, Villages.Village v, VillageFolkEntity f, Homes.Home home, Map<Item, Integer> picked) {
        BlockPos chest = Homes.chestOf(level, v.id(), home);
        if (chest == null || !(level.getBlockEntity(chest) instanceof Container c)) return 0;
        int moved = 0;
        for (Map.Entry<Item, Integer> e : picked.entrySet()) {
            Item it = e.getKey();
            int want = Math.min(e.getValue(), f.countCarried(s -> s.is(it)));
            if (want <= 0) continue;
            int took = f.removeMatching(s -> s.is(it), want);
            ItemStack left = Homes.insertInto(c, new ItemStack(it, took));
            if (!left.isEmpty()) f.insertItem(left);
            moved += took - left.getCount();
        }
        c.setChanged();
        picked.clear();
        return moved;
    }

    // ------------------------------------------------------------------ an evening at the plot

    /** The household's plot for this folk, as {building, plot}, or null. */
    @Nullable
    static Object[] plotFor(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return null;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        return h == null ? null : plotOf(village, h.anchor.asLong());
    }

    /**
     * Some evenings (VillageFolkEntity.eveningSocial, after supper): to the household's plot, an hour's work
     * on it, and home with what came up. True while it has the folk.
     */
    static boolean evening(VillageFolkEntity f, long t) {
        if (f.isBaby() || !(f.level() instanceof ServerLevel level) || f.ownerId() == null) return false;
        UUID village = f.ownerId();
        long day = level.getDayTime() / 24000L;
        Evening e = EVENINGS.get(f.getUUID());
        if (e != null && e.day != day) {
            EVENINGS.remove(f.getUUID());
            e = null;
        }
        if (e == null) {
            if (t >= 13600L || level.isRaining()) return false;
            if (Math.floorMod((int) (day * 7L) + f.getUUID().hashCode(), 3) == 0) return false;   // two evenings in three
            Object[] plot = plotFor(f);
            if (plot == null) return false;
            Ledger.Building b = (Ledger.Building) plot[0];
            int i = (int) plot[1];
            if (!needsWork(level, b, i)) return false;                     // all of it growing: nothing to go out for
            String turn = b.anchor().asLong() + "/" + i + "/" + day;
            UUID who = TONIGHT.putIfAbsent(turn, f.getUUID());
            if (who != null && !who.equals(f.getUUID())) return false;     // one of the household is at it already
            e = new Evening(day, b, i, f.tickCount);
            EVENINGS.put(f.getUUID(), e);
        }
        if (e.stage >= 3) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        Homes.Home home = Homes.homeOf(village, f.getUUID());
        f.lastLeisureTick = f.tickCount;
        if (e.stage == 0) {
            BlockPos at = stand(e.b, e.plot);
            if (f.blockPosition().distSqr(at) > 9.0) {
                f.hobbyNow = "walking out to its allotment";
                if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 100) {
                    f.walkTo(at, 0.85D);
                    e.walkTick = f.tickCount;
                }
                if (f.tickCount - e.started > 2400) e.stage = 3;                // could not get there tonight
                return true;
            }
            f.getNavigation().stop();
            e.stage = 1;
        }
        if (e.stage == 1) {
            f.getLookControl().setLookAt(middle(e.b, e.plot));
            if (f.tickCount - e.workTick < PACE) return true;
            e.workTick = f.tickCount;
            String did = t < 13600L ? work(level, v, f, e.b, e.plot, e.picked, home) : null;
            if (did != null) {
                f.hobbyNow = did;
                return true;
            }
            e.stage = e.picked.isEmpty() || home == null ? 3 : 2;
            if (e.stage == 2) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That's a good few for the pot.",
                "Not bad for a little plot.", "Home with these, then."));
        }
        if (e.stage == 2 && home != null) {
            BlockPos chest = Homes.chestOf(level, village, home);
            if (chest == null) {
                e.stage = 3;
                return false;
            }
            if (f.blockPosition().distSqr(chest) > 9.0) {
                f.hobbyNow = "taking its vegetables home";
                if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 100) {
                    f.walkTo(chest, 0.9D);
                    e.walkTick = f.tickCount;
                }
                return true;
            }
            int n = bringHome(level, v, f, home, e.picked);
            if (n > 0) f.persona().remember(day, "brought " + n + " from the allotment home", 1);
            e.stage = 3;
            return false;
        }
        return false;
    }

    private static net.minecraft.world.phys.Vec3 middle(Ledger.Building b, int plot) {
        BlockPos c = TownLook.cell(b, PLOTS[plot][0] + 1, 0, PLOTS[plot][1] + 1);
        return net.minecraft.world.phys.Vec3.atCenterOf(c);
    }

    // ------------------------------------------------------------------ where a player sees it

    /** The folk's card: its household's plot, and what is growing on it. */
    @Nullable
    static String cardPart(VillageFolkEntity f) {
        Object[] plot = plotFor(f);
        if (!(f.level() instanceof ServerLevel level) || plot == null) return null;
        Ledger.Building b = (Ledger.Building) plot[0];
        int i = (int) plot[1];
        if (!level.isLoaded(b.anchor())) return "an allotment by the fields, plot " + (i + 1);
        int growing = 0, ready = 0;
        for (BlockPos p : soil(b, i)) {
            BlockState st = level.getBlockState(p.above());
            if (st.getBlock() instanceof CropBlock) growing++;
            if (ripe(st)) ready++;
        }
        Evening e = EVENINGS.get(f.getUUID());
        String now = e != null && e.stage < 3 && f.hobbyNow != null ? " (" + f.hobbyNow + ")" : "";
        return "an allotment by the fields, plot " + (i + 1) + ": " + growing + " growing, " + ready + " ready" + now;
    }

    static String line(ServerLevel level, Villages.Village v) {
        List<Ledger.Building> all = allotments(v.id());
        if (all.isEmpty()) return gardenless(v.id()) + " households with no garden; no allotments yet";
        int let = 0, growing = 0;
        for (Ledger.Building b : all) {
            for (int i = 0; i < PLOTS.length; i++) {
                if (holder(v.id(), b, i) != -1L) let++;
                if (!level.isLoaded(b.anchor())) continue;
                for (BlockPos p : soil(b, i)) if (level.getBlockState(p.above()).getBlock() instanceof CropBlock) growing++;
            }
        }
        return let + " of " + all.size() * PLOTS.length + " plots let, " + growing + " crops in the ground";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the plots let to the households with no garden, as the town's rounds let them. Returns how many changed. */
    public static int letForTests(UUID village) {
        int n = 0;
        while (n < 16 && let(village)) n++;
        return n;
    }

    /** Tests: the plot's earth this folk's household has, or an empty list. */
    public static List<BlockPos> plotForTests(VillageFolkEntity f) {
        Object[] plot = plotFor(f);
        return plot == null ? List.of() : soil((Ledger.Building) plot[0], (int) plot[1]);
    }

    /**
     * Tests: an evening's work on the household's plot done where the folk stands (no walk out), up to so
     * many things, then what came up carried straight to the household's chest. Returns {things done, put in the chest}.
     */
    public static int[] tendForTests(ServerLevel level, VillageFolkEntity f, int most) {
        Object[] plot = plotFor(f);
        Villages.Village v = Villages.get(f.ownerId());
        if (plot == null || v == null) return new int[]{ 0, 0 };
        Homes.Home home = Homes.homeOf(v.id(), f.getUUID());
        Map<Item, Integer> picked = new HashMap<>();
        int done = 0;
        while (done < most && work(level, v, f, (Ledger.Building) plot[0], (int) plot[1], picked, home) != null) done++;
        int home_ = home == null ? 0 : bringHome(level, v, f, home, picked);
        return new int[]{ done, home_ };
    }

    /** Tests: does this folk's household hold a plot? */
    public static boolean hasPlotForTests(VillageFolkEntity f) {
        return plotFor(f) != null;
    }
}
