package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.pa.PaConfig;
import com.jrpetty.mcassistant.pa.PaData;
import com.jrpetty.mcassistant.pa.PaLevels;
import com.jrpetty.mcassistant.pa.PaLife;
import com.jrpetty.mcassistant.pa.PaLogistics;
import com.jrpetty.mcassistant.pa.PaPace;
import com.jrpetty.mcassistant.pa.PaServer;
import com.jrpetty.mcassistant.pa.PaStore;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

/**
 * [pa36] The assistant's chests, gathering and logistics, as a player uses them: "gather 100 stone and put it in this
 * chest" (and it takes a player's time: the vanilla break time for every block, plus the walking); its player's cobble
 * handed over into a pinged chest; a chest kept stocked, topped back up after some is taken; fuel and smelting at a
 * furnace's own speed; fetching from its player's known chests; a full chest overflowing into the next; another
 * player's chest refused; a haul loop with a stop that says what it moved; "how much iron do we have" counted across
 * chests, its pack and its player; the mining ladder (iron at level 3 refused, at level 4 done); and crafting: an iron
 * pickaxe from parts handed over, the full chain from logs and ore, 64 torches, an exact shortfall, any planks, and
 * crafted torches put in a pinged chest.
 *
 * <p>Each on its own ground, x 9_600_000 to 9_660_000 (3000 apart), z 70000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PaLogisticsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    private static int ground(ServerLevel level, int x) {
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        PaConfig.perPlayerForTests(null);
        PaConfig.respawnSecondsForTests(null);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        Kit.live(level, x, Z, 40);
        int y = Kit.surface(level, x, Z).getY();
        for (int dx = -36; dx <= 36; dx++) {
            for (int dz = -36; dz <= 36; dz++) {
                for (int dy = 0; dy < 12; dy++) level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
        return y;
    }

    private static ServerPlayer player(GameTestHelper helper, ServerLevel level, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
        p.getInventory().clearContent();
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
        for (int i = 0; i < a.getInventoryItems().size(); i++) a.getInventoryItems().set(i, ItemStack.EMPTY);
        PaLogistics.forgetSeenForTests(p.getUUID());
        return a;
    }

    private static void say(ServerPlayer p, PlayerAssistantEntity a, String text, BlockPos look) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        if (look != null) { t.putInt("bx", look.getX()); t.putInt("by", look.getY()); t.putInt("bz", look.getZ()); }
        PaServer.act(p, "talk", t);
        Kit.log("[VT] pl said \"" + text + "\" -> " + lastWords(a) + " | plan " + a.plan.total() + " " + (a.plan.all().isEmpty() ? "" : a.plan.all().get(0).label));
    }

    private static void hash(ServerPlayer p, String text) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        PaServer.act(p, "talk", t);
    }

    private static String lastWords(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        return r == null || r.log.isEmpty() ? "" : r.log.peekLast().text();
    }

    private static String allWords(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        StringBuilder b = new StringBuilder();
        if (r != null) for (PaData.Line l : r.log) b.append(l.who()).append(':').append(l.text()).append(" | ");
        return b.toString();
    }

    private static boolean saidAny(PlayerAssistantEntity a, String... bits) {
        String said = allWords(a).toLowerCase(java.util.Locale.ROOT);
        for (String b : bits) if (said.contains(b.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    private static int in(ServerLevel level, BlockPos chest, Item item) {
        int n = 0;
        if (level.getBlockEntity(chest) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    private static int pack(PlayerAssistantEntity a, Item item) {
        return a.countMatching(s -> s.is(item));
    }

    private static int on(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    private static BlockPos chest(ServerLevel level, BlockPos at, ServerPlayer owner) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        if (owner != null) {
            String dim = level.dimension().location().toString();
            PaStore.setPlacedBy(dim, at, owner.getUUID());
            PaStore.learn(owner.getUUID(), dim, at, "minecraft:chest", level.getGameTime());
        }
        return at;
    }

    private static void put(ServerLevel level, BlockPos chest, ItemStack s) {
        if (level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) { c.setItem(i, s); c.setChanged(); return; }
        }
    }

    // ================================================================== gathering into a chest

    /** pl01: "gather 100 stone and put it in this chest" ends with exactly 100 in the chest, at a player's pace (vanilla break time for each block, and the walking). */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "pl01_gather_chest")
    public static void pl01_gather_chest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_600_000, y = ground(level, x);
        for (int dx = 8; dx <= 16; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = 0; dy < 2; dy++) level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), Blocks.STONE.defaultBlockState(), 3);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos chest = chest(level, new BlockPos(x + 2, y, Z - 4), p);
        a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
        long t0 = level.getGameTime();
        say(p, a, "gather 100 stone and put it in this chest", chest);
        helper.assertTrue(a.plan.total() == 1, "one step planned: " + a.plan.total() + " (" + lastWords(a) + ")");
        // what a player would take: 100 x the stone pickaxe's vanilla break time on stone
        net.minecraft.world.entity.LivingEntity probe = a;
        ItemStack held = a.getMainHandItem().copy();
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        int each = PaPace.breakTicks(probe, Blocks.STONE.defaultBlockState(), level, new BlockPos(x + 8, y, Z));
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, held);
        String[] hud = {""};
        helper.onEachTick(() -> { if (a.state() != null && a.state().contains("/100")) hud[0] = a.state(); });
        helper.succeedWhen(() -> {
            int n = in(level, chest, Items.COBBLESTONE) + in(level, chest, Items.STONE);
            helper.assertTrue(n >= 100 && a.plan.idle(), "100 stone in the chest: " + n + " (" + a.state() + "; " + lastWords(a) + ")");
            long took = level.getGameTime() - t0;
            Kit.log("[VT] pl01 chest holds " + n + " stone after " + took + " ticks (vanilla floor " + 100 * each + " = 100 x " + each + "); hud seen \"" + hud[0] + "\"; said " + allWords(a));
            helper.assertTrue(n == 100, "exactly 100, not more: " + n);
            helper.assertTrue(took >= 100L * each, "a player's pace: " + took + " ticks >= " + 100 * each);
            helper.assertTrue(saidAny(a, "100 stone", "100 cobblestone"), "it said it was done: " + allWords(a));
        });
    }

    /** pl02: ping a chest, then "put my cobble in this chest": the player's cobble goes in when they press Hand over. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pl02_handover")
    public static void pl02_handover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_603_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos chest = chest(level, new BlockPos(x + 4, y, Z + 3), p);
        p.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        p.getInventory().add(new ItemStack(Items.COBBLESTONE, 30));
        PaLogistics.seen(p, chest);                                          // the ping
        say(p, a, "put all my cobble in this chest", null);
        helper.assertTrue(saidAny(a, "hand over", "take 94", "94 cobblestone"), "it asks to have them handed over: " + lastWords(a));
        hash(p, "#logi hand");
        helper.assertTrue(on(p, Items.COBBLESTONE) == 0 && pack(a, Items.COBBLESTONE) == 94, "handed over: player " + on(p, Items.COBBLESTONE) + ", pack " + pack(a, Items.COBBLESTONE));
        helper.succeedWhen(() -> {
            int n = in(level, chest, Items.COBBLESTONE);
            helper.assertTrue(n == 94 && a.plan.idle(), "94 cobble in the chest: " + n + " (" + lastWords(a) + ")");
            Kit.log("[VT] pl02 chest " + n + "; said " + allWords(a));
        });
    }

    /** pl03: "keep this chest stocked with 32 bread": filled now, and topped back up after the test takes 20. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pl03_stock")
    public static void pl03_stock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_606_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos chest = chest(level, new BlockPos(x + 4, y, Z - 3), p);
        a.insertGiven(new ItemStack(Items.BREAD, 64));
        say(p, a, "keep this chest stocked with 32 bread", chest);
        helper.assertTrue(PaStore.of(p.getUUID()).stock.size() == 1, "a standing job: " + PaStore.of(p.getUUID()).stock.size());
        int[] stage = {0};
        helper.onEachTick(() -> {
            if (stage[0] == 0 && in(level, chest, Items.BREAD) >= 32 && a.plan.idle()) {
                // someone eats 20
                if (level.getBlockEntity(chest) instanceof Container c) {
                    int take = 20;
                    for (int i = 0; i < c.getContainerSize() && take > 0; i++) {
                        ItemStack s = c.getItem(i);
                        if (s.is(Items.BREAD)) { int t = Math.min(take, s.getCount()); s.shrink(t); take -= t; }
                    }
                    c.setChanged();
                }
                for (PaStore.Stock s : PaStore.of(p.getUUID()).stock) s.next = level.getGameTime();   // the check falls due now
                stage[0] = 1;
                Kit.log("[VT] pl03 took 20 bread; chest " + in(level, chest, Items.BREAD));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stage[0] == 1, "first filled to 32 (" + in(level, chest, Items.BREAD) + "; " + lastWords(a) + ")");
            int n = in(level, chest, Items.BREAD);
            helper.assertTrue(n >= 32 && a.plan.idle(), "topped back up to 32: " + n + " (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("[VT] pl03 topped up to " + n + "; pack " + pack(a, Items.BREAD) + "; said " + allWords(a));
        });
    }

    /** pl04: fuel and smelt: raw iron smelted in the pinged furnace at the furnace's own speed (10 s an item), fuel put in; and "fuel this furnace". */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pl04_smelt")
    public static void pl04_smelt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_609_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos f = new BlockPos(x + 4, y, Z + 2);
        level.setBlock(f, Blocks.FURNACE.defaultBlockState(), 3);
        BlockPos f2 = new BlockPos(x + 4, y, Z - 4);
        level.setBlock(f2, Blocks.FURNACE.defaultBlockState(), 3);
        String dim = level.dimension().location().toString();
        PaStore.setPlacedBy(dim, f, p.getUUID());
        PaStore.setPlacedBy(dim, f2, p.getUUID());
        if (level.getBlockEntity(f2) instanceof Container c) { c.setItem(0, new ItemStack(Items.SAND, 8)); c.setChanged(); }
        a.insertGiven(new ItemStack(Items.RAW_IRON, 6));
        a.insertGiven(new ItemStack(Items.COAL, 8));
        long t0 = level.getGameTime();
        say(p, a, "smelt the raw iron in this furnace", f);
        helper.assertTrue(a.plan.total() == 1, "planned: " + lastWords(a));
        boolean[] fuelled = {false};
        helper.onEachTick(() -> {
            if (!fuelled[0] && a.plan.idle() && pack(a, Items.IRON_INGOT) >= 6) {
                fuelled[0] = true;
                say(p, a, "fuel this furnace", f2);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(pack(a, Items.IRON_INGOT) >= 6, "6 ingots: " + pack(a, Items.IRON_INGOT) + " (" + a.state() + "; " + lastWords(a) + ")");
            long took = level.getGameTime() - t0;
            helper.assertTrue(took >= 6 * 200, "the furnace's own time: " + took);
            int fuel = level.getBlockEntity(f2) instanceof Container c ? c.getItem(1).getCount() : 0;
            boolean lit = level.getBlockState(f2).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
            helper.assertTrue(fuelled[0] && (fuel > 0 || lit) && a.plan.idle(), "the second furnace fuelled: " + fuel + " lit " + lit + " (" + lastWords(a) + ")");
            Kit.log("[VT] pl04 6 ingots after " + took + " ticks; furnace 2 fuel " + fuel + "; said " + allWords(a));
        });
    }

    /** pl05: "bring me 20 torches from my chests": found in its player's known chests (not the empty one) and handed over. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl05_fetch")
    public static void pl05_fetch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_612_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        chest(level, new BlockPos(x + 3, y, Z + 6), p);
        BlockPos b = chest(level, new BlockPos(x - 8, y, Z - 6), p);
        put(level, b, new ItemStack(Items.TORCH, 48));
        say(p, a, "bring me 20 torches from my chests", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(on(p, Items.TORCH) == 20 && a.plan.idle(), "20 torches on the player: " + on(p, Items.TORCH) + " (" + a.state() + "; " + lastWords(a) + ")");
            helper.assertTrue(in(level, b, Items.TORCH) == 28, "taken from the chest: " + in(level, b, Items.TORCH));
            Kit.log("[VT] pl05 torches on player " + on(p, Items.TORCH) + "; said " + allWords(a));
        });
    }

    /** pl06: a full chest goes on to the next one: 128 stone into a chest with one free slot, the rest in the chest beside it. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pl06_full_next")
    public static void pl06_full_next(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_615_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos full = chest(level, new BlockPos(x + 4, y, Z + 3), p);
        BlockPos next = chest(level, new BlockPos(x + 6, y, Z + 3), p);
        if (level.getBlockEntity(full) instanceof Container c) { for (int i = 0; i < c.getContainerSize() - 1; i++) c.setItem(i, new ItemStack(Items.DIRT, 64)); c.setChanged(); }
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 64));
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 64));
        say(p, a, "put the stone in that chest", full);
        helper.succeedWhen(() -> {
            int one = in(level, full, Items.COBBLESTONE), two = in(level, next, Items.COBBLESTONE);
            helper.assertTrue(one == 64 && two == 64 && a.plan.idle(), "64 in the full one, 64 in the next: " + one + " / " + two + " (" + lastWords(a) + ")");
            Kit.log("[VT] pl06 " + one + " + " + two + "; said " + allWords(a));
        });
    }

    /** pl07: another player's chest is refused, even pointed at: nothing goes in, and it says whose. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pl07_refuse")
    public static void pl07_refuse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_618_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos theirs = new BlockPos(x + 4, y, Z + 2);
        level.setBlock(theirs, Blocks.CHEST.defaultBlockState(), 3);
        PaStore.setPlacedBy(level.dimension().location().toString(), theirs, UUID.randomUUID());   // placed by someone else
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 32));
        put(level, theirs, new ItemStack(Items.DIAMOND, 3));
        say(p, a, "put the stone in that chest", theirs);
        String said1 = lastWords(a);
        say(p, a, "take the diamonds from that chest", theirs);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(in(level, theirs, Items.COBBLESTONE) == 0 && pack(a, Items.COBBLESTONE) == 32, "nothing went in");
            helper.assertTrue(in(level, theirs, Items.DIAMOND) == 3 && pack(a, Items.DIAMOND) == 0, "nothing came out");
            helper.assertTrue(said1.toLowerCase().contains("someone else") || said1.toLowerCase().contains("not opening") || said1.toLowerCase().contains("belongs")
                || said1.toLowerCase().contains("thief") || said1.toLowerCase().contains("s chest") || said1.toLowerCase().contains("'s"), "it said whose: " + said1);
            Kit.log("[VT] pl07 refused: " + allWords(a));
            helper.succeed();
        });
    }

    /** pl08: a haul loop: "haul from the mine to my storage until I say stop" moves the mine chest's stone in trips; "stop" says what it moved. */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "pl08_haul")
    public static void pl08_haul(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_621_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos mine = chest(level, new BlockPos(x + 22, y, Z + 6), p);
        BlockPos store = chest(level, new BlockPos(x - 3, y, Z - 3), p);
        for (int i = 0; i < 5; i++) put(level, mine, new ItemStack(Items.COBBLESTONE, 64));
        PaData.Rec r = a.rec();
        r.waypoints.put("mine", new BlockPos(x + 21, y, Z + 6));
        r.waypointDims.put("mine", level.dimension().location().toString());
        PaStore.of(p.getUUID()).at(store, level.dimension().location().toString()).storage = true;
        say(p, a, "haul from the mine to my storage until i say stop", null);
        boolean[] stopped = {false};
        helper.onEachTick(() -> {
            if (!stopped[0] && in(level, store, Items.COBBLESTONE) >= 320) {
                stopped[0] = true;
                say(p, a, "stop", null);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stopped[0], "all 320 hauled to storage: " + in(level, store, Items.COBBLESTONE) + " (" + a.state() + "; " + lastWords(a) + ")");
            helper.assertTrue(a.plan.idle() && saidAny(a, "320"), "stopped, saying what it moved: " + allWords(a));
            Kit.log("[VT] pl08 storage " + in(level, store, Items.COBBLESTONE) + "; said " + allWords(a));
        });
    }

    /** pl09: "how much iron do we have?" counts the known chests, its pack and its player, every kind of iron. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pl09_count")
    public static void pl09_count(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_624_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos c1 = chest(level, new BlockPos(x + 4, y, Z + 3), p);
        BlockPos c2 = chest(level, new BlockPos(x - 6, y, Z - 5), p);
        put(level, c1, new ItemStack(Items.IRON_INGOT, 10));
        put(level, c2, new ItemStack(Items.RAW_IRON, 5));
        put(level, c2, new ItemStack(Items.COBBLESTONE, 30));
        a.insertGiven(new ItemStack(Items.IRON_INGOT, 3));
        p.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
        say(p, a, "how much iron do we have?", null);
        String w = lastWords(a);
        helper.assertTrue(w.contains("20 in all"), "20 in all: " + w);
        helper.assertTrue(w.contains("3 on me") && w.contains("2 on you"), "its pack and its player: " + w);
        say(p, a, "find me a chest with raw iron in it", null);
        helper.assertTrue(lastWords(a).toLowerCase().contains("blocks"), "found and said where: " + lastWords(a));
        Kit.log("[VT] pl09 " + allWords(a));
        helper.succeed();
    }

    /** pl10: the mining ladder: "gather 8 iron and put it in this chest" at level 3 is declined (level 4), at level 4 done. */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "pl10_ladder")
    public static void pl10_ladder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_627_000, y = ground(level, x);
        for (int dx = 8; dx <= 11; dx++) for (int dz = -2; dz <= 2; dz++) level.setBlock(new BlockPos(x + dx, y, Z + dz), Blocks.IRON_ORE.defaultBlockState(), 3);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos chest = chest(level, new BlockPos(x + 1, y, Z - 5), p);
        a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
        a.setPaXpForTests(PaLevels.xpFor(3));
        say(p, a, "gather 8 iron and put it in this chest", chest);
        helper.assertTrue(a.veteranLevel() == 3 && a.plan.idle() && lastWords(a).contains("4"), "level 3 declines with the level: " + lastWords(a) + " (level " + a.veteranLevel() + ")");
        a.setPaXpForTests(PaLevels.xpFor(4));
        say(p, a, "gather 8 iron and put it in this chest", chest);
        helper.assertTrue(a.plan.total() == 1, "level 4 starts: " + lastWords(a));
        helper.succeedWhen(() -> {
            int n = in(level, chest, Items.RAW_IRON);
            helper.assertTrue(n >= 8 && a.plan.idle(), "8 raw iron in the chest: " + n + " (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("[VT] pl10 " + n + " raw iron; said " + allWords(a));
        });
    }

    // ================================================================== crafting

    /** pl11: "make me an iron pickaxe" from three ingots and two sticks it was given: crafted at a table and handed over. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl11_craft_given")
    public static void pl11_craft_given(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_630_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        level.setBlock(new BlockPos(x + 3, y, Z + 2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        a.insertGiven(new ItemStack(Items.IRON_INGOT, 3));
        a.insertGiven(new ItemStack(Items.STICK, 2));
        say(p, a, "make me an iron pickaxe", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(on(p, Items.IRON_PICKAXE) == 1 && a.plan.idle(), "an iron pickaxe handed over (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("[VT] pl11 said " + allWords(a));
        });
    }

    /** pl12: the full chain: logs and raw iron, a furnace and coal, to an iron pickaxe (planks, sticks, smelted ingots, the pickaxe). */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "pl12_craft_chain")
    public static void pl12_craft_chain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_633_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        level.setBlock(new BlockPos(x + 3, y, Z + 2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x + 3, y, Z - 2), Blocks.FURNACE.defaultBlockState(), 3);
        a.insertGiven(new ItemStack(Items.OAK_LOG, 2));
        a.insertGiven(new ItemStack(Items.RAW_IRON, 3));
        a.insertGiven(new ItemStack(Items.COAL, 4));
        say(p, a, "craft an iron pickaxe", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(pack(a, Items.IRON_PICKAXE) + (a.getMainHandItem().is(Items.IRON_PICKAXE) ? 1 : 0) >= 1 && a.plan.idle(),
                "an iron pickaxe made from logs and ore (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("[VT] pl12 said " + allWords(a));
        });
    }

    /** pl13: "craft 64 torches" from 16 coal and 2 logs (planks, sticks, torches). */
    @GameTest(template = EMPTY, timeoutTicks = 2000, batch = "pl13_torches")
    public static void pl13_torches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_636_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        level.setBlock(new BlockPos(x + 3, y, Z + 2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        a.insertGiven(new ItemStack(Items.COAL, 16));
        a.insertGiven(new ItemStack(Items.OAK_LOG, 2));
        say(p, a, "craft 64 torches", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(pack(a, Items.TORCH) >= 64 && a.plan.idle(), "64 torches: " + pack(a, Items.TORCH) + " (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("[VT] pl13 torches " + pack(a, Items.TORCH) + "; said " + allWords(a));
        });
    }

    /** pl14: a missing ingredient: exactly what's short, with the choices (hand over, go and get them, leave it). */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pl14_shortfall")
    public static void pl14_shortfall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_639_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.IRON_INGOT, 1));
        a.insertGiven(new ItemStack(Items.STICK, 2));
        p.getInventory().add(new ItemStack(Items.IRON_INGOT, 5));
        say(p, a, "make an iron pickaxe", null);
        String w = lastWords(a);
        helper.assertTrue(w.contains("2 iron ingots") && a.plan.idle(), "exactly what's missing: " + w);
        hash(p, "#logi crafthand");
        helper.assertTrue(pack(a, Items.IRON_INGOT) == 3 && on(p, Items.IRON_INGOT) == 3, "the two handed over: pack " + pack(a, Items.IRON_INGOT) + ", player " + on(p, Items.IRON_INGOT));
        helper.assertTrue(!a.plan.idle(), "and it's making it: " + lastWords(a));
        Kit.log("[VT] pl14 " + allWords(a));
        helper.succeed();
    }

    /** pl15: a tag recipe: a crafting table from birch planks (any planks will do). */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "pl15_tag")
    public static void pl15_tag(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_642_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.BIRCH_PLANKS, 4));
        say(p, a, "craft a crafting table", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(pack(a, Items.CRAFTING_TABLE) >= 1 && a.plan.idle(), "a crafting table from birch planks (" + lastWords(a) + ")");
            Kit.log("[VT] pl15 said " + allWords(a));
        });
    }

    /** pl16: "craft 16 torches and put them in this chest": made, then delivered into the pinged chest. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl16_craft_chest")
    public static void pl16_craft_chest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_645_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        level.setBlock(new BlockPos(x + 3, y, Z + 2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        BlockPos chest = chest(level, new BlockPos(x - 4, y, Z - 3), p);
        a.insertGiven(new ItemStack(Items.COAL, 4));
        a.insertGiven(new ItemStack(Items.OAK_PLANKS, 4));
        PaLogistics.seen(p, chest);
        say(p, a, "craft 16 torches and put them in this chest", null);
        helper.succeedWhen(() -> {
            int n = in(level, chest, Items.TORCH);
            helper.assertTrue(n == 16 && a.plan.idle(), "16 torches in the chest: " + n + " (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("[VT] pl16 chest " + n + "; said " + allWords(a));
        });
    }
}
