package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [emerald] The emerald trader: the town's go-between with the game's own villagers.
 *
 * <p>The player's words: "walks to nearby vanilla villages and trades the town's surplus with real villagers for
 * emeralds, then emeralds for enchanted books, bells and maps. They have to explore like scouts almost." The folk and the
 * villagers are two peoples, kept apart (TwoPeoples); this one folk crosses between them, and only to trade.
 *
 * <ul>
 * <li><b>When the trade opens.</b> From the Stone Age, in a town of {@link #FROM} or more that knows of a village of
 *     villagers within {@link #REACH} blocks (its scouts found it and it is in their atlas, the trader found it, or it lies
 *     plainly on the land round the town for the trader to find) and has goods to spare. One trader; two once it trades
 *     with several villages. The town picks the hand for it: a sociable, cheerful or curious one, not the watch's, a
 *     craft's or a carrier's (appoint). It works from the Trading Post (blueprints/tradingpost.txt) once that stands.</li>
 * <li><b>Exploring like a scout.</b> With no village to go to, it goes looking: the way the scouts' atlas knows least,
 *     marking the land it walks as explored, and writing every village it sees into the atlas and its own book.</li>
 * <li><b>The trip.</b> In the morning it chooses where to go (where somebody sells what the town wants, where somebody
 *     buys what it can spare, nearest first), draws the goods and the emeralds from the stores, takes a pack donkey from
 *     the stable if the town has one with a chest (Riding.packFor), and walks there as a caravan does, the ground round
 *     it kept awake. It turns for home in the afternoon wherever it has got to.</li>
 * <li><b>Real trading.</b> At the village it reads every villager's own offers. It sells only what it brought (what the
 *     stores could spare: Budget.spare, the town's own surplus and glut rules) to whichever villager buys it, and buys
 *     what the town wants with the emeralds. Each trade is the villager's offer as it stands, price and all (with its
 *     demand), paid in full out of the pack; the offer is used up as a player's is, the villager gets its trade
 *     experience and goes up a level when it has earned it (AbstractVillager.notifyTrade, called with no player trading:
 *     every step of it is safe without one). Never with a villager a player is trading with; never at a village while a
 *     player is at one of its stalls; never an offer that is used up; never anything for nothing.</li>
 * <li><b>What it buys, by need</b> (wants): a player's ask first ("find us a Mending book"); Mending for the town's best
 *     tools; a bell for a town with none; Efficiency and Protection; the cleric's lapis for the enchanter; an explorer
 *     map for the scouts; diamond arms for the watch; a saddle for the stable. It learns who sells what and goes back.</li>
 * <li><b>Home.</b> The emeralds and what they bought go into the stores; the enchanter or the smith lays a bought book
 *     on the town's best tool (layBook). The emerald account (earned, spent, held), the villages, their villagers and
 *     their offers are on the Trading Post page of the town's books.</li>
 * <li><b>Safety.</b> A village under a raid is left alone: the trader turns for home and tells the town. The villagers
 *     are never harmed and never taken: the trader buys and sells, and that is all.</li>
 * </ul>
 */
public final class EmeraldTrader {

    private EmeraldTrader() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A town needs this many folk before it keeps a trader. */
    public static final int FROM = 12;
    /** The most traders a town keeps. */
    public static final int MOST = 2;
    /** How far a village of villagers may be for the trader to walk to it: a morning's walk. */
    public static final int REACH = 640;
    /** How far it goes looking for one. */
    static final int EXPLORE = 448;
    /** How far off it sees a village from the road. */
    static final int SIGHT = 96;
    /** Ticks between one trade and the next at a villager's stall. */
    static final int TRADE_EVERY = 20;
    /** A village found under a raid is left alone this long. */
    static final long RAIDED_FOR = 2 * 24000L;
    /** How long it waits at a village while a player is at a villager's stall, before it goes home. */
    static final int WAIT_FOR_PLAYER = 600;
    /** The book keeps so many villages, villagers a village, offers a villager, lines of the log. */
    static final int KEEP_HAMLETS = 10, KEEP_SELLERS = 16, KEEP_DEALS = 12, KEEP_LOG = 16;
    /** The trader's building. */
    public static final String POST = "tradingpost";
    /** How often the town reckons up whether it wants a trader (ticks), and how often the towns are looked over. */
    static final long RECKON_EVERY = 600L;
    static final int EVERY = 200;

    /** What the game's villagers buy, as the game has them buying it: the farmer wheat, potatoes, carrots, beetroot,
     *  pumpkins and melons; the fisherman string, coal and fish; the shepherd wool; the fletcher sticks and flint; the
     *  librarian paper; the cleric rotten flesh and gold; the smiths coal and iron; the butcher raw meat; the mason clay;
     *  the cartographer paper and glass panes; the leatherworker leather. What it takes on a first trip, before it knows a
     *  village's own offers; and all it ever sells. */
    static final Item[] BOUGHT = { Items.WHEAT, Items.POTATO, Items.CARROT, Items.BEETROOT, Items.PUMPKIN, Items.MELON,
        Items.STRING, Items.COAL, Items.COD, Items.SALMON, Items.WHITE_WOOL, Items.BROWN_WOOL, Items.BLACK_WOOL, Items.GRAY_WOOL,
        Items.STICK, Items.FLINT, Items.PAPER, Items.ROTTEN_FLESH, Items.GOLD_INGOT, Items.IRON_INGOT, Items.CHICKEN,
        Items.PORKCHOP, Items.RABBIT, Items.MUTTON, Items.BEEF, Items.CLAY_BALL, Items.GLASS_PANE, Items.LEATHER };
    private static final Set<Item> BOUGHT_SET = new HashSet<>(List.of(BOUGHT));

    /** Is this something the villagers buy (and so something the trader might carry out to sell)? */
    public static boolean sellable(ItemStack s) {
        return !s.isEmpty() && BOUGHT_SET.contains(s.getItem()) && !s.isEnchanted() && s.get(DataComponents.CUSTOM_NAME) == null;
    }

    /** Whether each town wants traders (0, 1 or 2), as last reckoned; when it last reckoned. */
    private static final Map<UUID, Integer> WANTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RECKONED = new ConcurrentHashMap<>();
    /** The day each trader last set out. */
    private static final Map<UUID, Long> WENT = new ConcurrentHashMap<>();
    /** The day each town last had a bought book laid on a tool, and when its stores were last looked over for one. */
    private static final Map<UUID, Long> LAID = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> BOOK_LOOK = new ConcurrentHashMap<>();
    /** The towns whose land has been looked over this session for villages of villagers as far as the trader explores. */
    private static final Set<UUID> FAR_LOOKED = ConcurrentHashMap.newKeySet();
    /** Tests: trade at once, with no pause between one trade and the next. */
    private static volatile boolean quickForTests;

    public static void resetForTests() {
        WANTED.clear();
        RECKONED.clear();
        WENT.clear();
        LAID.clear();
        BOOK_LOOK.clear();
        FAR_LOOKED.clear();
        BOOKS.clear();
        quickForTests = false;
        VanillaVillages.resetForTests();
        TwoPeoples.resetForTests();
    }

    // ================================================================== the trader's book

    /** A village of villagers, as the trader's book has it. */
    public static final class Hamlet {
        String key = "", name = "", kind = "", by = "";
        BlockPos centre = BlockPos.ZERO;
        int x0, z0, x1, z1;
        long found = -1, visited = -1, raided = -1;
        int trips, earned, spent;
        final List<Seller> folk = new ArrayList<>();

        public String key() { return key; }
        public String name() { return name; }
        public BlockPos centre() { return centre; }
        public List<Seller> sellers() { return folk; }
        public long raided() { return raided; }
        public long visited() { return visited; }
        public int trips() { return trips; }

        boolean within(int x, int z, int margin) {
            return x >= x0 - margin && x <= x1 + margin && z >= z0 - margin && z <= z1 + margin;
        }

        /** "Brookby, a plains village". */
        String words() {
            return name + (kind.isEmpty() ? "" : ", " + JobMarket.a(kind) + " village");
        }

        @Nullable
        Seller seller(UUID id) {
            for (Seller s : folk) if (s.id.equals(id)) return s;
            return null;
        }
    }

    /** One villager, as the book has it: who, what trade and level, and its offers when last seen. */
    public static final class Seller {
        UUID id = new UUID(0, 0);
        String name = "", trade = "none";
        int level = 1, xp;
        long seen = -1;
        int dealt;
        final List<Deal> deals = new ArrayList<>();

        public String name() { return name; }
        public String trade() { return trade; }
        public int level() { return level; }
        public List<Deal> deals() { return deals; }

        /** "Ashby the librarian, journeyman". */
        String words() {
            return name + " the " + trade + ", " + LEVELS[Math.max(1, Math.min(5, level)) - 1].toLowerCase(Locale.ROOT);
        }
    }

    /** One offer a villager makes, as last seen: what it asks (and the second thing, if any), what it gives, what it is
     *  called (an enchanted book's enchantment, a map's name), its enchantments' ids, how used it is, and its trade XP. */
    public record Deal(String cost, int costN, String cost2, int cost2N, String result, int resultN, String label, String ench,
                       int uses, int max, int xp) {
        boolean out() { return uses >= max; }

        /** Does the villager buy here (pay emeralds), or sell? */
        boolean buys() { return result.equals("minecraft:emerald"); }
    }

    static final String[] LEVELS = { "Novice", "Apprentice", "Journeyman", "Expert", "Master" };

    /** Each town's book as last read, with the text it was read from: read again only when the text has changed. The
     *  trader asks after its book every few ticks on the road, and a book of ten villages is a few thousand letters. */
    private static final Map<UUID, Object[]> BOOKS = new ConcurrentHashMap<>();

    /** The town's book: every village of villagers it knows, oldest first. */
    @SuppressWarnings("unchecked")
    public static List<Hamlet> book(UUID village) {
        String text = Ledger.note(village, "emerald/book");
        Object[] had = BOOKS.get(village);
        if (had != null && had[0] == text) return (List<Hamlet>) had[1];
        List<Hamlet> book = decode(text);
        BOOKS.put(village, new Object[]{ text, book });
        return book;
    }

    static void save(UUID village, List<Hamlet> book) {
        while (book.size() > KEEP_HAMLETS) {
            // Forget the one least used: never traded with, then the furthest-ago visited.
            Hamlet worst = book.get(0);
            for (Hamlet h : book) if (h.trips < worst.trips || h.trips == worst.trips && h.visited < worst.visited) worst = h;
            book.remove(worst);
        }
        Ledger.note(village, "emerald/book", encode(book));
        BOOKS.put(village, new Object[]{ Ledger.note(village, "emerald/book"), book });
    }

    @Nullable
    static Hamlet find(List<Hamlet> book, String key) {
        for (Hamlet h : book) if (h.key.equals(key)) return h;
        return null;
    }

