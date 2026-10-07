package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [econ-trade] A caravan or an envoy on the road, kept in the ledger of the town it set out from (trip/&lt;folk&gt;),
 * so a restart does not lose it. The trip itself lived only in the carrier's head (VillageFolkEntity.trip):
 * after a restart the carrier stood in the road with the other town's goods on its back and the coin it was
 * carrying gone — coin out of the world. Now every few seconds each trip on the road is written down (which
 * way, which leg, how far along, the coin it carries, the deal it is for, what it has to report), and a
 * carrier that comes back into the world with no trip in its head is given it back and walks on. A trip
 * that ends is let go (Caravans.arrive, Caravans.abandon); one whose carrier is never seen again is given
 * up after three days.
 */
final class TradeTrips {

    private TradeTrips() {}

    private static final String KEY = "trip/";
    /** How long a written trip waits for its carrier to come back into the world. */
    private static final long GIVE_UP = 3 * 24000L;

    /** Every few seconds: the trips on the road written down, and any a carrier has lost given back to it. */
    static void keep(ServerLevel level) {
        long now = level.getGameTime();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && f.trip() != null && f.isAlive()) save(f, f.trip(), now);
            }
            for (Map.Entry<String, String> e : Ledger.notes(v.id()).entrySet()) {
                if (!e.getKey().startsWith(KEY) || e.getValue() == null || e.getValue().isEmpty()) continue;
                UUID who;
                try {
                    who = UUID.fromString(e.getKey().substring(KEY.length()));
                } catch (IllegalArgumentException ex) {
                    Ledger.forget(v.id(), e.getKey());
                    continue;
                }
                Entity ent = level.getEntity(who);
                if (ent instanceof VillageFolkEntity f && f.isAlive()) {
                    if (f.trip() == null) restore(level, f, e.getValue());
                } else if (now - savedAt(e.getValue()) > GIVE_UP || now < savedAt(e.getValue())) {
                    Ledger.forget(v.id(), e.getKey());
                    String[] p = e.getValue().split("\\|", -1);
                    if (p.length > 2) {
                        try {
                            Villages.tell(v.id(), level.getDayTime() / 24000L, "the caravan to " + Villages.name(UUID.fromString(p[2]))
                                + " never came home, and what it carried was lost with it");
                        } catch (IllegalArgumentException ignored) {
                            // nowhere to name
                        }
                    }
                }
            }
        }
    }

    /** Is a trip between these two written down, its carrier not yet back in the world? */
    static boolean pending(UUID a, UUID b) {
        for (UUID v : new UUID[]{ a, b }) {
            for (Map.Entry<String, String> e : Ledger.notes(v).entrySet()) {
                if (!e.getKey().startsWith(KEY) || e.getValue() == null || e.getValue().isEmpty()) continue;
                String[] p = e.getValue().split("\\|", -1);
                if (p.length > 2 && (p[1].equals(a.toString()) && p[2].equals(b.toString()) || p[1].equals(b.toString()) && p[2].equals(a.toString()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The trip is over (home, or lost): let it go. */
    static void over(VillageFolkEntity f) {
        Caravans.Trip t = f.trip();
        if (t != null) Ledger.forget(t.from, KEY + f.getUUID());
    }

    static void save(VillageFolkEntity f, Caravans.Trip t, long now) {
        StringBuilder unsold = new StringBuilder();
        for (ItemStack s : t.unsold) {
            if (s.isEmpty()) continue;
            if (unsold.length() > 0) unsold.append(',');
            unsold.append(TradeDeals.id(s.getItem())).append('*').append(s.getCount());
        }
        String line = String.join("|", "v1", t.from.toString(), t.to.toString(), t.back ? "1" : "0", Integer.toString(t.at),
            t.errand == null ? "" : t.errand.name(), t.trade ? "1" : "0", Integer.toString(t.purse), t.deal == null ? "" : t.deal,
            Integer.toString(t.earned), t.waiting ? "1" : "0", unsold.toString(), Long.toString(now),
            t.outcome == null ? "" : t.outcome.replace("|", "/"));
        String key = KEY + f.getUUID();
        if (!line.equals(Ledger.note(t.from, key))) Ledger.note(t.from, key, line);
    }

    private static long savedAt(String line) {
        String[] p = line.split("\\|", -1);
        try {
            return p.length > 12 ? Long.parseLong(p[12]) : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Give a carrier its trip back, as it was written: the way worked out again, the leg, the coin, the deal. */
    static void restore(ServerLevel level, VillageFolkEntity f, String line) {
        String[] p = line.split("\\|", -1);
        if (p.length < 14 || !p[0].equals("v1")) return;
        try {
            UUID from = UUID.fromString(p[1]), to = UUID.fromString(p[2]);
            Villages.Village fv = Villages.get(from), tv = Villages.get(to);
            if (fv == null || tv == null) {
                Ledger.forget(from, KEY + f.getUUID());
                return;
            }
            boolean back = p[3].equals("1");
            Envoys.Errand errand = p[5].isEmpty() ? null : Envoys.Errand.valueOf(p[5]);
            String outcome = p[13].isEmpty() ? null : p[13];
            // An envoy that had had its answer but not yet turned for home: it turns now.
            if (errand != null && !back && outcome != null) back = true;
            List<BlockPos> way = back ? Caravans.way(tv, fv) : Caravans.way(fv, tv);
            Caravans.Trip t = new Caravans.Trip(from, to, new java.util.ArrayList<>(way));
            t.back = back;
            t.at = Math.max(0, Math.min(way.size(), Integer.parseInt(p[4])));
            t.errand = errand;
            t.trade = p[6].equals("1");
            t.purse = Integer.parseInt(p[7]);
            t.deal = p[8].isEmpty() ? null : p[8];
            t.earned = Integer.parseInt(p[9]);
            t.outcome = outcome;
            // An envoy that was waiting to be heard comes to the board again (Envoys.arrived), and asks again.
            if (p[10].equals("1") && !back) t.at = way.size();
            if (!p[11].isEmpty()) {
                for (String one : p[11].split(",")) {
                    String[] kv = one.split("\\*", 2);
                    Item it = TradeBook.itemOf(kv[0]);
                    if (it != null && kv.length > 1) t.unsold.add(new ItemStack(it, Integer.parseInt(kv[1])));
                }
            }
            t.gainedTick = f.tickCount;
            f.trip(t);
            com.mojang.logging.LogUtils.getLogger().info("[MCA-TRADE] {} took up its trip again: {} -> {}, {} leg, step {} of {}, {} coin",
                f.displayNameCap(), Villages.name(from), Villages.name(to), back ? "homeward" : "outward", t.at, way.size(), t.purse);
        } catch (RuntimeException e) {
            Ledger.forget(f.ownerId() == null ? UUID.randomUUID() : f.ownerId(), KEY + f.getUUID());
        }
    }

    /** Tests: write this carrier's trip down, take it out of its head (as a restart does), and give it back. */
    static boolean restartForTests(ServerLevel level, VillageFolkEntity f) {
        Caravans.Trip t = f.trip();
        if (t == null) return false;
        save(f, t, level.getGameTime());
        f.trip(null);
        keep(level);
        return f.trip() != null;
    }
}
