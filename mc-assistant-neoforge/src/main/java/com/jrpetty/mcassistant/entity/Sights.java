package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /village sights: where the town's newer sights are to be seen, a line each with its place, for an
 * operator looking round and for the pictures (tools/realworld/smoke.py), which tp the camera from these
 * lines rather than guess. The welcome sign at the edge of town, the gazette's lectern in the hall, the
 * crier and the spot it reads from, the children's game and its playground, and every household (its
 * children, its chest, its garden and its pet).
 *
 * <p>The operator's "now"s (sign, gazette, crier, game, pet, garden) do what the town would have done
 * later today, at once: the same stores, the same purses and the same hands as ever, only not waiting
 * for the hour. Nothing is made for the pictures.
 */
public final class Sights {

    private Sights() {}

    /** Everything there is to look at in this town, a line each. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        BlockPos sign = WelcomeSign.postedAt(id);
        out.add(sign == null ? "SIGN none yet (the edge of town at " + at(WelcomeSign.columnForTests(level, v)) + ")"
            : "SIGN " + at(sign) + " out " + WelcomeSign.wayForTests(level, v).getName());   // its face is to the road coming in
        BlockPos lectern = Gazette.lecternForTests(level, id);
        out.add(lectern == null ? "LECTERN none (no hall)" : "LECTERN " + at(lectern));
        UUID crier = Crier.crier(id);
        VillageFolkEntity c = crier != null && level.getEntity(crier) instanceof VillageFolkEntity f ? f : null;
        BlockPos stand = Crier.standForTests(id);
        out.add("CRIER " + (c == null ? "none today" : c.displayNameCap() + " " + at(c.blockPosition()))
            + (stand == null ? "" : " stand " + at(stand)) + " read " + Crier.readForTests(id).size() + "/" + Crier.linesForTests(id).size());
        String game = Families.gameStateForTests(level, id);
        BlockPos ground = Families.playgroundForTests(id);
        out.add("GAME " + (game == null ? "none today" : game) + (ground == null ? "" : " ground " + at(ground)));
        out.addAll(Families.sightsLines(level, v));
        return out;
    }

    /** One of the town's sights made now (operators): what was done, a line each. */
    public static List<String> now(ServerLevel level, Villages.Village v, String what) {
        List<String> out = new ArrayList<>();
        switch (what) {
            case "sign" -> {
                String did = WelcomeSign.putForTests(level, v);
                out.add("SIGN-NOW " + (did == null ? "waits (nothing to make it with in the stores, or no ground at the edge)" : did));
            }
            case "gazette" -> {
                String did = Gazette.writeForTests(level, v);
                out.add("GAZETTE-NOW " + (did == null ? "waits (no hall, or no book and quill to be had)" : did));
            }
            case "crier" -> out.add("CRIER-NOW " + (Crier.beginForTests(level, v) ? "sent" : "nobody fit to send"));
            case "tag", "hide" -> out.add("GAME-NOW " + (Families.gameForTests(level, v.id(), what.equals("tag")) ? "begun" : "fewer than two children free"));
            case "pet" -> out.addAll(Families.sightsNow(level, v, true));
            case "garden" -> out.addAll(Families.sightsNow(level, v, false));
            default -> out.add("unknown: " + what + " (sign, gazette, crier, tag, hide, pet, garden)");
        }
        out.addAll(lines(level, v));
        return out;
    }

    /** The things /village sights &lt;what&gt; can make now. */
    public static List<String> kinds() {
        return List.of("sign", "gazette", "crier", "tag", "hide", "pet", "garden");
    }

    private static String at(@Nullable BlockPos p) {
        return p == null ? "?" : p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
