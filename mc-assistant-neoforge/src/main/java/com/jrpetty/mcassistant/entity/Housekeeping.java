package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [home-making] A household's stocks at home, kept and used as folk keep a house.
 *
 * <p>Each household keeps set stocks at home and tops them up itself:
 * <ul>
 * <li><b>Food in the larder</b>: two days of meals a head, four in the autumn (putting by for the winter), three in the
 *     winter, a day more when the town is short. Fresh food goes off: raw meat and fish in three days (to rotten flesh),
 *     cooked in five, bread in six, fruit and greens in ten; what keeps (dried kelp, honey, potatoes and roots, cake in
 *     its tin) keeps. Breakfast and supper are eaten at home out of it (Meals has supper; the morning bite is here).</li>
 * <li><b>Water</b>: a water butt at home (a cauldron), or water buckets kept in the larder, filled at the town's well or
 *     the river by one of the household on its round. Drunk, cooked and washed with every evening: a share a head. A
 *     house with no water near it walks the further for it, and a household that runs dry goes thirsty.</li>
 * <li><b>Fuel</b> for the stove: logs and coal, a little through the year for the cooking and a good stack by winter; the
 *     stove is fed every winter evening. A house that runs out in winter is a cold house: its folk are miserable in it and
 *     catch their death of cold.</li>
 * <li><b>Clothes</b>: a change for each grown member in the wardrobe; winter wear put on in the cold months and put
 *     back in the spring.</li>
 * <li><b>Tools</b>: each member's trade tool, its own, brought home at night and taken to work in the morning (HomeStores
 *     .toolsOut), a worn one replaced; and a bucket for the water.</li>
 * <li><b>Light, medicine, seed</b>: candles and torches for the long nights (a candle burns down every few winter
 *     nights), a remedy in the house for a cold (given there by the household), seed for the gardener's patch.</li>
 * </ul>
 * Running low puts it on the household's shopping list (Needs: bought through the shop's own counter, ShoppingTrips), or
 * the household gets it itself: the water fetched, the woodcutter's own firewood felled with its own axe outside the town,
 * raw food cooked on the home's stove, food borrowed from family or friends when the larder is bare (and paid back when it
 * is full again), and the town's poor box for the rest (PoorBox). Nothing comes from nothing: every meal, bucket and log
 * is a real thing out of a pack, a box, the shop or the world, and the coin only moves at the counter.
 */
public final class Housekeeping {

    private Housekeeping() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    // ------------------------------------------------------------------ the stocks

    /** What a household keeps at home. */
    public enum Stock {
        FOOD("food"), WATER("water"), FUEL("fuel"), LIGHT("candles"), CLOTHES("clothes"), TOOLS("tools"), MEDICINE("remedies"), SEED("seed");

        public final String words;

        Stock(String words) {
            this.words = words;
        }
    }

