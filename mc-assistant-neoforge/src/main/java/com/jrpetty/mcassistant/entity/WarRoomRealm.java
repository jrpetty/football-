package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [realm-polish] The new realm on the war room's map table (WarRoom): the harbours and their lighthouses, the merchant ships
 * from over the sea in harbour or at sea, the roads between towns coloured by how far they are made (a track, an earth
 * path, gravel, stone) and the inns along them, the knights' lands and their manors, the wonders standing or rising, and on
 * each town's card the prisoners it holds.
 *
 * <p>The fog is the table's own (WarRoom.Known): a town's harbour, wonders and knights only for a town it knows and where
 * it knows the ground (its own always); a ship only where the ground is known or at its own harbour; a road between two
 * towns it knows. Kept small: a few numbers a thing, a road as its two ends and its middle, at most so many of each.
 */
public final class WarRoomRealm {

    private WarRoomRealm() {}

    /** At most so many of each kind in one overlay. */
    static final int MOST = 60;

    /** Does the viewer see this town's thing here: its own, or a known town's on known ground? */
    static boolean sees(WarRoom.Known k, UUID town, int x, int z) {
        return k.all || town.equals(k.town) || k.town(town) && k.at(x, z);
    }

    /** [WarRoom.build] The harbours, the roads by grade and their inns, the knights' lands and the wonders. */
    static void add(ServerLevel level, WarRoom.Known k, CompoundTag out) {
        ListTag harbours = new ListTag(), highways = new ListTag(), inns = new ListTag(), manors = new ListTag(), wonders = new ListTag();
        Set<String> roads = new HashSet<>();
        for (Villages.Village v : Villages.every()) {
            UUID id = v.id();
            if (!v.dim().equals(level.dimension()) || !k.town(id)) continue;
            harbour(level, k, id, harbours);
            for (KnightManors.Land l : KnightManors.lands(id)) {
                if (manors.size() >= MOST || !sees(k, id, l.at().getX(), l.at().getZ())) continue;
                CompoundTag t = new CompoundTag();
                t.putInt("x", l.at().getX());
                t.putInt("z", l.at().getZ());
                t.putInt("colour", Villages.colour(id));
                t.putBoolean("built", l.built());
                t.putString("label", l.name() + "'s land in " + Villages.name(id) + (l.built() ? ", the manor standing" : ", the manor not yet built"));
                manors.add(t);
            }
            wonders(k, id, wonders);
            for (RoadWorks.Road r : RoadWorks.roads(id)) {
                if (!roads.add(RoadWorks.key(r.a(), r.b()))) continue;
                if (highways.size() < MOST && k.town(r.a()) && k.town(r.b())) highway(r, highways);   // a road between two towns it knows
                inn(level, k, r, inns);                                                             // an inn: ours, or on ground it knows
            }
        }
        out.put("harbours", harbours);
        out.put("highways", highways);
        out.put("inns", inns);
        out.put("manors", manors);
        out.put("wonders", wonders);
    }

    /** A town's harbour: the office, the pier's end, the light (lit or dark). */
    static void harbour(ServerLevel level, WarRoom.Known k, UUID id, ListTag out) {
        Harbours.Shore s = Harbours.shore(id);
        if (s == null || out.size() >= MOST) return;
        Ledger.Building office = Villages.builtStructure(id, Harbours.OFFICE);
        if (office == null || !sees(k, id, s.water.getX(), s.water.getZ())) return;
        BlockPos end = s.at(Harbours.PIER - 1, 0, 0);
        CompoundTag t = new CompoundTag();
        t.putIntArray("pier", new int[]{ s.water.getX(), s.water.getZ(), end.getX(), end.getZ() });
        t.putBoolean("done", Harbours.pierDone(id));
        t.putInt("colour", Villages.colour(id));
        Ledger.Building light = Villages.builtStructure(id, Harbours.LIGHT);
        long day = level.getDayTime() / 24000L;
        boolean lit = light != null && (Harbours.lit(level, id) || Harbours.litLately(id, day));
        if (light != null) {
            t.putIntArray("light", new int[]{ light.anchor().getX(), light.anchor().getZ() });
            t.putBoolean("lit", lit);
        }
        t.putString("label", "The harbour of " + Villages.name(id) + ": " + (Harbours.pierDone(id) ? "the pier out into deep water" : "the pier being laid")
            + (light == null ? "" : lit ? "; its lighthouse burning" : "; its lighthouse dark"));
        out.add(t);
    }

    /** A road between two towns: its two ends and its middle, and each half's grade (0 a track ... 3 stone). */
    static void highway(RoadWorks.Road r, ListTag out) {
        List<int[]> line = r.line();
        if (line.size() < 2) return;
        int[] a = line.get(0), m = line.get(r.mid()), b = line.get(line.size() - 1);
        CompoundTag t = new CompoundTag();
        t.putIntArray("line", new int[]{ a[0], a[1], m[0], m[1], b[0], b[1] });
        t.putIntArray("grade", new int[]{ RoadWorks.half(r.a(), r.b()).grade, RoadWorks.half(r.b(), r.a()).grade });
        t.putString("label", "The road between " + Villages.name(r.a()) + " and " + Villages.name(r.b()) + ", " + r.length() + " blocks");
        out.add(t);
    }