    static String encode(List<Hamlet> book) {
        StringBuilder sb = new StringBuilder();
        for (Hamlet h : book) {
            line(sb, "H", h.key, h.name, h.kind, h.centre.getX(), h.centre.getY(), h.centre.getZ(), h.x0, h.z0, h.x1, h.z1,
                h.found, h.by, h.visited, h.raided, h.trips, h.earned, h.spent);
            for (Seller s : h.folk) {
                line(sb, "S", s.id, s.name, s.trade, s.level, s.xp, s.seen, s.dealt);
                for (Deal d : s.deals) {
                    line(sb, "D", d.cost(), d.costN(), d.cost2(), d.cost2N(), d.result(), d.resultN(), d.label(), d.ench(), d.uses(), d.max(), d.xp());
                }
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, Object... parts) {
        if (sb.length() > 0) sb.append('\n');
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(String.valueOf(parts[i]).replace('|', '/').replace('\n', ' '));
        }
    }

    static List<Hamlet> decode(@Nullable String text) {
        List<Hamlet> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Hamlet h = null;
        Seller s = null;
        for (String line : text.split("\n")) {
            String[] p = line.split("\\|", -1);
            try {
                switch (p[0]) {
                    case "H" -> {
                        h = new Hamlet();
                        h.key = p[1];
                        h.name = p[2];
                        h.kind = p[3];
                        h.centre = new BlockPos(Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
                        h.x0 = Integer.parseInt(p[7]);
                        h.z0 = Integer.parseInt(p[8]);
                        h.x1 = Integer.parseInt(p[9]);
                        h.z1 = Integer.parseInt(p[10]);
                        h.found = Long.parseLong(p[11]);
                        h.by = p[12];
                        h.visited = Long.parseLong(p[13]);
                        h.raided = Long.parseLong(p[14]);
                        h.trips = Integer.parseInt(p[15]);
                        h.earned = Integer.parseInt(p[16]);
                        h.spent = Integer.parseInt(p[17]);
                        out.add(h);
                        s = null;
                    }
                    case "S" -> {
                        if (h == null) continue;
                        s = new Seller();
                        s.id = UUID.fromString(p[1]);
                        s.name = p[2];
                        s.trade = p[3];
                        s.level = Integer.parseInt(p[4]);
                        s.xp = Integer.parseInt(p[5]);
                        s.seen = Long.parseLong(p[6]);
                        s.dealt = Integer.parseInt(p[7]);
                        h.folk.add(s);
                    }
                    case "D" -> {
                        if (s == null) continue;
                        s.deals.add(new Deal(p[1], Integer.parseInt(p[2]), p[3], Integer.parseInt(p[4]), p[5], Integer.parseInt(p[6]),
                            p[7], p[8], Integer.parseInt(p[9]), Integer.parseInt(p[10]), Integer.parseInt(p[11])));
                    }
                    default -> { }
                }
            } catch (RuntimeException e) {
                // A line the book cannot read is skipped, not the whole book.
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the account and the log

    static int number(UUID village, String key) {
        String s = Ledger.note(village, "emerald/" + key);
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void add(UUID village, String key, int n) {
        if (n != 0) Ledger.note(village, "emerald/" + key, Integer.toString(number(village, key) + n));
    }

    /** The emeralds it has brought home for the town's goods, all told. */
    public static int earned(UUID village) { return number(village, "earned"); }
    /** The emeralds it has spent on what the town wanted, all told. */
    public static int spent(UUID village) { return number(village, "spent"); }
    /** Its trips, all told. */
    public static int trips(UUID village) { return number(village, "trips"); }

    /** What it has brought home lately, newest last: "day 12: from Brookby, 9 emeralds for 60 wheat; a Mending book". */
    public static List<String> log(UUID village) {
        String s = Ledger.note(village, "emerald/log");
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String l : s.split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    static void log(UUID village, long day, String text) {
        List<String> all = log(village);
        all.add("Day " + (day + 1) + ": " + text);
        while (all.size() > KEEP_LOG) all.remove(0);
        Ledger.note(village, "emerald/log", String.join("\n", all));
    }

    /** What players have asked the trader to buy, by want key. */
    public static List<String> asks(UUID village) {
        String s = Ledger.note(village, "emerald/asks");
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String a : s.split(",")) if (!a.isEmpty()) out.add(a);
        return out;
    }

    static void asks(UUID village, List<String> keys) {
        Ledger.note(village, "emerald/asks", String.join(",", keys));
    }

    // ------------------------------------------------------------------ names

    private static final String[] GIVEN = { "Ada", "Alder", "Ashby", "Bertram", "Bram", "Cedric", "Clem", "Dora", "Edith",
        "Elsie", "Fenn", "Godric", "Hettie", "Ida", "Jory", "Kit", "Linnet", "Mabel", "Nell", "Osric", "Perrin", "Quenby",
        "Rook", "Sefton", "Tamsin", "Ulric", "Vera", "Wren", "Yarrow", "Zeb", "Agnes", "Barnaby", "Corin", "Dunstan" };
    private static final String[] FRONT = { "Ash", "Brook", "Elm", "Fern", "Holl", "Mill", "Oak", "Rush", "Stone", "Thorn",
        "Willow", "Bram", "Cress", "Hazel", "Marsh", "Pen", "Wick", "Sedge", "Larch", "Barley" };
    private static final String[] BACK = { "by", "ford", "ham", "ley", "stead", "wick", "combe", "thorpe", "well", "bury",
        "field", "dale" };

    /** A villager's name in the book (the trader's own for it: the game gives villagers none). Always the same for one. */
    static String villagerName(UUID id) {
        return GIVEN[Math.floorMod(id.hashCode(), GIVEN.length)];
    }

    /** A village of villagers' name in the book: always the same for one. */
    static String hamletName(String key) {
        int h = key.hashCode();
        return FRONT[Math.floorMod(h, FRONT.length)] + BACK[Math.floorMod(h / 31, BACK.length)];
    }

    /** "the librarian", from the game's profession. */
    static String tradeOf(Villager v) {
        VillagerProfession p = v.getVillagerData().getProfession();
        return p.name().replace('_', ' ');
    }

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    /** "20 wheat", "an emerald", "a book". */
    static String count(String itemId, int n) {
        Item it = item(itemId);
        String name = new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT);
        if (n == 1) return JobMarket.a(name);
        return n + " " + Bench.plural(name);
    }

    // ================================================================== reading a villager

    /** A villager's offers, read into the book's terms (the price as it stands now, with its demand). */
    static List<Deal> deals(Villager v) {
        List<Deal> out = new ArrayList<>();
        MerchantOffers offers = v.getOffers();
        for (int i = 0; i < offers.size() && out.size() < KEEP_DEALS; i++) {
            MerchantOffer o = offers.get(i);
            ItemStack a = o.getCostA(), b = o.getCostB(), r = o.getResult();
            out.add(new Deal(id(a.getItem()), a.getCount(), b.isEmpty() ? "" : id(b.getItem()), b.getCount(), id(r.getItem()),
                r.getCount(), label(r), enchIds(r), o.getUses(), o.getMaxUses(), o.getXp()));
        }
        return out;
    }

    /** What an offer's result is called: "Mending", "Efficiency III", "Woodland Explorer Map", "Bell". */
    static String label(ItemStack r) {
        ItemEnchantments e = r.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (e.isEmpty()) e = r.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (!e.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (var en : e.entrySet()) names.add(Enchantment.getFullname(en.getKey(), en.getIntValue()).getString());
            return String.join(", ", names) + (r.is(Items.ENCHANTED_BOOK) ? "" : " " + r.getHoverName().getString());
        }
        return r.getHoverName().getString();
    }

    static String enchIds(ItemStack r) {
        ItemEnchantments e = r.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (e.isEmpty()) e = r.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        List<String> ids = new ArrayList<>();
        for (Holder<Enchantment> h : e.keySet()) h.unwrapKey().ifPresent(k -> ids.add(k.location().toString()));
        return String.join(",", ids);
    }

    /** "sells Mending for 24 emeralds and a book (3 of 12 left)" / "buys 20 wheat for an emerald (sold out: it restocks at its
     *  workstation)". */
    public static String dealWords(Deal d) {
        String left = d.out() ? " (used up: it restocks at its workstation)" : " (" + (d.max() - d.uses()) + " of " + d.max() + " left)";
        String cost = count(d.cost(), d.costN()) + (d.cost2().isEmpty() ? "" : " and " + count(d.cost2(), d.cost2N()));
        if (d.buys()) return "buys " + cost + " for " + count(d.result(), d.resultN()) + left;
        String what = d.result().equals("minecraft:enchanted_book") ? d.label() + " (a book)" : d.resultN() > 1 ? count(d.result(), d.resultN()) : d.label();
        return "sells " + what + " for " + cost + left;
    }

    /** One villager into the book: who, its trade and level, its offers as they stand. Returns the book's seller. */
    static Seller see(Hamlet h, Villager v, long day) {
        Seller s = h.seller(v.getUUID());
        if (s == null) {
            s = new Seller();
            s.id = v.getUUID();
            s.name = villagerName(v.getUUID());
            h.folk.add(s);
            while (h.folk.size() > KEEP_SELLERS) {
                Seller worst = h.folk.get(0);
                for (Seller o : h.folk) if (o.dealt < worst.dealt || o.dealt == worst.dealt && o.seen < worst.seen) worst = o;
                h.folk.remove(worst);
            }
        }
        s.trade = tradeOf(v);
        s.level = v.getVillagerData().getLevel();
        s.xp = v.getVillagerXp();
        s.seen = day;
        s.deals.clear();
        s.deals.addAll(deals(v));
        return s;
    }

    /** Does this villager trade at all (a trade, grown, not the nitwit)? */
    static boolean trades(Villager v) {
        VillagerProfession p = v.getVillagerData().getProfession();
        return v.isAlive() && !v.isBaby() && p != VillagerProfession.NONE && p != VillagerProfession.NITWIT;
    }

    /** The villagers of a village of villagers, as far as they are about. */
    static List<Villager> villagersOf(ServerLevel level, Hamlet h) {
        AABB box = new AABB(h.x0 - 12, h.centre.getY() - 24, h.z0 - 12, h.x1 + 13, h.centre.getY() + 24, h.z1 + 13);
        return level.getEntitiesOfClass(Villager.class, box, EmeraldTrader::trades);
    }

    // ================================================================== what the town wants

    /** One thing on the buying list: what, in words, how many, the most it pays each (in emeralds), a player's ask? */
    public record Want(String key, String words, Item item, @Nullable ResourceKey<Enchantment> ench, int count, int most, boolean asked) {
        Want(String key, String words, Item item, int count, int most, boolean asked) {
            this(key, words, item, null, count, most, asked);
        }

        boolean matches(ItemStack s) {
            return s.is(item) && (ench == null || stored(s, ench) > 0);
        }

        boolean matches(Deal d) {
            return d.result().equals(id(item)) && (ench == null || d.ench().contains(ench.location().toString()));
        }
    }

    /** The level of this enchantment stored in an enchanted book (or on a thing), nought if none. */
    static int stored(ItemStack s, ResourceKey<Enchantment> ench) {
        for (ItemEnchantments e : new ItemEnchantments[]{ s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY),
                s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY) }) {
            for (var en : e.entrySet()) if (en.getKey().is(ench)) return en.getIntValue();
        }
        return 0;
    }

    static Want book(ResourceKey<Enchantment> ench, String name, int most, boolean asked) {
        return new Want("book:" + ench.location(), JobMarket.a(name) + " book", Items.ENCHANTED_BOOK, ench, 1, most, asked);
    }

    /** What the town wants the trader to buy, most wanted first, from its real wants: a player's ask; Mending for its best
     *  tools; a bell for a town with none; Efficiency and Protection from the Iron Age; the cleric's lapis while the
     *  enchanter is short; an explorer map for the scouts; diamond arms for the watch; a saddle for the stable. */
    public static List<Want> wants(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Want> out = new ArrayList<>();
        for (String a : asks(id)) {
            Want w = wantFor(level, a, true);
            if (w != null) out.add(w);
        }
        Villages.Age age = Villages.ageOf(id);
        if (books(level, id, Enchantments.MENDING) < 1) out.add(book(Enchantments.MENDING, "Mending", 64, false));
        if (Market.stock(level, id, s -> s.is(Items.BELL)) == 0 && TownBell.bellAt(level, v) == null) {
            out.add(new Want("bell", "a bell for the town", Items.BELL, 1, 48, false));
        }
        if (age.ordinal() >= Villages.Age.IRON.ordinal()) {
            if (books(level, id, Enchantments.EFFICIENCY) < 1) out.add(book(Enchantments.EFFICIENCY, "Efficiency", 40, false));
            if (books(level, id, Enchantments.PROTECTION) < 1) out.add(book(Enchantments.PROTECTION, "Protection", 40, false));
        }
        boolean enchanter = false, scouts = false;
        int guards = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.stationTask() == StationTask.ENCHANT) enchanter = true;
            if (a.stationTask() == StationTask.SCOUT) scouts = true;
            if (a.stationTask() == StationTask.GUARD) guards++;
        }
        if (enchanter && Market.stock(level, id, s -> s.is(Items.LAPIS_LAZULI)) < 16) {
            out.add(new Want("lapis", "lapis for the enchanter", Items.LAPIS_LAZULI, 8, 2, false));
        }
        if (scouts && Market.stock(level, id, s -> s.is(Items.FILLED_MAP)) == 0) {
            boolean known = false;
            for (Scouts.Find f : Scouts.atlas(id)) {
                if (f.label().contains("mansion") || f.label().contains("monument")) { known = true; break; }
            }
            if (!known) out.add(new Want("map", "an explorer map for the scouts", Items.FILLED_MAP, 1, 24, false));
        }
        if (age.ordinal() >= Villages.Age.IRON.ordinal() && guards >= 2) {
            if (Market.stock(level, id, s -> s.is(Items.DIAMOND_SWORD)) == 0) out.add(new Want("diamond_sword", "a diamond sword for the watch", Items.DIAMOND_SWORD, 1, 30, false));
            if (Market.stock(level, id, s -> s.is(Items.DIAMOND_CHESTPLATE)) == 0) out.add(new Want("diamond_chestplate", "a diamond chestplate for the watch", Items.DIAMOND_CHESTPLATE, 1, 40, false));
        }
        if (Villages.hasBuilt(id, "stable") && Market.stock(level, id, s -> s.is(Items.SADDLE)) == 0) {
            out.add(new Want("saddle", "a saddle for the stable", Items.SADDLE, 1, 8, false));
        }
        return out;
    }

    /** How many enchanted books with this on them the stores hold. */
    static int books(ServerLevel level, UUID id, ResourceKey<Enchantment> ench) {
        return Market.stock(level, id, s -> s.is(Items.ENCHANTED_BOOK) && stored(s, ench) > 0);
    }

    /** Things a player may ask it for, by name. */
    private static final Item[] ASKABLE = { Items.BELL, Items.SADDLE, Items.NAME_TAG, Items.ENDER_PEARL, Items.GLOWSTONE,
        Items.REDSTONE, Items.LAPIS_LAZULI, Items.EXPERIENCE_BOTTLE, Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
        Items.DIAMOND_SHOVEL, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
        Items.BOOKSHELF, Items.LANTERN, Items.GLOW_INK_SAC, Items.CLOCK, Items.COMPASS, Items.SPECTRAL_ARROW, Items.CROSSBOW,
        Items.BOW, Items.SHIELD, Items.CHAINMAIL_CHESTPLATE, Items.CAMPFIRE };

    /** A want from an ask's key ("book:minecraft:mending", "item:minecraft:bell", "map"), or null. */
    @Nullable
    static Want wantFor(ServerLevel level, String key, boolean asked) {
        if (key.startsWith("book:")) {
            ResourceLocation rl = ResourceLocation.tryParse(key.substring(5));
            if (rl == null) return null;
            ResourceKey<Enchantment> ek = ResourceKey.create(Registries.ENCHANTMENT, rl);
            var h = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolder(ek);
            if (h.isEmpty()) return null;
            String name = Enchantment.getFullname(h.get(), 1).getString().replaceAll(" I$", "");
            return book(ek, name, 64, asked);
        }
        if (key.equals("map")) return new Want("map", "an explorer map", Items.FILLED_MAP, 1, 30, asked);
        if (key.startsWith("item:")) {
            Item it = item(key.substring(5));
            if (it == Items.AIR) return null;
            String name = new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT);
            return new Want(key, JobMarket.a(name), it, null, it == Items.LAPIS_LAZULI || it == Items.REDSTONE || it == Items.GLOWSTONE ? 8 : 1,
                64, asked);
        }
        return null;
    }

