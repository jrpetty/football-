package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.pa.PaBanter;
import com.jrpetty.mcassistant.pa.PaConfig;
import com.jrpetty.mcassistant.pa.PaData;
import com.jrpetty.mcassistant.pa.PaLevels;
import com.jrpetty.mcassistant.pa.PaLife;
import com.jrpetty.mcassistant.pa.PaLines;
import com.jrpetty.mcassistant.pa.PaMemory;
import com.jrpetty.mcassistant.pa.PaMemoryAct;
import com.jrpetty.mcassistant.pa.PaMineLock;
import com.jrpetty.mcassistant.pa.PaPace;
import com.jrpetty.mcassistant.pa.PaPersona;
import com.jrpetty.mcassistant.pa.PaServer;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Set;

/**
 * [pa35] The Player AI Assistant's memory, its player's pace and its ladders, as a player meets them: told things in
 * plain words, it acts on them (the favourite food handed over, the liked wood felled first), keeps standing rules on
 * their triggers, says reminders when due, keeps it all across a save and a fall, forgets on request, and treats
 * friends and foes as told. It breaks blocks in exactly a player's time for the tool, plants at a hand's pace, and
 * leaves an ore above its level alone through every way of asking.
 *
 * <p>Each on its own ground, x 7_500_000 up (3000 apart), z 70000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PaMemoryGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    private static int ground(ServerLevel level, int x) {
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        PaConfig.perPlayerForTests(null);
        PaConfig.respawnSecondsForTests(null);
        PaConfig.ladderForTests(null, null);
        PaMemoryAct.resetForTests();
        PaMineLock.resetForTests();
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        Kit.live(level, x, Z, 40);
        int y = Kit.surface(level, x, Z).getY();
        for (int dx = -36; dx <= 36; dx++) for (int dz = -36; dz <= 36; dz++) {
            for (int dy = 0; dy < 12; dy++) level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        return y;
    }

    private static ServerPlayer player(GameTestHelper helper, ServerLevel level, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
        return p;
    }

    private static PlayerAssistantEntity assistant(GameTestHelper helper, ServerPlayer p) {
        PaServer.act(p, "summon", new CompoundTag());
        PlayerAssistantEntity a = PaLife.find(p);
        helper.assertTrue(a != null, "an assistant came");
        PaData.Rec r = a.rec();
        r.confirm = false;
        r.autoHelp = false;
        a.setPaXpForTests(0);
        return a;
    }

    private static void say(ServerPlayer p, PlayerAssistantEntity a, String text) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        PaServer.act(p, "talk", t);
        Kit.log("[VT] pam said to " + a.displayNameCap() + ": \"" + text + "\" -> " + lastWords(a));
    }

    private static String lastWords(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        return r == null || r.log.isEmpty() ? "" : r.log.peekLast().text();
    }

    private static String allWords(PlayerAssistantEntity a) {
        StringBuilder b = new StringBuilder();
        PaData.Rec r = a.rec();
        if (r != null) for (PaData.Line l : r.log) b.append(l.who()).append(':').append(l.text()).append(" | ");
        return b.toString();
    }

    /** Is this line one of a key's (any corner), filled as the assistant fills it? */
    private static boolean fromKey(PlayerAssistantEntity a, String key, String line, Map<String, String> fill) {
        for (String corner : new String[]{"", "@gloomy", "@serious", "@fg", "@sg", "@sf"}) {
            for (String raw : PaLines.get(key + corner)) {
                java.util.Map<String, String> m = new java.util.HashMap<>(fill);
                m.putIfAbsent("name", a.displayNameCap());
                m.putIfAbsent("owner", a.ownerName());
                String f = PaPersona.fill(raw, m);
                if (f.equalsIgnoreCase(line) || line.toLowerCase(java.util.Locale.ROOT).startsWith(f.toLowerCase(java.util.Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static void tree(ServerLevel level, BlockPos base, BlockState log, BlockState leaves) {
        for (int i = 0; i < 5; i++) level.setBlock(base.above(i), log, 3);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 5; dy++) {
            BlockPos l = base.offset(dx, dy, dz);
            if (level.getBlockState(l).isAir()) level.setBlock(l, leaves.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 3);
        }
    }

    // ================================================================== memory

    /** pam01: a preference changes what it does: told steak is the favourite, "bring me food" hands over steak, not bread;
     *  told "I like oak not spruce", it fells the oak first though a spruce stands nearer. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pam01_preference")
    public static void pam01_preference(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_500_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.BREAD, 16));
        a.insertGiven(new ItemStack(Items.COOKED_BEEF, 8));
        a.insertGiven(new ItemStack(Items.IRON_AXE));
        say(p, a, "my favourite food is steak");
        helper.assertTrue(a.rec().memory.favourite("food") != null, "it kept the favourite: " + lastWords(a));
        helper.assertTrue(fromKey(a, "say.mem.got.fav", lastWords(a), Map.of("what", "steak", "thing", "food")), "confirmed in one line: " + lastWords(a));
        say(p, a, "I like oak not spruce");
        tree(level, new BlockPos(x + 4, y, Z + 3), Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState());
        tree(level, new BlockPos(x + 11, y, Z - 5), Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());
        say(p, a, "bring me food");
        boolean[] chopping = {false};
        helper.runAfterDelay(400, () -> { say(p, a, "get 3 logs"); chopping[0] = true; });
        helper.succeedWhen(() -> {
            int beef = 0, bread = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (p.getInventory().getItem(i).is(Items.COOKED_BEEF)) beef += p.getInventory().getItem(i).getCount();
                if (p.getInventory().getItem(i).is(Items.BREAD)) bread += p.getInventory().getItem(i).getCount();
            }
            helper.assertTrue(beef >= 4 && bread == 0, "its favourite handed over, not bread: steak " + beef + ", bread " + bread + " (" + lastWords(a) + ")");
            helper.assertTrue(chopping[0], "then chopping");
            int oak = a.countMatching(s -> s.is(Items.OAK_LOG)), spruce = a.countMatching(s -> s.is(Items.SPRUCE_LOG));
            helper.assertTrue(oak >= 3 && spruce == 0, "the liked oak felled first, the nearer spruce left: oak " + oak + ", spruce " + spruce);
            Kit.log("[VT] pam01 steak " + beef + " bread " + bread + "; oak " + oak + " spruce " + spruce);
        });
    }

    /** pam02: standing rules run on their triggers: "whenever I'm low, give me food" when its player's health drops;
     *  "when it's night, come home" at nightfall (home set by "this is my base"). */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pam02_rules")
    public static void pam02_rules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_503_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.BREAD, 16));
        say(p, a, "this is my base");
        BlockPos home = a.getHome();
        helper.assertTrue(home != null && a.rec().waypoints.containsKey("base"), "the base is a place and its home: " + lastWords(a));
        say(p, a, "whenever I'm low, give me food");
        say(p, a, "when it's night, come home");
        helper.assertTrue(a.rec().memory.of(PaMemory.Kind.RULE).size() == 2, "two rules kept: " + allWords(a));
        p.teleportTo(level, x + 24.5, y, Z + 0.5, Set.of(), 0F, 0F);
        a.teleportTo(x + 22.5, y, Z + 0.5);
        say(p, a, "stay here");
        long[] phase = {0};
        helper.runAfterDelay(60, () -> p.setHealth(5.0F));
        helper.runAfterDelay(400, () -> { level.setDayTime(12600); phase[0] = 1; });
        helper.succeedWhen(() -> {
            int bread = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.BREAD)) bread += p.getInventory().getItem(i).getCount();
            helper.assertTrue(bread > 0, "low, it brought food: " + allWords(a));
            helper.assertTrue(phase[0] == 1, "night came");
            helper.assertTrue(a.blockPosition().distSqr(home) < 5 * 5, "at nightfall it went home: " + a.blockPosition().toShortString() + " home " + home.toShortString());
            Kit.log("[VT] pam02 food " + bread + "; home at night; said " + allWords(a));
        });
    }

    /** pam03: a reminder fires on time, said aloud, and is gone from its memory after. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pam03_reminder")
    public static void pam03_reminder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_506_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "remind me in 5 seconds to check the furnace");
        helper.assertTrue(a.rec().memory.of(PaMemory.Kind.REMINDER).size() == 1, "a reminder kept: " + lastWords(a));
        long start = level.getGameTime();
        helper.runAfterDelay(60, () -> helper.assertTrue(a.rec().memory.of(PaMemory.Kind.REMINDER).size() == 1, "not yet: three seconds in"));
        helper.succeedWhen(() -> {
            helper.assertTrue(a.rec().memory.of(PaMemory.Kind.REMINDER).isEmpty(), "fired and gone");
            helper.assertTrue(lastWords(a).toLowerCase(java.util.Locale.ROOT).contains("check the furnace"), "said aloud: " + lastWords(a));
            helper.assertTrue(level.getGameTime() - start >= 100, "on time, not early: " + (level.getGameTime() - start));
            Kit.log("[VT] pam03 reminder after " + (level.getGameTime() - start) + " ticks: " + lastWords(a));
        });
    }

    /** pam04: kept across a save (its record written and read back) and a fall (a new body after its respawn time). */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pam04_persist")
    public static void pam04_persist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_509_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "call me boss");
        say(p, a, "my favourite food is steak");
        say(p, a, "remind me in 10 minutes to feed the dog");
        PaData.Rec r = a.rec();
        PaData.Rec back = PaData.Rec.loadForTests(r.saveForTests());
        helper.assertTrue(back.memory.size() == 3 && "steak".equals(back.memory.favourite("food")) && back.memory.first(PaMemory.Kind.NAME).subject.equals("Boss"),
            "the same three things after a save: " + back.memory.size());
        helper.assertTrue(back.memory.of(PaMemory.Kind.REMINDER).get(0).due == r.memory.of(PaMemory.Kind.REMINDER).get(0).due, "the reminder still due when it was");
        PaConfig.respawnSecondsForTests(3);
        a.hurt(level.damageSources().genericKill(), 1000F);
        helper.succeedWhen(() -> {
            PlayerAssistantEntity again = PaLife.find(p);
            helper.assertTrue(again != null && again != a, "a new body");
            helper.assertTrue(PaMemoryAct.callName(again).equals("Boss"), "still calls its player boss: " + PaMemoryAct.callName(again));
            helper.assertTrue("steak".equals(again.rec().memory.favourite("food")), "still knows the favourite");
            say(p, again, "what's my favourite food?");
            helper.assertTrue(lastWords(again).toLowerCase(java.util.Locale.ROOT).contains("steak"), "and says so: " + lastWords(again));
            PaConfig.respawnSecondsForTests(null);
            Kit.log("[VT] pam04 after a fall: " + lastWords(again));
        });
    }

    /** pam05: "forget that" drops the last thing, "forget everything about Sam" all about Sam, "forget everything" asks first. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pam05_forget")
    public static void pam05_forget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_512_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "my favourite food is steak");
        say(p, a, "I hate creepers");
        say(p, a, "Sam is my friend, help him");
        say(p, a, "don't swear around Sam");
        PaMemory.Book b = a.rec().memory;
        helper.assertTrue(b.size() == 4, "four things: " + b.size());
        say(p, a, "forget that");
        helper.assertTrue(b.size() == 3 && b.of(PaMemory.Kind.CLEAN).isEmpty(), "the last one gone: " + lastWords(a));
        say(p, a, "forget everything about Sam");
        helper.assertTrue(b.size() == 2 && !b.friend("sam"), "Sam forgotten: " + lastWords(a));
        say(p, a, "what do you remember?");
        helper.assertTrue(lastWords(a).toLowerCase(java.util.Locale.ROOT).contains("steak") && lastWords(a).toLowerCase(java.util.Locale.ROOT).contains("creeper"),
            "it lists the rest: " + lastWords(a));
        say(p, a, "forget everything");
        helper.assertTrue(b.size() == 2, "asked first, nothing gone yet: " + lastWords(a));
        say(p, a, "#memforgetall");
        helper.assertTrue(b.size() == 0, "then all of it");
        say(p, a, "my password is hunter2");
        helper.assertTrue(b.size() == 0 && fromKey(a, "say.mem.private", lastWords(a), Map.of()), "a password is never kept: " + lastWords(a));
        helper.assertTrue(allWords(a).contains("(something private: not kept)") && !allWords(a).contains("hunter2"), "not even in the talk log");
        helper.succeed();
    }

    /** pam06: people rules: a foe's orders refused and its roasts answered whatever the roast setting; a friend teased
     *  gently and defended from a zombie; nobody else is told what its player told it. */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "pam06_people")
    public static void pam06_people(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_515_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        PaData.Rec r = a.rec();
        r.roastBack = 2;                                               // its player turned roasting others off
        ServerPlayer other = player(helper, level, new BlockPos(x + 3, y, Z));
        String name = other.getGameProfile().getName();
        r.memory.put(new PaMemory.Entry(PaMemory.Kind.FOE, name, ""), level.getGameTime());
        PaBanter.stranger(a, other, "follow me", false);
        helper.assertTrue(fromKey(a, "say.mem.foe_refuse", lastWords(a), Map.of("player", name)), "a foe's order refused: " + lastWords(a));
        helper.assertTrue(a.getMode() != com.jrpetty.mcassistant.entity.AssistantEntity.Mode.FOLLOW || a.getOwnerPlayer() == p, "and not obeyed");
        ServerPlayer other2 = player(helper, level, new BlockPos(x + 3, y, Z + 2));
        PaBanter.stranger(a, other2, "you're rubbish, you useless lump", false);
        String roast = lastWords(a);
        boolean roasted = false;
        for (String lang : new String[]{"clean", "cheeky", "unfiltered"}) roasted |= fromKey(a, "stranger.roast." + lang, roast, Map.of("player", name));
        helper.assertTrue(roasted, "a foe roasted back even with roasting off: " + roast);
        // the same name as a friend now
        r.memory.entries.clear();
        r.memory.put(new PaMemory.Entry(PaMemory.Kind.FRIEND, name, "").extra("help"), level.getGameTime());
        ServerPlayer other3 = player(helper, level, new BlockPos(x + 4, y, Z - 2));
        PaBanter.stranger(a, other3, "you're rubbish, you useless lump", false);
        helper.assertTrue(fromKey(a, "say.mem.friend_tease", lastWords(a), Map.of("player", name)), "a friend teased gently: " + lastWords(a));
        ServerPlayer other4 = player(helper, level, new BlockPos(x + 4, y, Z + 4));
        PaBanter.stranger(a, other4, "what's " + p.getGameProfile().getName() + "'s favourite food?", false);
        helper.assertTrue(fromKey(a, "say.mem.not_telling", lastWords(a), Map.of("player", name)), "never told: " + lastWords(a));
        // a friend in trouble
        ServerPlayer friend = player(helper, level, new BlockPos(x + 6, y, Z + 6));
        a.teleportTo(x + 3.5, y, Z + 3.5);
        Zombie z = EntityType.ZOMBIE.create(level);
        z.moveTo(x + 9.5, y, Z + 6.5);
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        level.addFreshEntity(z);
        z.setTarget(friend);
        a.insertGiven(new ItemStack(Items.IRON_SWORD));
        helper.onEachTick(() -> { if (z.isAlive()) z.setTarget(friend); });
        helper.succeedWhen(() -> {
            helper.assertTrue(a.getTarget() == z || !z.isAlive(), "it went for the zombie after its player's friend (target " + a.getTarget() + ")");
            Kit.log("[VT] pam06 defended the friend; said " + allWords(a));
        });
    }

    // ================================================================== a player's pace

    /** pam07: breaking stone with a wooden, an iron and a diamond pickaxe takes the vanilla player's time to the tick, at level 1 and level 10 alike. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pam07_break_time")
    public static void pam07_break_time(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_518_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos at = new BlockPos(x + 2, y, Z);
        level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
        StringBuilder log = new StringBuilder();
        for (net.minecraft.world.item.Item pick : new net.minecraft.world.item.Item[]{Items.WOODEN_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.AIR}) {
            p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(pick));
            p.setOnGround(true);                                  // a player standing on the ground (in the air vanilla digs five times slower)
            a.setOnGround(true);
            float perTick = Blocks.STONE.defaultBlockState().getDestroyProgress(p, level, at);
            float sum = 0;
            int vanilla = 0;
            while (sum < 1.0F && vanilla < 100000) { sum += perTick; vanilla++; }
            int[] lv = new int[2];
            for (int k = 0; k < 2; k++) {
                a.setPaXpForTests(k == 0 ? 0 : PaLevels.xpFor(10));
                a.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(pick));
                lv[k] = a.workTicksFor(Blocks.STONE.defaultBlockState());
            }
            helper.assertTrue(Math.abs(lv[0] - vanilla) <= 1 && lv[0] == lv[1], pick + ": vanilla " + vanilla + " ticks, level 1 " + lv[0] + ", level 10 " + lv[1]);
            log.append(pick).append(' ').append(vanilla).append('/').append(lv[0]).append('/').append(lv[1]).append("; ");
        }
        Kit.log("[VT] pam07 stone ticks (vanilla/L1/L10): " + log);
        helper.succeed();
    }

    /** pam08: replanting a field goes at a hand's pace (never two plantings under half a second apart), and every hand
     *  action (planting, placing) draws at least ten ticks, at every level. */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "pam08_hand_pace")
    public static void pam08_hand_pace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_521_000, y = ground(level, x);
        BlockPos water = new BlockPos(x + 6, y - 1, Z);
        level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx == 0 && dz == 0) continue;
            level.setBlock(water.offset(dx, 0, dz), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(water.offset(dx, 1, dz), ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
        }
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        for (int lv : new int[]{1, 10}) {
            a.setPaXpForTests(lv == 1 ? 0 : PaLevels.xpFor(10));
            for (int i = 0; i < 200; i++) {
                int h = a.actionPaceTicks(), b = a.buildPaceHundredths();
                helper.assertTrue(h >= PaPace.HAND_MIN && b >= PaPace.HAND_MIN * 100, "a hand action under the cap at level " + lv + ": " + h + " / " + b);
            }
        }
        a.setPaXpForTests(0);
        a.insertGiven(new ItemStack(Items.IRON_HOE));
        CompoundTag t = new CompoundTag();
        t.putString("text", "farm this field");
        t.putInt("bx", water.getX() + 1); t.putInt("by", water.getY() + 1); t.putInt("bz", water.getZ());
        PaServer.act(p, "talk", t);
        java.util.List<Long> planted = new java.util.ArrayList<>();
        int[] young = {0};
        helper.onEachTick(() -> {
            int n = 0;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                BlockState st = level.getBlockState(water.offset(dx, 1, dz));
                if (st.getBlock() instanceof CropBlock c && !c.isMaxAge(st)) n++;
            }
            for (int k = young[0]; k < n; k++) planted.add(level.getGameTime());
            young[0] = Math.max(young[0], n);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(planted.size() >= 20, "twenty replanted: " + planted.size());
            long span = planted.get(19) - planted.get(0);
            helper.assertTrue(span >= 19 * 10, "twenty plantings took at least 19 x 0.5 s: " + span + " ticks");
            Kit.log("[VT] pam08 twenty plantings over " + span + " ticks (" + String.format(java.util.Locale.ROOT, "%.2f", span / 19.0 / 20.0) + " s each)");
        });
    }

    // ================================================================== the ladders

    /** pam09: at level 3 it won't mine iron, by order or by gathering, and leaves the ore in place in a rock face it
     *  tunnels; at level 4 it does; with the ladder off, level 1 may. The gear ladder: a crossbow held, not used, at 4. */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pam09_ladder")
    public static void pam09_ladder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_524_000, y = ground(level, x);
        for (int dx = 3; dx < 9; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy < 4; dy++)
            level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), (dx == 3 && dz == 0 && dy == 1 ? Blocks.IRON_ORE : Blocks.STONE).defaultBlockState(), 2);
        BlockPos ore = new BlockPos(x + 3, y + 1, Z);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
        a.setPaXpForTests(PaLevels.xpFor(3));
        helper.assertTrue(!PaLevels.canMine(a, Blocks.IRON_ORE.defaultBlockState()) && PaLevels.canMine(a, Blocks.COAL_ORE.defaultBlockState()), "level 3: coal yes, iron no");
        CompoundTag t = new CompoundTag();
        t.putString("text", "mine that iron");
        t.putInt("bx", ore.getX()); t.putInt("by", ore.getY()); t.putInt("bz", ore.getZ());
        PaServer.act(p, "talk", t);
        helper.assertTrue(a.plan.idle() && fromKey(a, "say.lock.refuse", lastWords(a), Map.of()) || lastWords(a).toLowerCase(java.util.Locale.ROOT).contains("level 4"), "refused, with how far off: " + lastWords(a));   // [voice37] "Level 4 ..." opens a sentence
        say(p, a, "mine iron until you have 1");
        helper.assertTrue(a.plan.idle(), "the gathering order refused too: " + lastWords(a));
        say(p, a, "gather 6 stone");                                     // it digs round the iron, and leaves it
        a.insertGiven(new ItemStack(Items.CROSSBOW));
        a.setPaXpForTests(PaLevels.xpFor(4));
        helper.assertTrue(!PaLevels.canUse(a, new ItemStack(Items.CROSSBOW)) && !a.mayUseTier(new ItemStack(Items.CROSSBOW)), "level 4: the crossbow held, not used");
        a.setPaXpForTests(PaLevels.xpFor(3));
        long[] phase = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0 && a.plan.idle() && a.countMatching(s -> s.is(Items.COBBLESTONE)) >= 6) {
                helper.assertTrue(level.getBlockState(ore).is(Blocks.IRON_ORE), "the iron left in place");
                phase[0] = 1;
                a.setPaXpForTests(PaLevels.xpFor(4));
                PaServer.act(p, "talk", t);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] == 1, "stone dug round the ore first (" + lastWords(a) + ")");
            helper.assertTrue(!level.getBlockState(ore).is(Blocks.IRON_ORE), "at level 4 it mined the iron (" + lastWords(a) + ")");
            PaConfig.ladderForTests(false, null);
            a.setPaXpForTests(0);
            helper.assertTrue(PaLevels.canMine(a, Blocks.DIAMOND_ORE.defaultBlockState()), "with the ladder off, level 1 may mine anything");
            PaConfig.ladderForTests(null, null);
            Kit.log("[VT] pam09 ladder: refused at 3, left in place, mined at 4; said " + allWords(a));
        });
    }

    /** pam10: the unlock moment: reaching level 4 it says what it may now mine and use (iron ore, armour up to iron), and
     *  its player gets the unlock toast (the payload carries them). */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pam10_unlock")
    public static void pam10_unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_527_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        PaConfig.ladderForTests(null, null);
        helper.runAfterDelay(10, () -> {
            a.setPaXpForTests(PaLevels.xpFor(4) - 1);
            helper.assertTrue(a.veteranLevel() == 3, "level 3 first: " + a.veteranLevel());
            a.gainXpFirst(40);
            String said = lastWords(a).toLowerCase(java.util.Locale.ROOT);
            helper.assertTrue(a.veteranLevel() == 4, "level 4 now: " + a.veteranLevel());
            helper.assertTrue(said.contains("iron ore") && said.contains("armour up to iron"), "names its unlocks: " + said);
            helper.assertTrue(com.jrpetty.mcassistant.pa.PaLevelUp.unlocks(4).contains("Can now mine iron ore"), "the toast's text");
            Kit.log("[VT] pam10 unlock at level 4: " + lastWords(a));
            helper.succeed();
        });
    }

    /** pam11: the crossbow as a player's. Locked at level 4 (it won't shoot one); at 5 it spans a plain crossbow in the
     *  game's 25 ticks (held in the charging pose), one arrow from its pack, then shoulders and looses; Multishot with
     *  Quick Charge III spans in 10 ticks and carries three bolts for one arrow; Piercing IV bolts pierce four; and with
     *  the fight over it spans it again, so it carries a bolt. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pam11_crossbow")
    public static void pam11_crossbow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_530_000, y = ground(level, x);
        BlockPos tgt = new BlockPos(x + 14, y + 1, Z);
        for (int dx = 1; dx <= 14; dx++) for (int dy = 0; dy <= 3; dy++) level.setBlock(new BlockPos(x + dx, y + dy, Z), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(tgt, Blocks.TARGET.defaultBlockState(), 3);
        ServerPlayer p = player(helper, level, new BlockPos(x - 3, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p);
        PaConfig.ladderForTests(null, null);
        var ench = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        a.removeMatching(st -> st.is(Items.BOW) || st.is(Items.CROSSBOW) || st.is(Items.ARROW), 999);
        a.insertGiven(new ItemStack(Items.CROSSBOW));
        a.insertGiven(new ItemStack(Items.ARROW, 16));
        int[] phase = {0}, usingFrom = {-1}, chargedAt = {-1}, arrows = {0}, wait = {0};
        boolean[] shot = {false};
        StringBuilder log = new StringBuilder();
        helper.onEachTick(() -> {
            if (!a.isAlive()) helper.fail("it died");
            ItemStack hand = a.getMainHandItem();
            if (a.isUsingItem() && a.getUseItem().is(Items.CROSSBOW) && usingFrom[0] < 0) usingFrom[0] = a.tickCount;
            if (hand.is(Items.CROSSBOW) && net.minecraft.world.item.CrossbowItem.isCharged(hand) && chargedAt[0] < 0) chargedAt[0] = a.tickCount;
            wait[0]++;
            switch (phase[0]) {
                case 0 -> {                                                  // level 4: locked
                    if (wait[0] < 10) return;
                    a.teleportTo(x + 0.5, y, Z + 0.5);
                    a.setPaXpForTests(PaLevels.xpFor(4));
                    helper.assertTrue(!com.jrpetty.mcassistant.pa.PaCrossbow.atBlock(a, tgt, null) && !a.mayShoot(new ItemStack(Items.CROSSBOW)), "level 4: it won't shoot a crossbow");
                    String kept = com.jrpetty.mcassistant.pa.PaPack.equipBest(a);
                    helper.assertTrue(kept.toLowerCase(java.util.Locale.ROOT).contains("crossbow (level 5)"), "equip best says what it kept back: " + kept);
                    log.append("kept: ").append(kept).append("; ");
                    a.setPaXpForTests(PaLevels.xpFor(5));
                    arrows[0] = a.countMatching(st -> st.is(Items.ARROW));
                    usingFrom[0] = -1; chargedAt[0] = -1; shot[0] = false;
                    helper.assertTrue(com.jrpetty.mcassistant.pa.PaCrossbow.atBlock(a, tgt, sh -> shot[0] = true), "level 5: it takes the shot");
                    if (a.isUsingItem()) usingFrom[0] = a.tickCount;           // spanning from this very tick
                    phase[0] = 1; wait[0] = 0;
                }
                case 1 -> {                                                  // a plain crossbow: 25 ticks to span, one arrow, the shot
                    if (!shot[0]) { if (wait[0] > 200) helper.fail("no shot in 10 s: " + a.mind.xbow); return; }
                    int load = chargedAt[0] - usingFrom[0];
                    helper.assertTrue(load >= 25 && load <= 27, "spanned in the game's time: " + load + " ticks");
                    helper.assertTrue(arrows[0] - a.countMatching(st -> st.is(Items.ARROW)) == 1, "one arrow from its pack");
                    log.append("plain: spanned in ").append(load).append(" ticks; ");
                    com.jrpetty.mcassistant.pa.PaCrossbow.cancel(a);
                    ItemStack cb = new ItemStack(Items.CROSSBOW);
                    cb.enchant(ench.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT), 1);
                    cb.enchant(ench.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.QUICK_CHARGE), 3);
                    a.setItemSlot(EquipmentSlot.MAINHAND, cb);
                    arrows[0] = a.countMatching(st -> st.is(Items.ARROW));
                    usingFrom[0] = -1; chargedAt[0] = -1; shot[0] = false;
                    helper.assertTrue(com.jrpetty.mcassistant.pa.PaCrossbow.atBlock(a, tgt, sh -> shot[0] = true), "the Multishot shot");
                    if (a.isUsingItem()) usingFrom[0] = a.tickCount;           // spanning from this very tick
                    phase[0] = 2; wait[0] = 0;
                }
                case 2 -> {                                                  // Multishot + Quick Charge III
                    if (chargedAt[0] > 0 && !shot[0]) {
                        var cp = a.getMainHandItem().get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
                        if (cp != null && !cp.isEmpty()) helper.assertTrue(cp.getItems().size() == 3, "three bolts loaded: " + cp.getItems().size());
                    }
                    if (!shot[0]) { if (wait[0] > 200) helper.fail("no Multishot shot"); return; }
                    int load = chargedAt[0] - usingFrom[0];
                    helper.assertTrue(load >= 10 && load <= 12, "Quick Charge III spans in half a second: " + load);
                    helper.assertTrue(arrows[0] - a.countMatching(st -> st.is(Items.ARROW)) == 1, "three bolts for one arrow");
                    int flying = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Arrow.class, a.getBoundingBox().inflate(20), ar -> ar.getOwner() == a && ar.tickCount < 30).size();
                    helper.assertTrue(flying >= 3, "three in the air: " + flying);
                    log.append("multishot: ").append(flying).append(" bolts, spanned in ").append(load).append("; ");
                    com.jrpetty.mcassistant.pa.PaCrossbow.cancel(a);
                    ItemStack cb = new ItemStack(Items.CROSSBOW);
                    cb.enchant(ench.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.PIERCING), 4);
                    a.setItemSlot(EquipmentSlot.MAINHAND, cb);
                    usingFrom[0] = -1; chargedAt[0] = -1; shot[0] = false;
                    helper.assertTrue(com.jrpetty.mcassistant.pa.PaCrossbow.atBlock(a, tgt, sh -> shot[0] = true), "the Piercing shot");
                    if (a.isUsingItem()) usingFrom[0] = a.tickCount;           // spanning from this very tick
                    phase[0] = 3; wait[0] = 0;
                }
                case 3 -> {                                                  // Piercing IV
                    if (!shot[0]) { if (wait[0] > 200) helper.fail("no Piercing shot"); return; }
                    var bolts = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Arrow.class, a.getBoundingBox().inflate(20), ar -> ar.getOwner() == a && ar.tickCount < 3);
                    helper.assertTrue(!bolts.isEmpty() && bolts.get(0).getPierceLevel() == 4 && bolts.get(0).isCritArrow(), "a Piercing IV bolt, crit as a player's: " + (bolts.isEmpty() ? "none" : bolts.get(0).getPierceLevel()));
                    log.append("piercing ").append(bolts.get(0).getPierceLevel()).append("; ");
                    chargedAt[0] = -1;
                    phase[0] = 4; wait[0] = 0;
                }
                case 4 -> {                                                  // the fight over: spanned again, carried loaded
                    if (chargedAt[0] < 0) { if (wait[0] > 120) helper.fail("not reloaded after the shot"); return; }
                    helper.assertTrue(a.getMainHandItem().is(Items.CROSSBOW) && net.minecraft.world.item.CrossbowItem.isCharged(a.getMainHandItem()), "carries a bolt");
                    log.append("reloaded after ").append(wait[0]).append(" ticks; shots ").append(a.mind.xbowShots).append(", loads ").append(a.mind.xbowLoads);
                    Kit.log("[VT] pam11 crossbow: " + log + "; said " + allWords(a));
                    phase[0] = 5;
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    /** pam12: auto-help that's actually help, each kind its own switch: its player's pickaxe nearly worn through, it
     *  hands over its spare; loot lying about, it picks up; with "tool" switched off, it leaves the tool be. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pam12_auto_help")
    public static void pam12_auto_help(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_533_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.setPaXpForTests(PaLevels.xpFor(10));                          // checks every six seconds at the top
        PaData.Rec r = a.rec();
        r.autoHelp = true;
        r.autoOff.clear();
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 3);
        p.setItemSlot(EquipmentSlot.MAINHAND, worn);
        a.insertGiven(new ItemStack(Items.IRON_PICKAXE));
        for (int i = 0; i < 3; i++) {
            var ie = new net.minecraft.world.entity.item.ItemEntity(level, x + 4 + i, y + 0.2, Z + 3, new ItemStack(Items.COBBLESTONE, 5));
            level.addFreshEntity(ie);
        }
        int[] phase = {0}, at = {0};
        helper.onEachTick(() -> {
            at[0]++;
            switch (phase[0]) {
                case 0 -> {
                    int fresh = 0;
                    for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                        ItemStack st = p.getInventory().getItem(i);
                        if (st.is(Items.IRON_PICKAXE) && st.getDamageValue() == 0) fresh++;
                    }
                    if (fresh == 0) { if (at[0] > 900) helper.fail("no spare pickaxe handed over: " + allWords(a)); return; }
                    helper.assertTrue(a.countMatching(st -> st.is(Items.IRON_PICKAXE)) == 0, "its spare, not a copy");
                    Kit.log("[VT] pam12 tool handed over after " + at[0] + " ticks: " + lastWords(a));
                    phase[0] = 1; at[0] = 0;
                }
                case 1 -> {
                    int loose = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(x, y - 2, Z - 2, x + 9, y + 3, Z + 6), e -> e.getItem().is(Items.COBBLESTONE)).size();
                    if (loose > 0) { if (at[0] > 1200) helper.fail("loot left lying: " + loose + "; " + allWords(a)); return; }
                    helper.assertTrue(a.countMatching(st -> st.is(Items.COBBLESTONE)) >= 15, "in its pack: " + a.countMatching(st -> st.is(Items.COBBLESTONE)));
                    Kit.log("[VT] pam12 loot picked up after " + at[0] + " ticks");
                    r.autoOff.add("tool");                                // switched off: it leaves the tool be
                    ItemStack again = new ItemStack(Items.IRON_PICKAXE);
                    again.setDamageValue(again.getMaxDamage() - 3);
                    p.getInventory().clearContent();
                    p.setItemSlot(EquipmentSlot.MAINHAND, again);
                    a.insertGiven(new ItemStack(Items.IRON_PICKAXE));
                    a.mind.toolWarned = -100000;
                    phase[0] = 2; at[0] = 0;
                }
                case 2 -> {
                    if (at[0] < 400) {
                        helper.assertTrue(a.countMatching(st -> st.is(Items.IRON_PICKAXE)) == 1, "switched off: kept its spare");
                        return;
                    }
                    phase[0] = 3;
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    /** pam13: building goes at a hand's pace too: thirty blocks placed (two small walls) never quicker than half a second
     *  apart on average, and no two in the same tick, at level 10 as at level 1. */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "pam13_place_pace")
    public static void pam13_place_pace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_536_000, y = ground(level, x);
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = 0; dy <= 4; dy++)
            level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), Blocks.AIR.defaultBlockState(), 2);
        ServerPlayer p = player(helper, level, new BlockPos(x - 6, y, Z - 6));
        PlayerAssistantEntity a = assistant(helper, p);
        a.setPaXpForTests(PaLevels.xpFor(10));
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 64));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        java.util.List<Long> placed = new java.util.ArrayList<>();
        int[] seen = {0}, phase = {0};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            a.setYRot(0); a.setYHeadRot(0); a.yBodyRot = 0;
            a.enqueue(com.jrpetty.mcassistant.entity.Job.build("wall"));
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 0) return;
            int n = 0;
            for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = 0; dy <= 4; dy++)
                if (level.getBlockState(new BlockPos(x + dx, y + dy, Z + dz)).is(Blocks.COBBLESTONE)) n++;
            for (int k = seen[0]; k < n; k++) placed.add(level.getGameTime());
            seen[0] = Math.max(seen[0], n);
            if (phase[0] == 1 && n >= 15 && a.peekJob() == null && a.task == null) {       // the first wall up: a second, the other way
                a.setYRot(180); a.setYHeadRot(180); a.yBodyRot = 180;
                a.enqueue(com.jrpetty.mcassistant.entity.Job.build("wall"));
                phase[0] = 2;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(placed.size() >= 30, "thirty placed: " + placed.size());
            long span = placed.get(29) - placed.get(0);
            int sameTick = 0;
            for (int i = 1; i < 30; i++) if (placed.get(i).equals(placed.get(i - 1))) sameTick++;
            long firstWall = placed.get(14) - placed.get(0);
            helper.assertTrue(firstWall >= 14 * 10, "fifteen placements took at least 14 x 0.5 s: " + firstWall);
            helper.assertTrue(sameTick == 0, "never two in one tick: " + sameTick);
            Kit.log("[VT] pam13 thirty placements over " + span + " ticks; the first wall's fifteen over " + firstWall + " ("
                + String.format(java.util.Locale.ROOT, "%.2f", firstWall / 14.0 / 20.0) + " s each)");
        });
    }

    /** pam14: the trident from level 7: with no bow, a Loyalty trident is raised, thrown at a husk a dozen blocks off,
     *  and caught on its way back; at level 6 it stays in the pack. */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "pam14_trident")
    public static void pam14_trident(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_539_000, y = ground(level, x);
        for (int dx = -2; dx <= 14; dx++) for (int dy = 0; dy <= 3; dy++) level.setBlock(new BlockPos(x + dx, y + dy, Z), Blocks.AIR.defaultBlockState(), 3);
        ServerPlayer p = player(helper, level, new BlockPos(x - 4, y, Z + 4));
        PlayerAssistantEntity a = assistant(helper, p);
        a.removeMatching(st -> st.is(Items.BOW) || st.is(Items.CROSSBOW) || st.is(Items.ARROW), 999);
        var ench = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        ItemStack tri = new ItemStack(Items.TRIDENT);
        tri.enchant(ench.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOYALTY), 3);
        a.insertGiven(tri);
        a.setPaXpForTests(PaLevels.xpFor(6));
        net.minecraft.world.entity.monster.Husk husk = EntityType.HUSK.create(level);
        husk.moveTo(x + 12.5, y, Z + 0.5, 90, 0);
        husk.setNoAi(true);
        husk.setHealth(husk.getMaxHealth());
        level.addFreshEntity(husk);
        int[] phase = {0}, at = {0};
        helper.onEachTick(() -> {
            at[0]++;
            switch (phase[0]) {
                case 0 -> {
                    if (at[0] < 10) return;
                    a.teleportTo(x + 0.5, y, Z + 0.5);
                    a.setTarget(husk);
                    if (at[0] < 120) { helper.assertTrue(a.mind.tridentThrows == 0, "level 6: no throw"); return; }
                    a.setPaXpForTests(PaLevels.xpFor(7));
                    a.setTarget(null);
                    a.setTarget(husk);
                    phase[0] = 1; at[0] = 0;
                }
                case 1 -> {
                    if (a.getTarget() == null && husk.isAlive()) a.setTarget(husk);
                    boolean hit = husk.getHealth() < husk.getMaxHealth() || !husk.isAlive();
                    if (a.mind.tridentCatches == 0 || !hit || a.mind.trident != null) { if (at[0] > 1000) helper.fail("no hit and catch: throws " + a.mind.tridentThrows + ", caught " + a.mind.tridentCatches + ", hand " + a.getMainHandItem()); return; }
                    helper.assertTrue(a.getMainHandItem().is(Items.TRIDENT) || a.countMatching(st -> st.is(Items.TRIDENT)) == 1, "caught: back in hand or pack");
                    helper.assertTrue(husk.getHealth() < husk.getMaxHealth() || !husk.isAlive(), "the husk was hit at least once in " + a.mind.tridentThrows + " throws");
                    Kit.log("[VT] pam14 trident: " + a.mind.tridentThrows + " throws, " + a.mind.tridentCatches + " caught, husk at " + husk.getHealth());
                    phase[0] = 2;
                    helper.succeed();
                }
                default -> { }
            }
        });
    }
}
