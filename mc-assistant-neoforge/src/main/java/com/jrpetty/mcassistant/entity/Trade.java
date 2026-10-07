package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Trading with a folk. Every trade has its goods: a farmer's bread and crops, a
 * woodcutter's logs, a miner's coal and copper, a rancher's wool and leather, a
 * fisher's catch, a smelter's charcoal and glass, a guard's arrows.
 *
 * <p>Ask "got anything to trade?" and a folk offers a bundle of what it can spare,
 * out of its own pack or else the village stores. It asks an emerald for it, or,
 * when the village is short of something, some of that instead. Hold the price and
 * press Hand over.
 *
 * <p>It never sells what the village itself is short of. Standing counts: a friend
 * of the village gets a bigger bundle for the same price, an honoured guest bigger
 * still. Someone unwelcome pays double, and an outcast is not sold anything.
 */
public final class Trade {

    private Trade() {}

    /** What a trade has to sell: its name, how many make a bundle, and the village need it would eat into. */
    record Goods(String words, Predicate<ItemStack> what, int count, Villages.Task need, int reserve) {}

    /** An offer on the table: lasts a few minutes, for the one player it was made to. */
    record Deal(UUID player, Goods goods, int count, int emeralds, String alt, int altCount, long until, int coins) {}

    private static final Map<UUID, Deal> DEALS = new ConcurrentHashMap<>();
    private static final long LASTS = 6000L;

    private static List<Goods> goodsOf(AssistantEntity.StationTask trade) {
        return switch (trade) {
            case FARM -> List.of(
                new Goods("bread", s -> s.is(Items.BREAD), 6, Villages.Task.FOOD, 4),
                new Goods("wheat", s -> s.is(Items.WHEAT), 16, Villages.Task.FOOD, 0),
                new Goods("carrots", s -> s.is(Items.CARROT), 12, Villages.Task.FOOD, 2),
                new Goods("potatoes", s -> s.is(Items.POTATO), 12, Villages.Task.FOOD, 2));
            case WOOD -> List.of(
                new Goods("logs", s -> s.is(ItemTags.LOGS), 16, Villages.Task.LOGS, 0),
                new Goods("saplings", s -> s.is(ItemTags.SAPLINGS), 8, Villages.Task.NONE, 4),
                new Goods("planks", s -> s.is(ItemTags.PLANKS), 32, Villages.Task.LOGS, 0));
            case MINE -> List.of(
                new Goods("coal", s -> s.is(Items.COAL), 12, Villages.Task.COAL, 0),
                new Goods("cobblestone", s -> s.is(Items.COBBLESTONE), 32, Villages.Task.STONE, 0),
                new Goods("raw copper", s -> s.is(Items.RAW_COPPER), 8, Villages.Task.NONE, 0),
                new Goods("lapis", s -> s.is(Items.LAPIS_LAZULI), 8, Villages.Task.NONE, 0),
                new Goods("redstone", s -> s.is(Items.REDSTONE), 12, Villages.Task.NONE, 0));
            case RANCH -> List.of(
                new Goods("wool", s -> s.is(ItemTags.WOOL), 8, Villages.Task.NONE, 3),
                new Goods("leather", s -> s.is(Items.LEATHER), 4, Villages.Task.NONE, 0),
                new Goods("eggs", s -> s.is(Items.EGG), 8, Villages.Task.NONE, 0),
                new Goods("raw meat", s -> s.is(Items.BEEF) || s.is(Items.MUTTON) || s.is(Items.PORKCHOP)
                    || s.is(Items.CHICKEN), 6, Villages.Task.FOOD, 2));
            case FISH -> List.of(
                new Goods("cod", s -> s.is(Items.COD) || s.is(Items.COOKED_COD), 6, Villages.Task.FOOD, 2),
                new Goods("salmon", s -> s.is(Items.SALMON) || s.is(Items.COOKED_SALMON), 4, Villages.Task.FOOD, 2));
            case SMELT -> List.of(
                new Goods("charcoal", s -> s.is(Items.CHARCOAL), 8, Villages.Task.COAL, 0),
                new Goods("glass", s -> s.is(Items.GLASS), 12, Villages.Task.NONE, 0),
                new Goods("smooth stone", s -> s.is(Items.SMOOTH_STONE), 16, Villages.Task.STONE, 0));
            case GUARD -> List.of(new Goods("arrows", s -> s.is(Items.ARROW), 16, Villages.Task.NONE, 16));
            case NONE -> List.of();
            default -> List.of(
                new Goods("torches", s -> s.is(Items.TORCH), 12, Villages.Task.NONE, 0),
                new Goods("bread", s -> s.is(Items.BREAD), 6, Villages.Task.FOOD, 4));
        };
    }

    // ------------------------------------------------------------------ offering

