package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpc] A player renting a home: one of the town's empty houses or flats, or another player's house it has to let. The
 * house is held for the tenant (the town lets it to nobody else: Homes.Home.vacant), its bed is where the tenant wakes, and
 * the rent goes out each morning by standing order (to the treasury, or waiting for the landlord as a household's rent
 * does). Rent unpaid goes on the debts ledger; a week behind, the landlord gives notice. Either side may end it with three
 * days' notice. Kept with the town's books (PropertyBook "tenancy").
 */
public final class Tenancy {

    private Tenancy() {}

    /** Days' notice either way; rent unpaid this many days brings the landlord's notice. */
    static final int NOTICE = 3, BEHIND = 7;

    /** Houses held for a tenant, by anchor (Homes asks, and has no town to hand). */
    private static final Map<Long, UUID> HELD = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> LOADED = ConcurrentHashMap.newKeySet();

    static CompoundTag book(UUID village) {
        return PropertyBook.town(village, "tenancy");
    }

    /** Is this house held for a player tenant? */
    public static boolean held(BlockPos anchor) {
        return HELD.containsKey(anchor.asLong());
    }

    /** The town's tenancies into the held list once after a restart. */
    static void load(UUID village) {
        if (!LOADED.add(village)) return;
        CompoundTag b = book(village);
        for (String k : b.getAllKeys()) {
            try { HELD.put(b.getCompound(k).getLong("anchor"), UUID.fromString(k)); } catch (IllegalArgumentException e) { /* skip */ }
        }
    }

    @Nullable
    static CompoundTag of(UUID village, UUID player) {
        CompoundTag b = book(village);
        return b.contains(player.toString()) ? b.getCompound(player.toString()) : null;
    }

    /** The house a player rents here, or null. */
    @Nullable
    public static BlockPos home(UUID village, UUID player) {
        CompoundTag t = of(village, player);
        return t == null ? null : BlockPos.of(t.getLong("anchor"));
    }

    /** A tenancy begun: the house held, the first day's rent taken (unless {@code rehoused} by the receiver: it starts tomorrow). */
    static String start(ServerLevel level, UUID village, UUID player, String name, BlockPos anchor, int rent, boolean rehoused) {
        load(village);
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        if (h == null) return "!That house isn't on the town's books.";
        if (of(village, player) != null && !rehoused) return "!You rent a home here already: give notice on it first.";
        if (!rehoused && (!h.members.isEmpty() || held(anchor))) return "!Somebody lives there now.";
        long day = PropertyBook.day(level);
        CompoundTag t = new CompoundTag();
        t.putLong("anchor", anchor.asLong());
        t.putInt("rent", Math.max(1, rent));
        t.putLong("since", day);
        t.putLong("paidTo", rehoused ? day + 1 : day);
        t.putString("name", name);
        if (h.tenure == Homes.Tenure.PLAYER && h.landlord != null) {
            t.putUUID("landlord", h.landlord);
            t.putString("landlordName", h.landlordName);
            h.toLet = false;
            Homes.save(village, h);
        }
        book(village).put(player.toString(), t);
        HELD.put(anchor.asLong(), player);
        PropertyBook.dirty();
        ServerPlayer p = PropertyBook.online(level, player);
        if (p != null) {
            java.util.List<BlockPos> beds = Homes.bedsIn(level, village, h);
            if (!beds.isEmpty()) p.setRespawnPosition(level.dimension(), beds.get(0), 0f, true, false);
            if (!rehoused) rentDay(level, village, player, t, day);
            FirstWeek.check(p);
            Planner.add(p, (day + 1) * 24000L + 6000, "rent", "Rent: " + rent + " from your account", anchor);
        }
        return "";
    }

    /** A morning's rent: out of the account (and pack), to the treasury or the landlord; short, on the ledger. */
    static void rentDay(ServerLevel level, UUID village, UUID player, CompoundTag t, long day) {
        int rent = t.getInt("rent");
        String to = t.hasUUID("landlord") ? t.getString("landlordName") : "the town";
        int paid = StandingOrders.bill(level, village, player, t.getString("name"), rent, PlayerFinance.Cat.RENT_OUT, "rent to " + to, "rent", to,
            "rent:" + t.getLong("anchor"));
        if (paid > 0 && t.hasUUID("landlord")) {                             // the landlord's rent waits for it, as a household's does
            Ledger.takeCoins(village, paid);
            String key = "rentdue/" + t.getUUID("landlord");
            Ledger.note(village, key, Integer.toString(Homes.parse(Ledger.note(village, key)) + paid));
        }
        t.putLong("paidTo", day + 1);
    }

    /** Notice given (by the tenant or the landlord): the tenancy ends in three days. */
    static String notice(ServerLevel level, UUID village, UUID player, boolean byTenant) {
        CompoundTag t = of(village, player);
        if (t == null) return "!There's no tenancy to end.";
        if (t.getLong("noticeEnds") > 0) return "!Notice is given already: it ends on day " + t.getLong("noticeEnds") + ".";
        long ends = PropertyBook.day(level) + NOTICE;
        t.putLong("noticeEnds", ends);
        t.putBoolean("byTenant", byTenant);
        PropertyBook.dirty();
        if (!byTenant) PropertyBook.tell(level, player, "Notice to quit: your tenancy ends on day " + ends + ". Find another home before then (the Home page lists them).");
        return byTenant ? "Notice given: your tenancy ends on day " + ends + ". Rent is due till then." : "Notice served on your tenant: the tenancy ends on day " + ends + ".";
    }

    /** It ends: the house free again, the tenant's waking place taken away. */
    static void end(ServerLevel level, UUID village, UUID player) {
        CompoundTag t = of(village, player);
        if (t == null) return;
        HELD.remove(t.getLong("anchor"));
        book(village).remove(player.toString());
        PropertyBook.dirty();
        ServerPlayer p = PropertyBook.online(level, player);
        if (p != null && p.getRespawnPosition() != null && p.getRespawnPosition().distSqr(BlockPos.of(t.getLong("anchor"))) < 12 * 12) {
            p.setRespawnPosition(level.dimension(), null, 0f, false, false);
        }
        PropertyBook.tell(level, player, "Your tenancy has ended. The keys are back with the landlord.");
    }

    /** Each morning: rent, the landlord's notice a week behind, notices run out. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        load(id);
        CompoundTag b = book(id);
        for (String k : new java.util.ArrayList<>(b.getAllKeys())) {
            UUID who;
            try { who = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            CompoundTag t = b.getCompound(k);
            if (t.getLong("noticeEnds") > 0 && day >= t.getLong("noticeEnds")) { end(level, id, who); continue; }
            if (t.getLong("paidTo") <= day) rentDay(level, id, who, t, day);
            for (Debts.Debt d : Debts.of(id, who)) {
                if (d.ref().equals("rent:" + t.getLong("anchor")) && day - d.since() >= BEHIND && t.getLong("noticeEnds") <= 0) notice(level, id, who, false);
            }
        }
    }

    /** Tests: the town's houses onto its homes' books. */
    public static void enrolForTests(UUID village) {
        Homes.enrol(village);
    }

    public static void resetForTests() {
        HELD.clear();
        LOADED.clear();
    }

    public static String startForTests(ServerPlayer p, UUID village, BlockPos anchor, int rent) {
        String s = start(p.serverLevel(), village, p.getUUID(), Belonging.called(p), anchor, rent, false);
        return s.isEmpty() ? "ok" : s;
    }
}
