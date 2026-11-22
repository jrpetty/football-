package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32b] The ores the town's age is waiting on, for the works (MineDemand).
 *
 * <p>The works share their miners out by what the stores are short of, each ore's want against its stock. A Diamond Age
 * town of fifty-five was waiting on 140 more iron (the age asks twice the Iron Age's iron) while its works reckoned the
 * Iron Age's want, 86 with 54 in: a third short, so the gold its trades asked for (3 of 14, four fifths short) and the
 * coal took the miners, one miner was on the iron, and the iron came in at seven a day: four weeks to the next age. Now
 * an ore the age itself asks for counts what the age still wants of it, and goes first in the share: the first miner to
 * the level richest in it, the rest a mix as before.
 */
public final class AgeOres {

    private AgeOres() {}

    private static final Map<UUID, Set<MineDemand.Ore>> AGE = new ConcurrentHashMap<>();

    /** How much more of this the age asks for (Villages.needs), or nought. */
    static int missing(ServerLevel level, UUID village, Villages.Task task) {
        for (Villages.Need n : Villages.needs(level, village)) if (n.task() == task) return Math.max(0, n.amount());
        return 0;
    }

    static void put(UUID village, Set<MineDemand.Ore> ores) {
        AGE.put(village, ores.isEmpty() ? EnumSet.noneOf(MineDemand.Ore.class) : EnumSet.copyOf(ores));
    }

    /** The ores this town's age is waiting on (as the works last reckoned). */
    public static Set<MineDemand.Ore> of(UUID village) {
        Set<MineDemand.Ore> s = AGE.get(village);
        return s == null ? Set.of() : s;
    }

    public static void forgetForTests(UUID village) {
        AGE.remove(village);
    }
}
