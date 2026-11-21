package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * [join21] What the player's own shop (player-15) and its buildings on the property register and building orders
 * (orders-17) come to on Your finances (purse-20's FinanceSheet), town by town:
 * <ul>
 * <li><b>Its shop</b> on the Businesses tab: the week's takings, its costs (its keeper's wages and the business rates),
 *     the profit, the stock on its shelves at the going price, the till; the till and the stock among its assets, rates
 *     and wages owed among its bills. Its firm (a registered business) is listed beside it by its own books.</li>
 * <li><b>Its buildings</b> on the Property tab at the property register's valuation (PropertyMarket.value: the land, the
 *     building again at today's prices less its wear, a business's goodwill), let or not; a house that is on the register
 *     is valued there rather than at its old asking price.</li>
 * <li><b>A building going up</b> for it: what it has paid the builders so far, among its property; the stage payment or the
 *     balance due as a bill, the rest when it stands.</li>
 * </ul>
 */
public final class FinanceJoin {

    private FinanceJoin() {}

    /** The player's shop in this town. */
    static void shop(ServerLevel level, FinanceSheet.Sheet s, UUID id, String town, long day) {
        PlayerShops.Shop shop = PlayerShops.of(id, s.who);
        if (shop == null) return;
        String link = "shop:" + id;
        if (shop.till > 0) s.assets.add(new FinanceSheet.Row(FinanceSheet.OWED_TO_YOU, "Your shop's till: " + shop.name, shop.till * 100L, town, link,
            "Takings in " + shop.name + "'s till, waiting for you (booked as shop takings as they came in)."));
        long stock = 0;
        boolean seen = level.isLoaded(shop.anchor);
        CompoundTag store = PlayerFinance.sub(PlayerFinance.player(s.who), "shopStock");
        if (seen) {
            java.util.List<Container> all = new java.util.ArrayList<>(PlayerShops.shelves(level, shop));
            all.addAll(PlayerShops.stockroom(level, shop));
            for (Container c : all) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack it = c.getItem(i);
                    if (it.isEmpty() || Market.isCoin(it)) continue;
                    stock += (long) PlayerStalls.going(level, id, it) * 100L * it.getCount() / Math.max(1, PlayerStalls.lot(it));
                }
            }
            store.putLong(id.toString(), stock);
        } else {
            stock = store.getLong(id.toString());
        }
        if (stock > 0) s.assets.add(new FinanceSheet.Row(FinanceSheet.BUSINESSES, "Shop stock: " + shop.name, stock, town, link,
            "The goods in its chests at the going price" + (seen ? "." : " (as last seen).")));
        int rates = shop.licensed ? TaxBook.ratesCoins(id) : 0;
        if (shop.ratesOwed > 0) {
            s.liabilities.add(new FinanceSheet.Row(FinanceSheet.BILLS, "Rates owed: " + shop.name, shop.ratesOwed * 100L, town, link,
                "The business rates its till could not pay; taken out of the next takings."));
        }
        if (shop.keeper != null && shop.owed > 0) {
            s.liabilities.add(new FinanceSheet.Row(FinanceSheet.BILLS, "Wages owed: " + shop.keeperName, shop.owed * 100L, town, link,
                "What its keeper is owed; two days owed and it gives notice."));
        }
        if (rates > 0) FinanceSheet.bill(s, "Business rates: " + shop.name, town, rates * 100L, shop.ratesDay + PlayerShops.WEEK, false,
            "Out of its till each week.");
        long week = PlayerShops.week(shop.weekCoin) * 100L;
        long costs = rates * 100L + (shop.keeper != null ? shop.wage * 700L : 0);
        CompoundTag t = new CompoundTag();
        t.putString("name", shop.name);
        t.putString("town", town);
        t.putString("type", "Your shop" + (shop.firm == null ? "" : " (a firm's premises)"));
        t.putInt("share", Business.WHOLE);
        t.putBoolean("open", PlayerShops.state(level, shop) == PlayerShops.State.OPEN);
        t.putLong("takings", week);
        t.putLong("costs", costs);
        t.putLong("wages", shop.keeper != null ? shop.wage * 700L : 0);
        t.putLong("profit", week - costs);
        t.putLong("lastProfit", Long.MIN_VALUE);
        t.putInt("hands", shop.keeper != null ? 1 : 0);
        t.putLong("stock", stock);
        t.putLong("till", shop.till * 100L);
        t.putLong("value", stock + shop.till * 100L);
        t.putString("k", link);
        s.businesses.add(t);
    }

    /** Is this house on the register as the player's (its value is the register's then)? */
    static boolean registered(UUID id, UUID who, BlockPos anchor) {
        CompoundTag t = PropertyMarket.get(id, PropertyMarket.keyAt(anchor));
        return t != null && "player".equals(t.getString("ownerKind")) && who.toString().equals(t.getString("owner"));
    }

    /** The player's buildings on the property register here, and what is going up for it. Returns the week's rent they earn (pennies). */
    static long property(ServerLevel level, FinanceSheet.Sheet s, UUID id, String town, long day) {
        long rentWeek = 0;
        String link = "property:" + id;
        for (PropertyMarket.Property pr : PropertyMarket.ownedBy(id, s.who)) {
            PropertyMarket.Valuation val = PropertyMarket.value(level, id, pr.key());
            CompoundTag t0 = PropertyMarket.get(id, pr.key());
            boolean let = t0 != null && !t0.getString("tenant").isEmpty();
            long rent = let ? t0.getInt("rent") * 100L : 0;
            Homes.Home h = Homes.homes(id).get(pr.anchor().asLong());            // a house let to a household: its rent by the day
            if (!let && h != null && h.tenure == Homes.Tenure.PLAYER && s.who.equals(h.landlord) && !h.members.isEmpty() && h.rent > 0) {
                let = true;
                rent = h.rent * 700L;
            }
            rentWeek += rent;
            String kind = "plot".equals(pr.use()) ? "Plot" : "unfinished".equals(pr.use()) ? "Part-built" : BuildOrders.home(pr.drawing()) ? "House" : "Premises";
            s.assets.add(new FinanceSheet.Row(FinanceSheet.PROPERTY, pr.name(), val.total() * 100L, town, link,
                "On the property register at " + val.total() + "c: " + val.words()
                    + (let ? "; let at " + rent / 100 + " a week" : "")
                    + (pr.listing().isEmpty() ? "" : "; listed " + ("sale".equals(pr.listing()) ? "for sale at " + pr.asking() : "to let at " + pr.rent())) + "."));
            if (t0 != null && t0.getLong("leaseOwed") > 0) {
                s.liabilities.add(new FinanceSheet.Row(FinanceSheet.BILLS, "Ground rent owed: " + pr.name(), t0.getLong("leaseOwed"), town, link,
                    "The ground rent on its lease, owed to " + town + "."));
                FinanceSheet.bill(s, "Ground rent", town, t0.getLong("leaseOwed"), day, true, "Pay it at the property office.");
            }
            CompoundTag t = new CompoundTag();
            t.putString("name", pr.name());
            t.putString("town", town);
            t.putString("kind", kind);
            t.putLong("value", val.total() * 100L);
            t.putLong("rentIn", rent);
            t.putLong("mortgage", 0);
            t.putString("k", link);
            s.properties.add(t);
        }
        for (String oid : BuildOrders.ordersOf(id, s.who)) {
            BuildOrders.Status st = BuildOrders.status(level, oid);
            if (st == null || !(st.status.equals("open") || st.status.equals("awaiting"))) continue;
            // The works paid for so far (laid, or held for what is still to be laid): the land is its plot's line on the
            // register, and the permit, rates and registry are spent.
            CompoundTag o = BuildOrders.order(oid);
            long sunk = o == null ? 0 : o.getLong("land") + o.getLong("fees") + o.getLong("premium");
            long paid = Math.max(0, st.paid * 100L - sunk);
            s.assets.add(new FinanceSheet.Row(FinanceSheet.PROPERTY, "Going up: " + st.name, paid, town, link,
                st.percent + "% built (" + st.stage + "); " + st.paid + " of " + st.total + "c paid to the builders so far, "
                    + Businesses.pence(paid) + " of it for the works (the land is on its own line, the fees spent)"
                    + (st.refundable > 0 ? "; " + st.refundable + "c still held and refundable" : "") + "."));
            if (st.due > 0) FinanceSheet.bill(s, "Builders: " + st.name, town, st.due * 100L, day, st.status.equals("awaiting"),
                st.status.equals("awaiting") ? "The balance: the keys wait for it." : "The stage payment, now half of it is laid.");
            int rest = Math.max(0, st.total - st.paid - st.due);
            if (rest > 0) FinanceSheet.bill(s, "Builders, when it stands: " + st.name, town, rest * 100L, Math.max(day, st.etaDay), false,
                "The rest of the price, as the builders get on.");
            CompoundTag t = new CompoundTag();
            t.putString("name", "Going up: " + st.name);
            t.putString("town", town);
            t.putString("kind", "Being built (" + st.percent + "%)");
            t.putLong("value", paid);
            t.putLong("rentIn", 0);
            t.putLong("mortgage", 0);
            t.putString("k", link);
            s.properties.add(t);
        }
        return rentWeek;
    }
}
