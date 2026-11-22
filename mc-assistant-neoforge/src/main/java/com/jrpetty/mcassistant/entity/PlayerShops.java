package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [player15] A shop of the player's own: a building of theirs in a town, marked with a Shop Counter or a Shop Sign.
 * <ul>
 * <li><b>Marking it.</b> Set a counter (or hang a sign) in a building of yours in a town and it is your shop, one to a
 *     town: its screen opens at its setup (a name, what kind of shop, its hours) and its checklist (ShopNeeds: a counter,
 *     walls and a roof, a door reached from a street, somewhere for stock, the kind's own kit). Registering it with the
 *     town costs its licence (the business rates of a week, into the treasury), and it pays the rates every week out of
 *     its till as the town's businesses do.</li>
 * <li><b>Open.</b> Only once it has everything, and only at its hours. Till then its sign over the door says what it is
 *     missing ("Closed: needs a counter"), you are reminded while you build (a word over the hotbar near it), and the
 *     moment it is complete you are told it is ready to open.</li>
 * <li><b>Stock and prices.</b> Every chest, barrel, shelf and pot inside its walls is its stock, but those you mark back
 *     stock (the stockroom's: the shopkeeper fills the shelves from them). Prices by the lot on its screen, beside the
 *     going price; unpriced sells at the going price, 0 keeps a thing back.</li>
 * <li><b>Customers</b> (ShopCustomers): folk who want something it has walk to it in its hours, in at its door, look over
 *     its shelves, queue at its counter, pay into its till and go off with it, or say it's too dear and leave. Its books
 *     keep every sale, the week's takings, what sold out, what was too dear, and its customers' mood; its reputation grows
 *     with fair prices, full shelves and a keeper behind the counter, and brings more of them.</li>
 * <li><b>A shopkeeper</b> (ShopKeepers): a folk you take on minds the counter in its hours, fills the shelves from the
 *     stockroom and greets whoever comes, for its wage out of the till each morning.</li>
 * <li><b>Closing</b> it lets its keeper go and pays you its till; everything in its chests was yours all along and stays.
 *     Nothing in it may be taken by anybody else: no folk takes from it without paying, and no other player may open its
 *     stock or break its counter.</li>
 * </ul>
 * Kept in the town's ledger (notes "pshop/&lt;player&gt;"), so it outlasts a restart.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerShops {

    private PlayerShops() {}

    /** Sales and turnings-down kept on the books. */
    static final int SALES_KEPT = 60, DEAR_KEPT = 16, WEEK = 7;
    /** Default hours: six in the morning till eight at night (day ticks). */
    static final int OPENS = 0, CLOSES = 14000;
    /** How often a shop's room is read again (ticks), and its sign brought up to date. */
    static final int RESURVEY = 200;
    private static final String NOTE = "pshop/";
    static final String TAG = "mca_pshop";

    /** A shop: whose, where, what, and its books. */
    public static final class Shop {
        public UUID owner = new UUID(0, 0), village = new UUID(0, 0);
        public String ownerName = "", name = "", kind = "general";
        public BlockPos anchor = BlockPos.ZERO;
        public int opens = OPENS, closes = CLOSES;
        public boolean setUp, tell = true, licensed;
        public final Map<String, Integer> prices = new LinkedHashMap<>();
        public final Set<BlockPos> back = new HashSet<>();
        public int till, soldAll, takenAll, ratesOwed, visitsToday, servedToday;
        public long since, ratesDay, weekDay, visitDay;
        public final int[] weekCount = new int[WEEK], weekCoin = new int[WEEK];
        public final List<Sale> sales = new ArrayList<>();
        public final List<Dear> dear = new ArrayList<>();
        public final Map<String, Long> soldOut = new LinkedHashMap<>();
        public double rep = 0.5;
        /** The week's customers: pleased with a fair price, put off by a dear one, finding nothing they wanted. */
        public int fair, dearN, bare, greeted;
        @Nullable public UUID keeper;
        public String keeperName = "";
        public int wage, owed, kOpens = OPENS, kCloses = CLOSES;
        public long keeperSince;
        @Nullable public String firm;
        @Nullable public UUID display;
        // read off the building, not kept
        @Nullable transient ShopNeeds.Survey survey;
        transient List<ShopNeeds.Need> needs = List.of();
        transient boolean wasReady;

        public ShopNeeds.Kind kindOf() {
            return ShopNeeds.Kind.of(kind);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("owner", owner);
            t.putUUID("village", village);
            t.putString("ownerName", ownerName);
            t.putString("name", name);
            t.putString("kind", kind);
            t.putLong("anchor", anchor.asLong());
            t.putInt("opens", opens);
            t.putInt("closes", closes);
            t.putBoolean("setUp", setUp);
            t.putBoolean("tell", tell);
            t.putBoolean("licensed", licensed);
            CompoundTag pr = new CompoundTag();
            for (Map.Entry<String, Integer> e : prices.entrySet()) pr.putInt(e.getKey(), e.getValue());
            t.put("prices", pr);
            t.putLongArray("back", back.stream().mapToLong(BlockPos::asLong).toArray());
            t.putInt("till", till);
            t.putInt("soldAll", soldAll);
            t.putInt("takenAll", takenAll);
            t.putInt("ratesOwed", ratesOwed);
            t.putLong("since", since);
            t.putLong("ratesDay", ratesDay);
            t.putLong("weekDay", weekDay);
            t.putIntArray("weekCount", weekCount);
            t.putIntArray("weekCoin", weekCoin);
            ListTag sl = new ListTag();
            for (Sale s : sales) sl.add(s.save());
            t.put("sales", sl);
            ListTag dl = new ListTag();
            for (Dear d : dear) dl.add(d.save());
            t.put("dear", dl);
            CompoundTag so = new CompoundTag();
            for (Map.Entry<String, Long> e : soldOut.entrySet()) so.putLong(e.getKey(), e.getValue());
            t.put("soldOut", so);
            t.putDouble("rep", rep);
            t.putIntArray("mood", new int[]{ fair, dearN, bare, greeted });
            if (keeper != null) t.putUUID("keeper", keeper);
            t.putString("keeperName", keeperName);
            t.putInt("wage", wage);
            t.putInt("owed", owed);
            t.putLong("keeperSince", keeperSince);
            t.putInt("kOpens", kOpens);
            t.putInt("kCloses", kCloses);
            if (firm != null) t.putString("firm", firm);
            if (display != null) t.putUUID("display", display);
            return t;
        }

        static Shop load(CompoundTag t) {
            Shop s = new Shop();
            s.owner = t.getUUID("owner");
            s.village = t.getUUID("village");
            s.ownerName = t.getString("ownerName");
            s.name = t.getString("name");
            s.kind = t.getString("kind");
            s.anchor = BlockPos.of(t.getLong("anchor"));
            s.opens = t.getInt("opens");
            s.closes = t.getInt("closes");
            s.setUp = t.getBoolean("setUp");
            s.tell = t.getBoolean("tell");
            s.licensed = t.getBoolean("licensed");
            CompoundTag pr = t.getCompound("prices");
            for (String k : pr.getAllKeys()) s.prices.put(k, pr.getInt(k));
            for (long l : t.getLongArray("back")) s.back.add(BlockPos.of(l));
            s.till = t.getInt("till");
            s.soldAll = t.getInt("soldAll");
            s.takenAll = t.getInt("takenAll");
            s.ratesOwed = t.getInt("ratesOwed");
            s.since = t.getLong("since");
            s.ratesDay = t.getLong("ratesDay");
            s.weekDay = t.getLong("weekDay");
            ints(t.getIntArray("weekCount"), s.weekCount);
            ints(t.getIntArray("weekCoin"), s.weekCoin);
            for (Tag e : t.getList("sales", Tag.TAG_COMPOUND)) s.sales.add(Sale.load((CompoundTag) e));
            for (Tag e : t.getList("dear", Tag.TAG_COMPOUND)) s.dear.add(Dear.load((CompoundTag) e));
            CompoundTag so = t.getCompound("soldOut");
            for (String k : so.getAllKeys()) s.soldOut.put(k, so.getLong(k));
            s.rep = t.contains("rep") ? t.getDouble("rep") : 0.5;
            int[] m = t.getIntArray("mood");
            if (m.length == 4) { s.fair = m[0]; s.dearN = m[1]; s.bare = m[2]; s.greeted = m[3]; }
            if (t.hasUUID("keeper")) s.keeper = t.getUUID("keeper");
            s.keeperName = t.getString("keeperName");
            s.wage = t.getInt("wage");
            s.owed = t.getInt("owed");
            s.keeperSince = t.getLong("keeperSince");
            if (t.contains("kOpens")) { s.kOpens = t.getInt("kOpens"); s.kCloses = t.getInt("kCloses"); }
            if (t.contains("firm")) s.firm = t.getString("firm");
            if (t.hasUUID("display")) s.display = t.getUUID("display");
            return s;
        }
    }

    /** A sale: the day, who bought, what (its key), how many, for how much. */
    public record Sale(long day, String who, String key, int n, int price) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("d", day);
            t.putString("w", who);
            t.putString("k", key);
            t.putInt("n", n);
            t.putInt("p", price);
            return t;
        }

        static Sale load(CompoundTag t) {
            return new Sale(t.getLong("d"), t.getString("w"), t.getString("k"), t.getInt("n"), t.getInt("p"));
        }
    }

    /** Thought too dear: the day, who, what, the price asked and the going price. */
    public record Dear(long day, String who, String key, int price, int going) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("d", day);
            t.putString("w", who);
            t.putString("k", key);
            t.putInt("p", price);
            t.putInt("g", going);
            return t;
        }

        static Dear load(CompoundTag t) {
            return new Dear(t.getLong("d"), t.getString("w"), t.getString("k"), t.getInt("p"), t.getInt("g"));
        }
    }

    // ------------------------------------------------------------------ the register

    private static final Map<UUID, Map<UUID, Shop>> TOWNS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TOWNS.clear();
        TOLD.clear();
        LAST_TICK.clear();
        MORNING.clear();
        ShopCustomers.resetForTests();
        ShopKeepers.resetForTests();
    }

    /** The shops of a town, by owner (read from the ledger once). */
    static Map<UUID, Shop> town(UUID village) {
        return TOWNS.computeIfAbsent(village, v -> {
            Map<UUID, Shop> m = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : Ledger.notes(v).entrySet()) {
                if (!e.getKey().startsWith(NOTE) || e.getValue() == null || e.getValue().isEmpty()) continue;
                try {
                    Shop s = Shop.load(TagParser.parseTag(e.getValue()));
                    m.put(s.owner, s);
                } catch (Exception ex) {
                    com.mojang.logging.LogUtils.getLogger().warn("[player15] a shop's note could not be read: {}", ex.toString());
                }
            }
            return m;
        });
    }

    static void save(Shop s) {
        town(s.village).put(s.owner, s);
        Ledger.note(s.village, NOTE + s.owner, s.save().toString());
    }

    static void forget(Shop s) {
        Map<UUID, Shop> m = TOWNS.get(s.village);
        if (m != null) m.remove(s.owner);
        Ledger.forget(s.village, NOTE + s.owner);
    }

    public static List<Shop> all(UUID village) {
        return new ArrayList<>(town(village).values());
    }

    @Nullable
    public static Shop of(UUID village, UUID owner) {
        return town(village).get(owner);
    }

    /** The player's shop in the town they stand in (or the nearest town that has one of theirs). */
    @Nullable
    public static Shop mine(ServerPlayer p) {
        Villages.Village v = PlayerLife.here(p);
        if (v != null && of(v.id(), p.getUUID()) != null) return of(v.id(), p.getUUID());
        for (Villages.Village w : Villages.every()) {
            Shop s = of(w.id(), p.getUUID());
            if (s != null) return s;
        }
        return null;
    }

    /** The shop this block belongs to: its counter or sign, or a chest of its stock. */
    @Nullable
    public static Shop at(ServerLevel level, BlockPos pos) {
        Villages.Village v = Villages.nearest(level, pos, 200);
        if (v == null) return null;
        for (Shop s : town(v.id()).values()) {
            if (s.anchor.equals(pos)) return s;
            ShopNeeds.Survey sv = s.survey;
            if (sv != null && (sv.counters.contains(pos) || sv.stock.contains(pos))) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ the building, read

    /** The room as last read (read again if it is stale). */
    static ShopNeeds.Survey survey(ServerLevel level, Shop s) {
        if (s.survey == null || level.getGameTime() - s.survey.at > RESURVEY || level.getGameTime() < s.survey.at) {
            s.survey = ShopNeeds.survey(level, s.anchor);
            s.needs = ShopNeeds.check(level, Villages.get(s.village), s.survey, s.kindOf(), s.back);
        }
        return s.survey;
    }

    /** Read the building again now (a block of it changed, a button pressed). */
    public static void resurvey(ServerLevel level, Shop s) {
        s.survey = null;
        survey(level, s);
    }

    public static List<ShopNeeds.Need> needs(ServerLevel level, Shop s) {
        survey(level, s);
        return s.needs;
    }

    public static boolean ready(ServerLevel level, Shop s) {
        return level.isLoaded(s.anchor) && ShopNeeds.ready(needs(level, s));
    }

    /** How a shop stands now. */
    public enum State {
        UNSET("Not open yet: set it up"), INCOMPLETE("Closed"), SHUT("Closed"), OPEN("Open");

        public final String words;

        State(String words) {
            this.words = words;
        }
    }

    public static State state(ServerLevel level, Shop s) {
        if (!level.isLoaded(s.anchor)) return State.SHUT;
        if (!ShopNeeds.ready(needs(level, s))) return State.INCOMPLETE;
        if (!s.setUp) return State.UNSET;
        return inHours(level, s.opens, s.closes) ? State.OPEN : State.SHUT;
    }

    /** "Open", "Closed: needs a counter", "Closed till 7 in the morning", "Not open yet: set it up". */
    public static String stateWords(ServerLevel level, Shop s) {
        State st = state(level, s);
        return switch (st) {
            case INCOMPLETE -> "Closed: " + ShopNeeds.missing(s.needs);
            case SHUT -> level.isLoaded(s.anchor) ? "Closed till " + clock(s.opens) : "Closed";
            case OPEN -> "Open till " + clock(s.closes);
            default -> st.words;
        };
    }

    static boolean inHours(ServerLevel level, int opens, int closes) {
        int t = (int) Math.floorMod(level.getDayTime(), 24000L);
        return opens <= closes ? t >= opens && t < closes : t >= opens || t < closes;
    }

    /** A day tick as the clock: 0 is six in the morning. */
    static String clock(int tick) {
        int h = Math.floorMod(tick / 1000 + 6, 24);
        return h == 0 ? "midnight" : h == 12 ? "noon" : h < 12 ? h + " in the morning" : (h - 12) + (h < 18 ? " in the afternoon" : " at night");
    }

    /** The chests customers buy from: inside the walls, not marked back stock. */
    static List<Container> shelves(ServerLevel level, Shop s) {
        List<Container> out = new ArrayList<>();
        for (BlockPos p : survey(level, s).stock) {
            if (s.back.contains(p) || !level.isLoaded(p)) continue;
            if (level.getBlockEntity(p) instanceof Container c) out.add(c);
        }
        return out;
    }

    /** The stockroom's chests (back stock). */
    static List<Container> stockroom(ServerLevel level, Shop s) {
        List<Container> out = new ArrayList<>();
        for (BlockPos p : survey(level, s).stock) {
            if (!s.back.contains(p) || !level.isLoaded(p)) continue;
            if (level.getBlockEntity(p) instanceof Container c) out.add(c);
        }
        return out;
    }

    /** What the shelves hold, kind by kind: its key, a sample and how many. */
    static Map<String, ItemStack> wares(ServerLevel level, Shop s, Map<String, Integer> counts, boolean withBack) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        List<Container> boxes = new ArrayList<>(shelves(level, s));
        if (withBack) boxes.addAll(stockroom(level, s));
        for (Container c : boxes) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack x = c.getItem(i);
                if (x.isEmpty() || Market.isCoin(x)) continue;
                String k = PlayerStalls.key(x);
                out.putIfAbsent(k, x.copyWithCount(1));
                counts.merge(k, x.getCount(), Integer::sum);
            }
        }
        return out;
    }

    /** What it asks for a lot: the player's price, else the going price. */
    static int asking(ServerLevel level, Shop s, String key, ItemStack sample) {
        Integer set = s.prices.get(key);
        return set != null ? set : PlayerStalls.going(level, s.village, sample);
    }

    // ------------------------------------------------------------------ marking, opening, closing

    /** A counter or a sign set down by a player: their shop in this town, made or added to. */
    public static void placed(ServerLevel level, BlockPos pos, ServerPlayer p, boolean counter) {
        Villages.Village v = Villages.nearest(level, pos, 200);
        String what = counter ? "counter" : "sign";
        if (v == null) {
            p.sendSystemMessage(Component.literal("A shop is kept in a town: set the " + what + " in a building of yours inside one.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Shop s = of(v.id(), p.getUUID());
        if (s != null && level.isLoaded(s.anchor) && !(s.anchor.equals(pos))) {
            ShopNeeds.Survey sv = ShopNeeds.survey(level, s.anchor);
            if (sv.room.isEmpty() || !(sv.counters.contains(pos) || touches(sv, pos))) {
                if (anchorStands(level, s)) {
                    p.sendSystemMessage(Component.literal("You keep a shop in " + Villages.name(v.id()) + " already, " + s.name + " at "
                        + s.anchor.getX() + ", " + s.anchor.getZ() + ": one to a town. This " + what + " is only furniture.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                s.anchor = pos.immutable();                                 // its old mark is gone: it moves here
            }
            if (counter && !s.anchor.equals(pos) && !level.getBlockState(s.anchor).is(McAssistantMod.SHOP_COUNTER.get())) s.anchor = pos.immutable();
            resurvey(level, s);
            save(s);
            remind(level, s, p, true);
            PlayerLife.open(p, "shop", LifePage.args("tab", "setup"));
            return;
        }
        if (s == null) {
            s = new Shop();
            s.owner = p.getUUID();
            s.village = v.id();
            s.ownerName = p.getGameProfile().getName();
            s.name = s.ownerName + (s.ownerName.endsWith("s") ? "' Shop" : "'s Shop");
            s.since = today(level);
            s.weekDay = s.since;
            s.ratesDay = s.since;
        }
        s.anchor = pos.immutable();
        resurvey(level, s);
        s.wasReady = ShopNeeds.ready(s.needs);
        save(s);
        level.playSound(null, pos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.BLOCKS, 0.8F, 1.0F);
        p.sendSystemMessage(Component.literal("This is your shop now. Give it a name and its hours, and see what it still needs.").withStyle(ChatFormatting.GOLD));
        remind(level, s, p, true);
        display(level, s);
        PlayerLife.open(p, "shop", LifePage.args("tab", "setup"));
    }

    private static boolean touches(ShopNeeds.Survey sv, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (sv.room.contains(pos.relative(d))) return true;
        return sv.room.contains(pos);
    }

    static boolean anchorStands(ServerLevel level, Shop s) {
        if (!level.isLoaded(s.anchor)) return true;
        var st = level.getBlockState(s.anchor);
        return st.is(McAssistantMod.SHOP_COUNTER.get()) || st.is(McAssistantMod.SHOP_SIGN.get());
    }

    /** A counter or sign taken away: the shop moves to another counter of its own, or waits for one. */
    public static void removed(ServerLevel level, BlockPos pos) {
        Shop s = at(level, pos);
        if (s == null) return;
        if (s.anchor.equals(pos)) {
            ShopNeeds.Survey sv = s.survey;
            if (sv != null) {
                for (BlockPos c : sv.counters) {
                    if (!c.equals(pos) && level.getBlockState(c).is(McAssistantMod.SHOP_COUNTER.get())) { s.anchor = c; break; }
                }
            }
        }
        s.survey = null;
        save(s);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(s.owner);
        if (p != null && !anchorStands(level, s)) {
            p.sendSystemMessage(Component.literal(s.name + " has lost its counter and sign: it's shut till you set one again. Its till is kept for you.").withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Right-click on a counter or sign. */
    public static void openAt(ServerPlayer p, BlockPos pos) {
        Shop s = at(p.serverLevel(), pos);
        if (s == null) {
            placed(p.serverLevel(), pos, p, p.serverLevel().getBlockState(pos).is(McAssistantMod.SHOP_COUNTER.get()));
            return;
        }
        if (!s.owner.equals(p.getUUID())) {
            p.sendSystemMessage(Component.literal(s.name + ", " + s.ownerName + "'s shop: " + stateWords(p.serverLevel(), s) + ".").withStyle(ChatFormatting.GRAY));
            return;
        }
        PlayerLife.open(p, "shop", LifePage.args("tab", s.setUp ? "shop" : "setup"));
    }

    /** The setup confirmed: its name, kind and hours; the licence paid the first time. Returns what to say ("!" refused). */
    static String setUp(ServerLevel level, ServerPlayer p, Shop s, String name, String kind, int opens, int closes) {
        name = clean(name);
        if (name.isEmpty()) name = s.name;
        if (name.length() > 32) name = name.substring(0, 32);
        s.name = name;
        s.kind = ShopNeeds.Kind.of(kind).id;
        s.opens = Math.floorMod(opens, 24000);
        s.closes = Math.floorMod(closes, 24000);
        if (!s.licensed) {
            int fee = licence(s.village);
            if (Market.coinsHeld(p) < fee) {
                save(s);
                return "!The licence is " + PlayerLife.coins(fee) + " and you have " + Market.coinsHeld(p)
                    + ". Sell something at the town's shop, take a shift, or borrow at the bank.";
            }
            Market.payOut(p, fee);
            Ledger.addCoins(s.village, fee);
            CoinCensus.playerIn(s.village, fee, p, PlayerFinance.Cat.FEES, "the licence for " + s.name);   // [join21]
            TownBudget.in(s.village, "fees", fee * 100L);
            s.licensed = true;
            Villages.tell(s.village, today(level), s.ownerName + " opened a shop of their own, " + s.name);
        }
        boolean first = !s.setUp;
        s.setUp = true;
        resurvey(level, s);
        save(s);
        com.jrpetty.mcassistant.Guard.run("shop on the register", () -> PropertyDesk.register(level, s));   // [join21] the property register (PropertyMarket)
        display(level, s);
        if (!ShopNeeds.ready(s.needs)) return "Registered. It opens once it has everything: " + ShopNeeds.missing(s.needs) + ".";
        return first ? "Registered with the town, and ready to open! " + stateWords(level, s) + "." : "Saved.";
    }

    /** The licence to keep a shop: a week of the business rates, three coins at least. */
    static int licence(UUID village) {
        return Math.max(3, TaxBook.ratesCoins(village));
    }

    /** The till into the player's pack. */
    static int takeTill(ServerLevel level, Player p, Shop s) {
        int n = s.till;
        if (n <= 0) return 0;
        s.till = 0;
        for (int left = n; left > 0; left -= 64) Dealings.giveCoins(p, Math.min(64, left));
        CoinCensus.playerOut(s.village, n, p, PlayerFinance.Cat.TILL, s.name + "'s till");   // [join21] the takings were booked as they came in
        Earnings.record(p, s.village, Earnings.Source.STALL, n, s.name + "'s till", "of takings");
        save(s);
        return n;
    }

    /** The shop closed: its keeper let go, its till paid out, its sign gone; the goods in its chests stay where they are. */
    static String close(ServerLevel level, ServerPlayer p, Shop s) {
        int till = takeTill(level, p, s);
        if (s.keeper != null) ShopKeepers.letGo(level, s, "the shop closed");
        removeDisplay(level, s);
        forget(s);
        Villages.tell(s.village, today(level), s.ownerName + " closed " + s.name);
        return s.name + " is closed" + (till > 0 ? ", and its till (" + PlayerLife.coins(till) + ") is in your pack" : "")
            + ". Everything in its chests stays yours.";
    }

    // ------------------------------------------------------------------ a sale, booked

    /** A sale at the shop: the coin into its till (less the market's toll), its books, its owner told. */
    static void booked(ServerLevel level, Shop s, String who, ItemStack got, int price, double going) {
        long day = today(level);
        roll(s, day);
        int toll = TaxBook.toll(s.village, price);
        s.till += price - toll;
        PlayerFinance.internal(s.owner, s.village, (price - toll) * 100L, PlayerFinance.Cat.SHOP, who + " bought " + got.getCount() + " "
            + PlayerStalls.nameOf(got).toLowerCase(Locale.ROOT) + " at " + s.name + (toll > 0 ? " (less " + toll + " toll)" : ""));   // [join21] the owner's books, as it comes in
        s.soldAll += got.getCount();
        s.takenAll += price;
        s.weekCount[0] += got.getCount();
        s.weekCoin[0] += price;
        s.servedToday++;
        String key = PlayerStalls.key(got);
        s.sales.add(0, new Sale(day, who, key, got.getCount(), price));
        while (s.sales.size() > SALES_KEPT) s.sales.remove(s.sales.size() - 1);
        double ratio = going <= 0 ? 1 : price / going;
        s.fair++;
        s.rep = Math.min(1.0, s.rep + (ratio <= 1.0 ? 0.012 : 0.006));
        Map<String, Integer> left = new LinkedHashMap<>();
        wares(level, s, left, false);
        if (left.getOrDefault(key, 0) <= 0) s.soldOut.put(key, day);
        save(s);
        Businesses.stallSold(level, s.village, s.owner, s.name, got, got.getCount(), price, toll);
        if (s.tell) {
            tellOwner(level, s.owner, who + " bought " + got.getCount() + " " + PlayerStalls.nameOf(got).toLowerCase(Locale.ROOT) + " for "
                + PlayerLife.coins(price) + " at " + s.name + ". Till: " + s.till + ".");
        }
        display(level, s);
    }

    /** Too dear: on the books once a folk a ware a day, a word to the owner once a ware a day. */
    static void tooDear(ServerLevel level, Shop s, String who, ItemStack sample, int price, int going) {
        long day = today(level);
        String key = PlayerStalls.key(sample);
        for (Dear d : s.dear) if (d.day() == day && d.who().equals(who) && d.key().equals(key)) return;
        s.dear.add(0, new Dear(day, who, key, price, going));
        while (s.dear.size() > DEAR_KEPT) s.dear.remove(s.dear.size() - 1);
        s.dearN++;
        s.rep = Math.max(0.0, s.rep - 0.01);
        save(s);
        String told = s.owner + "/" + key + "/" + day;
        if (s.tell && TOLD.add(told)) {
            tellOwner(level, s.owner, who + " thought " + PlayerLife.coins(price) + " for " + PlayerStalls.nameOf(sample).toLowerCase(Locale.ROOT)
                + " too dear at " + s.name + " (the going price is " + going + ").");
        }
    }

    /** A customer found nothing it wanted on the shelves. */
    static void bare(ServerLevel level, Shop s) {
        s.bare++;
        s.rep = Math.max(0.0, s.rep - 0.004);
        save(s);
    }

    private static final Set<String> TOLD = ConcurrentHashMap.newKeySet();

    static void roll(Shop s, long today) {
        if (s.weekDay >= today) return;
        int shift = (int) Math.min(WEEK, today - s.weekDay);
        for (int i = WEEK - 1; i >= 0; i--) {
            s.weekCount[i] = i - shift >= 0 ? s.weekCount[i - shift] : 0;
            s.weekCoin[i] = i - shift >= 0 ? s.weekCoin[i - shift] : 0;
        }
        s.weekDay = today;
    }

    static int week(int[] a) {
        int n = 0;
        for (int x : a) n += x;
        return n;
    }

    // ------------------------------------------------------------------ the day

    private static final Map<UUID, Long> LAST_TICK = new ConcurrentHashMap<>(), MORNING = new ConcurrentHashMap<>();

    /** Every couple of seconds for a town: each shop read again when stale, its sign up to date, its owner reminded. */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Long last = LAST_TICK.get(v.id());
        if (last != null && now - last < 40 && now >= last) return;
        LAST_TICK.put(v.id(), now);
        com.jrpetty.mcassistant.Guard.run("player builds", () -> BuildDesk.tick(level, v));   // the buildings going up for players
        if (town(v.id()).isEmpty()) return;
        long day = today(level);
        if (MORNING.getOrDefault(v.id(), -1L) != day) {
            MORNING.put(v.id(), day);
            com.jrpetty.mcassistant.Guard.run("player shops' morning", () -> morning(level, v, day));
        }
        for (Shop s : all(v.id())) {
            if (!level.isLoaded(s.anchor)) continue;
            survey(level, s);
            boolean ready = ShopNeeds.ready(s.needs);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(s.owner);
            if (ready && !s.wasReady && p != null) {
                notify(p, "Your shop is ready to open!");
                p.sendSystemMessage(Component.literal(s.name + " is ready to open" + (s.setUp ? ": " + stateWords(level, s) + "." : ": give it a name and hours on its screen.")).withStyle(ChatFormatting.GREEN));
                level.playSound(null, s.anchor, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.2F);
            }
            s.wasReady = ready;
            if (p != null && !ready) remind(level, s, p, false);
            display(level, s);
            if (s.ratesDay + WEEK <= day && s.licensed) rates(level, s, day);
        }
        ShopKeepers.tick(level, v);
    }

    /** The week's business rates out of the till into the treasury; what the till can't pay is owed. */
    static void rates(ServerLevel level, Shop s, long day) {
        int due = TaxBook.ratesCoins(s.village) + s.ratesOwed;
        int paid = Math.min(due, s.till);
        s.till -= paid;
        s.ratesOwed = due - paid;
        s.ratesDay = day;
        if (paid > 0) {
            Ledger.addCoins(s.village, paid);
            TownBudget.in(s.village, "rates", paid * 100L);
            PlayerFinance.internal(s.owner, s.village, -paid * 100L, PlayerFinance.Cat.TAXES, "rates for " + s.name + ", out of its till");   // [join21]
        }
        save(s);
        if (s.tell) tellOwner(level, s.owner, s.name + " paid " + PlayerLife.coins(paid) + " in rates" + (s.ratesOwed > 0 ? "; " + s.ratesOwed + " still owed" : "") + ".");
    }

    /** The owner near an unfinished shop: what it needs, over the hotbar (every few seconds), and a toast now and then. */
    static void remind(ServerLevel level, Shop s, ServerPlayer p, boolean now) {
        if (p.distanceToSqr(s.anchor.getX(), s.anchor.getY(), s.anchor.getZ()) > 24 * 24) return;
        String m = ShopNeeds.missing(s.needs);
        if (m.isEmpty()) return;
        String line = "This shop " + m + (m.equals("needs a counter") ? " (so customers can pay)" : "") + ".";
        p.displayClientMessage(Component.literal(line).withStyle(ChatFormatting.YELLOW), true);
        String key = s.owner + "/" + m;
        if (now || REMINDED.getOrDefault(key, 0L) + 2400 < level.getGameTime()) {
            REMINDED.put(key, level.getGameTime());
            notify(p, line);
        }
    }

    private static final Map<String, Long> REMINDED = new ConcurrentHashMap<>();

    /** A toast in the corner. */
    static void notify(ServerPlayer p, String text) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        t.putInt("coins", 0);
        Earnings.send(p, com.jrpetty.mcassistant.net.EarnPayload.TOAST, t);
    }

    /** Each morning: the keeper's wage, the day's counts started afresh, its name drifting back toward the middle. */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        for (Shop s : all(v.id())) {
            roll(s, day);
            s.visitsToday = 0;
            s.servedToday = 0;
            if (s.keeper != null) ShopKeepers.payday(level, v, s, day);
            s.rep += (0.5 - s.rep) * 0.03;
            if (day % WEEK == 0) { s.fair = 0; s.dearN = 0; s.bare = 0; s.greeted = 0; }
            save(s);
        }
    }

    // ------------------------------------------------------------------ the sign over the door

    /** A line of words floating over the shop's counter or sign: its name and how it stands, or what it is missing. */
    static void display(ServerLevel level, Shop s) {
        if (!level.isLoaded(s.anchor)) return;
        String state = stateWords(level, s);
        String text = s.name + "\n" + state;
        Entity e = s.display == null ? null : level.getEntity(s.display);
        if (e == null) {
            for (Display.TextDisplay d : level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(s.anchor).inflate(2))) {
                if (d.getTags().contains(TAG)) { e = d; break; }
            }
        }
        Display.TextDisplay td = e instanceof Display.TextDisplay x ? x : null;
        if (td != null && td.distanceToSqr(s.anchor.getX() + 0.5, s.anchor.getY() + 1.6, s.anchor.getZ() + 0.5) > 0.25) {
            td.teleportTo(s.anchor.getX() + 0.5, s.anchor.getY() + 1.6, s.anchor.getZ() + 0.5);   // [biz25] the counter moved: its sign goes with it
        }
        if (td == null) {
            Display.TextDisplay td2 = EntityType.TEXT_DISPLAY.create(level);
            if (td2 == null) return;
            td2.addTag(TAG);
            td2.moveTo(s.anchor.getX() + 0.5, s.anchor.getY() + 1.6, s.anchor.getZ() + 0.5);
            CompoundTag init = new CompoundTag();
            init.putString("billboard", "center");
            init.putInt("background", 0x90202020);
            td2.load(merge(td2.saveWithoutId(new CompoundTag()), init));
            level.addFreshEntity(td2);
            s.display = td2.getUUID();
            save(s);
            td = td2;
        }
        CompoundTag t = td.saveWithoutId(new CompoundTag());
        String json = Component.Serializer.toJson(Component.literal(s.name).withStyle(ChatFormatting.GOLD)
            .append(Component.literal("\n" + state).withStyle(state.startsWith("Open") ? ChatFormatting.GREEN : ChatFormatting.RED)), level.registryAccess());
        if (json.equals(t.getString("text"))) return;
        t.putString("text", json);
        td.load(t);
    }

    private static CompoundTag merge(CompoundTag a, CompoundTag b) {
        for (String k : b.getAllKeys()) a.put(k, b.get(k));
        return a;
    }

    static void removeDisplay(ServerLevel level, Shop s) {
        if (s.display != null) {
            Entity e = level.getEntity(s.display);
            if (e != null) e.discard();
        }
        if (level.isLoaded(s.anchor)) {
            for (Display.TextDisplay d : level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(s.anchor).inflate(2))) {
                if (d.getTags().contains(TAG)) d.discard();
            }
        }
    }

    // ------------------------------------------------------------------ nobody takes from it

    /** No other player opens a shop's stock (its keeper fills it; folk pay at the counter). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getEntity() instanceof net.neoforged.neoforge.common.util.FakePlayer) return;
        if (!(level.getBlockEntity(e.getPos()) instanceof Container)) return;
        Shop s = at(level, e.getPos());
        if (s == null || s.owner.equals(e.getEntity().getUUID()) || e.getEntity().hasPermissions(2)) return;
        e.setCanceled(true);
        e.getEntity().displayClientMessage(Component.literal("That's " + s.ownerName + "'s stock: buy at the counter.").withStyle(ChatFormatting.YELLOW), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        Shop s = at(level, e.getPos());
        if (s == null || s.owner.equals(e.getPlayer().getUUID()) || e.getPlayer().hasPermissions(2)) return;
        e.setCanceled(true);
        e.getPlayer().displayClientMessage(Component.literal("That's part of " + s.ownerName + "'s shop.").withStyle(ChatFormatting.YELLOW), true);
    }

    /** A block set down or broken near a shop: it is read again soon. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel() instanceof ServerLevel level) near(level, e.getPos());
    }

    @SubscribeEvent
    public static void onBroken(BlockEvent.BreakEvent e) {
        if (e.getLevel() instanceof ServerLevel level) near(level, e.getPos());
    }

    private static void near(ServerLevel level, BlockPos pos) {
        Villages.Village v = Villages.nearest(level, pos, 200);
        if (v == null) return;
        Map<UUID, Shop> m = TOWNS.get(v.id());
        if (m == null) return;
        for (Shop s : m.values()) {
            if (Math.abs(s.anchor.getX() - pos.getX()) <= ShopNeeds.REACH + 1 && Math.abs(s.anchor.getZ() - pos.getZ()) <= ShopNeeds.REACH + 1
                    && s.survey != null) {
                s.survey.at = level.getGameTime() - RESURVEY + 10;           // read again in half a second
            }
        }
    }

    // ------------------------------------------------------------------ the town's books

    /** The players' shops for the Shops page of the town's books, as its players' stalls are shown. */
    public static ListTag report(ServerLevel level, UUID village) {
        ListTag out = new ListTag();
        long day = today(level);
        for (Shop s : all(village)) {
            roll(s, day);
            CompoundTag t = new CompoundTag();
            t.putBoolean("shop", true);
            t.putString("owner", s.name + " (" + s.ownerName + "'s shop)");
            State st = state(level, s);
            t.putString("state", st == State.OPEN ? "open" : "shut");
            t.putString("why", stateWords(level, s));
            t.putInt("rent", 0);
            t.putLong("since", s.since);
            t.putInt("till", s.till);
            t.putInt("sold7", week(s.weekCount));
            t.putInt("coin7", week(s.weekCoin));
            t.putInt("soldToday", s.weekCount[0]);
            t.putInt("coinToday", s.weekCoin[0]);
            t.putInt("soldAll", s.soldAll);
            t.putInt("takenAll", s.takenAll);
            t.putInt("bx", s.anchor.getX());
            t.putInt("bz", s.anchor.getZ());
            t.putDouble("rep", s.rep);
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, ItemStack> w = wares(level, s, counts, false);
            ListTag wares = new ListTag();
            for (Map.Entry<String, ItemStack> e : w.entrySet()) {
                CompoundTag x = new CompoundTag();
                x.putString("key", e.getKey());
                x.putString("item", BuiltInRegistries.ITEM.getKey(e.getValue().getItem()).toString());
                x.putString("name", PlayerStalls.nameOf(e.getValue()));
                x.putInt("count", counts.getOrDefault(e.getKey(), 0));
                x.putInt("lot", PlayerStalls.lot(e.getValue()));
                int going = PlayerStalls.going(level, village, e.getValue());
                x.putInt("going", going);
                x.putBoolean("set", s.prices.containsKey(e.getKey()));
                x.putInt("price", asking(level, s, e.getKey(), e.getValue()));
                wares.add(x);
            }
            t.put("wares", wares);
            ListTag sales = new ListTag();
            for (int i = 0; i < Math.min(12, s.sales.size()); i++) {
                Sale x = s.sales.get(i);
                CompoundTag y = new CompoundTag();
                y.putLong("day", x.day());
                y.putString("who", x.who());
                y.putString("what", PlayerStalls.nameOf(PlayerStalls.sampleOf(x.key())).toLowerCase(Locale.ROOT));
                y.putString("item", x.key().endsWith("*") ? x.key().substring(0, x.key().length() - 1) : x.key());
                y.putInt("count", x.n());
                y.putInt("price", x.price());
                sales.add(y);
            }
            t.put("sales", sales);
            ListTag dear = new ListTag();
            for (int i = 0; i < Math.min(6, s.dear.size()); i++) {
                Dear d = s.dear.get(i);
                CompoundTag y = new CompoundTag();
                y.putLong("day", d.day());
                y.putString("who", d.who());
                y.putString("what", PlayerStalls.nameOf(PlayerStalls.sampleOf(d.key())).toLowerCase(Locale.ROOT));
                y.putInt("price", d.price());
                y.putInt("going", d.going());
                dear.add(y);
            }
            t.put("dear", dear);
            out.add(t);
        }
        return out;
    }

    /** Every shop's till, for the coin census. */
    public static long tills(UUID village) {
        long n = 0;
        for (Shop s : town(village).values()) n += s.till;
        return n;
    }

    // ------------------------------------------------------------------ small things

    static void tellOwner(ServerLevel level, UUID owner, String text) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        if (p != null) p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
    }

    static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}§]", "").trim();
    }

    private static void ints(int[] from, int[] into) {
        for (int i = 0; i < Math.min(from.length, into.length); i++) into[i] = from[i];
    }

    // ------------------------------------------------------------------ for tests

    public static Shop registerForTests(ServerLevel level, BlockPos anchor, ServerPlayer p, String kind) {
        placed(level, anchor, p, level.getBlockState(anchor).is(McAssistantMod.SHOP_COUNTER.get()));
        Villages.Village v = Villages.nearest(level, anchor, 200);
        Shop s = v == null ? null : of(v.id(), p.getUUID());
        if (s != null) setUp(level, p, s, s.name, kind, 0, 23999);
        return s;
    }

    /** [biz25] Tests: the week's rates taken now, as the town's tick takes them when they fall due. */
    public static void ratesForTests(ServerLevel level, Shop s) {
        rates(level, s, today(level));
    }

    public static void priceForTests(Shop s, ItemStack ware, int price) {
        s.prices.put(PlayerStalls.key(ware), price);
        save(s);
    }

    @SubscribeEvent
    public static void onStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        resetForTests();
    }

    public static void forgetCacheForTests() {
        TOWNS.clear();
    }
}
