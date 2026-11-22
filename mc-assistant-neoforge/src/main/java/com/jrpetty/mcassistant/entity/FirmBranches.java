package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.BusinessTypes.BusinessType;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [biz30] A folk who does well opens another. A folk's own firm that has paid its way for weeks lets its owner open a
 * second (a branch of its trade, or a trade next to it: the baker's café), then a third, as far as its success carries it:
 * one more for every four weeks its firms have shown a profit, four at most. Never more than a fair share of the town's
 * firms in one pair of hands (its others must have room to start their own: a second needs two firms of other folk in
 * the town, a third five, and so on), never while any of its firms lost money over the fortnight, and never without the
 * means (Enterprise's own reckoning of a founder's coin). The owner works in one: a branch has a hand of its own taken on
 * to keep it (Enterprise.found). And a branch that loses money is shut: of an owner's two or more firms, the weakest that
 * has lost over its last fortnight is wound up by its owner (Enterprise.morning).
 */
public final class FirmBranches {

    private FirmBranches() {}

    /** At most so many firms to one owner. */
    public static final int MOST = 4;
    /** A firm's age before its owner may open another, days. */
    static final int SETTLED = 21;

    /** The open firms this folk owns (a stake of its own in each). */
    public static List<Business> owned(UUID village, UUID folk) {
        List<Business> out = new ArrayList<>();
        for (Business b : Enterprise.open(village)) if (b.ownedBy(folk)) out.add(b);
        return out;
    }

    /** Weeks its firms showed a profit, over the last eight weeks of each. */
    public static int profitableWeeks(ServerLevel level, UUID village, UUID folk) {
        long day = Businesses.today(level);
        int n = 0;
        for (Business b : owned(village, folk)) {
            for (int w = 0; w < 8; w++) {
                long end = day - 7L * w;
                if (end - 6 < b.opened) break;
                if (b.last(end, 7).net() > 0) n++;
            }
        }
        return n;
    }

    /** How many firms this owner's success carries: one, and one more for every four profitable weeks, four at most. */
    public static int cap(ServerLevel level, UUID village, UUID folk) {
        return Math.min(MOST, 1 + profitableWeeks(level, village, folk) / 4);
    }

    /** A fair share: one owner's firms, with one more, at most a third of the town's firms with it (others room to start). */
    public static boolean fairShare(UUID village, UUID folk) {
        int mine = owned(village, folk).size(), all = Enterprise.open(village).size();
        return (mine + 1) * 3 <= all + 1 + 2;
    }

    /**
     * Why this folk, who owns a firm already, may not open another; null if it may. A folk who owns none is not asked
     * here (Enterprise.barred's own rules).
     */
    @Nullable
    public static String whyNot(ServerLevel level, UUID village, VillageFolkEntity f) {
        List<Business> mine = owned(village, f.getUUID());
        if (mine.isEmpty()) return null;
        long day = Businesses.today(level);
        if (mine.size() >= cap(level, village, f.getUUID())) return "already has as many businesses as its success will carry (" + mine.size() + ")";
        if (!fairShare(village, f.getUUID())) return "already holds its fair share of the town's businesses";
        long month = 0;
        for (Business b : mine) {
            if (day - b.opened < SETTLED) return "its " + b.name + " is still finding its feet";
            if (b.last(day, 14).net() < 0) return "its " + b.name + " is losing money";
            month += b.last(day, 28).net();
        }
        if (month <= 0) return "its businesses have not paid their way this month";
        return null;
    }

    /** A branch's name: the trade's own with a number when the owner's other is called the same. */
    static String nameFor(UUID village, String base) {
        int n = 1;
        for (Business b : Enterprise.open(village)) if (b.name.equals(base) || b.name.startsWith(base + " No. ")) n++;
        return n == 1 ? base : base + " No. " + n;
    }

    /** What its owner says of a branch it has opened. */
    static String opened(Business b, BusinessType t, int count, boolean stall) {
        String nth = switch (count) { case 2 -> "a second"; case 3 -> "a third"; case 4 -> "a fourth"; default -> "another"; };
        return "I've opened " + nth + " " + t.noun() + ", " + b.name + (stall ? ", on the square" : "") + ". Business is good!";
    }

    /**
     * The morning (Enterprise.morning): of an owner's two firms or more, the weakest that lost money over its last fortnight
     * (and has run a fortnight) is wound up by its owner. How many were.
     */
    public static int closeLosers(ServerLevel level, UUID village, long day) {
        java.util.Map<UUID, List<Business>> byOwner = new java.util.HashMap<>();
        for (Business b : Enterprise.open(village)) {
            for (Business.Stake s : b.owners) {
                if (s.holder() == Business.Holder.FOLK && s.who() != null) byOwner.computeIfAbsent(s.who(), k -> new ArrayList<>()).add(b);
            }
        }
        int closed = 0;
        for (java.util.Map.Entry<UUID, List<Business>> e : byOwner.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Business worst = null;
            long worstNet = 0;
            for (Business b : e.getValue()) {
                if (day - b.opened < 14) continue;
                long net = b.last(day, 14).net();
                if (net < worstNet) { worstNet = net; worst = b; }
            }
            if (worst == null) continue;
            String name = worst.name;
            Enterprise.windUp(level, village, worst, day, "its owner closed the branch: it lost " + Businesses.pence(-worstNet) + " in a fortnight");
            VillageFolkEntity owner = Homes.loaded(village, e.getKey());
            if (owner != null) {
                FolkTalk.speak(owner, "I've shut " + name + ". It was losing money; the rest of my trade is better without it.");
                owner.persona().remember(day, "I closed " + name + ": it lost money", 2);
            }
            closed++;
        }
        return closed;
    }

    /** One line of an owner's firms, for its profile and the books: "Wren's Bakery (+3.20c this week), Wren's Café ...". */
    public static String line(ServerLevel level, UUID village, UUID folk) {
        long day = Businesses.today(level);
        List<String> out = new ArrayList<>();
        for (Business b : owned(village, folk)) out.add(b.name + String.format(Locale.ROOT, " (%s this week)", Businesses.pence(b.last(day, 7).net())));
        return String.join(", ", out);
    }
}
