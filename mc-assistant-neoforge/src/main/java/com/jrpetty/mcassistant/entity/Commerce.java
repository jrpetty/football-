package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Doing business with a whole village, from a bulk order to a trade route of your own.
 * <ul>
 * <li><b>Bulk orders.</b> Order a quantity of anything at a tenth off: what the village can spare
 *     goes at once; the rest is put by for you as it is made (a quarter down, a week to collect).</li>
 * <li><b>Supply contracts.</b> Sign up to bring the village what it is short of, every week, at a
 *     third over its worth. Keep it up and the village thinks the world of you; miss two weeks and
 *     the contract lapses.</li>
 * <li><b>Your own market stall.</b> Rent a stall on the square a week at a time, stock its barrel and
 *     set your prices: the folk buy from it out of their own purses, into its till (PlayerStalls).</li>
 * <li><b>The village bank.</b> Put coin by at the treasury and it earns a little every week; borrow
 *     against your good name, and pay it back — a debt left a fortnight shames you.</li>
 * <li><b>Investing.</b> Put coin into the village's works: for two weeks you take a share of what the
 *     village takes in each day.</li>
 * <li><b>The auction.</b> On market day the village puts up its finest spare thing for bids; the best
 *     bid takes it the day after.</li>
 * <li><b>Caravan escort.</b> Sign on to guard the next caravan: walk with it and be paid on arrival.</li>
 * <li><b>A trade route of your own.</b> Charter a route to a neighbour: the caravans start running
 *     and you take a tenth of every load sold on it.</li>
 * <li><b>Where things are dear.</b> Folk know what sells for what in the villages round about.</li>
 * </ul>
 * Everything is kept in the village's ledger (notes), so it outlasts a restart.
 */
public final class Commerce {

    private Commerce() {}

    static final int CHARTER_COST = 50, ESCORT_PAY = 5;

    // ------------------------------------------------------------------ helpers

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("\\b(\\d{1,4})\\b");

    /** The first number in what was said (no cap but four figures), or the default. */
    static int number(String text, int dflt) {
        java.util.regex.Matcher m = NUMBER.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : dflt;
    }

    static String idOf(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    @Nullable
    static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    static String name(Item it) {
        return new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT);
    }

    static String[] fields(UUID village, String key) {
        String s = Ledger.note(village, key);
        return s == null || s.isEmpty() ? null : s.split("\\|", -1);
    }

    static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Hand over n of a thing from the stores to the player, a stack at a time. Returns how many went. */
    static int handOver(ServerLevel level, Villages.Village v, Player p, Item it, int n) {
        int given = 0;
        while (given < n) {
            int lot = Math.min(it.getDefaultMaxStackSize(), n - given);
            if (!TownWork.take(level, v, s -> s.is(it) && !s.isEnchanted(), lot)) break;
            Dealings.give(p, new ItemStack(it, lot));
            given += lot;
        }
        Budget.forget(v.id());
        return given;
    }

    static int taking(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    static List<ItemStack> takeFrom(Player p, Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize() && n > 0; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int move = Math.min(n, s.getCount());
            out.add(s.split(move));
            n -= move;
        }
        return out;
    }

    // ------------------------------------------------------------------ 12. bulk orders

