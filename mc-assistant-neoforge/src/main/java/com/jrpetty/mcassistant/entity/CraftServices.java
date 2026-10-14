package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [roles-b] What the crafts do for a player, each out of what the town really has, at the town's own prices:
 * <ul>
 * <li><b>The tailor</b> makes clothes, banners, beds and carpets to order (Dealings.commission: the makings out of
 *     your pack first, then what the stores can spare; paid now, ready tomorrow), at its table's screen (MakerDesk) or
 *     when told what ("make me a red banner").</li>
 * <li><b>The brewer</b> sells a potion the stores hold; one they do not, it brews to order from the stores' makings,
 *     paid now: it is the next the stand brews (Crafts.wanted), and handed over when it comes out. Ordered and never
 *     brewed in three days, the coin comes back.</li>
 * <li><b>The enchanter</b> enchants the thing in your hand at its table, as strong as the shelves round it and its own
 *     hand allow (Crafts.spellFor, Craftsmanship): three lapis and a book out of the stores (else your own), and its
 *     fee for the work.</li>
 * <li><b>The beekeeper</b> sells honey, comb and candles out of the stores, and fills your bottle at a full hive when
 *     the stores have none.</li>
 * </ul>
 * Nothing done, nothing taken and nothing paid when the makings are not there, and the player is told why.
 */
public final class CraftServices {

    private CraftServices() {}

    /** The enchanter's fee, a rank of the work. */
    public static final int ENCHANT_FEE = 3;
    /** Lapis and books an enchanting takes. */
    static final int LAPIS = 3, BOOKS = 1;
    /** Days a potion order waits for the stand before its coin comes back. */
    static final int ORDER_DAYS = 3;
    /** The most of a thing the beekeeper sells at once. */
    static final int HONEY_MOST = 8;
    /** How near its table the enchanter must be to work at it. */
    static final double AT_TABLE = 12.0;

    /** The ledger's note of a town's potion orders. */
    private static final String ORDERS_NOTE = "brew.orders";

    /** A potion paid for and waiting on the stand: whose, which, the day it was ordered and what was paid. */
    record BrewOrder(UUID player, String potion, long day, int paid) {}

