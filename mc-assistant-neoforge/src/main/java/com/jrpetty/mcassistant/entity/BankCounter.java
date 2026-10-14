package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [earning] The bank's other two counters, on its screen beside the account and the mortgage:
 * <ul>
 * <li><b>The money changer.</b> Gold for coin and coin for gold at the mint's own rate (nine coins a bar, one a nugget:
 *     both ways the same, so nothing is made or lost changing it), emeralds and diamonds at the town's one spread
 *     (its paying price to buy, its asking price to sell). Coin out of the treasury, the stones into the stores; and
 *     back.</li>
 * <li><b>Property.</b> The rent a player's tenants have paid (kept for it by the town: Homes.payday) collected here; and a
 *     house the player owns outright sold back to the town at four fifths of its price, its tenants staying on as the
 *     town's.</li>
 * </ul>
 */
final class BankCounter {

    private BankCounter() {}

    /** What the changer deals in: the thing, and whether it goes at the mint's rate (gold) or the spread. */
    record Coinage(Item item, int mintCoins) {}

    static final List<Coinage> CHANGER = List.of(new Coinage(Items.GOLD_INGOT, Market.COINS_PER_GOLD), new Coinage(Items.GOLD_NUGGET, 1),
        new Coinage(Items.EMERALD, 0), new Coinage(Items.DIAMOND, 0));
    /** A house sold back to the town fetches this share of its price. */
    static final double HOUSE_BACK = 0.8;

    /** What the changer pays for one (whole coin): gold at the mint's rate, a stone at the town's paying price. */
    static int buys(ServerLevel level, UUID village, Coinage c) {
        return c.mintCoins() > 0 ? c.mintCoins() : Market.bidLot(level, village, new ItemStack(c.item()), 1);
    }

    /** What the changer asks for one: gold at the mint's rate, a stone at the town's asking price. */
    static int sells(ServerLevel level, UUID village, Coinage c) {
        return c.mintCoins() > 0 ? c.mintCoins() : Market.askLot(level, village, new ItemStack(c.item()), 1);
    }

