package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.EarnMath;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [earning] Selling at the counters: each trade buys its own makings and wares, out of a day's purse, at the town's one
 * paying price, less as it fills.
 * <ul>
 * <li><b>Who buys what</b> (data: {@link #LINES}). The general store buys what the market board deals in (what the
 *     folk's needs run on) and its bench's makings; the café the kitchen's (wheat, sugar, eggs, meat, fish, roots);
 *     the tavern the cellar's (wheat, sugar, apples, berries, honey, bottles); the smith metal and fuel; the tailor
 *     wool, string, leather and dye. The store's Sell tab also takes finds for the town's museum, library and map room.
 *     A thing nobody at this counter buys says who in town does.</li>
 * <li><b>The price.</b> The town's paying price (Market.bidEach: four fifths of its price, never over nine tenths of the
 *     cheapest it sells the thing or its makings for, so no buy-and-sell loop pays), the whole of it while the buyer
 *     wants plenty, falling as its stock nears what it keeps, nothing once it has it (EarnMath.glutted). Each lot is
 *     priced as the one before it went in, so selling all of a glut pays less and less.</li>
 * <li><b>The purse.</b> A counter spends at most a quarter of the treasury (and ten) a day on what players bring
 *     ({@link #purse}); a poor town's counters buy less. Paid out of the treasury, booked to the counter's day
 *     (Earnings.spend) and the player's book.</li>
 * <li><b>Where it goes.</b> A trade's makings into its workshop chest (up to what it keeps), the rest into the stores,
 *     where the town's makers and shops use it and sell it on.</li>
 * </ul>
 */
public final class Buyers {

    private Buyers() {}

    /** Where a bought thing goes. */
    enum Into { STORES, MAKINGS, FINDS }

    /** One thing a counter buys: what counts, one to show, how many it keeps, where it goes, and which trade's chest. */
    record Line(Predicate<ItemStack> is, Item sample, int keep, Into into, @Nullable StationTask chest) {}

    private static Line stores(Item it, int keep) {
        return new Line(s -> s.is(it), it, keep, Into.STORES, null);
    }

    private static Line makings(Item it, int keep, StationTask trade) {
        return new Line(s -> s.is(it), it, keep, Into.MAKINGS, trade);
    }

    /** The share of the treasury a counter may spend in a day on what players bring, and the least it may. */
    static final double PURSE_SHARE = 0.25;
    static final int PURSE_LEAST = 10;
    /** A board good's keep, in lots (Worth reckons four lots the usual; a counter buys to twice that). */
    static final int BOARD_LOTS = 8;
    /** Finds: the museum pays this a point of renown over the bid; the library a written book; the map room a map. */
    static final int MUSEUM_PER_RENOWN = 6, BOOK_LEAST = 4, BOOK_MOST = 12, MAP_PRICE = 8;
    /** Finds the library and the map room buy in a day. */
    static final int BOOKS_A_DAY = 3, MAPS_A_DAY = 2;

    /** What each counter buys beyond the board. Data: a trade's line is its buying. */
    static final Map<ServiceRole, List<Line>> LINES = Map.of(
        ServiceRole.SHOP, List.of(makings(Items.STICK, 32, StationTask.SHOP), makings(Items.COBBLESTONE, 32, StationTask.SHOP),
            makings(Items.STRING, 16, StationTask.SHOP), makings(Items.COAL, 16, StationTask.SHOP),
            new Line(s -> s.is(ItemTags.PLANKS), Items.OAK_PLANKS, 64, Into.MAKINGS, StationTask.SHOP)),
        ServiceRole.CAFE, List.of(makings(Items.WHEAT, 64, StationTask.COOK), makings(Items.SUGAR, 32, StationTask.COOK),
            makings(Items.EGG, 16, StationTask.COOK), makings(Items.COCOA_BEANS, 16, StationTask.COOK), stores(Items.MILK_BUCKET, 4),
            stores(Items.BEEF, 32), stores(Items.PORKCHOP, 32), stores(Items.CHICKEN, 32), stores(Items.MUTTON, 32), stores(Items.COD, 32),
            stores(Items.SALMON, 32), stores(Items.POTATO, 64), stores(Items.CARROT, 64), stores(Items.BEETROOT, 32),
            stores(Items.PUMPKIN, 16), stores(Items.MELON_SLICE, 32), stores(Items.BROWN_MUSHROOM, 16), stores(Items.RED_MUSHROOM, 16)),
        ServiceRole.TAVERN, List.of(stores(Items.WHEAT, 64), stores(Items.SUGAR, 32), stores(Items.APPLE, 32), stores(Items.SWEET_BERRIES, 32),
            stores(Items.HONEY_BOTTLE, 16), stores(Items.GLASS_BOTTLE, 32), stores(Items.MELON_SLICE, 32)),
        ServiceRole.SMITHY, List.of(makings(Items.IRON_INGOT, 48, StationTask.SMITH), makings(Items.COAL, 48, StationTask.SMITH),
            makings(Items.STICK, 32, StationTask.SMITH), stores(Items.RAW_IRON, 64), stores(Items.IRON_ORE, 32), stores(Items.CHARCOAL, 32),
            stores(Items.RAW_GOLD, 16), stores(Items.GOLD_INGOT, 16), stores(Items.RAW_COPPER, 32), stores(Items.COPPER_INGOT, 32),
            stores(Items.DIAMOND, 8)),
        ServiceRole.TAILOR, List.of(new Line(s -> s.is(ItemTags.WOOL), Items.WHITE_WOOL, 48, Into.MAKINGS, StationTask.TAILOR),
            makings(Items.STRING, 32, StationTask.TAILOR), makings(Items.LEATHER, 24, StationTask.TAILOR),
            makings(Items.RED_DYE, 8, StationTask.TAILOR), makings(Items.YELLOW_DYE, 8, StationTask.TAILOR),
            makings(Items.BLUE_DYE, 8, StationTask.TAILOR), stores(Items.RABBIT_HIDE, 16), stores(Items.FEATHER, 16), stores(Items.INK_SAC, 8)));

    /** Does this counter buy from players at all? */
    public static boolean buys(ServiceRole role) {
        return role == ServiceRole.SHOP || LINES.containsKey(role);
    }

    /** A thing offered: who at this counter buys it, what it keeps, where it goes. Null: not this counter. */
    @Nullable
    static Line lineFor(ServiceRole role, ItemStack one) {
        if (one.isEmpty() || Market.isCoin(one) || one.isDamaged()) return null;
        List<Line> own = LINES.get(role);
        if (own != null) for (Line l : own) if (l.is().test(one)) return l;
        if (role == ServiceRole.SHOP) {
            Market.Good g = Market.goodFor(one);
            if (g != null) return new Line(s -> g.what().test(s), one.getItem(), Math.max(8, BOARD_LOTS * Math.max(1, g.bundle())), Into.STORES, null);
        }
        return null;
    }

    /** Which counter in town buys this, in words ("the smithy"), or null if none does. */
    @Nullable
    static String whoBuys(ItemStack one) {
        for (ServiceRole r : List.of(ServiceRole.SHOP, ServiceRole.SMITHY, ServiceRole.TAILOR, ServiceRole.CAFE, ServiceRole.TAVERN)) {
            if (lineFor(r, one) != null) return "the " + r.title.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** How many of a line the buyer holds now: the stores, and its workshop chest for a making. */
    static int have(ServerLevel level, UUID village, Line l) {
        int n = Market.stock(level, village, l.is());
        if (l.into() == Into.MAKINGS && l.chest() != null) n += chestCount(level, village, l.chest(), l.is());
        return n;
    }

    private static int chestCount(ServerLevel level, UUID village, StationTask trade, Predicate<ItemStack> is) {
        BlockPos chest = WorkStock.chestOf(level, village, trade);
        if (chest == null || !(level.getBlockEntity(chest) instanceof Container c)) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (is.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** The counter's purse for the day: a quarter of the treasury and ten, never more than it holds; less what it has
     *  spent on players' goods today. */
    public static int purse(ServerLevel level, UUID village, ServiceRole role) {
        int treasury = Ledger.coins(village);
        int day = (int) Math.min(treasury, PURSE_LEAST + Math.round(treasury * PURSE_SHARE));
        return Math.max(0, day - Earnings.spentToday(village, payer(role), level.getDayTime() / 24000L));
    }

    static String payer(ServiceRole role) {
        return "buy/" + role.id;
    }

    /** What the buyer pays for the next {@code lot} of this with {@code have} already in hand (glut included). */
    static int lotPrice(ServerLevel level, UUID village, ItemStack one, Line l, int have, int lot) {
        double each = Market.bidEach(level, village, one);
        double g = EarnMath.glutted(each, have, l.keep());
        return (int) Math.floor(g * Math.max(1, lot) + 1e-9);
    }

    // ------------------------------------------------------------------ finds

    /** A find the town buys for its museum, library or map room: the price and where in words, or null. */
    record Find(int price, String forWhat, String payer, int perDay) {}

    @Nullable
    static Find find(ServerLevel level, UUID village, ItemStack one) {
        if (one.isEmpty()) return null;
        Museum.Kind k = Museum.Kind.inStores(one);
        if (k != null && Museum.building(village) != null && !Museum.onShow(village, k.key())
                && Market.stock(level, village, s -> k.is(s)) <= 0) {
            int price = MUSEUM_PER_RENOWN * k.renown + (int) Math.floor(Market.bidEach(level, village, one));
            return new Find(price, "for the museum's " + k.label.toLowerCase(Locale.ROOT), "museum/" + k.key(), 1);
        }
        if (one.is(Items.WRITTEN_BOOK) && Library.building(village) != null) {
            var content = one.get(DataComponents.WRITTEN_BOOK_CONTENT);
            int pages = content == null ? 0 : content.pages().size();
            if (pages <= 0) return null;
            return new Find(Math.min(BOOK_MOST, BOOK_LEAST + pages / 2), "for the library's shelves", "library", BOOKS_A_DAY);
        }
        if (one.is(Items.FILLED_MAP) && Cartographers.mapRoom(village) != null) {
            var deco = one.get(DataComponents.MAP_DECORATIONS);
            boolean explorer = deco != null && !deco.decorations().isEmpty();
            return explorer ? new Find(MAP_PRICE, "for the map room", "maps", MAPS_A_DAY) : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ the screen's rows

    /** What the player carries that this counter buys (and, at the store, the town's finds): a row a kind. */
    static ListTag sellTag(ServicePoints.Visit visit) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        ListTag out = new ListTag();
        if (p == null) return out;
        int purse = purse(level, id, visit.role());
        for (Map.Entry<String, int[]> e : carried(p).entrySet()) {
            ItemStack one = SAMPLES.get().get(e.getKey());
            int have = e.getValue()[0];
            CompoundTag t = new CompoundTag();
            t.putString("id", e.getKey());
            t.put("item", one.save(level.registryAccess()));
            t.putString("name", Goods.name(one));
            t.putString("cat", Goods.of(one).name());
            t.putInt("have", have);
            Line l = lineFor(visit.role(), one);
            Find f = visit.role() == ServiceRole.SHOP && l == null ? find(level, id, one) : null;
            if (l != null) {
                int lot = ShopDesk.sellLot(one);
                int held = have(level, id, l);
                int price = lotPrice(level, id, one, l, held, lot);
                int wants = EarnMath.wants(held, l.keep());
                t.putInt("lot", lot);
                t.putInt("bid", price);
                t.putInt("wants", wants);
                t.putInt("purse", purse);
                t.putString("why", wants <= 0 ? "Has all it needs of this today." : price <= 0 ? "Not worth a coin to the town just now."
                    : purse < price ? "The till has spent today's purse." : "");
                t.putString("sub", (lot > 1 ? lot + " for " : "") + price + "c · wants " + wants + " more" + (l.into() == Into.MAKINGS ? " for its bench" : ""));
            } else if (f != null) {
                int left = f.perDay() - Earnings.spentToday(id, "find/" + f.payer(), level.getDayTime() / 24000L);
                t.putInt("lot", 1);
                t.putInt("bid", left > 0 ? f.price() : 0);
                t.putInt("wants", Math.max(0, left));
                t.putInt("purse", purse);
                t.putBoolean("find", true);
                t.putString("why", left > 0 ? "" : "Bought enough of these for today.");
                t.putString("sub", f.price() + "c " + f.forWhat());
            } else {
                String who = whoBuys(one);
                t.putInt("lot", 1);
                t.putInt("bid", 0);
                t.putString("why", who == null ? "Nobody in town buys this." : "Not here: " + who + " buys it.");
                t.putString("sub", t.getString("why"));
            }
            out.add(t);
        }
        // What this counter buys first, then finds, then what it does not.
        List<CompoundTag> rows = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) rows.add(out.getCompound(i));
        rows.sort((a, b) -> Integer.compare(b.getInt("bid") > 0 ? 1 : 0, a.getInt("bid") > 0 ? 1 : 0));
        ListTag sorted = new ListTag();
        sorted.addAll(rows);
        return sorted;
    }

    private static final ThreadLocal<Map<String, ItemStack>> SAMPLES = ThreadLocal.withInitial(LinkedHashMap::new);

    /** What the player carries, a line a kind (whole, undamaged, no coin): count by line id; one of each kept aside. */
    private static Map<String, int[]> carried(ServerPlayer p) {
        Map<String, int[]> have = new LinkedHashMap<>();
        Map<String, ItemStack> samples = SAMPLES.get();
        samples.clear();
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty() || Market.isCoin(s) || s.isDamaged() || Shifts.isShiftItem(s)) continue;
            String key = Goods.lineId(s);
            have.computeIfAbsent(key, k -> new int[1])[0] += s.getCount();
            samples.putIfAbsent(key, s.copyWithCount(1));
        }
        return have;
    }

    /** The desk's section (the smithy, the tailor's, the tavern): what it buys of what you carry, Sell with an amount. */
    static void deskSection(DeskRows d, ServicePoints.Visit visit) {
        if (visit.player() == null || !buys(visit.role())) return;
        ListTag rows = sellTag(visit);
        d.section("We buy");
        d.line("The " + visit.role().title.toLowerCase(Locale.ROOT) + " pays for its makings out of the town's purse: up to "
            + purse(visit.level(), visit.village().id(), visit.role()) + "c more today, less as it fills.");
        int shown = 0;
        for (int i = 0; i < rows.size() && shown < 8; i++) {
            CompoundTag r = rows.getCompound(i);
            if (r.getInt("bid") <= 0 && r.getString("why").startsWith("Nobody")) continue;
            ItemStack icon = ItemStack.parseOptional(visit.level().registryAccess(), r.getCompound("item"));
            int lot = Math.max(1, r.getInt("lot")), have = r.getInt("have");
            CompoundTag row = d.row("sellto", "Sell", r.getString("name") + "  ×" + have, r.getString("sub"), icon,
                r.getInt("bid") <= 0 ? r.getString("why") : have < lot ? "Bought by the " + lot + "." : "");
            row.getCompound("args").putString("id", r.getString("id"));
            DeskRows.amount(row, lot, Math.max(lot, have - have % lot), Math.max(lot, have - have % lot), lot);
            shown++;
        }
        if (shown == 0) d.line("Nothing you carry is wanted here.");
    }

    // ------------------------------------------------------------------ selling

    /**
     * The player sells up to {@code pieces} of a thing it carries at this counter: lot by lot, each at the price the
     * buyer offers with what it has by then, as long as it wants more, its purse and the treasury last. Paid out of the
     * treasury, into the workshop chest or the stores. What it says.
     */
    static ServicePoints.Result sell(ServicePoints.Visit visit, String key, int pieces) {
        ServerLevel level = visit.level();
        Villages.Village v = visit.village();
        UUID id = v.id();
        ServerPlayer p = visit.player();
        if (p == null) return ServicePoints.Result.no("");
        long day = level.getDayTime() / 24000L;
        if (Standing.of(id, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) return ServicePoints.Result.no("Nobody here will trade with you.");
        ItemStack one = ItemStack.EMPTY;
        int have = 0;
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty() || s.isDamaged() || Shifts.isShiftItem(s) || !Goods.lineId(s).equals(key)) continue;
            if (one.isEmpty()) one = s.copyWithCount(1);
            have += s.getCount();
        }
        if (one.isEmpty()) return ServicePoints.Result.no("You've none of that on you now.");
        if (Services.given(id, p.getUUID(), day, one.getItem()) > 0) return ServicePoints.Result.no("We gave you that this morning, friend: keep it, it's yours.");
        Line l = lineFor(visit.role(), one);
        String counter = Villages.name(id) + " " + visit.role().title.toLowerCase(Locale.ROOT);
        if (l == null) {
            Find f = visit.role() == ServiceRole.SHOP ? find(level, id, one) : null;
            if (f != null) return sellFind(visit, one, f, key);
            String who = whoBuys(one);
            return ServicePoints.Result.no(who == null ? "Nobody in town buys " + Goods.name(one).toLowerCase(Locale.ROOT) + "."
                : "We don't deal in that here; " + who + " buys it.");
        }
        int lot = ShopDesk.sellLot(one);
        int want = Math.min(have, Math.max(lot, pieces));
        want -= want % lot;
        if (want <= 0) return ServicePoints.Result.no("The town buys " + Goods.name(one).toLowerCase(Locale.ROOT) + " by the " + lot + ".");
        int held = have(level, id, l), purse = purse(level, id, visit.role());
        int sold = 0, price = 0;
        String stop = "";
        while (sold < want) {
            int next = lotPrice(level, id, one, l, held + sold, lot);
            if (EarnMath.wants(held + sold, l.keep()) <= 0) { stop = "it has all it needs of that now"; break; }
            if (next <= 0) { stop = "the rest isn't worth a coin to it now"; break; }
            if (price + next > purse) { stop = "today's purse is spent"; break; }
            if (price + next > Ledger.coins(id)) { stop = "the treasury is short"; break; }
            price += next;
            sold += lot;
        }
        if (sold <= 0) {
            return ServicePoints.Result.no(stop.isEmpty() ? "Not wanted just now." : cap(Villages.name(id)) + " won't buy that: " + stop + ".");
        }
        int stored = put(level, v, l, one.copyWithCount(sold));
        if (stored < sold) {
            if (stored > 0) TownWork.take(level, v, ShopDesk.same(one), stored);
            return ServicePoints.Result.no("The stores are full; they can't take any more just now.");
        }
        takeFrom(p, key, sold);
        Earnings.spend(id, payer(visit.role()), day, price);
        int paid = Earnings.fromTreasury(p, id, price, Earnings.Source.SELL, counter, "for " + Goods.words(one, sold));
        if (l.into() == Into.MAKINGS && visit.role() == ServiceRole.SHOP) ShopBusiness.makings(id, day, paid);
        ShopDesk.receipt(level, id, p.getUUID(), "Day " + day + ": sold " + Goods.words(one, sold) + " for " + coins(paid));
        return ServicePoints.Result.done("Sold " + Goods.words(one, sold) + " for " + coins(paid) + "." + (sold < want ? " It took no more: " + stop + "." : ""));
    }

    /** A find for the museum, the library or the map room: one at a time, its day's number allowing. */
    private static ServicePoints.Result sellFind(ServicePoints.Visit visit, ItemStack one, Find f, String key) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        long day = level.getDayTime() / 24000L;
        if (Earnings.spentToday(id, "find/" + f.payer(), day) >= f.perDay()) return ServicePoints.Result.no("The town has bought enough of those for today.");
        if (Ledger.coins(id) < f.price()) return ServicePoints.Result.no("The treasury hasn't the coin for that just now.");
        ItemStack left = Market.intoStores(level, id, one.copy());
        if (!left.isEmpty()) return ServicePoints.Result.no("The stores are full just now.");
        takeFrom(p, key, 1);
        Earnings.spend(id, "find/" + f.payer(), day, 1);
        int paid = Earnings.fromTreasury(p, id, f.price(), Earnings.Source.FINDS, Villages.name(id), f.forWhat() + ": " + Goods.name(one).toLowerCase(Locale.ROOT));
        ShopDesk.receipt(level, id, p.getUUID(), "Day " + day + ": " + Goods.name(one).toLowerCase(Locale.ROOT) + " " + f.forWhat() + ", " + coins(paid));
        return ServicePoints.Result.done("Sold " + Goods.name(one).toLowerCase(Locale.ROOT) + " " + f.forWhat() + " for " + coins(paid) + ".");
    }

    /** Into the buyer: a making into its workshop chest up to what it keeps there, the rest into the stores. What went in. */
    private static int put(ServerLevel level, Villages.Village v, Line l, ItemStack lot) {
        int n = lot.getCount();
        ItemStack left = lot.copy();
        if (l.into() == Into.MAKINGS && l.chest() != null) {
            BlockPos chest = WorkStock.chestOf(level, v.id(), l.chest());
            int room = Math.max(0, l.keep() - chestCount(level, v.id(), l.chest(), l.is()));
            if (room > 0 && chest != null && level.getBlockEntity(chest) instanceof Container c) {
                int k = Math.min(room, left.getCount());
                ItemStack back = Stacking.insert(c, left.split(k));
                left.grow(back.getCount());
            }
        }
        if (!left.isEmpty()) left = Market.intoStores(level, v.id(), left);
        return n - left.getCount();
    }

    static void takeFrom(ServerPlayer p, String key, int n) {
        int toTake = n;
        for (ItemStack s : p.getInventory().items) {
            if (toTake <= 0) break;
            if (s.isEmpty() || s.isDamaged() || Shifts.isShiftItem(s) || !Goods.lineId(s).equals(key)) continue;
            int k = Math.min(toTake, s.getCount());
            s.shrink(k);
            toTake -= k;
        }
        p.getInventory().setChanged();
    }

    private static String coins(int n) {
        return n + (n == 1 ? " coin" : " coins");
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the page: the best prices about

    /** The best a town pays today for one of a thing (any counter), and which: {price to the hundredth, counter}. */
    @Nullable
    static Object[] bestBid(ServerLevel level, UUID village, ItemStack one) {
        double best = 0;
        String at = null;
        for (ServiceRole r : List.of(ServiceRole.SHOP, ServiceRole.SMITHY, ServiceRole.TAILOR, ServiceRole.CAFE, ServiceRole.TAVERN)) {
            Line l = lineFor(r, one);
            if (l == null) continue;
            double each = EarnMath.glutted(Market.bidEach(level, village, one), have(level, village, l), l.keep());
            if (each > best) { best = each; at = r.title; }
        }
        return at == null ? null : new Object[]{ best, at };
    }

    /** Tests: the price the counter offers for the next lot of this, with what it holds now. */
    public static int lotPriceForTests(ServerLevel level, UUID village, ServiceRole role, ItemStack one) {
        Line l = lineFor(role, one);
        return l == null ? -1 : lotPrice(level, village, one, l, have(level, village, l), ShopDesk.sellLot(one));
    }

    /** Tests: what this counter keeps of a thing, and how many it wants more. */
    public static int[] wantForTests(ServerLevel level, UUID village, ServiceRole role, ItemStack one) {
        Line l = lineFor(role, one);
        if (l == null) return new int[]{ -1, -1 };
        return new int[]{ l.keep(), EarnMath.wants(have(level, village, l), l.keep()) };
    }

    /** Tests: sell at a counter as its screen's button does. */
    public static ServicePoints.Result sellForTests(ServicePoints.Visit visit, ItemStack one, int pieces) {
        return sell(visit, Goods.lineId(one), pieces);
    }

    /** Tests: the bank's changer, as its rows do (toCoin: the thing for coin). */
    public static ServicePoints.Result changeForTests(ServicePoints.Visit visit, Item item, int n, boolean toCoin) {
        CompoundTag a = new CompoundTag();
        a.putString("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
        a.putInt("amount", n);
        a.putBoolean("toCoin", toCoin);
        return BankCounter.change(visit, a);
    }
}
