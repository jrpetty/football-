package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A thriving town builds faster (Villages.thriving, crewsAllowed, projectFor): with food to spare and a
 * bed for nearly everyone, a town of sixteen has two crews raising two different buildings at once, and
 * a short wait between one project and the next; short of food, it is back to one crew and the old pace.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BuildCrewsGameTests {

    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "bc01_crews")
    public static void bc01_crews(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 550000, z = 60000;
        Kit.hold(level, x, z, 24);
        Kit.prepare(level, x, z, 24);
        BlockPos at = Kit.surface(level, x, z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null, "a folk");
            folk.add(f);
        }
        UUID v = folk.get(0).ownerId();
        long now = level.getGameTime();

        Leader.booksForTests(v, new Leader.Books(2000, 120, 50, 120.0, 50.0, 40.0, Leader.Plan.STEADY, 0L));
        Kit.log("bc01 steady: thriving " + Villages.thriving(v) + ", crews " + Villages.crewsAllowed(v) + ", housing " + Villages.housing(v));
        helper.assertTrue(Villages.crewsAllowed(v) == 1, "a town getting by builds one thing at a time");

        Leader.booksForTests(v, new Leader.Books(4000, 200, 50, 200.0, 50.0, 80.0, Leader.Plan.PLENTY, 0L));
        helper.assertTrue(Villages.thriving(v), "food to spare and a bed for nearly all: a thriving town");
        helper.assertTrue(Villages.crewsAllowed(v) == 2, "a thriving town of sixteen has two crews: " + Villages.crewsAllowed(v));

        UUID a = folk.get(1).getUUID(), b = folk.get(2).getUUID(), c = folk.get(3).getUUID();
        helper.assertTrue(Villages.isLead(v, a, now), "the first hand takes a crew");
        String pa = Villages.projectFor(v, a);
        Villages.leadOn(v, a, pa);
        helper.assertTrue(Villages.isLead(v, b, now), "a second hand takes the second crew");
        String pb = Villages.projectFor(v, b);
        Villages.leadOn(v, b, pb);
        boolean third = Villages.isLead(v, c, now);
        Kit.log("bc01 thriving: wanted " + Villages.projectsWanted(v) + "; crews " + Villages.crewsReport(v, now) + "; a third hand leads " + third);
        helper.assertTrue(pa != null && pb != null && !pa.equals(pb), "two crews raise two different buildings: " + pa + ", " + pb);
        helper.assertTrue(!third, "no third crew in a town of sixteen");
        helper.assertTrue(Villages.ledByAnother(v, c, now), "the third hand sees both crews in other hands");
        helper.assertTrue(pa.equals(Villages.projectFor(v, a)), "a crew keeps to its building");
        helper.succeed();
    }
}
