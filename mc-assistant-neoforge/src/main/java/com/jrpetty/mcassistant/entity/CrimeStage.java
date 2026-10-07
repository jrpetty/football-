package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * [crime] The operators' stage for the pictures (/village crime stage, try, stocks): a purse picked among the town's
 * own folk where the operator stands, in front of a witness, and the watch sent to it at once; the watch's case
 * finished and the council sat on it; the stocks put up and the latest convicted sat in them. Everything that happens
 * is the real thing (the coins move, the court weighs the evidence); only the time it takes is shortened, and the
 * folk are set where the camera can see them. Each step says where the camera should stand ("VIEW name x y z ax ay
 * az", the camera's feet and what it looks at).
 */
final class CrimeStage {

    private CrimeStage() {}

    /** A purse picked at the operator's feet: a tempted folk, a victim with coins, a witness nine blocks off. */
    static List<String> scene(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> grown = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase() && f.isAlive() && f.stationTask() != StationTask.GUARD
                    && !Trial.sentenced(f)) grown.add(f);
        }
        VillageFolkEntity culprit = null, victim = null, witness = null;
        int most = Integer.MIN_VALUE;
        for (VillageFolkEntity f : grown) {
            if (f.getUUID().equals(Villages.elder(v.id()))) continue;
            int t = Mischief.temptation(level, f) * 10 - Mischief.honesty(f);
            if (t > most) { most = t; culprit = f; }
        }
        for (VillageFolkEntity f : grown) {
            if (f == culprit || f.purse() < 1) continue;
            if (victim == null || f.purse() > victim.purse()) victim = f;
        }
        for (VillageFolkEntity f : grown) {
            if (f == culprit || f == victim) continue;
            if (culprit != null && (f.getUUID().equals(culprit.life().partner()) || f.life().affinity(culprit.getUUID()) >= Social.CLOSE)) continue;
            witness = f;
            break;
        }
        if (culprit == null || victim == null) {
            out.add("STAGE nobody to stage it with: " + (culprit == null ? "no grown folk" : "nobody has a coin to lose"));
            return out;
        }
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at);
        place(victim, ground, Direction.SOUTH);
        place(culprit, ground.relative(Direction.WEST), Direction.EAST);
        if (witness != null) place(witness, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ground.relative(Direction.EAST, 9)), Direction.WEST);
        Crime.hurry = true;
        int before = victim.purse();
        Crime.Case c = Mischief.commitNow(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
        if (c == null) {
            out.add("STAGE the purse was not picked");
            return out;
        }
        Crime.report(level, v, c, victim.displayNameCap());
        boolean assigned = Inquiry.assign(level, v, c);
        List<String> saw = new ArrayList<>();
        for (Crime.Witness w : c.witnesses) saw.add(w.name + " (" + w.certainty + ")");
        out.add("STAGE case #" + c.id + ": " + culprit.displayNameCap() + " picked " + victim.displayNameCap() + "'s purse of " + c.coins + " (it had "
            + before + "); seen by " + (saw.isEmpty() ? "nobody" : String.join(", ", saw)) + "; " + (assigned ? c.investigatorName + " on the case" : "no watch to take it"));
        out.add(view("crime-1-scene", ground.offset(6, 3, 6), ground));
        return out;
    }

    private static void place(VillageFolkEntity f, BlockPos at, Direction looks) {
        f.clearQueue();
        f.getNavigation().stop();
        f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        f.setYRot(looks.toYRot());
        f.setYHeadRot(looks.toYRot());
        f.setYBodyRot(looks.toYRot());
    }

    /** The watch's case finished at once and the council sat on it now. */
    static List<String> court(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        Crime.hurry = true;
        for (Crime.Case c : Crime.open(v.id())) {
            if (c.stage == Crime.Stage.REPORTED || c.stage.investigating()) {
                Inquiry.finishNow(level, v, c);
                out.add("CASE #" + c.id + ": " + c.stage.words + (c.accused == null ? "" : ", " + c.accusedName + " named") + ". " + Inquiry.suspectsWords(c));
                break;
            }
        }
        Trial.Sitting s = Trial.openNow(level, v);
        if (s == null) {
            out.add("COURT nobody awaits trial");
            return out;
        }
        out.add("COURT the council sits " + (s.hall ? "in the hall" : "on the square") + ", " + s.judgeName + " presiding, " + s.called.size() + " called");
        BlockPos behind = s.dock.relative(s.faces.getOpposite(), 5).relative(s.faces.getClockWise(), 2).above(s.hall ? 1 : 2);
        out.add(view("crime-2-court", behind, s.at));
        return out;
    }

    /** The stocks put up (out of the stores) and the latest convicted sat in them for the rest of the day. */
    static List<String> stocks(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        boolean was = TownJobs.instantNow();
        TownJobs.instantForTests(true);
        boolean up;
        try {
            up = Trial.putUp(level, v);
        } finally {
            TownJobs.instantForTests(was);
        }
        BlockPos at = Trial.stocksAt(level, v);
        if (!up || at == null) {
            out.add("STOCKS the stores cannot run to a pair of stocks (three planks and two logs)");
            return out;
        }
        VillageFolkEntity who = null;
        for (Crime.Case c : Crime.cases(v.id())) {
            if (c.stage != Crime.Stage.CONVICTED || c.accused == null) continue;
            who = Civics.find(level, c.accused);
            if (who != null) {
                CompoundTag r = Crime.folk(who.getUUID());
                r.putString("sentence", "stocks");
                r.putLong("until", Trial.until(level.getDayTime()));
                r.putInt("sentenceCase", c.id);
                Crime.changed();
                Vec3 spot = Trial.sitSpot(level, at);
                who.teleportTo(spot.x, spot.y, spot.z);
                break;
            }
        }
        Crime.hurry = false;
        out.add("STOCKS on the square at " + at.toShortString() + (who == null ? ", nobody to sit in them" : ", " + who.displayNameCap() + " sat in them"));
        Direction facing = level.getBlockState(at).getValue(com.jrpetty.mcassistant.block.StocksBlock.FACING);
        out.add(view("crime-3-stocks", at.relative(facing, 4).relative(facing.getClockWise(), 1), at));
        return out;
    }

    private static String view(String name, BlockPos eye, BlockPos look) {
        return "VIEW " + name + " " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + look.getX() + " " + (look.getY() + 1) + " " + look.getZ();
    }
}
