package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.pa.PaConfig;
import com.jrpetty.mcassistant.pa.PaData;
import com.jrpetty.mcassistant.pa.PaField;
import com.jrpetty.mcassistant.pa.PaFishHook;
import com.jrpetty.mcassistant.pa.PaKnack;
import com.jrpetty.mcassistant.pa.PaLevels;
import com.jrpetty.mcassistant.pa.PaLife;
import com.jrpetty.mcassistant.pa.PaLines;
import com.jrpetty.mcassistant.pa.PaMemoryAct;
import com.jrpetty.mcassistant.pa.PaMineLock;
import com.jrpetty.mcassistant.pa.PaPersona;
import com.jrpetty.mcassistant.pa.PaServer;
import com.jrpetty.mcassistant.pa.PaSlips;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Set;

/**
 * [pa38] The assistant's rod, throws, mace, enchanting table, trading and learner's slips, as a player meets them: a real
 * hook catching real fish from the game's loot table (into a chest when asked), a snowball that turns a zombie off its
 * player, the rod's pull, the mace's smash from a wind charge and from a pillar (and the pillar taken down), the table's
 * own offers judged and paid for in levels and lapis, bookshelves set where they count, a villager's prices (its player's
 * standing, a cheaper villager next door), a zombie villager cured in its player's name, a learner's slip put right, and
 * a refused idle take that says why and how to allow it.
 *
 * <p>Each on its own ground, x 10_000_000 up (3000 apart), z 70000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PaFieldGameTests {

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
        PaSlips.testing = false;
        PaSlips.testDice = null;
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        Kit.live(level, x, Z, 40);
        int y = Kit.surface(level, x, Z).getY();
        for (int dx = -36; dx <= 36; dx++) for (int dz = -36; dz <= 36; dz++) {
            for (int dy = 0; dy < 14; dy++) level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), Blocks.AIR.defaultBlockState(), 2);
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

    private static PlayerAssistantEntity assistant(GameTestHelper helper, ServerPlayer p, int level) {
        PaServer.act(p, "summon", new CompoundTag());
        PlayerAssistantEntity a = PaLife.find(p);
        helper.assertTrue(a != null, "an assistant came");
        PaData.Rec r = a.rec();
        r.confirm = false;
        r.autoHelp = false;
        a.setPaXpForTests(PaLevels.xpFor(level));
        return a;
    }

    private static void say(ServerPlayer p, PlayerAssistantEntity a, String text, BlockPos look, int ent) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        if (look != null) { t.putInt("bx", look.getX()); t.putInt("by", look.getY()); t.putInt("bz", look.getZ()); }
        if (ent >= 0) t.putInt("ent", ent);
        PaServer.act(p, "talk", t);
        Kit.log("[VT] pf said to " + a.displayNameCap() + ": \"" + text + "\" -> " + lastWords(a));
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

    /** Did it say one of a key's lines (any corner), filled as it fills them? */
    private static boolean saidKey(PlayerAssistantEntity a, String key) {
        PaData.Rec r = a.rec();
        if (r == null) return false;
        for (PaData.Line l : r.log) for (String corner : new String[]{"", "@gloomy", "@serious"}) for (String raw : PaLines.get(key + corner)) {
            String lit = raw.replaceAll("\\{[a-z]+\\}", "\u0000");
            String[] bits = lit.split("\u0000");
            String longest = "";
            for (String b : bits) if (b.trim().length() > longest.length()) longest = b.trim();
            if (longest.length() >= 6 && l.text().toLowerCase(java.util.Locale.ROOT).contains(longest.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    private static void pond(ServerLevel level, int x, int y) {
        for (int dx = 3; dx <= 11; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -3; dy <= -1; dy++)
            level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), Blocks.WATER.defaultBlockState(), 2);
    }

    /** A dice that always rolls low: every chance it is given comes up (a slip, where the level allows one). */
    static final class LowDice extends java.util.Random {
        @Override public double nextDouble() { return 0.0; }
    }

    // ================================================================== fishing

    /** pf01: below level 3 it says it can't use a rod yet; at level 3 "catch 3 fish" casts a real hook (PaFishHook) into
     *  open water, waits the game's bite times and lands three fish from the game's loot table into its pack. */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "pf01_fish")
    public static void pf01_fish(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_000_000, y = ground(level, x);
        pond(level, x, y);
        ServerPlayer p = player(helper, level, new BlockPos(x - 4, y, Z + 4));
        PlayerAssistantEntity a = assistant(helper, p, 2);
        a.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        boolean[] hookSeen = {false};
        int[] phase = {0}, lure = {-1};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            say(p, a, "catch 3 fish", null, -1);
            helper.assertTrue(lastWords(a).contains("3"), "level 2: it can't use the rod yet, and says level 3: " + lastWords(a));
            a.setPaXpForTests(PaLevels.xpFor(3));
            say(p, a, "catch 3 fish", null, -1);
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 0) return;
            for (PaFishHook h : level.getEntitiesOfClass(PaFishHook.class, a.getBoundingBox().inflate(16))) if (h.getOwner() == a) hookSeen[0] = true;
        });
        helper.succeedWhen(() -> {
            int fish = a.countMatching(s -> s.is(ItemTags.FISHES));
            PaField f = a.mind.field;
            helper.assertTrue(fish >= 3, "three fish in its pack: " + fish + " (casts " + f.casts + ", lost " + f.lost + ", early " + f.early + ")");
            helper.assertTrue(hookSeen[0], "a real hook on its line");
            helper.assertTrue(f.casts >= 3, "a cast a fish at least: " + f.casts);
            Kit.log("[VT] pf01 " + fish + " fish in " + f.casts + " casts; lost " + f.lost + ", early " + f.early + ", treasure " + f.treasure + ", junk " + f.junk
                + ", missed casts " + f.missedCasts + "; lure +" + PaKnack.lureTicks(3) + " ticks, luck +" + PaKnack.luck(3));
        });
    }

    /** pf02: "catch 2 fish and put them in this chest" at a chest: caught on its rod, carried there by the logistics job. */
    @GameTest(template = EMPTY, timeoutTicks = 8000, batch = "pf02_fish_chest")
    public static void pf02_fish_chest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_003_000, y = ground(level, x);
        pond(level, x, y);
        BlockPos chest = new BlockPos(x - 3, y, Z - 2);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.EAST), 3);
        ServerPlayer p = player(helper, level, new BlockPos(x - 4, y, Z));
        PlayerAssistantEntity a = assistant(helper, p, 3);
        a.insertGiven(new ItemStack(Items.FISHING_ROD));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            say(p, a, "catch 2 fish and put them in this chest", chest, -1);
        });
        helper.succeedWhen(() -> {
            int in = 0;
            if (level.getBlockEntity(chest) instanceof ChestBlockEntity c)
                for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(ItemTags.FISHES)) in += c.getItem(i).getCount();
            helper.assertTrue(in >= 2, "two fish in the chest: " + in + " | " + allWords(a));
            Kit.log("[VT] pf02 " + in + " fish caught and put in the chest; casts " + a.mind.field.casts);
        });
    }

    // ================================================================== throws and the rod

    /** pf03: a zombie after its hurt player, the assistant (level 3, snowballs, no bow) throws: the zombie turns on it. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pf03_snowball")
    public static void pf03_snowball(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_006_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x + 10, y, Z));
        p.setHealth(8.0F);
        PlayerAssistantEntity a = assistant(helper, p, 3);
        a.removeMatching(s -> s.is(Items.BOW) || s.is(Items.ARROW), 999);
        a.insertGiven(new ItemStack(Items.SNOWBALL, 16));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        Zombie[] z = {null};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            Zombie zz = EntityType.ZOMBIE.create(level);
            zz.moveTo(x + 9.5, y, Z + 8.5, 0, 0);
            zz.setPersistenceRequired();
            level.addFreshEntity(zz);
            zz.setTarget(p);
            z[0] = zz;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(z[0] != null, "the zombie");
            if (z[0].getTarget() == p && z[0].distanceTo(p) < 3) p.setHealth(8.0F);           // keep its player standing
            PaField f = a.mind.field;
            helper.assertTrue(f.throwsDone >= 1, "it threw");
            helper.assertTrue(z[0].getTarget() == a, "the zombie turned on it: " + z[0].getTarget());
            helper.assertTrue(a.countMatching(s -> s.is(Items.SNOWBALL)) < 16, "snowballs from its own pack");
            Kit.log("[VT] pf03 " + f.throwsDone + " thrown, " + f.throwHits + " hit; the zombie turned on it; " + allWords(a));
        });
    }

    /** pf04: "throw snowballs at that" at a pig: refused at level 2 (the ladder), done at level 3 (the pig is hit by it). */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pf04_throw_order")
    public static void pf04_throw_order(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_009_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 2, y, Z + 2));
        PlayerAssistantEntity a = assistant(helper, p, 2);
        a.insertGiven(new ItemStack(Items.SNOWBALL, 16));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        Pig[] pig = {null};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            Pig pg = EntityType.PIG.create(level);
            pg.moveTo(x + 9.5, y, Z + 0.5, 0, 0);
            pg.setNoAi(true);
            level.addFreshEntity(pg);
            pig[0] = pg;
            say(p, a, "throw snowballs at that", null, pg.getId());
            helper.assertTrue(a.mind.field.throwAt == null, "level 2: not yet");
            a.setPaXpForTests(PaLevels.xpFor(3));
            say(p, a, "throw snowballs at that", null, pg.getId());
            helper.assertTrue(a.mind.field.throwAt != null, "level 3: on it");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(pig[0] != null && pig[0].getLastHurtByMob() == a, "the pig was hit by its snowball");
            Kit.log("[VT] pf04 ordered throws " + a.mind.field.throwsDone + ", hits " + a.mind.field.throwHits);
        });
    }

    /** pf05: "hook that" at a pig six blocks off: the rod's hook catches it and the tug pulls it towards the assistant. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pf05_hook")
    public static void pf05_hook(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_012_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 2, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p, 3);
        a.insertGiven(new ItemStack(Items.FISHING_ROD));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        Pig[] pig = {null};
        double[] start = {0}, pulledTo = {99};
        int[] pullAt = {Integer.MAX_VALUE};
        helper.onEachTick(() -> {
            if (pig[0] == null || a.mind.field.pulls < 1) return;
            if (pullAt[0] == Integer.MAX_VALUE) pullAt[0] = a.tickCount;
            if (a.tickCount - pullAt[0] <= 20) pulledTo[0] = Math.min(pulledTo[0], pig[0].distanceTo(a));   // nearest it came in the tug's second
        });
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            Pig pg = EntityType.PIG.create(level);
            pg.moveTo(x + 6.5, y, Z + 0.5, 0, 0);                                  // with its wits (a pig with no AI ignores a tug)
            level.addFreshEntity(pg);
            pig[0] = pg;
            start[0] = pg.distanceTo(a);
            say(p, a, "hook that", null, pg.getId());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(pig[0] != null && a.mind.field.pulls >= 1, "a pull: " + a.mind.field.pulls);
            helper.assertTrue(a.tickCount - pullAt[0] >= 12, "a moment for the tug to tell");
            double now = pulledTo[0];
            helper.assertTrue(now < start[0] - 1.0, "pulled in: " + start[0] + " -> " + now);
            Kit.log("[VT] pf05 pulled the pig from " + String.format("%.1f", start[0]) + " to " + String.format("%.1f", now));
        });
    }

    // ================================================================== the mace

    private static Zombie bigTarget(ServerLevel level, double x, double y, double z) {
        Zombie zz = EntityType.ZOMBIE.create(level);
        zz.moveTo(x, y, z, 0, 0);
        zz.setNoAi(true);
        zz.setPersistenceRequired();
        zz.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80);
        zz.setHealth(80);
        level.addFreshEntity(zz);
        return zz;
    }

    /** pf06: at level 9, "smash that" with a mace and a wind charge: up by the blast, down on the target, the game's mace
     *  damage from the fall, and no fall damage for it. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pf06_mace_wind")
    public static void pf06_mace_wind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_015_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 6, y, Z + 6));
        PlayerAssistantEntity a = assistant(helper, p, 9);
        a.insertGiven(new ItemStack(Items.MACE));
        a.insertGiven(new ItemStack(Items.WIND_CHARGE, 4));
        Zombie[] t = {null};
        float[] hp = {0};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            t[0] = bigTarget(level, x + 2.5, y, Z + 0.5);
            hp[0] = a.getHealth();
            say(p, a, "smash that", null, t[0].getId());
        });
        helper.succeedWhen(() -> {
            PaField f = a.mind.field;
            helper.assertTrue(t[0] != null && f.smashes >= 1, "a smash landed (misses " + f.smashMisses + ")");
            helper.assertTrue(f.lastSmashFall > 1.5F && f.lastSmashDamage > 9, "from a height, harder than a plain blow: " + f.lastSmashDamage + " after " + f.lastSmashFall);
            helper.assertTrue(a.onGround() && a.getHealth() >= hp[0] - 0.01F, "no fall damage when the blow lands: " + hp[0] + " -> " + a.getHealth());
            helper.assertTrue(a.countMatching(s -> s.is(Items.WIND_CHARGE)) <= 3, "its own wind charges used");
            Kit.log("[VT] pf06 smash " + f.lastSmashDamage + " after a fall of " + f.lastSmashFall);
        });
    }

    /** pf07: at level 9 with no wind charges it pillars up four, smashes, and takes its pillar down after. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pf07_mace_pillar")
    public static void pf07_mace_pillar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_018_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 6, y, Z + 6));
        PlayerAssistantEntity a = assistant(helper, p, 9);
        a.insertGiven(new ItemStack(Items.MACE));
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 16));
        Zombie[] t = {null};
        boolean[] pillar = {false};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            t[0] = bigTarget(level, x + 2.5, y, Z + 0.5);
            say(p, a, "smash that", null, t[0].getId());
        });
        helper.onEachTick(() -> { if (level.getBlockState(new BlockPos(x, y + 2, Z)).is(Blocks.COBBLESTONE)) pillar[0] = true; });
        helper.succeedWhen(() -> {
            PaField f = a.mind.field;
            helper.assertTrue(pillar[0], "it pillared up");
            helper.assertTrue(f.smashes + f.smashMisses >= 1, "came down for the smash");
            if (t[0] != null && t[0].isAlive()) t[0].discard();
            int left = 0;
            for (int dy = 0; dy < 6; dy++) if (level.getBlockState(new BlockPos(x, y + dy, Z)).is(Blocks.COBBLESTONE)) left++;
            helper.assertTrue(left == 0, "its pillar taken down: " + left + " left");
            Kit.log("[VT] pf07 pillar smash: smashes " + f.smashes + ", misses " + f.smashMisses + ", last " + f.lastSmashDamage + "; pillar tidied");
        });
    }

    // ================================================================== enchanting

    private static void table(ServerLevel level, BlockPos t, int shelves) {
        level.setBlock(t, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
        int n = 0;
        for (BlockPos off : net.minecraft.world.level.block.EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (n >= shelves) break;
            if (off.getY() != 0) continue;
            level.setBlock(t.offset(off), Blocks.BOOKSHELF.defaultBlockState(), 3);
            n++;
        }
    }

    /** pf08: at level 10, with thirty levels and lapis at a full table, "enchant my sword": the table's own offers, a slot's
     *  price paid in levels and lapis (never the requirement), and a fresh seed after. At level 5 it's not yet. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pf08_enchant")
    public static void pf08_enchant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_021_000, y = ground(level, x);
        BlockPos t = new BlockPos(x + 3, y, Z);
        table(level, t, 16);
        ServerPlayer p = player(helper, level, new BlockPos(x - 3, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p, 5);
        a.insertGiven(new ItemStack(Items.IRON_SWORD));
        a.insertGiven(new ItemStack(Items.LAPIS_LAZULI, 10));
        a.awardXp(1395);
        int[] before = {0}, seed = {0};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            say(p, a, "enchant my sword", null, -1);
            helper.assertTrue(lastWords(a).contains("6"), "level 5: the table's not for it yet (level 6): " + lastWords(a));
            a.setPaXpForTests(PaLevels.xpFor(10));
            before[0] = a.getXp();
            seed[0] = a.mind.field.enchantSeed;
            say(p, a, "enchant my sword", null, -1);
        });
        helper.succeedWhen(() -> {
            ItemStack sword = ItemStack.EMPTY;
            for (ItemStack s : a.getInventoryItems()) if (s.is(Items.IRON_SWORD)) sword = s;
            if (a.getMainHandItem().is(Items.IRON_SWORD)) sword = a.getMainHandItem();
            helper.assertTrue(sword.isEnchanted(), "the sword enchanted");
            PaField f = a.mind.field;
            int lapis = a.countMatching(s -> s.is(Items.LAPIS_LAZULI));
            int spent = 10 - lapis;
            int lvBefore = com.jrpetty.mcassistant.pa.PaEnchantJudge.levelOf(before[0]), lvAfter = com.jrpetty.mcassistant.pa.PaEnchantJudge.levelOf(a.getXp());
            helper.assertTrue(spent >= 1 && spent <= 3 && lvBefore - lvAfter == spent, "paid the slot (" + spent + " lapis, " + (lvBefore - lvAfter) + " levels), never the requirement");
            helper.assertTrue(f.enchants == 1 && f.enchantSeed != seed[0], "one enchantment and a fresh seed");
            Kit.log("[VT] pf08 picked " + f.lastPick + " (slot:requirement:what); paid " + spent + " levels and lapis; " + allWords(a));
        });
    }

    /** pf09: "put bookshelves round the table" with twenty in its pack and a torch in one gap: at level 6 it stops at
     *  fifteen and never puts one behind the torch (the table reads 15); at level 1 it just rings the table. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pf09_shelves")
    public static void pf09_shelves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_024_000, y = ground(level, x);
        BlockPos t = new BlockPos(x + 3, y, Z);
        level.setBlock(t, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
        level.setBlock(t.offset(1, 0, 0), Blocks.TORCH.defaultBlockState(), 3);          // a gap blocked
        ServerPlayer p = player(helper, level, new BlockPos(x - 3, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p, 6);
        a.insertGiven(new ItemStack(Items.BOOKSHELF, 20));
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            say(p, a, "put bookshelves round the table", null, -1);
        });
        helper.succeedWhen(() -> {
            int placed = 20 - a.countMatching(s -> s.is(Items.BOOKSHELF));
            helper.assertTrue(a.peekJob() == null && placed > 0, "placed: " + placed);
            float power = 0;
            for (BlockPos off : net.minecraft.world.level.block.EnchantingTableBlock.BOOKSHELF_OFFSETS)
                if (net.minecraft.world.level.block.EnchantingTableBlock.isValidBookShelf(level, t, off)) power++;
            helper.assertTrue(power == 15 && placed == 15, "fifteen placed, fifteen counting: " + placed + " / " + power);
            helper.assertTrue(!level.getBlockState(t.offset(2, 0, 0)).is(Blocks.BOOKSHELF), "none behind the torch");
            Kit.log("[VT] pf09 level 6: " + placed + " shelves, table reads " + power + "; " + lastWords(a));
        });
    }

    // ================================================================== trading and the cure

    private static Villager farmer(ServerLevel level, double x, double y, double z, int price) {
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(x, y, z, 0, 0);
        v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.FARMER).setLevel(2));
        v.setVillagerXp(10);
        v.setNoAi(true);
        v.setPersistenceRequired();
        level.addFreshEntity(v);
        v.getOffers().clear();
        v.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD, price), new ItemStack(Items.BREAD, 6), 12, 1, 0.05F));
        return v;
    }

    /** pf10: "buy bread from that villager": at level 1 it buys where it's sent; at level 5 it finds the villager next door
     *  three emeralds cheaper; its player's standing in the village (the game's gossip) brings the price down further. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pf10_trade")
    public static void pf10_trade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_027_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 3, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p, 5);
        a.insertGiven(new ItemStack(Items.EMERALD, 30));
        Villager[] v = {null, null};
        int[] phase = {0}, em = {30};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            v[0] = farmer(level, x + 4.5, y, Z + 0.5, 6);
            v[1] = farmer(level, x + 4.5, y, Z + 5.5, 3);
            v[1].getGossips().add(p.getUUID(), net.minecraft.world.entity.ai.gossip.GossipType.MAJOR_POSITIVE, 20);
            say(p, a, "buy 6 bread from that villager", null, v[0].getId());
            phase[0] = 1;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] == 1 && a.countMatching(s -> s.is(Items.BREAD)) >= 6, "bought bread");
            int spent = em[0] - a.countMatching(s -> s.is(Items.EMERALD));
            helper.assertTrue(spent < 3, "next door, and cheaper again for its player's good name: " + spent + " emeralds");
            helper.assertTrue(v[1].getOffers().get(0).getUses() == 1 && v[0].getOffers().get(0).getUses() == 0, "bought next door");
            helper.assertTrue(v[1].getOffers().get(0).getSpecialPriceDiff() == 0, "the villager's prices back as they were");
            Kit.log("[VT] pf10 level 5 bought 6 bread next door for " + spent + " (6 where it was sent); " + allWords(a));
        });
    }

    /** pf11: "cure that zombie villager" at level 7 with a golden apple (it has weakness): the game's cure starts, in its
     *  player's name; at level 6 it isn't able yet. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pf11_cure")
    public static void pf11_cure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_030_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 3, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p, 6);
        a.insertGiven(new ItemStack(Items.GOLDEN_APPLE, 2));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        ZombieVillager[] z = {null};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            ZombieVillager zv = EntityType.ZOMBIE_VILLAGER.create(level);
            zv.moveTo(x + 5.5, y, Z + 0.5, 0, 0);
            zv.setNoAi(true);
            zv.setPersistenceRequired();
            level.addFreshEntity(zv);
            zv.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 2400));
            z[0] = zv;
            say(p, a, "cure that zombie villager", null, zv.getId());
            helper.assertTrue(a.mind.field.cureOrder == null, "level 6: not yet");
            a.setPaXpForTests(PaLevels.xpFor(7));
            say(p, a, "cure that zombie villager", null, zv.getId());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(z[0] != null && z[0].isConverting(), "the cure is under way");
            CompoundTag tag = new CompoundTag();
            z[0].addAdditionalSaveData(tag);
            helper.assertTrue(tag.hasUUID("ConversionPlayer") && tag.getUUID("ConversionPlayer").equals(p.getUUID()), "in its player's name");
            helper.assertTrue(a.countMatching(s -> s.is(Items.GOLDEN_APPLE)) == 1, "one golden apple given");
            helper.assertTrue(a.getTarget() != z[0], "never a target meanwhile");
            Kit.log("[VT] pf11 cure started in its player's name; " + lastWords(a));
        });
    }

    // ================================================================== the learner's slips and the refused chest

    /** pf12: a learner (level 1, dice that always come up) building a wall sets one block a cell off and puts it right with a
     *  word; picks up the wrong tool for stone and switches; at level 8 the same dice bring no slip at all. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pf12_slips")
    public static void pf12_slips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_033_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 6, y, Z - 6));
        PlayerAssistantEntity a = assistant(helper, p, 1);
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 64));
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        PaSlips.testing = true;
        PaSlips.testDice = new LowDice();
        int[] phase = {0};
        boolean[] stray = {false};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            a.setYRot(0); a.setYHeadRot(0); a.yBodyRot = 0;
            a.enqueue(Job.build("wall"));
            phase[0] = 1;
        });
        helper.onEachTick(() -> { if (a.mind.field.fixAt != null) stray[0] = true; });
        helper.succeedWhen(() -> {
            PaField f = a.mind.field;
            helper.assertTrue(phase[0] == 1 && f.misplaced >= 1 && stray[0], "a block set a cell off");
            helper.assertTrue(f.fixAt == null, "and put right");
            helper.assertTrue(saidKey(a, "field.slip_block"), "with its word: " + allWords(a));
            // the wrong tool, then the right one
            a.mind.field.lastSlip = -100000;
            a.insertGiven(new ItemStack(Items.WOODEN_SHOVEL));
            a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
            a.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            a.equipBestTool(Blocks.STONE.defaultBlockState());
            helper.assertTrue(a.getMainHandItem().is(Items.WOODEN_SHOVEL), "the wrong tool first: " + a.getMainHandItem());
            a.mind.field.toolSwitchAt = a.tickCount - 1;
            a.equipBestTool(Blocks.STONE.defaultBlockState());
            helper.assertTrue(a.getMainHandItem().is(Items.STONE_PICKAXE), "then the right one: " + a.getMainHandItem());
            // level 8: the same dice, no slips
            a.setPaXpForTests(PaLevels.xpFor(8));
            a.mind.field.lastSlip = -100000;
            int before = f.misplaced + f.wrongTools;
            a.mind.field.lastToolBlock = null;
            a.equipBestTool(Blocks.STONE.defaultBlockState());
            com.jrpetty.mcassistant.pa.PaSlips.afterPlace(a, new BlockPos(x, y + 3, Z + 3), Blocks.COBBLESTONE.defaultBlockState());
            helper.assertTrue(f.misplaced + f.wrongTools == before && a.getMainHandItem().is(Items.STONE_PICKAXE), "level 8: no slips");
            PaSlips.testing = false;
            PaSlips.testDice = null;
            Kit.log("[VT] pf12 misplaced " + f.misplaced + ", wrong tools " + f.wrongTools + "; " + allWords(a));
        });
    }

    /** pf13: on its own account (no order) it wants logs from a chest its player hasn't allowed: it says that chest isn't
     *  one it may take from and how to allow it (never "that chest had no logs"); allowed, it takes them. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pf13_refused_chest")
    public static void pf13_refused_chest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 10_036_000, y = ground(level, x);
        BlockPos chest = new BlockPos(x + 3, y, Z);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(chest) instanceof ChestBlockEntity c) c.setItem(0, new ItemStack(Items.OAK_LOG, 16));
        ServerPlayer p = player(helper, level, new BlockPos(x - 3, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p, 1);
        a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
        int[] phase = {0};
        helper.runAfterDelay(10, () -> {
            a.teleportTo(x + 0.5, y, Z + 0.5);
            a.enqueue(Job.withdraw("log", 8));                                               // the engine's own idle top-up
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 1 && a.peekJob() == null && a.tickCount > 40) {
                helper.assertTrue(saidKey(a, "logi.not_allowed"), "says the chest isn't allowed, and how: " + allWords(a));
                helper.assertTrue(!allWords(a).contains("had no"), "never 'had no logs': " + allWords(a));
                say(p, a, "you can take from this chest", chest, -1);
                a.enqueue(Job.withdraw("log", 8));
                phase[0] = 2;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] == 2 && a.countMatching(s -> s.is(Items.OAK_LOG)) >= 8, "allowed: it takes them");
            Kit.log("[VT] pf13 " + allWords(a));
        });
    }
}