    /** What a player's words ask it to buy: an enchantment by name, a map, or one of the things the villagers sell. */
    @Nullable
    static String askedFor(ServerLevel level, String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replace('-', ' ') + " ";
        var reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        String best = null;
        int bestLen = 0;
        for (var e : reg.entrySet()) {
            String words = e.getKey().location().getPath().replace('_', ' ');
            if (t.contains(" " + words + " ") || t.contains(" " + words + "?") || t.contains(" " + words + ".") || t.contains(" " + words + ",")) {
                if (words.length() > bestLen) { bestLen = words.length(); best = "book:" + e.getKey().location(); }
            }
        }
        if (best != null) return best;
        if (t.contains(" map")) return "map";
        for (Item it : ASKABLE) {
            String name = new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT);
            if (t.contains(" " + name)) return "item:" + id(it);
        }
        if (t.contains(" lapis")) return "item:" + id(Items.LAPIS_LAZULI);
        if (t.contains(" pearl")) return "item:" + id(Items.ENDER_PEARL);
        return null;
    }

    // ================================================================== the town's day

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 133) return;
        Guard.run("emerald trader", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) tick(level, v);
            }
        });
    }

    /** Every ten seconds for each town: whether it wants a trader, and one taken on if it does and has none. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = RECKONED.get(id);
        if (last == null || now - last >= RECKON_EVERY || now < last) {
            RECKONED.put(id, now);
            WANTED.put(id, reckon(level, v));
        }
        int want = WANTED.getOrDefault(id, 0);
        if (want > 0 && traders(id, true).size() < want) {
            // [interviews] Two or more who want the place: it is held open for its interview (Interviews), and given
            // after it to the panel's choice (candidates puts it first).
            List<VillageFolkEntity> few = candidates(v);
            if (few.isEmpty() || !Interviews.vacancy(level, id, POST_KEY, few.get(0))) appoint(level, v);
        }
    }

    /** How many traders the town wants (0, 1 or 2), as last reckoned. Villages' share of the trades asks it. */
    public static double traders(@Nullable UUID village) {
        return village == null ? 0.0 : WANTED.getOrDefault(village, 0);
    }

    /** Does the town want a trader at all (Villages.craftReady)? */
    public static boolean wanted(@Nullable UUID village) {
        return traders(village) > 0;
    }

    /**
     * Whether, and how many: from the Stone Age, a town of {@link #FROM} that knows of a village of villagers within
     * {@link #REACH} (or has one plainly on its land for the trader to find) and has goods to spare (or emeralds to spend
     * on what it wants). Two once it has traded with two villages and is thirty strong.
     */
    static int reckon(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!VanillaVillages.apart()) return 0;                   // the takeover is on: there are no villagers to trade with
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal() || Villages.headcount(id) < FROM) return 0;
        learnFromTheAtlas(level, v);
        int near = 0, traded = 0;
        for (Hamlet h : book(id)) {
            if (Scouts.flat(h.centre, v.centre()) > (double) REACH * REACH) continue;
            near++;
            if (h.trips > 0) traded++;
        }
        // None in the book: is there one plainly on the land round the town for the trader to find? The world's villages
        // that far round looked up once a session (VanillaVillages caches what it has looked at, region by region).
        if (near == 0 && FAR_LOOKED.add(id)) VanillaVillages.lookAround(level, v.centre(), EXPLORE);
        boolean toFind = near == 0 && VanillaVillages.nearest(level, v.centre(), REACH) != null;
        if (near == 0 && !toFind) return 0;
        boolean goods = !spareGoods(level, id).isEmpty()
            || Market.stock(level, id, s -> s.is(Items.EMERALD)) >= 8 && !wants(level, v).isEmpty();
        if (!goods) return 0;
        return traded >= 2 && Villages.headcount(id) >= 30 ? 2 : 1;
    }

    /** What the town can spare of what the villagers buy: item, how many (Budget.spare: its own needs first). */
    static Map<Item, Integer> spareGoods(ServerLevel level, UUID id) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Item it : BOUGHT) {
            int n = Budget.spare(level, id, new ItemStack(it));
            if (n >= 4) out.put(it, n);
        }
        return out;
    }

    /** The villages of villagers in the scouts' atlas, into the trader's book (each once). */
    static void learnFromTheAtlas(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Hamlet> book = book(id);
        boolean changed = false;
        for (Scouts.Find f : Scouts.atlas(id)) {
            if (f.kind() != Scouts.Kind.SETTLEMENT) continue;
            boolean have = false;
            for (Hamlet h : book) if (h.within(f.at().getX(), f.at().getZ(), 48)) { have = true; break; }
            if (have) continue;
            VanillaVillages.Known k = VanillaVillages.nearest(level, f.at(), 48);
            Hamlet h = k != null ? hamlet(k, f.day(), f.by()) : hamlet("atlas@" + f.at().getX() + "," + f.at().getZ(), f.at(), 48, "", f.day(), f.by());
            book.add(h);
            changed = true;
        }
        if (changed) save(id, book);
    }

    static Hamlet hamlet(VanillaVillages.Known k, long day, String by) {
        Hamlet h = new Hamlet();
        h.key = k.key();
        h.name = hamletName(k.key());
        h.kind = k.kind();
        h.centre = k.centre();
        h.x0 = k.x0();
        h.z0 = k.z0();
        h.x1 = k.x1();
        h.z1 = k.z1();
        h.found = day;
        h.by = by;
        return h;
    }

    static Hamlet hamlet(String key, BlockPos centre, int half, String kind, long day, String by) {
        Hamlet h = new Hamlet();
        h.key = key;
        h.name = hamletName(key);
        h.kind = kind;
        h.centre = centre.immutable();
        h.x0 = centre.getX() - half;
        h.z0 = centre.getZ() - half;
        h.x1 = centre.getX() + half;
        h.z1 = centre.getZ() + half;
        h.found = day;
        h.by = by;
        return h;
    }

    /** The town's traders (those at the trade; with {@code out}, those out on the road too). */
    static List<VillageFolkEntity> traders(UUID village, boolean out) {
        List<VillageFolkEntity> list = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && (f.stationTask() == StationTask.EMERALD
                    || out && f.expedition() != null && f.expedition().venture() != null)) list.add(f);
        }
        return list;
    }

    /**
     * Who may be the town's trader, best first: grown and fit for the road (eighteen to sixty-five), not the watch's,
     * a craft's, the storehouse's, a carrier's, the cave team's, the bank's, the ferry's or a scout's, from a trade with a
     * hand to spare or none; the sociable, the cheerful and the curious before the shy and the grumpy, and one who has
     * traded before first of all.
     *
     * <p>[interviews] A town that holds interviews for the place (Interviews, InterviewPosts' "trader") holds it open while
     * two or more want it, and the panel's choice comes first here once it has chosen.
     */
    public static List<VillageFolkEntity> candidates(Villages.Village v) {
        UUID id = v.id();
        List<VillageFolkEntity> out = new ArrayList<>();
        Map<VillageFolkEntity, Integer> score = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() == StationTask.EMERALD) continue;
            int s = fitness(f, id);
            if (s == Integer.MIN_VALUE) continue;
            score.put(f, s + Interviews.preferred(id, POST_KEY, f));          // [interviews] the panel's choice first
            out.add(f);
        }
        out.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        return out;
    }

    /** The trader's place, in the interviews' books (InterviewPosts). */
    static final String POST_KEY = "trader";

    /**
     * How well this folk would do as the town's trader, by the town's own reckoning (candidates, and the interview's
     * paper); Integer.MIN_VALUE for one who may not be it: a child, a stand-in, a hired hand, one away, one outside
     * eighteen to sixty-five, the leader, the watch, a craft, the storekeeper, a carrier, the cave team, the bank, the
     * ferry, a scout, or one whose own trade cannot spare it.
     */
    static int fitness(VillageFolkEntity f, UUID id) {
        if (f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive()) return Integer.MIN_VALUE;
        if (f.trip() != null || f.expedition() != null) return Integer.MIN_VALUE;
        StationTask t = f.stationTask();
        if (t.isCraft() || t == StationTask.GUARD || t == StationTask.STORE || t == StationTask.HAUL || t == StationTask.CAVE || t == StationTask.NETHER   // [nether]
            || t == StationTask.BANK || t == StationTask.FERRY || t == StationTask.SCOUT) return Integer.MIN_VALUE;
        if (t != StationTask.NONE && t != StationTask.EMERALD && Villages.share(id, t) < 0.5) return Integer.MIN_VALUE;
        int years = f.ageYears();
        if (years < 18 || years > 65) return Integer.MIN_VALUE;
        if (Villages.holdsTheLead(id, f.getUUID(), f.level().getGameTime())) return Integer.MIN_VALUE;
        return f.tradeLevel(StationTask.EMERALD) * 6 + f.tradeLevel(StationTask.SCOUT) * 2
            + (t == StationTask.NONE ? 25 : 0)
            + (f.life().has(Social.Trait.SOCIABLE) ? 14 : 0) + (f.life().has(Social.Trait.CHEERFUL) ? 8 : 0)
            + (f.life().has(Social.Trait.CURIOUS) ? 8 : 0) - (f.life().has(Social.Trait.SHY) ? 10 : 0)
            - (f.life().has(Social.Trait.GRUMPY) ? 8 : 0) + (f.life().has(Social.Trait.HARDWORKING) ? 4 : 0);
    }

    /** [interviews] Is the town short of a trader just now (the place to be filled, InterviewPosts.stands)? */
    static boolean placeOpen(UUID id) {
        return wanted(id) && traders(id, true).size() < (int) Math.round(traders(id));
    }

    /** One more trader taken on: the first of the candidates (the town's choice: see candidates). */
    @Nullable
    static VillageFolkEntity appoint(ServerLevel level, Villages.Village v) {
        List<VillageFolkEntity> c = candidates(v);
        if (c.isEmpty()) return null;
        VillageFolkEntity f = c.get(0);
        take(level, v, f);
        return f;
    }

    /** This folk is a trader now: its post the Trading Post (or the board, till it stands). */
    static void take(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        StationTask was = f.stationTask();
        BlockPos post = post(v);
        f.setStation(post, StationTask.EMERALD);
        f.assignPlot(WorkZone.around(post, 6, WorkZone.DEFAULT_DEPTH), "The Trading Post");
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, f.displayNameCap() + " " + (was == StationTask.NONE ? "took up" : "gave up " + was.label + " for")
            + " trading with the villagers");
        f.persona().remember(day, "I became the town's emerald trader", 4);
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "The town's trader! I'll find the villagers, and strike a fair bargain.",
            "Emeralds for wheat, books for emeralds. Leave it to me.", "Me, the trader? I've always liked a haggle."));
        LOG.info("[MCA-EMERALD] {} of {} became the emerald trader (was {})", f.displayNameCap(), Villages.name(v.id()), was);
    }

    /** Where the trader stands at home: in the Trading Post, before its lectern, or beside the board till that stands. */
    static BlockPos post(Villages.Village v) {
        com.jrpetty.mcassistant.village.Ledger.Building b = Villages.builtStructure(v.id(), POST);
        if (b != null) return b.anchor();
        BlockPos board = VillageBoards.lectern(v.id());
        BlockPos at = board != null ? board : v.centre();
        return at.relative(Direction.EAST, 3);
    }

    /** Is the Trading Post wanted (Villages.projectsWantedInOrder): the trade open and no post yet? */
    public static boolean postWanted(UUID village) {
        return wanted(village) && !Villages.hasBuilt(village, POST) && Villages.builtStructure(village, POST) == null;
    }

    /** Why the town wants one (Villages.whyBuild). */
    public static String why(UUID village) {
        if (!wanted(village)) return "a trading post, once the town trades with the villagers";
        return "the Trading Post: the emerald trader's stall, its goods, and its book of the villagers' villages and their wares";
    }

    // ================================================================== the trader's day

    /**
     * A trader's day at home (its station brain): in the morning, out to a village of villagers with the town's goods (or
     * out looking for one); home again, what it brought into the stores; the rest of the day at the Trading Post with the
     * book. Returns whether it did something.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        if (f.expedition() != null) return true;
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        // Out beyond the fields (a restart in the middle of a trip): home first, along the way it came.
        if (f.blockPosition().distSqr(v.centre()) > 160 * 160) {
            Scouts.Expedition e = new Scouts.Expedition(id, v.centre(), 0, f.blockPosition());
            e.venture = new Venture(Venture.Purpose.TRADE, null);
            e.venture.stage = Stage.HOME;
            e.returning = true;
            e.crumb = -1;
            e.why = "came home";
            e.startedTick = e.gainedTick = f.tickCount;
            f.expedition(e);
            return true;
        }
        // Home with the last trip's goods still on its back (a restart): into the stores.
        if (unload(level, f, v, null) > 0) return true;
        boolean fit = f.getHealth() >= f.getMaxHealth() * 0.7F && !level.isThundering() && !Raids.underAlarm(id);
        boolean morning = time >= 1200 && time < 4200;
        if (morning && fit && WENT.getOrDefault(f.getUUID(), -1L) < day && !Assemblies.attending(f)) {
            if (setOut(f, level, v, day, null)) return true;
        }
        // At the Trading Post, with the book (its ground moved there once the post stands).
        BlockPos post = post(v);
        if (f.workZone() == null || f.workZone().center().distSqr(post) > 9) {
            f.assignPlot(WorkZone.around(post, 6, WorkZone.DEFAULT_DEPTH), "The Trading Post");
        }
        if (f.blockPosition().distSqr(post) > 4) {
            if (f.getNavigation().isDone()) f.walkTo(post, 0.8D);
        } else {
            f.hobbyNow = "going over the trading book";
        }
        return true;
    }

    /** A trader's trip: why it went, where to, how it is going, what it took and what it has done. */
    public static final class Venture {
        public enum Purpose { TRADE, EXPLORE }

        final Purpose purpose;
        @Nullable String hamlet;
        Stage stage = Stage.OUT;
        /** What it took from the stores to sell, and how many of each it may still sell. */
        final Map<Item, Integer> toSell = new LinkedHashMap<>();
        final Map<Item, Integer> took = new LinkedHashMap<>();
        int emeraldsTaken, earned, spent, trades, sold;
        final List<String> got = new ArrayList<>();
        final List<Want> wants = new ArrayList<>();
        final Map<String, Integer> bought = new HashMap<>();
        /** The villagers it is going round, and which it is with. */
        final List<UUID> round = new ArrayList<>();
        int at;
        int lastTrade = -1000, withSince, arrivedTick, waitSince = -1, lookedTick = -1000;
        /** The villagers' levels when it came, to see who went up. */
        final Map<UUID, Integer> levelsWhenCame = new HashMap<>();
        /** The village's villagers as last looked at, and when. */
        final List<Villager> seen = new ArrayList<>();
        int seenTick = -1000;
        @Nullable BlockPos stall;

        Venture(Purpose purpose, @Nullable String hamlet) {
            this.purpose = purpose;
            this.hamlet = hamlet;
        }

        public Purpose purpose() { return purpose; }
        @Nullable public String hamlet() { return hamlet; }
        public Stage stage() { return stage; }
        public int earned() { return earned; }
        public int spent() { return spent; }
        public int trades() { return trades; }
        public List<String> got() { return got; }
    }

    /** Where a trip has got to: on the way out, looking round the village, selling, buying, done there, or going home. */
    public enum Stage { OUT, LOOK, SELL, BUY, DONE, HOME }

    /**
     * Plan the day: where to go (or which way to look), the goods and the emeralds out of the stores, food for the road,
     * the pack donkey, and off. {@code to} is a village to go to whatever the plan says (the tests, the operator's command).
     */
    static boolean setOut(VillageFolkEntity f, ServerLevel level, Villages.Village v, long day, @Nullable String to) {
        UUID id = v.id();
        WENT.put(f.getUUID(), day);
        List<Want> wants = wants(level, v);
        List<Hamlet> book = book(id);
        Hamlet h = to != null ? find(book, to) : choose(level, v, book, wants, day);
        Venture t = new Venture(h == null ? Venture.Purpose.EXPLORE : Venture.Purpose.TRADE, h == null ? null : h.key);
        t.wants.addAll(wants);
        // The goods: what the villagers there buy (by their own offers, as last seen), else what villagers buy, and only
        // what the stores can spare.
        load(level, v, f, h, t);
        if (!wants.isEmpty()) {
            int n = Math.min(64, Market.stock(level, id, s -> s.is(Items.EMERALD)));
            for (ItemStack s : Caravans.takeOut(level, v, x -> x.is(Items.EMERALD), n)) {
                ItemStack left = f.insertGiven(s);
                t.emeraldsTaken += s.getCount() - left.getCount();
                if (!left.isEmpty()) Market.intoStores(level, id, left);
            }
            // What some of the wants cost besides the emeralds: a book for a librarian's enchanted book, a compass for a
            // cartographer's map. As many as the wants want, out of the stores.
            int booksWanted = 0;
            boolean mapWanted = false;
            for (Want w : wants) {
                if (w.item() == Items.ENCHANTED_BOOK) booksWanted++;
                if (w.item() == Items.FILLED_MAP) mapWanted = true;
            }
            if (booksWanted > 0) carry(level, v, f, Items.BOOK, Math.min(2, booksWanted), t);
            if (mapWanted) carry(level, v, f, Items.COMPASS, 1, t);
        }
        if (t.toSell.isEmpty() && t.emeraldsTaken == 0 && h != null) {
            // Nothing to sell and nothing to buy with: no trip today. What it carries for the road goes back.
            unload(level, f, v, t);
            return false;
        }
        // Food for the road.
        for (int i = 0; i < 4 && f.countMatching(s -> s.get(DataComponents.FOOD) != null) < 3; i++) {
            ItemStack s = Crafts.takeOne(level, v, x -> x.get(DataComponents.FOOD) != null && !x.is(Items.ROTTEN_FLESH)
                && !x.is(Items.SPIDER_EYE) && !sellable(x));
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        BlockPos target;
        int bearing;
        if (h != null) {
            target = h.centre;
            bearing = Scouts.bearingOf(target.getX() - v.centre().getX(), target.getZ() - v.centre().getZ());
        } else {
            // Exploring: the way the scouts' atlas knows least, as a scout picks its way.
            long[] bits = Scouts.explored(id);
            java.util.Random rng = new java.util.Random(f.getUUID().getLeastSignificantBits() ^ day * 131L);
            int best = -1, bestRing = Scouts.RINGS + 1, start = rng.nextInt(Scouts.BEARINGS);
            for (int k = 0; k < Scouts.BEARINGS; k++) {
                int b = (start + k) % Scouts.BEARINGS;
                if (Scouts.blockedThatWay(id, v.centre(), b)) continue;
                int ring = 0;
                while (ring < Scouts.RINGS && Scouts.been(bits, b, ring)) ring++;
                if (ring < bestRing) { bestRing = ring; best = b; }
            }
            // A village of villagers plainly on the land round the town (VanillaVillages), and not in the book yet, draws
            // it that way: exploring is for finding the new (one the book has, lately raided, is not walked back to).
            VanillaVillages.Known k = unbooked(level, v, book, EXPLORE);
            bearing = k != null ? Scouts.bearingOf(k.centre().getX() - v.centre().getX(), k.centre().getZ() - v.centre().getZ())
                : best < 0 ? start : best;
            double ang = bearing * (2 * Math.PI / Scouts.BEARINGS);
            target = new BlockPos(v.centre().getX() + (int) Math.round(Math.cos(ang) * EXPLORE), v.centre().getY(),
                v.centre().getZ() + (int) Math.round(Math.sin(ang) * EXPLORE));
        }
        f.clearQueue();
        f.getNavigation().stop();
        Scouts.Expedition e = new Scouts.Expedition(id, v.centre(), bearing, target);
        e.venture = t;
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        e.trail.add(f.blockPosition().immutable());
        f.expedition(e);
        Riding.packFor(level, v, f);                        // a donkey from the stable to carry the load, if there is one
        add(id, "trips", 1);
        String goods = goodsWords(t.toSell);
        if (h != null) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off to " + h.name + " with " + (goods.isEmpty() ? "the town's emeralds" : goods) + ". Back by dusk!",
                "To " + h.name + " today, " + e.heading + ". The villagers there know a fair price.",
                "Market day at " + h.name + ". Wish me a good bargain!"));
        } else {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off " + e.heading + " to find the villagers. There's a village out there somewhere.",
                "Looking for villagers today, " + e.heading + ". " + (goods.isEmpty() ? "" : "I'll take " + goods + " in case.")));
        }
        LOG.info("[MCA-EMERALD] {} of {} sets out {} ({}), with {} and {} emeralds; wants: {}", f.displayNameCap(), Villages.name(id),
            h == null ? "exploring " + e.heading : "for " + h.name, h == null ? "" : (int) Math.sqrt(Scouts.flat(h.centre, v.centre())) + " blocks",
            goods.isEmpty() ? "nothing to sell" : goods, t.emeraldsTaken, wantsWords(wants));
        return true;
    }

    /** The nearest village of villagers known on the land (VanillaVillages) within so far of the town that the book does
     *  not have yet, or null. */
    @Nullable
    static VanillaVillages.Known unbooked(ServerLevel level, Villages.Village v, List<Hamlet> book, int range) {
        VanillaVillages.Known best = null;
        int bestD = Integer.MAX_VALUE;
        for (VanillaVillages.Known k : VanillaVillages.all(level)) {
            int d = k.edge(v.centre().getX(), v.centre().getZ());
            if (d > range || d >= bestD) continue;
            boolean have = false;
            for (Hamlet h : book) {
                if (h.key.equals(k.key()) || h.within(k.centre().getX(), k.centre().getZ(), 24)) { have = true; break; }
            }
            if (!have) { best = k; bestD = d; }
        }
        return best;
    }

    /** "60 wheat and 20 potatoes". */
    static String goodsWords(Map<Item, Integer> goods) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : goods.entrySet()) parts.add(count(id(e.getKey()), e.getValue()));
        if (parts.isEmpty()) return "";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    static String wantsWords(List<Want> wants) {
        List<String> w = new ArrayList<>();
        for (Want x : wants) w.add(x.words());
        return w.isEmpty() ? "nothing" : String.join(", ", w);
    }

    /** Out of the stores into its pack, so many of this: what it costs a villager's offer besides emeralds. */
    private static void carry(ServerLevel level, Villages.Village v, VillageFolkEntity f, Item what, int n, Venture t) {
        for (ItemStack s : Caravans.takeOut(level, v, x -> x.is(what) && !x.isEnchanted(), n)) {
            ItemStack left = f.insertGiven(s);
            t.took.merge(what, s.getCount() - left.getCount(), Integer::sum);
            if (!left.isEmpty()) Market.intoStores(level, v.id(), left);
        }
    }

    /**
     * The goods for a trip, into the pack: what the villagers there buy, by their offers as last seen (what a village
     * never seen buys is what villagers buy), as much as the stores can spare (Budget.spare: the town's own needs first,
     * and only a glut while it is short of anything) and as much as the offers will take, five kinds at most.
     */
    static void load(ServerLevel level, Villages.Village v, VillageFolkEntity f, @Nullable Hamlet h, Venture t) {
        UUID id = v.id();
        Map<Item, Integer> room = new LinkedHashMap<>();                 // what the offers there will take, by item
        if (h != null) {
            for (Seller s : h.folk) {
                for (Deal d : s.deals) {
                    if (!d.buys() || !d.cost2().isEmpty()) continue;
                    Item it = item(d.cost());
                    if (!BOUGHT_SET.contains(it)) continue;
                    // As many uses as it has left when last seen, and all of them again if it has restocked since.
                    room.merge(it, d.costN() * Math.max(1, d.max()), Integer::sum);
                }
            }
        }
        if (room.isEmpty()) for (Item it : BOUGHT) room.put(it, 64);
        int kinds = 0;
        for (Map.Entry<Item, Integer> e : room.entrySet()) {
            if (kinds >= 5) break;
            Item it = e.getKey();
            int spare = Budget.spare(level, id, new ItemStack(it));
            int n = Math.min(Math.min(spare, e.getValue()), 64);
            if (n < 4) continue;
            int took = 0;
            for (ItemStack s : Caravans.takeOut(level, v, x -> x.is(it) && sellable(x), n)) {
                ItemStack left = f.insertGiven(s);
                took += s.getCount() - left.getCount();
                if (!left.isEmpty()) Market.intoStores(level, id, left);
            }
            if (took <= 0) continue;
            t.toSell.put(it, took);
            t.took.merge(it, took, Integer::sum);
            kinds++;
        }
        Budget.forget(id);
    }

    /**
     * Where to go today: of the villages of villagers within a day's walk not lately raided, the one with somebody who
     * sells what the town wants most (the trader learns who sells Mending, and goes back); then where most of what it
     * can spare is bought; the nearer the better; and one never yet traded with is worth the walk. Null to go looking.
     */
    @Nullable
    static Hamlet choose(ServerLevel level, Villages.Village v, List<Hamlet> book, List<Want> wants, long day) {
        Map<Item, Integer> spare = spareGoods(level, v.id());
        Hamlet best = null;
        double bestScore = -Double.MAX_VALUE;
        long now = level.getGameTime();
        for (Hamlet h : book) {
            double d = Math.sqrt(Scouts.flat(h.centre, v.centre()));
            if (d > REACH) continue;
            if (h.raided >= 0 && day * 24000L - h.raided * 24000L < RAIDED_FOR) continue;
            double score = -d / 16.0;
            for (int i = 0; i < wants.size(); i++) {
                Want w = wants.get(i);
                for (Seller s : h.folk) for (Deal dl : s.deals) if (w.matches(dl)) { score += 120 - 15 * i; break; }
            }
            Set<String> buys = new HashSet<>();
            for (Seller s : h.folk) for (Deal dl : s.deals) if (dl.buys() && spare.containsKey(item(dl.cost()))) buys.add(dl.cost());
            score += 20 * buys.size();
            if (h.folk.isEmpty()) score += 25;                              // never seen close: worth a look
            if (h.visited >= 0 && h.visited == day) score -= 30;            // been today already
            if (score > bestScore) { bestScore = score; best = h; }
        }
        return best;
    }

    // ================================================================== on the road

    /** Every five ticks while it is out (Scouts.drive): the way there, the village, the way home. True while it is out. */
    static boolean drive(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        Venture t = e.venture;
        if (t == null) return false;
        Scouts.keepAwake(level, f, e);
        long time = level.getDayTime() % 24000L;
        UUID id = e.village;
        Villages.Village v = Villages.get(id);
        if (v == null) {
            Scouts.abandon(level, f);
            return false;
        }
        // Something hostile close by: away from it, quick (as a scout does). At a village's stalls too.
        Mob foe = Scouts.foe(level, f);
        if (foe != null) {
            Vec3 away = f.position().subtract(foe.position()).normalize().scale(10);
            BlockPos run = Scouts.surface(level, BlockPos.containing(f.getX() + away.x, f.getY(), f.getZ() + away.z));
            f.getNavigation().moveTo(run.getX() + 0.5, run.getY(), run.getZ() + 0.5, 1.25D);
            if (f.tickCount - e.spokeTick > 400) {
                e.spokeTick = f.tickCount;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Not with the town's goods on my back!", "Away! I'm a trader, not a fighter."));
            }
            return true;
        }
        // The pack donkey fetched, tied and kept up with (Riding).
        Hamlet hNow = t.hamlet == null ? null : find(book(id), t.hamlet);
        if (Riding.packAlong(f, level, hNow != null ? hNow.name : "the villagers")) return true;
        // The afternoon (or hurt, or a long day): home, wherever it has got to.
        if (!e.returning) {
            boolean late = time >= 8800 && time < 23000;
            if (f.getHealth() < f.getMaxHealth() * 0.45F) homeward(level, f, e, t, "I got hurt");
            else if (late && t.stage != Stage.SELL && t.stage != Stage.BUY) homeward(level, f, e, t, "it was getting late");
            else if (time >= 10500 && time < 23000) homeward(level, f, e, t, "it was getting late");
            else if (f.tickCount - e.startedTick > 11000) homeward(level, f, e, t, "it was a long day");
        }
        if (e.returning) return walkHome(level, f, e, t, v);
        switch (t.stage) {
            case OUT -> out(level, f, e, t, v, hNow);
            case LOOK, SELL, BUY, DONE -> {
                if (hNow == null) homeward(level, f, e, t, "the village was not where the book had it");
                else stalls(level, f, e, t, v, hNow);
            }
            case HOME -> homeward(level, f, e, t, "came home");
        }
        return true;
    }

    /** On the way out: to the village (or, exploring, the way the atlas knows least), looking about as it goes. */
    private static void out(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Villages.Village v, @Nullable Hamlet h) {
        long day = level.getDayTime() / 24000L;
        // Look about every couple of seconds: a village of villagers seen from the road goes into the atlas and the book.
        if (f.tickCount - e.surveyTick >= 40) {
            e.surveyTick = f.tickCount;
            Hamlet seen = lookAbout(level, f, e, t, v, day);
            if (h == null && seen != null) {
                // Exploring, and there it is: there to trade, now.
                t.hamlet = seen.key;
                e.waypoint = null;
                e.best = Double.MAX_VALUE;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There! Villagers! " + seen.name + ", I'll call it.",
                    "A village of villagers, " + Guide.direction(f.blockPosition(), seen.centre) + " of here. Let's see what they buy."));
                return;
            }
        }
        // Breadcrumbs on the way out, and the land marked as seen in the scouts' atlas.
        BlockPos last = e.trail.get(e.trail.size() - 1);
        if (Scouts.flat(f.blockPosition(), last) > 16 * 16 && f.onGround()) {
            e.trail.add(f.blockPosition().immutable());
            Scouts.mark(e.village, e.home, f.blockPosition());
        }
        if (h != null && (h.within(f.getBlockX(), f.getBlockZ(), 4) || Scouts.flat(f.blockPosition(), h.centre) <= 14 * 14)) {
            arrive(level, f, e, t, h);
            return;
        }
        BlockPos dest = h != null ? h.centre : e.target;
        if (h == null && Scouts.flat(f.blockPosition(), dest) <= 10 * 10) {
            homeward(level, f, e, t, "I went as far as I meant to, and found no villagers that way");
            return;
        }
        step(level, f, e, dest);
        f.hobbyNow = h != null ? "on the road to " + h.name + " with the town's goods" : "looking for villagers, " + e.heading;
        if (f.tickCount - e.spokeTick > 3000) {
            e.spokeTick = f.tickCount;
            f.say(h != null ? "On the road to " + h.name + "." : "Looking for villagers " + e.heading + ".");
        }
    }

    /** What it can see from here: the village the world built that it is in or near, and any it knows of within sight. */
    @Nullable
    static Hamlet lookAbout(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Villages.Village v, long day) {
        BlockPos here = f.blockPosition();
        VanillaVillages.sawFrom(level, here);
        if (f.tickCount % 400 < 40) VanillaVillages.lookAround(level, here, SIGHT + 64);
        VanillaVillages.Known k = VanillaVillages.nearest(level, here, SIGHT);
        if (k == null) return null;
        List<Hamlet> book = book(v.id());
        for (Hamlet h : book) if (h.key.equals(k.key()) || h.within(k.centre().getX(), k.centre().getZ(), 24)) return null;
        Hamlet h = hamlet(k, day, f.displayNameCap());
        book.add(h);
        save(v.id(), book);
        Scouts.record(v.id(), new Scouts.Find(Scouts.Kind.SETTLEMENT, "a village of villagers", k.centre(), day, f.displayNameCap()));
        Villages.tell(v.id(), day, f.displayNameCap() + " found " + h.words() + ", " + (int) Math.sqrt(Scouts.flat(k.centre(), v.centre()))
            + " blocks " + Guide.direction(v.centre(), k.centre()) + " of the town");
        log(v.id(), day, "found " + h.words() + " " + Guide.direction(v.centre(), k.centre()) + " of the town");
        LOG.info("[MCA-EMERALD] {} found {} at {}", f.displayNameCap(), h.words(), k.centre().toShortString());
        return h;
    }

    /** One stage of the walk toward somewhere, by the scouts' way of picking a line over the land. True when there. */
    static boolean step(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, BlockPos dest) {
        double d = Scouts.flat(f.blockPosition(), dest);
        if (d <= 3 * 3) return true;
        if (d < e.best - 2.0) {
            e.best = d;
            e.gainedTick = f.tickCount;
            if (e.detour > 0 && f.tickCount % 200 == 0) e.detour--;
        } else if (f.tickCount - e.gainedTick > 300) {
            e.detour++;
            e.waypoint = null;
            e.gainedTick = f.tickCount;
            if (e.detour > 5) {
                // Stuck fast (a river, a cliff): over the bad patch to a spot a stage on, its donkey with it.
                BlockPos to = Scouts.surface(level, BlockPos.containing(f.getX() + (dest.getX() - f.getX()) * Math.min(1.0, 16.0 / Math.sqrt(d)),
                    f.getY(), f.getZ() + (dest.getZ() - f.getZ()) * Math.min(1.0, 16.0 / Math.sqrt(d))));
                Riding.carry(f, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
                Riding.bringAlong(f, level, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
                e.detour = 0;
                e.best = Double.MAX_VALUE;
            }
        }
        if (e.waypoint == null || Scouts.flat(f.blockPosition(), e.waypoint) <= 3 * 3) {
            e.waypoint = d <= 22 * 22 ? Scouts.surface(level, dest) : Scouts.chooseWaypoint(level, f, dest, e.detour);
            e.walkTick = -1000;
        }
        if (e.waypoint != null && (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80)) {
            f.walkTo(e.waypoint, 1.0D);
            e.walkTick = f.tickCount;
        }
        return false;
    }

    /** Turn for home: the village's stalls let go, the goods into the donkey's chest, and back along its own trail. */
    static void homeward(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, String why) {
        if (e.returning) return;
        Hamlet h = t.hamlet == null ? null : find(book(e.village), t.hamlet);
        if (h != null && (t.stage == Stage.SELL || t.stage == Stage.BUY || t.stage == Stage.DONE || t.stage == Stage.LOOK)) {
            leave(level, f, t, h);
        }
        releaseStalls(level, f);
        Riding.pack(f, level);
        t.stage = Stage.HOME;
        e.returning = true;
        e.why = why;
        e.crumb = e.trail.size() - 1;
        e.waypoint = null;
        e.detour = 0;
        e.best = Double.MAX_VALUE;
        e.gainedTick = f.tickCount;
        FolkTalk.speak(f, why.startsWith("I got hurt") ? "Ow. Home, and the goods with me."
            : t.trades > 0 ? FolkTalk.pick(f.getRandom(), "A good day's trading. Home!", "Home, with a lighter pack and a heavier purse.")
            : FolkTalk.pick(f.getRandom(), "Home, then.", "Time to head back."));
    }

    /** Home along its own trail, as a scout comes home. True while it is still on the way. */
    private static boolean walkHome(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Villages.Village v) {
        if (e.crumb < 0 && Scouts.flat(f.blockPosition(), e.home) <= 14 * 14) {
            home(level, f, e, t, v);
            return false;
        }
        BlockPos dest = e.crumb >= 0 && e.crumb < e.trail.size() ? e.trail.get(e.crumb) : e.home;
        if (e.crumb >= 0 && Scouts.flat(f.blockPosition(), dest) <= 6 * 6) {
            e.crumb--;
            e.waypoint = null;
            e.best = Double.MAX_VALUE;
            e.gainedTick = f.tickCount;
            return true;
        }
        if (e.crumb >= 0 && e.detour > 3) {
            // It knows the way: it walked it. Over the bad patch to the next mark.
            BlockPos to = Scouts.surface(level, dest);
            Riding.carry(f, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            Riding.bringAlong(f, level, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            e.detour = 0;
            e.best = Double.MAX_VALUE;
            return true;
        }
        step(level, f, e, dest);
        f.hobbyNow = "on the way home from trading";
        return true;
    }

    // ================================================================== at the villagers' village

    /** The owner of the ground kept awake round a village while the trader is at its stalls. */
    private static UUID stallsOwner(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-emerald-" + f.getUUID()).getBytes());
    }

    private static void releaseStalls(ServerLevel level, VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        Venture t = e == null ? null : e.venture;
        if (t != null && t.stall != null) {
            ChunkLoad.setLoaded(level, stallsOwner(f), t.stall, 3, false);
            t.stall = null;
        }
    }

    /** Arrived: the whole village kept awake while it is there, and a look round it before anything else. */
    static void arrive(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Hamlet h) {
        // A village known only from the scouts' atlas (a spot and a guess at its size): its true bounds, now it is here.
        VanillaVillages.sawFrom(level, f.blockPosition());
        VanillaVillages.Known k = h.key.startsWith("atlas@") ? VanillaVillages.nearest(level, h.centre, 48) : null;
        if (k != null) {
            h.centre = k.centre();
            h.kind = k.kind();
            h.x0 = k.x0();
            h.z0 = k.z0();
            h.x1 = k.x1();
            h.z1 = k.z1();
            if (f.ownerId() != null) save(f.ownerId(), book(f.ownerId()));
        }
        t.stage = Stage.LOOK;
        t.arrivedTick = f.tickCount;
        t.stall = h.centre;
        Riding.unpack(f, level);                             // the goods off the donkey's back, to hand at the stalls
        ChunkLoad.setLoaded(level, stallsOwner(f), h.centre, 3, true);
        f.getNavigation().stop();
        f.brain("at " + h.name + ", looking round the stalls");
        LOG.info("[MCA-EMERALD] {} arrived at {}", f.displayNameCap(), h.name);
    }

    /** Is the village being raided (a raid on it, or raiders about it)? */
    static boolean raided(ServerLevel level, Hamlet h) {
        Raid raid = level.getRaidAt(h.centre);
        if (raid != null && raid.isActive()) return true;
        AABB box = new AABB(h.x0 - 24, h.centre.getY() - 24, h.z0 - 24, h.x1 + 25, h.centre.getY() + 24, h.z1 + 25);
        return !level.getEntitiesOfClass(Raider.class, box, r -> r.isAlive()
            && (r.getCurrentRaid() != null || !(r instanceof net.minecraft.world.entity.monster.Witch))).isEmpty();
    }

    /** Is a player trading with any villager of the village just now? */
    @Nullable
    static Villager playerAtAStall(List<Villager> folk) {
        for (Villager v : folk) if (v.isTrading()) return v;
        return null;
    }

    /** At the village: the look round, then the selling, the buying, and home. */
    private static void stalls(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Villages.Village v, Hamlet h) {
        long day = level.getDayTime() / 24000L;
        // Pillagers at the village: not the town's fight. Home, and tell. (Looked for on arriving, before anything else,
        // and once a second while it is there.)
        if ((t.stage == Stage.LOOK || f.tickCount % 20 == 0) && raided(level, h)) {
            raidedAway(level, f, e, t, v, h, day);
            return;
        }
        // Who is about, looked again once a second (not every step).
        if (f.tickCount - t.seenTick >= 20 || f.tickCount < t.seenTick) {
            t.seen.clear();
            t.seen.addAll(villagersOf(level, h));
            t.seenTick = f.tickCount;
        }
        t.seen.removeIf(x -> !x.isAlive() || x.isRemoved());
        List<Villager> folk = t.seen;
        // A player at a villager's stall: the trader does not cut in, and waits (a while) with the village's trade.
        Villager busy = playerAtAStall(folk);
        if (busy != null) {
            if (t.waitSince < 0) {
                t.waitSince = f.tickCount;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A traveller at the stalls. I'll wait my turn.",
                    "After you, friend. I can wait."));
            }
            f.getNavigation().stop();
            f.getLookControl().setLookAt(busy);
            f.hobbyNow = "waiting while a traveller trades at " + h.name;
            if (f.tickCount - t.waitSince > WAIT_FOR_PLAYER) homeward(level, f, e, t, "a traveller kept the stalls busy all morning");
            return;
        }
        t.waitSince = -1;
        switch (t.stage) {
            case LOOK -> {
                // Two looks round (the far side's villagers come into the world as their ground wakes), then to work.
                if (f.tickCount - t.arrivedTick < 60 && !quickForTests) {
                    if (f.getNavigation().isDone()) f.walkTo(h.centre, 0.9D);
                    f.hobbyNow = "looking round " + h.name;
                    return;
                }
                survey(level, f, v, h, folk, day, true);
                t.stage = Stage.SELL;
                t.round.clear();
                t.round.addAll(sellRound(folk, f, t));
                t.at = 0;
                t.withSince = f.tickCount;
                if (folk.isEmpty()) {
                    FolkTalk.speak(f, "Nobody about at " + h.name + ". Not a villager to be seen.");
                    homeward(level, f, e, t, "there was nobody at the stalls");
                }
            }
            case SELL, BUY -> work(level, f, e, t, v, h, folk);
            case DONE -> {
                // A moment for the villagers who were raised a level by the day's trade to take it up (the game gives it
                // them two seconds after their last trade), then the book brought up to date, and home.
                if (f.tickCount - t.lastTrade < 60 && !quickForTests) return;
                survey(level, f, v, h, folk, day, false);
                homeward(level, f, e, t, "the day's trading was done");
            }
            default -> { }
        }
    }

    /** The villagers who buy something it carries to sell, nearest first. */
    static List<UUID> sellRound(List<Villager> folk, VillageFolkEntity f, Venture t) {
        List<Villager> by = new ArrayList<>(folk);
        by.sort((a, b) -> Double.compare(a.distanceToSqr(f), b.distanceToSqr(f)));
        List<UUID> out = new ArrayList<>();
        for (Villager v : by) if (nextSale(v, f, t) >= 0) out.add(v.getUUID());
        return out;
    }

    /** The villagers who sell something on the buying list, the cheapest for each want first. */
    static List<UUID> buyRound(List<Villager> folk, VillageFolkEntity f, Venture t) {
        List<UUID> out = new ArrayList<>();
        for (Want w : t.wants) {
            if (t.bought.getOrDefault(w.key(), 0) >= w.count()) continue;
            Villager best = null;
            int bestCost = Integer.MAX_VALUE;
            for (Villager v : folk) {
                MerchantOffers offers = v.getOffers();
                for (MerchantOffer o : offers) {
                    if (!buyable(o, w, f)) continue;
                    if (o.getCostA().getCount() < bestCost) { bestCost = o.getCostA().getCount(); best = v; }
                }
            }
            if (best != null && !out.contains(best.getUUID())) out.add(best.getUUID());
        }
        return out;
    }

    /** May this offer be bought for this want, with what the trader carries: in stock, paid in emeralds (and a book or a
     *  compass, if it asks one), within what the want will pay, and the trader carrying all of it. */
    static boolean buyable(MerchantOffer o, Want w, VillageFolkEntity f) {
        if (o.isOutOfStock() || !w.matches(o.getResult())) return false;
        ItemStack a = o.getCostA();
        if (!a.is(Items.EMERALD) || a.getCount() > w.most()) return false;
        if (carried(f, o.getItemCostA()) < a.getCount()) return false;
        ItemStack b = o.getCostB();
        if (!b.isEmpty()) {
            Optional<ItemCost> cb = o.getItemCostB();
            if (cb.isEmpty() || carried(f, cb.get()) < b.getCount()) return false;
        }
        return true;
    }

    /** How many of what this cost asks for the trader carries. */
    static int carried(VillageFolkEntity f, ItemCost c) {
        return f.countMatching(c::test);
    }

    /** The first of this villager's offers it can sell to now: one that pays an emerald for something it brought to sell
     *  (and has not sold all of), in stock, the whole price carried. -1 for none. */
    static int nextSale(Villager v, VillageFolkEntity f, Venture t) {
        MerchantOffers offers = v.getOffers();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer o = offers.get(i);
            if (o.isOutOfStock() || !o.getResult().is(Items.EMERALD) || !o.getCostB().isEmpty()) continue;
            ItemStack a = o.getCostA();
            int may = t.toSell.getOrDefault(a.getItem(), 0);
            if (may < a.getCount() || carried(f, o.getItemCostA()) < a.getCount()) continue;
            return i;
        }
        return -1;
    }

    /** The first of this villager's offers it can buy for the list now, or -1. */
    static int nextBuy(Villager v, VillageFolkEntity f, Venture t) {
        MerchantOffers offers = v.getOffers();
        for (Want w : t.wants) {
            if (t.bought.getOrDefault(w.key(), 0) >= w.count()) continue;
            int best = -1, bestCost = Integer.MAX_VALUE;
            for (int i = 0; i < offers.size(); i++) {
                MerchantOffer o = offers.get(i);
                if (!buyable(o, w, f)) continue;
                if (o.getCostA().getCount() < bestCost) { bestCost = o.getCostA().getCount(); best = i; }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    /** At the stalls: to the next villager on the round, and its trades one at a time. */
    private static void work(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Villages.Village v, Hamlet h,
                             List<Villager> folk) {
        if (t.at >= t.round.size()) {
            if (t.stage == Stage.SELL) {
                t.stage = Stage.BUY;
                t.round.clear();
                t.round.addAll(buyRound(folk, f, t));
                t.at = 0;
                t.withSince = f.tickCount;
                return;
            }
            t.stage = Stage.DONE;
            return;
        }
        UUID who = t.round.get(t.at);
        Villager vg = level.getEntity(who) instanceof Villager x && trades(x) ? x : null;
        if (vg == null || f.tickCount - t.withSince > 600) {
            t.at++;
            t.withSince = f.tickCount;
            return;
        }
        // Up to its stall: within arm's reach of it, as a player stands to trade.
        if (f.distanceToSqr(vg) > 2.6 * 2.6) {
            if (f.getNavigation().isDone() || f.tickCount % 20 == 0) f.walkTo(vg.blockPosition(), 0.9D);
            f.hobbyNow = "on its way to " + villagerName(vg.getUUID()) + "'s stall at " + h.name;
            return;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(vg);
        vg.getLookControl().setLookAt(f);
        if (vg.isSleeping()) {
            t.at++;                                                  // asleep: no trading with a villager in its bed
            return;
        }
        if (f.tickCount - t.lastTrade < TRADE_EVERY && !quickForTests) return;
        int i = t.stage == Stage.SELL ? nextSale(vg, f, t) : nextBuy(vg, f, t);
        if (i < 0) {
            t.at++;
            t.withSince = f.tickCount;
            return;
        }
        MerchantOffer o = vg.getOffers().get(i);
        if (!trade(level, f, vg, o, t, h)) {
            t.at++;
            t.withSince = f.tickCount;
        }
    }

    /**
     * One trade with a villager, through its own offer, as a player's goes: the villager is not trading with anybody,
     * the offer is not used up, the whole price (as the offer has it now, with its demand) is paid out of the pack and
     * what it gives is taken. The villager's side is the game's own (notifyTrade): the offer's uses go up, the villager
     * gets the offer's trade experience, and a level is coming when it has earned one, as from a player's trade. Called
     * with nobody trading with the villager: the advancement it would give a player is skipped for want of one, and the
     * NeoForge trade event that follows is told no player. Returns whether it traded.
     */
    static boolean trade(ServerLevel level, VillageFolkEntity f, Villager v, MerchantOffer o, Venture t, @Nullable Hamlet h) {
        if (!trades(v) || v.isTrading() || v.isSleeping()) return false;      // never cut in on a player, never wake one
        if (o.isOutOfStock()) return false;
        ItemStack a = o.getCostA(), b = o.getCostB();
        if (carried(f, o.getItemCostA()) < a.getCount()) return false;
        Optional<ItemCost> cb = o.getItemCostB();
        if (!b.isEmpty() && (cb.isEmpty() || carried(f, cb.get()) < b.getCount())) return false;
        ItemStack got = o.assemble();
        if (!roomFor(f, got)) return false;                                  // never pays for what it cannot carry home
        // Paid, in full.
        int paidA = f.removeMatching(o.getItemCostA()::test, a.getCount());
        int paidB = b.isEmpty() ? 0 : f.removeMatching(cb.get()::test, b.getCount());
        if (paidA < a.getCount() || paidB < b.getCount()) {
            // Never happens (it was counted just now); if it did, what was paid comes back and nothing is taken.
            if (paidA > 0) f.insertItem(a.copyWithCount(paidA));
            if (paidB > 0) f.insertItem(b.copyWithCount(paidB));
            return false;
        }
        int levelBefore = v.getVillagerData().getLevel();
        try {
            v.notifyTrade(o);
        } catch (RuntimeException ex) {
            // A trade listener elsewhere that wanted a player: the offer's use and the villager's experience are already
            // counted (they come before the event), so the trade stands.
            LOG.debug("[MCA-EMERALD] a trade listener failed without a player: {}", ex.toString());
        }
        level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
        ItemStack left = f.insertItem(got);
        if (!left.isEmpty()) f.spawnAtLocation(left);                        // (never: there was room) at its feet, not lost
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.awardXp(2 + o.getXp());
        t.lastTrade = f.tickCount;
        t.trades++;
        UUID town = f.ownerId();
        if (a.is(Items.EMERALD)) {
            t.spent += a.getCount();
            for (Want w : t.wants) {
                if (w.matches(got) && t.bought.getOrDefault(w.key(), 0) < w.count()) {
                    t.bought.merge(w.key(), got.getCount(), Integer::sum);
                    break;
                }
            }
            t.got.add(label(got) + (got.is(Items.ENCHANTED_BOOK) ? " book" : "") + " from " + villagerName(v.getUUID()) + " the "
                + tradeOf(v) + " for " + a.getCount() + (a.getCount() == 1 ? " emerald" : " emeralds"));
        }
        if (got.is(Items.EMERALD)) {
            t.earned += got.getCount();
            t.sold += a.getCount();
            t.toSell.merge(a.getItem(), -a.getCount(), Integer::sum);
        }
        if (h != null && town != null) {
            Seller s = h.seller(v.getUUID());
            if (s != null) s.dealt++;
        }
        if (!t.levelsWhenCame.containsKey(v.getUUID())) t.levelsWhenCame.put(v.getUUID(), levelBefore);
        f.brain("traded with " + villagerName(v.getUUID()) + " the " + tradeOf(v) + ": " + a.getCount() + " " + a.getHoverName().getString()
            + " for " + got.getCount() + " " + got.getHoverName().getString());
        return true;
    }

    /** Is there room in its pack for all of this? */
    static boolean roomFor(VillageFolkEntity f, ItemStack s) {
        int room = 0;
        for (ItemStack in : f.getInventoryItems()) {
            if (in.isEmpty()) room += s.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(in, s)) room += in.getMaxStackSize() - in.getCount();
            if (room >= s.getCount()) return true;
        }
        return room >= s.getCount();
    }

    /** The villagers into the book: who they are, their trades and levels, and their offers as they stand now. */
    static void survey(ServerLevel level, VillageFolkEntity f, Villages.Village v, Hamlet hh, List<Villager> folk, long day, boolean coming) {
        List<Hamlet> book = book(v.id());
        Hamlet h = find(book, hh.key);
        if (h == null) {
            h = hh;
            book.add(h);
        }
        for (Villager x : folk) {
            Seller s = see(h, x, day);
            if (!coming) {
                Integer was = f.expedition() != null && f.expedition().venture != null ? f.expedition().venture.levelsWhenCame.get(x.getUUID()) : null;
                if (was != null && s.level > was) raised(level, f, v, h, s, day);
            }
        }
        h.visited = day;
        save(v.id(), book);
        if (coming) {
            List<String> who = new ArrayList<>();
            for (Seller s : h.folk) if (s.seen == day) who.add(s.words());
            f.brain("at " + h.name + ": " + (who.isEmpty() ? "nobody" : String.join("; ", who.subList(0, Math.min(4, who.size())))));
        }
    }

    /** A villager the day's trade raised a level: the trader is pleased, and a master is the town's news. */
    private static void raised(ServerLevel level, VillageFolkEntity f, Villages.Village v, Hamlet h, Seller s, long day) {
        String lvl = LEVELS[Math.max(1, Math.min(5, s.level)) - 1].toLowerCase(Locale.ROOT);
        FolkTalk.speak(f, s.name + " the " + s.trade + " is " + JobMarket.a(lvl) + " now! New wares on the stall.");
        log(v.id(), day, s.name + " the " + s.trade + " at " + h.name + " became " + JobMarket.a(lvl) + " with our trade");
        if (s.level >= 5) Villages.tell(v.id(), day, s.name + " the " + s.trade + " of " + h.name + " is a master now, and our trade did it");
    }

    /** The village under a raid: home, and the town told. The watch stays at home: the villagers' fight is not the town's. */
    private static void raidedAway(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Villages.Village v, Hamlet hh, long day) {
        List<Hamlet> book = book(v.id());
        Hamlet h = find(book, hh.key);
        if (h != null) {
            h.raided = day;
            save(v.id(), book);
        }
        hh.raided = day;
        Villages.tell(v.id(), day, f.displayNameCap() + " found pillagers raiding " + hh.name + " and came straight home");
        log(v.id(), day, "turned back from " + hh.name + ": pillagers were raiding it");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Pillagers at " + hh.name + "! I'm not stopping. Home, and tell everybody.",
            "Raiders at " + hh.name + "! Not our fight. Home!"));
        LOG.info("[MCA-EMERALD] {} turned back from {}: a raid", f.displayNameCap(), hh.name);
        homeward(level, f, e, t, "pillagers were raiding " + hh.name);
    }

    /** Leaving a village: the book brought up to date with the day's trading. */
    private static void leave(ServerLevel level, VillageFolkEntity f, Venture t, Hamlet hh) {
        UUID id = f.ownerId();
        if (id == null) return;
        List<Hamlet> book = book(id);
        Hamlet h = find(book, hh.key);
        if (h == null) return;
        if (t.trades > 0) h.trips++;
        h.earned += t.earned;
        h.spent += t.spent;
        h.visited = level.getDayTime() / 24000L;
        // What the trades did to the villagers' own records (dealt), kept.
        for (Seller s : hh.folk) {
            Seller k = h.seller(s.id);
            if (k != null) k.dealt = Math.max(k.dealt, s.dealt);
        }
        save(id, book);
    }

    // ================================================================== home

    /** Home: the goods out of the donkey's chest and into the stores, the donkey led home, the account and the book. */
    static void home(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Venture t, Villages.Village v) {
        releaseStalls(level, f);
        Scouts.release(level, f, e);
        f.expedition(null);
        Riding.unpack(f, level);
        Riding.caravanHome(f, level);
        long day = level.getDayTime() / 24000L;
        UUID id = v.id();
        unload(level, f, v, t);
        add(id, "earned", t.earned);
        add(id, "spent", t.spent);
        // The asks that are met come off the list.
        List<String> asks = asks(id);
        boolean met = asks.removeIf(a -> t.bought.getOrDefault(a, 0) > 0);
        if (met) asks(id, asks);
        Hamlet h = t.hamlet == null ? null : find(book(id), t.hamlet);
        String where = h == null ? "the road" : h.name;
        String text;
        if (t.trades == 0) {
            text = h == null ? "came home from looking for villagers " + e.heading + " with nothing found"
                : "came home from " + where + " without trading (" + e.why + ")";
        } else {
            List<String> parts = new ArrayList<>();
            if (t.earned > 0) parts.add(t.earned + (t.earned == 1 ? " emerald" : " emeralds") + " for " + soldWords(t));
            parts.addAll(t.got);
            text = "from " + where + ": " + String.join("; ", parts);
        }
        log(id, day, text);
        // The town's news only for what matters: something bought for it, or the first trade with a village.
        boolean first = h != null && h.trips <= 1 && t.trades > 0;
        if (!t.got.isEmpty() || first) {
            Villages.tell(id, day, f.displayNameCap() + " came home from " + where + " with " + (t.got.isEmpty()
                ? t.earned + " emeralds for the town's goods" : String.join(", ", t.got.subList(0, Math.min(2, t.got.size())))));
        }
        if (t.trades > 0) f.persona().remember(day, "I traded at " + where + " and came home with " + (t.got.isEmpty()
            ? t.earned + " emeralds" : t.got.get(0)), t.got.isEmpty() ? 2 : 4);
        FolkTalk.speak(f, t.got.isEmpty() ? (t.earned > 0 ? "Home! " + t.earned + " emeralds for the stores." : "Home again.")
            : "Home! I've " + t.got.get(0) + ".");
        LOG.info("[MCA-EMERALD] {} home from {} ({}): {} trades, {} emeralds earned, {} spent; {}", f.displayNameCap(), where, e.why,
            t.trades, t.earned, t.spent, t.got);
    }

    /** "60 wheat and 20 potatoes", what was sold. */
    static String soldWords(Venture t) {
        Map<Item, Integer> sold = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : t.took.entrySet()) {
            Integer left = t.toSell.get(e.getKey());
            if (left == null) continue;
            int n = e.getValue() - left;
            if (n > 0) sold.put(e.getKey(), n);
        }
        String w = goodsWords(sold);
        return w.isEmpty() ? "the town's goods" : w;
    }

    /**
     * What it carries that is the town's into the stores: the emeralds, what they bought, the goods it did not sell, and
     * what it took for the trading; a bite of food kept for itself. What the stores have no room for stays in its pack.
     * What it brought home new (the emeralds earned, what they bought) is the town's output, and the trader's (Economy).
     * Returns how many things went in.
     */
    static int unload(ServerLevel level, VillageFolkEntity f, Villages.Village v, @Nullable Venture t) {
        if (f.blockPosition().distSqr(v.centre()) > 96 * 96) return 0;
        int moved = 0, food = 0;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty()) continue;
            boolean isFood = s.get(DataComponents.FOOD) != null && !sellable(s);
            if (isFood) {
                food += s.getCount();
                if (food <= 8) continue;                                   // its own bite for tomorrow
            }
            boolean ours = s.is(Items.EMERALD) || sellable(s) || s.is(Items.ENCHANTED_BOOK) || s.is(Items.BOOK) || s.is(Items.COMPASS)
                || s.is(Items.FILLED_MAP) || isFood || t != null && (t.took.containsKey(s.getItem()) || boughtThis(t, s));
            if (!ours) continue;
            Economy.produced(f, s.copy());
            ItemStack left = Market.intoStores(level, v.id(), s.copy());
            int in = s.getCount() - left.getCount();
            if (in <= 0) continue;
            s.shrink(in);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            moved += in;
        }
        if (moved > 0) Budget.forget(v.id());
        return moved;
    }

    private static boolean boughtThis(Venture t, ItemStack s) {
        for (Want w : t.wants) if (w.matches(s)) return true;
        return false;
    }

    /** A trader lost on the road (died, or left the town): its donkey let go, the village's ground let go. */
    static void abandon(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        releaseStalls(level, f);
        Riding.caravanLost(f, level);
    }

    // ================================================================== a bought book laid on the town's best tool

    /**
     * The smith at its anvil, or the enchanter at its table: an enchanted book the trader brought home laid on the town's
     * best thing it suits (Mending on the best pick or blade or armour, Efficiency on the best pick, Protection on the best
     * chestplate), as an anvil lays one: only a thing the enchantment can go on, with nothing on it that will not sit with
     * it, and only where it makes the thing better. The book is used up. One a day. Returns what was done, or null.
     */
    @Nullable
    public static String layBook(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, now = level.getGameTime();
        if (LAID.getOrDefault(id, -1L) == day) return null;
        // Looked once a minute at most: the smith and the enchanter ask on every round of their work.
        Long looked = BOOK_LOOK.get(id);
        if (looked != null && now - looked < 1200L && now >= looked) return null;
        BOOK_LOOK.put(id, now);
        List<BlockPos> chests = Villages.storeChests(level, id);
        for (BlockPos bp : chests) {
            if (!(level.getBlockEntity(bp) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack book = c.getItem(i);
                if (!book.is(Items.ENCHANTED_BOOK)) continue;
                ItemEnchantments spells = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                if (spells.isEmpty()) continue;
                ItemStack target = null;
                Container in = null;
                double best = 0;
                for (BlockPos tp : chests) {
                    if (!(level.getBlockEntity(tp) instanceof Container tc)) continue;
                    for (int j = 0; j < tc.getContainerSize(); j++) {
                        ItemStack s = tc.getItem(j);
                        if (s.isEmpty() || s.getCount() != 1 || s.is(Items.ENCHANTED_BOOK) || s.is(Items.BOOK) || !betters(s, spells)) continue;
                        double worth = Prices.each(s.getItem()) + (s.isEnchanted() ? 1 : 0);
                        if (worth > best) { best = worth; target = s; in = tc; }
                    }
                }
                if (target == null) continue;
                String was = target.getHoverName().getString().toLowerCase(Locale.ROOT);
                for (var en : spells.entrySet()) {
                    Holder<Enchantment> h = en.getKey();
                    if (!h.value().canEnchant(target)) continue;
                    ItemEnchantments on = target.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                    if (!EnchantmentHelper.isEnchantmentCompatible(otherThan(on, h), h)) continue;
                    int have = on.getLevel(h), lvl = en.getIntValue();
                    int then = have == lvl ? Math.min(h.value().getMaxLevel(), lvl + 1) : Math.max(have, lvl);
                    if (then > have) target.enchant(h, then);
                }
                in.setChanged();
                String spell = label(book);
                c.removeItem(i, 1);
                c.setChanged();
                LAID.put(id, day);
                level.playSound(null, f.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6F, 1.0F);
                f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                String done = spell + " laid on the " + was;
                Villages.tell(id, day, f.displayNameCap() + " laid the " + spell + " book the trader bought on the town's " + was);
                LOG.info("[MCA-EMERALD] {} of {}: {}", f.displayNameCap(), Villages.name(id), done);
                return done;
            }
        }
        return null;
    }

    private static Set<Holder<Enchantment>> otherThan(ItemEnchantments on, Holder<Enchantment> h) {
        Set<Holder<Enchantment>> out = new HashSet<>(on.keySet());
        out.remove(h);
        return out;
    }

    /** Would any of these make this thing better: the enchantment goes on it, sits with what is on it, and is stronger? */
    static boolean betters(ItemStack s, ItemEnchantments spells) {
        ItemEnchantments on = s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var en : spells.entrySet()) {
            Holder<Enchantment> h = en.getKey();
            if (!h.value().canEnchant(s)) continue;
            if (!EnchantmentHelper.isEnchantmentCompatible(otherThan(on, h), h)) continue;
            int have = on.getLevel(h), lvl = en.getIntValue();
            int now = have == lvl ? Math.min(h.value().getMaxLevel(), lvl + 1) : Math.max(have, lvl);
            if (now > have) return true;
        }
        return false;
    }

    // ================================================================== talk, the card, the board, the book

    /** What the trader is doing, in its own words (FolkTalk). */
    static String doing(VillageFolkEntity f, RandomSource r) {
        Scouts.Expedition e = f.expedition();
        Venture t = e == null ? null : e.venture;
        UUID id = f.ownerId();
        if (t != null && id != null) {
            Hamlet h = t.hamlet == null ? null : find(book(id), t.hamlet);
            String where = h == null ? "the villagers" : h.name;
            return switch (t.stage) {
                case OUT -> h == null ? "Looking for villagers, " + e.heading() + ". There must be a village out here somewhere."
                    : "On the road to " + where + " with the town's goods. They buy " + buyersWords(h) + " there.";
                case LOOK -> "Looking round " + where + "'s stalls. Let's see who's about.";
                case SELL -> "Selling at " + where + ": " + t.earned + (t.earned == 1 ? " emerald" : " emeralds") + " so far.";
                case BUY -> "Buying at " + where + " with the emeralds. " + (t.wants.isEmpty() ? "" : "We want " + t.wants.get(0).words() + ".");
                case DONE -> "Done at " + where + ". Saying my goodbyes.";
                case HOME -> "On my way home from " + where + (t.trades > 0 ? ", with " + t.earned + " emeralds and " + t.got.size()
                    + (t.got.size() == 1 ? " purchase." : " purchases.") : ".");
            };
        }
        if (id == null) return "Between towns.";
        Seller mending = whoSells(id, Enchantments.MENDING);
        String hint = mending != null ? " " + mending.words() + " sells Mending." : "";
        return FolkTalk.pick(r, "At the Trading Post, going over the book." + hint,
            "Resting my feet. Out to the villagers again in the morning.",
            "Counting emeralds. " + Market.stock((ServerLevel) f.level(), id, s -> s.is(Items.EMERALD)) + " in the stores.");
    }

    /** "wheat and carrots", what a village's villagers were last seen buying. */
    static String buyersWords(Hamlet h) {
        Set<String> what = new java.util.LinkedHashSet<>();
        for (Seller s : h.folk) for (Deal d : s.deals) if (d.buys()) what.add(Bench.plural(new ItemStack(item(d.cost())).getHoverName().getString().toLowerCase(Locale.ROOT)));
        if (what.isEmpty()) return "whatever they buy";
        List<String> l = new ArrayList<>(what);
        if (l.size() > 3) l = l.subList(0, 3);
        return l.size() == 1 ? l.get(0) : String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    /** The villager the book has selling this enchantment (the cheapest), or null. */
    @Nullable
    static Seller whoSells(UUID village, ResourceKey<Enchantment> ench) {
        Seller best = null;
        int bestCost = Integer.MAX_VALUE;
        for (Hamlet h : book(village)) {
            for (Seller s : h.folk) {
                for (Deal d : s.deals) {
                    if (!d.ench().contains(ench.location().toString()) || d.buys()) continue;
                    if (d.costN() < bestCost) { bestCost = d.costN(); best = s; }
                }
            }
        }
        return best;
    }

    /** The trader's line on its card: its trips, the emeralds, what it brought home last. Null for anybody else. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.EMERALD || f.ownerId() == null) return null;
        UUID id = f.ownerId();
        List<String> log = log(id);
        int known = book(id).size();
        return trips(id) + (trips(id) == 1 ? " trip" : " trips") + "; " + earned(id) + " emeralds earned, " + spent(id) + " spent; knows "
            + known + (known == 1 ? " village" : " villages") + " of villagers" + (log.isEmpty() ? "" : "; last: " + log.get(log.size() - 1));
    }

    /** For the board: the trader's news, in a line. Null if the town has no trade with the villagers. */
    @Nullable
    public static String boardLine(UUID village) {
        List<Hamlet> book = book(village);
        if (book.isEmpty() && traders(village, true).isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Trading Post: ");
        sb.append(book.size()).append(book.size() == 1 ? " village" : " villages").append(" of villagers known");
        sb.append("; ").append(earned(village)).append(" emeralds earned, ").append(spent(village)).append(" spent");
        for (VillageFolkEntity f : traders(village, true)) {
            Scouts.Expedition e = f.expedition();
            if (e != null && e.venture != null) {
                Hamlet h = e.venture.hamlet == null ? null : find(book, e.venture.hamlet);
                sb.append("; ").append(f.displayNameCap()).append(e.returning() ? " is on the way home" : h == null ? " is out looking for villagers"
                    : " is away at " + h.name);
                break;
            }
        }
        for (Hamlet h : book) {
            if (h.raided >= 0 && h.raided >= (long) Math.max(0, h.visited - 1)) {
                sb.append("; pillagers were raiding ").append(h.name).append(" on day ").append(h.raided + 1);
                break;
            }
        }
        List<String> log = log(village);
        if (!log.isEmpty()) sb.append("; ").append(log.get(log.size() - 1));
        return sb.append('.').toString();
    }

    /** The trader's book's notes on what works (TradeBooks): the villages it knows, who sells Mending, the account. */
    public static List<String> bookNotes(UUID village) {
        List<String> out = new ArrayList<>();
        List<Hamlet> book = book(village);
        if (!book.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Hamlet h : book) names.add(h.name);
            out.add("We know " + book.size() + (book.size() == 1 ? " village" : " villages") + " of villagers: "
                + String.join(", ", names.subList(0, Math.min(5, names.size()))) + ".");
        }
        Seller m = whoSells(village, Enchantments.MENDING);
        if (m != null) {
            for (Hamlet h : book) {
                if (!h.folk.contains(m)) continue;
                for (Deal d : m.deals) {
                    if (d.ench().contains("mending")) {
                        out.add(m.words() + " at " + h.name + " sells Mending for " + d.costN() + " emeralds" + (d.cost2().isEmpty() ? "" : " and a book")
                            + ". Go back to " + m.name + ": a villager you trade with gets better.");
                        break;
                    }
                }
                break;
            }
        }
        int trips = trips(village), earned = earned(village), spent = spent(village);
        if (trips > 0) out.add("Over " + trips + (trips == 1 ? " trip" : " trips") + " we've earned " + earned + " emeralds for the town's surplus and spent "
            + spent + " on what the town wanted.");
        Seller best = null;
        String bestAt = "";
        for (Hamlet h : book) for (Seller s : h.folk) if (best == null || s.dealt > best.dealt) { best = s; bestAt = h.name; }
        if (best != null && best.dealt > 0) out.add("Our best customer is " + best.words() + " at " + bestAt + ": " + best.dealt + " trades.");
        return out;
    }

    /** Is something said to the trader meant for the trade: an ask, or a question about the villagers? */
    public static boolean meant(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("find us") || t.contains("buy us") || t.contains("get us") || t.contains("bring us") || t.contains("fetch us")
            || t.contains("villager") || t.contains("emerald") || t.contains("trading post") || t.contains("what do they sell")
            || t.contains("mending") || t.contains("enchanted book") || t.contains("who sells");
    }

    /** The trader's answer: an ask taken onto the buying list, or what the villagers sell. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level)) return "I'm between towns just now.";
        String t = text.toLowerCase(Locale.ROOT);
        boolean ask = t.contains("find us") || t.contains("buy us") || t.contains("get us") || t.contains("bring us") || t.contains("fetch us")
            || t.contains("buy a") || t.contains("buy some") || t.contains("can you buy") || t.contains("could you buy");
        if (ask) {
            String key = askedFor(level, text);
            if (key == null) return "I'd gladly look, " + p.getName().getString() + ", but I don't know what you mean. A Mending book? A bell? A map?";
            Want w = wantFor(level, key, true);
            if (w == null) return "That's not something I can buy from the villagers, I'm afraid.";
            List<String> asks = asks(id);
            if (!asks.contains(key)) {
                asks.add(0, key);
                while (asks.size() > 4) asks.remove(asks.size() - 1);
                asks(id, asks);
            }
            String who = "";
            for (Hamlet h : book(id)) {
                for (Seller s : h.folk) {
                    for (Deal d : s.deals) {
                        if (w.matches(d) && !d.buys()) {
                            who = " " + s.words() + " at " + h.name + " " + dealWords(d) + ".";
                            break;
                        }
                    }
                    if (!who.isEmpty()) break;
                }
                if (!who.isEmpty()) break;
            }
            int held = Market.stock(level, id, s -> s.is(Items.EMERALD));
            return "I'll look for " + w.words() + " on my next trip, " + p.getName().getString() + "." + (who.isEmpty()
                ? " I don't know yet who sells one, but I'll ask round the villagers." : who)
                + " We've " + held + (held == 1 ? " emerald" : " emeralds") + " put by.";
        }
        // What the villagers sell, and buy.
        List<Hamlet> book = book(id);
        if (book.isEmpty()) return "I don't know of any villagers near enough to trade with yet. The scouts may find some, or I will.";
        StringBuilder sb = new StringBuilder();
        for (Hamlet h : book) {
            if (h.folk.isEmpty()) continue;
            sb.append(sb.length() == 0 ? "" : " ").append("At ").append(h.name).append(": ");
            List<String> bits = new ArrayList<>();
            for (Seller s : h.folk) {
                for (Deal d : s.deals) {
                    if (d.buys() || d.out()) continue;
                    bits.add(s.name + " the " + s.trade + " " + dealWords(d).replaceFirst(" \\([^()]*\\)$", ""));   // (how many left: not said)
                    break;
                }
                if (bits.size() >= 3) break;
            }
            sb.append(bits.isEmpty() ? "they only buy, so far as I've seen" : String.join("; ", bits)).append('.');
            if (sb.length() > 300) break;
        }
        return sb.length() == 0 ? "I know where the villagers are, but I've not been to their stalls yet." : sb.toString();
    }

    // ================================================================== the Trading Post page

    /** The Trading Post page of the town's books (client/TradingPostPage): the emerald account, the buying list, the
     *  villages of villagers the town knows with their villagers and offers, the traders, and what they brought home. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        out.putInt("held", Market.stock(level, id, s -> s.is(Items.EMERALD)));
        out.putInt("earned", earned(id));
        out.putInt("spent", spent(id));
        out.putInt("trips", trips(id));
        out.putBoolean("open", wanted(id));
        out.putBoolean("apart", VanillaVillages.apart());
        out.putString("age", Villages.ageOf(id).label);
        out.putIntArray("heart", new int[]{ v.centre().getX(), v.centre().getZ() });
        long[] turned = TwoPeoples.turnedAway(id, level.getDayTime() / 24000L);
        out.putLong("turned", turned[1]);
        ListTag wants = new ListTag();
        for (Want w : wants(level, v)) wants.add(StringTag.valueOf(w.words() + (w.asked() ? " (asked for)" : "")));
        out.put("wants", wants);
        ListTag traders = new ListTag();
        for (VillageFolkEntity f : traders(id, true)) traders.add(StringTag.valueOf(f.displayNameCap() + ": " + doing(f, f.getRandom())));
        out.put("traders", traders);
        ListTag villages = new ListTag();
        for (Hamlet h : book(id)) {
            CompoundTag c = new CompoundTag();
            c.putString("name", h.name);
            c.putString("kind", h.kind);
            c.putInt("dist", (int) Math.sqrt(Scouts.flat(h.centre, v.centre())));
            c.putString("way", Guide.direction(v.centre(), h.centre));
            c.putIntArray("at", new int[]{ h.centre.getX(), h.centre.getZ() });
            c.putLong("visited", h.visited);
            c.putLong("raided", h.raided);
            c.putInt("trips", h.trips);
            c.putInt("earned", h.earned);
            c.putInt("spent", h.spent);
            ListTag folk = new ListTag();
            for (Seller s : h.folk) {
                CompoundTag sc = new CompoundTag();
                sc.putString("who", s.words());
                sc.putInt("level", s.level);
                sc.putInt("dealt", s.dealt);
                ListTag deals = new ListTag();
                for (Deal d : s.deals) deals.add(StringTag.valueOf(dealWords(d)));
                sc.put("deals", deals);
                folk.add(sc);
            }
            c.put("folk", folk);
            villages.add(c);
        }
        out.put("villages", villages);
        ListTag log = new ListTag();
        for (String l : log(id)) log.add(StringTag.valueOf(l));
        out.put("log", log);
        if (!wanted(id)) {
            String why = !VanillaVillages.apart() ? "The villagers are taken over in this world (replaceVillagers): there is nobody to trade with."
                : Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal() ? "A trader comes with the Stone Age."
                : Villages.headcount(id) < FROM ? "A trader comes once the town is " + FROM + " strong."
                : book(id).isEmpty() && VanillaVillages.nearest(level, v.centre(), REACH) == null
                    ? "No village of villagers is known within a day's walk: the scouts' atlas has none, and none lies plainly on the land round the town."
                : "The town has nothing to spare for the villagers just now: its own needs come first.";
            out.putString("why", why);
        }
        return out;
    }

    // ================================================================== the command

    /**
     * /village emerald: the trader's book in lines. {@code books} opens the town's books at the Trading Post page.
     * Operators: {@code now} takes a trader on if the town has none and sends it out now; {@code stage} sets a little
     * village of villagers (a bell, three stalls' workstations, a bed each, and a farmer, a librarian and a cleric with
     * their own offers) beside where it is run, and sends the town's trader to it, for the pictures.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("emerald")
            .executes(EmeraldTrader::cmdPage)
            .then(Commands.literal("books").executes(EmeraldTrader::cmdBooks))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                WANTED.put(v.id(), Math.max(1, reckon(level, v)));
                List<VillageFolkEntity> have = traders(v.id(), false);
                VillageFolkEntity f = have.isEmpty() ? appoint(level, v) : have.get(0);
                if (f == null) {
                    ctx.getSource().sendFailure(Component.literal("Nobody could be the trader."));
                    return 0;
                }
                if (f.expedition() != null) Scouts.abandon(level, f);
                boolean went = setOut(f, level, v, level.getDayTime() / 24000L, null);
                ctx.getSource().sendSuccess(() -> Component.literal("EMERALD " + f.displayNameCap() + (went ? " set out." : " stayed home (nothing to trade).")), false);
                return 1;
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = EmeraldStage.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        List<String> lines = new ArrayList<>();
        UUID id = v.id();
        lines.add("EMERALD " + Villages.name(id) + ": " + (wanted(id) ? "trading" : "no trade") + "; " + Market.stock(level, id, s -> s.is(Items.EMERALD))
            + " held, " + earned(id) + " earned, " + spent(id) + " spent, " + trips(id) + " trips");
        for (VillageFolkEntity f : traders(id, true)) lines.add("  trader " + f.displayNameCap() + ": " + doing(f, f.getRandom()));
        lines.add("  wants: " + wantsWords(wants(level, v)));
        for (Hamlet h : book(id)) {
            lines.add("  " + h.words() + ", " + (int) Math.sqrt(Scouts.flat(h.centre, v.centre())) + " blocks " + Guide.direction(v.centre(), h.centre)
                + " (" + h.trips + " trips" + (h.raided >= 0 ? ", raided day " + (h.raided + 1) : "") + ")");
            for (Seller s : h.folk) {
                StringBuilder sb = new StringBuilder("    " + s.words() + ":");
                for (Deal d : s.deals) sb.append(" ").append(dealWords(d)).append(";");
                lines.add(sb.toString());
            }
        }
        for (String l : log(id)) lines.add("  " + l);
        lines.add("  peoples: " + (VanillaVillages.apart() ? "kept apart" : "taken over") + "; " + VanillaVillages.describe(level, v.centre())
            + "; villagers' claims undone here " + TwoPeoples.turnedAway(id, level.getDayTime() / 24000L)[1]);
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdPage(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Trading Post");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    /**
     * The pictures (EmeraldStage): a trip to this village with these goods to sell and these emeralds and things to buy
     * with, handed to the trader by the stage (free, as a stage's things are), the town's buying list its own. The trading
     * at the village is the real thing.
     */
    static void stageTrip(ServerLevel level, Villages.Village v, VillageFolkEntity f, Hamlet h, Map<Item, Integer> goods, int emeralds,
                          List<ItemStack> besides) {
        Venture t = new Venture(Venture.Purpose.TRADE, h.key);
        t.wants.addAll(wants(level, v));
        for (Map.Entry<Item, Integer> g : goods.entrySet()) {
            ItemStack left = f.insertGiven(new ItemStack(g.getKey(), g.getValue()));
            int in = g.getValue() - left.getCount();
            if (in > 0) {
                t.toSell.put(g.getKey(), in);
                t.took.put(g.getKey(), in);
            }
        }
        f.insertGiven(new ItemStack(Items.EMERALD, emeralds));
        t.emeraldsTaken = emeralds;
        for (ItemStack s : besides) {
            f.insertGiven(s.copy());
            t.took.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        f.clearQueue();
        f.getNavigation().stop();
        Scouts.Expedition e = new Scouts.Expedition(v.id(), v.centre(),
            Scouts.bearingOf(h.centre.getX() - v.centre().getX(), h.centre.getZ() - v.centre().getZ()), h.centre);
        e.venture = t;
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        e.trail.add(f.blockPosition().immutable());
        f.expedition(e);
        WENT.put(f.getUUID(), level.getDayTime() / 24000L);
    }

    // ================================================================== tests

    /** Tests: trade at once, without the second's pause between trades or the look round on arriving. */
    public static void quickForTests(boolean on) { quickForTests = on; }

    /** Tests: whether the town wants a trader, reckoned now. */
    public static int reckonForTests(ServerLevel level, Villages.Village v) {
        int n = reckon(level, v);
        WANTED.put(v.id(), n);
        RECKONED.put(v.id(), level.getGameTime());
        return n;
    }

    /** Tests and the stage: the Trading Post put up on the lot the town's plan gives it (as its builders would raise it,
     *  all at once and free, as a stage's are), and noted as built. Its anchor, or null if the plan found no lot. */
    @Nullable
    public static BlockPos buildPostForTests(ServerLevel level, Villages.Village v) {
        Villages.Site s = Villages.siteFor(level, v.id(), POST);
        if (s == null) return null;
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, POST, s.anchor(), s.facing(),
            com.jrpetty.mcassistant.entity.goal.BuildGoal.halfOf(POST) + 2,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Villages.noteProject(v.id(), POST, level.getGameTime());
        return s.anchor();
    }

    /** Tests: where the trader stands at home. */
    public static BlockPos postForTests(Villages.Village v) {
        return post(v);
    }

    /** Tests: the town's choice of trader, taken on now. */
    @Nullable
    public static VillageFolkEntity appointForTests(ServerLevel level, Villages.Village v) {
        return appoint(level, v);
    }

    /** Tests: a village of villagers the town knows (into the book, as the trader's own find). */
    public static Hamlet knowForTests(Villages.Village v, VanillaVillages.Known k, long day) {
        List<Hamlet> book = book(v.id());
        Hamlet h = find(book, k.key());
        if (h == null) {
            h = hamlet(k, day, "the tests");
            book.add(h);
            save(v.id(), book);
        }
        return h;
    }

    /** Tests: set out now, to this village (by its key), or to look for one (null). Whether it went. */
    public static boolean setOutForTests(VillageFolkEntity f, ServerLevel level, @Nullable String to) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return false;
        WENT.remove(f.getUUID());
        return setOut(f, level, v, level.getDayTime() / 24000L, to);
    }

    /** Tests: the trader set down at the village it is going to, as though it had walked there, and arrived. */
    public static void arriveForTests(ServerLevel level, VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        if (e == null || e.venture == null || e.venture.hamlet == null || f.ownerId() == null) return;
        Hamlet h = find(book(f.ownerId()), e.venture.hamlet);
        if (h == null) return;
        BlockPos at = Scouts.surface(level, h.centre.offset(2, 0, 2));
        f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Riding.bringAlong(f, level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        arrive(level, f, e, e.venture, h);
    }

    /** Tests: the trader set down at home, and home (the goods in, the account kept). */
    public static void homeForTests(ServerLevel level, VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        if (e == null || e.venture == null || f.ownerId() == null) return;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        if (!e.returning) homeward(level, f, e, e.venture, "the tests called it home");
        BlockPos at = Scouts.surface(level, v.centre().offset(1, 0, 1));
        f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        home(level, f, e, e.venture, v);
    }

    /** Tests: one trade with this villager, through this offer of its own, as the trader makes one at its stall. */
    public static boolean tradeForTests(ServerLevel level, VillageFolkEntity f, Villager v, int offer) {
        Scouts.Expedition e = f.expedition();
        Venture t = e != null && e.venture != null ? e.venture : new Venture(Venture.Purpose.TRADE, null);
        MerchantOffer o = v.getOffers().get(offer);
        if (o.getResult().is(Items.EMERALD) && !t.toSell.containsKey(o.getCostA().getItem())) {
            t.toSell.put(o.getCostA().getItem(), f.countMatching(o.getItemCostA()::test));
        }
        return trade(level, f, v, o, t, null);
    }

    /** Tests: the trip under way, or null. */
    @Nullable
    public static Venture ventureForTests(VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        return e == null ? null : e.venture;
    }

    /** Tests: the buying list. */
    public static List<Want> wantsForTests(ServerLevel level, Villages.Village v) {
        return wants(level, v);
    }

    /** Tests: a player's ask, as said to the trader. */
    public static String askForTests(VillageFolkEntity f, Player p, String text) {
        return talk(f, p, text);
    }

    /** Tests: the account {earned, spent, trips}. */
    public static int[] accountForTests(UUID village) {
        return new int[]{ earned(village), spent(village), trips(village) };
    }

    /** Tests: a book laid on a tool now, by this folk (the smith, the enchanter). */
    @Nullable
    public static String layBookForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        LAID.remove(v.id());
        BOOK_LOOK.remove(v.id());
        return layBook(level, v, f);
    }
}
