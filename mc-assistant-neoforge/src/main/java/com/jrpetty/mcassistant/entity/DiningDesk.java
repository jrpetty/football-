package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.DiningMenu.Course;
import com.jrpetty.mcassistant.entity.DiningPlaces.Place;
import com.jrpetty.mcassistant.entity.DiningPlaces.Table;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [dining] The order screen's business (ServicePoints.Desk, kind "dining"): today's card by course, a basket, the order
 * put in (at the table, to take away, or brought to you; paid now or from the bill), and the bill with its tip. Every
 * request is checked again here on the server (the dish still on the card and not sold out, the table yours or free, the
 * coin in your pack) before a coin or a dish moves; the screen is sent again whenever the order moves on (a dish off the
 * stove, on its way, on the table).
 */
final class DiningDesk implements ServicePoints.Desk {

    static final DiningDesk INSTANCE = new DiningDesk();

    private DiningDesk() {}

    /** The most of one dish, and of lines, in a basket. */
    static final int MOST_OF_ONE = 4, MOST_LINES = 8;
    /** The tips a player may leave, in coin. */
    static final int[] TIPS = { 0, 1, 2, 3 };

    @Override
    public String kind(ServiceRole role) {
        return "dining";
    }

    /** The place the screen is of: the one it was opened at, else the point's building, else the nearest. */
    @Nullable
    static Place placeOf(ServicePoints.Visit visit) {
        ServerLevel level = visit.level();
        long anchor = visit.state().getLong("place");
        if (anchor != 0) {
            for (Ledger.Building b : Ledger.buildings(visit.village().id())) {
                if (b.anchor().asLong() == anchor) return DiningPlaces.place(level, visit.village().id(), b);
            }
        }
        if (visit.point() != null) {
            Place p = DiningPlaces.place(level, visit.village().id(), visit.point().building());
            if (p != null) return p;
        }
        BlockPos near = visit.player() != null ? visit.player().blockPosition() : visit.village().centre();
        return DiningPlaces.nearest(level, visit.village().id(), null, near);
    }

    @Nullable
    private static Table tableOf(ServicePoints.Visit visit, Place p) {
        int n = visit.state().getInt("table");
        return n <= 0 ? null : p.table(n);
    }

    @Override
    public int fingerprint(ServicePoints.Visit visit) {
        int h = visit.state().hashCode();
        ServerPlayer p = visit.player();
        if (p != null) {
            Dining.Order o = Dining.orderOf(p.getUUID());
            if (o != null) {
                h = h * 31 + o.id;
                for (Dining.Line l : o.lines) h = h * 31 + l.step.ordinal();
                h = h * 31 + (o.paid ? 1 : 0);
            }
        }
        return h * 31 + (int) (visit.level().getGameTime() / DiningMenu.CARD_FOR);
    }

    // ------------------------------------------------------------------ the screen's body

