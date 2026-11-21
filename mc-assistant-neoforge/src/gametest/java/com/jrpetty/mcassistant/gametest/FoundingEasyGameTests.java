package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.FoundingEasy;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.SiteFinder;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.net.FoundEasyPayload;
import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * [found23] Starting a town made easy (entity/FoundingEasy): the Founding Kit given once on first joining; the U page
 * offering a village when you are in none, and the founding screen opening from it with commands banned; the spot finder
 * putting a flat meadow by water above a cliff and the sea, and keeping clear of towns; the sizes founding the numbers
 * they say, with the name chosen; and the server's rules (operators only, towns per player, the kit needed).
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FoundingEasyGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    private static ServerPlayer player(GameTestHelper helper, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        p.getAbilities().instabuild = false;
        return p;
    }

    private static int kits(ServerPlayer p) {
        return p.getInventory().countItem(McAssistantMod.FOUNDING_KIT.get());
    }

    /** What the server sends to players, caught. */
    private static List<CompoundTag> catchSent(int kind) {
        List<CompoundTag> out = new ArrayList<>();
        FoundingEasy.TEST_SINK = (k, t) -> { if (k == kind) out.add(t.copy()); };
        return out;
    }

    private static void ground(ServerLevel level, int cx, int cz, int r) {
        Kit.hold(level, cx, cz, r);
        Kit.prepare(level, cx, cz, r);
    }

    private static void clean() {
        FoundingEasy.TEST_SINK = null;
        FoundingEasy.rulesForTests(null, null, null);
        PlayerLife.BAN_COMMANDS_FOR_TESTS = false;
    }

    // ============================================================ the kit

    /** A new player is given one Founding Kit, once: joining again gives none. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "fe01_first_join_kit")
    public static void fe01_first_join_kit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 5200000;
        ground(level, x, Z, 16);
        ServerPlayer p = player(helper, Kit.surface(level, x, Z));
        // a stand-in player is logged in as it is made (and given its kit then): made new again
        p.getPersistentData().remove(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        boolean first = FoundingEasy.welcomeNewPlayer(p);
        boolean second = FoundingEasy.welcomeNewPlayer(p);
        Kit.log("fe01 first " + first + " second " + second + " kits " + kits(p));
        helper.assertTrue(first && !second, "given the first time only");
        helper.assertTrue(kits(p) == 1, "one kit in the pack: " + kits(p));
        helper.assertTrue(FoundingEasy.wantsHint(p), "out in the wild with no town: told they can start one");
        helper.succeed();
    }

    // ============================================================ the U page, with commands banned

    /**
     * Out of any town, the U page's first row is "Start a village"; its button opens the founding screen (the spots
     * offered, "here" first) with commands banned; confirming founds a hamlet of eight at the spot, uses up the kit,
     * and sends the welcome card. A folk asked "I'd like to start a village of my own" opens the screen too.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fe02_u_page")
    public static void fe02_u_page(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int x = 5210000;
        ground(level, x, Z, 180);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, Kit.surface(level, x, Z));
            p.getInventory().add(new ItemStack(McAssistantMod.FOUNDING_KIT.get()));
            List<CompoundTag> opened = catchSent(FoundEasyPayload.OPEN);
            PlayerLife.BAN_COMMANDS_FOR_TESTS = true;
            try {
                PlayerLife.open(p, "life", null);
                String page = PlayerLife.pageForTests(p).toString();
                Kit.log("fe02 page has Start a village: " + page.contains("Start a village"));
                helper.assertTrue(page.contains("Start a village") && page.contains("\"found\""), "the U page offers a village of your own");
                PlayerLife.act(p, "life", "found", new CompoundTag());
                helper.assertTrue(opened.size() == 1, "the founding screen opens from it");
                CompoundTag t = opened.get(0);
                var picks = t.getList("picks", Tag.TAG_COMPOUND);
                Kit.log("fe02 screen: no='" + t.getString("no") + "' need='" + t.getString("need") + "' picks " + picks);
                helper.assertTrue(!picks.isEmpty() && picks.getCompound(0).getBoolean("here"), "here is offered first");
                helper.assertTrue(t.getString("no").isEmpty() && t.getString("need").isEmpty(), "nothing in the way");
                helper.assertTrue(t.getCompound("map").getByteArray("kinds").length > 1000, "with a map of the ground");
                List<CompoundTag> welcome = catchSent(FoundEasyPayload.WELCOME);
                CompoundTag a = new CompoundTag();
                a.putInt("x", x);
                a.putInt("z", Z);
                a.putInt("count", 8);
                a.putString("name", "Easyford");
                FoundingEasy.act(p, "found", a);
                Kit.log("fe02 founding: near " + Founding.near(level, new BlockPos(x, 0, Z), 16) + ", kits " + kits(p) + ", welcome " + welcome);
                helper.assertTrue(Founding.near(level, Kit.surface(level, x, Z), 16), "the founding is under way at the spot");
                helper.assertTrue(kits(p) == 0, "the kit is used up");
                helper.assertTrue(welcome.size() == 1 && welcome.get(0).getString("name").equals("Easyford"), "the welcome card comes");
                // the talk menu's phrase
                List<CompoundTag> again = catchSent(FoundEasyPayload.OPEN);
                String said = PlayerLife.heard(null, p, "I'd like to start a village of my own");
                helper.assertTrue(said != null && again.size() == 1, "a folk asked opens the screen: " + said);
            } finally {
                clean();
            }
            helper.succeed();
        });
    }

    // ============================================================ the spot finder

    /**
     * On made ground: a flat meadow with a pool and a copse beside it, a cliff (a step of eighteen across the spot), the
     * sea (water over the whole square), and a town. The meadow scores above the cliff, the sea may not be founded on,
     * nor ground beside the town; and of the spots the finder offers from the middle, none is in the sea or by the town.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fe03_site_finder")
    public static void fe03_site_finder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int x = 5220000;
        ground(level, x, Z, 260);
        // the meadow, north: a pool forty out and a copse
        BlockPos meadow = Kit.surface(level, x, Z - 96);
        Kit.pond(level, x + 36, Z - 96, 5);
        for (int i = 0; i < 6; i++) Kit.tree(level, x - 34 + i * 3, Z - 100 + (i % 2) * 4);
        // the cliff, east: the half of the square past the spot raised eighteen
        BlockPos cliff = Kit.surface(level, x + 96, Z);
        for (int dx = 0; dx <= 40; dx++) {
            for (int dz = -40; dz <= 40; dz++) {
                BlockPos g = Kit.surface(level, x + 96 + dx, Z + dz);
                for (int i = 0; i < 18; i++) level.setBlock(g.above(i), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        // the sea, west
        BlockPos sea = Kit.surface(level, x - 96, Z);
        for (int dx = -44; dx <= 44; dx++) {
            for (int dz = -44; dz <= 44; dz++) {
                BlockPos g = Kit.surface(level, x - 96 + dx, Z + dz).below();
                level.setBlock(g, Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(g.below(), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        // a town, south
        BlockPos town = Kit.surface(level, x, Z + 150);
        VillageFolkSpawnerBlock.raise(level, town, 0F);
        helper.runAtTickTime(5, () -> {
            SiteFinder.Pick m = SiteFinder.find(level, meadow, 0).picks().get(0);
            SiteFinder.Pick c = SiteFinder.find(level, cliff, 0).picks().get(0);
            SiteFinder.Pick s = SiteFinder.find(level, sea, 0).picks().get(0);
            SiteFinder.Pick t = SiteFinder.find(level, town.north(40), 0).picks().get(0);
            Kit.log("fe03 meadow " + m + "\nfe03 cliff " + c + "\nfe03 sea " + s + "\nfe03 by the town " + t);
            helper.assertTrue(m.no().isEmpty() && m.score() > c.score() + 15, "the meadow above the cliff: " + m.score() + " vs " + c.score());
            helper.assertTrue(m.words().contains(" by a ") && m.words().contains("oak wood"), "the meadow's words say water and wood: " + m.words());
            helper.assertTrue(!s.no().isEmpty(), "not in the sea: " + s.no());
            helper.assertTrue(!t.no().isEmpty(), "not by the town: " + t.no());
            SiteFinder.Result all = SiteFinder.find(level, Kit.surface(level, x, Z), 160);
            Kit.log("fe03 offered " + all.picks());
            for (SiteFinder.Pick p : all.picks()) {
                if (p.here()) continue;
                helper.assertTrue(p.no().isEmpty(), "only spots that may be founded on are offered");
                helper.assertTrue(p.at().distSqr(sea) > 50 * 50, "none in the sea: " + p);
                helper.assertTrue(Math.sqrt(p.at().distSqr(new BlockPos(town.getX(), p.at().getY(), town.getZ()))) > Villages.VILLAGE_RANGE * 2, "none by the town: " + p);
            }
            helper.assertTrue(all.picks().size() >= 2, "better spots than the middle are offered");
            helper.succeed();
        });
    }

    // ============================================================ the sizes

    /** A hamlet of eight, founded from the screen, has eight folk when it is done, the name chosen, and its founder. */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "fe04_hamlet")
    public static void fe04_hamlet(GameTestHelper helper) {
        founds(helper, 5230000, 8, "Littlemere");
    }

    /** A village of twenty-five, the same, and "Take me there" carries the founder over to it. */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "fe05_village")
    public static void fe05_village(GameTestHelper helper) {
        founds(helper, 5240000, 25, "Fivewell");
    }

    private static void founds(GameTestHelper helper, int x, int folk, String name) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        int spot = x + 90;
        ground(level, spot, Z, FoundingPlan.coreRadius(folk) + FoundingPlan.BAND_MAX + 24);
        ground(level, x, Z, 24);
        BlockPos heart = Kit.surface(level, spot, Z);
        final ServerPlayer[] who = new ServerPlayer[1];
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, Kit.surface(level, x, Z));
            who[0] = p;
            FoundingEasy.rulesForTests(null, null, false);
            Founding.Outcome o = FoundingEasy.found(p, spot, Z, folk, name, "", true);
            FoundingEasy.rulesForTests(null, null, null);
            double moved = Math.sqrt(p.distanceToSqr(heart.getX(), p.getY(), heart.getZ()));
            Kit.log("fe " + folk + " founding: " + o.message() + "; the founder now " + (int) moved + " from it");
            helper.assertTrue(o.ok(), "founded: " + o.message());
            helper.assertTrue(moved <= FoundingPlan.coreRadius(folk) + 14, "carried over to it: " + moved);
        });
        final boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0] || helper.getTick() < 10) return;
            if (Founding.near(level, heart, 8)) {
                if (helper.getTick() % 200 == 0) Kit.log("fe " + folk + " @" + helper.getTick() + " " + Founding.status(level.getServer()));
                return;
            }
            done[0] = true;
            Villages.Village v = Villages.nearest(level, heart, 64);
            int n = v == null ? 0 : Villages.headcount(v.id());
            String named = v == null ? "" : Villages.name(v.id());
            Kit.log("fe " + folk + " done: " + named + " of " + n + ", founder " + (v == null ? null : com.jrpetty.mcassistant.entity.Advancements.founder(v.id())));
            helper.assertTrue(v != null && n == folk, "founded with " + folk + ": " + n);
            helper.assertTrue(named.equals(name), "with the name chosen: " + named);
            helper.assertTrue(who[0].getUUID().equals(com.jrpetty.mcassistant.entity.Advancements.founder(v.id())), "its founder written down");
            helper.assertTrue(SiteFinder.townsOf(level, who[0].getUUID()) == 1, "one town of theirs");
            // the welcome card's "Find the elder": whoever leads them glows
            CompoundTag a = new CompoundTag();
            a.putIntArray("heart", new int[]{ heart.getX(), heart.getY(), heart.getZ() });
            FoundingEasy.act(who[0], "elder", a);
            boolean glows = false;
            for (var f : Villages.folkOf(v.id())) if (f.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING)) glows = true;
            helper.assertTrue(glows, "Find the elder makes one of them glow");
            helper.succeed();
        });
    }

    // ============================================================ a spawner's board

    /**
     * A spawner set down puts the board up; right-clicking it opens the same screen, on its spot (mode "board", no kit
     * asked for, here not counted as in its own way); confirming there with a name founds it, and the welcome card comes.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fe07_board")
    public static void fe07_board(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int x = 5260000;
        ground(level, x, Z, 120);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, Kit.surface(level, x, Z - 4));
            try {
                BlockPos at = Kit.surface(level, x, Z);
                level.setBlock(at, McAssistantMod.FOLK_SPAWNER.get().defaultBlockState(), 3);
                VillageFolkSpawnerBlock.placed(level, at, level.getBlockState(at), p, 0F);
                BlockPos board = Founding.boardNear(level, at, 64);
                helper.assertTrue(board != null && level.getBlockEntity(board) instanceof com.jrpetty.mcassistant.block.VillageBoardBlockEntity,
                    "the spawner puts the board up");
                var be = (com.jrpetty.mcassistant.block.VillageBoardBlockEntity) level.getBlockEntity(board);
                List<CompoundTag> opened = catchSent(FoundEasyPayload.OPEN);
                be.showTo(p);
                helper.assertTrue(opened.size() == 1, "right-clicking the board opens the founding screen");
                CompoundTag t = opened.get(0);
                CompoundTag here = t.getList("picks", Tag.TAG_COMPOUND).getCompound(0);
                Kit.log("fe07 board screen: mode " + t.getString("mode") + ", need '" + t.getString("need") + "', here " + here);
                helper.assertTrue(t.getString("mode").equals("board") && t.getString("need").isEmpty(), "on the board's spot, no kit asked for");
                helper.assertTrue(here.getString("no").isEmpty(), "its own founding is not in its way");
                p.moveTo(board.getX() + 0.5, board.getY(), board.getZ() - 3.5);
                List<CompoundTag> welcome = catchSent(FoundEasyPayload.WELCOME);
                CompoundTag a = new CompoundTag();
                a.putLong("board", board.asLong());
                a.putInt("count", 8);
                a.putString("name", "Boardwick");
                FoundingEasy.act(p, "board", a);
                Kit.log("fe07 confirmed: under way " + (be.founding() == Founding.UNDER_WAY) + ", welcome " + welcome);
                helper.assertTrue(be.founding() == Founding.UNDER_WAY, "confirmed at the board");
                helper.assertTrue(welcome.size() == 1 && welcome.get(0).getString("name").equals("Boardwick"), "the welcome card comes");
            } finally {
                clean();
            }
            helper.succeed();
        });
    }

    // ============================================================ the server's rules

    /**
     * Operators only: a player is refused at the screen, at the spot and with a spawner set down (given back). One town
     * each: a second is refused while the first is being founded. The kit needed: refused without one.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fe06_rules")
    public static void fe06_rules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int x = 5250000;
        ground(level, x, Z, 200);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, Kit.surface(level, x, Z));
            try {
                FoundingEasy.rulesForTests("operators", 0, false);
                List<CompoundTag> opened = catchSent(FoundEasyPayload.OPEN);
                FoundingEasy.open(p, "test");
                String no = opened.isEmpty() ? "" : opened.get(0).getString("no");
                Founding.Outcome o = FoundingEasy.found(p, x, Z, 8, "", "", false);
                BlockPos at = Kit.surface(level, x + 4, Z);
                level.setBlock(at, McAssistantMod.FOLK_SPAWNER.get().defaultBlockState(), 3);
                VillageFolkSpawnerBlock.placed(level, at, level.getBlockState(at), p, 0F);
                int back = p.getInventory().countItem(McAssistantMod.FOLK_SPAWNER_ITEM.get());
                Kit.log("fe06 operators only: screen '" + no + "', found " + o.message() + ", spawner back " + back);
                helper.assertTrue(no.contains("operators"), "the screen says why");
                helper.assertTrue(!o.ok() && !Founding.near(level, at, 200), "nothing is founded");
                helper.assertTrue(back == 1, "the spawner is given back");

                FoundingEasy.rulesForTests("everyone", 1, false);
                Founding.Outcome first = FoundingEasy.found(p, x, Z, 8, "", "", false);
                Founding.Outcome second = FoundingEasy.found(p, x + 220, Z, 8, "", "", false);
                Kit.log("fe06 one each: " + first.message() + " / " + second.message());
                helper.assertTrue(first.ok() && !second.ok() && second.message().contains("most"), "one town each");
                helper.assertTrue(FoundingEasy.hasOwn(p) && !FoundingEasy.wantsHint(p), "with a town of their own, no more hints");

                FoundingEasy.rulesForTests("everyone", 0, true);
                p.getInventory().clearContent();                               // the spawner given back would do for a kit
                Founding.Outcome bare = FoundingEasy.found(p, x - 150, Z, 8, "", "", false);
                Kit.log("fe06 no kit: " + bare.message());
                helper.assertTrue(!bare.ok() && bare.message().contains("Founding Kit"), "without a kit, refused, and told what it takes");
            } finally {
                clean();
            }
            helper.succeed();
        });
    }
}