    /** "I'd like to order 256 cobblestone" — or, with an order in, "is my order ready?". */
    public static String bulk(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Orders? We've no village to fill them from.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "Orders? We've no village to fill them from.";
        long day = Dealings.day(f);
        String key = "bulk/" + p.getUUID();
        String[] o = fields(village, key);
        Item want = Services.itemNamed(text);
        if (o != null && (want == null || o.length >= 4 && itemOf(o[0]) == want)) {
            Item it = itemOf(o[0]);
            int count = (int) num(o[1]), deposit = (int) num(o[2]);
            long due = num(o[3]);
            if (it == null) { Ledger.note(village, key, ""); return "Your order's gone astray. Order again?"; }
            int total = bulkPrice(it, count);
            if (Budget.spare(level, village, new ItemStack(it)) < count) {
                if (day > due) {
                    Ledger.note(village, key, "");
                    Dealings.giveCoins(p, deposit);
                    return "We couldn't make your " + count + " " + name(it) + " in time, I'm sorry. Here's your " + deposit + " coins back.";
                }
                return "Your " + count + " " + name(it) + " aren't all made yet — come back in a day or two (by day " + due + ").";
            }
            int owe = Math.max(0, total - deposit);
            if (Market.coinsHeld(p) < owe) return "Your " + count + " " + name(it) + " are ready: " + owe + " coins still to pay. You've " + Market.coinsHeld(p) + ".";
            int got = handOver(level, v, p, it, count);
            Market.payOut(p, owe);
            Ledger.addCoins(village, owe);
            Economy.sold(village, owe + deposit);
            Ledger.note(village, key, "");
            return "Here you are: " + got + " " + name(it) + ". " + (owe > 0 ? owe + " coins, and we're square." : "Paid in full. Pleasure doing business.");
        }
        if (want == null) return "Order what? Say what and how many — \"I'd like to order 256 cobblestone\".";
        int count = Math.max(1, Math.min(1024, number(text, 64)));
        if (Prices.each(want) <= 0) return "We don't deal in " + name(want) + ".";
        int total = bulkPrice(want, count);
        int spare = Budget.spare(level, village, new ItemStack(want));
        if (spare >= count) {
            if (Market.coinsHeld(p) < total) return count + " " + name(want) + " would be " + total + " coins, a tenth off for the quantity. You've " + Market.coinsHeld(p) + ".";
            int got = handOver(level, v, p, want, count);
            int paid = got == count ? total : bulkPrice(want, got);
            Market.payOut(p, paid);
            Ledger.addCoins(village, paid);
            Economy.sold(village, paid);
            return "There: " + got + " " + name(want) + " for " + paid + " coins.";
        }
        if (o != null) return "You've an order in with us already. Let us fill that one first.";
        int deposit = Math.max(1, total / 4);
        if (Market.coinsHeld(p) < deposit) return "We'd have to make most of that: a quarter down — " + deposit + " coins — and it's yours within a week.";
        Market.payOut(p, deposit);
        Ledger.addCoins(village, deposit);
        Ledger.note(village, key, idOf(want) + "|" + count + "|" + deposit + "|" + (day + 7));
        return "We can spare " + Math.max(0, spare) + " just now, so we'll put the rest by as we make it: " + deposit
            + " coins down, " + (total - deposit) + " when you collect, within the week.";
    }

    static int bulkPrice(Item it, int count) {
        return (int) Math.max(1, Math.ceil(Budget.playerPrice(new ItemStack(it)) * 0.9 * count));
    }

    // ------------------------------------------------------------------ 13. supply contracts

    /** What a contract asks each week for a shortage, and what counts as it. */
    record Supply(String words, Item sample, int count, Predicate<ItemStack> counts) {}