    @Override
    public CompoundTag body(ServicePoints.Visit visit, String tab) {
        ServerLevel level = visit.level();
        CompoundTag t = new CompoundTag();
        Place p = placeOf(visit);
        if (p == null) {
            t.putString("empty", "There's no café, tavern or restaurant here.");
            return t;
        }
        Table table = tableOf(visit, p);
        DiningMenu.Meal meal = DiningMenu.meal(level.getDayTime());
        t.putString("place", Dining.cap(p.words()));
        t.putInt("table", table == null ? 0 : table.number());
        t.putString("meal", meal == DiningMenu.Meal.BREAKFAST ? "Breakfast" : meal == DiningMenu.Meal.LUNCH ? "Lunch" : "Dinner");
        List<DiningMenu.Line> card = DiningMenu.card(level, p, meal);
        CompoundTag basket = visit.state().getCompound("basket");
        ListTag courses = new ListTag();
        for (Course c : Course.values()) {
            ListTag dishes = new ListTag();
            for (DiningMenu.Line l : card) {
                if (l.dish().course() != c) continue;
                CompoundTag d = new CompoundTag();
                d.putString("id", l.dish().id());
                d.putString("name", l.dish().name());
                d.putInt("price", l.price());
                d.putString("about", l.about());
                d.putInt("hunger", l.hunger());
                d.putBoolean("soldOut", l.soldOut());
                d.putBoolean("special", l.special());
                d.putBoolean("local", l.local());
                d.putInt("count", basket.getInt(l.dish().id()));
                d.put("icon", l.dish().one().save(level.registryAccess()));
                dishes.add(d);
            }
            CompoundTag course = new CompoundTag();
            course.putString("title", c.title);
            course.put("dishes", dishes);
            courses.add(course);
        }
        t.put("courses", courses);
        // The basket, with its total.
        ListTag lines = new ListTag();
        int total = 0;
        for (String id : basket.getAllKeys()) {
            DiningMenu.Line l = line(card, id);
            int n = basket.getInt(id);
            if (l == null || n <= 0) continue;
            CompoundTag x = new CompoundTag();
            x.putString("id", id);
            x.putString("name", l.dish().name());
            x.putInt("count", n);
            x.putInt("price", l.price() * n);
            x.putBoolean("soldOut", l.soldOut());
            lines.add(x);
            total += l.price() * n;
        }
        t.put("basket", lines);
        t.putInt("total", total);
        t.putString("mode", visit.state().contains("mode") ? visit.state().getString("mode") : table != null ? "table" : "takeaway");
        t.putBoolean("payNow", visit.state().getBoolean("payNow"));
        // The order in, and its bill.
        ServerPlayer pl = visit.player();
        Dining.Order o = pl == null ? null : Dining.orderOf(pl.getUUID());
        if (o != null) {
            CompoundTag order = new CompoundTag();
            order.putInt("id", o.id);
            order.putString("mode", o.mode.name().toLowerCase(java.util.Locale.ROOT));
            order.putInt("table", o.table);
            ListTag ol = new ListTag();
            for (Dining.Line l : o.lines) {
                DiningMenu.Dish d = l.dishDef();
                CompoundTag x = new CompoundTag();
                x.putString("name", d == null ? l.dish : d.name());
                x.putString("step", stepWords(l, o));
                x.putInt("state", l.step.ordinal());
                x.putInt("price", l.step == Dining.Step.STRUCK ? 0 : l.price);
                x.putBoolean("struck", l.step == Dining.Step.STRUCK);
                ol.add(x);
            }
            order.put("lines", ol);
            order.putInt("total", o.total());
            order.putInt("paidCoins", o.paidCoins);
            order.putInt("due", Math.max(0, o.total() - o.paidCoins));
            order.putBoolean("paid", o.paid);
            order.putBoolean("finished", o.finished());
            t.put("order", order);
        }
        return t;
    }

    @Nullable
    private static DiningMenu.Line line(List<DiningMenu.Line> card, String id) {
        for (DiningMenu.Line l : card) if (l.dish().id().equals(id)) return l;
        return null;
    }

    /** Where a dish of an order is, in words. */
    static String stepWords(Dining.Line l, Dining.Order o) {
        return switch (l.step) {
            case QUEUED -> "Ordered";
            case COOKING -> l.cook.isEmpty() ? "Cooking" : l.cook + " is cooking it";
            case PASS -> "Ready on the pass";
            case CARRYING -> o.mode == Dining.Mode.DELIVERY ? "On its way to you" : "Coming to your table";
            case SERVED -> o.mode == Dining.Mode.TABLE ? "On your table" : "Handed over";
            case EATEN -> "Finished";
            case STRUCK -> "Off the menu: not charged";
        };
    }

