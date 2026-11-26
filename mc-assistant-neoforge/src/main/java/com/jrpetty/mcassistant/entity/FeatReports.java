package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.DangerLevel;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.UUID;

/**
 * [featbuild] What the board's three new pages show (Annals hands them on): the Defences page (the danger, the wall and its
 * gates, the night watch's rota and log), the Parks page (the parks and statue gardens, who is to be honoured next) and the
 * Stone page (the quarry, the yard's stock and flow, who is waiting on stone).
 */
public final class FeatReports {

    private FeatReports() {}

    public static CompoundTag defences(ServerLevel level, Villages.Village v) {
        CompoundTag c = new CompoundTag();
        UUID id = v.id();
        DangerLevel.Reading r = TownDanger.reading(level, id);
        int stage = TownDanger.stage(level, id);
        c.putInt("score", r.score());
        c.putString("word", DangerLevel.word(r.score()));
        c.putInt("stage", stage);
        c.putInt("calls", r.stage());
        c.putString("stageName", DangerLevel.stageName(stage));
        c.putString("next", stage >= DangerLevel.TOWERS ? "" : "danger " + DangerLevel.AT[stage] + " for " + DangerLevel.stageName(stage + 1));
        c.putIntArray("parts", new int[]{ r.raidPart(), r.monsterPart(), r.warPart(), r.wealthPart() });
        c.putIntArray("at", DangerLevel.AT);
        ListTag why = new ListTag();
        for (String s : r.reasons()) why.add(StringTag.valueOf(s));
        c.put("reasons", why);
        c.putBoolean("live", CastleWall.live());
        c.putBoolean("begun", CastleWall.begun(id));
        int[] f = CastleWall.figures(id);
        c.putIntArray("wall", f);
        ListTag lines = new ListTag();
        if (CastleWall.live()) for (String s : CastleWall.lines(level, v)) lines.add(StringTag.valueOf(s));
        c.put("lines", lines);
        // The wall's four sides, each its middle on the walk for a marker; the gates with whether they are shut.
        ListTag sides = new ListTag();
        ListTag gates = new ListTag();
        if (CastleWall.live() && !CastleWall.plan(id).isEmpty()) {
            WallPlan.Sides s = CastleWall.sides(id);
            String[] names = { "north", "east", "south", "west" };
            for (int side = 0; side < 4; side++) {
                CompoundTag sd = new CompoundTag();
                sd.putString("name", "The " + names[side] + " wall");
                List<BlockPos> walk = CastleWall.walkway(level, id, side, 6);
                BlockPos at;
                if (!walk.isEmpty()) at = walk.get(walk.size() / 4);
                else {
                    int[] o = WallPlan.offset(side, s.of(side) + 1, 12);
                    int x = v.centre().getX() + o[0], z = v.centre().getZ() + o[1];
                    at = new BlockPos(x, level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : v.centre().getY(), z);
                }
                sd.putIntArray("at", new int[]{ at.getX(), at.getY(), at.getZ() });
                sd.putInt("line", s.of(side));
                sd.putInt("walk", walk.size());
                sides.add(sd);
            }
            for (CastleWall.Gate g : CastleWall.gates(level, id)) {
                CompoundTag gt = new CompoundTag();
                gt.putString("name", "The " + g.out().getName() + " gate");
                gt.putIntArray("at", new int[]{ g.gateway().getX(), g.gateway().getY(), g.gateway().getZ() });
                gt.putBoolean("hung", g.stands());
                int shut = 0, all = 0;
                for (BlockPos d : g.doors()) {
                    BlockState st = level.getBlockState(d);
                    if (!(st.getBlock() instanceof DoorBlock)) continue;
                    all++;
                    if (!st.getValue(DoorBlock.OPEN)) shut++;
                }
                gt.putBoolean("shut", all > 0 && shut == all);
                gates.add(gt);
            }
            WallPlan.Sides old = CastleWall.oldLine(id);
            if (old != null) c.putString("old", "The old line (" + old.encode() + ") is coming down as the new one stands.");
        }
        c.put("sides", sides);
        c.put("gates", gates);
        c.putString("gatesHours", "Gates open by day and shut from dusk to dawn, and whenever the bell rings.");
        c.put("watch", NightWatch.report(level, v));
        c.putBoolean("alarm", Raids.underAlarm(id));
        if (Raids.underAlarm(id)) c.putString("alarmWhy", String.valueOf(Raids.why(id)));
        return c;
    }

    public static CompoundTag greens(ServerLevel level, Villages.Village v) {
        return Monuments.report(level, v);
    }

    public static CompoundTag stone(ServerLevel level, Villages.Village v) {
        CompoundTag c = new CompoundTag();
        c.put("quarry", Quarry.report(level, v));
        c.put("yard", StoneYard.report(level, v.id()));
        return c;
    }
}
