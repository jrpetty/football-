package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpb] The Business Core: every business's hub in the world. This part holds what the business keeps about itself that
 * the Core shows and the owner sets there: its site, its opening hours and days (stored per business, for the weekend and
 * hours work to come), its discount day and its quality, and its timeline of notable events.
 */
public final class BusinessCore {

    private BusinessCore() {}

    static final int KEEP_EVENTS = 60;
    static final String[] DAYS = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };

    /** Where the business is: its Core, else its premises, else the town's middle. */
    static BlockPos siteOf(ServerLevel level, Villages.Village v, Business b) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r != null && r.contains("core")) return BlockPos.of(r.getLong("core"));
        return b.at != null ? b.at : v.centre();
    }

    /** What it is, in a word: "bakery", "greengrocer's", "farm". */
    static String noun(UUID village, Business b) {
        BusinessTypes.BusinessType t = Enterprise.kindOf(village, b);
        return t != null ? t.noun() : b.type.title.toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ the timeline

    /** A notable event on its timeline (hired, sacked, a record day, a skim caught, sold). */
    static void event(CompoundTag r, long day, String text) {
        if (r == null) return;
        ListTag l = r.getList("timeline", Tag.TAG_COMPOUND);
        CompoundTag e = new CompoundTag();
        e.putLong("d", day);
        e.putString("t", text);
        l.add(e);
        while (l.size() > KEEP_EVENTS) l.remove(0);
        r.put("timeline", l);
        Accounts.dirty();
    }

    static List<String> timeline(CompoundTag r, int most) {
        List<String> out = new ArrayList<>();
        ListTag l = r.getList("timeline", Tag.TAG_COMPOUND);
        for (int i = l.size() - 1; i >= 0 && out.size() < most; i--) out.add("Day " + (l.getCompound(i).getLong("d") + 1) + ": " + l.getCompound(i).getString("t"));
        return out;
    }

    // ------------------------------------------------------------------ hours and days

    /** Its hours: the clock hour it opens and shuts, and the days of the week it trades (a bit each, Monday first). */
    static int[] hours(CompoundTag r) {
        CompoundTag h = r.getCompound("hours");
        return h.contains("open") ? new int[]{ h.getInt("open"), h.getInt("close"), h.getInt("days") } : new int[]{ 7, 19, 127 };
    }

    static boolean keepsHours(CompoundTag r) {
        return r.contains("hours");
    }

    static void setHours(CompoundTag r, int open, int close, int days) {
        CompoundTag h = new CompoundTag();
        h.putInt("open", Math.floorMod(open, 24));
        h.putInt("close", Math.floorMod(close, 24));
        h.putInt("days", days & 127);
        r.put("hours", h);
        Accounts.dirty();
    }

    /** A clock hour as the day's ticks (six in the morning is nought). */
    static long tickOf(int hour) {
        return Math.floorMod((hour - 6) * 1000L, 24000L);
    }

    static int hourOf(long dayTime) {
        return (int) ((Math.floorMod(dayTime, 24000L) / 1000 + 6) % 24);
    }

    /** When it opens, as the day's ticks. */
    static long opensAt(CompoundTag r) {
        return tickOf(hours(r)[0]);
    }

    /** Open at this time? Always, unless its owner set hours. */
    static boolean openAt(CompoundTag r, long dayTime) {
        if (!keepsHours(r)) return true;
        int[] h = hours(r);
        int dow = (int) Math.floorMod(dayTime / 24000L, 7L);
        if ((h[2] & 1 << dow) == 0) return false;
        int hr = hourOf(dayTime);
        return h[0] <= h[1] ? hr >= h[0] && hr < h[1] : hr >= h[0] || hr < h[1];
    }

    static String hoursWords(CompoundTag r) {
        if (!keepsHours(r)) return "open whenever its staff are in";
        int[] h = hours(r);
        List<String> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) if ((h[2] & 1 << i) != 0) days.add(DAYS[i]);
        String d = days.size() == 7 ? "every day" : days.size() == 5 && (h[2] & 0b1100000) == 0 ? "Mon to Fri" : days.size() == 6 && (h[2] & 0b1000000) == 0 ? "Mon to Sat"
            : days.isEmpty() ? "no days" : String.join(", ", days);
        return String.format(java.util.Locale.ROOT, "%02d:00 to %02d:00, %s", h[0], h[1], d);
    }

    // ------------------------------------------------------------------ the owner's price levers

    /** A discount day (a tenth off), and its quality (fine dearer, cheap cheaper): on the price Sellers asks. */
    static double priceFactor(ServerLevel level, UUID village, Business b) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null) return 1;
        double k = 1;
        if (r.contains("discountDay") && Math.floorMod(level.getDayTime() / 24000L, 7L) == r.getInt("discountDay")) k *= 0.9;
        String q = r.getString("quality");
        if (q.equals("fine")) k *= 1.08;
        else if (q.equals("cheap")) k *= 0.93;
        return k;
    }
}
