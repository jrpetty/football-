package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [featbuild] The operators' staging for the photographs (FeatStageCommands): the quarry opened and part cut, a statue
 * garden put up with its statue, the wall's stage set, and where the night watch is. Everything here is what the town does
 * by itself in time, done at once.
 */
public final class FeatStage {

    private FeatStage() {}

    public static List<String> quarry(ServerLevel level, Villages.Village v, int cut) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        Quarry.Site s = Quarry.site(id);
        if (s != null && cut < 0) {                                       // a new site: the old one closed
            Quarry.close(level, v, s, "moved for the film");
            Ledger.forget(id, StoneYard.SITE);
            Ledger.forget(id, "yard.laid");
            s = null;
            cut = -cut;
        }
        if (s == null) {
            // The ground out past the growth line loaded, for the site to be looked for.
            int reach = Quarry.growthReach(id) + 60;
            for (int x = (v.centre().getX() - reach) >> 4; x <= (v.centre().getX() + reach) >> 4; x++) {
                for (int z = (v.centre().getZ() - reach) >> 4; z <= (v.centre().getZ() + reach) >> 4; z++) level.getChunk(x, z);
            }
            s = Quarry.choose(level, v);
            if (s == null) { out.add("QUARRY none: no dry ground out past the growth line (" + Quarry.growthReach(id) + ")"); return out; }
            Quarry.open(level, v, s);
        }
        StoneYard.Site y = StoneYard.site(id);
        if (y != null) StoneYard.layOut(level, v, y, true);
        int n = cut > 0 ? Quarry.cutForTests(level, v, cut) : 0;
        Quarry.fenceForTests(level, v);                                   // round the pit as it stands once it is cut
        if (y != null) {
            StoneYard.dress(level, v, y, true);
            StoneYard.piles(level, id, y);
        }
        Quarry.men(level, v);
        s = Quarry.site(id);
        if (s != null) out.add("QUARRY " + s.centre().getX() + " " + s.centre().getY() + " " + s.centre().getZ() + " size " + s.size() + " cut " + n
            + " growth " + Quarry.growthReach(id));
        if (y != null) out.add("YARD " + y.centre().getX() + " " + y.centre().getY() + " " + y.centre().getZ() + " gate " + y.gate().getName()
            + " stock " + StoneYard.stock(id));
        return out;
    }

    public static List<String> garden(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        Ledger.Building g = null;
        for (Ledger.Building b : Monuments.gardens(id)) if (Monuments.statueAt(id, b.anchor()) == null) g = b;
        if (g == null) {
            Villages.Site site = Villages.siteFor(level, id, Park.GARDEN);
            BlockPos at = site == null ? null : site.anchor();
            Direction facing = site == null ? Direction.NORTH : site.facing();
            if (at == null) {
                // The plan's lots all spoken for (a grown town): the nearest open, level ground to the heart, for the picture.
                at = openGround(level, v);
                if (at == null) { out.add("GARDEN none: no lot and no open ground near"); return out; }
                int dx = at.getX() - v.centre().getX(), dz = at.getZ() - v.centre().getZ();
                facing = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            }
            g = Monuments.putUp(level, id, at, facing);
        }
        if (g == null) { out.add("GARDEN none"); return out; }
        List<Object[]> c = Monuments.candidates(id);
        if (!c.isEmpty() && Monuments.statueAt(id, g.anchor()) == null) Monuments.raise(level, v, g, (VillageFolkEntity) c.get(0)[0], true);
        String[] st = Monuments.statueAt(id, g.anchor());
        out.add("GARDEN " + g.anchor().getX() + " " + g.anchor().getY() + " " + g.anchor().getZ() + " facing " + g.facing().getName()
            + " statue " + (st == null ? "none" : st[2] + " (" + st[4] + ")"));
        return out;
    }

    /** Level, open ground thirteen square near the heart (nothing built on it, no water): the first found going out. */
    static BlockPos openGround(ServerLevel level, Villages.Village v) {
        BlockPos c = v.centre();
        for (int r = 24; r <= 220; r += 6) {
            for (int k = 0; k < Math.max(8, r / 3); k++) {
                double a = Math.PI * 2 * k / Math.max(8, r / 3);
                int x = c.getX() + (int) Math.round(Math.cos(a) * r), z = c.getZ() + (int) Math.round(Math.sin(a) * r);
                if (!level.hasChunk((x - 7) >> 4, (z - 7) >> 4) || !level.hasChunk((x + 7) >> 4, (z + 7) >> 4)) continue;
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                boolean ok = true;
                for (int dx = -6; dx <= 6 && ok; dx++) {
                    for (int dz = -6; dz <= 6 && ok; dz++) {
                        int y = Quarry.surface(level, x + dx, z + dz);
                        if (y == Integer.MIN_VALUE) { ok = false; break; }
                        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                        for (int up = y + 1; up < top && ok; up++) {
                            BlockPos p = new BlockPos(x + dx, up, z + dz);
                            if (!CastleWall.wild(level, p, level.getBlockState(p))) ok = false;
                        }
                        lo = Math.min(lo, y);
                        hi = Math.max(hi, y);
                    }
                }
                if (ok && hi - lo <= 3) return new BlockPos(x, (hi + lo) / 2 + 1, z);   // levelled by putUp (ParkGround.levelNow)
            }
        }
        return null;
    }

    public static List<String> danger(ServerLevel level, Villages.Village v, int stage) {
        List<String> out = new ArrayList<>();
        Ledger.note(v.id(), TownDanger.STAGE, Integer.toString(stage));
        TownDanger.forget(v.id());
        out.add("DANGER stage " + stage + " (" + com.jrpetty.mcassistant.village.DangerLevel.stageName(stage) + ")");
        return out;
    }

    /** So many more grown folk put on the watch (the photographs of a night watch need a watch to photograph). */
    public static List<String> enlist(ServerLevel level, Villages.Village v, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = AdminTools.enlist(v);
            if (f == null) break;
            out.add("ENLISTED " + f.displayNameCap());
        }
        return out;
    }

    public static List<String> watch(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        out.add("HEART " + v.centre().getX() + " " + v.centre().getY() + " " + v.centre().getZ() + " stage " + TownDanger.stage(level, v.id())
            + " danger " + TownDanger.reading(level, v.id()).score());
        for (CastleWall.Gate g : CastleWall.gates(level, v.id())) {
            out.add("GATE " + g.out().getName() + " " + g.gateway().getX() + " " + g.gateway().getY() + " " + g.gateway().getZ() + (g.stands() ? " hung" : ""));
        }
        for (NightWatch.Post p : NightWatch.posts(level, v.id())) {
            List<BlockPos> r = NightWatch.route(level, v, p, false);
            if (!r.isEmpty()) out.add("POST " + p.name().replace(' ', '_') + " " + r.get(r.size() / 2).getX() + " " + r.get(r.size() / 2).getY() + " " + r.get(r.size() / 2).getZ()
                + " stops " + r.size());
        }
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity g) || g.stationTask() != AssistantEntity.StationTask.GUARD) continue;
            boolean lit = NightLight.isLight(g.getItemBySlot(EquipmentSlot.OFFHAND));
            BlockPos stop = NightWatch.stopForTests(g);
            out.add("WATCH " + g.displayNameCap() + " " + g.blockPosition().getX() + " " + g.blockPosition().getY() + " " + g.blockPosition().getZ()
                + (lit ? " lantern" : "") + (stop == null ? "" : " stop " + stop.toShortString()) + (g.isSleeping() ? " asleep" : "")
                + " duty " + Roster.dutyOf(level, g));
        }
        if (out.isEmpty()) out.add("WATCH nobody");
        return out;
    }
}
