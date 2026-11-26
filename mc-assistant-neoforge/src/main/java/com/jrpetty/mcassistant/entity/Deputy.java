package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [wpb] Who runs a player's firm when its owner isn't there, and the folk deputy manager who covers for them.
 *
 * <p><b>Who is in charge</b>, each morning (and shown on the business's pages): an owner at the business lately (seen within
 * half a day); else a player partner online in the town (a partner outranks the deputy); else a player hand online in the
 * town (the acting manager); else the deputy, if one is appointed and its authority is on; else an owner online elsewhere
 * (the firm's ordinary routine runs, as it always has); else nobody, and the folk staff carry on with what is in stock
 * (they serve and make; nothing is bought in, the prices stand). Only when every member of staff is a player and none is
 * online does the firm shut for the day, safely, with its sign turned (MyBusinesses.pausedToday).
 *
 * <p><b>The deputy's authority is the owner's to give.</b> A master switch (off: it keeps the title and its pay, and decides
 * nothing). A mode: "only while I'm away" (it stands back the moment an owner is at the business) or "always help" (the
 * routine work even with the owner there: opening up, banking, keeping the wages covered; never prices or spending). And a
 * switch for each task: restock, prices, wages, banking, hiring cover, opening and closing. It never overrides the owner:
 * a markup the owner set stays (until they hand prices back), a ware's own markup is untouched, an owner's stock target is
 * kept to exactly. It is quiet: no messages while the owner is online unless "Notify me" is on; there is the report at login.
 *
 * <p><b>Its standing orders</b> ("While I'm away"): a markup range, the most it may spend in a day, the till it must keep.
 *
 * <p><b>Its character shows</b> (Temperament's marks, Mischief's honesty, its regard for the owner) and it learns: its slips
 * fall by nearly half over four weeks of the work. Chances are kept as running tallies, so an easygoing deputy who is late
 * two days in five is late two days in five, not as the dice fall. It is a little worse than an attentive owner even at its
 * best (it keeps the shelves to nineteen parts in twenty unless hardworking), so an owner's attention pays, and logging off
 * costs little.
 *
 * <p>Kept in the firm's own record (Enterprise.rec): "manager" (the deputy), "depTitle", "depRaise", "depDays", "depOff",
 * "depMode", "depTasks", "depNotify", "away" (the standing orders), "depSnap" (its character, for days its chunk is unloaded),
 * "depLog" (its days), "acc/*" (the tallies), "ownerPrice", "stockTarget", "lateUntil", "haltDay", "raiseAsked".
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Deputy {

    private Deputy() {}

    public enum Mode { OWNER, PARTNER, PLAYER_HAND, DEPUTY, OWNER_AWAY, STAFF, PAUSED }

    public record Charge(Mode mode, String who) {
        public boolean runs() {
            return mode != Mode.PAUSED;
        }
    }

    /** An owner "at the business" if seen there within this long (ticks: half a day). */
    static final long NEAR_FOR = 12000L;
    static final int KEEP_LOG = 42, LEARN_DAYS = 28, LONG_AWAY = 30;
    public static final String[] TITLES = { "Deputy manager", "Shop manager", "Foreman", "Head clerk" };
    public static final String[] TASKS = { "restock", "prices", "wages", "bank", "cover", "hours" };
    static final String[] TASK_WORDS = { "Restock", "Set prices", "Keep wages covered", "Bank takings", "Hire cover", "Open and close" };

    /** [WP-E's seam] An owner who can't attend to the business though online (in jail, say): counted as away. */
    public static Predicate<UUID> ABSENT = u -> false;

    // ------------------------------------------------------------------ the deputy and its settings

    @Nullable
    static UUID deputyId(@Nullable CompoundTag r) {
        return r != null && r.hasUUID("manager") ? r.getUUID("manager") : null;
    }

    @Nullable
    static VillageFolkEntity deputy(UUID village, Business b, CompoundTag r) {
        UUID d = deputyId(r);
        if (d == null) return null;
        for (VillageFolkEntity f : Enterprise.hands(village, b)) if (f.getUUID().equals(d)) return f;
        return null;
    }

    /** Still on the firm's books (loaded or not). */
    static boolean onBooks(CompoundTag r, UUID who) {
        return Enterprise.staffOf(r).contains(who);
    }

    static boolean on(CompoundTag r) {
        return deputyId(r) != null && !r.getBoolean("depOff");
    }

    static boolean always(CompoundTag r) {
        return r.getString("depMode").equals("always");
    }

    static boolean may(CompoundTag r, String task) {
        CompoundTag t = r.getCompound("depTasks");
        return t.contains(task) ? t.getBoolean(task) : !task.equals("cover");
    }

    static String title(CompoundTag r) {
        String t = r.getString("depTitle");
        return t.isEmpty() ? TITLES[0] : t;
    }

    static String name(UUID village, CompoundTag r) {
        UUID d = deputyId(r);
        if (d == null) return "";
        VillageFolkEntity f = Homes.loaded(village, d);
        if (f != null) return f.displayNameCap();
        String n = r.getCompound("depSnap").getString("name");
        return n.isEmpty() ? "your deputy" : n;
    }

    /** The owner's standing orders, with defaults: markup 15-60%, spend 20c a day, keep 3c in the till. */
    static CompoundTag orders(CompoundTag r) {
        CompoundTag o = r.getCompound("away");
        if (!o.contains("lo")) { o.putInt("lo", 15); o.putInt("hi", 60); o.putInt("spend", 20); o.putInt("floor", 3); }
        r.put("away", o);
        return o;
    }

    // ------------------------------------------------------------------ who is in charge

    static boolean online(ServerLevel level, @Nullable UUID p) {
        return p != null && level.getServer().getPlayerList().getPlayer(p) != null && !ABSENT.test(p);
    }

    static boolean inTown(ServerLevel level, UUID p, UUID village) {
        ServerPlayer sp = level.getServer().getPlayerList().getPlayer(p);
        if (sp == null || ABSENT.test(p)) return false;
        Villages.Village v = PlayerLife.here(sp);
        return v != null && v.id().equals(village);
    }

    static boolean near(ServerLevel level, CompoundTag r, UUID p) {
        return online(level, p) && r.contains("near/" + p) && level.getGameTime() - r.getLong("near/" + p) < NEAR_FOR;
    }

    /** Who runs it now, and how. Folk-founded firms are their owner's, always. */
    public static Charge charge(ServerLevel level, UUID village, Business b, @Nullable CompoundTag r, long day) {
        if (r == null || r.hasUUID("founder")) return new Charge(Mode.OWNER, b.ownersWords());
        Business.Stake top = null;
        for (Business.Stake s : b.owners) if (s.holder() == Business.Holder.PLAYER && s.who() != null && (top == null || s.parts() > top.parts())) top = s;
        if (top == null) return new Charge(Mode.OWNER, b.ownersWords());
        for (Business.Stake s : b.owners) {
            if (s.holder() == Business.Holder.PLAYER && s.who() != null && near(level, r, s.who())) return new Charge(Mode.OWNER, s.name());
        }
        for (Business.Stake s : b.owners) {
            if (s.holder() == Business.Holder.PLAYER && s.who() != null && s != top && inTown(level, s.who(), village)) return new Charge(Mode.PARTNER, s.name() + " (a partner)");
        }
        for (ServerPlayer h : PlayerHiring.hands(level, "firm:" + b.id)) {
            if (inTown(level, h.getUUID(), village)) return new Charge(Mode.PLAYER_HAND, h.getGameProfile().getName() + " (acting manager)");
        }
        UUID d = deputyId(r);
        if (d != null && on(r) && onBooks(r, d)) return new Charge(Mode.DEPUTY, name(village, r) + " (" + title(r).toLowerCase(Locale.ROOT) + ")");
        for (Business.Stake s : b.owners) {
            if (s.holder() == Business.Holder.PLAYER && online(level, s.who())) return new Charge(Mode.OWNER_AWAY, s.name() + " (away from the business)");
        }
        if (MyBusinesses.pausedToday(village, b, day)) return new Charge(Mode.PAUSED, "nobody: shut till one of you is back");
        return new Charge(Mode.STAFF, "nobody: the staff carry on with what's in stock");
    }

    /** Owners seen at their businesses (every five seconds), and the deputy's character noted while it is loaded. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 100 != 23) return;
        ServerLevel level = e.getServer().overworld();
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            Villages.Village v = PlayerLife.here(p);
            if (v == null) continue;
            for (Business b : Enterprise.open(v.id())) {
                if (!b.ownedBy(p.getUUID())) continue;
                CompoundTag r = Enterprise.rec(v.id(), b.id);
                if (r == null) continue;
                BlockPos at = BusinessCore.siteOf(level, v, b);
                if (p.blockPosition().closerThan(at, 40)) {
                    r.putLong("near/" + p.getUUID(), level.getGameTime());
                    VillageFolkEntity d = deputy(v.id(), b, r);
                    if (d != null) snap(r, d, b);
                }
            }
        }
    }

    // ------------------------------------------------------------------ its character

    /** What its character comes to at the work. */
    record Profile(EnumSet<Temperament.Mark> marks, int honesty, boolean fond, double skill) {
        boolean has(Temperament.Mark m) {
            return marks.contains(m);
        }
    }

    static final Map<UUID, EnumSet<Temperament.Mark>> MARKS_FOR_TESTS = new ConcurrentHashMap<>();

    static EnumSet<Temperament.Mark> marksOf(VillageFolkEntity f) {
        EnumSet<Temperament.Mark> t = MARKS_FOR_TESTS.get(f.getUUID());
        return t != null ? EnumSet.copyOf(t) : Temperament.marks(f);
    }

    /** Its character kept in the record, for the days its chunk isn't loaded. */
    static void snap(CompoundTag r, VillageFolkEntity f, Business b) {
        CompoundTag s = new CompoundTag();
        int bits = 0;
        for (Temperament.Mark m : marksOf(f)) bits |= 1 << m.ordinal();
        s.putInt("marks", bits);
        s.putInt("honesty", Mischief.honesty(f));
        s.putString("name", f.displayNameCap());
        int aff = 0;
        for (Business.Stake st : b.owners) if (st.holder() == Business.Holder.PLAYER && st.who() != null) aff = Math.max(aff, f.persona().affinity(st.who()));
        s.putBoolean("fond", aff >= 30);
        r.put("depSnap", s);
    }

    static Profile profile(@Nullable VillageFolkEntity f, Business b, CompoundTag r) {
        if (f != null) snap(r, f, b);
        CompoundTag s = r.getCompound("depSnap");
        EnumSet<Temperament.Mark> m = EnumSet.noneOf(Temperament.Mark.class);
        for (Temperament.Mark x : Temperament.Mark.values()) if ((s.getInt("marks") & 1 << x.ordinal()) != 0) m.add(x);
        double skill = Math.min(1, r.getInt("depDays") / (double) LEARN_DAYS);
        return new Profile(m, s.contains("honesty") ? s.getInt("honesty") : 55, s.getBoolean("fond"), skill);
    }

    static List<String> traitWords(CompoundTag r) {
        List<String> out = new ArrayList<>();
        CompoundTag s = r.getCompound("depSnap");
        for (Temperament.Mark x : Temperament.Mark.values()) if ((s.getInt("marks") & 1 << x.ordinal()) != 0) out.add(x.word);
        if (s.getBoolean("fond")) out.add("fond of you");
        return out;
    }

    /** A running tally: so much chance added; a whole one due is the event. True when it falls today. */
    static boolean tally(CompoundTag r, String key, double chance) {
        double t = r.getDouble("acc/" + key) + Math.max(0, chance);
        boolean due = t >= 1;
        r.putDouble("acc/" + key, due ? t - 1 : t);
        return due;
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The firm's morning as far as who runs it goes (Enterprise.morning, after its staff, share and fortunes). Returns true
     * when the firm's ordinary routine (its shelf targets and restock) should run as before.
     */
    static boolean morning(ServerLevel level, Villages.Village v, Business b, CompoundTag r, long day, Charge ch, Map<String, Integer> wants) {
        UUID d = deputyId(r);
        if (d != null && !onBooks(r, d)) {                                  // it quit, was let go, or died
            String n = name(v.id(), r);
            demote(v.id(), b, r);
            r.putString("depGone", n + " is no longer with the firm; nobody is deputy now");
            b.note(day, n + " left the firm; the deputy's post is empty");
            BusinessCore.event(r, day, n + " left; no deputy now");
            notifyOwners(level, b, r, n + " has left " + b.name + ": appoint another deputy from Staff.");
            ch = charge(level, v.id(), b, r, day);
        }
        r.remove("lateUntil");
        switch (ch.mode()) {
            case DEPUTY -> { day(level, v, b, r, day, wants, true); return false; }
            case STAFF -> { staffDay(b, r, day); return false; }
            default -> {
                if (on(r) && always(r)) day(level, v, b, r, day, wants, false);   // the routine work, even with the owner there
                return true;
            }
        }
    }

    /** The owner's own stock targets over the firm's reckoning, exactly (Enterprise, after its targets). */
    static void ownerTargets(CompoundTag r) {
        CompoundTag own = r.getCompound("stockTarget");
        if (own.isEmpty()) return;
        CompoundTag tg = r.getCompound("targets");
        for (String k : own.getAllKeys()) tg.putInt(k, own.getInt(k));
        r.put("targets", tg);
    }

    /** Nobody in charge: the staff serve and make what they can; a day's line for the report. */
    static void staffDay(Business b, CompoundTag r, long day) {
        CompoundTag rec = new CompoundTag();
        rec.putLong("d", day);
        rec.putLong("missed", missedYesterday(b, day));
        ListTag log = r.getList("staffLog", Tag.TAG_COMPOUND);
        log.add(rec);
        while (log.size() > KEEP_LOG) log.remove(0);
        r.put("staffLog", log);
    }

    static long missedYesterday(Business b, long day) {
        long n = 0;
        for (Business.Line l : b.last(day - 1, 1).lines.values()) n += l.missed;
        return n;
    }

    /**
     * The deputy's day. {@code full}: in charge (the owner away); else only the routine work beside the owner (opening up,
     * banking, the wages), never prices or spending. Returns the day's record.
     */
    static CompoundTag day(ServerLevel level, Villages.Village v, Business b, CompoundTag r, long day, Map<String, Integer> wants, boolean full) {
        UUID id = v.id();
        UUID did = deputyId(r);
        CompoundTag rec = new CompoundTag();
        rec.putLong("d", day);
        rec.putBoolean("full", full);
        if (did == null) return rec;
        VillageFolkEntity d = deputy(id, b, r);
        Profile p = profile(d, b, r);
        CompoundTag o = orders(r);
        double err = (1 - 0.45 * p.skill()) * (p.fond() ? 0.7 : 1.0);
        boolean longAway = full && ownersAwayDays(r, b, day) >= LONG_AWAY;
        List<String> why = new ArrayList<>();
        BusinessTypes.BusinessType t = Enterprise.kindOf(id, b);
        // Opening on time.
        boolean lateDay = false;
        if (may(r, "hours")) {
            double late = (0.06 + (p.has(Temperament.Mark.EASYGOING) ? 0.42 : 0) + (p.has(Temperament.Mark.CARELESS) ? 0.12 : 0)
                + (p.has(Temperament.Mark.TIMID) ? 0.04 : 0)) * err;
            if (p.has(Temperament.Mark.HARDWORKING)) late = Math.min(late, 0.01);
            lateDay = tally(r, "late", late);
            if (lateDay) {
                r.putLong("lateUntil", day * 24000L + BusinessCore.opensAt(r) + 3000L);   // three hours after its opening
                why.add("opened late: " + (p.has(Temperament.Mark.EASYGOING) ? "easygoing" : p.has(Temperament.Mark.CARELESS) ? "careless" : "overslept"));
            }
        }
        rec.putBoolean("late", lateDay);
        // The shelves.
        long spend = 0;
        double fillShare = 1;
        if (full && may(r, "restock") && t != null) {
            Enterprise.targets(level, id, b, r, wants);
            CompoundTag tg = r.getCompound("targets"), own = r.getCompound("stockTarget");
            for (String k : own.getAllKeys()) if (!tg.contains(k)) tg.putInt(k, own.getInt(k));
            double fill = p.has(Temperament.Mark.HARDWORKING) ? 1.05 : 0.95 + 0.05 * p.skill();
            double held0 = 0, want0 = 0;
            for (String k : tg.getAllKeys()) { want0 += tg.getInt(k); held0 += held(b, k); }
            boolean rush = want0 > 0 && held0 < want0 * 0.4;
            if (p.has(Temperament.Mark.EASYGOING)) { fill = 0.7; why.add("let the stock run low: easygoing"); }
            if ((p.has(Temperament.Mark.THRIFTY) || p.has(Temperament.Mark.SHREWD)) && rush) { fill = Math.min(fill, 0.8); why.add("under-stocked in a rush: " + (p.has(Temperament.Mark.SHREWD) ? "shrewd" : "thrifty")); }
            if (p.has(Temperament.Mark.RECKLESS) || p.has(Temperament.Mark.SPENDTHRIFT)) { fill = Math.max(fill, 1.3); why.add("over-ordered: " + (p.has(Temperament.Mark.RECKLESS) ? "reckless" : "spendthrift")); }
            if (fill < 1) fill = 1 - (1 - fill) * Math.max(0.55, err);       // it learns
            long limit = o.getInt("spend") * 100L / (longAway ? 2 : 1);
            long budget = Math.min(limit, Math.max(0, b.till - o.getInt("floor") * 100L));
            CompoundTag buyIn = r.getCompound("buyIn");
            double want = 0, held1 = 0;
            long till0 = b.till;
            for (String k : tg.getAllKeys()) {
                int base = own.contains(k) ? own.getInt(k) : tg.getInt(k);
                int target = own.contains(k) ? base : (int) Math.round(base * fill);
                want += base;
                if (!t.resells() && !buyIn.contains(k)) continue;
                long short_ = target - held(b, k);
                ItemStack one = Stockroom.sampleOf(k);
                if (short_ <= 0 || one.isEmpty()) continue;
                long each = Math.max(1, (long) Math.ceil(Businesses.storesEach(level, id, one) * 100));
                long left = budget - (till0 - b.till);
                long n = Math.min(short_, left / each);
                if (n < short_) rec.putBoolean("capped", true);
                if (n <= 0) continue;
                Businesses.order(level, id, b, one, n, false);
            }
            spend = Math.max(0, till0 - b.till);
            if ((p.has(Temperament.Mark.THRIFTY) || p.has(Temperament.Mark.SHREWD)) && spend > 0) {
                long saved = spend * 6 / 100;
                b.till += saved;
                spend -= saved;
                if (saved > 0) why.add("bought under the ask: " + (p.has(Temperament.Mark.SHREWD) ? "shrewd" : "thrifty"));
            } else if (p.has(Temperament.Mark.SPENDTHRIFT) && spend > 0) {
                long over = Math.max(1, spend * 4 / 100);
                b.till -= over;
                spend += over;
                why.add("paid over the odds: spendthrift");
            }
            for (String k : tg.getAllKeys()) held1 += held(b, k);
            fillShare = want <= 0 ? 1 : Math.min(1.3, held1 / want);
            if (rec.getBoolean("capped") && spend > 0) why.add("held to your spending limit");
        }
        rec.putLong("spend", spend);
        rec.putDouble("fill", fillShare);
        rec.putLong("missed", missedYesterday(b, day));
        // The day's markup, within the owner's range, unless the owner set it.
        if (full && may(r, "prices")) {
            if (r.contains("ownerPrice")) rec.putBoolean("keptPrice", true);
            else {
                double lo = o.getInt("lo") / 100.0, hi = Math.max(lo, o.getInt("hi") / 100.0);
                double at = p.has(Temperament.Mark.CLEVER) ? 0.7 : p.has(Temperament.Mark.RECKLESS) ? 0.2 : 0.45;
                at += (0.65 - at) * p.skill() * 0.5;                          // it learns where the margin is
                double was = b.markup;
                b.markup = Math.round((lo + (hi - lo) * at) * 100) / 100.0;
                if (Math.abs(was - b.markup) > 0.004) rec.putDouble("markup", b.markup);
                if (p.has(Temperament.Mark.CLEVER)) why.add("priced near the best margin: clever");
            }
        }
        // Cover for a missing hand.
        if (full && may(r, "cover") && t != null && Enterprise.staffOf(r).size() < Math.max(1, t.startStaff())) {
            VillageFolkEntity c = Enterprise.hire(level, v, b, r, t);
            if (c != null) { rec.putString("cover", c.displayNameCap()); BusinessCore.event(r, day, c.displayNameCap() + " taken on as cover by " + name(id, r)); }
        }
        // A dishonest deputy's hand in the till.
        int skim = 0;
        if (p.honesty() < 45 && (full || may(r, "bank"))) {
            double chance = 0.35 + (p.has(Temperament.Mark.SPENDTHRIFT) ? 0.15 : 0) - (p.fond() ? 0.2 : 0) - p.honesty() / 300.0;
            if (tally(r, "skim", chance) && b.till > 500) {
                skim = (int) Math.max(1, Math.min(b.till / 1000, 1 + Math.floorMod(day * 7 + did.hashCode(), 4)));
                b.till -= skim * 100L;
                if (d != null) d.earn(skim);
                b.note(day, "till short " + skim + "c at the day's count");
                rec.putInt("skim", skim);
                List<String> saw = new ArrayList<>();
                String dn = name(id, r);
                for (VillageFolkEntity h : Enterprise.hands(id, b)) {
                    if (h.getUUID().equals(did) || !tally(r, "saw/" + h.getUUID(), 0.6)) continue;
                    h.persona().remember(level.getDayTime() / 24000L, "I saw " + dn + " take coin from the till at " + b.name, -3);
                    h.persona().feelFor(did, dn, -6);
                    saw.add(h.displayNameCap());
                }
                if (!saw.isEmpty()) { rec.putString("saw", String.join(", ", saw)); BusinessCore.event(r, day, "till short " + skim + "c; " + String.join(", ", saw) + " saw it taken"); }
            }
        }
        // Temper and ambition.
        if (p.has(Temperament.Mark.QUARRELSOME)) {
            if (tally(r, "complaint", 0.3)) { rec.putBoolean("complaint", true); why.add("a customer complained of the manner: quarrelsome"); }
            List<VillageFolkEntity> hs = Enterprise.hands(id, b);
            if (hs.size() > 2 && tally(r, "walkout", 0.08)) {
                VillageFolkEntity w = null;
                for (VillageFolkEntity h : hs) if (!h.getUUID().equals(did)) w = h;
                if (w != null) {
                    LabourMarket.layOff(level, v, LabourBook.town(id), w, "firm:" + b.id, "walked out after a row with the " + title(r).toLowerCase(Locale.ROOT), level.getDayTime() / 24000L);
                    rec.putString("walkout", w.displayNameCap());
                    BusinessCore.event(r, day, w.displayNameCap() + " walked out after a row with " + name(id, r));
                }
            }
        }
        int days = r.getInt("depDays") + (full ? 1 : 0);
        r.putInt("depDays", days);
        if (p.has(Temperament.Mark.AMBITIOUS) && days >= 14 && !r.contains("raiseAsked")) { r.putLong("raiseAsked", day); rec.putBoolean("asks", true); }
        if (p.has(Temperament.Mark.AMBITIOUS) && days >= 42 && tally(r, "leave", 0.03)) {
            rec.putBoolean("left", true);
            String n = name(id, r);
            b.note(day, n + " gave up the " + title(r).toLowerCase(Locale.ROOT) + "'s post to start out on their own");
            BusinessCore.event(r, day, n + " left to start their own firm");
            demote(id, b, r);
        }
        // Banking, keeping the till's floor (and the day's wages, if it minds them).
        long floor = o.getInt("floor") * 100L;
        long keep = floor + (may(r, "wages") ? wageBill(id, r, t) : 0);
        if (may(r, "bank") && b.till - keep >= 100) {
            long amt = (b.till - keep) / 100 * 100, banked = 0;
            for (Business.Stake s : b.owners) {
                if (s.holder() != Business.Holder.PLAYER || s.who() == null) continue;
                long share = amt * s.parts() / Business.WHOLE;
                b.ownersDue.merge(s.who(), share, Long::sum);
                banked += share;
            }
            b.till -= banked;
            if (banked > 0) rec.putLong("banked", banked);
        }
        boolean emptyShelves = true;
        for (Business.Lot l : b.stock.values()) if (l.qty > 0) { emptyShelves = false; break; }
        if (full && b.till < Math.max(100, floor / 3) && emptyShelves) {
            r.putLong("haltDay", day + 1);
            rec.putBoolean("halt", true);
            b.note(day, name(id, r) + " called a halt: the till ran dry and the shelves are bare");
            BusinessCore.event(r, day, name(id, r) + " called a halt: the till ran dry");
            notifyOwners(level, b, r, name(id, r) + " shut " + b.name + " for a day: the till ran dry.");
        }
        int score = 100 - (lateDay ? 25 : 0) - (int) Math.round(35 * Math.max(0, 1 - Math.min(1, fillShare))) - (rec.getBoolean("complaint") ? 10 : 0)
            - (skim > 0 ? 35 : 0) - (rec.contains("walkout") ? 15 : 0) - (rec.getBoolean("halt") ? 20 : 0)
            + (p.has(Temperament.Mark.CLEVER) && full ? 3 : 0) - (p.has(Temperament.Mark.HARDWORKING) ? 0 : 3);
        rec.putInt("score", Math.max(0, Math.min(100, score)));
        ListTag rl = new ListTag();
        for (String s : why) rl.add(StringTag.valueOf(s));
        rec.put("why", rl);
        ListTag log = r.getList("depLog", Tag.TAG_COMPOUND);
        log.add(rec);
        while (log.size() > KEEP_LOG) log.remove(0);
        r.put("depLog", log);
        if (!why.isEmpty()) b.note(day, name(id, r) + " (" + title(r).toLowerCase(Locale.ROOT) + "): " + String.join("; ", why));
        if (rec.getBoolean("asks")) notifyOwners(level, b, r, name(id, r) + " would like a word about a raise (" + b.name + ", Deputy).");
        Accounts.dirty();
        Businesses.dirty();
        return rec;
    }

    static long held(Business b, String k) {
        Business.Lot lot = b.stock.get(k);
        return lot == null ? 0 : lot.qty;
    }

    static long wageBill(UUID village, CompoundTag r, @Nullable BusinessTypes.BusinessType t) {
        if (t == null) return 0;
        return (long) Enterprise.staffOf(r).size() * Enterprise.wageOf(t.work(), village) * 100L;
    }

    /** How long every player owner has been away (days; 0 if one is about). */
    static long ownersAwayDays(CompoundTag r, Business b, long day) {
        long most = Long.MAX_VALUE;
        boolean any = false;
        for (Business.Stake s : b.owners) {
            if (s.holder() != Business.Holder.PLAYER || s.who() == null) continue;
            any = true;
            if (!r.contains("away/" + s.who())) return 0;
            most = Math.min(most, day - r.getLong("away/" + s.who()));
        }
        return any ? most : 0;
    }

    /** Before the deputy's late opening, or outside the hours its owner set, the firm sells nothing (Sellers asks). */
    public static boolean closedNow(ServerLevel level, UUID village, Business b) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null) return false;
        long now = level.getDayTime();
        if (r.contains("lateUntil")) {
            long until = r.getLong("lateUntil");
            if (now < until && now / 24000L == until / 24000L) return true;
        }
        return !BusinessCore.openAt(r, now);
    }

    /** "Notify me": a toast to an owner online, at most one a day, and only if they asked. */
    static void notifyOwners(ServerLevel level, Business b, CompoundTag r, String text) {
        if (!r.getBoolean("depNotify")) return;
        long day = Businesses.today(level);
        if (r.getLong("notified") == day + 1) return;
        r.putLong("notified", day + 1);
        for (Business.Stake s : b.owners) {
            ServerPlayer p = s.who() == null ? null : level.getServer().getPlayerList().getPlayer(s.who());
            if (p != null) WorkLife.toast(p, text);
        }
    }

    // ------------------------------------------------------------------ the record

    static List<CompoundTag> log(CompoundTag r, String key, long since) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = r.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getLong("d") >= since) out.add(l.getCompound(i));
        return out;
    }

    /** The rating over the last seven days it ran (0-100), or -1 with none. */
    static int rating(CompoundTag r) {
        ListTag l = r.getList("depLog", Tag.TAG_COMPOUND);
        int n = 0, sum = 0;
        for (int i = l.size() - 1; i >= 0 && n < 7; i--) {
            if (!l.getCompound(i).getBoolean("full")) continue;
            sum += l.getCompound(i).getInt("score");
            n++;
        }
        return n == 0 ? -1 : sum / n;
    }

    static String ratingWord(int rating) {
        return rating < 0 ? "not yet tried" : rating >= 85 ? "excellent" : rating >= 70 ? "good" : rating >= 50 ? "fair" : "poor";
    }

    /** The trait effects in plain words, with how often over its last fortnight: "Opened late on 2 days: easygoing". */
    static List<String> reasons(CompoundTag r) {
        Map<String, Integer> count = new LinkedHashMap<>();
        ListTag l = r.getList("depLog", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, l.size() - 14); i < l.size(); i++) {
            ListTag w = l.getCompound(i).getList("why", Tag.TAG_STRING);
            for (int j = 0; j < w.size(); j++) count.merge(w.getString(j), 1, Integer::sum);
            if (l.getCompound(i).getInt("skim") > 0) count.merge("till short at the count: dishonest", 1, Integer::sum);
            if (l.getCompound(i).contains("walkout")) count.merge("a hand walked out: quarrelsome", 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            String s = e.getKey();
            int c = s.indexOf(':');
            String head = c < 0 ? s : s.substring(0, c), tail = c < 0 ? "" : s.substring(c);
            out.add(Character.toUpperCase(head.charAt(0)) + head.substring(1) + " on " + e.getValue() + (e.getValue() == 1 ? " day" : " days") + tail);
        }
        return out;
    }

    static final String[] GOOD = { "Kept it ticking over. Nothing you'd notice, which is the point.", "Shelves full, doors open, books square. You're welcome.",
        "A quiet run. The quiet runs are the good ones.", "No fires, no fuss. Coin's banked.", "All in order. I'd tell you if it wasn't.",
        "Ran it as you'd run it. Possibly better; we'll never know.", "Nothing to report, and I mean that kindly." };
    static final String[] FAIR = { "Mostly fine. A day or two I'd rather forget.", "We got by. Not every day was a good one.",
        "Some days ran smooth, some didn't. The books say which.", "It held together. Just about.", "Nothing broke that can't be fixed.",
        "Fair enough, on the whole. Don't ask about the middle bit.", "Could've been tidier. Could've been a lot worse." };
    static final String[] POOR = { "Not my finest stretch. The books will tell you.", "It got away from me a bit.",
        "I'd sooner you were here, to be honest.", "Rough going. I'll do better.", "We lost some trade. I know.",
        "Let's call it a learning week.", "I won't pretend that went well." };
    static final String[] HALT = { "The till ran dry, so I shut the doors. Seemed wiser than the alternative.", "Nothing left to pay with, so I called a halt.",
        "Couldn't open on an empty till. It's waiting for you.", "Stopped the trade till there's coin to run it.", "Closed up: no coin, no stock. Over to you.",
        "I put the shutters up. The till needs you more than I do.", "Shut it for a day. Better than trading on nothing." };
    static final String[] STAFF = { "Nobody was in charge, so we sold what was on the shelves.", "We kept the doors open. Nothing came in, mind.",
        "Served what we had. The shelves are thinner for it.", "No one to order, so no one ordered.", "Did our hours. The stockroom didn't refill itself.",
        "Ticked along on what was there. Not much left now." };

    /** The "While you were away" report since this day: what was done, the money, the problems, and a word in its voice. */
    static List<String> report(ServerLevel level, UUID village, Business b, CompoundTag r, long since) {
        List<String> out = new ArrayList<>();
        long today = Businesses.today(level);
        List<CompoundTag> days = log(r, "depLog", since);
        days.removeIf(x -> !x.getBoolean("full"));
        List<CompoundTag> staff = log(r, "staffLog", since);
        int n = (int) Math.max(1, today - since + 1);
        Business.Day sum = b.last(today, n);
        String gone = r.getString("depGone");
        if (days.isEmpty() && staff.isEmpty()) {
            if (!gone.isEmpty()) out.add(gone + ".");
            return out;
        }
        if (!days.isEmpty()) {
            long spend = 0, banked = 0, missed = 0;
            int late = 0, complaints = 0, total = 0;
            boolean halt = false, asks = false, left = false, kept = false;
            List<String> problems = new ArrayList<>();
            for (CompoundTag d : days) {
                spend += d.getLong("spend");
                banked += d.getLong("banked");
                missed += d.getLong("missed");
                if (d.getBoolean("late")) late++;
                if (d.getInt("skim") > 0) problems.add("Till short " + d.getInt("skim") + "c on day " + (d.getLong("d") + 1) + (d.contains("saw") ? " (" + d.getString("saw") + " says they saw it taken)" : "") + ".");
                if (d.getBoolean("complaint")) complaints++;
                if (d.contains("walkout")) problems.add(d.getString("walkout") + " walked out after a row, day " + (d.getLong("d") + 1) + ".");
                if (d.contains("cover")) problems.add("Took on " + d.getString("cover") + " as cover, day " + (d.getLong("d") + 1) + ".");
                halt |= d.getBoolean("halt");
                asks |= d.getBoolean("asks");
                left |= d.getBoolean("left");
                kept |= d.getBoolean("keptPrice");
                total += d.getInt("score");
            }
            int rating = total / days.size();
            String who = r.getCompound("depSnap").getString("name");
            if (who.isEmpty()) who = "Your deputy";
            out.add(who + " ran " + b.name + " for " + days.size() + (days.size() == 1 ? " day" : " days") + ": sales " + Businesses.pence(sum.revenue) + ", "
                + Businesses.pence(spend) + " on stock, wages " + Businesses.pence(sum.wages) + ", " + Businesses.pence(banked) + " banked for you.");
            if (late > 0) out.add("Opened late on " + late + (late == 1 ? " day." : " days."));
            if (missed > 0) out.add(missed + (missed == 1 ? " sale" : " sales") + " missed for want of stock.");
            if (complaints > 0) out.add(complaints + (complaints == 1 ? " customer complaint." : " customer complaints."));
            if (kept) out.add("Your prices were left as you set them.");
            out.addAll(problems);
            if (asks) out.add(who + " asks for a raise (Deputy tab).");
            if (left) out.add(who + " has left to start out on their own.");
            if (!gone.isEmpty()) out.add(gone + ".");
            out.add("Rating " + rating + "/100 (" + ratingWord(rating) + ").");
            String[] band = halt ? HALT : rating >= 75 ? GOOD : rating >= 50 ? FAIR : POOR;
            out.add(who + ": \"" + band[Math.floorMod(since * 31 + b.id.hashCode(), band.length)] + "\"");
        } else {
            long missed = 0;
            for (CompoundTag d : staff) missed += d.getLong("missed");
            out.add("Nobody was in charge of " + b.name + " for " + staff.size() + (staff.size() == 1 ? " day" : " days") + ": the staff sold what was in stock ("
                + Businesses.pence(sum.revenue) + "), nothing was bought in" + (missed > 0 ? ", and " + missed + " sales were missed for want of it" : "") + ".");
            if (!gone.isEmpty()) out.add(gone + ".");
            out.add("A hand: \"" + STAFF[Math.floorMod(since * 31 + b.id.hashCode(), STAFF.length)] + "\"");
            out.add("Appoint a deputy manager (Staff) to restock and bank while you're away.");
        }
        return out;
    }

    /** At login: each business's report since its owner left, in chat and a toast, and kept for its page. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        loginReport(p.serverLevel(), p);
    }

    static int loginReport(ServerLevel level, ServerPlayer p) {
        int n = 0;
        for (Villages.Village v : Villages.every()) {
            for (Business b : Enterprise.open(v.id())) {
                if (!b.ownedBy(p.getUUID())) continue;
                CompoundTag r = Enterprise.rec(v.id(), b.id);
                if (r == null || !r.contains("left/" + p.getUUID())) continue;
                long since = r.getLong("left/" + p.getUUID());
                r.remove("left/" + p.getUUID());
                List<String> rep = report(level, v.id(), b, r, since);
                r.remove("depGone");
                if (rep.isEmpty()) continue;
                ListTag l = new ListTag();
                for (String s : rep) l.add(StringTag.valueOf(s));
                r.put("report/" + p.getUUID(), l);
                ListTag hist = r.getList("reports", Tag.TAG_COMPOUND);
                CompoundTag h = new CompoundTag();
                h.putLong("d", Businesses.today(level));
                h.put("l", l.copy());
                hist.add(h);
                while (hist.size() > 8) hist.remove(0);
                r.put("reports", hist);
                p.sendSystemMessage(Component.literal("While you were away: " + b.name).withStyle(ChatFormatting.GOLD));
                for (String s : rep) p.sendSystemMessage(Component.literal("  " + s).withStyle(ChatFormatting.GRAY));
                WorkLife.toast(p, "While you were away: " + b.name + " (its Business Core has the report)");
                Planner.add(p, Businesses.today(level), 7, "report", "While you were away: " + b.name, BusinessCore.siteOf(level, v, b));
                Accounts.dirty();
                n++;
            }
        }
        return n;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        left(p.serverLevel(), p.getUUID());
    }

    static void left(ServerLevel level, UUID who) {
        long day = Businesses.today(level);
        for (Villages.Village v : Villages.every()) {
            for (Business b : Enterprise.open(v.id())) {
                if (!b.ownedBy(who)) continue;
                CompoundTag r = Enterprise.rec(v.id(), b.id);
                if (r != null) { r.putLong("left/" + who, day); r.remove("near/" + who); Accounts.dirty(); }
            }
        }
    }

    // ------------------------------------------------------------------ the owner's hand

    static String appoint(ServerLevel level, UUID village, Business b, VillageFolkEntity f, String title, int raisePct) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null) return "!Its record is missing.";
        demote(village, b, r);
        r.putUUID("manager", f.getUUID());
        r.putString("depTitle", title);
        r.putInt("depRaise", Math.max(0, Math.min(50, raisePct)));
        r.putInt("depDays", 0);
        r.remove("raiseAsked");
        r.remove("depOff");
        r.remove("depGone");
        for (String k : new ArrayList<>(r.getAllKeys())) if (k.startsWith("acc/")) r.remove(k);
        LabourBook.Contract c = LabourMarket.contract(f);
        if (c != null && raisePct > 0) { c.m *= 1 + raisePct / 100.0; LabourBook.changed(); }
        orders(r);
        snap(r, f, b);
        Accounts.dirty();
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll keep it as you'd want it.", "Right. I'll mind the place.", "Leave it with me.",
            "I won't let you down. Probably.", "Fair enough. I'll need the keys.", "Understood. I'll keep the books straight."));
        long day = Businesses.today(level);
        b.note(day, f.displayNameCap() + " made " + title.toLowerCase(Locale.ROOT) + (raisePct > 0 ? ", with a rise of " + raisePct + "%" : ""));
        BusinessCore.event(r, day, f.displayNameCap() + " made " + title.toLowerCase(Locale.ROOT));
        return f.displayNameCap() + " is " + title.toLowerCase(Locale.ROOT) + (raisePct > 0 ? " (a rise of " + raisePct + "%)" : "")
            + ". While you're away they run it within your standing orders; set what they may touch on the Deputy tab.";
    }

    static void demote(UUID village, Business b, CompoundTag r) {
        UUID d = deputyId(r);
        if (d == null) return;
        VillageFolkEntity old = Homes.loaded(village, d);
        int raise = r.getInt("depRaise");
        if (old != null && raise > 0) {
            LabourBook.Contract c = LabourMarket.contract(old);
            if (c != null) { c.m /= 1 + raise / 100.0; LabourBook.changed(); }
        }
        r.remove("manager");
        r.remove("depRaise");
        Accounts.dirty();
    }

    /** The owner's answer to a raise asked for: granted (a tenth) or refused (it remembers). */
    static String answerRaise(ServerLevel level, UUID village, Business b, CompoundTag r, boolean grant) {
        VillageFolkEntity d = deputy(village, b, r);
        if (d == null || !r.contains("raiseAsked") || r.getLong("raiseAsked") == Long.MAX_VALUE) return "!Nobody's asking.";
        r.putLong("raiseAsked", Long.MAX_VALUE);                            // answered: not asked again
        if (grant) {
            LabourBook.Contract c = LabourMarket.contract(d);
            if (c != null) { c.m *= 1.1; LabourBook.changed(); }
            r.putInt("depRaise", r.getInt("depRaise") + 10);
            d.persona().remember(level.getDayTime() / 24000L, "my raise at " + b.name + " was granted", 3);
            FolkTalk.speak(d, FolkTalk.pick(d.getRandom(), "Much obliged.", "Good. I'll keep earning it.", "That's fair.", "Thank you. Truly.",
                "I won't forget it.", "Right. Back to it, then."));
            return d.displayNameCap() + " has a tenth more. Ambition fed is ambition kept, mostly.";
        }
        d.persona().remember(level.getDayTime() / 24000L, "my raise at " + b.name + " was refused", -3);
        r.putDouble("acc/leave", r.getDouble("acc/leave") + 0.3);
        FolkTalk.speak(d, FolkTalk.pick(d.getRandom(), "I see.", "Right. Noted.", "Fair enough. For now.", "I'll bear that in mind.",
            "Understood.", "Hm. We'll see."));
        return d.displayNameCap() + " took it quietly. An ambitious one may look elsewhere sooner.";
    }

    /** A skim the owner saw in the books: sacked, or reported to the watch as theft. */
    static String deal(ServerLevel level, ServerPlayer owner, Villages.Village v, Business b, CompoundTag r, boolean report) {
        VillageFolkEntity d = deputy(v.id(), b, r);
        if (d == null) return "!Your deputy isn't about to answer for it.";
        long day = Businesses.today(level);
        String n = d.displayNameCap();
        if (report) {
            Crime.Case c = Mischief.commitNow(level, d, Crime.Kind.STORES, null, BusinessCore.siteOf(level, v, b));
            if (c != null) Crime.report(level, v, c, owner.getGameProfile().getName());
            BusinessCore.event(r, day, n + " reported to the watch for the till");
        }
        demote(v.id(), b, r);
        LabourMarket.layOff(level, v, LabourBook.town(v.id()), d, "firm:" + b.id, "sacked over the till at " + b.name, level.getDayTime() / 24000L);
        b.note(day, n + " sacked over money missing from the till" + (report ? ", and reported to the watch" : ""));
        BusinessCore.event(r, day, n + " sacked over the till");
        return n + " is sacked" + (report ? " and reported to the watch for theft; the watch takes it from here." : ". Word gets round.");
    }

    // ------------------------------------------------------------------ tests

    public static void marksForTests(VillageFolkEntity f, EnumSet<Temperament.Mark> marks) {
        MARKS_FOR_TESTS.put(f.getUUID(), marks);
    }

    public static CompoundTag dayForTests(ServerLevel level, Villages.Village v, Business b, long day, boolean full, Map<String, Integer> wants) {
        return day(level, v, b, Enterprise.rec(v.id(), b.id), day, wants, full);
    }

    public static String appointForTests(ServerLevel level, UUID village, Business b, VillageFolkEntity f) {
        return appoint(level, village, b, f, TITLES[0], 10);
    }

    public static Charge chargeForTests(ServerLevel level, UUID village, Business b) {
        return charge(level, village, b, Enterprise.rec(village, b.id), Businesses.today(level));
    }

    public static List<String> reportForTests(ServerLevel level, UUID village, Business b, long since) {
        return report(level, village, b, Enterprise.rec(village, b.id), since);
    }

    public static int ratingForTests(UUID village, Business b) {
        return rating(Enterprise.rec(village, b.id));
    }

    public static List<String> reasonsForTests(UUID village, Business b) {
        return reasons(Enterprise.rec(village, b.id));
    }

    public static void ordersForTests(UUID village, Business b, int spend, int floor) {
        CompoundTag o = orders(Enterprise.rec(village, b.id));
        o.putInt("spend", spend);
        o.putInt("floor", floor);
    }

    public static void setForTests(UUID village, Business b, String key, boolean value) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (key.startsWith("task:")) { CompoundTag t = r.getCompound("depTasks"); t.putBoolean(key.substring(5), value); r.put("depTasks", t); }
        else if (key.equals("on")) { if (value) r.remove("depOff"); else r.putBoolean("depOff", true); }
        else if (key.equals("always")) r.putString("depMode", value ? "always" : "away");
    }

    public static void nearForTests(ServerLevel level, UUID village, Business b, UUID owner) {
        Enterprise.rec(village, b.id).putLong("near/" + owner, level.getGameTime());
    }

    public static boolean morningForTests(ServerLevel level, Villages.Village v, Business b, long day) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        return morning(level, v, b, r, day, charge(level, v.id(), b, r, day), Map.of());
    }

    public static int loginReportForTests(ServerLevel level, ServerPlayer p, long since) {
        for (Villages.Village v : Villages.every()) for (Business b : Enterprise.open(v.id())) {
            CompoundTag r = b.ownedBy(p.getUUID()) ? Enterprise.rec(v.id(), b.id) : null;
            if (r != null) r.putLong("left/" + p.getUUID(), since);
        }
        return loginReport(level, p);
    }
}