    /** "Got anything to trade?" */
    static String offer(VillageFolkEntity f, Player p) {
        RandomSource r = f.getRandom();
        UUID village = f.ownerId();
        if (f.stationTask() == AssistantEntity.StationTask.NONE || village == null
                || !(f.level() instanceof ServerLevel server)) {
            return "I've no trade yet, so I've nothing to sell. Ask me once I've found my feet.";
        }
        Standing.Title title = Standing.of(village, p.getUUID(), f.level().getGameTime()).title();
        if (title == Standing.Title.OUTCAST) return "I'll not trade with you. Nobody here will.";
        List<Villages.Need> needs = Villages.needs(server, village);
        java.util.EnumSet<Villages.Task> short_ = java.util.EnumSet.noneOf(Villages.Task.class);
        for (Villages.Need n : needs) short_.add(n.task());
        // A bigger bundle the better the village thinks of you.
        double more = switch (title) {
            case FRIEND -> 1.5;
            case HONOURED, HERO -> 2.0;
            default -> 1.0;
        };
        if (f.life().has(Social.Trait.GENEROUS)) more *= 1.25;
        List<Goods> wares = new ArrayList<>(goodsOf(f.stationTask()));
        java.util.Collections.shuffle(wares, new java.util.Random(r.nextLong()));
        boolean held = false;
        for (Goods g : wares) {
            if (g.need() != Villages.Task.NONE && short_.contains(g.need())) { held = true; continue; }
            int count = (int) Math.round(g.count() * more);
            if (!stocked(f, g, count)) continue;
            int emeralds = title == Standing.Title.UNWELCOME ? 2 : 1;
            // When the village is short of something, the folk would sooner have that.
            String alt = "";
            int altCount = 0;
            for (Villages.Need n : needs) {
                String item = Errands.supplyItem(n.task());
                if (item == null || "diamond".equals(item) || "obsidian".equals(item)) continue;
                alt = item;
                altCount = Math.max(4, Errands.supplyCount(item, n.amount()) / 2);
                if (title == Standing.Title.UNWELCOME) altCount *= 2;
                break;
            }
            // Its price in the village's coin, at the market's worth of the bundle it would ask an emerald for.
            int coins = coinPrice(f, g, (int) Math.round(g.count()));
            if (title == Standing.Title.UNWELCOME) coins *= 2;
            Deal d = new Deal(p.getUUID(), g, count, emeralds, alt, altCount, f.level().getGameTime() + LASTS, coins);
            DEALS.put(f.getUUID(), d);
            if (DEALS.size() > 256) DEALS.clear();
            StringBuilder said = new StringBuilder(pick(r, "I could let you have ", "You can have ", "I'll part with "))
                .append(count).append(' ').append(g.words()).append(" for ").append(coins).append(coins == 1 ? " coin" : " coins")
                .append(", or ").append(emeralds == 1 ? "an emerald" : emeralds + " emeralds").append('.');
            if (!alt.isEmpty()) {
                said.append(" Or ").append(Errands.words(alt, altCount))
                    .append(" instead — we're short of it, and I'd sooner have that.");
            }
            if (title.atLeast(Standing.Title.FRIEND)) {
                said.append(pick(r, " A little extra, for a friend of the village.", " I've put a bit more in, for you."));
            } else if (more > 1.0) {
                said.append(pick(r, " I've put a bit more in — I can't help myself.", " And a few over, because why not."));
            }
            if (title == Standing.Title.UNWELCOME) said.append(" And that's the price for you. Take it or leave it.");
            said.append(" Hold out the price and hand it over.");
            return said.toString();
        }
        DEALS.remove(f.getUUID());
        if (held) return pick(r, "We're short ourselves just now. I can't sell what the village needs.",
            "Not today — the village needs every bit of it. Ask me again when we're better off.");
        return pick(r, "I've nothing to spare today. Come back when I've been at it a while.",
            "My pack's empty and so are the stores, near enough. Another day.");
    }

    /** Has it (or the stores) got a bundle to spare, keeping back what it needs for itself? */
    private static boolean stocked(VillageFolkEntity f, Goods g, int count) {
        int want = count + g.reserve();
        int have = f.countCarried(g.what());
        if (have >= want) return true;
        if (f.villageCentre() == null || f.ownerId() == null) return false;
        f.drawFrom(f.villageCentre(), g.what(), want - have, Villages.storesRadius(f.ownerId()));
        return f.countCarried(g.what()) >= want;
    }

    // ------------------------------------------------------------------ closing

    /** Is there an offer standing for this player? */
    public static boolean live(VillageFolkEntity f, Player p) {
        Deal d = DEALS.get(f.getUUID());
        if (d == null) return false;
        if (f.level().getGameTime() >= d.until()) {
            DEALS.remove(f.getUUID());
            return false;
        }
        return d.player().equals(p.getUUID());
    }

