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
        Quarry.fenceForTests(level, v);
        int n = cut > 0 ? Quarry.cutForTests(level, v, cut) : 0;
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
            if (site == null) { out.add("GARDEN none: no lot for one yet"); return out; }
            g = Monuments.putUp(level, id, site.anchor(), site.facing());
        }
        if (g == null) { out.add("GARDEN none"); return out; }
        List<Object[]> c = Monuments.candidates(id);
        if (!c.isEmpty() && Monuments.statueAt(id, g.anchor()) == null) Monuments.raise(level, v, g, (VillageFolkEntity) c.get(0)[0], true);
        String[] st = Monuments.statueAt(id, g.anchor());
        out.add("GARDEN " + g.anchor().getX() + " " + g.anchor().getY() + " " + g.anchor().getZ() + " facing " + g.facing().getName()
            + " statue " + (st == null ? "none" : st[2] + " (" + st[4] + ")"));
        return out;
    }

    public static List<String> danger(ServerLevel level, Villages.Village v, int stage) {
        List<String> out = new ArrayList<>();
        Ledger.note(v.id(), TownDanger.STAGE, Integer.toString(stage));
        TownDanger.forget(v.id());
        out.add("DANGER stage " + stage + " (" + com.jrpetty.mcassistant.village.DangerLevel.stageName(stage) + ")");
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
