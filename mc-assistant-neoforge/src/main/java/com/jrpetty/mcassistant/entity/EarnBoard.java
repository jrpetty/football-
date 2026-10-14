package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.EarnMath;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [earning] The Earnings page (client/EarningsPage, on the town's books under Economy, and /village earnings): the
 * player's purse and what it holds in each town, today's and the week's earnings by source, the job skills and the
 * shifts worked, and the ways to earn in the town it stands in now (open shifts, wanted orders, the night's bounty,
 * haul contracts, the best prices for what it carries here and in the towns about), each with its reward, how far and
 * by when, and its place to set a marker on. Written when asked for, never on a tick.
 */
public final class EarnBoard {

    private EarnBoard() {}

    /** How many of the player's things the page prices across the towns, and how many towns it looks at. */
    static final int PRICED = 5, TOWNS = 4;

    public static CompoundTag page(ServerLevel level, ServerPlayer p) {
        CompoundTag t = new CompoundTag();
        long day = level.getDayTime() / 24000L;
        t.putInt("purse", Market.coinsHeld(p));
        ListTag labels = new ListTag();
        for (Earnings.Source s : Earnings.Source.values()) labels.add(StringTag.valueOf(s.label));
        t.put("sources", labels);
        t.putIntArray("today", Earnings.earned(p, null, day, 1));
        t.putIntArray("week", Earnings.earned(p, null, day, 7));
        t.putIntArray("all", sized(Earnings.data(p).getIntArray("all")));
        Villages.Village here = PlayerServices.townAt(level, p.blockPosition());
        if (here == null) here = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 4);
        t.putString("town", here == null ? "" : Villages.name(here.id()));
        t.put("towns", towns(level, p, here, day));
        t.put("skills", skills(p));
        ListTag hist = new ListTag();
        for (String h : Shifts.history(p)) hist.add(StringTag.valueOf(h));
        t.put("history", hist);
        String[] job = Shifts.standing(p);
        if (job != null) {
            ShiftRole r = ShiftRole.of(job[1]);
            UUID town = Earnings.uuid(job[0]);
            t.putString("standing", (r == null ? job[1] : r.title) + " in " + (town == null ? "?" : Villages.name(town)));
        }
        Shifts.Shift s = Shifts.of(p);
        if (s != null) t.putString("shift", s.title() + " at " + Villages.name(s.village) + ": " + s.tasks + " tasks, " + s.pay + "c so far.");
        String haul = HaulContracts.line(p);
        if (haul != null) {
            t.putString("contract", haul);
            BlockPos stop = HaulContracts.nextStop(level, p);
            if (stop != null) t.putIntArray("contractAt", new int[]{ stop.getX(), stop.getY(), stop.getZ() });
        }
        t.put("ways", here == null ? new ListTag() : ways(level, p, here, day));
        return t;
    }

    private static int[] sized(int[] a) {
        int[] out = new int[Earnings.Source.values().length];
        System.arraycopy(a, 0, out, 0, Math.min(a.length, out.length));
        return out;
    }

    /** Each town the player has a stake in: its bank account, rent waiting, houses, and earned there today and this week. */
    private static ListTag towns(ServerLevel level, ServerPlayer p, @Nullable Villages.Village here, long day) {
        Map<String, UUID> ids = new LinkedHashMap<>();
        if (here != null) ids.put(here.id().toString(), here.id());
        for (String k : Earnings.townsEarnedIn(p)) {
            UUID u = Earnings.uuid(k);
            if (u != null && Villages.get(u) != null) ids.putIfAbsent(k, u);
        }
        for (Villages.Village v : Villages.every()) {
            if (ids.size() >= 8) break;
            if (Bank.balanceForTests(v.id(), p.getUUID()) > 0 || Homes.playerHouses(v.id(), p.getUUID())[0] > 0 || BankCounter.rentDue(v.id(), p.getUUID()) > 0) {
                ids.putIfAbsent(v.id().toString(), v.id());
            }
        }
        ListTag out = new ListTag();
        for (UUID id : ids.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("name", Villages.name(id));
            c.putBoolean("here", here != null && here.id().equals(id));
            c.putInt("bank", Bank.balanceForTests(id, p.getUUID()));
            c.putInt("rent", BankCounter.rentDue(id, p.getUUID()));
            c.putInt("houses", Homes.playerHouses(id, p.getUUID())[0]);
            c.putInt("today", Earnings.sum(Earnings.earned(p, id, day, 1)));
            c.putInt("week", Earnings.sum(Earnings.earned(p, id, day, 7)));
            c.putInt("treasury", Ledger.coins(id));
            out.add(c);
        }
        return out;
    }

    private static ListTag skills(ServerPlayer p) {
        ListTag out = new ListTag();
        for (ShiftRole r : ShiftRole.values()) {
            int xp = Shifts.xp(p, r);
            CompoundTag c = new CompoundTag();
            c.putString("role", r.title);
            c.putString("level", EarnMath.LEVELS[EarnMath.level(xp)]);
            c.putInt("xp", xp);
            c.putFloat("progress", (float) EarnMath.levelProgress(xp));
            c.putInt("poor", Earnings.data(p).getInt("poor/" + r.id));
            out.add(c);
        }
        return out;
    }

    /** One way to earn: its kind, title, reward and detail, how far, by when, its place, and its button. */
    private static CompoundTag way(String kind, String title, String reward, String detail, ServerPlayer p, @Nullable BlockPos at,
                                   String deadline, String action, String why) {
        CompoundTag c = new CompoundTag();
        c.putString("kind", kind);
        c.putString("title", title);
        c.putString("reward", reward);
        c.putString("detail", detail);
        c.putString("deadline", deadline);
        c.putString("action", action);
        c.putString("why", why);
        if (at != null) {
            c.putIntArray("at", new int[]{ at.getX(), at.getY(), at.getZ() });
            c.putInt("distance", (int) Math.round(Math.sqrt(at.distSqr(p.blockPosition()))));
        }
        return c;
    }

    /** The live ways to earn in this town, best first by kind: shifts, orders, the bounty, contracts, prices, rent. */
    static ListTag ways(ServerLevel level, ServerPlayer p, Villages.Village v, long day) {
        ListTag out = new ListTag();
        UUID id = v.id();
        for (Shifts.Offer o : Shifts.offers(level, v, p)) {
            CompoundTag c = way("shift", o.title(), String.format(Locale.ROOT, "%.1fc a task", o.perTask()),
                o.hours() + " · ~" + o.perDay() + "c a day · needs " + o.needs(), p, o.post(), o.hours(), "take", o.whyNot());
            c.putString("role", o.role().id);
            c.putString("townId", id.toString());
            c.putString("level", EarnMath.LEVELS[Shifts.level(p, o.role())]);
            out.add(c);
        }
        BlockPos store = ShiftTasks.storehouse(level, id);
        for (WantedBoard.Order o : WantedBoard.orders(level, v)) {
            if (o.left() <= 0) continue;
            long d = o.deadline - day;
            out.add(way("wanted", "Wanted: " + Goods.words(new ItemStack(o.item), o.left()), o.pay(o.left()) + "c",
                cap(o.why) + ". Bring it to the board or the storehouse.", p, store, d <= 0 ? "today" : d + (d == 1 ? " day" : " days"), "", ""));
        }
        String bounty = PlayerServices.boardLine(level, id);
        out.add(way("bounty", "Night bounty", "1-2c a monster", bounty != null ? bounty : "At night the watch pays for every monster killed in or near the town.",
            p, v.centre(), "tonight", "", ""));
        for (HaulContracts.Contract c : HaulContracts.offered(level, v)) {
            CompoundTag w = way("contract", "Haul " + Goods.words(new ItemStack(c.item()), c.count()) + " from " + Villages.name(c.from()), c.fee() + "c",
                "Fee held in escrow; a deposit for the load; delivered here.", p, ShiftTasks.storehouse(level, c.from()),
                "by day " + c.deadline(), "contract", "");
            w.putString("id", c.id());
            out.add(w);
        }
        prices(level, p, v, out);
        int rent = BankCounter.rentDue(id, p.getUUID());
        if (rent > 0) {
            List<ServicePoints.Point> bank = ServicePoints.of(level, id, ServiceRole.BANK);
            out.add(way("rent", "Rent waiting", rent + "c", "Your tenants have paid: collect it at the bank.", p, bank.isEmpty() ? null : bank.get(0).pos(),
                "", "", ""));
        }
        return out;
    }

    /** The best price for what the player carries: here, and in the towns about (a trade run). */
    private static void prices(ServerLevel level, ServerPlayer p, Villages.Village v, ListTag out) {
        Map<String, ItemStack> kinds = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty() || Market.isCoin(s) || s.isDamaged() || Shifts.isShiftItem(s)) continue;
            kinds.putIfAbsent(Goods.lineId(s), s.copyWithCount(1));
            if (kinds.size() >= PRICED) break;
        }
        List<Villages.Village> others = new ArrayList<>();
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !o.dim().equals(v.dim()) || !level.isLoaded(o.centre())) continue;
            others.add(o);
        }
        others.sort((a, b) -> Double.compare(a.centre().distSqr(p.blockPosition()), b.centre().distSqr(p.blockPosition())));
        for (ItemStack one : kinds.values()) {
            Object[] here = Buyers.bestBid(level, v.id(), one);
            double best = here == null ? 0 : (double) here[0];
            String where = here == null ? "nobody here" : Villages.name(v.id()) + " " + here[1].toString().toLowerCase(Locale.ROOT);
            Villages.Village bestTown = here == null ? null : v;
            for (int i = 0; i < Math.min(TOWNS, others.size()); i++) {
                Object[] o = Buyers.bestBid(level, others.get(i).id(), one);
                if (o != null && (double) o[0] > best * 1.1) {
                    best = (double) o[0];
                    bestTown = others.get(i);
                    where = Villages.name(bestTown.id()) + " " + o[1].toString().toLowerCase(Locale.ROOT);
                }
            }
            if (bestTown == null || best <= 0) continue;
            String detail = here == null ? "Nobody here buys it; " + where + " does."
                : bestTown == v ? "Best price about: here." : String.format(Locale.ROOT, "Here %.2fc; %s pays more.", (double) here[0], where);
            out.add(way("price", Goods.name(one), String.format(Locale.ROOT, "%.2fc each", best), detail, p, bestTown.centre(), "today", "", ""));
        }
    }

    // ------------------------------------------------------------------ talk: work going

    private record Going(long at, String line) {}

    private static final Map<UUID, Going> GOING = new java.util.concurrent.ConcurrentHashMap<>();

    /** A folk's answer with, at most, one line more of work going in town ("The smith's short of iron, and pays well"). */
    public static String withWork(VillageFolkEntity f, String said) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level) || said == null) return said;
        String line = workGoing(level, id);
        return line.isEmpty() || said.contains(line) ? said : said + " " + line;
    }

    /** The one line of work going in this town (kept a minute): a counter short of its makings, the board, a shift. */
    static String workGoing(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        Going g = GOING.get(village);
        if (g != null && now - g.at() < 1200 && now >= g.at()) return g.line();
        String line = "";
        Villages.Village v = Villages.get(village);
        if (v != null) {
            outer:
            for (ServiceRole r : List.of(ServiceRole.SMITHY, ServiceRole.TAILOR, ServiceRole.CAFE, ServiceRole.TAVERN)) {
                if (ServicePoints.of(level, village, r).isEmpty()) continue;
                for (Buyers.Line l : Buyers.LINES.getOrDefault(r, List.of())) {
                    if (l.into() != Buyers.Into.MAKINGS) continue;
                    int have = Buyers.have(level, village, l);
                    if (have * 2 > l.keep()) continue;
                    line = "The " + r.keeperNoun + "'s short of " + Goods.name(new ItemStack(l.sample())).toLowerCase(Locale.ROOT) + ", and pays well for it.";
                    break outer;
                }
            }
            if (line.isEmpty()) {
                for (WantedBoard.Order o : WantedBoard.orders(level, v)) {
                    if (o.left() <= 0) continue;
                    line = "The board wants " + Goods.words(new ItemStack(o.item), o.left()) + " " + o.why + ", at a good price.";
                    break;
                }
            }
            if (line.isEmpty()) {
                for (Shifts.Offer o : Shifts.offers(level, v, null)) {
                    if (!o.whyNot().isEmpty()) continue;
                    line = "They're after a " + o.title().toLowerCase(Locale.ROOT) + ", if you fancy a shift.";
                    break;
                }
            }
        }
        GOING.put(village, new Going(now, line));
        return line;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
