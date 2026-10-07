package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The village's money and its market.
 *
 * <ul>
 * <li><b>Coin.</b> A village is founded with a purse of coin in its treasury. From the
 *     Iron Age it mints more from the gold in its stores (nine coins a bar) whenever the
 *     treasury runs low.</li>
 * <li><b>Wages.</b> Every morning the treasury pays every working folk a wage — more to
 *     an old hand — out of what it holds beyond what it is saving for (a trade's kit, the
 *     drover's pair). Short of coin, everybody gets the same share of its wage. Folk save
 *     what they earn, spend it in town, and once a week pay a tithe back.</li>
 * <li><b>Rent.</b> Straight after the wages, the village's tenants pay their rent into the
 *     treasury, and the households saving for a house of their own put part of their pay by
 *     (Homes.payday).</li>
 * <li><b>Selling.</b> Every morning passing traders buy enough of what the village has
 *     plenty of to meet the day's wages; on market day the traders take its surplus.</li>
 * <li><b>Buying.</b> A village short of wool for its beds buys it on market day.</li>
 * <li><b>Market day.</b> Once a week, a different day for every village, the bell rings
 *     in the morning, prices at the stalls are kinder, and folk spend some of their
 *     savings at the stalls on their break: their favourite treat if the stores have it.</li>
 * <li><b>Prices.</b> Everything has a worth, and its price moves with how much of it the
 *     stores hold: dear when it is scarce, cheap when there is plenty. A sign on each
 *     stall shows what is on its counter and what it costs; another shows what the
 *     village wants to buy.</li>
 * <li><b>Trading.</b> Right-click a stall's counter to buy what is on it, with coin in your
 *     pack; right-click it with goods in your hand to sell them to the village, if it
 *     buys them and has the coin.</li>
 * </ul>
 */
public final class Market {

    private Market() {}

    /** What a new village has in its treasury. */
    public static final int FOUNDING_PURSE = 32;
    /** Coins minted from one gold ingot. */
    public static final int COINS_PER_GOLD = 9;

    /** One kind of thing the market deals in: what it is called, what it matches, what
     *  one is worth in coin, how many make a lot, and the village need it answers. */
    public record Good(String name, Predicate<ItemStack> what, double value, int bundle, Villages.Task need) {}

    private static Good good(String name, Item item, double value, int bundle, Villages.Task need) {
        return new Good(name, s -> s.is(item), value, bundle, need);
    }

    public static final List<Good> GOODS = List.of(
        good("Bread", Items.BREAD, 0.3, 8, Villages.Task.FOOD),
        good("Wheat", Items.WHEAT, 0.1, 16, Villages.Task.FOOD),
        good("Carrots", Items.CARROT, 0.15, 16, Villages.Task.FOOD),
        good("Potatoes", Items.POTATO, 0.15, 16, Villages.Task.FOOD),
        good("Baked potato", Items.BAKED_POTATO, 0.25, 8, Villages.Task.FOOD),
        good("Apples", Items.APPLE, 0.3, 8, Villages.Task.FOOD),
        good("Cookies", Items.COOKIE, 0.2, 8, Villages.Task.FOOD),
        good("Pumpkin pie", Items.PUMPKIN_PIE, 1.0, 2, Villages.Task.FOOD),
        good("Cake", Items.CAKE, 4.0, 1, Villages.Task.NONE),
        good("Beetroot", Items.BEETROOT, 0.1, 16, Villages.Task.FOOD),
        good("Melon", Items.MELON_SLICE, 0.1, 16, Villages.Task.FOOD),
        good("Berries", Items.SWEET_BERRIES, 0.1, 16, Villages.Task.FOOD),
        good("Pumpkins", Items.PUMPKIN, 0.4, 4, Villages.Task.NONE),
        good("Eggs", Items.EGG, 0.2, 8, Villages.Task.NONE),
        good("Beef", Items.COOKED_BEEF, 0.6, 4, Villages.Task.FOOD),
        good("Pork", Items.COOKED_PORKCHOP, 0.6, 4, Villages.Task.FOOD),
        good("Mutton", Items.COOKED_MUTTON, 0.5, 4, Villages.Task.FOOD),
        good("Chicken", Items.COOKED_CHICKEN, 0.4, 4, Villages.Task.FOOD),
        good("Cod", Items.COOKED_COD, 0.4, 4, Villages.Task.FOOD),
        good("Salmon", Items.COOKED_SALMON, 0.5, 4, Villages.Task.FOOD),
        good("Raw beef", Items.BEEF, 0.3, 4, Villages.Task.FOOD),
        good("Raw fish", Items.COD, 0.25, 4, Villages.Task.FOOD),
        good("Honey", Items.HONEY_BOTTLE, 1.0, 2, Villages.Task.NONE),
        good("Honeycomb", Items.HONEYCOMB, 0.6, 4, Villages.Task.NONE),
        good("Sugar cane", Items.SUGAR_CANE, 0.15, 16, Villages.Task.NONE),
        new Good("Wool", s -> s.is(ItemTags.WOOL), 0.4, 8, Villages.Task.NONE),
        good("Leather", Items.LEATHER, 0.6, 4, Villages.Task.NONE),
        good("String", Items.STRING, 0.2, 8, Villages.Task.NONE),
        good("Feathers", Items.FEATHER, 0.1, 16, Villages.Task.NONE),
        new Good("Logs", s -> s.is(ItemTags.LOGS), 0.25, 16, Villages.Task.LOGS),
        new Good("Planks", s -> s.is(ItemTags.PLANKS), 0.07, 32, Villages.Task.LOGS),
        good("Cobblestone", Items.COBBLESTONE, 0.04, 64, Villages.Task.STONE),
        good("Stone bricks", Items.STONE_BRICKS, 0.1, 32, Villages.Task.STONE),
        good("Coal", Items.COAL, 0.4, 8, Villages.Task.COAL),
        good("Charcoal", Items.CHARCOAL, 0.35, 8, Villages.Task.COAL),
        good("Raw iron", Items.RAW_IRON, 1.0, 4, Villages.Task.IRON),
        good("Iron", Items.IRON_INGOT, 1.5, 4, Villages.Task.IRON),
        good("Copper", Items.COPPER_INGOT, 0.4, 8, Villages.Task.NONE),
        good("Gold", Items.GOLD_INGOT, COINS_PER_GOLD, 1, Villages.Task.NONE),
        good("Lapis", Items.LAPIS_LAZULI, 0.5, 8, Villages.Task.NONE),
        good("Redstone", Items.REDSTONE, 0.3, 8, Villages.Task.NONE),
        good("Diamond", Items.DIAMOND, 24.0, 1, Villages.Task.DIAMOND),
        good("Emerald", Items.EMERALD, 6.0, 1, Villages.Task.NONE),
        good("Obsidian", Items.OBSIDIAN, 3.0, 1, Villages.Task.OBSIDIAN),
        good("Glass", Items.GLASS, 0.2, 8, Villages.Task.NONE),
        good("Arrows", Items.ARROW, 0.1, 16, Villages.Task.NONE),
        good("Torches", Items.TORCH, 0.1, 16, Villages.Task.NONE),
        good("Books", Items.BOOK, 2.0, 1, Villages.Task.NONE),
        good("Glass bottles", Items.GLASS_BOTTLE, 0.15, 8, Villages.Task.NONE),
        good("Cocoa beans", Items.COCOA_BEANS, 0.3, 8, Villages.Task.NONE),
        good("Sugar", Items.SUGAR, 0.1, 16, Villages.Task.NONE),
        // What the trades need and the village can never make for itself (Trades.kit): a player
        // who brings it from the Nether, a swamp or a jungle is paid well for it.
        good("Blaze rod", Items.BLAZE_ROD, 4.0, 1, Villages.Task.NONE),
        good("Blaze powder", Items.BLAZE_POWDER, 2.0, 2, Villages.Task.NONE),
        good("Nether wart", Items.NETHER_WART, 0.5, 8, Villages.Task.NONE),
        good("Soul sand", Items.SOUL_SAND, 0.5, 4, Villages.Task.NONE),
        good("Slime balls", Items.SLIME_BALL, 1.5, 2, Villages.Task.NONE),
        good("Leads", Items.LEAD, 2.0, 1, Villages.Task.NONE),
        good("Flint", Items.FLINT, 0.2, 8, Villages.Task.NONE),
        good("Sand", Items.SAND, 0.05, 32, Villages.Task.NONE),
        good("Bones", Items.BONE, 0.1, 16, Villages.Task.NONE),
        // What the crafts make, sold one at a time at the shop and the café.
        good("Iron pickaxe", Items.IRON_PICKAXE, 6.0, 1, Villages.Task.NONE),
        good("Iron sword", Items.IRON_SWORD, 4.0, 1, Villages.Task.NONE),
        good("Iron axe", Items.IRON_AXE, 6.0, 1, Villages.Task.NONE),
        good("Iron shovel", Items.IRON_SHOVEL, 3.0, 1, Villages.Task.NONE),
        good("Iron hoe", Items.IRON_HOE, 4.0, 1, Villages.Task.NONE),
        good("Shears", Items.SHEARS, 4.0, 1, Villages.Task.NONE),
        good("Bow", Items.BOW, 3.0, 1, Villages.Task.NONE),
        good("Bucket", Items.BUCKET, 5.0, 1, Villages.Task.NONE),
        good("Iron helmet", Items.IRON_HELMET, 9.0, 1, Villages.Task.NONE),
        good("Iron chestplate", Items.IRON_CHESTPLATE, 14.0, 1, Villages.Task.NONE),
        good("Iron leggings", Items.IRON_LEGGINGS, 12.0, 1, Villages.Task.NONE),
        good("Iron boots", Items.IRON_BOOTS, 7.0, 1, Villages.Task.NONE),
        new Good("Bed", s -> s.is(ItemTags.BEDS), 4.0, 1, Villages.Task.NONE),
        new Good("Rugs", s -> s.is(ItemTags.WOOL_CARPETS), 0.3, 4, Villages.Task.NONE),
        new Good("Banner", s -> s.is(ItemTags.BANNERS), 2.5, 1, Villages.Task.NONE));

    /** The café's drinks, each its own good; then the brewer's potions. */
    private static final List<Good> DRINKS_AND_POTIONS = drinksAndPotions();

    private static List<Good> drinksAndPotions() {
        List<Good> out = new ArrayList<>();
        for (Cafe.Drink d : Cafe.DRINKS) {
            out.add(new Good(d.name(), s -> d.id().equals(Cafe.drinkOf(s)), 0.8, 1, Villages.Task.NONE));
        }
        out.add(new Good("Potion", s -> s.is(Items.POTION) && !Cafe.isDrink(s)
            && s.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                net.minecraft.world.item.alchemy.PotionContents.EMPTY).hasEffects(), 4.0, 1, Villages.Task.NONE));
        return out;
    }

    /** What the market calls this thing, or null if it does not deal in it. */
    @Nullable
    public static Good goodFor(ItemStack s) {
        if (s.isEmpty() || isCoin(s)) return null;
        for (Good g : GOODS) if (g.what().test(s)) return g;
        for (Good g : DRINKS_AND_POTIONS) if (g.what().test(s)) return g;
        return null;
    }

    public static boolean isCoin(ItemStack s) {
        return s.is(McAssistantMod.VILLAGE_COIN.get());
    }

    // ------------------------------------------------------------------ days

    /** Is this the village's market day? Once a week, a day of its own. */
    public static boolean marketDay(UUID village, long day) {
        return Math.floorMod(day + village.hashCode(), 7L) == 0;
    }

    /** How many days until the next market day (0: today). */
    public static int daysToMarket(UUID village, long day) {
        for (int d = 0; d < 7; d++) if (marketDay(village, day + d)) return d;
        return 7;
    }

    // ------------------------------------------------------------------ prices

    /** How much of what matches the stores hold. */
    public static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) n += s.getCount();
            }
        }
        return n;
    }

    /** One of a thing's worth today: dear when the stores hold little, cheap when they hold plenty. */
    static double each(Good g, int stock) {
        int target = g.bundle() * 4;
        double scarcity = Math.sqrt((target + g.bundle()) / (double) (stock + g.bundle()));
        return g.value() * Math.max(0.5, Math.min(3.0, scarcity));
    }

    /** What the village asks for a lot of this. */
    public static int sellPrice(Good g, int stock, boolean marketDay) {
        return Math.max(1, (int) Math.round(each(g, stock) * g.bundle() * (marketDay ? 0.9 : 1.0)));
    }

    /** What the village pays for a lot of this. Always less than it would ask for it back. */
    public static int buyPrice(Good g, int stock, boolean marketDay) {
        return Math.max(1, (int) Math.floor(each(g, stock) * g.bundle() * 0.55 * (marketDay ? 1.1 : 1.0)));
    }

    /** What the village would buy, most wanted first: what it is short of, by what it needs now. */
    public static List<Good> wanted(ServerLevel level, UUID village) {
        Set<Villages.Task> short_ = EnumSet.noneOf(Villages.Task.class);
        for (Villages.Need n : Villages.needs(level, village)) short_.add(n.task());
        List<Good> out = new ArrayList<>();
        List<Integer> stock = new ArrayList<>();
        for (Good g : GOODS) {
            if (g.need() == Villages.Task.NONE || !short_.contains(g.need())) continue;
            int have = stock(level, village, g.what());
            if (have >= g.bundle() * 4) continue;
            out.add(g);
            stock.add(have);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> -each(out.get(i), stock.get(i)) / out.get(i).value()));
        List<Good> sorted = new ArrayList<>();
        for (int i : order) sorted.add(out.get(i));
        return sorted.size() > 3 ? sorted.subList(0, 3) : sorted;
    }

    /** The lines of a stall's price sign: each lot on its counter and its price. */
    public static String[] sellLines(ServerLevel level, UUID village, List<Item> goods) {
        boolean md = marketDay(village, level.getDayTime() / 24000L);
        String[] lines = { "", "", "", md ? "Market day!" : "" };
        int k = 0;
        for (Item it : goods) {
            if (it == null || k >= 3) continue;
            ItemStack one = new ItemStack(it);
            Good g = goodFor(one);
            if (g == null) continue;
            int p = Stockroom.asked(level, village, one, sellPrice(g, stock(level, village, s -> s.is(it)), md), g.bundle());
            lines[k++] = g.bundle() + " " + g.name() + " " + p + "c";
        }
        return lines;
    }

    /** The lines of a stall's other sign: what the village is buying, and for how much. */
    public static String[] buyLines(ServerLevel level, UUID village) {
        boolean md = marketDay(village, level.getDayTime() / 24000L);
        String[] lines = { "We buy:", "", "", "" };
        if (Ledger.coins(village) <= 0) {
            lines[1] = "(no coin";
            lines[2] = "to pay)";
            return lines;
        }
        int k = 1;
        for (Good g : wanted(level, village)) {
            int p = buyPrice(g, stock(level, village, g.what()), md);
            lines[k++] = g.bundle() + " " + g.name() + " " + p + "c";
            if (k > 3) break;
        }
        if (k == 1) lines[1] = "nothing today";
        return lines;
    }

    // ------------------------------------------------------------------ the treasury

    /** The morning's business, once a day: the treasury opened (a new village's purse),
     *  coin minted from gold, wages paid, and on market day the bell rung. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!Ledger.hasTreasury(id)) Ledger.addCoins(id, FOUNDING_PURSE);
        long time = level.getDayTime();
        long day = time / 24000L, t = time % 24000L;
        if (t < 500 || t > 6000) return;
        if (Ledger.paidOn(id) >= day) return;
        Ledger.paid(id, day);
        Homeland.survey(level, v);                       // a village from before: its land, looked over now
        // [sf] The morning's count of the stores, steadied against one that missed the storehouse (Villages.steadyStock).
        HUNGRY.put(id, Villages.steadyStock(level, v, Villages.Task.FOOD, day) * 2
            < Villages.larderForBirth(id));
        Economy.closeTheDay(level, v, day);              // yesterday's output, and what the village is worth
        Annals.record(level, v, day);                    // and the morning written into the town's books
        Leader.morning(level, v, day);                   // the leader's books, the plan and the day's pay
        CityTree.morning(level, v, day);                 // the day's research points, and the leader's next civic
        mint(level, v);
        int sold = trade(level, v);
        takings(level, v, sold);                         // what the village made yesterday is its revenue
        buyWool(level, v, day);                          // the beds, out of half of it at most
        payWages(level, v);
        Bank.beforeRent(level, v, day);                  // the bank: a saver short of the rent draws it out first (Bank)
        Homes.payday(level, v, day);                     // the rent in, and what the households put by to buy their houses
        if (RestDay.today(id, day)) tithe(level, v, day);
        Bank.morning(level, v, day);                     // the bank: the day's savings in, and on its seventh day its round (Bank)
        Villages.checkRank(level, v, day);
        News.morning(level, v, day);
        if (marketDay(id, day)) {
            sellSurplus(level, v, day);
            level.playSound(null, v.centre(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0F, 1.0F);
            for (ServerPlayer p : level.players()) {
                if (p.blockPosition().closerThan(v.centre(), 96)) {
                    p.displayClientMessage(Component.literal("Market day in " + Villages.name(id) + "!"), true);
                }
            }
        }
    }

    /**
     * Market day: the travelling traders buy what the village has far more of than it can use —
     * timber, stone and food over a good reserve — for coin into the treasury, which pays the
     * wages. The long game's village sat on 1,081 logs, 1,225 stone and 1,115 food with an empty
     * treasury and full chests. At most three stacks of each a market day. Returns the coin.
     */
    public static int sellSurplus(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int head = Math.max(1, Villages.headcount(id));
        int foodStock = Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id));
        int foodKeep = Math.max(256, 3 * Villages.larderForBirth(id));
        List<String> sold = new ArrayList<>();
        int coins = 0;
        for (Good g : GOODS) {
            int keep = switch (g.need()) {
                case LOGS -> g.name().equals("Logs") ? Math.max(256, 8 * head) : -1;
                case STONE -> g.name().equals("Cobblestone") ? Math.max(384, 12 * head) : -1;
                case FOOD -> foodStock > foodKeep ? 128 : -1;
                default -> -1;
            };
            if (keep < 0) continue;
            int have = stock(level, id, g.what());
            have -= TradeDeals.spokenFor(id, g);           // [econ-trade] what a partner is promised goes to the partner, not off the map
            int n = Math.min(192, have - keep);
            if (g.need() == Villages.Task.FOOD) n = Math.min(n, foodStock - foodKeep);
            if (n < 32) continue;
            int paid = (int) Math.floor(n * each(g, have) * 0.5 * CityTree.takingsPercent(id) / 100.0);   // the Market Charter
            if (paid < 1 || !TownWork.take(level, v, g.what(), n)) continue;
            if (g.need() == Villages.Task.FOOD) foodStock -= n;
            coins += paid;
            sold.add(n + " " + g.name().toLowerCase());
        }
        if (coins <= 0) return 0;
        Ledger.addCoins(id, coins);
        Economy.sold(id, coins);
        Villages.tell(id, day, "Traders came for market day and bought " + String.join(", ", sold) + " for " + coins + " coin");
        return coins;
    }

    /** From the Iron Age: gold from the stores into coin, while the treasury is low. Returns the coin minted. */
    public static int mint(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal()) return 0;
        int want = 12 * Math.max(1, Villages.headcount(id));
        int have = Ledger.coins(id);
        if (have >= want) return 0;
        int bars = Math.min(3, (want - have + COINS_PER_GOLD - 1) / COINS_PER_GOLD);
        int took = 0;
        while (took < bars && TownWork.take(level, v, s -> s.is(Items.GOLD_INGOT), 1)) took++;
        if (took > 0) Ledger.addCoins(id, took * COINS_PER_GOLD);
        return took * COINS_PER_GOLD;
    }

    /**
     * Passing traders buy a little of what the village makes, every day, while its treasury is
     * low: a lot or two of whatever it has plenty of, at the going price — goods out, coin in.
     * (They used to leave the coin and take nothing: coin out of thin air.) Returns the coin.
     */
    public static int trade(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int head = Math.max(1, Villages.headcount(id));
        long now = level.getGameTime();
        // Enough to meet today's wages and what it is saving for: a town of sixty paid a hundred
        // and fifty coin a day in wages, and the traders brought in two dozen; the treasury stood
        // empty every morning, and nothing it had to buy (a hive, the drover's pair) was ever bought.
        int need = wageBill(id) + saved(id, now) - Ledger.coins(id);
        if (need <= 0) return 0;
        // The traders buy what the town makes: never more in a day than its output was worth
        // (yesterday's, or the week's average if that is more). A busy town meets its wages; an
        // idle one sells what it can and pays short.
        int base = Math.max(2, head / 3) + Villages.ageOf(id).ordinal();
        int makes = Math.max(Economy.yesterday(id), Economy.weekAverage(id));
        int want = Math.max(base, Math.min(need, makes));
        // Never what the village is short of itself (the stone for the hall it is raising, the food
        // for a lean larder), nor the wool while beds wait for it. They used to buy only what the
        // stores held ten lots of, at a third of its worth: a mining town of fifty sold three coin
        // of a day's hundred and sixteen, and paid its wages out of nothing.
        Set<Villages.Task> short_ = EnumSet.noneOf(Villages.Task.class);
        for (Villages.Need n : Villages.needs(level, id)) short_.add(n.task());
        boolean bedsWait = bedsShort(id) > 0;
        // Food only over a full larder (as on market day): a young village not yet "short of food"
        // with a hundred put by sold half of it in a morning, and starved.
        int foodSpare = Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id))
            - Math.max(256, 3 * Villages.larderForBirth(id));
        int in = 0;
        List<String> sold = new ArrayList<>();
        for (Good g : GOODS) {
            if (in >= want) break;
            if (g.need() == Villages.Task.NONE && g.value() < 0.3) continue;
            if (g.need() != Villages.Task.NONE && short_.contains(g.need())) continue;
            if (bedsWait && g.name().equals("Wool")) continue;
            boolean food = g.need() == Villages.Task.FOOD;
            if (food && foodSpare < g.bundle()) continue;
            int have = stock(level, id, g.what());
            have -= TradeDeals.spokenFor(id, g);           // [econ-trade] what a partner is promised goes to the partner, not off the map
            int plenty = g.bundle() * 4;
            if (have < plenty + g.bundle()) continue;
            // As much as is wanted, from what it has to spare: up to eight lots of a thing.
            int lotWorth = Math.max(1, (int) Math.floor(g.bundle() * each(g, have) * 0.8));
            int lots = Math.min(Math.min(8, (have - plenty) / g.bundle()), Math.max(1, (want - in + lotWorth - 1) / lotWorth));
            if (food) lots = Math.min(lots, foodSpare / g.bundle());
            if (lots <= 0) continue;
            int n = lots * g.bundle();
            int paid = (int) Math.floor(n * each(g, have) * 0.8);
            if (paid < 1 || !TownWork.take(level, v, g.what(), n)) continue;
            in += paid;
            sold.add(n + " " + g.name().toLowerCase());
        }
        if (in <= 0) return 0;
        Ledger.addCoins(id, in);
        Economy.sold(id, in);
        Villages.tell(id, level.getDayTime() / 24000L, "passing traders bought " + String.join(", ", sold) + " for " + in + " coin");
        return in;
    }

    /**
     * The day's work is the village's revenue: what it made yesterday (its fields, woods, mines,
     * pens and crafts, at the market's prices: Economy) comes into the treasury each morning, less
     * what the traders have just paid for. A village that used everything it made on its own walls
     * and bread sold nothing, earned nothing, and paid no wages for a month however hard it worked.
     * Returns the coin.
     */
    public static int takings(ServerLevel level, Villages.Village v, int sold) {
        UUID id = v.id();
        // At the place's standing, as its wages are: a town's work fetches more than a hamlet's
        // (bigger markets for it), so what comes in and what is paid out grow together.
        // And a twentieth more under the town's Market Charter (CityTree).
        int worth = (int) Math.round(Economy.yesterday(id) * Wealth.standing(id) / 10.0 * CityTree.takingsPercent(id) / 100.0);
        int in = Math.max(0, worth - Math.max(0, sold));
        if (in <= 0) return 0;
        Ledger.addCoins(id, in);
        Economy.takings(id, in);
        return in;
    }

    /** A folk's wage for a day's work: a coin, and one more at each of levels ten and twenty-five. */
    public static int wage(AssistantEntity a) {
        if (a instanceof VillageFolkEntity f) return Wealth.wage(f);       // by trade, level, office and the day's work
        int lv = a.veteranLevel();
        return 1 + (lv >= 10 ? 1 : 0) + (lv >= 25 ? 1 : 0);
    }

    /** The day's wages, all told. */
    public static int wageBill(UUID village) {
        int bill = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != AssistantEntity.StationTask.NONE) bill += wage(f);
        }
        return bill;
    }

    /** The village's tax: this much in the hundred of every wage stays in the treasury on payday. */
    public static final int TAX_PERCENT = 10;
    /** No tax is taken while the treasury (over what it is saving for) holds this many days' wages. */
    public static final int TAX_TILL_DAYS = 7;

    /** What each folk's tax has come to so far that is short of a whole coin, in hundredths (by the folk). */
    private static final java.util.Map<UUID, Integer> TAX_CARRY = new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether the village's last payday took the tax (by the village). */
    private static final java.util.Map<UUID, Boolean> TAXING = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The tax on a wage of {@code wage}: a tenth of it, the part of a coin carried to the folk's next
     * payday, so a hand paid three a day pays a coin every third or fourth morning and a tenth in the end.
     * The poor pay none. Never more than the wage.
     */
    static int taxOn(VillageFolkEntity f, int wage) {
        if (wage <= 0 || Wealth.tier(f) == Wealth.Tier.POOR) return 0;
        if (TAX_CARRY.size() > 8192) TAX_CARRY.clear();               // folk long gone: under a coin each, let go
        int owed = TAX_CARRY.getOrDefault(f.getUUID(), 0) + wage * TAX_PERCENT;
        int tax = Math.min(wage, owed / 100);
        TAX_CARRY.put(f.getUUID(), owed - tax * 100);
        return tax;
    }

    /** Did the village's last payday take the tax (its treasury short of a week's wages)? True before the first. */
    public static boolean taxing(@Nullable UUID village) {
        return village == null || TAXING.getOrDefault(village, true);
    }

    /**
     * Every working folk paid, out of what the treasury holds beyond what it is saving for. Short
     * of coin, every folk gets the same share of its wage, and the odd coins go round, starting
     * with a different folk each day. (The coin used to go down the list until it ran out: the
     * first dozen were paid in full every day, and the rest never.) Returns the coin paid out.
     *
     * <p>A tenth of every wage is the village's tax and never leaves the treasury: a folk is paid the
     * rest. The wages are reckoned before it, out of what the treasury holds, so whatever it pays out
     * it keeps a tenth of, and it is never emptied by a payday. Once the treasury holds a week's wages
     * over what it is saving for, it takes no tax until it holds less again: it is a purse to pay the
     * wages out of, not a hoard. (Every coin of the wages used to go out of it every morning, the
     * treasury paid out whatever it held, and only the weekly tithe and what the folk spent came back:
     * a town of a hundred kept a few coins in its treasury and three thousand in its purses.)
     */
    public static int payWages(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<VillageFolkEntity> hands = new ArrayList<>();
        List<Integer> wages = new ArrayList<>();
        int bill = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.NONE) continue;
            int w = wage(f);
            if (w <= 0) continue;
            // At the rate the leader set today (Leader.payRate): over the odds, or part held back.
            w = Math.max(1, (int) Math.round(w * Leader.payRate(id) / 100.0));
            hands.add(f);
            wages.add(w);
            bill += w;
        }
        int purse = Ledger.coins(id) - saved(id, level.getGameTime());
        if (bill <= 0) return 0;
        if (purse <= 0) {
            // Nothing to pay with is a payday too: an empty treasury kept the last good morning's
            // "paid in full" on the books for days.
            payday(level, v, hands, wages, new int[hands.size()], 0);
            return 0;
        }
        int[] due = new int[hands.size()];
        int given = 0;
        for (int i = 0; i < due.length; i++) {
            due[i] = bill <= purse ? wages.get(i) : (int) Math.floor(wages.get(i) * (double) purse / bill);
            given += due[i];
        }
        int start = (int) Math.floorMod(level.getDayTime() / 24000L, (long) due.length);
        for (int k = 0; k < due.length && given < Math.min(purse, bill); k++) {
            int i = (start + k) % due.length;
            if (due[i] < wages.get(i)) { due[i]++; given++; }
        }
        int paid = 0, taxed = 0;
        long day = level.getDayTime() / 24000L;
        boolean taxing = purse < TAX_TILL_DAYS * bill;
        TAXING.put(id, taxing);
        for (int i = 0; i < due.length; i++) {
            if (due[i] <= 0) continue;
            int tax = taxing ? taxOn(hands.get(i), due[i]) : 0;
            int net = due[i] - tax;
            int got = net <= 0 ? 0 : Ledger.takeCoins(id, net);
            if (net > 0 && got <= 0) break;
            hands.get(i).paid(got);
            PAID.put(hands.get(i).getUUID(), new long[]{ day, got });
            paid += got;
            taxed += tax;
        }
        // The town's Counting House (CityTree): a twentieth of it back into the treasury; the folk keep every coin.
        int back = CityTree.countingHouse(id, paid);
        if (back > 0) Ledger.addCoins(id, back);
        // In the books: the wages as earned (what the folk were paid and the tax on it), and the tax as money in.
        Economy.wages(id, paid + taxed - back);
        Economy.tax(id, taxed);
        payday(level, v, hands, wages, due, bill <= purse ? 100 : (int) Math.round(100.0 * Math.min(purse, bill) / bill));
        return paid;
    }

    // ------------------------------------------------------------------ payday

    /** What each folk was paid at its last payday: {day, coin}. */
    private static final java.util.Map<UUID, long[]> PAID = new java.util.concurrent.ConcurrentHashMap<>();

    /** What this folk was paid on this day's payday (0 if nothing, or not that day): Homes puts a share of it by. */
    public static int paidOn(UUID folk, long day) {
        long[] p = PAID.get(folk);
        return p == null || p[0] != day ? 0 : (int) p[1];
    }

    /** The share of the wages the last payday paid, in the hundred (-1 before the first). */
    private static final java.util.Map<UUID, Integer> SHARE = new java.util.concurrent.ConcurrentHashMap<>();
    /** What the elder has to tell the morning assembly about the money (Assemblies). */
    private static final java.util.Map<UUID, List<String>> NEWS = new java.util.concurrent.ConcurrentHashMap<>();

    public static int lastShare(UUID village) {
        return SHARE.getOrDefault(village, -1);
    }

    /** Something the elder has to tell the morning assembly (the leader's calls: Leader). */
    public static void assemblyNews(UUID village, String line) {
        NEWS.computeIfAbsent(village, k -> new ArrayList<>()).add(line);
    }

    /** The money news for the morning assembly, once. */
    public static List<String> reports(UUID village) {
        List<String> out = NEWS.remove(village);
        return out == null ? List.of() : out;
    }

    /**
     * How the payday went down. A rise when the place has come up in the world (a village pays
     * half as much again as a hamlet, a town twice): it goes in the chronicle, the elder tells the
     * morning assembly, and folk are glad of it for a day or two. A short payday: they feel it,
     * and one or two of the grumpier ones say so — not everybody, every morning.
     */
    static void payday(ServerLevel level, Villages.Village v, List<VillageFolkEntity> hands, List<Integer> wages, int[] due, int share) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        SHARE.put(id, share);
        int standing = Wealth.standing(id);
        int before = -1;
        try { before = Integer.parseInt(String.valueOf(Ledger.note(id, "pay.standing"))); } catch (NumberFormatException ignored) { }
        Ledger.note(id, "pay.standing", Integer.toString(standing));
        net.minecraft.util.RandomSource r = level.getRandom();
        if (before > 0 && standing > before) {
            String place = Villages.rank(id).label;
            Villages.tell(id, day, "wages rose: " + place + " pays " + Wealth.standingWords(standing));
            NEWS.computeIfAbsent(id, k -> new ArrayList<>()).add("Now that we're " + place + ", every wage goes up — "
                + Wealth.standingWords(standing) + ". You've earned every coin of it.");
            for (VillageFolkEntity f : hands) f.payRise(day);
            int said = 0;
            for (VillageFolkEntity f : hands) {
                if (said >= 2 || r.nextInt(4) != 0) continue;
                FolkTalk.speak(f, FolkTalk.pick(r, "A rise! I'll not say no to that.", "More pay — the town's doing well by us.",
                    "Did you hear? Wages are up!"));
                said++;
            }
        }
        if (share < 100 && !hands.isEmpty()) {
            for (VillageFolkEntity f : hands) f.shortPaid(day);
            if (share < 80) {
                // The grumpy first, and only a couple of them.
                List<VillageFolkEntity> by = new ArrayList<>(hands);
                by.sort(Comparator.comparingInt(f -> f.life().has(Social.Trait.GRUMPY) ? 0 : 1));
                int said = 0;
                for (VillageFolkEntity f : by) {
                    if (said >= 2) break;
                    if (!f.life().has(Social.Trait.GRUMPY) && r.nextInt(3) != 0) continue;
                    FolkTalk.speak(f, FolkTalk.pick(r, "Short again this morning. The treasury's thin.",
                        "Half a wage! We'd best make more than we eat.", "Paid short. Somebody tell the traders we're open.",
                        "That's not a day's pay. Still — it's a bad week, not a bad town."));
                    said++;
                }
            }
            String last = Ledger.note(id, "pay.shortnews");
            long lastDay = -10;
            try { lastDay = Long.parseLong(String.valueOf(last)); } catch (NumberFormatException ignored) { }
            if (share < 60 && day - lastDay >= 3) {
                Ledger.note(id, "pay.shortnews", Long.toString(day));
                NEWS.computeIfAbsent(id, k -> new ArrayList<>()).add("The treasury's low: this morning's wages were "
                    + share + " in the hundred. Make more and sell more, and it'll come right.");
            }
        }
    }

    // ------------------------------------------------------------------ saving up

    /** What a village is putting coin by for, and how much, till when (game time). */
    private static final java.util.Map<UUID, java.util.Map<String, long[]>> SAVING = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The village wants to buy this and has not the coin for it yet: for the next three days the
     * wages leave that much in the treasury, and the morning's traders buy enough to make it up.
     */
    public static void saveFor(UUID village, String what, int price, long now) {
        if (price <= 0) return;
        SAVING.computeIfAbsent(village, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(what, new long[] { price, now + 72000L });
    }

    /** Bought (or no longer wanted): no more coin put by for it. */
    public static void bought(UUID village, String what) {
        java.util.Map<String, long[]> m = SAVING.get(village);
        if (m != null) m.remove(what);
    }

    /** The coin the treasury is keeping back. */
    public static int saved(UUID village, long now) {
        java.util.Map<String, long[]> m = SAVING.get(village);
        if (m == null) return 0;
        m.values().removeIf(e -> e[1] < now);
        long sum = 0;
        for (long[] e : m.values()) sum += e[0];
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    /** What is being put by for this one thing. */
    static int savedFor(UUID village, String what, long now) {
        java.util.Map<String, long[]> m = SAVING.get(village);
        long[] e = m == null ? null : m.get(what);
        return e == null || e[1] < now ? 0 : (int) e[0];
    }

    /** Tests: the tax on this wage for this folk, as payday would take it (its odd part carried). */
    public static int taxOnForTests(VillageFolkEntity f, int wage) {
        return taxOn(f, wage);
    }

    public static void resetForTests() {
        BEDS_SHORT.clear();
        HUNGRY.clear();
        SAVING.clear();
        SHARE.clear();
        NEWS.clear();
        PAID.clear();
        TAX_CARRY.clear();
        TAXING.clear();
    }

    // ------------------------------------------------------------------ the tithe

    /**
     * The day of rest: every folk with savings puts one coin in ten (of what it holds over a
     * dozen) into the treasury, which pays the wages. Without it the coin only went one way:
     * a town of sixty held 385 coins in its purses and two in its treasury.
     */
    public static int tithe(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Ledger.note(id, "tithe.day") != null && Ledger.note(id, "tithe.day").equals(Long.toString(day))) return 0;
        Ledger.note(id, "tithe.day", Long.toString(day));
        int in = 0, gave = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            int over = f.purse() - 12;
            int due = over >= 10 ? over / 10 : 0;
            if (due <= 0 || !f.spend(due)) continue;
            in += due;
            gave++;
        }
        if (in <= 0) return 0;
        Ledger.addCoins(id, in);
        Economy.tithe(id, in);
        Villages.tell(id, day, gave + " folk gave the tithe, " + in + " coin, for the village's purse");
        return in;
    }

    // ------------------------------------------------------------------ buying in

    /** Villages whose larder was low this morning (under half what a birth wants put by). */
    private static final java.util.Map<UUID, Boolean> HUNGRY = new java.util.concurrent.ConcurrentHashMap<>();

    /** Is the village's larder low (this morning's count)? Its farms and waters take more hands. */
    public static boolean hungry(UUID village) {
        return HUNGRY.getOrDefault(village, false);
    }

    /** Beds the village's houses want and nobody has made up yet, as of this morning. */
    private static final java.util.Map<UUID, Integer> BEDS_SHORT = new java.util.concurrent.ConcurrentHashMap<>();

    /** How many beds the village's houses are waiting on (this morning's count). */
    public static int bedsShort(UUID village) {
        return BEDS_SHORT.getOrDefault(village, 0);
    }

    /**
     * Every morning: a village with houses whose beds are not made up, and not the wool to make
     * them, buys wool from the traders (dear: it pays what they would ask), a lot or two a day,
     * before the wages. Short of the coin, it puts it by: the wages leave it in the treasury and
     * the traders buy enough of the village's goods to make it up. (It bought only on market day,
     * out of what was left after a full day's wages, which never happened: a town of fifty-two had
     * thirty-six beds planned, eight made, and never bought a strand.) Returns the coin spent.
     */
    public static int buyWool(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int missing = Math.max(0, Villages.bedsPlanned(id) - Villages.bedsMadeUp(level, id));
        BEDS_SHORT.put(id, missing);
        long now = level.getGameTime();
        int wool = stock(level, id, s -> s.is(ItemTags.WOOL));
        int beds = stock(level, id, s -> s.is(ItemTags.BEDS));
        int short_ = 3 * Math.max(0, missing - beds) - wool;
        if (short_ < 3) { bought(id, "wool"); return 0; }
        Good g = goodFor(new ItemStack(Items.WHITE_WOOL));
        if (g == null) return 0;
        int lots = Math.min(2, (short_ + g.bundle() - 1) / g.bundle());
        int price = sellPrice(g, wool, marketDay(id, day));
        int other = saved(id, now) - savedFor(id, "wool", now);
        // Half of what the treasury has free at most: the beds came before the wages and took all
        // of it, and nobody was paid for a fortnight.
        int can = Math.max(0, (Ledger.coins(id) - other) / 2) / Math.max(1, price);
        if (can < lots) saveFor(id, "wool", price, now);  // a lot put by for tomorrow
        lots = Math.min(lots, can);
        if (lots <= 0) return 0;
        int paid = Ledger.takeCoins(id, lots * price);
        Economy.spent(id, paid);
        if (lots * g.bundle() >= short_) bought(id, "wool");
        ItemStack left = intoStores(level, id, new ItemStack(Items.WHITE_WOOL, lots * g.bundle()));
        if (!left.isEmpty()) { /* the stores are full: the rest is left with the traders */ }
        Villages.tell(id, day, "bought " + lots * g.bundle() + " wool from the traders for " + paid + " coin, for the beds");
        return paid;
    }

    // ------------------------------------------------------------------ folk at the market

    /** The treats folk buy at the market, after their own favourite. */
    private static final List<Item> TREATS = List.of(Items.COOKIE, Items.APPLE, Items.BAKED_POTATO, Items.PUMPKIN_PIE,
        Items.SWEET_BERRIES, Items.MELON_SLICE, Items.BREAD, Items.COOKED_COD, Items.HONEY_BOTTLE);

    /**
     * A folk spends at the market: its favourite food if the stores have it, else some other
     * treat, paid for out of its savings into the treasury. Returns what it bought, or null.
     */
    @Nullable
    public static String folkBuys(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        List<Item> wants = new ArrayList<>();
        Item fav = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(f.persona().food()));
        if (fav != Items.AIR) wants.add(fav);
        for (Item t : TREATS) if (!wants.contains(t)) wants.add(t);
        for (Item it : wants) {
            // A player's stall on the square is a seller like any other: bought there if it has this at a
            // price this folk thinks fair, and no dearer than the village's own (PlayerStalls).
            String atStall = PlayerStalls.instead(level, v, f, s -> s.is(it), PlayerStalls.Use.TREAT, it == fav ? fav : null);
            if (atStall != null) return atStall;
            Good g = goodFor(new ItemStack(it));
            int have = stock(level, v.id(), s -> s.is(it));
            int price = g == null ? 1 : Math.max(1, (int) Math.round(each(g, have)));
            price = Stockroom.asked(level, v.id(), new ItemStack(it), price, 1);
            price = FolkSkills.thrifty(f, price);                             // a Thrifty folk pays a tenth less
            if (f.purse() < price) continue;
            // Its favourite not to be had: the market's books count the sale it had not got (Stockroom).
            if (have <= 0 && it == fav) Stockroom.missed(level, v.id(), Stockroom.Seller.MARKET, new ItemStack(it));
            if (!TownWork.take(level, v, s -> s.is(it), 1)) continue;
            f.spend(price);
            Ledger.addCoins(v.id(), price);
            Economy.spentInTown(v.id(), price);
            Stockroom.sold(level, v.id(), Stockroom.Seller.MARKET, new ItemStack(it), 1, price);
            ItemStack bought = new ItemStack(it);
            ItemStack left = f.insertItem(bought);
            if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
            return it.getDescription().getString().toLowerCase();
        }
        return null;
    }

    // ------------------------------------------------------------------ players at the stalls

    /** The frame of goods on this stall counter, or null if it is not one. */
    @Nullable
    public static ItemFrame stallFrame(ServerLevel level, BlockPos counter) {
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(counter.above()))) {
            if (f.getTags().contains("mca_stall")) return f;
        }
        return null;
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!level.getBlockState(pos).is(Blocks.BARREL)) return;
        ItemFrame frame = stallFrame(level, pos);
        if (frame == null) return;
        // A stall's counter is not a barrel to rummage in.
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.nearest(level, pos, 40);
        if (v == null) return;
        Player p = e.getEntity();
        // A shop's counter sells; only the market's stalls buy what you bring.
        String said = frame.getTags().contains("mca_counter") ? buy(level, v, p, frame.getItem()) : deal(level, v, p, frame.getItem());
        p.displayClientMessage(Component.literal(said), true);
    }

    /**
     * A player at a stall: with goods the village buys in their hand, they sell a lot of
     * them; otherwise they buy a lot of what is on the counter, with coin from their pack.
     * Returns what to tell them.
     */
    public static String deal(ServerLevel level, Villages.Village v, Player p, ItemStack shown) {
        UUID id = v.id();
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST) return "Nobody here will trade with you.";
        boolean md = marketDay(id, level.getDayTime() / 24000L);
        ItemStack hand = p.getMainHandItem();
        if (!hand.isEmpty() && !isCoin(hand)) {
            Good g = goodFor(hand);
            if (g == null) return "The village doesn't buy " + hand.getHoverName().getString() + ".";
            return sell(level, v, p, hand, g, md);
        }
        return buy(level, v, p, shown, Stockroom.Seller.MARKET);
    }

    /** A player buys a lot of what is on a counter, with coin from their pack. Returns what to tell them. */
    public static String buy(ServerLevel level, Villages.Village v, Player p, ItemStack shown) {
        return buy(level, v, p, shown, Stockroom.sellerFor(shown));
    }

    /** As buy, at this seller's counter (for its books: Stockroom). */
    public static String buy(ServerLevel level, Villages.Village v, Player p, ItemStack shown, Stockroom.Seller seller) {
        UUID id = v.id();
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST) return "Nobody here will trade with you.";
        if (Laws.banished(id, p.getUUID(), level.getDayTime() / 24000L)) return "You're banished from " + Villages.name(id) + ". Nobody will serve you.";
        boolean md = marketDay(id, level.getDayTime() / 24000L);
        if (shown.isEmpty()) return "Nothing on this counter today.";
        Good g = Budget.goodFor(shown);
        if (g == null) return "That's not for sale.";
        // The very thing on the counter: this potion, this drink, this enchanted pick.
        Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, shown);
        String lot = lotName(g, shown);
        int stock = stock(level, id, same);
        if (stock < g.bundle()) {
            if (!p.isShiftKeyDown()) Stockroom.missed(level, id, seller, shown);       // a sale the shelf had not got
            return "They've not got " + lot + " to spare just now.";
        }
        // Its own needs first: the guards' swords and the larder's bread are not for sale (Budget).
        if (Budget.spare(level, id, shown) < g.bundle()) return "They can't spare " + lot + " — the village needs it itself just now.";
        int price = price(level, id, g, shown, stock, md);
        if (title == Standing.Title.UNWELCOME) price *= 2;
        else if (title.atLeast(Standing.Title.FRIEND)) price = Math.max(1, price - price / 10);
        if (Citizens.is(id, p.getUUID())) price = Math.max(1, price - Math.max(1, price / 10));   // a citizen's ten off
        price = Dealings.haggled(id, p.getUUID(), level.getDayTime() / 24000L, price);           // talked down today
        if (p.isShiftKeyDown()) return lot + ": " + price + coinWord(price) + ". Right-click to buy.";
        int coins = coinsHeld(p);
        if (coins < price) {
            return lot + " for " + price + coinWord(price) + ". You have " + coins + ".";
        }
        if (!TownWork.take(level, v, same, g.bundle())) return "They've not got that to spare just now.";
        payOut(p, price);
        Ledger.addCoins(id, price);
        Economy.sold(id, price);
        Budget.forget(id);
        Stockroom.sold(level, id, seller, shown, g.bundle(), price);
        ItemStack bought = shown.copyWithCount(g.bundle());
        if (!p.getInventory().add(bought)) p.drop(bought, false);
        thanks(level, v, p);
        return "Bought " + lot + " for " + price + coinWord(price) + ".";
    }

    /** What a lot of this is called: "8 bread", or the thing's own name when it is one thing. */
    private static String lotName(Good g, ItemStack shown) {
        return g.bundle() > 1 ? g.bundle() + " " + g.name().toLowerCase() : shown.getHoverName().getString();
    }

    /** What the village asks for a lot of what is on a counter: more for an enchanted thing, and
     *  more for a master's work than a beginner's (Craftsmanship). */
    static int price(Good g, ItemStack shown, int stock, boolean marketDay) {
        int p = sellPrice(g, stock, marketDay);
        if (shown.isEnchanted()) p *= 3;
        return (int) Math.max(1, Math.round(p * Craftsmanship.worth(shown)));
    }

    /** As price, at this village's counters: what its sellers make is marked down when it is slow, and
     *  never sold for less than it cost (Stockroom). */
    public static int price(ServerLevel level, UUID village, Good g, ItemStack shown, int stock, boolean marketDay) {
        return Stockroom.asked(level, village, shown, price(g, shown, stock, marketDay), g.bundle());
    }

    /** A counter's price tag: what is on it and what a lot costs. */
    public static String[] tagLines(ServerLevel level, UUID village, ItemStack shown) {
        if (shown.isEmpty()) return new String[]{ "", "Sold out", "", "" };
        Good g = Budget.goodFor(shown);
        if (g == null) return new String[]{ "", "", "", "" };
        boolean md = marketDay(village, level.getDayTime() / 24000L);
        int p = price(level, village, g, shown, stock(level, village, s -> ItemStack.isSameItemSameComponents(s, shown)), md);
        String name = g.bundle() > 1 ? g.bundle() + " " + g.name() : shown.getHoverName().getString();
        String one = name, two = "";
        if (name.length() > 15) {
            int cut = name.lastIndexOf(' ', 15);
            if (cut <= 0) cut = 15;
            one = name.substring(0, cut).trim();
            two = name.substring(cut).trim();
            if (two.length() > 15) two = two.substring(0, 15);
        }
        return new String[]{ one, two, p + coinWord(p), md ? "Market day!" : "" };
    }

    private static String sell(ServerLevel level, Villages.Village v, Player p, ItemStack hand, Good g, boolean md) {
        UUID id = v.id();
        if (hand.getCount() < g.bundle()) return "The village buys " + g.name().toLowerCase() + " by the " + g.bundle() + ".";
        if (hand.isDamaged()) return "Nobody here wants a worn " + hand.getHoverName().getString().toLowerCase() + ".";
        int price = buyPrice(g, stock(level, id, g.what()), md);
        if (hand.isEnchanted()) price *= 2;
        if (p.isShiftKeyDown()) return "The village would pay " + price + coinWord(price) + " for " + lotName(g, hand) + ".";
        if (Ledger.coins(id) < price) return "The village hasn't the coin to pay for that.";
        ItemStack lot = hand.copyWithCount(g.bundle());
        ItemStack left = intoStores(level, id, lot);
        int stored = g.bundle() - left.getCount();
        if (stored < g.bundle()) {
            // Not room for the whole lot: what went in comes back out.
            if (stored > 0) TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, lot), stored);
            return "The stores are full — they can't take any more just now.";
        }
        hand.shrink(g.bundle());
        int paid = Ledger.takeCoins(id, price);
        Economy.spent(id, paid);
        ItemStack coins = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), paid);
        if (!p.getInventory().add(coins)) p.drop(coins, false);
        thanks(level, v, p);
        return "Sold " + g.bundle() + " " + g.name().toLowerCase() + " for " + paid + coinWord(paid) + ".";
    }

    private static String coinWord(int n) {
        return n == 1 ? " coin" : " coins";
    }

    /**
     * Put a lot into the village's stores, the storehouse first. Returns what would not fit. Onto
     * the part stacks of the same in every store first, then into empty slots (Stacking): this used
     * to drop a lot into the first empty slot it came to, before a half stack of the same further
     * along, and filled chest after chest without topping up the one before.
     */
    static ItemStack intoStores(ServerLevel level, UUID village, ItemStack stack) {
        Economy.storesIn(village, stack);                                       // a maker's work, item by item
        List<BlockPos> stores = new ArrayList<>(Villages.storeChests(level, village));
        List<net.minecraft.world.Container> boxes = new ArrayList<>();
        int storehouse = -1;
        for (BlockPos p : stores) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            if (c instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity) storehouse = boxes.size();
            boxes.add(c);
        }
        int[] took = new int[boxes.size()];
        ItemStack left = Stacking.insert(boxes, stack, took);
        // What went into the storehouse is in its books (the stores' own dealings: a sale, a
        // maker's work, a caravan home, a gift).
        if (storehouse >= 0 && took[storehouse] > 0) Storekeeping.bookIn(level, village, null, stack, took[storehouse], false);
        // Every store full: another chest for it (Villages.growStores), rather than the ground.
        if (!left.isEmpty()) {
            BlockPos more = Villages.growStores(level, village);
            if (more != null && level.getBlockEntity(more) instanceof net.minecraft.world.Container c) {
                left = Stacking.insert(c, left);
            }
        }
        return left;
    }

    /** The coin a player carries. */
    public static int coinsHeld(Player p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (isCoin(s)) n += s.getCount();
        }
        return n;
    }

    static void payOut(Player p, int n) {
        for (int i = 0; i < p.getInventory().getContainerSize() && n > 0; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!isCoin(s)) continue;
            int k = Math.min(n, s.getCount());
            s.shrink(k);
            n -= k;
        }
        p.getInventory().setChanged();
    }

    /** Whoever is nearest the stall says thank you. */
    private static void thanks(ServerLevel level, Villages.Village v, Player p) {
        level.playSound(null, p.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8F, 1.0F);
        VillageFolkEntity near = null;
        double best = 12.0 * 12.0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isSleeping()) continue;
            double d = f.distanceToSqr(p);
            if (d < best) { best = d; near = f; }
        }
        if (near != null) {
            String[] lines = { "Thank you kindly!", "A pleasure doing business.", "Come again!", "Mind how you go!" };
            near.say(lines[level.getRandom().nextInt(lines.length)]);
        }
    }
}