    /** "6 bread for an emerald (or 16 cobblestone)", for the talk screen. */
    public static String describe(VillageFolkEntity f) {
        Deal d = DEALS.get(f.getUUID());
        if (d == null) return "";
        return d.count() + " " + d.goods().words() + " for " + d.coins() + (d.coins() == 1 ? " coin, or " : " coins, or ")
            + (d.emeralds() == 1 ? "an emerald" : d.emeralds() + " emeralds")
            + (d.alt().isEmpty() ? "" : " (or " + Errands.words(d.alt(), d.altCount()) + ")");
    }

    /** Can this player pay, out of what they carry? */
    public static boolean canPay(VillageFolkEntity f, Player p) {
        if (!live(f, p)) return false;
        Deal d = DEALS.get(f.getUUID());
        if (d == null) return false;
        return Market.coinsHeld(p) >= d.coins() || carried(p, s -> s.is(Items.EMERALD)) >= d.emeralds()
            || (!d.alt().isEmpty() && carried(p, Errands.matcher(d.alt())) >= d.altCount());
    }

    /** Hand over the price; take the goods. */
    static String close(VillageFolkEntity f, Player p) {
        RandomSource r = f.getRandom();
        Deal d = DEALS.get(f.getUUID());
        if (d == null || !live(f, p)) return "We haven't struck a deal. Ask me what I've got to trade.";
        boolean inKind;
        Predicate<ItemStack> price;
        int priceCount;
        boolean inCoin = false;
        if (Market.coinsHeld(p) >= d.coins()) {
            inKind = false;
            inCoin = true;
            price = s -> false;
            priceCount = 0;
        } else if (carried(p, s -> s.is(Items.EMERALD)) >= d.emeralds()) {
            inKind = false;
            price = s -> s.is(Items.EMERALD);
            priceCount = d.emeralds();
        } else if (!d.alt().isEmpty() && carried(p, Errands.matcher(d.alt())) >= d.altCount()) {
            inKind = true;
            price = Errands.matcher(d.alt());
            priceCount = d.altCount();
        } else {
            return "That's not enough. It's " + describe(f) + ".";
        }
        // The goods may have gone since it offered them.
        if (!stocked(f, d.goods(), d.count())) {
            DEALS.remove(f.getUUID());
            return pick(r, "Oh — the last of it's gone since I offered. Sorry!", "Ah. Somebody's had it already. Another time.");
        }
        if (inCoin) {
            // Coin goes to the treasury: the goods were the village's.
            Market.payOut(p, d.coins());
            if (f.ownerId() != null) {
                com.jrpetty.mcassistant.village.Ledger.addCoins(f.ownerId(), d.coins());
                Economy.sold(f.ownerId(), d.coins());
            }
        } else {
            List<ItemStack> paid = take(p.getInventory().items, price, priceCount);
            for (ItemStack s : paid) {
                ItemStack left = f.insertItem(s);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            }
        }
        List<ItemStack> goods = take(f.getInventoryItems(), d.goods().what(), d.count());
        for (ItemStack s : goods) if (!p.getInventory().add(s)) p.drop(s, false);
        DEALS.remove(f.getUUID());
        Persona me = f.persona();
        String you = p.getName().getString();
        long day = f.level().getDayTime() / 24000L;
        me.feelFor(p.getUUID(), you, inKind ? 3 : 1);
        if (inKind && f.ownerId() != null) Standing.stir(f.ownerId(), p.getUUID());
        if (me.memories().stream().noneMatch(m -> m.text().equals("I traded with " + you))) {
            me.remember(day, "I traded with " + you, 2);
        }
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_TRADE, 1.0F, 1.0F);
        return pick(r, "Pleasure doing business!", "A fair trade. Enjoy them!", "Done! Come back any time.")
            + (inKind ? pick(r, " That'll go straight to the stores.", " The village will be glad of it.") : "");
    }

    /** The bundle's worth at the market, in coin (a coin at least). */
    private static int coinPrice(VillageFolkEntity f, Goods g, int count) {
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !g.what().test(s)) continue;
            Market.Good mg = Market.goodFor(s);
            if (mg == null) break;
            // [econ-prices] At its town's price today (PriceIndex); its usual worth with no town to ask.
            double each = f.ownerId() == null || !(f.level() instanceof ServerLevel sl) ? mg.value() : PriceIndex.each(sl, f.ownerId(), mg);
            return Math.max(1, (int) Math.round(each * count));
        }
        return Math.max(1, count / 4);
    }

    private static int carried(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    /** Take up to {@code count} of a thing out of a list of stacks. */
    private static List<ItemStack> take(List<ItemStack> from, Predicate<ItemStack> what, int count) {
        List<ItemStack> out = new ArrayList<>();
        int need = count;
        for (ItemStack s : from) {
            if (need <= 0) break;
            if (s.isEmpty() || !what.test(s)) continue;
            out.add(s.split(Math.min(need, s.getCount())));
            need -= out.get(out.size() - 1).getCount();
        }
        return out;
    }

    private static String pick(RandomSource r, String... options) {
        return options[r.nextInt(options.length)];
    }

    public static void resetForTests() { DEALS.clear(); }
}
