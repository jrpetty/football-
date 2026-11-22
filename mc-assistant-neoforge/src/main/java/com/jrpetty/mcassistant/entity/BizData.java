package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [biz30] A business's data, for its owner's dashboard (client BusinessDataScreen): what it sold, how many a day, to whom
 * and when, what it cost, what is left on its shelves and how long that lasts, who kept it and for how long, and a word of
 * advice from the numbers. Read straight off its own day books (Business.Day: the same books its till and its costs are
 * booked into as they happen, so the data cannot drift from the money), eight weeks of them day by day.
 *
 * <p>Its owner (a player's own firm or shop, or one it has a stake in) and an operator see it all; anybody else the town's
 * public summary of it (this week's takings and profit, and which way it is going). With more than one business the
 * owner also gets them side by side ("All businesses"), a click into any. Sent once when asked (the screen asks again
 * when its range changes), a few kilobytes.
 */
public final class BizData {

    private BizData() {}

    /** Ranges the screen offers, days. */
    public static final int[] RANGES = { 7, 28, 56 };

    // ------------------------------------------------------------------ open hours and the keeper's

    private static final Map<UUID, Long> LAST = new HashMap<>();

    /**
     * Every visit of the town's round (TownLife): the minutes since the last onto each business's book, open (a firm in the
     * working day, a player's shop while it says OPEN) and kept (a hand of the firm, the shop's keeper, at the counter).
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Long last = LAST.put(v.id(), now);
        if (last == null || now <= last) return;
        int minutes = (int) Math.min(240, (now - last) * 60 / 1000);
        if (minutes <= 0) { LAST.put(v.id(), last); return; }
        boolean day = level.getDayTime() % 24000L < 12000L;
        for (Business b : Enterprise.open(v.id())) {
            if (!day || b.at == null) continue;
            boolean kept = false;
            for (VillageFolkEntity f : Enterprise.hands(v.id(), b)) {
                if (f.isAlive() && !f.isSleeping() && f.blockPosition().closerThan(b.at, 16)) { kept = true; break; }
            }
            Businesses.kept(level, b, minutes, kept ? minutes : 0);
        }
        for (PlayerShops.Shop s : PlayerShops.all(v.id())) {
            if (PlayerShops.state(level, s) != PlayerShops.State.OPEN) continue;
            boolean kept = false;
            if (s.keeper != null && level.getEntity(s.keeper) instanceof VillageFolkEntity k && k.isAlive()) kept = k.blockPosition().closerThan(s.anchor, 12);
            Businesses.kept(level, Businesses.shopBook(level, s), minutes, kept ? minutes : 0);
        }
    }

    // ------------------------------------------------------------------ who sees what

    /** Its owner, an operator: the whole of it. */
    public static boolean full(Business b, @Nullable ServerPlayer p) {
        return p != null && (p.hasPermissions(2) || b.ownedBy(p.getUUID()));
    }

    /** Every open business this player owns, in every town: town id and business. */
    public static List<Map.Entry<UUID, Business>> mine(ServerPlayer p) {
        List<Map.Entry<UUID, Business>> out = new ArrayList<>();
        for (Villages.Village v : Villages.every()) {
            for (Business b : Businesses.all(v.id())) {
                if (b.open && b.ownedBy(p.getUUID()) && !b.id.startsWith("stall:")) out.add(Map.entry(v.id(), b));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the data

    /** One day's figures for the charts: {day, revenue, stock, wages, rent and tax, other, net, units, sales, customers,
     *  players, repeats, open minutes, kept minutes, missed}. */
    static long[] row(Business.Day d) {
        long units = 0, missed = 0;
        for (Business.Line l : d.lines.values()) { units += l.units; missed += l.missed; }
        return new long[]{ d.day, d.revenue, d.cogs, d.wages, d.rent + d.taxes, d.other + d.losses + d.interest, d.net(), units, d.trans,
            d.customers, d.players, d.repeats, d.openMin, d.keptMin, missed };
    }

    /** A period's figures: {revenue, costs, net, customers, sales, units}. */
    static long[] period(Business.Day d) {
        long units = 0;
        for (Business.Line l : d.lines.values()) units += l.units;
        return new long[]{ d.revenue, d.revenue - d.net(), d.net(), d.customers, d.trans, units };
    }

    /** The data for its screen; {@code range} days of detail (7, 28 or 56). */
    public static CompoundTag build(ServerLevel level, UUID village, Business b, @Nullable ServerPlayer viewer, int range) {
        range = range <= 7 ? 7 : range <= 28 ? 28 : 56;
        long today = Businesses.today(level);
        CompoundTag t = new CompoundTag();
        t.putString("id", b.id);
        t.putString("name", b.name);
        BusinessTypes.BusinessType kind = Enterprise.isFirm(b) ? Enterprise.kindOf(village, b) : null;
        t.putString("kind", b.id.startsWith("shop:") ? "Your shop" : kind != null ? kind.title() : b.type.title);
        t.putString("town", Villages.name(village));
        t.putString("where", b.at == null ? "" : FolkProfileBiz.where(level, village, b.at));
        t.putString("owners", b.ownersWords());
        t.putLong("today", today);
        t.putLong("opened", b.opened);
        t.putInt("range", range);
        t.putLong("till", b.till);
        t.putLong("hour", Businesses.clockHour(level));
        boolean full = full(b, viewer);
        t.putBoolean("full", full);
        Business.Day week = b.last(today, 7), before = b.last(today - 7, 7), day = b.last(today, 1), yday = b.last(today - 1, 1);
        t.put("p_today", new LongArrayTag(period(day)));
        t.put("p_yday", new LongArrayTag(period(yday)));
        t.put("p_week", new LongArrayTag(period(week)));
        t.put("p_before", new LongArrayTag(period(before)));
        t.put("p_all", new LongArrayTag(period(b.allTime)));
        if (viewer != null) rollup(level, viewer, t);
        if (!full) {
            t.putString("advice", "You see what the town tells of it: its week's takings and profit. Its owner sees the rest.");
            return t;
        }
        // the days, oldest first, a row each (a day with nothing booked is a row of noughts)
        Map<Long, Business.Day> byDay = new HashMap<>();
        for (Business.Day d : b.days) byDay.put(d.day, d);
        ListTag days = new ListTag();
        Business.Day span = new Business.Day();
        long from = Math.max(today - range + 1, Math.min(b.opened, today - 6));    // not the days before it opened (a week at least)
        t.putLong("from", from);
        for (long d = from; d <= today; d++) {
            Business.Day bd = byDay.get(d);
            if (bd == null) { bd = new Business.Day(); bd.day = d; }
            days.add(new LongArrayTag(row(bd)));
            span.addAll(bd);
        }
        t.put("days", days);
        // its wares over the range: units, revenue, cost, made, bought in, missed; today's units; on hand
        ListTag items = new ListTag();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(span.lines.keySet());
        for (Map.Entry<String, Business.Lot> e : b.stock.entrySet()) if (e.getValue().qty > 0) keys.add(e.getKey());
        for (String k : keys) {
            Business.Line l = span.lines.getOrDefault(k, new Business.Line());
            Business.Lot lot = b.stock.get(k);
            Business.Line td = day.lines.get(k);
            long[] perDay = new long[(int) (today - from + 1)];
            int i = 0;
            for (long d = from; d <= today; d++, i++) {
                Business.Day bd = byDay.get(d);
                Business.Line x = bd == null ? null : bd.lines.get(k);
                perDay[i] = x == null ? 0 : x.units;
            }
            if (k.startsWith("service/") && l.units == 0) continue;
            CompoundTag c = new CompoundTag();
            c.putString("k", k);
            c.putString("n", k.startsWith("service/") ? "Service" : Businesses.nameOf(k));
            c.putLongArray("v", new long[]{ l.units, l.revenue, l.cogs, l.made, l.in, l.missed, td == null ? 0 : td.units,
                lot == null ? 0 : lot.qty, lot == null ? 0 : lot.cost, l.used });
            c.putLongArray("d", perDay);
            items.add(c);
        }
        t.put("items", items);
        // its hands and their pay over the range
        ListTag staff = new ListTag();
        Map<UUID, long[]> pay = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (long d = today - range + 1; d <= today; d++) {
            Business.Day bd = byDay.get(d);
            if (bd == null) continue;
            for (Map.Entry<UUID, Long> e : bd.staffPay.entrySet()) {
                long[] a = pay.computeIfAbsent(e.getKey(), k -> new long[2]);
                a[0] += e.getValue();
                a[1]++;
                names.put(e.getKey(), bd.staffNames.getOrDefault(e.getKey(), "?"));
            }
        }
        List<VillageFolkEntity> hands = Enterprise.isFirm(b) ? Enterprise.hands(village, b) : List.of();
        for (VillageFolkEntity f : hands) if (!pay.containsKey(f.getUUID())) { pay.put(f.getUUID(), new long[2]); names.put(f.getUUID(), f.displayNameCap()); }
        if (b.id.startsWith("shop:")) {
            for (PlayerShops.Shop s : PlayerShops.all(village)) {
                if (!b.id.equals("shop:" + s.key()) || s.keeper == null) continue;
                if (!pay.containsKey(s.keeper)) { pay.put(s.keeper, new long[2]); names.put(s.keeper, s.keeperName); }
            }
        }
        for (Map.Entry<UUID, long[]> e : pay.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("n", names.getOrDefault(e.getKey(), "?"));
            c.putLong("pay", e.getValue()[0]);
            c.putLong("days", e.getValue()[1]);
            boolean now = false;
            for (VillageFolkEntity f : hands) if (f.getUUID().equals(e.getKey())) now = true;
            if (b.id.startsWith("shop:")) now = true;
            c.putBoolean("now", now);
            staff.add(c);
        }
        t.put("staff", staff);
        t.putIntArray("hours", span.hours);
        t.put("span", new LongArrayTag(row(span)));
        ListTag advice = new ListTag();
        for (String a : advice(b, span, range, week, before, byDay, today)) advice.add(StringTag.valueOf(a));
        t.put("advice_all", advice);
        t.putString("advice", advice.isEmpty() ? "" : advice.getString(0));
        return t;
    }

    /** The viewer's businesses side by side: id, name, kind, town, week's takings, profit, last week's profit. */
    static void rollup(ServerLevel level, ServerPlayer viewer, CompoundTag t) {
        List<Map.Entry<UUID, Business>> mine = mine(viewer);
        if (mine.size() < 2) return;
        long today = Businesses.today(level);
        ListTag firms = new ListTag();
        for (Map.Entry<UUID, Business> e : mine) {
            Business b = e.getValue();
            Business.Day w = b.last(today, 7), pw = b.last(today - 7, 7);
            CompoundTag c = new CompoundTag();
            c.putString("id", b.id);
            c.putString("n", b.name);
            BusinessTypes.BusinessType k = Enterprise.isFirm(b) ? Enterprise.kindOf(e.getKey(), b) : null;
            c.putString("kind", b.id.startsWith("shop:") ? "Shop" : k != null ? k.title() : b.type.title);
            c.putString("town", Villages.name(e.getKey()));
            c.putLongArray("v", new long[]{ w.revenue, w.net(), pw.revenue, pw.net(), b.till, w.customers });
            firms.add(c);
        }
        t.put("firms", firms);
    }

    // ------------------------------------------------------------------ a word from the numbers

    /** What the numbers say, the most pressing first: a ware that runs out, costs eating the takings, slow stock. */
    static List<String> advice(Business b, Business.Day span, int range, Business.Day week, Business.Day before, Map<Long, Business.Day> byDay, long today) {
        List<String> out = new ArrayList<>();
        // a ware that sells out: the most sales missed
        String outKey = null;
        long outN = 0;
        for (Map.Entry<String, Business.Line> e : week.lines.entrySet()) {
            if (e.getValue().missed > outN) { outN = e.getValue().missed; outKey = e.getKey(); }
        }
        if (outKey != null && outN >= 2) {
            out.add(Businesses.nameOf(outKey) + " sells out: " + outN + " sales missed this week for want of it on the shelf. Stock more, or raise the price.");
        }
        if (week.revenue > 0) {
            long wagePct = week.wages * 100 / week.revenue, goodsPct = week.cogs * 100 / week.revenue;
            if (wagePct >= 50) out.add("Wages are " + wagePct + "% of sales. Consider shorter hours or fewer hands, or sell more in the hours you keep.");
            if (goodsPct >= 85) out.add("Its goods cost " + goodsPct + "% of what they sell for. Raise the price or find a cheaper supplier.");
            if (week.net() < 0 && wagePct < 50 && goodsPct < 85) out.add("It lost " + Businesses.pence(-week.net()) + " this week on takings of " + Businesses.pence(week.revenue)
                + ". Its rent, taxes and running costs want more sales: a better spot, a lower price to draw buyers, or fewer costs.");
        }
        // stock running short at the rate it sells, and stock that does not sell
        for (Map.Entry<String, Business.Lot> e : b.stock.entrySet()) {
            Business.Line l = week.lines.get(e.getKey());
            double perDay = l == null ? 0 : l.units / 7.0;
            long qty = e.getValue().qty;
            if (perDay >= 1 && qty < perDay * 2) {
                out.add(Businesses.nameOf(e.getKey()) + ": " + qty + " left, under two days at " + String.format(Locale.ROOT, "%.0f", perDay) + " a day. Order "
                    + Math.max(1, Math.round(perDay * 7 - qty)) + " more.");
                break;
            }
        }
        for (Map.Entry<String, Business.Lot> e : b.stock.entrySet()) {
            Business.Line l = week.lines.get(e.getKey());
            if (e.getValue().qty >= 8 && (l == null || l.units == 0) && today - b.opened >= 7 && !Enterprise.isFirm(b)) {
                out.add(e.getValue().qty + " " + Businesses.nameOf(e.getKey()).toLowerCase(Locale.ROOT) + " sat unsold all week. Lower its price, or stop stocking it.");
                break;
            }
        }
        long quiet = 0;
        for (long d = today; d > today - 3; d--) {
            Business.Day bd = byDay.get(d);
            if (bd == null || bd.trans == 0 && bd.revenue == 0) quiet++;
        }
        if (quiet == 3 && today - b.opened >= 3) out.add("Nobody has bought in three days. Is it open, kept and stocked, and are its prices fair?");
        if (week.openMin > 120 && week.keptMin * 2 < week.openMin) out.add("Its counter stood unkept for " + (100 - week.keptMin * 100 / week.openMin)
            + "% of its open hours. A keeper (or you) behind it would sell more.");
        if (out.isEmpty()) {
            if (before.revenue > 0 && week.revenue > before.revenue) out.add("Going well: takings up " + ((week.revenue - before.revenue) * 100 / before.revenue) + "% on last week.");
            else if (week.revenue > 0) out.add("Steady. Its best hours and its best seller are below: keep those stocked.");
            else out.add("No sales yet this week. Stock it, open it and keep it, and the folk will find it.");
        }
        return out;
    }

    // ------------------------------------------------------------------ the wire

    /** Send it to a player (the screen opens, or takes the new range). */
    public static void send(ServerPlayer p, UUID village, Business b, int range) {
        CompoundTag t = build(p.serverLevel(), village, b, p, range);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.BizDataPayload(t));
    }

    /** A business by id in any town, for the screen's ask (the town it is in, or null). */
    @Nullable
    public static Map.Entry<UUID, Business> find(String id) {
        for (Villages.Village v : Villages.every()) {
            Business b = Businesses.of(v.id(), id);
            if (b != null) return Map.entry(v.id(), b);
        }
        return null;
    }

    /** The screen asked: this business's data at this range (an id it may see), else its owner's first. */
    public static void ask(ServerPlayer p, String id, int range) {
        Map.Entry<UUID, Business> e = id.isEmpty() ? null : find(id);
        if (e == null) {
            List<Map.Entry<UUID, Business>> mine = mine(p);
            if (mine.isEmpty()) {
                p.displayClientMessage(net.minecraft.network.chat.Component.literal("You have no business yet: the U page's Start a business, or a shop of your own."), true);
                return;
            }
            e = mine.get(0);
        }
        send(p, e.getKey(), e.getValue(), range);
    }

    /** The near spot's business for a counter: the player's shop or firm whose premises these are. */
    @Nullable
    public static Map.Entry<UUID, Business> at(ServerLevel level, BlockPos pos) {
        for (Villages.Village v : Villages.every()) {
            for (PlayerShops.Shop s : PlayerShops.all(v.id())) {
                if (s.anchor != null && s.anchor.closerThan(pos, 8)) return Map.entry(v.id(), Businesses.shopBook(level, s));
            }
            for (Business b : Enterprise.open(v.id())) if (b.at != null && b.at.closerThan(pos, 8)) return Map.entry(v.id(), b);
        }
        return null;
    }
}