    @Nullable
    static Supply supplyFor(Villages.Task t) {
        return switch (t) {
            case FOOD -> new Supply("food", Items.BREAD, 64, s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null);
            case LOGS -> new Supply("logs", Items.OAK_LOG, 64, s -> s.is(ItemTags.LOGS));
            case STONE -> new Supply("cobblestone", Items.COBBLESTONE, 128, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE));
            case IRON -> new Supply("iron", Items.IRON_INGOT, 16, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON));
            case COAL -> new Supply("coal", Items.COAL, 32, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
            case DIAMOND -> new Supply("diamonds", Items.DIAMOND, 1, s -> s.is(Items.DIAMOND));
            case OBSIDIAN -> new Supply("obsidian", Items.OBSIDIAN, 4, s -> s.is(Items.OBSIDIAN));
            default -> null;
        };
    }

    /** "Is there a contract going?" — sign one; with one signed and the goods in your pack, deliver. */
    public static String contract(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Contracts? We've no village to sign for.";
        long day = Dealings.day(f);
        String key = "contract/" + p.getUUID();
        String[] c = fields(village, key);
        String name = p.getName().getString();
        if (c != null && c.length >= 6) {
            Villages.Task t = Villages.Task.valueOf(c[0]);
            Supply s = supplyFor(t);
            int price = (int) num(c[2]);
            long last = num(c[4]);
            int done = (int) num(c[5]);
            if (s == null) { Ledger.note(village, key, ""); return "That contract's no use to us now."; }
            if (day - last < 7 && done > 0) return "You've brought this week's " + s.words() + " already. Next week, same again.";
            if (taking(p, s.counts()) < s.count()) {
                return "Our contract: " + s.count() + " " + s.words() + " a week, " + price + " coins each time. Bring them when you have them.";
            }
            for (ItemStack lot : takeFrom(p, s.counts(), s.count())) {
                ItemStack left = Market.intoStores(level, village, lot);
                if (!left.isEmpty()) Dealings.give(p, left);
            }
            int paid = Math.min(price, Ledger.coins(village));
            Ledger.takeCoins(village, paid);
            Economy.spent(village, paid);
            Dealings.giveCoins(p, paid);
            done++;
            Ledger.note(village, key, c[0] + "|" + s.count() + "|" + price + "|" + c[3] + "|" + day + "|" + done + "|0");
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a instanceof VillageFolkEntity g && g.getRandom().nextInt(3) == 0) g.persona().feelFor(p.getUUID(), name, 2);
            }
            Standing.stir(village, p.getUUID());
            Budget.forget(village);
            if (done >= 4) {
                Ledger.note(village, key, "");
                Villages.tell(village, day, name + " kept a whole month's contract to bring us " + s.words());
                return "That's the month done — every week on time. " + paid + " coins, and our thanks. The village won't forget it.";
            }
            return "This week's " + s.words() + ", right on time. " + paid + " coins — see you next week (" + (4 - done) + " to go).";
        }
        List<Villages.Need> needs = Villages.needs(level, village);
        for (Villages.Need n : needs) {
            Supply s = supplyFor(n.task());
            if (s == null) continue;
            int price = (int) Math.max(1, Math.round(Prices.each(s.sample()) * s.count() * 1.3));
            if (!text.toLowerCase(Locale.ROOT).contains("sign") && !text.toLowerCase(Locale.ROOT).contains("take it")
                    && !text.toLowerCase(Locale.ROOT).contains("i'll do it")) {
                return "We're short of " + s.words() + ". Bring us " + s.count() + " a week for a month and we'll pay " + price
                    + " coins each time — a third over its worth. Say \"I'll sign\" if you'll do it.";
            }
            Ledger.note(village, key, n.task().name() + "|" + s.count() + "|" + price + "|" + day + "|" + (day - 7) + "|0|0");
            Villages.tell(village, day, name + " signed a contract to bring us " + s.count() + " " + s.words() + " a week");
            return "Signed. " + s.count() + " " + s.words() + " a week, " + price + " coins a time. Don't let us down.";
        }
        return "We're not short of anything a contract would help with just now.";
    }

    // ------------------------------------------------------------------ 14. a market stall

    /** "Could I rent a stall?", "pay the rent", "take the till": a stall of your own on the square (PlayerStalls). */
    public static String stall(VillageFolkEntity f, Player p, String text) {
        return PlayerStalls.talk(f, p, text);
    }

    static List<Map.Entry<String, String>> notesStarting(UUID village, String prefix) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() != null && !e.getValue().isEmpty()) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ 15. the bank

    /** "Deposit 20 coins", "withdraw 10", "borrow 30", "repay my loan", or how the account stands. */
    public static String bank(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel)) return "There's no treasury to keep it in.";
        long day = Dealings.day(f);
        String t = text.toLowerCase(Locale.ROOT);
        String key = "bank/" + p.getUUID();
        String[] b = fields(village, key);
        int deposit = b == null ? 0 : (int) num(b[0]);
        int loan = b == null || b.length < 2 ? 0 : (int) num(b[1]);
        long since = b == null || b.length < 3 ? day : num(b[2]);
        int n = number(text, 0);
        String name = p.getName().getString();
        if (t.contains("deposit") || t.contains("put by") || t.contains("save")) {
            n = Math.min(n <= 0 ? 10 : n, Market.coinsHeld(p));
            if (n <= 0) return "You've no coin on you to put by.";
            Market.payOut(p, n);
            Ledger.addCoins(village, n);
            deposit += n;
        } else if (t.contains("withdraw") || t.contains("take out")) {
            n = Math.min(n <= 0 ? deposit : n, deposit);
            if (n <= 0) return "You've nothing put by with us.";
            if (Ledger.coins(village) < n) return "The treasury's short just now — come back after market day.";
            Ledger.takeCoins(village, n);
            Dealings.giveCoins(p, n);
            deposit -= n;
        } else if (t.contains("repay") || t.contains("pay back") || t.contains("pay off")) {
            n = Math.min(Math.min(n <= 0 ? loan : n, loan), Market.coinsHeld(p));
            if (loan <= 0) return "You owe us nothing.";
            if (n <= 0) return "You've no coin on you to pay it with.";
            Market.payOut(p, n);
            Ledger.addCoins(village, n);
            loan -= n;
            if (loan == 0) f.persona().feelFor(p.getUUID(), name, 2);
        } else if (t.contains("borrow") || t.contains("loan") || t.contains("lend")) {
            Standing.Title title = Standing.of(village, p.getUUID(), f.level().getGameTime()).title();
            int most = title.atLeast(Standing.Title.HONOURED) ? 64 : title.atLeast(Standing.Title.FRIEND) ? 24 : 0;
            if (most == 0) return "We don't lend to folk we don't know. Make some friends here first.";
            if (loan > 0) return "You owe us " + loan + " already. Pay that off first.";
            n = Math.min(n <= 0 ? 10 : n, most);
            if (Ledger.coins(village) - 2 * Market.wageBill(village) < n) return "We can't lend that much just now — the wages come first.";
            Ledger.takeCoins(village, n);
            Dealings.giveCoins(p, n);
            loan = n;
            since = day;
        } else {
            return "Your account: " + deposit + " coins put by (it earns a coin in fifty a week)" + (loan > 0 ? ", and " + loan
                + " coins owed (a tenth a week; pay it within the fortnight)" : "") + ". Say \"deposit 20\", \"withdraw 10\", \"borrow 30\" or \"repay\".";
        }
        Ledger.note(village, key, deposit + "|" + loan + "|" + since);
        return "Done. You've " + deposit + " coins put by with us" + (loan > 0 ? ", and owe " + loan : "") + ".";
    }

    /** A week at the bank: deposits earn, loans grow; a debt left two weeks shames the borrower. */
    static void bankWeek(ServerLevel level, Villages.Village v, long day) {
        for (Map.Entry<String, String> e : notesStarting(v.id(), "bank/")) {
            String[] b = e.getValue().split("\\|", -1);
            int deposit = (int) num(b[0]);
            int loan = b.length > 1 ? (int) num(b[1]) : 0;
            long since = b.length > 2 ? num(b[2]) : day;
            int interest = deposit / 50;
            if (interest > 0 && Ledger.coins(v.id()) > interest + Market.wageBill(v.id())) {
                Ledger.takeCoins(v.id(), interest);
                deposit += interest;
            }
            if (loan > 0) {
                loan += Math.max(1, loan / 10);
                if (day - since >= 14) {
                    UUID player;
                    try {
                        player = UUID.fromString(e.getKey().substring("bank/".length()));
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    sour(level, v.id(), player, -3);
                    Villages.tell(v.id(), day, "a debt to the treasury has gone unpaid for a fortnight");
                }
            }
            Ledger.note(v.id(), e.getKey(), deposit + "|" + loan + "|" + since);
        }
    }

    // ------------------------------------------------------------------ 16. investing

    /** "I'd like to invest 50 coins" — a share of the village's takings for two weeks. */
    public static String invest(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "Invest in what? We've no village.";
        long day = Dealings.day(f);
        String key = "invest/" + p.getUUID();
        String[] i = fields(village, key);
        if (i != null && i.length >= 2 && day <= num(i[1])) {
            return "You've " + num(i[0]) + " coins in our works until day " + num(i[1]) + ". Your share goes into your account here each morning.";
        }
        int n = Math.max(10, Math.min(500, number(text, 50)));
        if (Market.coinsHeld(p) < n) return "To invest " + n + " coins you'd need " + n + " coins. You've " + Market.coinsHeld(p) + ".";
        Market.payOut(p, n);
        Ledger.addCoins(village, n);
        Ledger.note(village, key, n + "|" + (day + 14));
        String name = p.getName().getString();
        Villages.tell(village, day, name + " put " + n + " coins into the village's works");
        Standing.stir(village, p.getUUID());
        return "Thank you! " + n + " coins into our works — for two weeks you'll have your share of what we take in each day, paid into your account here.";
    }

    /** The morning's dividends: each investor's share of yesterday's takings, into its account. */
    static void dividends(Villages.Village v, long day) {
        int taken = Math.max(0, Economy.yesterday(v.id()));
        if (taken <= 0) return;
        for (Map.Entry<String, String> e : notesStarting(v.id(), "invest/")) {
            String[] i = e.getValue().split("\\|", -1);
            if (i.length < 2 || day > num(i[1])) { Ledger.note(v.id(), e.getKey(), ""); continue; }
            int stake = (int) num(i[0]);
            int share = Math.max(1, (int) Math.round(taken * Math.min(0.25, stake / 400.0)));
            if (Ledger.coins(v.id()) < share + Market.wageBill(v.id())) continue;
            Ledger.takeCoins(v.id(), share);
            String bankKey = "bank/" + e.getKey().substring("invest/".length());
            String[] b = fields(v.id(), bankKey);
            int deposit = b == null ? 0 : (int) num(b[0]);
            String rest = b == null || b.length < 3 ? "0|" + day : b[1] + "|" + b[2];
            Ledger.note(v.id(), bankKey, (deposit + share) + "|" + rest);
        }
    }

    // ------------------------------------------------------------------ 17. the auction

    /** "What's up for auction?" / "I bid 40" / "I'll collect what I won". */
    public static String auction(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Auctions? There's no market here.";
        long day = Dealings.day(f);
        String won = Ledger.note(village, "auction.won/" + p.getUUID());
        if (won != null && !won.isEmpty()) {
            Item it = itemOf(won);
            Ledger.note(village, "auction.won/" + p.getUUID(), "");
            Villages.Village v = Villages.get(village);
            if (it != null && v != null && handOver(level, v, p, it, 1) == 1) return "Your " + name(it) + " — you won it fair and square. Enjoy it!";
            return "The lot you won has gone astray. I'm sorry.";
        }
        String[] a = fields(village, "auction");
        if (a == null || a.length < 6 || day > num(a[5]) + 1) return "Nothing up for auction just now. Market day, we put our finest spare thing up for bids.";
        Item it = itemOf(a[0]);
        int reserve = (int) num(a[1]), best = (int) num(a[2]);
        String bidder = a[4];
        if (it == null) return "Nothing up for auction just now.";
        int bid = number(text, 0);
        String name = p.getName().getString();
        if (bid <= 1 || !text.toLowerCase(Locale.ROOT).contains("bid")) {
            return "Up for auction: " + name(it) + ". " + (best > 0 ? "Best bid " + best + " coins, from " + bidder + "." : "Bids from " + reserve + " coins.")
                + " Say \"I bid 30\". It goes to the best bid tomorrow.";
        }
        if (bid < Math.max(reserve, best + 1)) return "You'll have to beat " + Math.max(reserve, best + 1) + " coins.";
        if (Market.coinsHeld(p) < bid) return "You've not got " + bid + " coins on you.";
        Market.payOut(p, bid);
        if (best > 0 && !a[3].isEmpty()) refund(village, a[3], best);                // the last best bid back into its bidder's account
        Ledger.note(village, "auction", a[0] + "|" + reserve + "|" + bid + "|" + p.getUUID() + "|" + name + "|" + a[5]);
        return "Bid taken: " + bid + " coins for the " + name(it) + ". If nobody beats it, it's yours tomorrow.";
    }

    private static void refund(UUID village, String player, int coins) {
        String key = "bank/" + player;
        String[] b = fields(village, key);
        int deposit = b == null ? 0 : (int) num(b[0]);
        String rest = b == null || b.length < 3 ? "0|0" : b[1] + "|" + b[2];
        Ledger.note(village, key, (deposit + coins) + "|" + rest);
    }

    /** Market day: put the finest spare thing up. The day after: settle it. */
    static void auctionDay(ServerLevel level, Villages.Village v, long day) {
        String[] a = fields(v.id(), "auction");
        if (a != null && a.length >= 6 && day > num(a[5])) {
            int best = (int) num(a[2]);
            if (best > 0 && !a[3].isEmpty()) {
                Ledger.addCoins(v.id(), best);
                Economy.sold(v.id(), best);
                Ledger.note(v.id(), "auction.won/" + a[3], a[0]);
                Villages.tell(v.id(), day, a[4] + " won the auction with a bid of " + best + " coins");
            }
            Ledger.note(v.id(), "auction", "");
            a = null;
        }
        if (a == null && Market.marketDay(v.id(), day)) {
            List<Budget.Offer> offers = Budget.forSale(level, v.id(), 12);
            Budget.Offer finest = null;
            for (Budget.Offer o : offers) if (o.each() >= 5 && (finest == null || o.each() > finest.each())) finest = o;
            if (finest == null) return;
            int reserve = (int) Math.max(1, Math.round(finest.each() * 0.8));
            Ledger.note(v.id(), "auction", idOf(finest.item()) + "|" + reserve + "|0||" + "|" + day);
            Villages.tell(v.id(), day, "up for auction today: " + finest.name() + ", bids from " + reserve + " coins");
        }
    }

    // ------------------------------------------------------------------ 18. caravan escort

    /** "Can I guard your next caravan?" */
    public static String escort(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "Caravans? We've no village to send them from.";
        long day = Dealings.day(f);
        Ledger.note(village, "escort/" + p.getUUID(), Long.toString(day));
        return "You'd guard our caravan? Then walk with it — the next one that sets out, keep by the carrier till it gets there, and you'll have "
            + ESCORT_PAY + " coins at the other end. Mornings, they leave.";
    }

    /** A caravan came in: an escort who walked with it is paid; the charterer of its route takes its tenth. */
    static void caravanArrived(ServerLevel level, VillageFolkEntity carrier, UUID from, UUID to, boolean trade, int paid) {
        long day = level.getDayTime() / 24000L;
        for (Player p : level.players()) {
            if (p.distanceToSqr(carrier) > 48.0 * 48.0) continue;
            String signed = Ledger.note(from, "escort/" + p.getUUID());
            if (signed == null || signed.isEmpty() || day - num(signed) > 3) continue;
            Ledger.note(from, "escort/" + p.getUUID(), "");
            int pay = Math.min(ESCORT_PAY, Ledger.coins(from));
            Ledger.takeCoins(from, pay);
            Dealings.giveCoins(p, pay);
            String name = p.getName().getString();
            for (UUID v : new UUID[]{ from, to }) {
                for (AssistantEntity a : Villages.folkOf(v)) {
                    if (a instanceof VillageFolkEntity g && g.getRandom().nextInt(3) == 0) g.persona().feelFor(p.getUUID(), name, 2);
                }
                Standing.stir(v, p.getUUID());
            }
            p.displayClientMessage(Component.literal("The caravan from " + Villages.name(from) + " is in safe. " + pay + " coins for the escort."), false);
        }
        if (trade && paid > 0) {
            String charterer = Ledger.note(from, "charter/" + to);
            if (charterer != null && !charterer.isEmpty()) {
                int cut = Math.max(1, paid / 10);
                if (Ledger.coins(from) >= cut) {
                    Ledger.takeCoins(from, cut);
                    refund(from, charterer, cut);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 19. a trade route of your own

    /** "I'd like to charter a trade route to Ravenmere." — fifty coins, a tenth of every load. */
    public static String charter(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "A route from where? We've no village.";
        Villages.Village o = Diplomacy.meant(village, text);
        if (o == null) return "A route to where? There's nobody near enough to trade with.";
        String on = Villages.name(o.id());
        String have = Ledger.note(village, "charter/" + o.id());
        if (have != null && !have.isEmpty()) {
            return have.equals(p.getUUID().toString()) ? "The route to " + on + " is yours already — your tenth goes into your account here."
                : "Somebody else holds the route to " + on + ".";
        }
        if (Ledger.relation(village, o.id()) <= Diplomacy.UNEASY) return "Trade with " + on + "? Not while there's bad blood between us.";
        if (Market.coinsHeld(p) < CHARTER_COST) return "A charter costs " + CHARTER_COST + " coins: the road, the carts, the first loads. You've " + Market.coinsHeld(p) + ".";
        Market.payOut(p, CHARTER_COST);
        Ledger.addCoins(village, CHARTER_COST);
        long day = Dealings.day(f);
        if (!Envoys.pact(village, o.id())) Envoys.sign(village, o.id(), day);
        Ledger.note(village, "charter/" + o.id(), p.getUUID().toString());
        Ledger.note(o.id(), "charter/" + village, p.getUUID().toString());
        Bonds.remember(village, o.id(), day, 3, p.getName().getString() + " chartered a trade route between us");
        return "The route to " + on + " is yours: our caravans will run it, and a tenth of every load we sell there goes into your account here.";
    }

    // ------------------------------------------------------------------ 20. where things are dear

    /** "Where's iron dear?" — what a thing sells for here and in the villages round about. */
    public static String prices(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Prices? I've no market to know them by.";
        Item it = Services.itemNamed(text);
        List<Market.Good> goods = new ArrayList<>();
        if (it != null) {
            Market.Good g = Budget.goodFor(new ItemStack(it));
            if (g != null) goods.add(g);
        } else {
            for (Market.Good g : Market.GOODS) if (g.need() != Villages.Task.NONE) goods.add(g);
        }
        if (goods.isEmpty()) return "Nobody round here deals in that.";
        List<Villages.Village> places = new ArrayList<>();
        Villages.Village home = Villages.get(village);
        if (home != null) places.add(home);
        places.addAll(Diplomacy.neighboursOf(village));
        StringBuilder sb = new StringBuilder();
        int told = 0;
        for (Market.Good g : goods) {
            if (told >= 3) break;
            Villages.Village dear = null, cheap = null;
            double hi = -1, lo = Double.MAX_VALUE;
            for (Villages.Village v : places) {
                double each = Market.each(g, Market.stock(level, v.id(), g.what()));
                if (each > hi) { hi = each; dear = v; }
                if (each < lo) { lo = each; cheap = v; }
            }
            if (dear == null || cheap == null) continue;
            String nm = g.name().toLowerCase(Locale.ROOT);
            if (dear == cheap || hi - lo < 0.05) {
                sb.append(capital(nm)).append(" goes for about the same everywhere. ");
            } else {
                sb.append(capital(nm)).append(" is dearest in ").append(Villages.name(dear.id())).append(" (")
                    .append(Budget.priceWords(hi)).append(") and cheapest in ").append(Villages.name(cheap.id()))
                    .append(" (").append(Budget.priceWords(lo)).append("). ");
            }
            told++;
        }
        if (sb.length() == 0) return "Prices are much of a muchness round here.";
        return sb.append("Buy cheap, sell dear — that's the trade.").toString().trim();
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the day

    /** Tests: market day at the players' stalls, now (PlayerStalls). */
    public static int stallDayForTests(ServerLevel level, Villages.Village v, long day) {
        return PlayerStalls.marketDayForTests(level, v);
    }

    /** Tests: put the finest spare thing up for auction now, or settle yesterday's. */
    public static void auctionForTests(ServerLevel level, Villages.Village v, long day, boolean open) {
        if (open) {
            List<Budget.Offer> offers = Budget.forSale(level, v.id(), 12);
            Budget.Offer finest = null;
            for (Budget.Offer o : offers) if (finest == null || o.each() > finest.each()) finest = o;
            if (finest != null) {
                int reserve = (int) Math.max(1, Math.round(finest.each() * 0.8));
                Ledger.note(v.id(), "auction", idOf(finest.item()) + "|" + reserve + "|0||" + "|" + day);
            }
        } else {
            auctionDay(level, v, day);
        }
    }

    private static final Map<UUID, Long> DONE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DONE.clear();
    }

    /** Once a day for a village (TownLife, after the market's morning): the bank, dividends, the auction,
     *  contracts, and friends' letters; and every round, the players' stalls (PlayerStalls). */
    public static void daily(ServerLevel level, Villages.Village v) {
        PlayerStalls.tick(level, v);                    // the players' stalls: rent run out, given back, one to let
        long t = level.getDayTime() % 24000L;
        if (t < 6000L || t > 11000L) return;
        long day = level.getDayTime() / 24000L;
        if (DONE.getOrDefault(v.id(), -1L) >= day) return;
        DONE.put(v.id(), day);
        com.jrpetty.mcassistant.Guard.run("commerce", () -> {
            if (day % 7 == 0) bankWeek(level, v, day);
            dividends(v, day);
            auctionDay(level, v, day);
            contractWeek(level, v, day);
            Dealings.letters(level, v, day);
        });
    }

    /** A contract not kept: two weeks missed and it lapses, and the village thinks less of the signer. */
    static void contractWeek(ServerLevel level, Villages.Village v, long day) {
        for (Map.Entry<String, String> e : notesStarting(v.id(), "contract/")) {
            String[] c = e.getValue().split("\\|", -1);
            if (c.length < 6) continue;
            long last = num(c[4]);
            if (day - last <= 14) continue;
            Ledger.note(v.id(), e.getKey(), "");
            try {
                sour(level, v.id(), UUID.fromString(e.getKey().substring("contract/".length())), -2);
            } catch (IllegalArgumentException ignored) {
                // a key that names nobody
            }
            Villages.tell(v.id(), day, "a supply contract lapsed: two weeks and nothing brought");
        }
    }

    /** The village thinks the less of a player who let it down (by name, if they are about). */
    static void sour(ServerLevel level, UUID village, UUID player, int delta) {
        Player p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) {
            String name = p.getName().getString();
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a instanceof VillageFolkEntity g && g.persona().knows(player)) g.persona().feelFor(player, name, delta);
            }
        }
        Standing.stir(village, player);
    }

    /** The player's business with a village, for the ledger book and talk. */
    public static String account(UUID village, UUID player) {
        List<String> out = new ArrayList<>();
        String[] b = fields(village, "bank/" + player);
        if (b != null) out.add(num(b[0]) + " coins put by" + (b.length > 1 && num(b[1]) > 0 ? ", " + num(b[1]) + " owed" : ""));
        if (fields(village, "contract/" + player) != null) out.add("a supply contract");
        if (fields(village, "stall/" + player) != null) out.add("a stall on the square");
        if (fields(village, "invest/" + player) != null) out.add("coin in the works");
        if (fields(village, "bulk/" + player) != null) out.add("an order being made");
        return String.join(", ", out);
    }
}
