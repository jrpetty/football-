package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The names folk go by (entity/Names): hundreds of them, none twice, the first forty-eight where they
 * always were (a rename order is an index into the list), and a town's folk named at random from the
 * whole of it, so that no two towns are Bramble, Fen, Holt and Marrow over again.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class NamesGameTests {

    private static final String EMPTY = "empty";

    private static final List<String> FIRST = List.of(
        "Bramble", "Fen", "Holt", "Marrow", "Quill", "Rook",
        "Tansy", "Wick", "Bryn", "Cobb", "Dell", "Ember",
        "Flint", "Hazel", "Juniper", "Kestrel", "Larkin", "Mabel",
        "Nettle", "Orin", "Pike", "Reed", "Sorrel", "Thatch",
        "Vesper", "Willa", "Yarrow", "Ash", "Birch", "Corvin",
        "Dane", "Elm", "Ferris", "Gale", "Hollis", "Iris",
        "Kip", "Linden", "Moss", "Nell", "Otis", "Perrin",
        "Rowan", "Sage", "Teal", "Vale", "Wren", "Alder");

    /** The pool: big, every name once, one plain word, the old names where they were. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "nm01_pool")
    public static void nm01_pool(GameTestHelper helper) {
        List<String> pool = Names.POOL;
        Kit.log("nm01 " + pool.size() + " names; the last few: " + pool.subList(pool.size() - 6, pool.size()));
        helper.assertTrue(pool.size() >= FIRST.size() + 400, "at least 400 names more than the first 48: " + pool.size());
        helper.assertTrue(pool.subList(0, FIRST.size()).equals(FIRST), "the first 48 names are where they were (a rename is an index)");
        Set<String> seen = new HashSet<>();
        for (String n : pool) {
            helper.assertTrue(seen.add(n.toLowerCase()), "a name is in the pool twice: " + n);
            helper.assertTrue(n.matches("[A-Z][a-z]+") && n.length() <= 10, "one short word with a capital: " + n);
        }
        helper.succeed();
    }

    /** Forty folk in one town, forty names; two towns founded the same way begin with different folk. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "nm02_towns")
    public static void nm02_towns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        List<List<String>> towns = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            int x = 500000 + t * 4000, z = 60000;
            Kit.hold(level, x, z, 16);
            Kit.prepare(level, x, z, 16);
            BlockPos at = Kit.surface(level, x, z);
            List<String> names = new ArrayList<>();
            Set<String> distinct = new HashSet<>();
            for (int i = 0; i < 40; i++) {
                VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F, 1000);
                helper.assertTrue(f != null, "a folk raised");
                names.add(f.displayNameCap());
                distinct.add(f.displayNameCap().toLowerCase());
            }
            Kit.log("nm02 town " + t + ": " + names);
            helper.assertTrue(distinct.size() == 40, "forty folk, forty names: " + distinct.size());
            towns.add(names);
        }
        helper.assertTrue(!towns.get(0).subList(0, 6).equals(towns.get(1).subList(0, 6)), "two towns do not begin with the same six names");
        helper.succeed();
    }
}
