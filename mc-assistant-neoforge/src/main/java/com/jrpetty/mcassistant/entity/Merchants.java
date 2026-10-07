package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The merchant from afar. [batchG] On market day a town with a market has a merchant walk in from the edge
 * of the world with a small stall's worth of what its own land has not got: cocoa from the jungle, glow
 * berries from the lush caves under the hills, cane from the river lands, cactus from the desert, saplings of
 * the woods that do not grow here, coral from the warm seas, dyes the meadows here give no flower for.
 * <ul>
 * <li>Its goods come from outside the town, as a wandering trader's do: carried in on its back, sold, and
 *     whatever is not sold carried away again at dusk. Never more than four lots.</li>
 * <li>What it brings is chosen against the land: nothing the town's own ground grows (a jungle town is
 *     brought no cocoa) and nothing its stores already hold.</li>
 * <li><b>The town buys what it needs</b>, as soon as the stall is up: cane when its stores are short of
 *     paper (the gazette, the map on the hall's wall, the library), cocoa for the café's hot cocoa, saplings
 *     when the woodcutters have few, berries when the larder is low, dyes while the houses wait on coloured
 *     rugs. Two lots at most, out of the treasury, and never out of what it keeps for the wages.</li>
 * <li><b>You can buy too</b>: ask it "Trade?", and it offers its lots in turn, for coin or an emerald; hand
 *     the price over as you would to any folk.</li>
 * <li>It leaves at dusk with its takings.</li>
 * </ul>
 */
public final class Merchants {

    private Merchants() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A lot a merchant may carry: what, how many to a lot, its price in coin, and where it comes from. */
    record Ware(Item item, int count, int price, String words, String from, Predicate<Holder<Biome>> growsIn) {}

    private static Predicate<Holder<Biome>> tag(net.minecraft.tags.TagKey<Biome> t) {
        return b -> b.is(t);
    }

    private static Predicate<Holder<Biome>> key(net.minecraft.resources.ResourceKey<Biome> k) {
        return b -> b.is(k);
    }

    private static final Predicate<Holder<Biome>> NOWHERE = b -> false;

    static final List<Ware> WARES = List.of(
        new Ware(Items.COCOA_BEANS, 8, 3, "cocoa beans", "the jungle", tag(BiomeTags.IS_JUNGLE)),
        new Ware(Items.GLOW_BERRIES, 6, 3, "glow berries", "the lush caves under the hills", key(Biomes.LUSH_CAVES)),
        new Ware(Items.SUGAR_CANE, 8, 2, "sugar cane", "the river lands", NOWHERE),
        new Ware(Items.CACTUS, 4, 2, "cactus", "the desert", key(Biomes.DESERT).or(tag(BiomeTags.IS_BADLANDS))),
        new Ware(Items.BAMBOO, 8, 2, "bamboo", "the bamboo forests", key(Biomes.BAMBOO_JUNGLE)),
        new Ware(Items.JUNGLE_SAPLING, 2, 2, "jungle saplings", "the jungle", tag(BiomeTags.IS_JUNGLE)),
        new Ware(Items.ACACIA_SAPLING, 2, 2, "acacia saplings", "the savanna", tag(BiomeTags.IS_SAVANNA)),
        new Ware(Items.DARK_OAK_SAPLING, 4, 3, "dark oak saplings", "the dark forest", key(Biomes.DARK_FOREST)),
        new Ware(Items.CHERRY_SAPLING, 2, 3, "cherry saplings", "the cherry groves", key(Biomes.CHERRY_GROVE)),
        new Ware(Items.SPRUCE_SAPLING, 2, 2, "spruce saplings", "the taiga", tag(BiomeTags.IS_TAIGA)),
        new Ware(Items.SWEET_BERRIES, 8, 2, "sweet berries", "the taiga", tag(BiomeTags.IS_TAIGA)),
        new Ware(Items.MELON_SLICE, 8, 2, "melon", "the jungle", tag(BiomeTags.IS_JUNGLE)),
        new Ware(Items.TUBE_CORAL_BLOCK, 2, 4, "coral", "the warm seas", key(Biomes.WARM_OCEAN)),
        new Ware(Items.BLUE_DYE, 4, 2, "blue dye", "the cornflower meadows", NOWHERE),
        new Ware(Items.PURPLE_DYE, 4, 3, "purple dye", "the far meadows", NOWHERE),
        new Ware(Items.HONEYCOMB, 3, 3, "honeycomb", "the flower forests", key(Biomes.FLOWER_FOREST)));

    static void resetForTests() { }

    @Nullable
    static Ware ware(Item item) {
        for (Ware w : WARES) if (w.item() == item) return w;
        return null;
    }

    // ------------------------------------------------------------------ coming

    /** Should the merchant come today (the town's round)? Market day, in a town with a market, once. */
    static void consider(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        if (t < 1000L || t > 6000L) return;
        if (Visitors.building(id, "market") == null || !Market.marketDay(id, day)) return;
        if (Raids.underAlarm(id) || Weather.stormy(level)) return;
        if (Bard.parse(Ledger.note(id, "visit/merchant")) == day) return;
        if (!Visitors.inTown(level, id, Visitors.Kind.MERCHANT).isEmpty()) return;
        come(level, v, day);
    }

    /**
     * What a merchant brings this town: up to four lots of the wares, none its own land grows (the biome at
     * the heart) and none its stores already hold, a different choice from one market day to the next.
     */
    static List<Ware> choose(ServerLevel level, Villages.Village v, long day) {
        Holder<Biome> here = level.getBiome(v.centre());
        List<Ware> fit = new ArrayList<>();
        for (Ware w : WARES) {
            if (w.growsIn().test(here)) continue;
            if (Market.stock(level, v.id(), s -> s.is(w.item())) > 0) continue;
            fit.add(w);
        }
        java.util.Collections.shuffle(fit, new java.util.Random(v.id().getMostSignificantBits() ^ day * 7919L));
        return fit.size() > 4 ? new ArrayList<>(fit.subList(0, 4)) : fit;
    }

    /** The merchant comes in from the edge with its lots on its back; it leaves at dusk. */
    @Nullable
    static VillageFolkEntity come(ServerLevel level, Villages.Village v, long day) {
        List<Ware> wares = choose(level, v, day);
        if (wares.isEmpty()) return null;
        List<ItemStack> kit = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Ware w : wares) {
            kit.add(new ItemStack(w.item(), w.count()));
            if (sb.length() > 0) sb.append(',');
            sb.append(BuiltInRegistries.ITEM.getKey(w.item()).getPath());
        }
        ItemStack curio = Auctions.curio(level, v, day);              // [fleet] a curio from far away, for the auction
        if (!curio.isEmpty()) kit.add(curio);
        VillageFolkEntity f = Visitors.arrive(level, v, Visitors.Kind.MERCHANT, day, 0, 0, kit, "a merchant from far away");
        if (f == null) return null;
        Visitors.Visit vis = Visitors.visit(f);
        if (vis != null) {
            vis.wares = sb.toString();
            Visitors.save(f, vis);
        }
        Ledger.note(v.id(), "visit/merchant", Long.toString(day));
        return f;
    }

    /** /village visitors merchant, and the game tests: the merchant comes now, market day or not. */
    @Nullable
    static VillageFolkEntity comeForTests(ServerLevel level, Villages.Village v) {
        return come(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the merchant comes to this town now (walking in from the edge). */
    @Nullable
    public static VillageFolkEntity arriveNowForTests(ServerLevel level, Villages.Village v) {
        return comeForTests(level, v);
    }

    /** The lots it still has with it, as wares. */
    static List<Ware> stocked(VillageFolkEntity f, Visitors.Visit v) {
        List<Ware> out = new ArrayList<>();
        if (v.wares.isEmpty()) return out;
        for (String id : v.wares.split(",")) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id));
            Ware w = ware(item);
            if (w != null && f.countCarried(s -> s.is(w.item())) >= w.count()) out.add(w);
        }
        return out;
    }

    /** "cocoa beans from the jungle (8 for 3c)", for its card and the books. */
    static String waresLine(Visitors.Visit v) {
        List<String> out = new ArrayList<>();
        for (String id : v.wares.split(",")) {
            Ware w = ware(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id)));
            if (w != null) out.add(w.words() + " from " + w.from() + " (" + w.count() + " for " + w.price() + "c)");
        }
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ at the market

    /** At the market: the stall up, the town told, and the town's own buying done. */
    static void arrived(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        List<String> words = new ArrayList<>();
        for (Ware w : stocked(f, v)) words.add(w.words());
        String name = Villages.name(town.id());
        Villages.tell(town.id(), day, v.name + ", a merchant from afar, set up at the market with " + list(words));
        FolkTalk.speak(f, "Goods from afar! " + FolkTalk.cap(list(words)) + " — things you'll not find in " + name + "!");
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(town.centre(), 128)) {
                p.displayClientMessage(Component.literal("A merchant from afar is at " + name + "'s market today, with " + list(words) + "."), true);
            }
        }
        String bought = townBuys(level, town, f, v, day);
        if (!bought.isEmpty()) LOG.info("[MCA-VISIT] {} bought from the merchant {}: {}", name, v.name, bought);
        Pets.merchant(level, town, f, day);                    // [pets] a pup or a kitten on its lead, for a household that wants one
    }

    static String list(List<String> words) {
        if (words.isEmpty()) return "nothing much";
        if (words.size() == 1) return words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    /**
     * The town buys what it needs off the stall, out of the treasury: two lots at most, each only if the
     * treasury has the price over what it keeps for the morning's wages. The goods go into the stores, the
     * coin into the merchant's purse. What was bought, in words ("" for nothing).
     */
    static String townBuys(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        UUID id = town.id();
        int keep = Math.max(10, 3 * Villages.headcount(id));
        List<String> bought = new ArrayList<>();
        for (Ware w : stocked(f, v)) {
            if (bought.size() >= 2) break;
            String why = need(level, town, w);
            if (why == null) continue;
            if (Ledger.coins(id) < w.price() + keep) continue;
            int paid = Ledger.takeCoins(id, w.price());
            if (paid < w.price()) {
                if (paid > 0) Ledger.addCoins(id, paid);
                continue;
            }
            int took = f.removeMatching(s -> s.is(w.item()), w.count());
            if (took <= 0) {
                Ledger.addCoins(id, paid);
                continue;
            }
            f.earn(paid);
            Economy.spent(id, paid);
            Crafts.store(level, town, new ItemStack(w.item(), took));
            bought.add(took + " " + w.words() + " for " + Visitors.coins(paid) + " (" + why + ")");
        }
        if (!bought.isEmpty()) Villages.tell(id, day, "the town bought " + list(bought) + " from the merchant from afar");
        return String.join("; ", bought);
    }

    /** Why the town wants this lot, or null if it does not: its own needs, read off its stores and its works. */
    @Nullable
    static String need(ServerLevel level, Villages.Village town, Ware w) {
        UUID id = town.id();
        Item i = w.item();
        if (i == Items.SUGAR_CANE) {
            return Market.stock(level, id, s -> s.is(Items.PAPER)) < 18 ? "paper for the gazette, the map room and the books" : null;
        }
        if (i == Items.COCOA_BEANS) return Visitors.building(id, "cafe") != null ? "the café's hot cocoa" : null;
        if (w.words().endsWith("saplings")) {
            return Market.stock(level, id, s -> s.is(ItemTags.SAPLINGS)) < 8 ? "saplings for the woodcutters" : null;
        }
        if (i == Items.SWEET_BERRIES || i == Items.GLOW_BERRIES || i == Items.MELON_SLICE) {
            for (Villages.Need n : Villages.needs(level, id)) if (n.task() == Villages.Task.FOOD) return "the larder is low";
            return null;
        }
        if (i == Items.BLUE_DYE || i == Items.PURPLE_DYE) return Decor.wants(id).isEmpty() ? null : "the houses wait on coloured rugs";
        return null;
    }

    /** Its day at the market: stood at the stall calling its wares; at dusk, away. True when it goes. */
    static boolean stay(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day, long t) {
        if (t >= 12000L || day > v.arrived) return true;
        Ledger.Building market = Visitors.building(town.id(), "market");
        BlockPos at = market == null ? town.centre() : market.anchor();
        if (!Visitors.walk(f, level, at, 4.0, 0.6D, v.walk)) return false;
        long now = level.getGameTime();
        if (now - v.lastSaid > 800L) {
            v.lastSaid = now;
            List<Ware> left = stocked(f, v);
            if (left.isEmpty()) return true;                   // sold out: home early
            Ware w = left.get(f.getRandom().nextInt(left.size()));
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), FolkTalk.cap(w.words()) + " from " + w.from() + "! " + w.count() + " for "
                + Visitors.coins(w.price()) + "!", "Who'll have my " + w.words() + "? All the way from " + w.from() + "!"));
        }
        return false;
    }

    // ------------------------------------------------------------------ a player buys

    /** "Trade?": the next of its lots offered, for coin or an emerald, on the table as any folk's offer is. */
    static String offer(VillageFolkEntity f, Visitors.Visit v, Player p) {
        List<Ware> left = stocked(f, v);
        if (left.isEmpty()) return "I'm sold out, friend. Next market day!";
        Ware w = left.get(Math.floorMod(v.sight, left.size()));
        v.sight++;
        Visitors.save(f, v);
        Trade.Goods goods = new Trade.Goods(w.words(), s -> s.is(w.item()), w.count(), Villages.Task.NONE, 0);
        Trade.table(f, new Trade.Deal(p.getUUID(), goods, w.count(), 1, "", 0, f.level().getGameTime() + 6000L, w.price()));
        return FolkTalk.cap(w.words()) + ", from " + w.from() + ": " + w.count() + " for " + Visitors.coins(w.price())
            + ", or an emerald. Hold out the price and hand it over." + (left.size() > 1 ? " Ask again to see my other lots." : "");
    }

    /** "Hand over": the price paid into its purse (or the emerald into its pack), the lot into yours. */
    static String close(VillageFolkEntity f, Visitors.Visit v, Player p) {
        Trade.Deal d = Trade.tabled(f);
        if (d == null || !Trade.live(f, p)) return "Ask me what I've got first!";
        if (f.countCarried(d.goods().what()) < d.count()) {
            Trade.untable(f);
            return "Ah — that's gone already. Ask me again.";
        }
        if (Market.coinsHeld(p) >= d.coins()) {
            Market.payOut(p, d.coins());
            f.earn(d.coins());
        } else {
            int emeralds = 0;
            for (ItemStack s : p.getInventory().items) if (s.is(Items.EMERALD)) emeralds += s.getCount();
            if (emeralds < d.emeralds()) return "That's " + Visitors.coins(d.coins()) + ", or an emerald. You've not got it on you.";
            int need = d.emeralds();
            for (ItemStack s : p.getInventory().items) {
                if (need <= 0) break;
                if (!s.is(Items.EMERALD)) continue;
                int k = Math.min(need, s.getCount());
                ItemStack paid = s.split(k);
                need -= k;
                ItemStack left = f.insertGiven(paid);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            }
        }
        int took = f.removeMatching(d.goods().what(), d.count());
        Ware w = null;
        for (Ware x : WARES) if (d.goods().what().test(new ItemStack(x.item()))) { w = x; break; }
        ItemStack lot = new ItemStack(w == null ? Items.AIR : w.item(), took);
        if (!lot.isEmpty() && !p.getInventory().add(lot)) p.drop(lot, false);
        Trade.untable(f);
        if (p instanceof ServerPlayer sp) Advancements.firstTrade(sp);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_TRADE, 1.0F, 1.0F);
        return FolkTalk.pick(f.getRandom(), "A pleasure! You'll not see those again till next market day.", "Done! Mind how you go with them.");
    }

    /** Tests: the town's buying off the stall, now. What it bought. */
    public static String townBuysForTests(ServerLevel level, VillageFolkEntity f) {
        Visitors.Visit v = Visitors.visit(f);
        Villages.Village town = v == null ? null : Villages.get(v.town);
        return town == null ? "" : townBuys(level, town, f, v, level.getDayTime() / 24000L);
    }

    /** Tests: a player asks the merchant to trade, and hands over the price. What it said each time. */
    public static String[] buyForTests(VillageFolkEntity f, Player p) {
        Visitors.Visit v = Visitors.visit(f);
        if (v == null) return new String[]{ "", "" };
        return new String[]{ offer(f, v, p), close(f, v, p) };
    }

    /** Tests: the wares a merchant would bring this town today. */
    public static List<Item> choiceForTests(ServerLevel level, Villages.Village v) {
        List<Item> out = new ArrayList<>();
        for (Ware w : choose(level, v, level.getDayTime() / 24000L)) out.add(w.item());
        return out;
    }

    /** Tests: does the town want this lot (its needs)? */
    public static boolean neededForTests(ServerLevel level, Villages.Village v, Item item) {
        Ware w = ware(item);
        return w != null && need(level, v, w) != null;
    }

    /** Tests: the wares a merchant still carries. */
    public static List<Item> stockedForTests(VillageFolkEntity f) {
        List<Item> out = new ArrayList<>();
        Visitors.Visit v = Visitors.visit(f);
        if (v != null) for (Ware w : stocked(f, v)) out.add(w.item());
        return out;
    }
}
