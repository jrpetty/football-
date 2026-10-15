package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.CameraCommands;
import com.jrpetty.mcassistant.Gallery;
import com.jrpetty.mcassistant.entity.Palettes;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [gallery] The photo gallery's stage (Gallery, /village showcase gallery): every drawing the mod has put up, row by
 * row on the one stage, as a town of the Iron Age puts it up (its own woods, furnished out of its stores), and each
 * with its two camera spots good before a client is ever asked to stand on them: the eyes in open air outside with the
 * front door in sight, and in open air inside, a step or two in from the door, with room in front of them.
 *
 * <p>On ground of its own: x 2400000 to 2400400, z 69900 to 70100.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class GalleryGameTests {

    private static final String EMPTY = "empty";
    private static final int X = 2400050, Z = 70000;

    /** The rooms a town furnishes (Interiors.FURNISHED). */
    private static final Set<String> FURNISHED = Set.of("house", "manor", "townhall", "hall", "tavern", "cafe", "shop", "library",
        "chapel", "guesthouse", "barracks", "school");

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "gl01_every_building_laid_out_for_its_pictures")
    public static void gl01_every_building_laid_out_for_its_pictures(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Gallery.resetForTests();
        List<String> names = Gallery.drawings(level.getServer());
        List<List<String>> rows = Gallery.rows(names);
        int longest = 0;
        for (List<String> r : rows) {
            int n = 0;
            for (String s : r) n += 2 * Blueprints.fullHalf(s)[0] + 11;
            longest = Math.max(longest, n);
        }
        // The stage and the town's heart behind it, held for the test.
        for (int cx = (X - 40) >> 4; cx <= (X + longest + 40) >> 4; cx++) {
            for (int cz = (Z - 80) >> 4; cz <= (Z + 90) >> 4; cz++) level.setChunkForced(cx, cz, true);
        }
        BlockPos start = Kit.surface(level, X, Z);
        List<Gallery.Shot> shots = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (int k = 0; k < rows.size(); k++) {
                List<Gallery.Shot> row = Gallery.row(level, start, k, Villages.Age.IRON);
                // Each row checked as it stands: the next one clears it away.
                check(level, row, shots);
                shots.addAll(row);
            }
        } finally {
            Kit.log(String.format("gl01 %d drawings in %d rows laid out in %.1f s", names.size(), rows.size(), (System.nanoTime() - t0) / 1.0e9));
        }
        List<String> faults = new ArrayList<>(FAULTS);
        FAULTS.clear();
        Set<String> laid = new HashSet<>();
        for (Gallery.Shot s : shots) laid.add(s.name());
        if (names.size() < 90) faults.add("only " + names.size() + " drawings found");
        for (String n : names) if (!laid.contains(n)) faults.add(n + ": not laid out");
        for (String n : List.of("house", "cathedral", "tavern", "station", "well")) if (!names.contains(n)) faults.add(n + ": not among the drawings");
        // Built of the town's own woods: the house's walls of the first wood its builders reach for.
        UUID town = Gallery.townForTests();
        Item wall = null;
        for (Item it : Palettes.ranked(town, Blueprints.Style.WALL)) if (it instanceof BlockItem) { wall = it; break; }
        Kit.log("gl01 the town's walls: " + wall + ", its look: " + Palettes.line(town));
        for (String f : faults) Kit.log("gl01 FAULT " + f);
        helper.assertTrue(faults.isEmpty(), faults.size() + " faults, first: " + (faults.isEmpty() ? "" : faults.get(0)));
        for (int cx = (X - 40) >> 4; cx <= (X + longest + 40) >> 4; cx++) {
            for (int cz = (Z - 80) >> 4; cz <= (Z + 90) >> 4; cz++) level.setChunkForced(cx, cz, false);
        }
        helper.succeed();
    }

    private static final List<String> FAULTS = new ArrayList<>();

    /** A row as it stands: every building up, furnished where a town furnishes, and both its camera spots good. */
    private static void check(ServerLevel level, List<Gallery.Shot> row, List<Gallery.Shot> before) {
        UUID town = Gallery.townForTests();
        Block wall = null;
        for (Item it : Palettes.ranked(town, Blueprints.Style.WALL)) if (it instanceof BlockItem bi) { wall = bi.getBlock(); break; }
        for (Gallery.Shot s : row) {
            String n = s.name();
            int blocks = 0, walls = 0, wallsOfTheTown = 0;
            for (BuildGoal.Placement p : BuildGoal.plan(n, s.anchor(), Gallery.BACK, 13)) {
                BlockState st = level.getBlockState(p.pos());
                if (!st.isAir()) blocks++;
                if (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.WALL) {
                    walls++;
                    if (wall != null && st.is(wall)) wallsOfTheTown++;
                }
            }
            if (blocks == 0) FAULTS.add(n + ": nothing built");
            if (n.equals("house") && walls > 0 && wallsOfTheTown * 2 < walls) {
                FAULTS.add("house: " + wallsOfTheTown + " of " + walls + " walls of the town's " + wall);
            }
            if (s.door() != null) {
                BlockState d = level.getBlockState(s.door());
                if (!d.is(BlockTags.DOORS) && !d.is(BlockTags.FENCE_GATES)) FAULTS.add(n + ": no door at " + s.door().toShortString() + " (" + d + ")");
                if (!s.doorSeen()) FAULTS.add(n + ": the door is not in sight from outside");
            }
            if (!CameraCommands.sees(level, s.out(), s.outCheck())) FAULTS.add(n + ": the outside camera is not clear at " + v(s.out()));
            if (!CameraCommands.sees(level, s.in(), s.inCheck())) FAULTS.add(n + ": the inside camera is not clear at " + v(s.in()));
            if (s.door() != null && !s.inHow().equals("above")) {
                double far = s.in().add(0, Gallery.EYES, 0).distanceTo(s.inCheck());
                if (far < (s.inHow().equals("cramped") ? 0.1 : 2.0)) FAULTS.add(n + ": the inside camera sees only " + String.format("%.1f", far) + " blocks");
            }
            if (FURNISHED.contains(n)) {
                // Furnished as the town furnishes it: whatever Interiors has worked out for the room is in.
                java.util.Map<String, Integer> plan = com.jrpetty.mcassistant.entity.Interiors.planForTests(level, town,
                    new com.jrpetty.mcassistant.village.Ledger.Building(n, s.anchor(), Gallery.BACK), 2);
                int planned = 0;
                for (int c : plan.values()) planned += c;
                Kit.log("gl01 " + n + " furnished: " + s.pieces() + " pieces in, the plan " + plan);
                if (planned > 0 && s.pieces() <= 0) FAULTS.add(n + ": not furnished (the plan " + plan + ")");
            }
            Kit.log("gl01 " + s.line());
        }
    }

    private static String v(Vec3 p) {
        return String.format("%.1f %.1f %.1f", p.x, p.y, p.z);
    }
}
