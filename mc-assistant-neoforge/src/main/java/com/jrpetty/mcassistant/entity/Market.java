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
 *     an old hand — for as long as the coin lasts. Folk save what they earn.</li>
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
            int p = sellPrice(g, stock(level, village, s -> s.is(it)), md);
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
        mint(level, v);
        payWages(level, v);
        if (marketDay(id, day)) {
            level.playSound(null, v.centre(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0F, 1.0F);
            for (ServerPlayer p : level.players()) {
                if (p.blockPosition().closerThan(v.centre(), 96)) {
                    p.displayClientMessage(Component.literal("Market day in " + Villages.name(id) + "!"), true);
                }
            }
        }
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

    /** A folk's wage for a day's work: a coin, and one more at each of levels ten and twenty-five. */
    public static int wage(AssistantEntity a) {
        int lv = a.veteranLevel();
        return 1 + (lv >= 10 ? 1 : 0) + (lv >= 25 ? 1 : 0);
    }

    /** Every working folk paid, while the coin lasts. Returns the coin paid out. */
    public static int payWages(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int paid = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.NONE) continue;
            int got = Ledger.takeCoins(id, wage(f));
            if (got <= 0) break;
            f.earn(got);
            paid += got;
        }
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
            Good g = goodFor(new ItemStack(it));
            int price = g == null ? 1 : Math.max(1, (int) Math.round(each(g, stock(level, v.id(), s -> s.is(it)))));
            if (f.purse() < price) continue;
            if (!TownWork.take(level, v, s -> s.is(it), 1)) continue;
            f.spend(price);
            Ledger.addCoins(v.id(), price);
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
        return buy(level, v, p, shown);
    }

    /** A player buys a lot of what is on a counter, with coin from their pack. Returns what to tell them. */
    public static String buy(ServerLevel level, Villages.Village v, Player p, ItemStack shown) {
        UUID id = v.id();
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST) return "Nobody here will trade with you.";
        if (Laws.banished(id, p.getUUID(), level.getDayTime() / 24000L)) return "You're banished from " + Villages.name(id) + ". Nobody will serve you.";
        boolean md = marketDay(id, level.getDayTime() / 24000L);
        if (shown.isEmpty()) return "Nothing on this counter today.";
        Good g = goodFor(shown);
        if (g == null) return "That's not for sale.";
        // The very thing on the counter: this potion, this drink, this enchanted pick.
        Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, shown);
        String lot = lotName(g, shown);
        int stock = stock(level, id, same);
        if (stock < g.bundle()) return "They've not got " + lot + " to spare just now.";
        int price = price(g, shown, stock, md);
        if (title == Standing.Title.UNWELCOME) price *= 2;
        else if (title.atLeast(Standing.Title.FRIEND)) price = Math.max(1, price - price / 10);
        if (Citizens.is(id, p.getUUID())) price = Math.max(1, price - Math.max(1, price / 10));   // a citizen's ten off
        if (p.isShiftKeyDown()) return lot + ": " + price + coinWord(price) + ". Right-click to buy.";
        int coins = coinsHeld(p);
        if (coins < price) {
            return lot + " for " + price + coinWord(price) + ". You have " + coins + ".";
        }
        if (!TownWork.take(level, v, same, g.bundle())) return "They've not got that to spare just now.";
        payOut(p, price);
        Ledger.addCoins(id, price);
        ItemStack bought = shown.copyWithCount(g.bundle());
        if (!p.getInventory().add(bought)) p.drop(bought, false);
        thanks(level, v, p);
        return "Bought " + lot + " for " + price + coinWord(price) + ".";
    }

    /** What a lot of this is called: "8 bread", or the thing's own name when it is one thing. */
    private static String lotName(Good g, ItemStack shown) {
        return g.bundle() > 1 ? g.bundle() + " " + g.name().toLowerCase() : shown.getHoverName().getString();
    }

    /** What the village asks for a lot of what is on a counter: more for an enchanted thing. */
    static int price(Good g, ItemStack shown, int stock, boolean marketDay) {
        int p = sellPrice(g, stock, marketDay);
        return shown.isEnchanted() ? p * 3 : p;
    }

    /** A counter's price tag: what is on it and what a lot costs. */
    public static String[] tagLines(ServerLevel level, UUID village, ItemStack shown) {
        if (shown.isEmpty()) return new String[]{ "", "Sold out", "", "" };
        Good g = goodFor(shown);
        if (g == null) return new String[]{ "", "", "", "" };
        boolean md = marketDay(village, level.getDayTime() / 24000L);
        int p = price(g, shown, stock(level, village, s -> ItemStack.isSameItemSameComponents(s, shown)), md);
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
        ItemStack coins = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), paid);
        if (!p.getInventory().add(coins)) p.drop(coins, false);
        thanks(level, v, p);
        return "Sold " + g.bundle() + " " + g.name().toLowerCase() + " for " + paid + coinWord(paid) + ".";
    }

    private static String coinWord(int n) {
        return n == 1 ? " coin" : " coins";
    }

    /** Put a lot into the village's stores, the storehouse first. Returns what would not fit. */
    static ItemStack intoStores(ServerLevel level, UUID village, ItemStack stack) {
        ItemStack left = stack.copy();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (left.isEmpty()) break;
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
                ItemStack there = c.getItem(i);
                if (there.isEmpty()) {
                    c.setItem(i, left.copy());
                    left = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(there, left) && there.getCount() < there.getMaxStackSize()) {
                    int move = Math.min(left.getCount(), there.getMaxStackSize() - there.getCount());
                    there.grow(move);
                    left.shrink(move);
                }
            }
            c.setChanged();
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