    static final Predicate<ItemStack> FOOD = Meals.FOOD;
    static final Predicate<ItemStack> FUEL = s -> s.is(ItemTags.COALS) || s.is(ItemTags.LOGS_THAT_BURN);
    static final Predicate<ItemStack> LIGHTS = s -> s.is(ItemTags.CANDLES) || s.is(Items.TORCH);
    static final Predicate<ItemStack> REMEDY = s -> Health.isRemedy(s);
    static final Predicate<ItemStack> SEEDS = s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(ItemTags.SMALL_FLOWERS);
    static final Predicate<ItemStack> WATER = s -> s.is(Items.WATER_BUCKET) || isWaterBottle(s);
    static final Predicate<ItemStack> BUCKET = s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET);

    static boolean isWaterBottle(ItemStack s) {
        return s.is(Items.POTION) && s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER);
    }

    /** Which of the stocks a thing is part of, or null. */
    @Nullable
    public static Stock stock(ItemStack s) {
        if (s.isEmpty()) return null;
        if (WATER.test(s)) return Stock.WATER;
        if (FOOD.test(s)) return Stock.FOOD;
        if (FUEL.test(s)) return Stock.FUEL;
        if (LIGHTS.test(s)) return Stock.LIGHT;
        if (Goods.clothes(s)) return Stock.CLOTHES;
        if (REMEDY.test(s)) return Stock.MEDICINE;
        if (s.is(Items.BUCKET)) return Stock.TOOLS;
        return null;
    }

    /** The heat in a fuel: a coal's four evenings for one of a log's. */
    static int heat(ItemStack s) {
        return s.is(ItemTags.COALS) ? 4 : s.is(ItemTags.LOGS_THAT_BURN) ? 1 : 0;
    }

    /** A household's stocks: what it has, and what it keeps. */
    public record Levels(Map<Stock, Integer> have, Map<Stock, Integer> want, int heads, int grown, boolean stove, int foodDays) {
        public int days() {
            int perDay = Math.max(1, heads * 2);
            return have.getOrDefault(Stock.FOOD, 0) / perDay;
        }

        public boolean shortOf(Stock s) {
            return have.getOrDefault(s, 0) < want.getOrDefault(s, 0);
        }
    }

    /** Forced season for the tests (null: the town's own). */
    @Nullable private static Seasons.Season SEASON_FOR_TESTS;

    public static void seasonForTests(@Nullable Seasons.Season s) {
        SEASON_FOR_TESTS = s;
    }

    static Seasons.Season season(UUID village, long day) {
        return SEASON_FOR_TESTS != null ? SEASON_FOR_TESTS : Seasons.season(village, day);
    }

    /** Is the town short of food (its stores under what it wants for a birth): every larder keeps a day more. */
    static boolean hardTimes(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        return Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(village)) < Villages.larderForBirth(village);
    }

    /** How many days of meals the larder keeps in this season. */
    static int foodDays(ServerLevel level, UUID village, long day) {
        int d = switch (season(village, day)) {
            case AUTUMN -> 4;
            case WINTER -> 3;
            default -> 2;
        };
        return d + (hardTimes(level, village) ? 1 : 0);
    }

    /** The heat the stove burns of an evening in this season (a coal is four, a log one). */
    static int burns(Seasons.Season s) {
        return s == Seasons.Season.WINTER ? 2 : s == Seasons.Season.AUTUMN ? 1 : 0;
    }

    /** A home's stocks counted now. */
    public static Levels levels(ServerLevel level, UUID village, Homes.Home h) {
        Map<Stock, Integer> have = new EnumMap<>(Stock.class), want = new EnumMap<>(Stock.class);
        for (Stock s : Stock.values()) { have.put(s, 0); want.put(s, 0); }
        long day = level.getDayTime() / 24000L;
        List<VillageFolkEntity> household = Homes.loadedMembers(village, h);
        int heads = Math.max(1, h.members.size()), grown = 0, children = 0;
        for (VillageFolkEntity f : household) if (f.isBaby()) children++; else grown++;
        if (household.isEmpty()) grown = heads;
        boolean stove = stoveOf(level, village, h) != null;
        Seasons.Season season = season(village, day);
        int days = foodDays(level, village, day);
        int heat = 0;
        boolean gardener = false;
        for (HomeStores.Box b : HomeStores.of(level, village, h)) {
            if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                if (s.is(Items.WATER_BUCKET)) have.merge(Stock.WATER, 3 * s.getCount(), Integer::sum);
                else if (isWaterBottle(s)) have.merge(Stock.WATER, s.getCount(), Integer::sum);
                else if (FOOD.test(s)) have.merge(Stock.FOOD, s.getCount(), Integer::sum);
                else if (FUEL.test(s)) heat += heat(s) * s.getCount();
                else if (LIGHTS.test(s)) have.merge(Stock.LIGHT, s.getCount(), Integer::sum);
                else if (Goods.clothes(s)) {
                    // Worn through (four fifths gone) is no change of clothes: it is noticed, and a new one wanted.
                    if (!s.isDamageableItem() || s.getDamageValue() * 5 < s.getMaxDamage() * 4) have.merge(Stock.CLOTHES, s.getCount(), Integer::sum);
                }
                else if (REMEDY.test(s)) have.merge(Stock.MEDICINE, s.getCount(), Integer::sum);
                else if (SEEDS.test(s)) have.merge(Stock.SEED, s.getCount(), Integer::sum);
            }
        }
        have.put(Stock.FUEL, heat);
        // The water in the butt, less what has been drawn from the buckets.
        BlockPos butt = buttOf(level, village, h);
        if (butt != null) {
            BlockState st = level.getBlockState(butt);
            if (st.is(Blocks.WATER_CAULDRON)) have.merge(Stock.WATER, st.getValue(LayeredCauldronBlock.LEVEL), Integer::sum);
        }
        have.put(Stock.WATER, Math.max(0, have.get(Stock.WATER) - drawn(village, h)));
        // The tools: each grown member's trade tool, on it or at home, and a bucket for the water.
        int tools = 0, toolsWanted = 0;
        for (VillageFolkEntity f : household) {
            if (f.isBaby()) continue;
            Persona p = f.persona();
            gardener |= p.hobby() == Persona.Hobby.GARDENING;
            Predicate<ItemStack> t = Cafe.toolFor(f.stationTask());
            if (t == null || f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
            toolsWanted++;
            if (f.countCarried(t) > 0 || HomeStores.count(level, village, h, s -> t.test(s) && Homes.ownedBy(s, f)) > 0) tools++;
        }
        toolsWanted++;
        if (buckets(level, village, h, household) > 0) tools++;
        have.put(Stock.TOOLS, tools);
        int perDay = grown + (children + 1) / 2;
        want.put(Stock.FOOD, heads * 2 * days);
        want.put(Stock.WATER, Math.max(3, 2 * perDay));
        want.put(Stock.FUEL, !stove ? 0 : season == Seasons.Season.WINTER ? 12 : season == Seasons.Season.AUTUMN ? 16 : 4);
        want.put(Stock.LIGHT, season == Seasons.Season.WINTER || season == Seasons.Season.AUTUMN ? 4 : 2);
        want.put(Stock.CLOTHES, grown);
        want.put(Stock.TOOLS, toolsWanted);
        want.put(Stock.MEDICINE, children > 0 ? 2 : 1);
        want.put(Stock.SEED, gardener ? 4 : 0);
        return new Levels(have, want, heads, grown, stove, days);
    }

    /** How many buckets (full or empty) the household has, on its folk or at home. */
    static int buckets(ServerLevel level, UUID village, Homes.Home h, List<VillageFolkEntity> household) {
        int n = HomeStores.count(level, village, h, s -> BUCKET.test(s) && HomeMaking.ours(s, h));
        for (VillageFolkEntity f : household) n += f.countCarried(s -> BUCKET.test(s) && HomeMaking.ours(s, h));
        return n;
    }

    /** The home's stove (a furnace or a smoker inside it), or null. */
    @Nullable
    static BlockPos stoveOf(ServerLevel level, UUID village, Homes.Home h) {
        HomeMaking.Plan p = HomeMaking.load(village, h.anchor.asLong());
        HomeMaking.Piece q = p == null ? null : p.piece("stove");
        if (q != null && q.standing()) {
            BlockPos at = BlockPos.of(q.pos);
            if (level.isLoaded(at) && level.getBlockState(at).getBlock() instanceof AbstractFurnaceBlock) return at;
        }
        HomeMaking.Rooms rooms = HomeMaking.rooms(level, village, h);
        for (BlockPos c : rooms.floor()) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                if (level.isLoaded(n) && level.getBlockState(n).getBlock() instanceof AbstractFurnaceBlock) return n.immutable();
            }
        }
        return null;
    }

    /** The home's water butt (its cauldron), or null. */
    @Nullable
    static BlockPos buttOf(ServerLevel level, UUID village, Homes.Home h) {
        HomeMaking.Plan p = HomeMaking.load(village, h.anchor.asLong());
        HomeMaking.Piece q = p == null ? null : p.piece("cauldron");
        if (q == null || !q.standing()) return null;
        BlockPos at = BlockPos.of(q.pos);
        BlockState s = level.isLoaded(at) ? level.getBlockState(at) : Blocks.AIR.defaultBlockState();
        return s.is(Blocks.CAULDRON) || s.is(Blocks.WATER_CAULDRON) ? at : null;
    }

    // ------------------------------------------------------------------ the home's own book

    /** What a home keeps in the ledger: water drawn from its buckets, its last evening, cold and dry days, what it owes. */
    static final class Book {
        int drawn;
        long evening = -1, cold = -100, dry = -100, candleNights;
        /** Food borrowed, by the anchor of the home it came from. */
        final Map<Long, Integer> owes = new ConcurrentHashMap<>();
    }

    private static final Map<String, Book> BOOKS = new ConcurrentHashMap<>();

    static Book book(UUID village, Homes.Home h) {
        String k = village + "/" + h.anchor.asLong();
        return BOOKS.computeIfAbsent(k, x -> {
            Book b = new Book();
            String s = Ledger.note(village, "homekeep/" + h.anchor.asLong());
            if (s != null && !s.isEmpty()) {
                try {
                    String[] p = s.split(";", -1);
                    b.drawn = Integer.parseInt(p[0]);
                    b.evening = Long.parseLong(p[1]);
                    b.cold = Long.parseLong(p[2]);
                    b.dry = Long.parseLong(p[3]);
                    b.candleNights = Long.parseLong(p[4]);
                    if (p.length > 5 && !p[5].isEmpty()) {
                        for (String o : p[5].split(",")) {
                            String[] kv = o.split("=");
                            b.owes.put(Long.parseLong(kv[0]), Integer.parseInt(kv[1]));
                        }
                    }
                } catch (RuntimeException ignored) { }
            }
            return b;
        });
    }

    static void save(UUID village, Homes.Home h, Book b) {
        StringBuilder owes = new StringBuilder();
        for (Map.Entry<Long, Integer> e : b.owes.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (owes.length() > 0) owes.append(',');
            owes.append(e.getKey()).append('=').append(e.getValue());
        }
        Ledger.note(village, "homekeep/" + h.anchor.asLong(), b.drawn + ";" + b.evening + ";" + b.cold + ";" + b.dry + ";" + b.candleNights + ";" + owes);
    }

    static int drawn(UUID village, Homes.Home h) {
        return book(village, h).drawn;
    }

    public static synchronized void resetForTests() {
        BOOKS.clear();
        CHORES.clear();
        NEXT_LOOK.clear();
        WATER_AT.clear();
        BREAKFAST.clear();
        SEASON_FOR_TESTS = null;
        INSTANT = false;
    }

    // ------------------------------------------------------------------ the evening's round

    /**
     * A household's evening at home, once a day (HomeLife.tick, after the day's work): what has gone off thrown out, the
     * water drunk and cooked and washed with, the stove fed in the cold months (or the house cold), a candle burnt down,
     * the raw food put on to cook, a sick member given a remedy out of the cupboard, food it borrowed paid back.
     */
    static void evening(ServerLevel level, Villages.Village v, Homes.Home h, long day) {
        UUID id = v.id();
        Book b = book(id, h);
        if (b.evening >= day) return;
        b.evening = day;
        List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
        if (household.isEmpty()) { save(id, h, b); return; }
        spoil(level, id, h, day);
        // Water: a share a head, drawn from the butt first, then the buckets in the larder.
        int grown = 0, children = 0;
        for (VillageFolkEntity f : household) if (f.isBaby()) children++; else grown++;
        int use = grown + (children + 1) / 2;
        int got = drink(level, id, h, b, use);
        if (got < use) {
            b.dry = day;
            for (VillageFolkEntity f : HomeMaking.grown(household)) { f.brain("no water at home tonight"); break; }
        }
        // Warmth: the stove fed of an evening in the cold months; with nothing to feed it, a cold house.
        Seasons.Season season = season(id, day);
        int heat = burns(season);
        BlockPos stove = stoveOf(level, id, h);
        if (heat > 0 && stove != null) {
            int burnt = burn(level, id, h, heat);
            if (burnt < heat) {
                b.cold = day;
                if (season == Seasons.Season.WINTER && Health.coldsHere(id, day)) {
                    for (VillageFolkEntity f : household) {
                        if (f.isBaby() || f.health().ill() || f.getRandom().nextInt(4) != 0) continue;
                        Health.catchCold(level, f, "in a cold house, with no fuel for the stove", day);
                    }
                }
            } else {
                lightStove(level, stove);
            }
        }
        // A candle burns down every few long nights.
        if (season == Seasons.Season.WINTER || season == Seasons.Season.AUTUMN) {
            b.candleNights++;
            if (b.candleNights % 4 == 0) takeOut(level, id, h, s -> s.is(ItemTags.CANDLES), 1);
        }
        cook(level, v, h, stove);
        dose(level, v, h, household, day);
        repay(level, v, h, b, household);
        save(id, h, b);
    }

    /** Water drawn for the evening: so many shares. Returns how many there were. */
    static int drink(ServerLevel level, UUID village, Homes.Home h, Book b, int use) {
        int got = 0;
        BlockPos butt = buttOf(level, village, h);
        while (got < use && butt != null) {
            BlockState st = level.getBlockState(butt);
            if (!st.is(Blocks.WATER_CAULDRON)) break;
            LayeredCauldronBlock.lowerFillLevel(st, level, butt);
            got++;
        }
        while (got < use) {
            // A bucket in the larder: three shares to a bucket, then it is an empty bucket again.
            if (HomeStores.count(level, village, h, s -> s.is(Items.WATER_BUCKET)) <= 0) break;
            b.drawn++;
            got++;
            if (b.drawn >= 3) {
                b.drawn = 0;
                swap(level, village, h, s -> s.is(Items.WATER_BUCKET), Items.BUCKET);
            }
        }
        if (got < use) {
            // A bottle of water.
            while (got < use && takeOut(level, village, h, Housekeeping::isWaterBottle, 1) > 0) {
                got++;
                ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
                VillageFolkEntity first = firstGrown(village, h);
                if (first != null) Homes.keepsake(bottle, first, Homes.Source.BOUGHT);
                HomeStores.putAway(level, village, h, bottle, false);
            }
        }
        return got;
    }

    @Nullable
    private static VillageFolkEntity firstGrown(UUID village, Homes.Home h) {
        for (VillageFolkEntity f : Homes.loadedMembers(village, h)) if (!f.isBaby()) return f;
        return null;
    }

    /** One of what matches in the home's boxes turned to another thing (a water bucket emptied), its owner kept. */
    private static boolean swap(ServerLevel level, UUID village, Homes.Home h, Predicate<ItemStack> what, net.minecraft.world.item.Item to) {
        for (HomeStores.Box box : HomeStores.of(level, village, h)) {
            if (!(level.getBlockEntity(box.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                ItemStack next = new ItemStack(to);
                if (s.has(DataComponents.CUSTOM_DATA)) next.set(DataComponents.CUSTOM_DATA, s.get(DataComponents.CUSTOM_DATA));
                s.shrink(1);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                ItemStack left = HomeStores.putAway(level, village, h, next, false);
                if (!left.isEmpty()) c.setItem(i, left);
                return true;
            }
        }
        return false;
    }

    /** So many of what matches taken out of the home's boxes and used up. Returns how many. */
    static int takeOut(ServerLevel level, UUID village, Homes.Home h, Predicate<ItemStack> what, int n) {
        int left = n;
        for (HomeStores.Box box : HomeStores.of(level, village, h)) {
            if (left <= 0) break;
            if (!(level.getBlockEntity(box.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int k = Math.min(left, s.getCount());
                s.shrink(k);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                left -= k;
            }
        }
        return n - left;
    }

    /** The stove fed: so much heat burnt out of the home's fuel, logs first and coal after. Returns the heat burnt. */
    static int burn(ServerLevel level, UUID village, Homes.Home h, int heat) {
        int burnt = 0;
        while (burnt < heat && takeOut(level, village, h, s -> s.is(ItemTags.LOGS_THAT_BURN), 1) > 0) burnt += 1;
        while (burnt < heat && takeOut(level, village, h, s -> s.is(ItemTags.COALS), 1) > 0) burnt += 4;
        return burnt;
    }

    /** The stove alight of an evening (its own burning, and the glow of it: the game's own lit state). */
    private static void lightStove(ServerLevel level, BlockPos stove) {
        BlockState st = level.getBlockState(stove);
        if (st.hasProperty(AbstractFurnaceBlock.LIT) && !st.getValue(AbstractFurnaceBlock.LIT)) {
            level.playSound(null, stove, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.6F, 1.0F);
        }
    }

    /** Spoilage: when it was put by, marked on it; what is past its days thrown out, raw meat and fish gone rotten. */
    static final String PUT_BY = "mca_put_by";

    /** How many days a food keeps (a very long time: it keeps). */
    static int keeps(ItemStack s) {
        if (s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN) || s.is(Items.MUTTON) || s.is(Items.RABBIT)
            || s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.TROPICAL_FISH)) return 3;
        if (s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_CHICKEN) || s.is(Items.COOKED_MUTTON)
            || s.is(Items.COOKED_RABBIT) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON) || s.is(Items.RABBIT_STEW)
            || s.is(Items.MUSHROOM_STEW) || s.is(Items.BEETROOT_SOUP)) return 5;
        if (s.is(Items.BREAD) || s.is(Items.PUMPKIN_PIE) || s.is(Items.COOKIE)) return 6;
        if (s.is(Items.APPLE) || s.is(Items.SWEET_BERRIES) || s.is(Items.GLOW_BERRIES) || s.is(Items.MELON_SLICE) || s.is(Items.BEETROOT)) return 10;
        if (s.is(Items.ROTTEN_FLESH) || s.is(Items.POISONOUS_POTATO)) return 1;          // found, and thrown out the next day
        return 10000;
    }

    static void spoil(ServerLevel level, UUID village, Homes.Home h, long day) {
        for (HomeStores.Box box : HomeStores.of(level, village, h)) {
            if (!(level.getBlockEntity(box.pos()) instanceof Container c)) continue;
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.get(DataComponents.FOOD) == null || Homes.ownerOf(s) == null) continue;   // the household's own
                int keeps = keeps(s);
                if (keeps >= 10000) continue;
                CompoundTag t = s.has(DataComponents.CUSTOM_DATA) ? s.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
                if (!t.contains(PUT_BY)) {
                    t.putLong(PUT_BY, day);
                    s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
                    changed = true;
                    continue;
                }
                if (day - t.getLong(PUT_BY) < keeps) continue;
                boolean meat = keeps == 3 || s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_CHICKEN)
                    || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_RABBIT) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON);
                if (meat) {
                    ItemStack rot = new ItemStack(Items.ROTTEN_FLESH, s.getCount());
                    CompoundTag r = t.copy();
                    r.putLong(PUT_BY, day);
                    rot.set(DataComponents.CUSTOM_DATA, CustomData.of(r));
                    c.setItem(i, rot);
                } else {
                    c.setItem(i, ItemStack.EMPTY);                     // gone stale and thrown on the midden
                }
                changed = true;
                LOG.info("[MCA-HOMEKEEP] {} {} went off in a larder of {}", s.getCount(), s.getHoverName().getString(), Villages.name(village));
            }
            if (changed) c.setChanged();
        }
    }

    /**
     * The raw food in the larder put on to cook on the home's stove (the game's own furnace does the cooking, with the
     * household's own fuel), and what it cooked yesterday taken off and put by.
     */
    static int cook(ServerLevel level, Villages.Village v, Homes.Home h, @Nullable BlockPos stove) {
        if (stove == null || !(level.getBlockEntity(stove) instanceof AbstractFurnaceBlockEntity oven)) return 0;
        UUID id = v.id();
        VillageFolkEntity cook = firstGrown(id, h);
        if (cook == null) return 0;
        // Yesterday's, off the stove.
        ItemStack done = oven.getItem(2);
        if (!done.isEmpty()) {
            ItemStack dish = done.copy();
            Homes.keepsake(dish, cook, Homes.Source.CRAFT);
            ItemStack left = HomeStores.putAway(level, id, h, dish, false);
            oven.setItem(2, left);
        }
        if (!oven.getItem(0).isEmpty()) return 0;                       // still cooking
        boolean smoker = level.getBlockState(stove).is(Blocks.SMOKER);
        int put = 0;
        for (HomeStores.Box box : HomeStores.of(level, id, h)) {
            if (!(level.getBlockEntity(box.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && put == 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.get(DataComponents.FOOD) == null || !HomeMaking.ours(s, h)) continue;
                ItemStack result = cooked(level, smoker, s);
                if (result.isEmpty() || result.get(DataComponents.FOOD) == null) continue;
                int n = Math.min(4, s.getCount());
                // The fire under it: a coal does eight, a log one and a half.
                ItemStack fuel = oven.getItem(1);
                if (fuel.isEmpty()) {
                    int heat = n <= 1 ? 1 : 4;
                    Predicate<ItemStack> f = heat >= 4 ? (x -> x.is(ItemTags.COALS)) : (x -> x.is(ItemTags.LOGS_THAT_BURN));
                    ItemStack fu = takeFirst(level, id, h, f);
                    if (fu.isEmpty()) fu = takeFirst(level, id, h, x -> x.is(ItemTags.COALS) || x.is(ItemTags.LOGS_THAT_BURN));
                    if (fu.isEmpty()) return 0;                          // nothing to cook it over
                    if (fu.is(ItemTags.LOGS_THAT_BURN)) n = 1;
                    oven.setItem(1, fu);
                }
                oven.setItem(0, s.copyWithCount(n));
                s.shrink(n);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                oven.setChanged();
                put = n;
            }
            if (put > 0) break;
        }
        if (put > 0) {
            cook.brain("put supper on the stove at home");
            if (cook.distanceToSqr(stove.getX() + 0.5, stove.getY(), stove.getZ() + 0.5) < 64.0) {
                cook.swing(InteractionHand.MAIN_HAND);
                FolkTalk.speak(cook, FolkTalk.pick(cook.getRandom(), "Supper's on.", "That'll be ready by morning, with luck.", "A bit of something hot tonight."));
            }
        }
        return put;
    }

    /** What the game's own recipe makes of this on a furnace (or a smoker), or nothing. */
    static ItemStack cooked(ServerLevel level, boolean smoker, ItemStack raw) {
        SingleRecipeInput in = new SingleRecipeInput(raw.copyWithCount(1));
        if (smoker) {
            return level.getRecipeManager().getRecipeFor(RecipeType.SMOKING, in, level)
                .map(r -> r.value().getResultItem(level.registryAccess())).orElse(ItemStack.EMPTY);
        }
        return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, in, level)
            .map(r -> r.value().getResultItem(level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    /** One of what matches out of the home's boxes, the household's own (the fuel for the stove). */
    private static ItemStack takeFirst(ServerLevel level, UUID village, Homes.Home h, Predicate<ItemStack> what) {
        for (HomeStores.Box box : HomeStores.of(level, village, h)) {
            if (!(level.getBlockEntity(box.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                ItemStack one = s.copyWithCount(1);
                s.shrink(1);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** A sick member at home given a remedy out of the household's cupboard by whoever is about. */
    static boolean dose(ServerLevel level, Villages.Village v, Homes.Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        for (VillageFolkEntity f : household) {
            Health.State s = f.health();
            if (!s.ill() || f.blockPosition().distSqr(h.anchor) > 16 * 16) continue;
            for (Health.Remedy r : List.of(Health.HONEY, Health.CARROT, Health.BERRIES, Health.POTION)) {
                ItemStack got = takeFirst(level, id, h, x -> r.what().test(x) && HomeMaking.ours(x, h));
                if (got.isEmpty()) continue;
                if (r == Health.POTION) Health.drink(f, got);
                else f.heal(r.heals());
                if (r.bottle()) {
                    ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
                    Homes.keepsake(bottle, f, Homes.Source.BOUGHT);
                    HomeStores.putAway(level, id, h, bottle, false);
                }
                s.cold -= r.eases();
                if (s.cold <= 0) Health.recovered(level, f, s, day);
                VillageFolkEntity carer = null;
                for (VillageFolkEntity o : household) if (o != f && !o.isBaby()) { carer = o; break; }
                String who = carer == null ? f.displayNameCap() : carer.displayNameCap();
                s.tendedBy = who;
                s.tendedWith = r.word() + " from the cupboard at home";
                f.persona().remember(day, (carer == null ? "I had " : who + " gave me ") + r.word() + " from our cupboard when I was ill", 2);
                if (carer != null) FolkTalk.speak(carer, FolkTalk.pick(carer.getRandom(), "Here — " + r.word() + ". We keep some by for this.",
                    "Drink this up. That's what the cupboard's for."));
                return true;
            }
        }
        return false;
    }

    /** Food borrowed of a neighbour's larder, paid back now the larder is full again. */
    static void repay(ServerLevel level, Villages.Village v, Homes.Home h, Book b, List<VillageFolkEntity> household) {
        if (b.owes.isEmpty()) return;
        UUID id = v.id();
        Levels l = levels(level, id, h);
        for (Map.Entry<Long, Integer> e : List.copyOf(b.owes.entrySet())) {
            Homes.Home lender = Homes.homes(id).get(e.getKey());
            if (lender == null || lender.members.isEmpty()) { b.owes.remove(e.getKey()); continue; }
            int spare = l.have().get(Stock.FOOD) - l.want().get(Stock.FOOD);
            if (spare < e.getValue()) continue;
            VillageFolkEntity to = firstGrown(id, lender);
            if (to == null) continue;
            int n = 0;
            for (int i = 0; i < e.getValue(); i++) {
                ItemStack one = takeFirst(level, id, h, s -> FOOD.test(s) && HomeMaking.ours(s, h));
                if (one.isEmpty()) break;
                Homes.keepsake(one, to, Homes.Source.GIFT);
                ItemStack left = HomeStores.putAway(level, id, lender, one, false);
                if (!left.isEmpty()) { HomeStores.putAway(level, id, h, left, false); break; }
                n++;
            }
            if (n > 0) {
                b.owes.put(e.getKey(), e.getValue() - n);
                VillageFolkEntity payer = firstGrown(id, h);
                if (payer != null) {
                    payer.persona().remember(level.getDayTime() / 24000L, "we paid back the food we borrowed of " + Homes.names(Homes.loadedMembers(id, lender)), 2);
                    payer.brain("paid back " + n + " food it borrowed");
                }
            }
        }
        b.owes.values().removeIf(x -> x <= 0);
    }

    // ------------------------------------------------------------------ the morning, and the tools home at night

    private static final Map<UUID, Long> BREAKFAST = new ConcurrentHashMap<>();

    /**
     * The morning (HomeLife.tick, from first light to the start of work): breakfast at home out of the larder, the tool of
     * its trade out of the household chest (HomeStores.toolsOut), and winter wear on in the cold months (back in the
     * wardrobe in the spring).
     */
    static void morning(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        UUID id = v.id();
        Homes.Home h = Homes.homeOf(id, f.getUUID());
        if (h == null || f.isSleeping() || f.blockPosition().distSqr(h.anchor) > 16 * 16) return;
        if (BREAKFAST.getOrDefault(f.getUUID(), -1L) != day && HomeStores.takeFood(level, id, h, f, FOOD)) {
            BREAKFAST.put(f.getUUID(), day);
            if (f.eatFromPack()) f.brain("had its breakfast at home");
        }
        if (!f.isBaby()) HomeStores.toolsOut(level, f);
        dress(level, id, h, f, day);
    }

    /** Winter wear: boots out of the wardrobe and on, in the winter; off and back in it in the spring. */
    static void dress(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f, long day) {
        Seasons.Season s = season(village, day);
        ItemStack feet = f.getItemBySlot(EquipmentSlot.FEET);
        if (s == Seasons.Season.WINTER && feet.isEmpty()) {
            ItemStack boots = takeFirst(level, village, h, x -> x.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == EquipmentSlot.FEET
                && Homes.ownedBy(x, f));
            if (!boots.isEmpty()) {
                f.setItemSlot(EquipmentSlot.FEET, boots);
                f.brain("put its winter boots on, out of the wardrobe");
            }
        } else if (s == Seasons.Season.SPRING && feet.getItem() instanceof ArmorItem && Homes.ownedBy(feet, f) && feet.is(Items.LEATHER_BOOTS)) {
            ItemStack left = HomeStores.putAway(level, village, h, feet.copy(), f.isBaby());
            if (left.isEmpty()) {
                f.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                f.brain("put its winter boots away till next winter");
            }
        }
    }

    /** At home of an evening: the tool of its trade (its own) out of its pack and into the household chest for the night. */
    static boolean toolsHome(ServerLevel level, UUID village, VillageFolkEntity f) {
        Predicate<ItemStack> tool = Cafe.toolFor(f.stationTask());
        if (tool == null || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.GUARD) return false;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null || f.blockPosition().distSqr(h.anchor) > 10 * 10) return false;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !tool.test(s) || !Homes.ownedBy(s, f)) continue;
            Container chest = HomeStores.box(level, village, h, HomeStores.Kind.CHEST);
            if (chest == null) return false;
            ItemStack left = Homes.insertInto(chest, s.copy());
            if (!left.isEmpty()) return false;
            pack.set(i, ItemStack.EMPTY);
            chest.setChanged();
            f.brain("put its " + Goods.name(s).toLowerCase(java.util.Locale.ROOT) + " away at home for the night");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the chores

    /** A chore a folk does for its household, in its own time. */
    enum Chore { WATER, FIREWOOD, PRODUCE, BORROW }

    private static final class Doing {
        final Chore chore;
        final long started;
        int phase;
        int walked = -1000;
        @Nullable BlockPos to;
        long lender = HomeMaking.NONE;

        Doing(Chore chore, long started) {
            this.chore = chore;
            this.started = started;
        }
    }

    private static final Map<UUID, Doing> CHORES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_LOOK = new ConcurrentHashMap<>();
    /** Where each home fetches its water from, and the day it was looked for (by anchor). */
    private static final Map<Long, Object[]> WATER_AT = new ConcurrentHashMap<>();
    private static boolean INSTANT;

    /**
     * In its own time (VillageFolkEntity.socialise): a chore for its household if one wants doing and nobody else of
     * it is at it: water fetched when the butt runs low, firewood felled by a woodcutter before the winter, food
     * borrowed of family or friends when the larder is bare. True while it is about it.
     */
    public static boolean chores(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isSleeping() || f.isShowcase() || f.isHired() || Homes.lodging(f)) return false;
        Villages.Village v = Villages.get(village);
        Homes.Home h = v == null ? null : Homes.homeOf(village, f.getUUID());
        if (h == null || !level.isLoaded(h.anchor)) return false;
        long now = level.getGameTime();
        Doing d = CHORES.get(f.getUUID());
        if (d == null) {
            if (now < NEXT_LOOK.getOrDefault(f.getUUID(), 0L)) return false;
            NEXT_LOOK.put(f.getUUID(), now + 600L);
            d = choose(level, v, h, f);
            if (d == null) return false;
            CHORES.put(f.getUUID(), d);
        }
        if (now - d.started > 6000L) {
            CHORES.remove(f.getUUID());
            f.brain("gave up on a chore for its home");
            return false;
        }
        return step(level, v, h, f, d);
    }

    /** The chore it is for, if any (and no other of its household at it). */
    @Nullable
    private static Doing choose(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f) {
        UUID id = v.id();
        for (UUID m : h.members) if (!m.equals(f.getUUID()) && CHORES.containsKey(m)) return null;   // one of them at a chore at a time
        Levels l = levels(level, id, h);
        long now = level.getGameTime();
        List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
        if (l.shortOf(Stock.WATER) && buckets(level, id, h, household) > 0 && waterFor(level, id, h) != null) return new Doing(Chore.WATER, now);
        Seasons.Season s = season(id, level.getDayTime() / 24000L);
        if (f.stationTask() == AssistantEntity.StationTask.WOOD && l.stove() && (s == Seasons.Season.AUTUMN || s == Seasons.Season.WINTER)
            && l.shortOf(Stock.FUEL) && f.countCarried(x -> x.getItem() instanceof AxeItem) > 0) return new Doing(Chore.FIREWOOD, now);
        if (l.shortOf(Stock.FOOD) && produce(f) != null && Purchases.open(id) && f.purse() > ShoppingTrips.KEEP_BY) return new Doing(Chore.PRODUCE, now);
        if (l.have().get(Stock.FOOD) == 0 && f.countFood() == 0) {
            Homes.Home lh = lender(level, v, h, f);
            if (lh != null) {
                Doing d = new Doing(Chore.BORROW, now);
                d.lender = lh.anchor.asLong();                    // whose door it knocks on, chosen once
                return d;
            }
        }
        return null;
    }

    /** One look at a chore: walk on, or, there, do it. True while it is about it. */
    private static boolean step(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, Doing d) {
        UUID id = v.id();
        BlockPos dest = switch (d.chore) {
            case WATER -> d.phase == 0 ? waterFor(level, id, h) : homeSpot(level, id, h);
            case FIREWOOD -> {
                if (d.phase == 0 && d.to == null) d.to = tree(level, v, h);
                yield d.phase == 0 ? d.to : homeSpot(level, id, h);
            }
            case BORROW -> {
                if (d.phase == 0) {
                    Homes.Home lh = Homes.homes(id).get(d.lender);
                    yield lh == null ? null : homeSpot(level, id, lh);
                }
                yield homeSpot(level, id, h);
            }
            case PRODUCE -> d.phase == 0 ? Neighbourly.storesSpot(level, v) : homeSpot(level, id, h);
        };
        if (dest == null) { CHORES.remove(f.getUUID()); return false; }
        if (!INSTANT && f.blockPosition().distSqr(dest) > 3.0 * 3.0) {
            if (f.getNavigation().isDone() || f.tickCount - d.walked >= 100) {
                f.walkTo(dest, 0.9D);
                d.walked = f.tickCount;
            }
            f.hobbyNow = switch (d.chore) {
                case WATER -> d.phase == 0 ? "off to fetch water" : "carrying water home";
                case FIREWOOD -> d.phase == 0 ? "off to cut firewood" : "carrying firewood home";
                case BORROW -> d.phase == 0 ? "going to borrow a bite to eat" : "taking borrowed food home";
                case PRODUCE -> d.phase == 0 ? "off to get some of its own produce for home" : "taking its produce home";
            };
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        if (!INSTANT) f.getNavigation().stop();
        boolean ok = switch (d.chore) {
            case WATER -> d.phase == 0 ? fill(level, id, h, f, dest) : pour(level, id, h, f);
            case FIREWOOD -> d.phase == 0 ? fell(level, v, f, dest) : putAwayOwn(level, id, h, f, FUEL) > 0;
            case BORROW -> d.phase == 0 ? borrow(level, v, h, f, d.lender) : putAwayOwn(level, id, h, f, FOOD) > 0;
            case PRODUCE -> d.phase == 0 ? fetchProduce(level, v, h, f) : putAwayOwn(level, id, h, f, FOOD) > 0;
        };
        if (!ok) { CHORES.remove(f.getUUID()); return false; }
        d.phase++;
        d.walked = -1000;
        if (d.phase >= 2) CHORES.remove(f.getUUID());
        return true;
    }

    /** The household chest to come home to, or the door. */
    private static BlockPos homeSpot(ServerLevel level, UUID village, Homes.Home h) {
        BlockPos chest = Homes.chestOf(level, village, h);
        return chest != null ? chest : h.anchor;
    }

    /**
     * Where a home fetches its water: the town's well or fountain if it has one, else the nearest water that will not run
     * dry (a source with others beside it: a pond, the river), the nearest the home first. Null if none within a walk.
     */
    @Nullable
    static BlockPos waterFor(ServerLevel level, UUID village, Homes.Home h) {
        long day = level.getDayTime() / 24000L;
        Object[] seen = WATER_AT.get(h.anchor.asLong());
        if (seen != null && (long) seen[0] == day) return (BlockPos) seen[1];
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (com.jrpetty.mcassistant.village.Ledger.Building b : Ledger.buildings(village)) {
            String s = b.structure();
            if (!(s.equals("well") || s.equals("fountain") || s.equals("aqueduct") || s.equals("watermill"))) continue;
            for (BlockPos p : BlockPos.betweenClosed(b.anchor().offset(-4, -4, -4), b.anchor().offset(4, 3, 4))) {
                if (!level.isLoaded(p) || !lasting(level, p)) continue;
                double dd = p.distSqr(h.anchor);
                if (dd < bestD) { bestD = dd; best = p.immutable(); }
            }
        }
        if (best == null) {
            for (int dx = -40; dx <= 40; dx += 2) {
                for (int dz = -40; dz <= 40; dz += 2) {
                    int x = h.anchor.getX() + dx, z = h.anchor.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                    if (!lasting(level, top)) continue;
                    double dd = top.distSqr(h.anchor);
                    if (dd < bestD) { bestD = dd; best = top; }
                }
            }
        }
        WATER_AT.put(h.anchor.asLong(), new Object[]{ day, best });
        return best;
    }

    /** Water that a bucket can be filled at without running it dry: a source with two more beside it, as the game has it. */
    static boolean lasting(ServerLevel level, BlockPos p) {
        if (!level.getFluidState(p).is(FluidTags.WATER) || !level.getFluidState(p).isSource()) return false;
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(p.relative(d)).is(FluidTags.WATER) && level.getFluidState(p.relative(d)).isSource()) n++;
        }
        return n >= 2;
    }

    /** At the water: its household's empty buckets (taken out of the house as it left) filled. */
    private static boolean fill(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f, BlockPos water) {
        if (!lasting(level, water)) return false;
        // The buckets from home, if it did not have them on it.
        if (f.countCarried(s -> s.is(Items.BUCKET) && HomeMaking.ours(s, h)) == 0) {
            HomeMaking.takeFromBoxes(level, village, h, f, s -> s.is(Items.BUCKET), 1);
        }
        int filled = 0;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size() && filled < 2; i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !s.is(Items.BUCKET) || !HomeMaking.ours(s, h)) continue;
            ItemStack full = new ItemStack(Items.WATER_BUCKET);
            if (s.has(DataComponents.CUSTOM_DATA)) full.set(DataComponents.CUSTOM_DATA, s.get(DataComponents.CUSTOM_DATA));
            s.shrink(1);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
            ItemStack left = f.insertItem(full);
            if (!left.isEmpty()) { f.insertItem(new ItemStack(Items.BUCKET)); break; }
            filled++;
            i = -1;                                                       // the pack changed: look again from the start
        }
        if (filled == 0) return false;
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, water, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 0.8F, 1.0F);
        f.brain("filled " + (filled == 1 ? "a bucket" : filled + " buckets") + " with water for home");
        return true;
    }

    /** Home with the water: into the butt if it has one, else the buckets kept full in the larder. */
    static boolean pour(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f) {
        BlockPos butt = buttOf(level, village, h);
        int poured = 0;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !s.is(Items.WATER_BUCKET) || !HomeMaking.ours(s, h)) continue;
            BlockState st = butt == null ? null : level.getBlockState(butt);
            if (st != null && (st.is(Blocks.CAULDRON) || st.is(Blocks.WATER_CAULDRON) && st.getValue(LayeredCauldronBlock.LEVEL) < 3)) {
                level.setBlock(butt, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), 3);
                level.playSound(null, butt, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
                ItemStack empty = new ItemStack(Items.BUCKET);
                if (s.has(DataComponents.CUSTOM_DATA)) empty.set(DataComponents.CUSTOM_DATA, s.get(DataComponents.CUSTOM_DATA));
                pack.set(i, empty);
                poured++;
                continue;
            }
            ItemStack left = HomeStores.putAway(level, village, h, s.copy(), false);
            pack.set(i, left);
            if (left.isEmpty()) poured++;
        }
        // The empty buckets go back in the house.
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !s.is(Items.BUCKET) || !HomeMaking.ours(s, h)) continue;
            pack.set(i, HomeStores.putAway(level, village, h, s.copy(), false));
        }
        if (poured > 0) {
            f.swing(InteractionHand.MAIN_HAND);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — water in for the evening.", "That's the water fetched.", "Full again. Heavy work, that."));
            f.brain("brought water home");
        }
        return poured > 0;
    }

    /**
     * A tree outside the town for the woodcutter's own firewood: a log with leaves on it, on open ground (no building's,
     * no orchard's or park's), within a walk of home. Null if none.
     */
    @Nullable
    private static BlockPos tree(ServerLevel level, Villages.Village v, Homes.Home h) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -48; dx <= 48; dx += 2) {
            for (int dz = -48; dz <= 48; dz += 2) {
                int x = h.anchor.getX() + dx, z = h.anchor.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos log = new BlockPos(x, top - 1, z);
                if (!level.getBlockState(log).is(BlockTags.LOGS_THAT_BURN)) continue;
                boolean leaves = false;
                for (BlockPos n : BlockPos.betweenClosed(log.offset(-2, 0, -2), log.offset(2, 3, 2))) {
                    if (level.getBlockState(n).is(BlockTags.LEAVES)) { leaves = true; break; }
                }
                if (!leaves || TownBlocks.zone(level, v, log) != TownBlocks.Zone.OPEN || Homes.homeAt(v.id(), log) != null) continue;
                double dd = log.distSqr(h.anchor);
                if (dd < bestD) { bestD = dd; best = log; }
            }
        }
        return best;
    }

    /** Two logs off the tree with its own axe (the wood its own: gathered in its own time), the axe the worse for it. */
    private static boolean fell(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos log) {
        ItemStack axe = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && s.getItem() instanceof AxeItem) { axe = s; break; }
        if (axe.isEmpty()) return false;
        int got = 0;
        BlockPos at = log;
        for (int i = 0; i < 2; i++) {
            BlockState st = level.getBlockState(at);
            if (!st.is(BlockTags.LOGS_THAT_BURN) || !TownBlocks.mayBreak(level, v, at, TownBlocks.Why.FIELD)) break;
            ItemStack wood = new ItemStack(st.getBlock().asItem());
            level.destroyBlock(at, false, f);
            Homes.keepsake(wood, f, Homes.Source.CRAFT);
            ItemStack left = f.insertItem(wood);
            if (!left.isEmpty()) f.spawnAtLocation(left);
            axe.hurtAndBreak(1, f, EquipmentSlot.MAINHAND);
            got++;
            at = at.below();
        }
        if (got == 0) return false;
        f.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A bit of firewood for our own hearth.", "That'll keep us warm a night or two."));
        f.brain("cut " + got + " logs of firewood for home");
        return true;
    }

    /** The household's own of what matches, out of its pack and put away at home. Returns how many. */
    static int putAwayOwn(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f, Predicate<ItemStack> what) {
        int n = 0;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !what.test(s) || !HomeMaking.ours(s, h) || Keepsakes.isCarried(s)) continue;
            ItemStack left = HomeStores.putAway(level, village, h, s.copy(), f.isBaby());
            n += s.getCount() - left.getCount();
            pack.set(i, left);
        }
        return n;
    }

    /**
     * What a folk's own trade grows or catches that a household keeps in its larder: the farmer's bread and roots, the
     * fisher's fish, the rancher's and the hunter's meat (raw: cooked on the home's stove). Null for the other trades.
     */
    @Nullable
    static Predicate<ItemStack> produce(VillageFolkEntity f) {
        return switch (f.stationTask()) {
            case FARM -> s -> s.is(Items.BREAD) || s.is(Items.POTATO) || s.is(Items.CARROT) || s.is(Items.BEETROOT);
            case FISH, DIVER -> s -> s.is(Items.COD) || s.is(Items.SALMON);
            case RANCH, HUNT -> s -> s.is(Items.BEEF) || s.is(Items.MUTTON) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN) || s.is(Items.RABBIT);
            default -> null;
        };
    }

    /**
     * At the stores: some of what its own trade brought in, for its larder at home, paid for at the town's price out of
     * its purse (Purchases.get: the same counter every folk's food goes over), and marked its own.
     */
    private static boolean fetchProduce(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f) {
        Predicate<ItemStack> what = produce(f);
        if (what == null || !Purchases.open(v.id())) return false;
        Levels l = levels(level, v.id(), h);
        int n = Math.max(1, Math.min(6, l.want().get(Stock.FOOD) - l.have().get(Stock.FOOD)));
        Map<net.minecraft.world.item.Item, Integer> before = loose(f, what);
        int got = Purchases.get(level, f, what, n, Purchases.Need.FOOD);
        if (got <= 0) return false;
        Map<net.minecraft.world.item.Item, Integer> after = loose(f, what);
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : after.entrySet()) {
            int more = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (more > 0) HomeMaking.ownIt(f, new ItemStack(e.getKey(), more), Homes.Source.BOUGHT);
        }
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("took " + got + " of its own trade's produce home for the larder, paid for");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A few of my own for our larder.", "Can't grow it all day and eat none of it.", "Some of today's for home."));
        return true;
    }

    /** What matches in its pack that is nobody's own (counted by kind). */
    private static Map<net.minecraft.world.item.Item, Integer> loose(VillageFolkEntity f, Predicate<ItemStack> what) {
        Map<net.minecraft.world.item.Item, Integer> out = new java.util.HashMap<>();
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && what.test(s) && !Homes.isKeepsake(s)) out.merge(s.getItem(), s.getCount(), Integer::sum);
        return out;
    }

    /** A household that can spare a bite: family first (parents, grown children), then friends; with food over its own two days. */
    @Nullable
    static Homes.Home lender(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f) {
        UUID id = v.id();
        Homes.Home best = null;
        int bestRank = -1;
        Set<UUID> friends = new HashSet<>();
        for (Map.Entry<UUID, Social.Bond> e : f.life().bonds.entrySet()) if (e.getValue().affinity >= Social.FRIEND) friends.add(e.getKey());
        for (Homes.Home o : Homes.homes(id).values()) {
            if (o == h || o.members.isEmpty() || !level.isLoaded(o.anchor)) continue;
            int rank = -1;
            for (VillageFolkEntity m : Homes.loadedMembers(id, o)) {
                if (m.isBaby()) continue;
                if (Homes.childOf(f, m) || Homes.childOf(m, f)) rank = Math.max(rank, 2);
                else if (friends.contains(m.getUUID())) rank = Math.max(rank, 1);
            }
            if (rank < 0) continue;
            Levels l = levels(level, id, o);
            if (l.have().get(Stock.FOOD) < l.heads() * 2 * 2 + 2) continue;          // nothing over its own two days
            if (rank > bestRank) { bestRank = rank; best = o; }
        }
        return best;
    }

    /** At the lender's: a day's food for its household, out of the lender's larder, its own now and owed back. */
    private static boolean borrow(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, long lenderAt) {
        UUID id = v.id();
        Homes.Home o = Homes.homes(id).get(lenderAt);
        if (o == null) return false;
        Levels l = levels(level, id, o);
        int spare = l.have().get(Stock.FOOD) - l.heads() * 2 * 2;
        int n = Math.min(spare, Math.max(2, h.members.size() * 2));
        int got = 0;
        for (int i = 0; i < n; i++) {
            ItemStack one = takeFirst(level, id, o, s -> FOOD.test(s) && HomeMaking.ours(s, o));
            if (one.isEmpty()) break;
            Homes.keepsake(one, f, Homes.Source.GIFT);
            ItemStack left = f.insertItem(one);
            if (!left.isEmpty()) { HomeStores.putAway(level, id, o, left, false); break; }
            got++;
        }
        if (got == 0) return false;
        Book b = book(id, h);
        b.owes.merge(o.anchor.asLong(), got, Integer::sum);
        save(id, h, b);
        VillageFolkEntity giver = firstGrown(id, o);
        long day = level.getDayTime() / 24000L;
        f.persona().remember(day, "we borrowed food of " + (giver == null ? "the neighbours" : giver.displayNameCap()) + " when our larder was bare", 2);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Our larder's bare — could you spare a bite? We'll pay it back.",
            "I hate to ask, but we've nothing in. I'll make it good, I promise."));
        if (giver != null) {
            giver.life().feel(f.getUUID(), f.displayNameCap(), 2);
            FolkTalk.speak(giver, FolkTalk.pick(giver.getRandom(), "Of course. Take what you need.", "Here — nobody goes hungry next door to us."));
        }
        LOG.info("[MCA-HOMEKEEP] {} of {} borrowed {} food of {}", f.displayNameCap(), Villages.name(id), got, giver == null ? "?" : giver.displayNameCap());
        return true;
    }

    // ------------------------------------------------------------------ the shopping list

    /**
     * [home-making] What a household's stocks put on the shopping list (Needs.make), by the same rules as its own: the
     * provider's list (the best-off grown member), within its means. A bucket for the water and the larder's winter
     * stock are necessities; fuel before the winter; a change of clothes, a remedy for the cupboard and the garden's
     * seed as they can run to them; a new tool for a worn one.
     */
    static List<Needs.Want> wants(Needs.Ctx c, int floor) {
        try {
            return listFor(c, floor);
        } catch (RuntimeException e) {
            return List.of();                             // a home half-loaded: its stocks are counted another day
        }
    }

    private static List<Needs.Want> listFor(Needs.Ctx c, int floor) {
        List<Needs.Want> out = new ArrayList<>();
        if (c.home() == null || c.folk().isBaby()) return out;
        Levels l = levels(c.level(), c.village(), c.home());
        boolean provider = Needs.provider(c);
        Wealth.Tier t = c.tier();
        // A bucket for the water (the household has none): bought by the provider, whatever its means.
        if (provider && buckets(c.level(), c.village(), c.home(), Homes.loadedMembers(c.village(), c.home())) == 0 && 90 >= floor) {
            out.add(want(Items.BUCKET, 1, 90, "to fetch the water in"));
        }
        // The larder put by for the winter, and the dear days (the everyday larder is Needs' own "food").
        Seasons.Season s = season(c.village(), c.day());
        if (provider && (s == Seasons.Season.AUTUMN || s == Seasons.Season.WINTER) && l.shortOf(Stock.FOOD) && 85 >= floor) {
            int n = l.want().get(Stock.FOOD) - l.have().get(Stock.FOOD);
            out.add(want(Items.BREAD, n, 85, s == Seasons.Season.AUTUMN ? "put by for the winter" : "for the winter larder"));
        }
        // Fuel for the stove: a necessity in the cold, else a little for the cooking.
        int fuelPri = s == Seasons.Season.WINTER ? 85 : s == Seasons.Season.AUTUMN ? 70 : 30;
        if (provider && l.stove() && l.shortOf(Stock.FUEL) && fuelPri >= floor) {
            int heat = l.want().get(Stock.FUEL) - l.have().get(Stock.FUEL);
            out.add(want(Items.CHARCOAL, (heat + 3) / 4, fuelPri, s == Seasons.Season.WINTER || s == Seasons.Season.AUTUMN ? "for the stove this winter" : "for the stove"));
        }
        // A worn tool replaced before it breaks in its hand.
        Predicate<ItemStack> tool = Cafe.toolFor(c.folk().stationTask());
        if (tool != null && c.folk().stationTask() != AssistantEntity.StationTask.GUARD && 75 >= floor) {
            for (ItemStack it : c.folk().getInventoryItems()) {
                if (it.isEmpty() || !tool.test(it) || !it.isDamageableItem() || !Homes.ownedBy(it, c.folk())) continue;
                if (it.getDamageValue() * 5 >= it.getMaxDamage() * 4) {
                    out.add(want(it.getItem(), 1, 75, "its old one's worn through"));
                    break;
                }
            }
        }
        if (provider && l.shortOf(Stock.MEDICINE) && t.ordinal() >= Wealth.Tier.GETTING_BY.ordinal() && 30 >= floor) {
            out.add(want(Items.HONEY_BOTTLE, 1, 30, "for the cupboard, against a cold"));
        }
        if (l.shortOf(Stock.CLOTHES) && t.ordinal() >= Wealth.Tier.GETTING_BY.ordinal() && 25 >= floor
            && HomeStores.count(c.level(), c.village(), c.home(), x -> Goods.clothes(x) && Homes.ownedBy(x, c.folk())) == 0) {
            out.add(want(Items.LEATHER_CHESTPLATE, 1, 25, "a change of clothes"));
        }
        if (provider && l.shortOf(Stock.SEED) && t.ordinal() >= Wealth.Tier.COMFORTABLE.ordinal() && 15 >= floor) {
            out.add(want(Items.WHEAT_SEEDS, 4, 15, "for the garden"));
        }
        return out;
    }

    private static Needs.Want want(net.minecraft.world.item.Item it, int n, int priority, String why) {
        ItemStack one = new ItemStack(it);
        return new Needs.Want(one, Math.max(1, Math.min(Needs.MOST, n)), priority, why, Goods.of(one));
    }

    // ------------------------------------------------------------------ what a player reads

    /** The stocks in a line: "food 3 days, water 4 of 6, fuel 8 of 16, clothes 2 of 2, tools 2 of 3, remedies none". */
    public static String line(ServerLevel level, UUID village, Homes.Home h) {
        if (!level.isLoaded(h.anchor)) return "";
        Levels l = levels(level, village, h);
        List<String> parts = new ArrayList<>();
        parts.add("food " + l.days() + (l.days() == 1 ? " day" : " days") + " (" + l.have().get(Stock.FOOD) + " of " + l.want().get(Stock.FOOD) + ")");
        parts.add("water " + l.have().get(Stock.WATER) + " of " + l.want().get(Stock.WATER));
        if (l.stove()) parts.add("fuel " + l.have().get(Stock.FUEL) + " of " + l.want().get(Stock.FUEL));
        parts.add("clothes " + l.have().get(Stock.CLOTHES) + " of " + l.want().get(Stock.CLOTHES));
        parts.add("tools " + l.have().get(Stock.TOOLS) + " of " + l.want().get(Stock.TOOLS));
        parts.add("candles " + l.have().get(Stock.LIGHT));
        parts.add("remedies " + l.have().get(Stock.MEDICINE));
        return String.join(", ", parts);
    }

    /** What the home is short of, in words ("" if nothing). */
    public static String shortWords(ServerLevel level, UUID village, Homes.Home h) {
        if (!level.isLoaded(h.anchor)) return "";
        Levels l = levels(level, village, h);
        List<String> out = new ArrayList<>();
        for (Stock s : Stock.values()) if (l.shortOf(s)) out.add(s.words);
        return String.join(", ", out);
    }

    /** Its card's line: its home's stocks, what is short, and its coin (in its purse and at the bank). */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || !(f.level() instanceof ServerLevel level)) return "";
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null || Homes.lodging(f)) return "";
        String stocks = line(level, village, h);
        if (stocks.isEmpty()) return "";
        String shorts = shortWords(level, village, h);
        Book b = book(village, h);
        long day = level.getDayTime() / 24000L;
        String state = day - b.cold <= 1 ? " A cold house: no fuel for the stove." : day - b.dry <= 1 ? " Went short of water." : "";
        int owed = 0;
        for (int n : b.owes.values()) owed += n;
        return cap(stocks) + "." + (shorts.isEmpty() ? " Nothing short." : " Short of " + shorts + ".") + state
            + (owed > 0 ? " Owes " + owed + " food borrowed." : "") + " Coin: " + f.purse() + " in its purse" + Bank.worthWords(f) + ".";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Was the home cold (no fuel in the winter) or dry (no water) last night? {cold, dry}. */
    static boolean[] hardship(UUID village, Homes.Home h, long day) {
        Book b = book(village, h);
        return new boolean[]{ day - b.cold <= 1, day - b.dry <= 1 };
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a household's evening now (the day's water, fuel, spoilage, cooking, remedies), whatever the hour. */
    public static void eveningForTests(ServerLevel level, Villages.Village v, BlockPos anchor) {
        Homes.Home h = Homes.homes(v.id()).get(anchor.asLong());
        if (h == null) return;
        book(v.id(), h).evening = -1;
        evening(level, v, h, level.getDayTime() / 24000L);
    }

    /** Tests: a chore done now, the walking left out (null: none wanted). */
    @Nullable
    public static String choreForTests(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        Homes.Home h = v == null ? null : Homes.homeOf(village, f.getUUID());
        if (h == null) return null;
        CHORES.remove(f.getUUID());
        Doing d = choose(level, v, h, f);
        if (d == null) return null;
        INSTANT = true;
        try {
            CHORES.put(f.getUUID(), d);
            for (int i = 0; i < 4 && CHORES.containsKey(f.getUUID()); i++) step(level, v, h, f, d);
            boolean done = d.phase >= 2;
            CHORES.remove(f.getUUID());
            return done ? d.chore.name().toLowerCase(java.util.Locale.ROOT) : "failed " + d.chore.name().toLowerCase(java.util.Locale.ROOT);
        } finally {
            INSTANT = false;
        }
    }

    /** Tests: the morning now (breakfast, tools out, winter wear) for this folk. */
    public static void morningForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v != null) {
            BREAKFAST.remove(f.getUUID());
            morning(level, v, f, level.getDayTime() / 24000L);
        }
    }

    /** Tests: its tool home for the night now. */
    public static boolean toolsHomeForTests(ServerLevel level, VillageFolkEntity f) {
        return f.ownerId() != null && toolsHome(level, f.ownerId(), f);
    }

    /** Tests: a home's stocks {have, want} of one. */
    public static int[] stockForTests(ServerLevel level, UUID village, BlockPos anchor, Stock s) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        if (h == null) return new int[2];
        HomeStores.resetFor(h);
        Levels l = levels(level, village, h);
        return new int[]{ l.have().get(s), l.want().get(s) };
    }

    /** Tests: {cold, dry} last night. */
    public static boolean[] hardshipForTests(UUID village, BlockPos anchor, long day) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        return h == null ? new boolean[2] : hardship(village, h, day);
    }

    /** Tests: the food a home owes others. */
    public static int owesForTests(UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        if (h == null) return 0;
        int n = 0;
        for (int x : book(village, h).owes.values()) n += x;
        return n;
    }
}