    // ------------------------------------------------------------------ the buttons

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerPlayer p = visit.player();
        Place place = placeOf(visit);
        if (p == null || place == null) return ServicePoints.Result.no("");
        return switch (action) {
            case "add" -> add(visit, place, args.getString("id"), 1);
            case "remove" -> add(visit, place, args.getString("id"), -1);
            case "clear" -> {
                visit.state().remove("basket");
                yield ServicePoints.Result.said("");
            }
            case "mode" -> mode(visit, place, args.getString("mode"));
            case "payNow" -> {
                visit.state().putBoolean("payNow", args.getBoolean("on"));
                yield ServicePoints.Result.said("");
            }
            case "order" -> order(visit, place, p);
            case "pay" -> pay(visit, p, args.getInt("tip"));
            default -> ServicePoints.Result.no("");
        };
    }

    private static ServicePoints.Result add(ServicePoints.Visit visit, Place place, String id, int by) {
        CompoundTag basket = visit.state().getCompound("basket");
        DiningMenu.Line l = line(DiningMenu.card(visit.level(), place, DiningMenu.meal(visit.level().getDayTime())), id);
        if (l == null) return ServicePoints.Result.no("That's not on the menu now.");
        if (by > 0 && l.soldOut()) return ServicePoints.Result.no(l.dish().name() + " is sold out.");
        int n = Math.max(0, Math.min(MOST_OF_ONE, basket.getInt(id) + by));
        if (by > 0 && n == basket.getInt(id)) return ServicePoints.Result.no("That's as many as the kitchen sends at once.");
        if (by > 0 && !basket.contains(id) && basket.size() >= MOST_LINES) return ServicePoints.Result.no("That's a full order already.");
        if (n == 0) basket.remove(id); else basket.putInt(id, n);
        visit.state().put("basket", basket);
        return ServicePoints.Result.said("");
    }

    private static ServicePoints.Result mode(ServicePoints.Visit visit, Place place, String mode) {
        if (mode.equals("table") && tableOf(visit, place) == null) return ServicePoints.Result.no("Sit at a table to be served there.");
        if (!mode.equals("table") && !mode.equals("takeaway") && !mode.equals("delivery")) return ServicePoints.Result.no("");
        visit.state().putString("mode", mode);
        return ServicePoints.Result.said("");
    }

    /** The basket put in as an order: each dish still to be had, the table yours, the coin there if you pay now. */
    private static ServicePoints.Result order(ServicePoints.Visit visit, Place place, ServerPlayer p) {
        ServerLevel level = visit.level();
        CompoundTag basket = visit.state().getCompound("basket");
        if (basket.isEmpty()) return ServicePoints.Result.no("Choose something from the menu first.");
        String m = visit.state().contains("mode") ? visit.state().getString("mode") : tableOf(visit, place) != null ? "table" : "takeaway";
        Dining.Mode mode = m.equals("table") ? Dining.Mode.TABLE : m.equals("delivery") ? Dining.Mode.DELIVERY : Dining.Mode.TAKEAWAY;
        Table table = tableOf(visit, place);
        if (mode == Dining.Mode.TABLE && table == null) return ServicePoints.Result.no("Sit at a table to be served there.");
        Dining.Order mine = Dining.orderOf(p.getUUID());
        if (mine != null && (mine.mode != mode || mode == Dining.Mode.TABLE && mine.table != (table == null ? 0 : table.number()))) {
            return ServicePoints.Result.no("You've an order in already: settle it first.");
        }
        if (mode == Dining.Mode.TABLE) {
            Dining.Order there = Dining.atTable(place.building, table.number());
            if (there != null && !there.diner.equals(p.getUUID())) return ServicePoints.Result.no("That table's taken.");
        }
        List<String> dishes = new ArrayList<>();
        for (String id : basket.getAllKeys()) for (int i = 0; i < basket.getInt(id); i++) dishes.add(id);
        Dining.Order o = Dining.place(level, place, table == null ? 0 : table.number(), p.getUUID(), p.getName().getString(), true, mode, dishes, null);
        if (o == null) return ServicePoints.Result.no("None of that can be had just now: it's sold out.");
        boolean payNow = visit.state().getBoolean("payNow") || mode != Dining.Mode.TABLE;
        if (payNow) {
            int due = o.total();
            if (Market.coinsHeld(p) < due) {
                Dining.ORDERS.remove(o.id);
                return ServicePoints.Result.no("That comes to " + due + (due == 1 ? " coin" : " coins") + "; you've " + Market.coinsHeld(p) + ".");
            }
            Market.payOut(p, due);
            Dining.takings(level, o, due);
            o.paidCoins = due;
            o.paid = true;
        }
        if (mine != null) Dining.merge(mine, o);
        visit.state().remove("basket");
        int n = dishes.size(), got = o.lines.size();
        String what = got + (got == 1 ? " dish" : " dishes") + (got < n ? " (the rest is sold out)" : "");
        return switch (mode) {
            case TABLE -> ServicePoints.Result.done("Ordered: " + what + ". The kitchen's on it" + (payNow ? "; paid." : "; pay from the bill."));
            case TAKEAWAY -> ServicePoints.Result.done("Ordered to take away: " + what + ", paid. Wait by the counter.");
            case DELIVERY -> ServicePoints.Result.done("Ordered: " + what + ", paid. A runner will bring it to you in town.");
        };
    }

    private static ServicePoints.Result pay(ServicePoints.Visit visit, ServerPlayer p, int tip) {
        Dining.Order o = Dining.orderOf(p.getUUID());
        if (o == null) return ServicePoints.Result.no("You've nothing to pay for.");
        int t = Math.max(0, Math.min(TIPS[TIPS.length - 1], tip));
        String no = Dining.pay(visit.level(), p, o, t);
        if (no != null) return ServicePoints.Result.no(no);
        return ServicePoints.Result.done("Paid, thank you" + (t > 0 ? ", and " + t + " for the waiter" : "") + ". Come again!");
    }
}
