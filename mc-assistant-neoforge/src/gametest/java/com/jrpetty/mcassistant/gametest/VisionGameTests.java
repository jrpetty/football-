package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.TimeLapse;
import com.jrpetty.mcassistant.TimeSpeed;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.client.PlateText;
import com.jrpetty.mcassistant.entity.Favourites;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.entity.Happenings;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.net.CameraAskPayload;
import com.jrpetty.mcassistant.net.CameraNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * [vision27] The camera's server half: "show me something happening" finds what the folk are really doing, the busiest
 * first, and another the next time it is asked; a quiet town says so; a time-lapse is only for an operator (or the
 * owner of a single-player world), is held to its limits and puts the pace back as it was; the camera screen's info
 * names the town's heart and the folk to follow, a favourite first; and the tidy name tag keeps the name, a stuck folk's
 * warning and a hurt one's health, and drops the rest.
 *
 * <p>Each on its own ground, x 6000000 to 6100000 on z 60000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class VisionGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 60000;

    /** Ground at x, cleared, its folk raised at the given offsets from its middle (east, south), all held still. */
    private static List<VillageFolkEntity> ground(GameTestHelper helper, int x, int[][] at) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Happenings.resetForTests();
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int[] o : at) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.east(o[0]).south(o[1]), 0.0F);
            helper.assertTrue(f != null, "folk raised at " + o[0] + "," + o[1]);
            f.setNoAi(true);
            folk.add(f);
        }
        return folk;
    }

    private static BlockPos heart(GameTestHelper helper, int x) {
        return Kit.surface(helper.getLevel(), x, Z);
    }

    // ============================================================ vi01: the busiest thing going on, then another

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "vi01_happening")
    public static void vi01_happening(GameTestHelper helper) {
        int x = 6_000_000;
        // Three builders together to the east, two woodcutters to the west, one talker to the south.
        List<VillageFolkEntity> f = ground(helper, x, new int[][] {{20, 0}, {21, 2}, {19, -2}, {-22, 0}, {-23, 3}, {0, 24}});
        BlockPos heart = heart(helper, x);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            for (int i = 0; i < 3; i++) FolkAction.play(f.get(i), Act.BUILD, 4000);
            for (int i = 3; i < 5; i++) FolkAction.play(f.get(i), Act.CHOP, 4000);
            FolkAction.play(f.get(5), Act.TALK, 4000);
            for (int i = 0; i < 6; i++) helper.assertTrue(FolkAction.now(f.get(i)) != Act.NONE, "folk " + i + " at its act: " + FolkAction.now(f.get(i)));
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            List<Happenings.Happening> all = Happenings.around(level, heart, Happenings.RANGE, null);
            helper.assertTrue(all.size() == 3, "three things going on: " + all);
            Happenings.Happening first = Happenings.pick(level, heart, p.getUUID());
            helper.assertTrue(first != null && first.kind().equals("build") && first.folk() == 3, "the builders first: " + first);
            helper.assertTrue(first.at().distanceTo(f.get(0).position()) < 4.0, "among the builders: " + first.at());
            helper.assertTrue(first.label().startsWith("Builders"), "said as builders: " + first.label());
            boolean focusIsBuilder = false;
            for (int i = 0; i < 3; i++) if (f.get(i).getId() == first.focus()) focusIsBuilder = true;
            helper.assertTrue(focusIsBuilder, "a builder kept in the picture");
            Happenings.Happening second = Happenings.pick(level, heart, p.getUUID());
            helper.assertTrue(second != null && second.kind().equals("wood"), "asked again: the woodcutters, not the builders again: " + second);
            // Through the wire, as the camera screen's button asks.
            p.setPos(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
            CompoundTag t = CameraNetwork.answer(p, new CameraAskPayload("happening", 0, 0));
            helper.assertTrue(t != null && t.getBoolean("ok") && t.getInt("focus") > 0 && !t.getString("label").isEmpty(), "the wire's answer: " + t);
            Kit.log("[VT] vi01 " + first.label() + " then " + second.label() + " then " + t.getString("label"));
            p.discard();
            helper.succeed();
        });
    }

    // ============================================================ vi02: a quiet town says so

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "vi02_quiet")
    public static void vi02_quiet(GameTestHelper helper) {
        int x = 6_002_000;
        List<VillageFolkEntity> f = ground(helper, x, new int[][] {{3, 0}, {-3, 2}});
        BlockPos heart = heart(helper, x);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            for (VillageFolkEntity v : f) FolkAction.play(v, Act.NONE, 4000);   // stood about
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            helper.assertTrue(Happenings.pick(level, heart, p.getUUID()) == null, "nothing to show where nothing happens");
            p.setPos(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
            CompoundTag t = CameraNetwork.answer(p, new CameraAskPayload("happening", 0, 0));
            helper.assertTrue(t != null && !t.getBoolean("ok") && !t.getString("msg").isEmpty(), "the screen told so: " + t);
            Kit.log("[VT] vi02 " + t.getString("msg"));
            p.discard();
            helper.succeed();
        });
    }

    // ============================================================ vi03: a time-lapse, only for those who may

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "vi03_lapse")
    public static void vi03_lapse(GameTestHelper helper) {
        int x = 6_004_000;
        ground(helper, x, new int[][] {});
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            var server = level.getServer();
            int pace = TimeSpeed.factor(server);
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            try {
                helper.assertTrue(!TimeSpeed.mayChange(p), "a mock player is no operator");
                String no = TimeLapse.start(p, 16, 20);
                helper.assertTrue(no != null && !TimeLapse.running() && TimeSpeed.factor(server) == pace, "refused to a player who may not: " + no);
                CompoundTag t = CameraNetwork.answer(p, new CameraAskPayload("timelapse", 16, 20));
                helper.assertTrue(t != null && !t.getBoolean("ok") && t.getString("msg").contains("operator"), "the wire refuses too: " + t);
                CompoundTag info = CameraNetwork.answer(p, new CameraAskPayload("info", 0, 0));
                helper.assertTrue(info != null && !info.getBoolean("mayLapse"), "the screen told it may not");
                // An operator of the top level (the test server's own "op" gives level 0).
                server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(p.getGameProfile(), 4, false));
                helper.assertTrue(TimeSpeed.mayChange(p), "an operator may");
                // Started and stopped in the one tick: the other tests share this server's clock.
                helper.assertTrue(TimeLapse.start(p, 16, 20) == null, "started for an operator");
                boolean ran = TimeLapse.running() && TimeSpeed.factor(server) == 16 && TimeLapse.secondsLeft() <= 20 && TimeLapse.beforeForTests() == pace;
                TimeLapse.stop(server);
                helper.assertTrue(ran, "sixteen times faster, for twenty seconds at most, the old pace kept");
                helper.assertTrue(!TimeLapse.running() && TimeSpeed.factor(server) == pace, "the pace put back: " + TimeSpeed.factor(server));
                TimeLapse.start(p, 999, 999);
                boolean held = TimeSpeed.factor(server) == TimeLapse.MOST_FACTOR && TimeLapse.secondsLeft() <= TimeLapse.MOST_SECONDS;
                TimeLapse.stop(server);
                helper.assertTrue(held, "held to its limits (" + TimeLapse.MOST_FACTOR + "x, " + TimeLapse.MOST_SECONDS + " s)");
                CompoundTag ok = CameraNetwork.answer(p, new CameraAskPayload("timelapse", 8, 10));
                boolean wire = ok != null && ok.getBoolean("ok") && TimeSpeed.factor(server) == 8;
                TimeLapse.stop(server);
                helper.assertTrue(wire, "the wire starts one for an operator: " + ok);
                helper.assertTrue(TimeSpeed.factor(server) == pace, "and the pace back after");
                Kit.log("[VT] vi03 refused, then 16x, held to " + TimeLapse.MOST_FACTOR + "x, pace back to " + pace);
            } finally {
                if (TimeLapse.running()) TimeLapse.stop(server);
                server.getPlayerList().getOps().remove(p.getGameProfile());
                p.discard();
            }
            helper.succeed();
        });
    }

    // ============================================================ vi04: the tidy name tag

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "vi04_plates")
    public static void vi04_plates(GameTestHelper helper) {
        helper.runAtTickTime(1, () -> {
            Component whole = tag("⚒", "Ann Smith", "20/20❤");
            helper.assertTrue(PlateText.tidy(whole).getString().equals("Ann Smith"), "at work and well: the name alone: '" + PlateText.tidy(whole).getString() + "'");
            Component hurt = tag("⚒", "Ann Smith", "12/20❤");
            helper.assertTrue(PlateText.tidy(hurt).getString().equals("Ann Smith 12/20❤"), "hurt: its health shown: '" + PlateText.tidy(hurt).getString() + "'");
            Component stuck = tag("⚠", "Ann Smith", "20/20❤");
            helper.assertTrue(PlateText.tidy(stuck).getString().equals("⚠ Ann Smith"), "stuck: its warning shown: '" + PlateText.tidy(stuck).getString() + "'");
            Component ruler = tag("", "King Ann of Oakvale", "20/20❤");
            helper.assertTrue(PlateText.nameOnly(ruler).getString().equals("King Ann of Oakvale"), "a ruler keeps its title");
            Component plain = Component.literal("Ann Smith");
            helper.assertTrue(PlateText.tidy(plain).getString().equals("Ann Smith"), "a plain name as it is");
            helper.assertTrue(PlateText.nameOnly(Component.literal("⚒ ✦2 Bo Reed 20/20❤")).getString().equals("Bo Reed"), "the bare words stripped");
            Kit.log("[VT] vi04 " + PlateText.tidy(hurt).getString() + " / " + PlateText.tidy(stuck).getString());
            helper.succeed();
        });
    }

    /** A tag built as the server builds it (AssistantEntity's live name tag). */
    private static Component tag(String glyph, String name, String hearts) {
        MutableComponent t = Component.literal("");
        if (!glyph.isEmpty()) t.append(Component.literal(glyph + " ").withStyle(glyph.equals("⚠") ? ChatFormatting.RED : ChatFormatting.AQUA));
        t.append(Component.literal("✦2 ").withStyle(ChatFormatting.GOLD));
        t.append(Component.literal(name)).append(Component.literal(" "));
        t.append(Component.literal("★ Baker ").withStyle(ChatFormatting.AQUA));
        t.append(Component.literal(hearts).withStyle(ChatFormatting.GREEN));
        return t;
    }

    // ============================================================ vi05: the screen's info: the heart, who to follow

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "vi05_info")
    public static void vi05_info(GameTestHelper helper) {
        int x = 6_006_000;
        List<VillageFolkEntity> f = ground(helper, x, new int[][] {{2, 0}, {4, 0}, {30, 0}});
        BlockPos heart = heart(helper, x);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Favourites.resetForTests();
            Villages.Village v = Villages.found(level, heart);
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.setPos(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
            Favourites.add(p, f.get(2));                                    // the far one, a favourite
            CompoundTag t = CameraNetwork.answer(p, new CameraAskPayload("info", 0, 0));
            helper.assertTrue(t != null && t.contains("heart"), "the heart named: " + t);
            int[] h = t.getIntArray("heart");
            helper.assertTrue(h[0] == v.centre().getX() && h[2] == v.centre().getZ(), "the town's own heart");
            ListTag who = t.getList("folk", Tag.TAG_COMPOUND);
            helper.assertTrue(who.size() == 3, "all three to follow: " + who);
            helper.assertTrue(who.getCompound(0).getInt("id") == f.get(2).getId() && who.getCompound(0).getBoolean("fav"), "the favourite first, starred");
            helper.assertTrue(who.getCompound(1).getInt("id") == f.get(0).getId(), "then the nearest");
            Kit.log("[VT] vi05 " + t.getString("town") + " heart " + h[0] + "," + h[2] + " folk " + who.size());
            p.discard();
            helper.succeed();
        });
    }
}