    private static final Map<UUID, Map<UUID, BrewOrder>> ORDERS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ORDERS.clear();
    }

    // ------------------------------------------------------------------ the tailor

    /** Is this the tailor's work (cloth, wool, leather, a binding), rather than the smith's? */
    static boolean tailorsWork(Item it) {
        ItemStack s = new ItemStack(it);
        String path = BuiltInRegistries.ITEM.getKey(it).getPath();
        return s.is(ItemTags.BANNERS) || s.is(ItemTags.BEDS) || s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.WOOL)
            || path.startsWith("leather_") || it == Items.LEAD || it == Items.BOOK || it == Items.SADDLE;
    }

    /** "Could you make me something?": the tailor's order, made or collected; another folk sends the player on. */
    public static String tailor(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "I've nowhere to sew.";
        if (f.stationTask() != StationTask.TAILOR) return sendOn(village, StationTask.TAILOR, "Clothes and banners are the tailor's work");
        if (Dealings.hasOrder(p.getUUID(), village)) return Dealings.order(f, p, text);
        Item want = text == null || text.isBlank() ? null : Services.itemNamed(text);
        if (want == null) {
            return "Beds, banners, carpets, leather clothes, a lead or a bound book, made to order: say which. \"Make me a red banner\", say. "
                + "Your own makings first, then what the stores can spare; paid now, ready tomorrow.";
        }
        if (!tailorsWork(want)) return "That's not cloth or leather work. Ask the smith.";
        return Dealings.order(f, p, "make me " + text);
    }

    // ------------------------------------------------------------------ the brewer

    /** Words a player names a potion by, as well as its own name ("speed" for swiftness). */
    private static final Map<String, List<String>> POTION_WORDS = Map.of(
        "swiftness", List.of("speed", "swift"),
        "healing", List.of("heal", "health"),
        "night_vision", List.of("night vision", "see in the dark"),
        "regeneration", List.of("regen"),
        "leaping", List.of("leap", "jump"),
        "water_breathing", List.of("water breathing", "breathe under", "breathing"),
        "fire_resistance", List.of("fire resist", "fireproof", "fire"),
        "strength", List.of("strong"));

    /** The brew a player names, or null. */
    @Nullable
    static Crafts.Brew brewNamed(Villages.Village v, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (Crafts.Brew b : Crafts.brews(v)) {
            String path = path(b.potion());
            if (t.contains(path.replace('_', ' '))) return b;
            for (String w : POTION_WORDS.getOrDefault(path, List.of())) if (t.contains(w)) return b;
        }
        return null;
    }

    static String path(Holder<Potion> pot) {
        return pot.unwrapKey().map(k -> k.location().getPath()).orElse("potion");
    }

    static String nameOf(Holder<Potion> pot) {
        String w = path(pot).replace('_', ' ');
        return "Potion of " + Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** What the brewer brews, in a line: "healing, swiftness, night vision...". */
    static String brewList(Villages.Village v) {
        List<String> w = new ArrayList<>();
        for (Crafts.Brew b : Crafts.brews(v)) w.add(path(b.potion()).replace('_', ' '));
        return String.join(", ", w);
    }

    /**
     * "Brew me a potion of healing": one out of the stores if they have it, else brewed to order (paid now, the next
     * the stand brews). Asked again, the order handed over once it is out of the stand, or its coin back after three
     * days. Another folk sells from the stores as ever (Services.stores) or sends the player on.
     */
    public static String potion(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Potions? Not from me.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "Potions? Not from me.";
        if (f.stationTask() != StationTask.BREW) {
            if (Services.itemNamed(text) != null) return Services.stores(f, p, text);
            return sendOn(village, StationTask.BREW, "Potions are the brewer's work");
        }
        String refused = refused(level, village, p);
        if (refused != null) return refused;
        BrewOrder mine = ordersOf(village).get(p.getUUID());
        if (mine != null) return collect(level, v, f, p, mine);
        Crafts.Brew b = brewNamed(v, text);
        if (b == null) return "Which potion? I brew " + brewList(v) + ". \"Brew me a potion of healing\", say.";
        return orderOrSell(level, v, f, p, b, false);
    }

    /**
     * A potion for the player: sold from the stores if they have one to spare; else, if the brewer has the makings,
     * paid now and brewed next. With {@code dryRun}, only what it would cost ("QUOTE|price|what"), nothing done.
     */
    static String orderOrSell(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, Crafts.Brew b, boolean dryRun) {
        UUID village = v.id();
        ItemStack one = PotionContents.createItemStack(Items.POTION, b.potion());
        String name = nameOf(b.potion());
        Predicate<ItemStack> same = s -> Crafts.isPotion(s, b.potion());
        int price = priceOf(level, village, p, one, 1);
        boolean inStores = Market.stock(level, village, same) > 0 && Budget.spare(level, village, one) > 0;
        if (inStores) {
            if (dryRun) return "QUOTE|" + price + "|" + price + coinsWord(price) + ", from the stores";
            Sold s = sell(level, village, p, one, same, 1);
            if (s.count() <= 0) return s.why();
            f.swing(InteractionHand.MAIN_HAND);
            return "A " + name.toLowerCase(Locale.ROOT) + " from the stores: " + s.price() + coinsWord(s.price()) + ", thank you.";
        }
        if (!Crafts.reagent(level, v, f, b, false)) {
            return "We've none in the stores, and I've no " + word(new ItemStack(b.reagent()))
                + " to brew a " + name.toLowerCase(Locale.ROOT) + " with. Bring some and I'll set it on.";
        }
        if (Crafts.have(level, v, f, s -> s.is(Items.NETHER_WART)) < 1) {
            return "We've none in the stores, and no nether wart to start a brew with. Come back when the wart's grown.";
        }
        if (dryRun) return "QUOTE|" + price + "|" + price + coinsWord(price) + ", brewed to order: next off the stand";
        if (Market.coinsHeld(p) < price) return "A " + name.toLowerCase(Locale.ROOT) + " brewed to order is " + price + coinsWord(price)
            + ". You've " + Market.coinsHeld(p) + ".";
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        Economy.sold(village, price);
        long day = level.getDayTime() / 24000L;
        ordersOf(village).put(p.getUUID(), new BrewOrder(p.getUUID(), path(b.potion()), day, price));
        saveOrders(village);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        return "Right you are: " + price + coinsWord(price) + ". The " + name.toLowerCase(Locale.ROOT)
            + " is the next the stand brews. Ask me for it when it's out.";
    }

    /** An order asked after: handed over if one is in the stores, its coin back if three days went by, else not yet. */
    private static String collect(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, BrewOrder o) {
        UUID village = v.id();
        Holder<Potion> pot = potionOf(o.potion());
        long day = level.getDayTime() / 24000L;
        String name = pot == null ? "potion" : nameOf(pot).toLowerCase(Locale.ROOT);
        if (pot != null) {
            List<ItemStack> got = Services.take(level, village, s -> Crafts.isPotion(s, pot), 1);
            if (!got.isEmpty()) {
                ordersOf(village).remove(p.getUUID());
                saveOrders(village);
                for (ItemStack s : got) Dealings.give(p, s);
                Budget.forget(village);
                f.swing(InteractionHand.MAIN_HAND);
                return "Here's your " + name + ", fresh off the stand.";
            }
        }
        if (pot == null || day - o.day() >= ORDER_DAYS) {
            ordersOf(village).remove(p.getUUID());
            saveOrders(village);
            int back = Ledger.takeCoins(village, o.paid());
            Dealings.giveCoins(p, back);
            return "I'm sorry: the " + name + " never came right. Here's your " + back + coinsWord(back) + " back.";
        }
        return "Your " + name + " isn't off the stand yet. Come back in a while.";
    }

    /** The potion the brewer should brew next for a player's order: the oldest order the stores have none of. */
    @Nullable
    static Holder<Potion> orderedPotion(ServerLevel level, Villages.Village v) {
        Map<UUID, BrewOrder> orders = ordersOf(v.id());
        if (orders.isEmpty()) return null;
        BrewOrder oldest = null;
        Holder<Potion> best = null;
        for (BrewOrder o : orders.values()) {
            Holder<Potion> pot = potionOf(o.potion());
            if (pot == null || Market.stock(level, v.id(), s -> Crafts.isPotion(s, pot)) > 0) continue;
            if (oldest == null || o.day() < oldest.day()) { oldest = o; best = pot; }
        }
        return best;
    }

    /** Tests: the potion the brewer would brew next for an order. */
    @Nullable
    public static Holder<Potion> orderedPotionForTests(ServerLevel level, Villages.Village v) {
        return orderedPotion(level, v);
    }

    /** Has this player a potion order waiting in this town? */
    public static boolean hasPotionOrder(UUID village, UUID player) {
        return ordersOf(village).containsKey(player);
    }

    @Nullable
    static Holder<Potion> potionOf(String path) {
        ResourceLocation id = ResourceLocation.withDefaultNamespace(path);
        return BuiltInRegistries.POTION.getHolder(ResourceKey.create(Registries.POTION, id)).map(h -> (Holder<Potion>) h).orElse(null);
    }

    static Map<UUID, BrewOrder> ordersOf(UUID village) {
        return ORDERS.computeIfAbsent(village, k -> {
            Map<UUID, BrewOrder> m = new LinkedHashMap<>();
            String kept = Ledger.note(village, ORDERS_NOTE);
            if (kept != null && !kept.isEmpty()) {
                for (String row : kept.split(";")) {
                    String[] c = row.split(",");
                    if (c.length != 4) continue;
                    try {
                        UUID who = UUID.fromString(c[0]);
                        m.put(who, new BrewOrder(who, c[1], Long.parseLong(c[2]), Integer.parseInt(c[3])));
                    } catch (IllegalArgumentException e) {
                        // a spoilt row: dropped
                    }
                }
            }
            return java.util.Collections.synchronizedMap(m);
        });
    }

    private static void saveOrders(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (BrewOrder o : ordersOf(village).values()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(o.player()).append(',').append(o.potion()).append(',').append(o.day()).append(',').append(o.paid());
        }
        Ledger.note(village, ORDERS_NOTE, sb.toString());
    }

    // ------------------------------------------------------------------ the enchanter

    /** "Could you enchant this?": the thing in the player's hand, at the enchanter's table. */
    public static String enchant(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "I've no table to work at.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "I've no table to work at.";
        if (f.stationTask() != StationTask.ENCHANT) return sendOn(village, StationTask.ENCHANT, "Enchanting is the enchanter's work");
        return enchantHeld(level, v, f, p, p.getMainHandItem(), false);
    }

    /** The enchanter's table it works at: in its tower (the library till then), else by its post; null if none. */
    @Nullable
    static BlockPos tableOf(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        String house = CraftScale.houseOf(v.id(), StationTask.ENCHANT);
        BlockPos at = house == null ? null : Villages.builtAt(v.id(), house);
        BlockPos found = at == null ? null : Trades.find(level, at, 8, Blocks.ENCHANTING_TABLE);
        if (found == null && f.stationPos() != null) found = Trades.find(level, f.stationPos(), 8, Blocks.ENCHANTING_TABLE);
        return found;
    }

    /**
     * Enchant this thing of the player's (in its hand, or picked on the table's screen): the spell its table and hand
     * allow, the lapis and the book out of the stores at the market's price (else the player's own), and the fee a rank.
     * With {@code dryRun}, only what it would cost ("QUOTE|price|what"), nothing taken.
     */
    static String enchantHeld(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, ItemStack held, boolean dryRun) {
        UUID village = v.id();
        String refused = refused(level, village, p);
        if (refused != null) return refused;
        if (held.isEmpty()) return "Hand me what you'd have enchanted: a good tool, a blade, armour or a bow.";
        BlockPos table = tableOf(level, v, f);
        if (table == null) return "I've no enchanting table set up just now, so I can't.";
        if (!dryRun && f.distanceToSqr(table.getX() + 0.5, table.getY(), table.getZ() + 0.5) > AT_TABLE * AT_TABLE) {
            return "I can do that at my table. Come and find me there.";
        }
        int skill = f.veteranLevel();
        String thing = word(held);
        if (held.getCount() != 1 || !Craftsmanship.canEnchant(skill, held)) return "A " + thing + " is beyond my hand yet, I'm afraid.";
        int shelves = Crafts.shelvesRound(level, table);
        int tier = Crafts.tier(shelves, skill);
        var spell = Crafts.spellFor(held, tier, skill >= 30);
        var reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        if (spell.isEmpty()) return "There's nothing I can put on a " + thing + ". Iron, diamond or netherite tools, blades and armour, or a bow.";
        if (!Crafts.betters(held, spell, reg)) return "Your " + thing + " is as well enchanted as my table can make it already.";
        // The lapis and the book: what the stores hold first, then the player's own.
        Predicate<ItemStack> lapis = s -> s.is(Items.LAPIS_LAZULI) && s.getComponentsPatch().isEmpty();
        Predicate<ItemStack> book = s -> s.is(Items.BOOK) && s.getComponentsPatch().isEmpty();
        int lapisStores = Math.min(LAPIS, Market.stock(level, village, lapis));
        int lapisOwn = Math.min(LAPIS - lapisStores, Services.carried(p, lapis));
        int bookStores = Math.min(BOOKS, Market.stock(level, village, book));
        int bookOwn = Math.min(BOOKS - bookStores, Services.carried(p, book));
        if (lapisStores + lapisOwn < LAPIS) {
            return "It takes " + LAPIS + " lapis, and we've " + (lapisStores == 0 ? "none" : "only " + lapisStores) + " in the stores"
                + (lapisOwn > 0 ? " with your " + lapisOwn : "") + ". Bring lapis and I'll do it.";
        }
        if (bookStores + bookOwn < BOOKS) return "It takes a book to bind the spell with, and we've none in the stores, nor have you. Bring one.";
        double makings = PlayerServices.marketPrice(level, village, new ItemStack(Items.LAPIS_LAZULI)) * lapisStores
            + PlayerServices.marketPrice(level, village, new ItemStack(Items.BOOK)) * bookStores;
        long day = level.getDayTime() / 24000L;
        int price = Dealings.haggled(village, p.getUUID(), day, (int) Math.ceil(makings - 1e-9) + ENCHANT_FEE * tier);
        StringBuilder words = new StringBuilder();
        for (Map.Entry<ResourceKey<Enchantment>, Integer> e : spell) {
            var h = reg.getHolder(e.getKey());
            if (h.isEmpty()) continue;
            if (words.length() > 0) words.append(" and ");
            words.append(spellWords(e.getKey(), e.getValue()));
        }
        if (dryRun) return "QUOTE|" + price + "|" + price + coinsWord(price) + ": " + words
            + (lapisOwn + bookOwn > 0 ? " (some of the makings yours)" : "");
        if (Market.coinsHeld(p) < price) return words + " on your " + thing + " would be " + price + coinsWord(price)
            + ". You've " + Market.coinsHeld(p) + ".";
        // Take it all, then lay the spell on.
        int gotLapis = 0, gotBook = 0;
        for (ItemStack s : Services.take(level, village, lapis, lapisStores)) { gotLapis += s.getCount(); Economy.storesOut(village, s, s.getCount()); }
        for (ItemStack s : Services.take(level, village, book, bookStores)) { gotBook += s.getCount(); Economy.storesOut(village, s, s.getCount()); }
        if (gotLapis < lapisStores || gotBook < bookStores) {
            Crafts.giveBack(level, v, Items.LAPIS_LAZULI, gotLapis);
            Crafts.giveBack(level, v, Items.BOOK, gotBook);
            return "Somebody's just taken the last of the makings. Come back in a moment.";
        }
        if (lapisOwn > 0) Services.takeFrom(p, lapis, lapisOwn);
        if (bookOwn > 0) Services.takeFrom(p, book, bookOwn);
        for (Map.Entry<ResourceKey<Enchantment>, Integer> e : spell) reg.getHolder(e.getKey()).ifPresent(h -> held.enchant(h, e.getValue()));
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        Economy.sold(village, price);
        Budget.forget(village);
        f.swing(InteractionHand.MAIN_HAND);
        f.note(AssistantEntity.Deed.THINGS_MADE, 1);
        level.playSound(null, table, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        f.persona().remember(day, "I enchanted " + p.getName().getString() + "'s " + thing, 1);
        return "There: " + words + " on your " + thing + (shelves > 0 ? " (" + shelves + " bookshelves round the table)" : "")
            + ". " + price + coinsWord(price) + ", thank you.";
    }

    // ------------------------------------------------------------------ the beekeeper

    /** "Could I buy some honey?": honey, comb or candles from the stores; else a bottle filled at a full hive. */
    public static String honey(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Honey? Not from me.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "Honey? Not from me.";
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (f.stationTask() != StationTask.BEEKEEP) {
            if (Services.itemNamed(t) != null) return Services.stores(f, p, text);
            return sendOn(village, StationTask.BEEKEEP, "Honey's the beekeeper's");
        }
        String refused = refused(level, village, p);
        if (refused != null) return refused;
        Item it = t.contains("candle") ? Items.CANDLE : t.contains("comb") ? Items.HONEYCOMB : Items.HONEY_BOTTLE;
        int n = Math.min(HONEY_MOST, Services.countIn(t));
        ItemStack one = new ItemStack(it);
        Predicate<ItemStack> same = it == Items.CANDLE ? s -> s.is(ItemTags.CANDLES) : s -> s.is(it);
        String word = it == Items.CANDLE ? "candles" : word(one);
        int spare = Math.min(Market.stock(level, village, same), Budget.spare(level, village, one));
        if (spare > 0) {
            Sold s = sell(level, village, p, one, same, Math.min(n, spare));
            if (s.count() <= 0) return s.why();
            f.swing(InteractionHand.MAIN_HAND);
            return s.count() + " " + word + " from the stores: " + s.price() + coinsWord(s.price()) + ", thank you."
                + (s.count() < n ? " That's all we can spare." : "");
        }
        if (it == Items.HONEY_BOTTLE) {
            String filled = fromTheHive(level, v, f, p);
            if (filled != null) return filled;
        }
        return "We've no " + word + " to spare just now: " + (it == Items.CANDLE ? "the candles are dipped from the spare comb at the apiary."
            : "the hives aren't full. Come back in a day or two.");
    }

    /** A bottle of the player's (else the stores') filled at a full hive on the keeper's meadow, at the honey's price. */
    @Nullable
    private static String fromTheHive(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p) {
        WorkZone z = f.workZone();
        if (z == null || !Land.areaLoaded(level, z.center(), 8)) return null;
        BlockPos full = null;
        for (BlockPos h : Crafts.hivesAt(level, z.center(), Math.min(6, z.radius()))) {
            BlockState st = level.getBlockState(h);
            if (st.hasProperty(BeehiveBlock.HONEY_LEVEL) && st.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS) { full = h; break; }
        }
        if (full == null) return null;
        Predicate<ItemStack> glass = s -> s.is(Items.GLASS_BOTTLE);
        boolean own = Services.carried(p, glass) > 0;
        ItemStack honey = new ItemStack(Items.HONEY_BOTTLE);
        int price = priceOf(level, v.id(), p, honey, 1);
        if (own) price = Math.max(1, price - (int) Math.floor(PlayerServices.marketPrice(level, v.id(), new ItemStack(Items.GLASS_BOTTLE))));
        if (Market.coinsHeld(p) < price) return "A bottle of honey fresh from the hive is " + price + coinsWord(price) + ". You've " + Market.coinsHeld(p) + ".";
        if (own) Services.takeFrom(p, glass, 1);
        else if (!Crafts.bottle(level, v) || !Crafts.take(level, v, glass, 1)) return "There's honey in the hive, but no bottle to put it in. Bring one.";
        level.setBlock(full, level.getBlockState(full).setValue(BeehiveBlock.HONEY_LEVEL, 0), 3);
        level.playSound(null, full, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 0.8F, 1.0F);
        Dealings.give(p, honey);
        Market.payOut(p, price);
        Ledger.addCoins(v.id(), price);
        Economy.sold(v.id(), price);
        f.swing(InteractionHand.MAIN_HAND);
        return "None in the stores, but this hive's full: there, a bottle of honey fresh from it. " + price + coinsWord(price) + ", thank you.";
    }

    // ------------------------------------------------------------------ the till

    /** What a sale came to: how many changed hands, for how much, or why none did. */
    record Sold(int count, int price, String why) {}

    /** The town's asking price for so many, as the counter would have it today (haggled, never under the least). */
    static int priceOf(ServerLevel level, UUID village, Player p, ItemStack one, int n) {
        long day = level.getDayTime() / 24000L;
        int ask = Market.askLot(level, village, one, n);
        return Math.max(1, Math.max(Dealings.haggled(village, p.getUUID(), day, ask), Market.leastLot(level, village, one, n)));
    }

    /** Sell so many of a thing out of the stores to the player, at the town's price: taken, handed over, paid, booked. */
    static Sold sell(ServerLevel level, UUID village, Player p, ItemStack one, Predicate<ItemStack> what, int n) {
        int price = priceOf(level, village, p, one, n);
        int coins = Market.coinsHeld(p);
        if (coins < price) return new Sold(0, 0, n + " would be " + price + coinsWord(price) + ". You've " + coins + ".");
        List<ItemStack> got = Services.take(level, village, what, n);
        int count = 0;
        for (ItemStack s : got) { count += s.getCount(); Dealings.give(p, s); }
        if (count == 0) return new Sold(0, 0, "Somebody's just taken the last of it.");
        price = priceOf(level, village, p, one, count);
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        Economy.sold(village, price);
        Budget.forget(village);
        Stockroom.sold(level, village, Stockroom.Seller.STORES, one, count, price);
        return new Sold(count, price, "");
    }

    // ------------------------------------------------------------------ words

    /** An outcast or one banished is not served. */
    @Nullable
    private static String refused(ServerLevel level, UUID village, Player p) {
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(village, p.getUUID(), day)) return "Not for you. Not after what you've done.";
        return null;
    }

    /** "Potions are the brewer's work: ask Ash." (or "and we've no brewer just now."). */
    private static String sendOn(UUID village, StationTask trade, String lead) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == trade && f.isAlive() && !f.isBaby()) {
                return lead + ": ask " + f.displayNameCap() + ".";
            }
        }
        return lead + ", and we've no " + trade.title.toLowerCase(Locale.ROOT) + " just now.";
    }

    /** A thing as a folk says it: its own name if it was given one, else what it is ("iron sword"). */
    static String word(ItemStack s) {
        if (s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) return s.getHoverName().getString();
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace('_', ' ');
    }

    /** "Sharpness III": an enchantment and its rank, as the enchanter names it. */
    static String spellWords(ResourceKey<Enchantment> key, int rank) {
        String w = key.location().getPath().replace('_', ' ');
        String[] roman = { "", "I", "II", "III", "IV", "V" };
        return Character.toUpperCase(w.charAt(0)) + w.substring(1) + " " + (rank >= 1 && rank < roman.length ? roman[rank] : Integer.toString(rank));
    }

    private static String coinsWord(int n) {
        return n == 1 ? " coin" : " coins";
    }
}