    /** The inn halfway along a road, if one stands: where, whose, open or shut. */
    static void inn(ServerLevel level, WarRoom.Known k, RoadWorks.Road r, ListTag out) {
        Roadside.House h = Roadside.of(r.a(), r.b());
        if (h == null || "paying".equals(h.state) || out.size() >= MOST) return;
        if (!k.all && !h.owner.equals(k.town) && !h.other.equals(k.town) && !k.at(h.anchor.getX(), h.anchor.getZ())) return;
        CompoundTag t = new CompoundTag();
        t.putInt("x", h.anchor.getX());
        t.putInt("z", h.anchor.getZ());
        boolean open = Roadside.open(h);
        t.putBoolean("open", open);
        t.putString("label", "The inn on the road between " + Villages.name(h.owner) + " and " + Villages.name(h.other) + " ("
            + Villages.name(h.owner) + "'s): " + ("building".equals(h.state) ? "being built" : open ? "open" : Wars.atWar(h.owner, h.other) ? "shut by the war" : "shut, no keeper"));
        out.add(t);
    }

    /** The town's wonders: standing, and the one rising (how far on). */
    static void wonders(WarRoom.Known k, UUID id, ListTag out) {
        for (TownWonders.Kind w : TownWonders.Kind.values()) {
            Ledger.Building b = TownWonders.standing(id, w.structure);
            if (b == null || out.size() >= MOST || !sees(k, id, b.anchor().getX(), b.anchor().getZ())) continue;
            CompoundTag t = new CompoundTag();
            t.putInt("x", b.anchor().getX());
            t.putInt("z", b.anchor().getZ());
            t.putInt("pct", 100);
            t.putString("label", w.title() + " of " + Villages.name(id) + ", standing");
            out.add(t);
        }
        CompoundTag cur = TownWonders.current(id);
        if (cur == null || out.size() >= MOST || !cur.contains("site")) return;
        BlockPos site = BlockPos.of(cur.getLong("site"));
        if (!sees(k, id, site.getX(), site.getZ())) return;
        TownWonders.Kind w = TownWonders.Kind.of(cur.getString("structure"));
        int pct;
        try {
            pct = TownWonders.percent(id, cur);
        } catch (RuntimeException e) {
            pct = 0;
        }
        CompoundTag t = new CompoundTag();
        t.putInt("x", site.getX());
        t.putInt("z", site.getZ());
        t.putInt("pct", Math.min(99, pct));
        t.putString("label", (w == null ? "A wonder" : w.title()) + " of " + Villages.name(id) + ", rising: " + pct + "% done");
        out.add(t);
    }

    /** [WarRoom.movers] The merchant ships from over the sea: in harbour, sailing in, or sailing away. */
    static void ships(ServerLevel level, WarRoom.Known k, ListTag ms) {
        for (Villages.Village v : Villages.every()) {
            if (ms.size() >= 170) return;
            UUID id = v.id();
            if (!v.dim().equals(level.dimension())) continue;
            ShipCalls.Ship ship = ShipCalls.ship(id);
            Harbours.Shore s = ship == null ? null : Harbours.shore(id);
            if (s == null) continue;
            BlockPos at = s.at(ship.pos + ShipHull.LENGTH / 2, ShipHull.MIDDLE, 0);
            if (!k.all && !id.equals(k.town) && !k.at(at.getX(), at.getZ())) continue;
            ForeignShips.Land land = ship.land();
            CompoundTag m = new CompoundTag();
            m.putInt("x", at.getX());
            m.putInt("z", at.getZ());
            m.putString("kind", "ship");
            m.putInt("colour", land == null ? 0xFFFFFF : land.colour());
            String where = ship.docked() ? (ship.anchored ? "at anchor off the pier of " : "in harbour at ") : "out".equals(ship.stage) ? "sailing away from " : "sailing in to ";
            m.putString("label", ship.name + (land == null ? "" : ", a merchant ship of " + land.name()) + ", " + where + Villages.name(id));
            if (!ship.docked()) {
                BlockPos to = "out".equals(ship.stage) ? s.at(ShipHull.OFFING + 8, ShipHull.MIDDLE, 0) : s.at(ShipHull.BERTH, ShipHull.MIDDLE, 0);
                m.putIntArray("to", new int[]{ to.getX(), to.getZ() });
            }
            ms.add(m);
        }
    }

    /** [WarRoom.townTag] On a town's card: the prisoners it holds, and (on another's) how many of ours; its wonders and knights. */
    static void card(Villages.Village v, @Nullable UUID us, CompoundTag t) {
        UUID id = v.id();
        int held = Prisoners.heldBy(id).size();
        if (held > 0) t.putInt("prisoners", held);
        if (us != null && !us.equals(id)) {
            int ours = 0;
            for (Prisoners.Pow p : Prisoners.heldBy(id)) if (us.equals(p.home())) ours++;
            if (ours > 0) t.putInt("oursHeld", ours);
        } else if (us != null) {
            int away = Prisoners.ofTown(id).size();
            if (away > 0) t.putInt("oursHeld", away);
        }
        int w = TownWonders.count(id);
        if (w > 0) t.putInt("wonders", w);
        if (TownWonders.underWay(id)) t.putBoolean("rising", true);
        int knights = KnightManors.lands(id).size();
        if (knights > 0) t.putInt("knights", knights);
    }

    /** Tests: one overlay's counts of the new things {harbours, highways, inns, manors, wonders, ships}. */
    public static int[] countsForTests(CompoundTag overlay) {
        int ships = 0;
        for (net.minecraft.nbt.Tag m : overlay.getList("movers", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            if ("ship".equals(((CompoundTag) m).getString("kind"))) ships++;
        }
        return new int[]{ overlay.getList("harbours", 10).size(), overlay.getList("highways", 10).size(), overlay.getList("inns", 10).size(),
            overlay.getList("manors", 10).size(), overlay.getList("wonders", 10).size(), ships };
    }
}
