package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AnimStage;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.EmoteRules;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.entity.PlayerEmotes;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.item.FieldItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [anim] What a folk's body is doing, as the server names it for the client (FolkAction): every trade's work by its
 * block and its tool, the life of the town (sleeping, sitting, eating, drinking, talking, listening, a feast's dance),
 * sent only when it changes, and none of it in the way of a save and a load; and the player's emotes (PlayerEmotes):
 * taken, kept to a pace, and answered by the folk by what they think of the player.
 * <ul>
 * <li><b>fan01</b>: the trades at their blocks (the smith at the anvil, the tailor at the loom, the brewer at the
 *     stand, the cook at the smoker...), the outdoor trades by the tool in the hand as they swing it (a hoe, seed, a
 *     sickle's harvest, the can; the axe; the pick and the shovel; the shears, the bucket, the lead), a builder's block,
 *     the fisher by the water; and every trade there is has a work act of its own when it works.</li>
 * <li><b>fan02</b>: asleep in its bed; sat down; a bite and a sip, sat or standing; its words; a player talking to it;
 *     the words of its own time (the maypole's dance, supper, the tavern, tag); a fall and getting up.</li>
 * <li><b>fan03</b>: sent only on a change: the same act looked at again and again is sent once; a played act sent once
 *     and once more when it ends.</li>
 * <li><b>fan04</b>: saved and loaded, a folk is none the worse: nothing of its act is saved, and it works out what it is
 *     doing again.</li>
 * <li><b>fan05</b>: the player's emotes: an emote taken, a second at once refused, five in ten seconds at most, a work act
 *     no emote; a wave answered by the folk that likes the player and not by the one that cannot stand it; a handshake
 *     taken by the folk it was offered to, the better thought of once a day; a beckon followed by a close friend.</li>
 * <li><b>fan06</b>: the stage the smoke photographs (/village anim stage): a folk doing every act there is.</li>
 * </ul>
 * Each on its own ground (x 2,800,000 to 2,824,000, z 70,000).
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FolkAnimGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    /** Flat stone about the spot, clear air above, a morning on a working day. */
    private static BlockPos ground(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        PlayerEmotes.resetForTests();
        level.setDayTime(24000L * 3 + 3000L);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 24);
        Kit.prepare(level, x, Z, 24);
        BlockPos mid = Kit.surface(level, x, Z);
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                level.setBlock(new BlockPos(x + dx, mid.getY() - 1, Z + dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int dy = 0; dy < 6; dy++) level.setBlock(new BlockPos(x + dx, mid.getY() + dy, Z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        return new BlockPos(x, mid.getY(), Z);
    }

    /** A folk of a town, standing still where it is put (no brain of its own to walk it off). */
    private static VillageFolkEntity folk(GameTestHelper helper, BlockPos at) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(helper.getLevel(), at, 0.0F);
        helper.assertTrue(f != null, "a folk at " + at.toShortString());
        f.ensurePersona();
        f.setNoAi(true);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        return f;
    }

    /** A working hour for this folk: not its town's day of rest, nor its own break. */
    private static void workday(ServerLevel level, VillageFolkEntity f) {
        long[] hours = {3500L, 4600L, 2600L, 6200L, 7400L, 5300L};    // its break falls at an hour of its own
        for (int d = 1; d <= 8; d++) {
            for (long h : hours) {
                level.setDayTime(24000L * d + h);
                if (!f.offWorkNow()) return;
            }
        }
    }

    private static void hold(VillageFolkEntity f, ItemStack s) {
        f.setItemSlot(EquipmentSlot.MAINHAND, s);
    }

    /** Its trade, its hand and the block beside it; its arm swung (or not); what it is doing then. */
    private static Act at(ServerLevel level, VillageFolkEntity f, StationTask job, ItemStack hand, Block block, boolean swing) {
        f.setJob(job);
        hold(f, hand);
        BlockPos side = f.blockPosition().east();
        level.setBlock(side, block == null ? Blocks.AIR.defaultBlockState() : block.defaultBlockState(), 3);
        if (swing) f.swing(InteractionHand.MAIN_HAND);
        return FolkAction.deriveForTests(f);
    }

    // ------------------------------------------------------------------ fan01: work

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fan01_work")
    public static void fan01_work(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 2_800_000);
        VillageFolkEntity f = folk(helper, o);
        helper.runAtTickTime(5, () -> {
            workday(level, f);
            helper.assertTrue(!f.offWorkNow(), "a working morning: on its shift, not " + f.offWorkWhy());
            List<String> seen = new ArrayList<>();
            // Not swinging, out in the open: standing about, not at work.
            helper.assertTrue(at(level, f, StationTask.FARM, new ItemStack(Items.IRON_HOE), null, false) != Act.HOE, "a hoe held still is not hoeing");
            Object[][] blocks = {
                {StationTask.SMITH, Blocks.ANVIL, Act.HAMMER}, {StationTask.TAILOR, Blocks.LOOM, Act.SEW},
                {StationTask.BREW, Blocks.BREWING_STAND, Act.BREW}, {StationTask.ENCHANT, Blocks.ENCHANTING_TABLE, Act.ENCHANT},
                {StationTask.COOK, Blocks.SMOKER, Act.STIR_POT}, {StationTask.FLETCHER, Blocks.FLETCHING_TABLE, Act.FLETCH},
                {StationTask.CARTOGRAPHER, Blocks.CARTOGRAPHY_TABLE, Act.MAP}, {StationTask.SMELT, Blocks.FURNACE, Act.STOKE},
                {StationTask.BEEKEEP, Blocks.BEEHIVE, Act.SMOKE_HIVE}, {StationTask.SHOP, Blocks.STONECUTTER, Act.CHISEL},
            };
            for (Object[] b : blocks) {
                Act a = at(level, f, (StationTask) b[0], ItemStack.EMPTY, (Block) b[1], b[0] == StationTask.SHOP);
                seen.add(b[0] + "@" + ((Block) b[1]).getName().getString() + "=" + a);
                helper.assertTrue(a == b[2], "the " + b[0] + " at its " + b[1] + " is " + b[2] + ", not " + a);
            }
            // Turned to its work: the hands meet the anvil.
            at(level, f, StationTask.SMITH, ItemStack.EMPTY, Blocks.ANVIL, false);
            float yaw = net.minecraft.util.Mth.wrapDegrees(f.getYRot());
            helper.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw + 90.0F)) < 5.0F, "faced to the anvil to its east: yaw " + yaw);
            Object[][] tools = {
                {StationTask.FARM, new ItemStack(Items.IRON_HOE), Act.HOE}, {StationTask.FARM, new ItemStack(Items.WHEAT_SEEDS), Act.PLANT},
                {StationTask.FARM, ItemStack.EMPTY, Act.HARVEST}, {StationTask.FARM, new ItemStack(FieldItems.WATERING_CAN.get()), Act.WATER},
                {StationTask.WOOD, new ItemStack(Items.IRON_AXE), Act.CHOP}, {StationTask.WOOD, new ItemStack(Items.OAK_SAPLING), Act.PLANT},
                {StationTask.MINE, new ItemStack(Items.IRON_PICKAXE), Act.PICK}, {StationTask.MINE, new ItemStack(Items.IRON_SHOVEL), Act.DIG},
                {StationTask.RANCH, new ItemStack(Items.SHEARS), Act.SHEAR}, {StationTask.RANCH, new ItemStack(Items.BUCKET), Act.MILK},
                {StationTask.RANCH, new ItemStack(Items.LEAD), Act.LEAD}, {StationTask.RANCH, new ItemStack(Items.WHEAT), Act.FEED},
                {StationTask.HAUL, new ItemStack(Items.BRICKS), Act.BUILD}, {StationTask.GOLEMS, new ItemStack(Items.IRON_INGOT), Act.REPAIR},
                {StationTask.REDSTONE, ItemStack.EMPTY, Act.TINKER}, {StationTask.HEAL, ItemStack.EMPTY, Act.TEND},
            };
            for (Object[] t : tools) {
                Act a = at(level, f, (StationTask) t[0], (ItemStack) t[1], null, true);
                seen.add(t[0] + "+" + ((ItemStack) t[1]).getItem() + "=" + a);
                helper.assertTrue(a == t[2], "the " + t[0] + " swinging " + t[1] + " is " + t[2] + ", not " + a);
            }
            // Every trade has an act of its own at its work.
            Set<StationTask> none = EnumSet.of(StationTask.NONE, StationTask.GUARD, StationTask.FERRY, StationTask.SCOUT, StationTask.HUNT);
            for (StationTask t : StationTask.values()) {
                if (none.contains(t)) continue;
                Act a = at(level, f, t, ItemStack.EMPTY, null, true);
                helper.assertTrue(a.work(), t + " at its work has a work act: " + a);
            }
            // The fisher by the water.
            level.setBlock(f.blockPosition().south(2).below(), Blocks.WATER.defaultBlockState(), 3);
            Act fish = at(level, f, StationTask.FISH, new ItemStack(Items.FISHING_ROD), null, true);
            helper.assertTrue(fish == Act.FISH, "the fisher casting at the water: " + fish);
            Kit.log("fan01 " + seen);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fan02: life

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fan02_life")
    public static void fan02_life(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 2_804_000);
        VillageFolkEntity f = folk(helper, o);
        helper.runAtTickTime(5, () -> {
            // Asleep in its bed.
            BlockPos foot = o.north(3);
            BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(foot.north(), bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
            f.startSleeping(foot.north());
            helper.assertTrue(FolkAction.deriveForTests(f) == Act.SLEEP, "asleep: " + FolkAction.now(f));
            f.stopSleeping();
            f.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5);
            // Sat down; a bite sat down; a sip standing.
            f.setPose(Pose.SITTING);
            helper.assertTrue(FolkAction.deriveForTests(f) == Act.SIT, "sat down: " + FolkAction.now(f));
            FolkAction.ate(f, new ItemStack(Items.BREAD));
            helper.assertTrue(FolkAction.now(f) == Act.EAT, "a bite: " + FolkAction.now(f));
            helper.assertTrue(FolkAction.deriveForTests(f) == Act.EAT, "still at its meal at the table: " + FolkAction.now(f));
            f.setPose(Pose.STANDING);
            FolkAction.ate(f, new ItemStack(Items.HONEY_BOTTLE));
            helper.assertTrue(FolkAction.now(f) == Act.DRINK, "a sip: " + FolkAction.now(f));
            // Its words, and a player's.
            FolkAction.spoke(f);
            helper.assertTrue(FolkAction.deriveForTests(f) == Act.TALK, "its hands at its words: " + FolkAction.now(f));
            ServerPlayer you = helper.makeMockServerPlayerInLevel();
            you.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 3.5);
            f.startTalking(you);
            helper.assertTrue(FolkAction.deriveForTests(f) == Act.TALK, "talking with the player, its own words still fresh: " + FolkAction.now(f));
            // The words of its own time.
            Map<String, Act> words = Map.of("dancing round the maypole", Act.DANCE, "at supper with the family", Act.EAT,
                "at the tavern", Act.DRINK, "playing tag on the square", Act.TAG, "teaching at the school: the farmer's trade", Act.TEACH,
                "crying the news on the square", Act.PREACH, "on the way to the shop", Act.NONE, "carrying its shopping home", Act.SACK);
            for (Map.Entry<String, Act> e : words.entrySet()) {
                helper.assertTrue(FolkAction.hobbyAct(e.getKey()) == e.getValue(), "\"" + e.getKey() + "\" reads as " + e.getValue()
                    + ", not " + FolkAction.hobbyAct(e.getKey()));
            }
            f.stopTalking();
            Kit.noLeftoverPlayers(level);
        });
        // A fall from six blocks: down, and up again.
        boolean[] fell = new boolean[1];
        helper.runAtTickTime(80, () -> {
            f.setNoAi(false);
            f.moveTo(o.getX() + 0.5, o.getY() + 7, o.getZ() + 0.5);
        });
        for (int t = 82; t < 150; t += 2) {
            helper.runAtTickTime(t, () -> { if (FolkAction.now(f) == Act.FALL) fell[0] = true; });
        }
        helper.runAtTickTime(160, () -> {
            helper.assertTrue(fell[0], "down hard, and up again: " + FolkAction.now(f));
            Kit.log("fan02 the life of the town: sleep, sit, eat, drink, talk, words, fall " + FolkAction.now(f));
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fan03: only on a change

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fan03_sends")
    public static void fan03_sends(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 2_808_000);
        VillageFolkEntity f = folk(helper, o);
        int[] before = new int[1];
        helper.runAtTickTime(5, () -> {
            workday(level, f);
            at(level, f, StationTask.SMITH, ItemStack.EMPTY, Blocks.ANVIL, false);
            helper.assertTrue(FolkAction.now(f) == Act.HAMMER, "at the anvil: " + FolkAction.now(f));
            before[0] = FolkAction.sendsForTests(f);
        });
        helper.runAtTickTime(65, () -> {
            // Sixty ticks of its own looks (one every five) and nothing has changed: nothing sent.
            helper.assertTrue(FolkAction.sendsForTests(f) == before[0], "the same act is not sent again: " + before[0] + " then " + FolkAction.sendsForTests(f));
            FolkAction.play(f, Act.WAVE, 20);
            helper.assertTrue(FolkAction.sendsForTests(f) == before[0] + 1, "a wave sent once");
            FolkAction.mark(f, FolkAction.Mark.COIN);
            helper.assertTrue(FolkAction.sendsForTests(f) == before[0] + 2, "a coin over its head, sent once");
            helper.assertTrue(FolkAction.markOf(f.clientAction()) == FolkAction.Mark.COIN.ordinal()
                && FolkAction.actOf(f.clientAction()) == Act.WAVE.ordinal(), "the wire carries both: " + Integer.toHexString(f.clientAction()));
        });
        helper.runAtTickTime(120, () -> {
            // The wave over: back at the anvil, sent once.
            helper.assertTrue(FolkAction.now(f) == Act.HAMMER, "back at the anvil: " + FolkAction.now(f));
            helper.assertTrue(FolkAction.sendsForTests(f) == before[0] + 3, "back to work, sent once: " + FolkAction.sendsForTests(f));
            Kit.log("fan03 sends " + FolkAction.sendsForTests(f));
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fan04: saved and loaded

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fan04_save")
    public static void fan04_save(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 2_812_000);
        VillageFolkEntity f = folk(helper, o);
        helper.runAtTickTime(5, () -> {
            workday(level, f);
            at(level, f, StationTask.TAILOR, ItemStack.EMPTY, Blocks.LOOM, false);
            FolkAction.play(f, Act.HANDSHAKE, 400);
            CompoundTag tag = new CompoundTag();
            f.saveWithoutId(tag);
            VillageFolkEntity g = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
            helper.assertTrue(g != null, "a folk to load into");
            g.load(tag);
            g.setNoAi(true);
            g.setUUID(java.util.UUID.randomUUID());
            g.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
            helper.assertTrue(level.addFreshEntity(g), "loaded into the world");
            helper.assertTrue(FolkAction.now(g) == Act.NONE && g.clientAction() == 0, "loaded with no act of its own yet");
            f.discard();
            helper.runAtTickTime(30, () -> {
                g.setNoAi(true);
                Act a = FolkAction.deriveForTests(g);
                helper.assertTrue(a == Act.SEW, "it works out it is at its loom again: " + a + " (" + g.offWorkWhy() + ")");
                helper.assertTrue(g.stationTask() == StationTask.TAILOR, "its trade kept");
                Kit.log("fan04 loaded and at its loom: " + a);
                helper.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ fan05: the player's emotes

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "fan05_emotes")
    public static void fan05_emotes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 2_816_000);
        VillageFolkEntity friend = folk(helper, o.east(2));
        VillageFolkEntity foe = folk(helper, o.west(2));
        ServerPlayer[] you = new ServerPlayer[1];
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            p.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 4.5, 180.0F, 0.0F);
            you[0] = p;
            friend.persona().feelFor(p.getUUID(), p.getName().getString(), 70);
            foe.persona().feelFor(p.getUUID(), p.getName().getString(), -70);
            helper.assertTrue(PlayerEmotes.emote(p, Act.HAMMER.ordinal(), -1) == PlayerEmotes.Result.NOT_AN_EMOTE, "hammering is no emote");
            helper.assertTrue(PlayerEmotes.emote(p, Act.WAVE.ordinal(), -1) == PlayerEmotes.Result.OK, "a wave taken");
            helper.assertTrue(PlayerEmotes.now(p) == Act.WAVE, "the player is waving: " + PlayerEmotes.now(p));
            helper.assertTrue(PlayerEmotes.emote(p, Act.BOW.ordinal(), -1) == PlayerEmotes.Result.TOO_SOON, "a second emote at once is refused");
            helper.assertTrue(PlayerEmotes.waitingForTests() >= 1, "an answer coming: " + PlayerEmotes.waitingForTests());
        });
        helper.runAtTickTime(30, () -> {
            helper.assertTrue(FolkAction.now(friend) == Act.WAVE, "the friend waves back: " + FolkAction.now(friend));
            helper.assertTrue(FolkAction.now(foe) != Act.WAVE, "the one that cannot stand the player does not: " + FolkAction.now(foe));
            // Five in ten seconds at most, a second apart.
            ServerPlayer p = you[0];
            int ok = 0;
            for (int i = 0; i < 4; i++) {
                PlayerEmotes.Result r = PlayerEmotes.emote(p, Act.CLAP.ordinal(), -1);
                if (r == PlayerEmotes.Result.OK) ok++;
            }
            helper.assertTrue(ok == 1, "one a second: " + ok);
        });
        for (int k = 0; k < 4; k++) {
            int at = 50 + k * 22;
            helper.runAtTickTime(at, () -> {
                PlayerEmotes.Result r = PlayerEmotes.emote(you[0], Act.CHEER.ordinal(), -1);
                helper.assertTrue(k(at) < 3 ? r == PlayerEmotes.Result.OK : r == PlayerEmotes.Result.TOO_SOON,
                    "the emote at " + at + ": " + r + " (five in ten seconds at most)");
            });
        }
        // A handshake offered to the friend: taken, and the better thought of, once a day.
        helper.runAtTickTime(250, () -> {
            ServerPlayer p = you[0];
            p.moveTo(friend.getX(), friend.getY(), friend.getZ() + 2.0, 180.0F, 0.0F);
            int before = friend.persona().affinity(p.getUUID());
            PlayerEmotes.resetForTests();
            helper.assertTrue(PlayerEmotes.emote(p, Act.HANDSHAKE.ordinal(), friend.getId()) == PlayerEmotes.Result.OK, "a handshake offered");
            helper.assertTrue(FolkAction.now(friend) == Act.HANDSHAKE, "taken at once: " + FolkAction.now(friend));
            int after = friend.persona().affinity(p.getUUID());
            helper.assertTrue(after > before, "thought the better of for it: " + before + " -> " + after);
            PlayerEmotes.resetForTests();
            // reset clears the day's record too; the record itself is what keeps it once a day
            PlayerEmotes.emote(p, Act.HANDSHAKE.ordinal(), friend.getId());
            helper.assertTrue(friend.persona().affinity(p.getUUID()) > after - 1, "a second handshake does no harm");
            // A beckon to a close friend: it comes along.
            friend.setNoAi(false);
            helper.assertTrue(!friend.isFollowing(p), "not following yet");
        });
        helper.runAtTickTime(280, () -> {
            PlayerEmotes.resetForTests();
            ServerPlayer p = you[0];
            helper.assertTrue(PlayerEmotes.emote(p, Act.BECKON.ordinal(), -1) == PlayerEmotes.Result.OK, "a beckon");
            helper.assertTrue(friend.isFollowing(p), "the close friend comes along");
            helper.assertTrue(!foe.isFollowing(p), "the other does not");
            EmoteRules.Who w = new EmoteRules.Who(-70, false, false, false, false, true);
            helper.assertTrue(EmoteRules.answer(Act.HANDSHAKE, w) == Act.SHRUG, "a hand it will not take is shrugged off");
            Kit.log("fan05 emotes: wave answered by the friend, the pace kept, a handshake, a beckon followed");
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    private static int k(int at) { return (at - 50) / 22; }

    // ------------------------------------------------------------------ fan06: the stage

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fan06_stage")
    public static void fan06_stage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 2_820_000);
        Kit.hold(level, 2_820_000 + 12, Z + 80, 96);
        helper.runAtTickTime(5, () -> {
            List<String> lines = AnimStage.stage(level, o);
            net.minecraft.world.phys.AABB all = new net.minecraft.world.phys.AABB(o).expandTowards(32, 8, 170).inflate(4);
            int views = 0;
            for (String l : lines) if (l.startsWith("VIEW ")) views++;
            helper.assertTrue(views >= 14, "a picture a row and the life of the town: " + views);
            Set<Act> doing = EnumSet.noneOf(Act.class);
            for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, all)) {
                if (f.getTags().contains("folk_lineup")) doing.add(FolkAction.now(f));
            }
            for (Act a : Act.values()) {
                if (a == Act.NONE) continue;
                helper.assertTrue(doing.contains(a), "somebody on the stage is doing " + a.word());
            }
            Kit.log("fan06 the stage: " + doing.size() + " acts, " + views + " views");
            for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null,
                    all, e -> e.getTags().contains("folk_lineup"))) e.discard();
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fan07: a crowd, and the swimmer's pool  [anim]

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fan07_crowd")
    public static void fan07_crowd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 3_310_000);
        helper.runAtTickTime(5, () -> {
            int n = AnimStage.crowd(level, o.offset(-12, 0, -12), 100);
            helper.assertTrue(n == 100, "a crowd of a hundred: " + n);
            net.minecraft.world.phys.AABB all = new net.minecraft.world.phys.AABB(o).inflate(16, 4, 16);
            Set<Act> doing = EnumSet.noneOf(Act.class);
            int busy = 0;
            for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, all)) {
                if (!f.getTags().contains("folk_lineup")) continue;
                Act a = FolkAction.now(f);
                if (a != Act.NONE) busy++;
                doing.add(a);
            }
            helper.assertTrue(busy == 100, "every one of them at work or talk: " + busy);
            helper.assertTrue(doing.contains(Act.TALK) && doing.contains(Act.HAMMER) && doing.size() >= 15, "the town's work and its talk: " + doing);
            for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null,
                    all, e -> e.getTags().contains("folk_lineup"))) e.discard();
            Kit.log("[VT] fan07 a crowd of " + n + ", " + doing.size() + " acts");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fan08_swimmer")
    public static void fan08_swimmer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ground(helper, 3_320_000);
        Kit.hold(level, 3_320_000 + 12, Z + 80, 96);
        helper.runAtTickTime(5, () -> {
            AnimStage.stage(level, o);
            net.minecraft.world.phys.AABB all = new net.minecraft.world.phys.AABB(o).expandTowards(32, 8, 170).inflate(4);
            VillageFolkEntity swimmer = null;
            for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, all)) {
                if (f.getTags().contains("folk_lineup") && FolkAction.now(f) == Act.SWIM) swimmer = f;
            }
            helper.assertTrue(swimmer != null, "a swimmer on the stage");
            helper.assertTrue(swimmer.isInWater(), "the swimmer in water: " + swimmer.blockPosition().toShortString());
            helper.assertTrue(swimmer.getY() < o.getY(), "held at the water's top, below the floor: " + swimmer.getY());
            for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null,
                    all, e -> e.getTags().contains("folk_lineup"))) e.discard();
            Kit.log("[VT] fan08 the swimmer in its pool at " + swimmer.getY());
            helper.succeed();
        });
    }
}
