package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.FeedTroughBlock;
import com.jrpetty.mcassistant.block.FieldBlockEntity;
import com.jrpetty.mcassistant.block.FishTrapBlock;
import com.jrpetty.mcassistant.block.NestingBoxBlock;
import com.jrpetty.mcassistant.block.RainBarrelBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.BeeSmokerItem;
import com.jrpetty.mcassistant.item.FieldItems;
import com.jrpetty.mcassistant.item.SeedSatchelItem;
import com.jrpetty.mcassistant.item.SickleItem;
import com.jrpetty.mcassistant.item.WateringCanItem;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fields] The tools of the fields and the pens, made and used by the folk (item/FieldItems): nothing from nothing, all
 * of it out of the town's stores, and copper put to use at last.
 * <ul>
 * <li><b>The makers.</b> The smith beats the watering cans, the sickles and the bee smokers out of the stores' copper;
 *     the tailor stitches the seed satchels; the rancher knocks up the nesting boxes and the feed troughs for its own
 *     ground; the fisher weaves its fish traps; the shop's workshop makes any of them for the town (its order book has
 *     them), and the rain barrels. A Stone Age town has neither smith nor shop: its smelter, who has the copper at its
 *     furnace, beats the copper tools cold; with no tailor the rancher, who has the hides, stitches a satchel; and with
 *     no shop an idle hand coopers a barrel at the bench, on the town's works. Every one by its real recipe (Bench),
 *     when the town wants one: a can, a sickle and a satchel for every farmer, a smoker for every beekeeper, a box and
 *     a trough for the pen, two traps a fisher (four at the most), a barrel by every workshop and every field.</li>
 * <li><b>The farmer</b> takes its can, its sickle and its satchel out of the stores. It waters its field a three-by-three
 *     at a time as it goes (the patch nearest it that is driest, or still growing), and fills the can at a rain barrel
 *     or the nearest water when it runs dry; in a drought it waters the dry farmland with the can instead of carrying a
 *     bucket (Droughts), and a bucket-carrier draws from the barrels first. Its satchel is filled at the stores and
 *     hands it seed as it sows, so a field is sown in a trip (and it is the quicker for it, on its pace line); its sickle
 *     reaps the three-by-three round every ripe crop it cuts (FarmGoal). Of an evening it waters its house's garden and
 *     window boxes.</li>
 * <li><b>The rancher</b> sets a nesting box and a feed trough out on its ground. Hens lay into the box (and more often
 *     on fresh hay); the rancher empties it into the stores, where the cook finds the eggs for its cakes and pies. It
 *     fills the trough with the feed for what is in the pen; the animals keep near it, and a pair now and then breeds
 *     off it, eating real feed, so the rancher seldom has to walk about with wheat held out.</li>
 * <li><b>The fisher</b> sets two traps in the water by its fishing ground and empties them on the days the boats stay in
 *     (the rain, the day of rest), or when passing, into the fish market or the stores.</li>
 * <li><b>The beekeeper</b> with a smoker smokes every full hive on its round and takes them all, a comb or a bottle the
 *     more for each, and the bees never anger.</li>
 * <li><b>The barrels</b> catch the rain: the cans and the fire brigade's buckets are filled at them, a bucket chain draws
 *     from one when there is no pond near the fire, and a workshop with no iron for a cauldron of water against fire has
 *     a barrel set by it instead (FireSafety).</li>
 * </ul>
 * Everything is looked up on the folk's own rounds (every five seconds for each), from the blocks' own index
 * (FieldBlockEntity), never by looking over the ground.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FieldTools {

    private FieldTools() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** A folk's round with its tools every so many seconds. */
    static final int ROUND_SECONDS = 5;
    /** Its tools looked for in the stores at most this often (ticks). */
    static final long KIT_EVERY = 1200L;
    /** A square watered is not watered again for this long. */
    static final long PATCH_AGAIN = 6000L;
    /** A patch watered at most this often by one farmer, out of a drought (ticks). */
    static final long POUR_EVERY = 190L;
    /** A trade outside the crafts makes a piece at most this often (ticks). */
    static final long MAKE_EVERY = 2400L;
    /** The town's barrels looked to this often. */
    static final long TOWN_EVERY = 600L;
    /** How far from a field its water may be (a barrel, or open water). */
    static final int WATER_REACH = 32;
    /** A hen lays into a box this near; a trough's pull, and its breeding's reach. */
    static final int NEST_REACH = 6, TROUGH_REACH = 8, TROUGH_PULL = 12;
    /** The pen's limit off the trough: of a kind, and in all. */
    static final int HERD_LIMIT = VillageFolkEntity.HERD_KEPT * 2, PEN_MOST = 16;
    /** A pair breeds off a trough at most this often. */
    static final long TROUGH_BREED_EVERY = 600L;
    /** A fisher's traps, and the town's most. */
    static final int TRAPS_A_FISHER = 2, TRAPS_MOST = 4;
    /** Seed in hand below which the satchel hands out more, and a handful. */
    static final int SEED_IN_HAND = 12, SEED_HANDFUL = 20;
    /** Seed the farmer's own trip to the stores would have fetched (VillageFolkEntity.kitFromTheStores). */
    static final int A_TRIP = 16;
    /** A satchel with seed in it: the sowing goes this much the quicker (the pace line). */
    static final int SATCHEL_PACE = 5;
    /** An errand gives up after this long (ticks). */
    static final long ERRAND_MOST = 1800L;
    /** The town's works for the barrels (TownJobs). */
    static final String WORKS = "fields";

    // ------------------------------------------------------------------ the state

    /** A folk's day with its tools, for its card. */
    static final class Tally {
        long day = Long.MIN_VALUE;
        int watered, dry, sown, reaped, eggs, fed, bred, fish, hives, gardens, made;
        long poured = -100000L, reapedAt = -100000L;
    }

    private static final Map<UUID, Tally> TALLY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> KIT = new ConcurrentHashMap<>(), MADE = new ConcurrentHashMap<>(), GARDENED = new ConcurrentHashMap<>();
    /** Squares watered lately (farmland, by where), and when. */
    private static final Map<Long, Long> WATERED = new ConcurrentHashMap<>();
    /** A tool shown in a folk's free hand, till when. */
    private static final Map<UUID, Long> SHOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Object[]> WANTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TOWN_LOOKED = new ConcurrentHashMap<>();
    /** A field's open water (by its middle), found once; and fields looked at for water and found dry, and when. */
    private static final Map<Long, BlockPos> WATER_AT = new ConcurrentHashMap<>();
    private static final Map<Long, Long> NO_WATER = new ConcurrentHashMap<>();
    /** When each trough last bred a pair. */
    private static final Map<Long, Long> BRED_AT = new ConcurrentHashMap<>();
    private static volatile boolean demanded;
    /** Tests: the rounds whatever the hour, and the town settled at once, till this game time (it lapses of itself, so a
     *  test that stops short leaves no other test's town at its mercy). */
    private static volatile long testingUntil = Long.MIN_VALUE;
    /** When each town was first seen: its folk go about with these tools a minute after (a town just founded, or a
     *  world just loaded, settles first). */
    private static final Map<UUID, Long> FIRST_SEEN = new ConcurrentHashMap<>();
    static final long SETTLE = 1200L;

    public static void resetForTests() {
        TALLY.clear();
        KIT.clear();
        MADE.clear();
        GARDENED.clear();
        WATERED.clear();
        SHOWN.clear();
        ERRANDS.clear();
        WANTS.clear();
        TOWN_LOOKED.clear();
        WATER_AT.clear();
        NO_WATER.clear();
        BRED_AT.clear();
        BeeSmokerItem.resetForTests();
        testingUntil = Long.MIN_VALUE;
        FIRST_SEEN.clear();
    }

    static Tally tally(VillageFolkEntity f) {
        Tally t = TALLY.computeIfAbsent(f.getUUID(), k -> new Tally());
        long day = f.level().getDayTime() / 24000L;
        if (t.day != day) {
            Tally fresh = new Tally();
            fresh.day = day;
            TALLY.put(f.getUUID(), fresh);
            return fresh;
        }
        return t;
    }

    // ------------------------------------------------------------------ the server's round

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        com.jrpetty.mcassistant.Guard.run("the tools of the fields", () -> tick(event.getServer()));
    }

    static void tick(MinecraftServer server) {
        int t = server.getTickCount();
        if (t % 10 == 3) calmTheBees(server);
        if (t % 20 != 13) return;
        demandOnce();
        for (ServerLevel level : server.getAllLevels()) {
            boxes(level);
            troughs(level, t % 40 == 13);
            FieldsStage.tick(level);
        }
        for (Villages.Village v : Villages.every()) {
            ServerLevel level = server.getLevel(v.dim());
            if (level == null || !settled(level, v.id())) continue;
            long now = level.getGameTime();
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || f.showcaseFolk() || f.isBaby() || !f.isAlive()) continue;
                putBack(f, now);
                // A sower's hand kept in seed from its satchel every second (its work goes quicker than the round).
                if (f.stationTask() == StationTask.FARM && !f.isSleeping()) {
                    ItemStack bag = carried(f, FieldItems.SEED_SATCHEL.get());
                    if (!bag.isEmpty()) handOut(f, bag);
                }
                if (Math.floorMod(f.getUUID().hashCode() + t / 20, ROUND_SECONDS) != 0) continue;
                round(level, v, f, now);
            }
            if (now - TOWN_LOOKED.getOrDefault(v.id(), -100000L) >= TOWN_EVERY || now < TOWN_LOOKED.getOrDefault(v.id(), 0L)) {
                TOWN_LOOKED.put(v.id(), now);
                town(level, v);
            }
        }
    }

    /** One folk's round with its tools. */
    static void round(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        if (ERRANDS.containsKey(f.getUUID()) || f.isSleeping() || f.getTarget() != null || Fleet.out(f)) return;
        switch (f.stationTask()) {
            case FARM -> farmer(level, v, f, now);
            case RANCH -> rancher(level, v, f, now);
            case FISH -> fisher(level, v, f, now);
            case BEEKEEP -> kit(level, v, f, now, FieldItems.BEE_SMOKER.get());
            case SMELT -> smelter(level, v, f, now);
            default -> { }
        }
    }

    /** A test at work (its rounds whatever the hour, its town settled at once)? */
    static boolean testing(net.minecraft.world.level.Level level) {
        long now = level.getGameTime();
        return now < testingUntil && testingUntil - now <= 1200L;
    }

    /** Has the town been about a minute (or is a test at it)? Till then nothing here touches it. */
    static boolean settled(ServerLevel level, UUID village) {
        if (testing(level)) return true;
        long now = level.getGameTime();
        Long seen = FIRST_SEEN.putIfAbsent(village, now);
        return seen != null && (now - seen >= SETTLE || now < seen);
    }

    /** Is it about its work just now (or, for the tests, at any hour)? */
    static boolean atWork(VillageFolkEntity f) {
        return testing(f.level()) || f.onShift() && !f.offWorkNow();
    }

    // ------------------------------------------------------------------ its tools, in hand and in the pack

    static boolean ours(ItemStack s) {
        return s.is(FieldItems.WATERING_CAN.get()) || s.is(FieldItems.SEED_SATCHEL.get()) || s.is(FieldItems.COPPER_SICKLE.get())
            || s.is(FieldItems.BEE_SMOKER.get());
    }

    /** The tool itself, wherever it carries it (its free hand first): empty if it has none. */
    static ItemStack carried(AssistantEntity f, Item tool) {
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(tool)) return off;
        ItemStack main = f.getItemBySlot(EquipmentSlot.MAINHAND);
        if (main.is(tool)) return main;
        for (ItemStack s : f.getInventoryItems()) if (s.is(tool)) return s;
        return ItemStack.EMPTY;
    }

    static boolean has(AssistantEntity f, Item tool) {
        return !carried(f, tool).isEmpty();
    }

    /** One of these out of the stores into its pack, if it has none and the stores do (the stack itself, its marks and all). */
    static boolean kit(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now, Item tool) {
        if (has(f, tool)) return true;
        if (Market.stock(level, v.id(), s -> s.is(tool)) <= 0) return false;
        ItemStack one = Crafts.takeOne(level, v, s -> s.is(tool));
        if (one.isEmpty()) return false;
        ItemStack left = f.insertItem(one);
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);
            return false;
        }
        f.brain("took " + Bench.words(tool, 1) + " out of the stores");
        return true;
    }

    /** Its tools kept, never banked into the stores (Trades.keeps): one of each its trade uses, and a fisher's traps to set. */
    public static int keeps(StationTask t, ItemStack s) {
        if (!FieldItems.WATERING_CAN.isBound()) return 0;
        return switch (t) {
            case FARM -> s.is(FieldItems.WATERING_CAN.get()) || s.is(FieldItems.SEED_SATCHEL.get()) || s.is(FieldItems.COPPER_SICKLE.get()) ? 1 : 0;
            case BEEKEEP -> s.is(FieldItems.BEE_SMOKER.get()) ? 1 : 0;
            case FISH -> s.is(FieldItems.FISH_TRAP_ITEM.get()) ? TRAPS_A_FISHER : 0;
            case RANCH -> s.is(FieldItems.NESTING_BOX_ITEM.get()) || s.is(FieldItems.FEED_TROUGH_ITEM.get()) ? 1 : 0;
            default -> 0;
        };
    }

    /**
     * The tool in its free hand for a while, where it can be seen (the folk's model draws what is in its hands): the can
     * as it waters, the sickle as it reaps, the smoker at the hives. Not while its hand holds something of its own (a
     * light after dark, the watch's shield, a banner).
     */
    static void show(VillageFolkEntity f, Item tool, int ticks) {
        long until = f.level().getGameTime() + ticks;
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(tool)) {
            SHOWN.put(f.getUUID(), until);
            return;
        }
        if (!off.isEmpty() && !ours(off)) return;
        if (!off.isEmpty()) {
            ItemStack left = f.insertItem(off.copy());
            f.setItemSlot(EquipmentSlot.OFFHAND, left);
            if (!left.isEmpty()) return;
        }
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (!pack.get(i).is(tool)) continue;
            f.setItemSlot(EquipmentSlot.OFFHAND, pack.get(i));
            pack.set(i, ItemStack.EMPTY);
            SHOWN.put(f.getUUID(), until);
            return;
        }
    }

    /** The tool shown back into its pack when its moment is over (and one left in its hand across a restart, too: its
     *  hand is wanted for its light after dark). */
    static void putBack(VillageFolkEntity f, long now) {
        Long until = SHOWN.get(f.getUUID());
        if (until != null && now < until && now >= until - 6000L) return;
        if (until != null) SHOWN.remove(f.getUUID());
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!ours(off)) return;
        ItemStack left = f.insertItem(off.copy());
        f.setItemSlot(EquipmentSlot.OFFHAND, left);
    }

    /** Wear a tool by one use, as a hand that looks after its tools wears it (one in three charged, as the hoe). */
    static void wear(VillageFolkEntity f, ItemStack tool) {
        if (tool.isEmpty() || !tool.isDamageableItem()) return;
        if (f.getRandom().nextInt(3) != 0) return;
        tool.setDamageValue(tool.getDamageValue() + 1);
        if (tool.getDamageValue() >= tool.getMaxDamage()) {
            String name = tool.getHoverName().getString().toLowerCase(Locale.ROOT);
            tool.shrink(1);
            f.playSound(SoundEvents.ITEM_BREAK, 0.8F, 1.0F);
            f.brain("its " + name + " wore out");
        }
    }

    // ------------------------------------------------------------------ the farmer

    static void farmer(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        if (now - KIT.getOrDefault(f.getUUID(), -100000L) >= KIT_EVERY || now < KIT.getOrDefault(f.getUUID(), 0L)) {
            KIT.put(f.getUUID(), now);
            kit(level, v, f, now, FieldItems.WATERING_CAN.get());
            kit(level, v, f, now, FieldItems.COPPER_SICKLE.get());
            kit(level, v, f, now, FieldItems.SEED_SATCHEL.get());
        }
        satchel(level, v, f);
        if (atWork(f) && refill(level, v, f, now)) return;
        long tod = level.getDayTime() % 24000L;
        if (atWork(f)) can(level, v, f, now);
        else if (tod >= 11000L && tod < 12700L && !f.isSleeping()) garden(level, v, f, now);
    }

    // --- the satchel

    /** The seed the satchel takes, in the order a field is sown. */
    private static final Item[] SEEDS = { Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS };

    /** At the stores the satchel is packed; out in the field it hands seed to the farmer as it runs low. */
    static void satchel(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        ItemStack bag = carried(f, FieldItems.SEED_SATCHEL.get());
        if (bag.isEmpty()) return;
        BlockPos stores = f.storesSpot(level, v.id());
        if (stores != null && stores.distSqr(f.blockPosition()) <= 10 * 10 && SeedSatchelItem.total(bag) < SeedSatchelItem.MOST / 2) {
            pack(level, v, f, bag);
        }
        handOut(f, bag);
    }

    /**
     * The satchel run low and its hands nearly empty of seed: one trip to the stores to fill it (an errand), and the
     * whole field sown off that. Not for a handful: only while the stores hold two dozen seed of the field's kinds.
     */
    static boolean refill(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        ItemStack bag = carried(f, FieldItems.SEED_SATCHEL.get());
        if (bag.isEmpty() || SeedSatchelItem.total(bag) >= A_TRIP || f.countMatching(AssistantEntity.FARM_SEEDS) >= SEED_IN_HAND) return false;
        BlockPos stores = f.storesSpot(level, v.id());
        if (stores == null || stores.distSqr(f.blockPosition()) <= 10 * 10) return false;
        int stock = 0;
        for (Item seed : SEEDS) stock += Math.max(0, Market.stock(level, v.id(), s -> s.is(seed)) - 12);
        if (stock < 24) return false;
        errand(f, Do.PACK, List.of(stores), "off to the stores to fill its seed satchel", now);
        return true;
    }

    /**
     * The satchel packed at the stores: of each seed the field grows, the commonest first, half of what the stores hold
     * (the other farmers' share and the kitchen's carrots and potatoes left), never their last twelve, a stack at most.
     * Returns the seed packed.
     */
    static int pack(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack bag) {
        List<Item> order = new ArrayList<>(List.of(SEEDS));
        order.sort((a, b) -> Integer.compare(Market.stock(level, v.id(), s -> s.is(b)), Market.stock(level, v.id(), s -> s.is(a))));
        int packed = 0;
        for (Item seed : order) {
            int stock = Market.stock(level, v.id(), s -> s.is(seed));
            int n = Math.min(64 - SeedSatchelItem.count(bag, s -> s.is(seed)) % 64, Math.min(stock / 2, stock - 12));
            if (n <= 0) continue;
            ItemStack trial = bag.copy();
            ItemStack left = SeedSatchelItem.add(trial, new ItemStack(seed, n));
            n -= left.getCount();
            if (n <= 0 || !Crafts.take(level, v, s -> s.is(seed), n)) continue;
            SeedSatchelItem.add(bag, new ItemStack(seed, n));
            packed += n;
        }
        if (packed > 0) {
            f.swing(InteractionHand.MAIN_HAND);
            f.brain("packed its seed satchel at the stores: " + packed + " seed");
            show(f, FieldItems.SEED_SATCHEL.get(), 40);
        }
        return packed;
    }

    /** Seed from the satchel into its hand as it runs low, a handful at a time: no walk back to the stores for it. */
    static int handOut(VillageFolkEntity f, ItemStack bag) {
        int inHand = f.countMatching(AssistantEntity.FARM_SEEDS);
        if (inHand >= SEED_IN_HAND || SeedSatchelItem.total(bag) <= 0) return 0;
        // The kind the satchel holds most of (the field's mix is the stores' mix: FarmGoal.pickSeed).
        Item most = null;
        int best = 0;
        for (Item seed : SEEDS) {
            int n = SeedSatchelItem.count(bag, s -> s.is(seed));
            if (n > best) { best = n; most = seed; }
        }
        if (most == null) return 0;
        final Item kind = most;
        ItemStack out = SeedSatchelItem.take(bag, s -> s.is(kind), SEED_HANDFUL);
        if (out.isEmpty()) return 0;
        ItemStack left = f.insertItem(out.copy());
        if (!left.isEmpty()) SeedSatchelItem.add(bag, left);
        int n = out.getCount() - left.getCount();
        if (n <= 0) return 0;
        Tally t = tally(f);
        t.sown += n;
        Villages.Village v = Villages.get(f.ownerId());
        if (v != null && t.sown >= 64 && f.level() instanceof ServerLevel level) {
            first(level, v, "satchel", f.displayNameCap() + " sowed a whole field on one trip to the stores, the seed in a satchel at its hip");
        }
        show(f, FieldItems.SEED_SATCHEL.get(), 60);
        f.brain("took " + n + " seed out of its satchel: no trip to the stores for it");
        return n;
    }

    /** The seed in its satchel (VillageFolkEntity.kitShort: a farmer with seed at its hip is not short of it). */
    public static int satchelSeed(AssistantEntity f) {
        if (!FieldItems.SEED_SATCHEL.isBound()) return 0;
        ItemStack bag = carried(f, FieldItems.SEED_SATCHEL.get());
        return bag.isEmpty() ? 0 : SeedSatchelItem.total(bag);
    }

    /** Its satchel's seed, for its pace: a farmer with seed at its hip sows on without a break. */
    public static int workPercent(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.FARM || !FieldItems.SEED_SATCHEL.isBound()) return 0;
        ItemStack bag = carried(f, FieldItems.SEED_SATCHEL.get());
        return !bag.isEmpty() && SeedSatchelItem.total(bag) >= A_TRIP ? SATCHEL_PACE : 0;
    }

    public static String paceWord() {
        return "its seed satchel";
    }

    // --- the can

    /** At its field: a patch watered (the driest near it, or one still growing), or off to fill the can when it is dry. */
    static void can(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        ItemStack can = carried(f, FieldItems.WATERING_CAN.get());
        WorkZone z = f.workZone();
        if (can.isEmpty() || z == null) return;
        boolean drought = Droughts.on(v.id());
        if (WateringCanItem.water(can) <= 0) {
            fetchWater(level, f, z);
            return;
        }
        if (!z.containsColumn(f.blockPosition())) return;
        // A patch every ten seconds or so as it goes about the field (every round in a drought): a help, not a flood.
        Tally t = TALLY.get(f.getUUID());
        if (!drought && t != null && now - t.poured < POUR_EVERY && now >= t.poured) return;
        BlockPos patch = patch(level, f, z, drought, now);
        if (patch == null && drought) {
            // Dry ground on its field, but not within reach: it walks out to the driest of it with the can.
            BlockPos dry = Droughts.driest(level, z);
            if (dry != null && !recent(dry, now)) errand(f, Do.POUR, List.of(dry), "taking the watering can out to the driest of its field", now);
            return;
        }
        if (patch != null) pourAt(level, v, f, patch, drought, now);
    }

    /** Was this square watered lately? */
    static boolean recent(BlockPos farmland, long now) {
        Long at = WATERED.get(farmland.asLong());
        return at != null && now - at < PATCH_AGAIN && now >= at;
    }

    /**
     * The patch to water near it (within four blocks, on its own field): farmland under a growing crop first, the driest
     * first, none watered lately; in a drought the dry squares above all. Null if none wants it.
     */
    @Nullable
    static BlockPos patch(ServerLevel level, VillageFolkEntity f, WorkZone z, boolean drought, long now) {
        BlockPos at = f.blockPosition();
        BlockPos best = null;
        int bestScore = 1;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos g = at.offset(dx, dy, dz);
                    BlockState gs = level.getBlockState(g);
                    if (!(gs.getBlock() instanceof FarmBlock) || !z.containsColumn(g) || recent(g, now)) continue;
                    BlockState crop = level.getBlockState(g.above());
                    boolean growing = crop.getBlock() instanceof CropBlock c && !c.isMaxAge(crop);
                    int m = gs.getValue(FarmBlock.MOISTURE);
                    int dry = m == 0 ? 3 : m < FarmBlock.MAX_MOISTURE ? 1 : 0;
                    int score = (growing ? 3 : 0) + dry * (drought ? 3 : 1) - (Math.abs(dx) + Math.abs(dz)) / 4;
                    if (!growing && !drought) continue;                     // nothing growing there wants it
                    if (score > bestScore) { bestScore = score; best = g.immutable(); }
                }
            }
        }
        return best;
    }

    /** The can tipped over a patch: its three-by-three soaked and given its growth; the squares remembered. */
    static WateringCanItem.Poured pourAt(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos patch, boolean drought, long now) {
        ItemStack can = carried(f, FieldItems.WATERING_CAN.get());
        if (can.isEmpty() || WateringCanItem.water(can) <= 0) return new WateringCanItem.Poured(0, 0, 0);
        show(f, FieldItems.WATERING_CAN.get(), 50);
        can = carried(f, FieldItems.WATERING_CAN.get());
        WateringCanItem.Poured p = WateringCanItem.pour(level, patch);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) WATERED.put(patch.offset(dx, 0, dz).asLong(), now);
        if (WATERED.size() > 4096) WATERED.values().removeIf(t -> now - t > PATCH_AGAIN || now < t);
        if (!p.any()) return p;
        WateringCanItem.setWater(can, WateringCanItem.water(can) - 1);
        f.getLookControl().setLookAt(patch.getX() + 0.5, patch.getY() + 1.0, patch.getZ() + 0.5);
        f.swing(InteractionHand.OFF_HAND);
        Tally t = tally(f);
        t.watered++;
        t.poured = now;
        if (drought && p.soaked() > 0) {
            t.dry++;
            first(level, v, "drought-can", f.displayNameCap() + " kept its field green through the drought with a copper watering can");
        }
        f.brain("watered a patch of its field with the copper can (" + p.grew() + " of " + p.plants() + " grew)");
        if (f.getRandom().nextInt(12) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — a good drink for you.", "Grow on, now.",
                drought ? "Not a cloud in the sky. The can'll have to do." : "A drop of water goes a long way."));
        }
        return p;
    }

    /** The can dry: to the nearest rain barrel with water in it, else the field's own open water, to fill it. */
    static void fetchWater(ServerLevel level, VillageFolkEntity f, WorkZone z) {
        BlockPos barrel = barrelWithWater(level, z.center(), WATER_REACH);
        BlockPos from = barrel != null ? barrel : waterFor(level, z.center(), level.getGameTime());
        if (from == null) return;
        errand(f, Do.FILL, List.of(from), "filling its watering can at " + (barrel != null ? "the rain barrel" : "the water"), level.getGameTime());
    }

    /** The nearest rain barrel with water in it within reach of here (from the blocks' index), or null. */
    @Nullable
    static BlockPos barrelWithWater(ServerLevel level, BlockPos near, int reach) {
        for (BlockPos p : FieldBlockEntity.near(level, FieldBlockEntity.Kind.BARREL, near, reach)) {
            if (RainBarrelBlock.water(level, p) > 0) return p;
        }
        return null;
    }

    /** A field's open water, looked for once (it is a long look) and again only if it has gone. */
    @Nullable
    static BlockPos waterFor(ServerLevel level, BlockPos field, long now) {
        long key = field.asLong();
        BlockPos w = WATER_AT.get(key);
        if (w != null && level.getFluidState(w).is(FluidTags.WATER)) return w;
        Long dry = NO_WATER.get(key);
        if (dry != null && now - dry < 6000L && now >= dry) return null;
        w = Droughts.water(level, field, WATER_REACH);
        if (w == null) {
            NO_WATER.put(key, now);
            return null;
        }
        WATER_AT.put(key, w);
        return w;
    }

    /** The can filled where it stands: a bucket's worth from the barrel, or dipped in the water. */
    static boolean fillCan(ServerLevel level, VillageFolkEntity f, BlockPos at) {
        ItemStack can = carried(f, FieldItems.WATERING_CAN.get());
        if (can.isEmpty()) return false;
        boolean barrel = level.getBlockState(at).getBlock() instanceof RainBarrelBlock;
        if (barrel ? !RainBarrelBlock.draw(level, at) : !level.getFluidState(at).is(FluidTags.WATER)) return false;
        WateringCanItem.fill(can);
        show(f, FieldItems.WATERING_CAN.get(), 40);
        f.swing(InteractionHand.OFF_HAND);
        if (!barrel) level.playSound(null, at, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 0.7F, 1.3F);
        f.brain("filled its watering can at " + (barrel ? "the rain barrel" : "the water"));
        return true;
    }

    // --- the garden, of an evening

    /** Of an evening, its house's garden and window boxes watered from the can (an hour of its own it enjoys). */
    static void garden(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        long day = level.getDayTime() / 24000L;
        if (GARDENED.getOrDefault(f.getUUID(), Long.MIN_VALUE) == day) return;
        ItemStack can = carried(f, FieldItems.WATERING_CAN.get());
        if (can.isEmpty() || WateringCanItem.water(can) < 2) return;
        BlockPos home = Homes.homeOf(f);
        if (home == null || !level.isLoaded(home)) return;
        GARDENED.put(f.getUUID(), day);
        List<BlockPos> spots = gardenSpots(level, v, home);
        if (spots.isEmpty()) return;
        errand(f, Do.GARDEN, spots, "watering the garden with its copper can", now);
    }

    /**
     * What a house has to water: its garden (the flowers and the young tree before it, a patch of up to three by three
     * each, up to three patches) and its window boxes' pots. Looked over once an evening, round the house only.
     */
    static List<BlockPos> gardenSpots(ServerLevel level, Villages.Village v, BlockPos home) {
        List<BlockPos> out = new ArrayList<>();
        if (Families.gardened(v.id(), home.asLong())) {
            for (BlockPos p : BlockPos.betweenClosed(home.offset(-9, -2, -9), home.offset(9, 3, 9))) {
                BlockState st = level.getBlockState(p);
                if (!st.is(BlockTags.SMALL_FLOWERS) && !st.is(BlockTags.SAPLINGS)) continue;
                boolean apart = true;
                for (BlockPos o : out) if (o.distManhattan(p) < 3) apart = false;
                if (apart) out.add(p.immutable());
                if (out.size() >= 3) break;
            }
        }
        Ledger.Building b = Homes.building(v.id(), home);
        if (b != null) {
            for (StreetFurniture.Box box : StreetFurniture.boxes(b)) {
                BlockState pot = level.getBlockState(box.pot());
                if (pot.is(BlockTags.FLOWER_POTS) && !pot.is(net.minecraft.world.level.block.Blocks.FLOWER_POT)) out.add(box.pot());
            }
        }
        return out;
    }

    static void gardenPour(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos at) {
        ItemStack can = carried(f, FieldItems.WATERING_CAN.get());
        if (can.isEmpty() || WateringCanItem.water(can) <= 0) return;
        show(f, FieldItems.WATERING_CAN.get(), 50);
        can = carried(f, FieldItems.WATERING_CAN.get());
        WateringCanItem.Poured p = WateringCanItem.pour(level, at);
        if (!p.any()) return;
        WateringCanItem.setWater(can, WateringCanItem.water(can) - 1);
        f.swing(InteractionHand.OFF_HAND);
        Tally t = tally(f);
        t.gardens++;
        long day = level.getDayTime() / 24000L;
        if (f.persona().hobbyDay() != day) {
            f.persona().enjoyedHobby(day);                        // a quiet hour of its own with the can: it is the better for it
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The flowers were gasping. There.", "I like a bit of watering of an evening.",
                "The boxes look all the better for a drink."));
        }
        f.brain("watered its garden of an evening");
    }

    // --- the sickle (FarmGoal)

    /**
     * From the farmer's harvest (FarmGoal.doHarvest), the crop at {@code centre} just cut: with a copper sickle, the ripe
     * crops of the three-by-three round it cut in the same swing, each sown again from its own seed, the drops into its
     * pack. Returns how many more were reaped.
     */
    public static int reap(AssistantEntity a, BlockPos centre) {
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level) || !FieldItems.COPPER_SICKLE.isBound()) return 0;
        ItemStack sickle = carried(f, FieldItems.COPPER_SICKLE.get());
        if (sickle.isEmpty()) return 0;
        List<BlockPos> crops = new ArrayList<>();
        for (BlockPos p : SickleItem.ripeAround(level, centre)) if (f.inZone(p)) crops.add(p);
        if (crops.isEmpty()) return 0;
        // The swing: the lot cut, sown again from its own seed, and what the swing's seed did not run to from the pack.
        SickleItem.Swing swing = SickleItem.reap(level, crops, f, sickle);
        for (SickleItem.Bare b : swing.bare()) {
            Item seed = SickleItem.seedOf(level, b.at(), b.crop());
            if (seed != null && f.removeMatching(s -> s.is(seed), 1) == 1) level.setBlock(b.at(), b.crop().defaultBlockState(), 3);
        }
        for (ItemStack d : swing.drops()) {
            ItemStack picked = d.copy();
            ItemStack left = f.insertItem(d);
            Economy.gathered(f, picked, picked.getCount() - left.getCount());
            if (!left.isEmpty()) Block.popResource(level, centre, left);
        }
        int n = swing.cut();
        if (n <= 0) return 0;
        f.note(AssistantEntity.Deed.CROPS_HARVESTED, n);
        show(f, FieldItems.COPPER_SICKLE.get(), 40);
        sickle = carried(f, FieldItems.COPPER_SICKLE.get());
        f.swing(InteractionHand.OFF_HAND);
        level.playSound(null, centre, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.NEUTRAL, 0.7F, 1.3F);
        wear(f, sickle);
        Tally t = tally(f);
        t.reaped += n + 1;
        t.reapedAt = level.getGameTime();
        f.brain("reaped " + (n + 1) + " crops at a stroke with its copper sickle");
        Villages.Village v = Villages.get(f.ownerId());
        if (v != null) first(level, v, "sickle", f.displayNameCap() + " reaped the harvest three rows at a stroke with a copper sickle");
        return n;
    }

    // ------------------------------------------------------------------ the drought (Droughts)

    /** A farmer with a can waters its dry field with it (this round) and carries no bucket (Droughts.carry). */
    public static boolean carriesCan(VillageFolkEntity f) {
        return FieldItems.WATERING_CAN.isBound() && f.stationTask() == StationTask.FARM && has(f, FieldItems.WATERING_CAN.get());
    }

    /** In a drought the farmers draw from the rain barrels first: the nearest with water near the field, or null. */
    @Nullable
    public static BlockPos barrelFirst(ServerLevel level, BlockPos field, int reach) {
        return barrelWithWater(level, field, reach);
    }

    /** A bucket filled at a rain barrel standing here (Droughts.fill, and so the bucket chain's and the cauldron's): false if no barrel, or dry. */
    public static boolean drawBarrel(ServerLevel level, BlockPos at) {
        return level.getBlockState(at).getBlock() instanceof RainBarrelBlock && RainBarrelBlock.draw(level, at);
    }

    /** A barrel's water, for the fire brigade's look for a full cauldron (FireBrigade.fullCauldron). */
    public static int barrelWater(BlockState st) {
        return RainBarrelBlock.water(st);
    }

    public static boolean isBarrel(BlockState st) {
        return st.getBlock() instanceof RainBarrelBlock;
    }

    /**
     * The bucket chain's water (BucketChain.water): a rain barrel with water nearer the fire than the open water it found,
     * when that is no near pond (eight blocks or more), or there is none.
     */
    @Nullable
    public static BlockPos chainWater(ServerLevel level, BlockPos fire, @Nullable BlockPos open) {
        double openD = open == null ? Double.MAX_VALUE : open.distSqr(fire);
        if (openD <= 8 * 8) return open;
        for (BlockPos b : FieldBlockEntity.near(level, FieldBlockEntity.Kind.BARREL, fire, BucketChain.WATER_REACH)) {
            if (RainBarrelBlock.water(level, b) <= 0) continue;
            double d = b.distSqr(fire);
            if (d >= 9 && d < openD) return b;
        }
        return open;
    }

    /**
     * FireSafety's cauldron of water by a workshop, when the stores have no cauldron and no iron to spare for one: a rain
     * barrel out of the stores instead, set there by hand and filled from a bucket of the stores' if they have one.
     * True if it was set.
     */
    public static boolean barrelInstead(ServerLevel level, Villages.Village v, BlockPos spot, String where) {
        if (!FieldItems.RAIN_BARREL_ITEM.isBound() || Market.stock(level, v.id(), s -> s.is(FieldItems.RAIN_BARREL_ITEM.get())) <= 0) return false;
        if (!TownJobs.atWork(level, v, "firesafety", spot, "setting a rain barrel by " + where)) return false;
        if (!Crafts.take(level, v, s -> s.is(FieldItems.RAIN_BARREL_ITEM.get()), 1)) return false;
        level.setBlockAndUpdate(spot, FieldItems.RAIN_BARREL.get().defaultBlockState());
        if (Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1)) {
            Crafts.store(level, v, new ItemStack(Items.BUCKET));
            RainBarrelBlock.add(level, spot, 1);
        }
        level.playSound(null, spot, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
        return true;
    }

    // ------------------------------------------------------------------ the rancher

    static void rancher(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        WorkZone z = f.workZone();
        if (z == null || !atWork(f)) return;
        BlockPos c = z.center();
        int r = ground(z);
        Drover.Pen pen = Drover.pen(v.id());
        boolean inPen = pen != null && pen.centre().equals(c);
        List<BlockPos> boxes = FieldBlockEntity.near(level, FieldBlockEntity.Kind.BOX, c, r);
        List<BlockPos> troughs = FieldBlockEntity.near(level, FieldBlockEntity.Kind.TROUGH, c, r);
        // What is set out is seen to first (eggs left in a box go unlaid-in, a trough run dry breeds nothing), then
        // what its ground still lacks, and only then the bench.
        for (BlockPos b : boxes) {
            if (!(level.getBlockEntity(b) instanceof FieldBlockEntity be) || be.count() <= 0 && be.hay() >= 3) continue;
            if (b.distSqr(f.blockPosition()) <= 5 * 5) emptyBox(level, v, f, b);
            else errand(f, Do.BOX, List.of(b), "seeing to the nesting box", now);
            return;
        }
        for (BlockPos tr : troughs) {
            if (FeedTroughBlock.feedIn(level, tr) >= 24 || !troughStock(level, v, f, tr)) continue;
            if (tr.distSqr(f.blockPosition()) <= 5 * 5) fillTrough(level, v, f, tr);
            else errand(f, Do.TROUGH, List.of(tr), "filling the feed trough", now);
            return;
        }
        // What its ground lacks set out on it, out of the stores, or made at the bench for it.
        if (boxes.isEmpty() && animals(level, c, r + 4, a -> a instanceof Chicken) > 0) {
            if (setOut(level, v, f, FieldItems.NESTING_BOX_ITEM.get(), spot(level, c, inPen ? pen : null, true), now)) return;
        }
        if (troughs.isEmpty() && animals(level, c, r + 4, a -> !a.isBaby()) >= 2) {
            if (setOut(level, v, f, FieldItems.FEED_TROUGH_ITEM.get(), spot(level, c, inPen ? pen : null, false), now)) return;
        }
        // With no tailor and no shop, the rancher (who has the hides) stitches the town's seed satchels.
        if (!hasTrade(v.id(), StationTask.TAILOR) && Workshop.keeper(v.id()) == null) {
            makeFor(level, v, f, now, List.of(FieldItems.SEED_SATCHEL.get()));
        }
    }

    /** Is there feed for this trough to be had (its pack's, or the stores' beyond their last dozen)? No errand for nothing. */
    static boolean troughStock(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos tr) {
        for (Item feed : feedWanted(level, tr)) {
            if (f.countMatching(s -> s.is(feed)) > 0 || Market.stock(level, v.id(), s -> s.is(feed)) > 12) return true;
        }
        return false;
    }

    /** The reach of a rancher's ground round its middle. */
    static int ground(WorkZone z) {
        return Math.max(5, Math.min(12, z.radius() + 2));
    }

    static int animals(ServerLevel level, BlockPos c, int r, Predicate<Animal> what) {
        return level.getEntitiesOfClass(Animal.class, new AABB(c).inflate(r, 4, r), a -> a.isAlive() && farmed(a) && what.test(a)).size();
    }

    /** The pen's animals (not the pets, nor the stable's horses). */
    static boolean farmed(Animal a) {
        return a instanceof Sheep || a instanceof Cow || a instanceof Pig || a instanceof Chicken || a instanceof Goat || a instanceof Rabbit;
    }

    /**
     * Where a box or a trough goes: in a pen, against its far fence (the trough in the middle of it, the box in a far
     * corner), clear of the gate; on open ground, a step or two from the ground's middle. Null if there is no room.
     */
    @Nullable
    static BlockPos spot(ServerLevel level, BlockPos c, @Nullable Drover.Pen pen, boolean box) {
        List<BlockPos> tries = new ArrayList<>();
        if (pen != null) {
            BlockPos far = pen.farSide();
            int dx = Integer.signum(far.getX() - pen.centre().getX()), dz = Integer.signum(far.getZ() - pen.centre().getZ());
            Direction along = dx != 0 ? Direction.SOUTH : Direction.EAST;
            if (box) {
                tries.add(far.relative(along, 2));
                tries.add(far.relative(along, -2));
            } else {
                tries.add(far);
                tries.add(far.relative(along, 1));
            }
            tries.add(pen.centre().offset(dx, 0, dz));
        }
        int[][] ring = box ? new int[][]{ {3, 3}, {-3, 3}, {3, -3}, {-3, -3}, {2, 2}, {-2, 2} } : new int[][]{ {0, 3}, {3, 0}, {0, -3}, {-3, 0}, {2, 0}, {0, 2} };
        for (int[] d : ring) tries.add(c.offset(d[0], 0, d[1]));
        for (BlockPos t : tries) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, t.getX(), t.getZ());
            BlockPos p = new BlockPos(t.getX(), y, t.getZ());
            if (Math.abs(y - c.getY()) > 3) continue;
            if (!level.getBlockState(p).canBeReplaced() || !level.getFluidState(p).isEmpty()) continue;
            if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
            return p;
        }
        return null;
    }

    /**
     * One of these set out on the ground here: out of the stores (or its pack), else made at the bench out of the stores
     * by this hand (it waits for the next round to set it). True if anything was done.
     */
    static boolean setOut(ServerLevel level, Villages.Village v, VillageFolkEntity f, Item what, @Nullable BlockPos at, long now) {
        if (at == null) return false;
        if (!has(f, what) && !kit(level, v, f, now, what)) {
            return makeFor(level, v, f, now, List.of(what)) != null;
        }
        if (f.removeMatching(s -> s.is(what), 1) != 1) return false;
        BlockState st = Block.byItem(what).defaultBlockState();
        if (st.hasProperty(NestingBoxBlock.FACING)) st = st.setValue(NestingBoxBlock.FACING, Direction.NORTH);
        level.setBlockAndUpdate(at, st);
        if (st.getBlock() instanceof NestingBoxBlock) NestingBoxBlock.line(level, at, NestingBoxBlock.HAY_OF_WHEAT);
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        String name = Bench.words(what, 1);
        f.brain("set " + name + " out on its ground");
        first(level, v, "set/" + BuiltInRegistries.ITEM.getKey(what).getPath(), f.displayNameCap() + " set " + name + " out in the pen");
        return true;
    }

    /** A nesting box emptied into the stores, and lined afresh with a wheat (its pack's, or the stores'). */
    static int emptyBox(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof FieldBlockEntity be) || be.kind() != FieldBlockEntity.Kind.BOX) return 0;
        int eggs = 0;
        for (ItemStack s : be.takeAll()) {
            eggs += s.getCount();
            Economy.produced(f, s.copy());
            Crafts.store(level, v, s);
        }
        if (be.hay() < 3 && (f.removeMatching(s -> s.is(Items.WHEAT), 1) == 1 || Crafts.take(level, v, s -> s.is(Items.WHEAT), 1))) {
            NestingBoxBlock.line(level, at, NestingBoxBlock.HAY_OF_WHEAT);
        }
        if (level.getBlockState(at).getBlock() instanceof NestingBoxBlock b) b.refresh(level, at);
        f.swing(InteractionHand.MAIN_HAND);
        if (eggs > 0) {
            Tally t = tally(f);
            t.eggs += eggs;
            level.playSound(null, at, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 1.1F);
            f.brain("took " + eggs + (eggs == 1 ? " egg" : " eggs") + " from the nesting box to the stores");
            first(level, v, "eggs", "the hens laid in the nesting box, and " + f.displayNameCap() + " took the eggs to the stores for the kitchen");
        }
        return eggs;
    }

    /** The feeds a trough wants for what is near it: wheat for sheep, cows and goats, seed for the hens, carrots for pigs and rabbits. */
    static List<Item> feedWanted(ServerLevel level, BlockPos trough) {
        List<Item> out = new ArrayList<>();
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(trough).inflate(TROUGH_REACH, 4, TROUGH_REACH), x -> x.isAlive() && farmed(x))) {
            Item want = a instanceof Chicken ? Items.WHEAT_SEEDS : a instanceof Pig || a instanceof Rabbit ? Items.CARROT : Items.WHEAT;
            if (!out.contains(want)) out.add(want);
            if (out.size() >= 3) break;
        }
        return out;
    }

    /** The trough topped up with the right feed for what is near it, out of the rancher's pack first, then the stores (their last dozen kept). */
    static int fillTrough(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos at) {
        int put = 0;
        for (Item feed : feedWanted(level, at)) {
            if (!(level.getBlockEntity(at) instanceof FieldBlockEntity be)) break;
            int room = 16 - be.countOf(s -> s.is(feed));
            if (room <= 0) continue;
            int fromPack = Math.min(room, f.countMatching(s -> s.is(feed)));
            if (fromPack > 0) f.removeMatching(s -> s.is(feed), fromPack);
            int fromStores = Math.min(room - fromPack, Math.max(0, Market.stock(level, v.id(), s -> s.is(feed)) - 12));
            if (fromStores > 0 && !Crafts.take(level, v, s -> s.is(feed), fromStores)) fromStores = 0;
            int n = fromPack + fromStores;
            if (n <= 0) continue;
            ItemStack left = FeedTroughBlock.fill(level, at, new ItemStack(feed, n));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            put += n - left.getCount();
        }
        if (put > 0) {
            f.swing(InteractionHand.MAIN_HAND);
            tally(f).fed += put;
            f.brain("filled the feed trough: " + put + " feed");
        }
        return put;
    }

    /** Does a trough with feed in it stand on this rancher's ground? Then its breeding is the trough's, not its own (AssistantEntity). */
    public static boolean troughFeeds(AssistantEntity a) {
        WorkZone z = a.workZone();
        if (z == null || !(a.level() instanceof ServerLevel level) || !FieldItems.FEED_TROUGH.isBound()) return false;
        for (BlockPos t : FieldBlockEntity.near(level, FieldBlockEntity.Kind.TROUGH, z.center(), ground(z))) {
            if (FeedTroughBlock.feedIn(level, t) > 0) return true;
        }
        return false;
    }

    // --- the boxes and the troughs, every second

    /** Hens near a box with hay in it lay a little more often: a fifth again. */
    static void boxes(ServerLevel level) {
        for (BlockPos p : FieldBlockEntity.all(level, FieldBlockEntity.Kind.BOX)) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof FieldBlockEntity be) || be.hay() <= 0) continue;
            if (be.count() >= NestingBoxBlock.MOST) continue;
            for (Chicken c : level.getEntitiesOfClass(Chicken.class, new AABB(p).inflate(NEST_REACH), x -> x.isAlive() && !x.isBaby())) {
                c.eggTime = Math.max(1, c.eggTime - 4);
            }
        }
    }

    /** Every trough with feed in it: the animals drawn to it (every two seconds), and a pair bred off it now and then. */
    static void troughs(ServerLevel level, boolean pull) {
        long now = level.getGameTime();
        for (BlockPos p : FieldBlockEntity.all(level, FieldBlockEntity.Kind.TROUGH)) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof FieldBlockEntity be) || be.count() <= 0) continue;
            if (pull) pull(level, p, be);
            Long last = BRED_AT.get(p.asLong());
            if (last == null) {
                BRED_AT.put(p.asLong(), now - Math.floorMod(p.hashCode(), (int) TROUGH_BREED_EVERY));
                continue;
            }
            if (now - last < TROUGH_BREED_EVERY && now >= last) continue;
            BRED_AT.put(p.asLong(), now);
            breed(level, p, be);
        }
    }

    /** The grown animals a little way off that eat what is in it wander back to it. */
    static void pull(ServerLevel level, BlockPos p, FieldBlockEntity be) {
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(p).inflate(TROUGH_PULL, 4, TROUGH_PULL),
                x -> x.isAlive() && farmed(x) && !x.isBaby() && !x.isLeashed() && !x.isInLove())) {
            if (a.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) < 4.0 * 4.0 || !a.getNavigation().isDone()) continue;
            if (be.countOf(a::isFood) <= 0 || level.getRandom().nextInt(3) != 0) continue;
            a.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0D);
        }
    }

    /**
     * A pair bred off the trough: two grown animals of a kind near it, ready for it, each eating one of what is in it,
     * so long as the pen keeps to its limit (eight of a kind, sixteen in all, near the trough). True if a pair was bred.
     */
    static boolean breed(ServerLevel level, BlockPos p, FieldBlockEntity be) {
        List<Animal> near = level.getEntitiesOfClass(Animal.class, new AABB(p).inflate(TROUGH_REACH, 4, TROUGH_REACH), x -> x.isAlive() && farmed(x));
        if (near.size() >= PEN_MOST) return false;
        Map<EntityType<?>, Integer> count = new HashMap<>();
        Map<EntityType<?>, List<Animal>> ready = new LinkedHashMap<>();
        for (Animal a : near) {
            count.merge(a.getType(), 1, Integer::sum);
            if (a.isBaby() || a.getAge() != 0 || !a.canFallInLove() || a.isInLove() || a.isLeashed() || be.countOf(a::isFood) <= 0) continue;
            ready.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<EntityType<?>, List<Animal>> e : ready.entrySet()) {
            List<Animal> pair = e.getValue();
            if (pair.size() < 2 || count.getOrDefault(e.getKey(), 0) >= HERD_LIMIT) continue;
            Animal x = pair.get(0), y = pair.get(1);
            if (be.use(x::isFood, 1) < 1) continue;
            if (be.use(y::isFood, 1) < 1) continue;
            x.setInLove(null);
            y.setInLove(null);
            level.playSound(null, p, SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.7F, 1.0F);
            if (level.getBlockState(p).getBlock() instanceof FeedTroughBlock t) t.refresh(level, p);
            for (Villages.Village v : Villages.every()) {
                if (!v.dim().equals(level.dimension()) || v.centre().distSqr(p) > (double) Villages.VILLAGE_RANGE * Villages.VILLAGE_RANGE) continue;
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity r && r.stationTask() == StationTask.RANCH && r.workZone() != null
                            && r.workZone().center().distSqr(p) <= 16 * 16) tally(r).bred++;
                }
                first(level, v, "trough", "a pair of " + Drover.kind(x) + (Drover.kind(x).endsWith("s") || Drover.kind(x).equals("sheep") ? "" : "s")
                    + " bred off the feed trough in the pen");
                break;
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the fisher

    static void fisher(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        WorkZone z = f.workZone();
        if (z == null) return;
        long day = level.getDayTime() / 24000L, tod = level.getDayTime() % 24000L;
        boolean keptIn = Fleet.keptIn(level, v.id(), day) != null;
        boolean daylight = tod >= 1000L && tod < 12000L;
        BlockPos c = z.center();
        List<BlockPos> traps = FieldBlockEntity.near(level, FieldBlockEntity.Kind.TRAP, c, TRAP_REACH);
        // The catch: a trap it is passing emptied at once; on a day the boats stay in, the round of them all.
        List<BlockPos> full = new ArrayList<>();
        for (BlockPos t : traps) if (level.getBlockEntity(t) instanceof FieldBlockEntity be && be.count() > 0) full.add(t);
        if (!full.isEmpty()) {
            BlockPos me = f.blockPosition();
            full.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
            if (full.get(0).distSqr(me) <= 5 * 5) {
                emptyTrap(level, v, f, full.get(0));
                land(level, v, f);
                return;
            }
            if (keptIn && (daylight || testing(level))) {
                errand(f, Do.TRAPS, full, "going round its fish traps while the boats stay in", now);
                return;
            }
        }
        // Its traps set: two by its fishing ground, the town's four at the most.
        if (!atWork(f) || traps.size() >= TRAPS_A_FISHER || townTraps(level, v) >= TRAPS_MOST) return;
        if (!has(f, FieldItems.FISH_TRAP_ITEM.get()) && !kit(level, v, f, now, FieldItems.FISH_TRAP_ITEM.get())) {
            makeFor(level, v, f, now, List.of(FieldItems.FISH_TRAP_ITEM.get()));
            return;
        }
        BlockPos spot = trapSpot(level, v, f, c, traps);
        if (spot != null) errand(f, Do.SET_TRAP, List.of(spot), "setting a fish trap in the water", now);
    }

    /** The town's traps, all told (near its fishers' grounds). */
    static int townTraps(ServerLevel level, Villages.Village v) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() != StationTask.FISH || a.workZone() == null) continue;
            for (BlockPos t : FieldBlockEntity.near(level, FieldBlockEntity.Kind.TRAP, a.workZone().center(), TRAP_REACH)) seen.add(t.asLong());
        }
        return seen.size();
    }

    static final int TRAP_REACH = 24;

    /**
     * Where a trap goes: open water near the fishing ground (off the end of the quay first), a source with water round it
     * and air or water over it, three blocks from any other trap. Null if there is none in reach.
     */
    @Nullable
    static BlockPos trapSpot(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos c, List<BlockPos> traps) {
        List<BlockPos> centres = new ArrayList<>();
        Waterfront.Dock dock = Waterfront.dockOf(v.id(), f);
        if (dock != null) centres.add(dock.start().relative(dock.out(), Math.max(1, dock.length())));
        centres.add(c);
        for (BlockPos from : centres) {
            for (int ring = 1; ring <= 8; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        for (int dy = 1; dy >= -3; dy--) {
                            BlockPos p = from.offset(dx, dy, dz);
                            if (!level.isLoaded(p) || !level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.WATER)
                                    || !level.getFluidState(p).isSource()) continue;
                            BlockState above = level.getBlockState(p.above());
                            if (!above.isAir() && !above.is(net.minecraft.world.level.block.Blocks.WATER)) continue;
                            int wet = 0;
                            for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(p.relative(d)).is(FluidTags.WATER)) wet++;
                            if (wet < 2) continue;
                            boolean clear = true;
                            for (BlockPos t : traps) if (t.distManhattan(p) < 3) clear = false;
                            if (clear) return p.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** A trap set in the water here, out of its pack. */
    static boolean setTrap(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos at) {
        if (!level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.WATER)) return false;
        if (f.removeMatching(s -> s.is(FieldItems.FISH_TRAP_ITEM.get()), 1) != 1) return false;
        level.setBlockAndUpdate(at, FieldItems.FISH_TRAP.get().defaultBlockState().setValue(FishTrapBlock.WATERLOGGED, true)
            .setValue(FishTrapBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom())));
        level.playSound(null, at, SoundEvents.BUCKET_EMPTY_FISH, SoundSource.NEUTRAL, 0.6F, 0.9F);
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("set a fish trap in the water");
        first(level, v, "trap", f.displayNameCap() + " set the town's first fish trap in the water");
        return true;
    }

    /** A trap emptied: the fish into its pack (for the market), the junk into the stores. Returns the fish. */
    static int emptyTrap(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof FieldBlockEntity be) || be.kind() != FieldBlockEntity.Kind.TRAP) return 0;
        int fish = 0;
        for (ItemStack s : be.takeAll()) {
            if (Economy.RAW_FISH.test(s)) {
                fish += s.getCount();
                ItemStack left = f.insertItem(s);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            } else {
                Crafts.store(level, v, s);
            }
        }
        if (level.getBlockState(at).getBlock() instanceof FishTrapBlock b) b.refresh(level, at);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, at, SoundEvents.BUCKET_FILL_FISH, SoundSource.NEUTRAL, 0.6F, 1.0F);
        if (fish > 0) {
            tally(f).fish += fish;
            f.brain("emptied a fish trap: " + fish + " fish");
        }
        return fish;
    }

    /** What it brought in from the traps landed: into the fish market's barrels if it stands, else the stores (FishMarket). */
    static void land(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        int fish = f.countMatching(Economy.RAW_FISH);
        if (fish <= 0) return;
        int market = FishMarket.land(level, v, f, Map.of());
        f.brain("landed the traps' catch: " + fish + " fish" + (market > 0 ? ", " + market + " into the fish market" : ", into the stores"));
        first(level, v, "trap-catch", "the fish traps' first catch came in: " + fish + " fish, landed by " + f.displayNameCap()
            + (market > 0 ? " at the fish market" : " into the stores") + " while the boats stayed in");
    }

    // ------------------------------------------------------------------ the beekeeper (Crafts.beekeep)

    /**
     * The beekeeper's harvest with a smoker (its own, or the stores'): every full hive on its meadow smoked and emptied
     * on the one round, the bees calmed, and a comb or a bottle the more for each (four comb to the shears; a bottle and
     * a comb to a bottle). Null with no smoker, or no full hive, and the harvest goes on the old way, a hive at a time.
     */
    @Nullable
    public static String smokedHarvest(ServerLevel level, Villages.Village v, VillageFolkEntity f, List<BlockPos> hives) {
        if (!FieldItems.BEE_SMOKER.isBound()) return null;
        List<BlockPos> full = new ArrayList<>();
        for (BlockPos p : hives) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof BeehiveBlock && st.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS) full.add(p);
        }
        if (full.isEmpty() || !kit(level, v, f, level.getGameTime(), FieldItems.BEE_SMOKER.get())) return null;
        show(f, FieldItems.BEE_SMOKER.get(), 80);
        int comb = 0, honey = 0, done = 0;
        for (BlockPos p : full) {
            ItemStack smoker = carried(f, FieldItems.BEE_SMOKER.get());
            if (smoker.isEmpty()) break;
            boolean wantComb = Market.stock(level, v.id(), s -> s.is(Items.HONEYCOMB)) + comb < 6;
            boolean shears = f.countCarried(s -> s.is(Items.SHEARS)) > 0 || takeShears(level, v, f);
            boolean bottle = Crafts.bottle(level, v);
            if (shears && (wantComb || !bottle)) {
                comb += 4;
                for (ItemStack s : f.getInventoryItems()) if (s.is(Items.SHEARS)) { wear(f, s); break; }
            } else if (bottle && Crafts.take(level, v, s -> s.is(Items.GLASS_BOTTLE), 1)) {
                honey++;
                comb++;
            } else {
                continue;
            }
            BeeSmokerItem.puff(level, p, f.getEyePosition());
            BeeSmokerItem.smokeHive(level, p);
            BlockState st = level.getBlockState(p);
            level.setBlock(p, st.setValue(BeehiveBlock.HONEY_LEVEL, 0), 3);
            level.playSound(null, p, SoundEvents.BEEHIVE_SHEAR, SoundSource.NEUTRAL, 0.8F, 1.0F);
            wear(f, smoker);
            done++;
        }
        if (done == 0) return null;
        if (comb > 0) Crafts.store(level, v, new ItemStack(Items.HONEYCOMB, comb));
        if (honey > 0) Crafts.store(level, v, new ItemStack(Items.HONEY_BOTTLE, honey));
        f.swing(InteractionHand.OFF_HAND);
        tally(f).hives += done;
        first(level, v, "smoker", f.displayNameCap() + " smoked the hives and took the honey without a sting");
        return (done == 1 ? "a hive" : done + " hives") + " smoked and emptied: " + (comb > 0 ? comb + " honeycomb" : "")
            + (comb > 0 && honey > 0 ? " and " : "") + (honey > 0 ? (honey == 1 ? "a bottle" : honey + " bottles") + " of honey" : "");
    }

    private static boolean takeShears(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        ItemStack one = Crafts.takeOne(level, v, s -> s.is(Items.SHEARS));
        if (one.isEmpty()) return false;
        ItemStack left = f.insertItem(one);
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);
            return false;
        }
        return true;
    }

    /** Every half-second: the bees where a player (or a beekeeper) has smoked kept calm. */
    static void calmTheBees(MinecraftServer server) {
        for (BeeSmokerItem.Calm c : BeeSmokerItem.calmNow(server.overworld().getGameTime())) {
            ServerLevel level = server.getLevel(c.dim());
            if (level == null || !level.isLoaded(c.at())) continue;
            for (Bee b : level.getEntitiesOfClass(Bee.class, new AABB(c.at()).inflate(BeeSmokerItem.REACH + 2), Bee::isAngry)) {
                b.stopBeingAngry();
                b.setTarget(null);
            }
        }
    }

    /** A player emptying a full hive where the bees have been smoked: the comb or the honey, and no bees let out angry. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !FieldItems.BEE_SMOKER.isBound()) return;
        BlockPos pos = e.getPos();
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof BeehiveBlock hive) || st.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) return;
        ItemStack held = e.getItemStack();
        boolean shears = held.is(Items.SHEARS), bottle = held.is(Items.GLASS_BOTTLE);
        if (!shears && !bottle || !BeeSmokerItem.calm(level, pos)) return;
        Player p = e.getEntity();
        if (shears) {
            BeehiveBlock.dropHoneycomb(level, pos);
            held.hurtAndBreak(1, p, e.getHand() == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
            level.playSound(null, pos, SoundEvents.BEEHIVE_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
        } else {
            if (!p.getAbilities().instabuild) held.shrink(1);
            ItemStack honey = new ItemStack(Items.HONEY_BOTTLE);
            if (held.isEmpty()) p.setItemInHand(e.getHand(), honey);
            else if (!p.getInventory().add(honey)) p.drop(honey, false);
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        hive.resetHoneyLevel(level, st, pos);
        level.gameEvent(p, GameEvent.SHEAR, pos);
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
    }

    // ------------------------------------------------------------------ the hens (an egg laid near a box goes into it)

    /** An egg laid by a hen near a nesting box with room in it is laid into the box, not on the ground. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof ItemEntity ie) || !(e.getLevel() instanceof ServerLevel level) || e.loadedFromDisk()) return;
        if (!ie.getItem().is(Items.EGG) || ie.getItem().getCount() != 1) return;
        List<BlockPos> boxes = FieldBlockEntity.near(level, FieldBlockEntity.Kind.BOX, ie.blockPosition(), NEST_REACH);
        if (boxes.isEmpty()) return;
        if (level.getEntitiesOfClass(Chicken.class, ie.getBoundingBox().inflate(0.75), c -> c.isAlive() && !c.isBaby()).isEmpty()) return;
        for (BlockPos b : boxes) {
            if (ie.blockPosition().distSqr(b) > NEST_REACH * NEST_REACH * 2) continue;
            if (NestingBoxBlock.room(level, b) && NestingBoxBlock.lay(level, b, ie.getItem())) {
                e.setCanceled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ the barrels: the town's round

    /**
     * Every half-minute: a rain barrel set by each workshop that has none, and at the edge of each field, out of the
     * stores and by hand (TownJobs); with none in the stores and no shop to make one, an idle hand coopers one at the
     * bench, of the stores' planks and copper.
     */
    static void town(ServerLevel level, Villages.Village v) {
        if (!FieldItems.RAIN_BARREL_ITEM.isBound()) return;
        Item barrel = FieldItems.RAIN_BARREL_ITEM.get();
        List<Object[]> places = barrelPlaces(level, v);
        if (places.isEmpty()) return;
        Object[] first = places.get(0);
        BlockPos spot = (BlockPos) first[0];
        String where = (String) first[1];
        if (Market.stock(level, v.id(), s -> s.is(barrel)) > 0) {
            if (!TownJobs.atWork(level, v, WORKS, spot, "setting a rain barrel " + where)) return;
            if (!Crafts.take(level, v, s -> s.is(barrel), 1)) return;
            level.setBlockAndUpdate(spot, FieldItems.RAIN_BARREL.get().defaultBlockState());
            level.playSound(null, spot, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
            first(level, v, "barrel", "the town set its first rain barrel " + where + ", to catch the rain");
            return;
        }
        if (Workshop.keeper(v.id()) != null || !Tiers.allows(level, Villages.ageOf(v.id()), barrel)) return;
        BlockPos at = v.centre();
        if (!TownJobs.atWork(level, v, WORKS + "/cooper", at, "making a rain barrel at the bench")) return;
        Economy.openCraft(v.id(), StationTask.NONE);
        try {
            Bench.Hand hand = Bench.handOf(level, v, null, null);
            Bench.Plan plan = Bench.plan(level, v, barrel, 1, hand);
            if (!plan.ok()) return;
            ItemStack made = Bench.make(level, v, plan, null, hand);
            if (!made.isEmpty()) first(level, v, "made/rain_barrel", "a rain barrel was coopered at the bench, of " + plan.chain());
        } finally {
            Economy.closeCraft();
        }
    }

    /** Where a barrel is wanted: {spot, "by the smithy"}, the workshops first, then the fields. */
    static List<Object[]> barrelPlaces(ServerLevel level, Villages.Village v) {
        List<Object[]> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!FireSafety.WORKSHOPS.contains(b.structure()) || !level.isLoaded(b.anchor())) continue;
            if (!FieldBlockEntity.near(level, FieldBlockEntity.Kind.BARREL, b.anchor(), 9).isEmpty()) continue;
            BlockPos s = FireSafety.cauldronSpot(level, b);
            if (s == null || !level.canSeeSky(s)) continue;
            out.add(new Object[]{ s, "by " + FireBrigade.named(b.structure()) });
            if (out.size() >= 4) return out;
        }
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.FARM || f.workZone() == null) continue;
            WorkZone z = f.workZone();
            if (!level.isLoaded(z.center())) continue;
            if (!FieldBlockEntity.near(level, FieldBlockEntity.Kind.BARREL, z.center(), z.radius() + 3).isEmpty()) continue;
            BlockPos chest = f.productionChest();
            BlockPos s = beside(level, chest != null ? chest : z.center());
            if (s == null || !level.canSeeSky(s)) continue;
            out.add(new Object[]{ s, "at the edge of " + f.displayNameCap() + "'s field" });
            if (out.size() >= 4) return out;
        }
        return out;
    }

    /** A free spot on firm ground beside here (not on the farmland), or null. */
    @Nullable
    static BlockPos beside(ServerLevel level, BlockPos at) {
        for (int[] d : new int[][]{ {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {2, 0}, {0, 2} }) {
            int x = at.getX() + d[0], z = at.getZ() + d[1];
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (Math.abs(y - at.getY()) > 2 || !level.getBlockState(p).isAir()) continue;
            BlockState under = level.getBlockState(p.below());
            if (under.getBlock() instanceof FarmBlock || !under.isFaceSturdy(level, p.below(), Direction.UP)) continue;
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ the makers

    /** Put the fields' wants on the shop's order book (once, at the first town round: Pets.demandOnce's reason). */
    static void demandOnce() {
        if (demanded) return;
        demanded = true;
        Workshop.demand("the fields and the pens", (level, v, want) -> {
            for (Map.Entry<Item, Integer> e : wanted(level, v).entrySet()) want.accept(e.getKey(), e.getValue());
        });
    }

    /**
     * What the town wants kept in its stores of these, worked out at most every ten seconds: a can, a sickle and a
     * satchel for every farmer without, a smoker for every beekeeper without, a box and a trough for every rancher's
     * ground without (and animals on it), the fishers' traps still to set, and a barrel for every workshop and field
     * without.
     */
    public static Map<Item, Integer> wanted(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Object[] cached = WANTS.get(id);
        if (cached != null && now - (Long) cached[0] < 200L && now >= (Long) cached[0]) {
            @SuppressWarnings("unchecked") Map<Item, Integer> m = (Map<Item, Integer>) cached[1];
            return m;
        }
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (!FieldItems.WATERING_CAN.isBound() || !settled(level, id)) return out;
        int traps = 0, fishers = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            switch (f.stationTask()) {
                case FARM -> {
                    if (!has(f, FieldItems.WATERING_CAN.get())) out.merge(FieldItems.WATERING_CAN.get(), 1, Integer::sum);
                    if (!has(f, FieldItems.COPPER_SICKLE.get())) out.merge(FieldItems.COPPER_SICKLE.get(), 1, Integer::sum);
                    if (!has(f, FieldItems.SEED_SATCHEL.get())) out.merge(FieldItems.SEED_SATCHEL.get(), 1, Integer::sum);
                }
                case BEEKEEP -> {
                    if (!has(f, FieldItems.BEE_SMOKER.get())) out.merge(FieldItems.BEE_SMOKER.get(), 1, Integer::sum);
                }
                case RANCH -> {
                    WorkZone z = f.workZone();
                    if (z == null || !level.isLoaded(z.center())) continue;
                    int r = ground(z);
                    if (FieldBlockEntity.near(level, FieldBlockEntity.Kind.BOX, z.center(), r).isEmpty() && !has(f, FieldItems.NESTING_BOX_ITEM.get())
                            && animals(level, z.center(), r + 4, x -> x instanceof Chicken) > 0) out.merge(FieldItems.NESTING_BOX_ITEM.get(), 1, Integer::sum);
                    if (FieldBlockEntity.near(level, FieldBlockEntity.Kind.TROUGH, z.center(), r).isEmpty() && !has(f, FieldItems.FEED_TROUGH_ITEM.get())
                            && animals(level, z.center(), r + 4, x -> !x.isBaby()) >= 2) out.merge(FieldItems.FEED_TROUGH_ITEM.get(), 1, Integer::sum);
                }
                case FISH -> {
                    fishers++;
                    if (f.workZone() != null) traps += FieldBlockEntity.near(level, FieldBlockEntity.Kind.TRAP, f.workZone().center(), TRAP_REACH).size();
                    traps += f.countCarried(s -> s.is(FieldItems.FISH_TRAP_ITEM.get()));
                }
                default -> { }
            }
        }
        int trapWant = Math.min(TRAPS_MOST, fishers * TRAPS_A_FISHER) - traps;
        if (trapWant > 0) out.put(FieldItems.FISH_TRAP_ITEM.get(), Math.min(2, trapWant));
        int barrels = barrelPlaces(level, v).size();
        if (barrels > 0) out.put(FieldItems.RAIN_BARREL_ITEM.get(), Math.min(2, barrels));
        WANTS.put(id, new Object[]{ now, out });
        return out;
    }

    /** Whose work each thing is: the smith's copper, the tailor's satchel, the rancher's box and trough, the fisher's trap; the shop makes them all. */
    static boolean makes(StationTask t, Item it) {
        if (t == StationTask.SHOP) return true;
        if (t == StationTask.SMITH || t == StationTask.SMELT) {
            return it == FieldItems.WATERING_CAN.get() || it == FieldItems.COPPER_SICKLE.get() || it == FieldItems.BEE_SMOKER.get();
        }
        if (t == StationTask.TAILOR || t == StationTask.RANCH && it == FieldItems.SEED_SATCHEL.get()) return it == FieldItems.SEED_SATCHEL.get();
        if (t == StationTask.RANCH) return it == FieldItems.NESTING_BOX_ITEM.get() || it == FieldItems.FEED_TROUGH_ITEM.get();
        if (t == StationTask.FISH) return it == FieldItems.FISH_TRAP_ITEM.get();
        return false;
    }

    static boolean hasTrade(UUID village, StationTask t) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t && !a.isBaby() && a.isAlive()) return true;
        return false;
    }

    /**
     * A piece of the crafts' work (Crafts.now): the first of the fields' things the town wants and is short of that is
     * this trade's work and its age allows, made at the bench out of the stores by its real recipe. What was made, or null.
     */
    @Nullable
    public static String craft(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (!FieldItems.WATERING_CAN.isBound()) return null;
        Map<Item, Integer> want = wanted(level, v);
        if (want.isEmpty()) return null;
        return makeNow(level, v, f, new ArrayList<>(want.keySet()), want);
    }

    /** A trade outside the crafts at the bench (the rancher, the fisher, the smelter standing in for the smith): at most every two minutes. */
    @Nullable
    static String makeFor(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now, List<Item> items) {
        Long last = MADE.get(f.getUUID());
        if (last != null && now - last < MAKE_EVERY && now >= last) return null;
        Map<Item, Integer> want = wanted(level, v);
        List<Item> mine = new ArrayList<>();
        for (Item it : items) if (want.getOrDefault(it, 0) > 0) mine.add(it);
        if (mine.isEmpty()) return null;
        Economy.openCraft(v.id(), f.stationTask());
        String made;
        try {
            made = makeNow(level, v, f, mine, want);
        } finally {
            Economy.closeCraft();
        }
        if (made != null) {
            MADE.put(f.getUUID(), now);
            f.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, f.blockPosition(), SoundEvents.VILLAGER_WORK_TOOLSMITH, SoundSource.NEUTRAL, 0.7F, 1.0F);
            f.brain("made " + made);
        }
        return made;
    }

    @Nullable
    static String makeNow(ServerLevel level, Villages.Village v, VillageFolkEntity f, List<Item> items, Map<Item, Integer> want) {
        StationTask t = f.stationTask();
        Villages.Age age = Villages.ageOf(v.id());
        Bench.Hand hand = null;
        for (Item it : items) {
            if (!makes(t, it) || !Tiers.allows(level, age, it)) continue;
            if (Market.stock(level, v.id(), s -> s.is(it)) + ShopStock.held(level, v.id(), s -> s.is(it)) >= want.getOrDefault(it, 0)) continue;
            if (hand == null) hand = Bench.handOf(level, v, f, VillageFolkEntity.buildingFor(t));
            Bench.Plan plan = Bench.plan(level, v, it, 1, hand);
            if (!plan.ok()) continue;
            ItemStack out = Bench.make(level, v, plan, f, hand);
            if (out.isEmpty()) continue;
            WANTS.remove(v.id());
            tally(f).made++;
            String words = Bench.words(out.getItem(), out.getCount());
            LOG.info("[MCA-FIELDS] {} ({}) made {} of {}", f.displayNameCap(), t.title, words, plan.chain());
            first(level, v, "made/" + BuiltInRegistries.ITEM.getKey(it).getPath(), f.displayNameCap() + " made the town's first "
                + new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT) + ", of " + plan.chain());
            return words + ", for the fields and the pens";
        }
        return null;
    }

    /** The smelter with the copper at its furnace beats the copper tools cold, while the town has neither smith nor shop. */
    static void smelter(ServerLevel level, Villages.Village v, VillageFolkEntity f, long now) {
        if (!atWork(f) || hasTrade(v.id(), StationTask.SMITH) || Workshop.keeper(v.id()) != null) return;
        makeFor(level, v, f, now, List.of(FieldItems.WATERING_CAN.get(), FieldItems.COPPER_SICKLE.get(), FieldItems.BEE_SMOKER.get()));
    }

    /** The first time a thing happens in a town: into its chronicle, once (Ledger). */
    static void first(ServerLevel level, Villages.Village v, String key, String line) {
        String k = "fields/first/" + key;
        if (Ledger.note(v.id(), k) != null) return;
        long day = level.getDayTime() / 24000L;
        Ledger.note(v.id(), k, Long.toString(day));
        Villages.tell(v.id(), day, line);
    }

    // ------------------------------------------------------------------ errands: a short walk and a piece of work at the end

    enum Do { FILL, POUR, GARDEN, BOX, TROUGH, TRAPS, SET_TRAP, PACK }

    /** An errand: what, the stops, and how the walk is going. */
    static final class Errand {
        final Do what;
        final List<BlockPos> stops;
        final String doing;
        final long since;
        int at;
        double best = Double.MAX_VALUE;
        long bestAt;
        int walkTick = -1000;

        Errand(Do what, List<BlockPos> stops, String doing, long now) {
            this.what = what;
            this.stops = new ArrayList<>(stops);
            this.doing = doing;
            this.since = now;
            this.bestAt = now;
        }
    }

    /** Within reach of the work at a stop; and as near as it gets when it can come no nearer (a trap out in the water). */
    static final double REACH = 3.2, REACH_FAR = 7.0;

    static void errand(VillageFolkEntity f, Do what, List<BlockPos> stops, String doing, long now) {
        if (stops.isEmpty()) return;
        ERRANDS.put(f.getUUID(), new Errand(what, stops, doing, now));
        f.clearQueue();
        f.getNavigation().stop();
        f.brain(doing);
    }

    /** Is it about one of these errands? */
    public static boolean busy(VillageFolkEntity f) {
        return ERRANDS.containsKey(f.getUUID());
    }

    /** Free to go on with it: awake, not fighting, no bell, no storm, not on the law's business. */
    static boolean fit(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        return id != null && !f.isSleeping() && f.getTarget() == null && !Raids.underAlarm(id) && !Weather.stormy(level) && !Crime.busy(f);
    }

    /**
     * From the folk's tick (VillageFolkEntity.aiStep): on its way to the next stop of an errand, and the work done there.
     * True while it is about it (the rest of its tick waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return false;
        long now = level.getGameTime();
        if (!fit(f, level) || now - e.since > ERRAND_MOST || now < e.since) {
            ERRANDS.remove(f.getUUID());
            return false;
        }
        if (f.tickCount % 4 != 0) return true;
        BlockPos to = e.stops.get(e.at);
        double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5), dy = Math.abs(f.getY() - to.getY());
        double d = Math.sqrt(dx * dx + dz * dz);
        boolean there = d <= REACH && dy <= 3.5;
        if (!there) {
            if (d < e.best - 0.4) {
                e.best = d;
                e.bestAt = now;
            } else if (now - e.bestAt > 160L || now < e.bestAt) {
                if (d <= REACH_FAR && dy <= 4.5) there = true;              // as near as it can come: it reaches out
                else if (!next(e, now)) {
                    ERRANDS.remove(f.getUUID());                          // no way there: back to its work
                    return false;
                } else return true;
            }
            if (!there) {
                if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 30) {
                    f.walkTo(to, 1.0D);
                    e.walkTick = f.tickCount;
                }
                return true;
            }
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5);
        act(level, f, e, to);
        if (!next(e, now)) ERRANDS.remove(f.getUUID());
        return true;
    }

    private static boolean next(Errand e, long now) {
        e.at++;
        e.best = Double.MAX_VALUE;
        e.bestAt = now;
        return e.at < e.stops.size();
    }

    /** The work at a stop. */
    static void act(ServerLevel level, VillageFolkEntity f, Errand e, BlockPos to) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        switch (e.what) {
            case FILL -> fillCan(level, f, to);
            case POUR -> pourAt(level, v, f, to, Droughts.on(v.id()), level.getGameTime());
            case GARDEN -> gardenPour(level, v, f, to);
            case BOX -> emptyBox(level, v, f, to);
            case TROUGH -> fillTrough(level, v, f, to);
            case TRAPS -> {
                emptyTrap(level, v, f, to);
                if (e.at == e.stops.size() - 1) land(level, v, f);
            }
            case SET_TRAP -> setTrap(level, v, f, to);
            case PACK -> {
                ItemStack bag = carried(f, FieldItems.SEED_SATCHEL.get());
                if (!bag.isEmpty()) {
                    pack(level, v, f, bag);
                    handOut(f, bag);
                }
            }
        }
    }

    // ------------------------------------------------------------------ where the player sees it

    /** What it is about, for what it says it is doing (FolkTalk.doing): an errand, or the can or the sickle a moment ago. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e != null) return e.doing.replace(" its ", " my ");          // in its own words
        Tally t = TALLY.get(f.getUUID());
        long now = f.level().getGameTime();
        if (t != null && now - t.poured < 200L && now >= t.poured) return "watering the field with my copper can";
        if (t != null && now - t.reapedAt < 200L && now >= t.reapedAt) return "reaping three rows at a stroke with my copper sickle";
        return null;
    }

    /** Its tools and what they did today, for its card (FolkTalk.card). */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (!FieldItems.WATERING_CAN.isBound()) return null;
        List<String> parts = new ArrayList<>();
        Tally t = TALLY.get(f.getUUID());
        long day = f.level().getDayTime() / 24000L;
        if (t != null && t.day != day) t = null;
        ItemStack can = carried(f, FieldItems.WATERING_CAN.get());
        if (!can.isEmpty()) {
            parts.add("a copper watering can (" + WateringCanItem.water(can) + " of " + WateringCanItem.CAPACITY + " waterings)"
                + (t != null && t.watered > 0 ? ", " + t.watered + (t.watered == 1 ? " patch" : " patches") + " watered today"
                + (t.dry > 0 ? " (" + t.dry + " in the drought)" : "") : "")
                + (t != null && t.gardens > 0 ? ", the garden watered" : ""));
        }
        ItemStack bag = carried(f, FieldItems.SEED_SATCHEL.get());
        if (!bag.isEmpty()) {
            int sown = t == null ? 0 : t.sown;
            parts.add("a seed satchel with " + SeedSatchelItem.total(bag) + " seed in it" + (sown > 0 ? ", " + sown + " sown from it today ("
                + Math.max(1, sown / A_TRIP) + (sown / A_TRIP <= 1 ? " trip" : " trips") + " to the stores saved)" : ""));
        }
        ItemStack sickle = carried(f, FieldItems.COPPER_SICKLE.get());
        if (!sickle.isEmpty()) {
            parts.add("a copper sickle (" + (sickle.getMaxDamage() - sickle.getDamageValue()) + " cuts left)"
                + (t != null && t.reaped > 0 ? ", " + t.reaped + " crops reaped today" : ""));
        }
        ItemStack smoker = carried(f, FieldItems.BEE_SMOKER.get());
        if (!smoker.isEmpty()) {
            parts.add("a bee smoker" + (t != null && t.hives > 0 ? ", " + t.hives + (t.hives == 1 ? " hive" : " hives") + " smoked and emptied today" : ""));
        }
        if (t != null) {
            if (t.eggs > 0) parts.add(t.eggs + (t.eggs == 1 ? " egg" : " eggs") + " from the nesting box today");
            if (t.fed > 0 || t.bred > 0) parts.add("the feed trough filled" + (t.bred > 0 ? ", " + t.bred + (t.bred == 1 ? " pair" : " pairs") + " bred off it" : ""));
            if (t.fish > 0) parts.add(t.fish + " fish from its traps today");
            if (t.made > 0) parts.add(t.made + " of the fields' tools made today");
        }
        if (f.stationTask() == StationTask.FISH && f.workZone() != null && f.level() instanceof ServerLevel level) {
            int traps = FieldBlockEntity.near(level, FieldBlockEntity.Kind.TRAP, f.workZone().center(), TRAP_REACH).size();
            if (traps > 0) parts.add(traps + (traps == 1 ? " fish trap" : " fish traps") + " set");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /** The town's tools of the fields, a line each, for /village items fields. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        out.add("FIELDS " + Villages.name(id) + ": " + Villages.ageOf(id).label);
        Map<Item, Integer> want = wanted(level, v);
        for (Item it : FieldItems.all()) {
            int stock = Market.stock(level, id, s -> s.is(it));
            out.add(new ItemStack(it).getHoverName().getString() + ": " + stock + " in the stores, wanted " + want.getOrDefault(it, 0)
                + ", the " + Tiers.of(level, it).label + ", worth " + String.format(Locale.ROOT, "%.2f", Prices.each(it)));
        }
        int boxes = 0, troughs = 0, traps = 0, barrels = 0, water = 0;
        for (BlockPos p : FieldBlockEntity.all(level, FieldBlockEntity.Kind.BOX)) if (p.distSqr(v.centre()) < 160 * 160) boxes++;
        for (BlockPos p : FieldBlockEntity.all(level, FieldBlockEntity.Kind.TROUGH)) if (p.distSqr(v.centre()) < 160 * 160) troughs++;
        for (BlockPos p : FieldBlockEntity.all(level, FieldBlockEntity.Kind.TRAP)) if (p.distSqr(v.centre()) < 160 * 160) traps++;
        for (BlockPos p : FieldBlockEntity.all(level, FieldBlockEntity.Kind.BARREL)) {
            if (p.distSqr(v.centre()) >= 160 * 160) continue;
            barrels++;
            water += RainBarrelBlock.water(level, p);
        }
        out.add("Set out: " + boxes + " nesting boxes, " + troughs + " feed troughs, " + traps + " fish traps, " + barrels + " rain barrels ("
            + water + " buckets of rain in them)");
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            String line = cardLine(f);
            if (line != null) out.add(f.displayNameCap() + " (" + f.stationTask().title + "): " + line);
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    public static void anyHourForTests(ServerLevel level, boolean on) {
        testingUntil = on ? level.getGameTime() + 1200L : Long.MIN_VALUE;
    }

    /** Tests: this folk's round now, whatever the stagger. */
    public static void roundForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        KIT.remove(f.getUUID());
        MADE.remove(f.getUUID());
        WANTS.remove(v.id());
        round(level, v, f, level.getGameTime());
    }

    /** Tests: the errand it is on done now, every stop, as though it had walked to each. Returns the stops done. */
    public static int errandNowForTests(ServerLevel level, VillageFolkEntity f) {
        Errand e = ERRANDS.remove(f.getUUID());
        if (e == null) return 0;
        int n = 0;
        for (e.at = 0; e.at < e.stops.size(); e.at++) {
            act(level, f, e, e.stops.get(e.at));
            n++;
        }
        return n;
    }

    /** Tests: what its errand is (its words), or null. */
    @Nullable
    public static String errandForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null ? null : e.what + ": " + e.doing + " " + e.stops;
    }

    @Nullable
    public static String craftForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return null;
        WANTS.remove(v.id());
        return craft(level, v, f);
    }

    /** Tests: a trade outside the crafts at the bench now (the rancher, the fisher, the smelter), for these. */
    @Nullable
    public static String makeForTests(ServerLevel level, VillageFolkEntity f, List<Item> items) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return null;
        MADE.remove(f.getUUID());
        WANTS.remove(v.id());
        return makeFor(level, v, f, level.getGameTime(), items);
    }

    public static Map<Item, Integer> wantedForTests(ServerLevel level, Villages.Village v) {
        demandOnce();                                                     // the shop's order book, as the first server round puts it there
        WANTS.remove(v.id());
        return wanted(level, v);
    }

    /** Tests: the town's barrel round now. */
    public static void townForTests(ServerLevel level, Villages.Village v) {
        WANTS.remove(v.id());
        town(level, v);
    }

    /** Tests: the boxes' hay and the troughs' pull now; and a trough's breeding now. */
    public static void boxesForTests(ServerLevel level) {
        boxes(level);
    }

    public static boolean breedForTests(ServerLevel level, BlockPos trough) {
        return level.getBlockEntity(trough) instanceof FieldBlockEntity be && breed(level, trough, be);
    }

    /** Tests: the can poured where the farmer stands now (its round's choice of patch), or null if none wanted it. */
    @Nullable
    public static WateringCanItem.Poured pourForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        WorkZone z = f.workZone();
        if (v == null || z == null) return null;
        long now = level.getGameTime();
        BlockPos patch = patch(level, f, z, Droughts.on(v.id()), now);
        return patch == null ? null : pourAt(level, v, f, patch, Droughts.on(v.id()), now);
    }

    /** Tests: Droughts' own bucket-carrying for this farmer, now (true if it set out with a bucket). */
    public static boolean droughtCarryForTests(VillageFolkEntity f, ServerLevel level) {
        f.tickCount = (f.tickCount / 40) * 40 + 49;
        boolean went = Droughts.carry(f, level);
        return went || Droughts.carrying(f);
    }

    /** Tests: is this farmer carrying water to its field in Droughts' own way (a bucket)? */
    public static boolean droughtCarryingForTests(VillageFolkEntity f) {
        return Droughts.carrying(f);
    }

    /** Tests: a bucket filled at this spot by Droughts' own rule (a barrel drawn from, or open water). */
    public static boolean droughtFillForTests(ServerLevel level, BlockPos at) {
        return Droughts.fill(level, at);
    }

    /** Tests: where the bucket chain would draw for a fire here. */
    @Nullable
    public static BlockPos chainWaterForTests(ServerLevel level, BlockPos fire) {
        return BucketChain.water(level, fire);
    }

    /** Tests: the reach of the sickle on the farmer's harvest at this crop (as FarmGoal calls it). */
    public static int reapForTests(VillageFolkEntity f, BlockPos centre) {
        return reap(f, centre);
    }

    /** Tests: the day's tally, "watered sown reaped eggs fed bred fish hives gardens made". */
    public static String tallyForTests(VillageFolkEntity f) {
        Tally t = tally(f);
        return t.watered + " " + t.sown + " " + t.reaped + " " + t.eggs + " " + t.fed + " " + t.bred + " " + t.fish + " " + t.hives + " "
            + t.gardens + " " + t.made;
    }

    /** Tests: the beekeeper's harvest now, on its meadow's hives as its craft finds them (Crafts.beekeep's first step). */
    @Nullable
    public static String smokeForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        WorkZone z = f.workZone();
        if (v == null || z == null) return null;
        return smokedHarvest(level, v, f, Crafts.hivesAt(level, z.center(), Math.min(6, z.radius())));
    }

    /** Tests: the town resolved on a cauldron of water by each workshop (as after a fire), and its works done now
     *  (FireSafety.cauldrons). Returns {set, the spots it keeps}. */
    public static int[] cauldronRuleForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return new int[2];
        Disasters.Town t = Disasters.town(village);
        t.cauldrons = true;
        int n = FireSafety.cauldrons(level, v, t);
        return new int[]{ n, t.cauldronsAt.size() };
    }

    /** Tests: would the fire brigade fill a bucket here (a full cauldron, or a barrel with water: FireBrigade.fullCauldron)? */
    public static boolean brigadeWaterForTests(BlockState st) {
        return FireBrigade.fullCauldron(st);
    }

    /** Tests: the trough's pull on the animals round it, now. Returns how many set off toward it. */
    public static int pullForTests(ServerLevel level, BlockPos trough) {
        if (!(level.getBlockEntity(trough) instanceof FieldBlockEntity be)) return 0;
        int n = 0;
        for (int i = 0; i < 40; i++) pull(level, trough, be);
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(trough).inflate(TROUGH_PULL, 4, TROUGH_PULL), Animal::isAlive)) {
            if (a.getNavigation().isInProgress()) n++;
        }
        return n;
    }

    /** Tests: the evening's garden round now. */
    public static void gardenForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        GARDENED.remove(f.getUUID());
        garden(level, v, f, level.getGameTime());
    }
}