    static void sections(DeskRows d, ServicePoints.Visit visit) {
        ServerPlayer p = visit.player();
        if (p == null) return;
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        d.section("The money changer");
        d.line("Gold at the mint's rate both ways (" + Market.COINS_PER_GOLD + " coins a bar); emeralds and diamonds at the town's prices. The treasury holds "
            + Ledger.coins(id) + "c.");
        int held = Market.coinsHeld(p);
        for (Coinage c : CHANGER) {
            ItemStack one = new ItemStack(c.item());
            int have = count(p, c.item());
            int buy = buys(level, id, c), sell = sells(level, id, c);
            String name = Goods.name(one);
            CompoundTag in = d.row("change", "Change", name + " for coin", "You carry " + have + "; " + buy + "c each.", one,
                have <= 0 ? "You've none on you." : buy <= 0 ? "Not worth a coin here." : Ledger.coins(id) < buy ? "The treasury is short of coin." : "");
            in.getCompound("args").putString("item", key(c.item()));
            in.getCompound("args").putBoolean("toCoin", true);
            DeskRows.amount(in, 1, Math.max(1, have), Math.max(1, Math.min(have, 9)), 1);
            int stock = Market.stock(level, id, s -> s.is(c.item()));
            CompoundTag out = d.row("change", "Buy", "Coin for " + name.toLowerCase(Locale.ROOT), sell + "c each; the stores have " + stock + ".", one,
                stock <= 0 ? "The stores have none." : held < sell ? "You need " + sell + "c." : "");
            out.getCompound("args").putString("item", key(c.item()));
            out.getCompound("args").putBoolean("toCoin", false);
            DeskRows.amount(out, 1, Math.max(1, Math.min(stock, held / Math.max(1, sell))), 1, 1);
        }
        d.section("Your property");
        int due = rentDue(id, p.getUUID());
        d.row("collectRent", "Collect", "Rent your tenants have paid", due > 0 ? due + "c waiting." : "Nothing waiting.", new ItemStack(Items.EMERALD),
            due > 0 ? "" : "No rent waiting for you here.");
        boolean any = false;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (h.tenure != Homes.Tenure.PLAYER || !p.getUUID().equals(h.landlord)) continue;
            any = true;
            int price = houseBack(id, h);
            Bank.Loan loan = Bank.playerLoan(id, p.getUUID());
            boolean owed = loan != null && loan.anchor == h.anchor.asLong();
            CompoundTag r = d.row("sellHouse", "Sell", "Sell " + Homes.address(id, visit.village(), h) + " to the town", price + "c, four fifths of its price"
                + (h.members.isEmpty() ? "." : "; your tenants stay on."), new ItemStack(Items.OAK_DOOR),
                owed ? "Pay off its mortgage first." : Ledger.coins(id) < price ? "The treasury hasn't " + price + "c just now." : "");
            r.getCompound("args").putLong("anchor", h.anchor.asLong());
        }
        if (!any) d.line("You own no house here. Stand in an empty one and ask the bank for a mortgage.");
    }

    private static String key(Item it) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString();
    }

    private static int count(ServerPlayer p, Item it) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(it) && !Shifts.isShiftItem(s)) n += s.getCount();
        return n;
    }

    static int rentDue(UUID village, UUID player) {
        String s = Ledger.note(village, "rentdue/" + player);
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int houseBack(UUID village, Homes.Home h) {
        return (int) Math.floor((h.price > 0 ? h.price : Homes.price(village, h)) * HOUSE_BACK);
    }

    static ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerPlayer p = visit.player();
        if (p == null) return ServicePoints.Result.no("");
        return switch (action) {
            case "change" -> change(visit, args);
            case "collectRent" -> collect(visit);
            case "sellHouse" -> sellHouse(visit, args.getLong("anchor"));
            default -> ServicePoints.Result.no("");
        };
    }

    /** Change so many of a thing for coin (toCoin) or coin for it, at the changer's rates. */
    static ServicePoints.Result change(ServicePoints.Visit visit, CompoundTag args) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        Coinage c = null;
        for (Coinage x : CHANGER) if (key(x.item()).equals(args.getString("item"))) c = x;
        int n = Math.max(1, args.getInt("amount"));
        if (c == null || p == null) return ServicePoints.Result.no("The changer doesn't deal in that.");
        ItemStack one = new ItemStack(c.item());
        if (args.getBoolean("toCoin")) {
            n = Math.min(n, count(p, c.item()));
            int each = buys(level, id, c);
            if (n <= 0 || each <= 0) return ServicePoints.Result.no("You've none to change.");
            int coins = each * n;
            if (Ledger.coins(id) < coins) return ServicePoints.Result.no("The treasury hasn't " + coins + "c to change that.");
            ItemStack left = Market.intoStores(level, id, one.copyWithCount(n));
            if (!left.isEmpty()) return ServicePoints.Result.no("The vault has no room for it just now.");
            Buyers.takeFrom(p, Goods.lineId(one), n);
            Earnings.fromTreasury(p, id, coins, Earnings.Source.BANK, Villages.name(id) + " bank", "for " + Goods.words(one, n));
            return ServicePoints.Result.done("Changed " + Goods.words(one, n) + " for " + coins + "c.");
        }
        int each = sells(level, id, c);
        Item it = c.item();
        int stock = Market.stock(level, id, s -> s.is(it));
        n = Math.min(n, stock);
        if (n <= 0) return ServicePoints.Result.no("The stores have none.");
        int coins = each * n;
        if (Market.coinsHeld(p) < coins) return ServicePoints.Result.no("That's " + coins + "c; you have " + Market.coinsHeld(p) + ".");
        if (!TownWork.take(level, visit.village(), s -> s.is(it), n)) return ServicePoints.Result.no("The stores ran short.");
        Market.payOut(p, coins);
        Ledger.addCoins(id, coins);
        Economy.sold(id, coins);
        ItemStack got = one.copyWithCount(n);
        if (!p.getInventory().add(got)) p.drop(got, false);
        return ServicePoints.Result.done("Bought " + Goods.words(one, n) + " for " + coins + "c.");
    }

    /** The rent waiting for the player, into its pack. */
    static ServicePoints.Result collect(ServicePoints.Visit visit) {
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        int due = rentDue(id, p.getUUID());
        if (due <= 0) return ServicePoints.Result.no("No rent waiting for you here.");
        Ledger.note(id, "rentdue/" + p.getUUID(), "0");
        Earnings.giveCoins(p, due);
        Earnings.record(p, id, Earnings.Source.PROPERTY, due, "your tenants in " + Villages.name(id), "in rent");
        return ServicePoints.Result.done("Your rent: " + due + "c.");
    }

    /** A house the player owns outright, sold back to the town: four fifths of its price out of the treasury. */
    static ServicePoints.Result sellHouse(ServicePoints.Visit visit, long anchor) {
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        Homes.Home h = Homes.homes(id).get(anchor);
        if (h == null || h.tenure != Homes.Tenure.PLAYER || !p.getUUID().equals(h.landlord)) return ServicePoints.Result.no("That isn't your house.");
        Bank.Loan loan = Bank.playerLoan(id, p.getUUID());
        if (loan != null && loan.anchor == anchor) return ServicePoints.Result.no("Pay off its mortgage first.");
        int price = houseBack(id, h);
        if (Ledger.coins(id) < price) return ServicePoints.Result.no("The treasury hasn't " + price + "c just now.");
        String where = Homes.address(id, visit.village(), h);
        h.tenure = Homes.Tenure.RENTED;
        h.landlord = null;
        h.landlordName = "";
        h.toLet = false;
        h.price = Homes.price(id, h);
        h.rent = Homes.rent(id, h);
        Homes.save(id, h);
        Earnings.fromTreasury(p, id, price, Earnings.Source.PROPERTY, Villages.name(id), "for " + where);
        Villages.tell(id, visit.level().getDayTime() / 24000L, p.getName().getString() + " sold " + where + " to the town for " + price + " coin");
        return ServicePoints.Result.done("Sold " + where + " to the town for " + price + "c.");
    }
}
