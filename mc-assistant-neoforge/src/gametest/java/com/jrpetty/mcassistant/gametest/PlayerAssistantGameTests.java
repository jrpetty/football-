package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.pa.PaBrain;
import com.jrpetty.mcassistant.pa.PaChests;
import com.jrpetty.mcassistant.pa.PaConfig;
import com.jrpetty.mcassistant.pa.PaData;
import com.jrpetty.mcassistant.pa.PaItems;
import com.jrpetty.mcassistant.pa.PaLife;
import com.jrpetty.mcassistant.pa.PaOrders;
import com.jrpetty.mcassistant.pa.PaSafety;
import com.jrpetty.mcassistant.pa.PaServer;
import com.jrpetty.mcassistant.pa.PaSpawner;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * [pa33] The Player AI Assistant, as a player meets it: got by a click, a crafted whistle or a spawner, always its
 * maker's (only they direct it), one each; told things in plain words through its own talk path (no chat) and doing
 * them: following, gathering logs, crafting a pickaxe from logs, mining iron, building a small house from the mod's
 * drawing, farming a plot, guarding and fighting, putting things in a chest, a three-step order; asking back when an
 * order is unclear; helping on its own; keeping off a town's ground; kept across a logout and a save; coming back
 * after a fall at its spawner; getting out of trouble; and wholly apart from the village folk.
 *
 * <p>Each on its own ground, x 7_000_000 to 7_090_000 (3000 apart), z 70000, in a batch of its own. The test world is
 * flat earth; each lays what it needs.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerAssistantGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    /** A clean slate and held ground; the y of the free block over the earth at x, Z. */
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
        return p;
    }

    /** Its assistant, made by the free button (with no confirmations, so a test's order starts at once). */
    private static PlayerAssistantEntity assistant(GameTestHelper helper, ServerPlayer p) {
        PaServer.act(p, "summon", new CompoundTag());
        PlayerAssistantEntity a = PaLife.find(p);
        if (a == null) {
            PaData.Rec rr = PaData.first(p.getUUID());
            Kit.log("pa no assistant for " + p.getUUID() + ": records " + PaData.of(p.getUUID()).size()
                + (rr == null ? "" : ", entity " + rr.entity + ", away " + rr.away + ", dead " + rr.deadUntil + ", inc " + rr.incarnation));
        }
        helper.assertTrue(a != null, "an assistant came");
        PaData.Rec r = a.rec();
        r.confirm = false;
        r.autoHelp = false;
        return a;
    }

    private static void say(ServerPlayer p, PlayerAssistantEntity a, String text) {
        say(p, a, text, null, -1);
    }

    private static void say(ServerPlayer p, PlayerAssistantEntity a, String text, BlockPos look, int ent) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        if (look != null) { t.putInt("bx", look.getX()); t.putInt("by", look.getY()); t.putInt("bz", look.getZ()); }
        if (ent >= 0) t.putInt("ent", ent);
        PaServer.act(p, "talk", t);
        Kit.log("pa said to " + a.displayNameCap() + ": \"" + text + "\" -> " + lastWords(a));
    }

    /** What it said last (its talk log). */
    private static String lastWords(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || r.log.isEmpty()) return "";
        return r.log.peekLast().text();
    }

    private static String allWords(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        StringBuilder b = new StringBuilder();
        if (r != null) for (PaData.Line l : r.log) b.append(l.who()).append(':').append(l.text()).append(" | ");
        return b.toString();
    }

    private static int count(AssistantEntity a, net.minecraft.world.item.Item item) {
        return a.countMatching(s -> s.is(item));
    }

    private static void tree(ServerLevel level, BlockPos base, int h) {
        for (int i = 0; i < h; i++) level.setBlock(base.above(i), Blocks.OAK_LOG.defaultBlockState(), 3);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = h - 2; dy <= h; dy++) {
            BlockPos l = base.offset(dx, dy, dz);
            if (level.getBlockState(l).isAir()) level.setBlock(l, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 3);
        }
    }

    // ================================================================== getting one

    /** pai01: the free button makes one beside its player, linked to their UUID, named for them; a second click calls it, not another. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pai01_button")
    public static void pai01_button(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_000_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        PaData.Rec r = a.rec();
        Kit.log("pai01 " + a.displayNameCap() + " owner " + a.getOwnerId() + " (player " + p.getUUID() + "), plate \"" + a.ownerName()
            + "\", " + String.format("%.1f", a.distanceTo(p)) + " away; said " + allWords(a));
        helper.assertTrue(p.getUUID().equals(a.getOwnerId()) && r.owner.equals(p.getUUID()), "linked to its maker's UUID");
        helper.assertTrue(a.ownerName().equals(p.getGameProfile().getName()), "shows its owner's name");
        helper.assertTrue(a.distanceTo(p) < 6, "beside its player");
        helper.assertTrue(!(((Object) a) instanceof VillageFolkEntity) && a.getType() == McAssistantMod.PLAYER_ASSISTANT.get(), "its own entity type");
        helper.assertTrue(allWords(a).contains("I'm"), "it introduced itself: " + allWords(a));
        PaServer.act(p, "summon", new CompoundTag());
        helper.assertTrue(PaData.of(p.getUUID()).size() == 1, "the button again calls it; it makes no second one");
        helper.succeed();
    }

    /** pai02: a crafted whistle used on the ground makes one there, and is spent; blown again it calls it, and is kept. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pai02_whistle")
    public static void pai02_whistle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_003_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        p.setGameMode(GameType.SURVIVAL);
        ItemStack whistle = new ItemStack(McAssistantMod.ASSISTANT_WHISTLE.get());
        p.setItemInHand(InteractionHand.MAIN_HAND, whistle);
        BlockPos spot = new BlockPos(x + 5, y - 1, Z + 3);
        McAssistantMod.ASSISTANT_WHISTLE.get().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(spot), Direction.UP, spot, false)));
        PlayerAssistantEntity a = PaLife.find(p);
        Kit.log("pai02 whistle: assistant " + (a == null ? "none" : a.displayNameCap() + " at " + a.blockPosition().toShortString())
            + "; whistle left " + p.getMainHandItem().getCount() + " (instabuild " + p.getAbilities().instabuild + ")");
        helper.assertTrue(a != null && a.blockPosition().distManhattan(spot.above()) <= 3, "made where the whistle was used");
        helper.assertTrue(p.getAbilities().instabuild || p.getMainHandItem().isEmpty(), "the whistle is spent making one");
        helper.assertTrue(level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "assistant_whistle")).isPresent(),
            "the whistle has a crafting recipe");
        ItemStack again = new ItemStack(McAssistantMod.ASSISTANT_WHISTLE.get());
        p.setItemInHand(InteractionHand.MAIN_HAND, again);
        a.teleportTo(x + 20, y, Z + 20);
        McAssistantMod.ASSISTANT_WHISTLE.get().use(level, p, InteractionHand.MAIN_HAND);
        PlayerAssistantEntity b = PaLife.find(p);
        helper.assertTrue(b != null && b.distanceTo(p) < 6 && !p.getMainHandItem().isEmpty(), "blown again: it comes, the whistle kept");
        helper.assertTrue(PaData.of(p.getUUID()).size() == 1, "still one");
        helper.succeed();
    }

    /** pai03: a spawner placed and clicked brings one out there, its home; fallen, it comes back at the spawner. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pai03_spawner_respawn")
    public static void pai03_spawner_respawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_006_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        BlockPos spawner = new BlockPos(x + 10, y, Z + 10);
        level.setBlock(spawner, McAssistantMod.ASSISTANT_SPAWNER.get().defaultBlockState(), 3);
        PaSpawner.placed(p, spawner);
        PaSpawner.clicked(p, spawner);
        PlayerAssistantEntity a = PaLife.find(p);
        helper.assertTrue(a != null, "a click on the spawner brought one out");
        PaData.Rec r = a.rec();
        r.confirm = false;
        helper.assertTrue(spawner.equals(r.spawner) && spawner.equals(a.getHome()), "the spawner is its home and its respawn point");
        helper.assertTrue(a.distanceToSqr(Vec3.atCenterOf(spawner)) < 16, "out at the spawner");
        helper.assertTrue(level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "assistant_spawner")).isPresent(),
            "the spawner has a crafting recipe");
        PaConfig.respawnSecondsForTests(5);
        a.teleportTo(x - 10, y, Z - 10);
        a.hurt(level.damageSources().genericKill(), 1000F);
        helper.assertTrue(r.dead(level.getGameTime()) && r.snapshot != null, "fallen, its record waits");
        helper.succeedWhen(() -> {
            PlayerAssistantEntity back = PaLife.find(p);
            helper.assertTrue(back != null, "back after its time");
            helper.assertTrue(back.distanceToSqr(Vec3.atCenterOf(spawner)) < 16, "back at the spawner: " + back.blockPosition().toShortString());
            Kit.log("pai03 back at " + back.blockPosition().toShortString() + " (spawner " + spawner.toShortString() + "); said " + lastWords(back));
            PaConfig.respawnSecondsForTests(null);
        });
    }

    /** pai04: one each: at the limit a clear message, and nothing made; dismissed, another can be made; the operator may raise it. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pai04_limit")
    public static void pai04_limit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_009_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        String name = a.displayNameCap();
        String limit = PaLife.atLimit(p);
        Kit.log("pai04 at the limit: \"" + limit + "\"");
        helper.assertTrue(("You already have " + name + ". Dismiss " + name + " to make another.").equals(limit), "the limit's message: " + limit);
        helper.assertTrue(PaLife.create(p, null, "whistle") == null && PaData.of(p.getUUID()).size() == 1, "no second one");
        PaConfig.perPlayerForTests(2);
        helper.assertTrue(PaLife.create(p, null, "whistle") != null && PaData.of(p.getUUID()).size() == 2, "the operator raised it: a second");
        PaConfig.perPlayerForTests(null);
        for (PaData.Rec r : List.copyOf(PaData.of(p.getUUID()))) PaLife.dismiss(p, r);
        helper.assertTrue(PaData.of(p.getUUID()).isEmpty(), "dismissed");
        helper.assertTrue(PaLife.create(p, null, "button") != null, "and now another can be made");
        helper.succeed();
    }

    // ================================================================== orders, typed

    /** pai05: "follow me": its player walks off; it follows. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pai05_follow")
    public static void pai05_follow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_012_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "stay here");
        helper.assertTrue(a.getMode() == AssistantEntity.Mode.STAY, "stays");
        say(p, a, "folow me pls");
        helper.assertTrue(a.getMode() == AssistantEntity.Mode.FOLLOW, "follows (typo and all)");
        p.teleportTo(level, x + 16.5, y, Z + 12.5, Set.of(), 0, 0);
        helper.succeedWhen(() -> {
            double d = a.distanceTo(p);
            helper.assertTrue(d < 5, "caught up: " + String.format("%.1f", d));
            Kit.log("pai05 followed to " + String.format("%.1f", d) + " of its player");
        });
    }

    /** pai06: "gather 16 logs": it chops sixteen from the trees about. */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pai06_gather_logs")
    public static void pai06_gather_logs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_015_000, y = ground(level, x);
        for (int i = 0; i < 5; i++) tree(level, new BlockPos(x + 6 + i * 4, y, Z + (i % 2 == 0 ? 5 : -5)), 5);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.IRON_AXE));
        say(p, a, "gather 16 logs");
        helper.assertTrue(a.plan.total() == 1, "one step: " + a.plan.all().size());
        helper.succeedWhen(() -> {
            int logs = a.countMatching(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
            helper.assertTrue(logs >= 16, "16 logs: " + logs + " (" + a.state() + ")");
            helper.assertTrue(a.plan.idle(), "done");
            Kit.log("pai06 " + logs + " logs; said " + lastWords(a));
        });
    }

    /** pai07: "craft a pickaxe from logs": from four logs, a wooden pickaxe (planks, sticks and a table on the way). */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pai07_craft_pickaxe")
    public static void pai07_craft_pickaxe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_018_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.OAK_LOG, 4));
        say(p, a, "craft a pickaxe from logs");
        helper.succeedWhen(() -> {
            int picks = count(a, Items.WOODEN_PICKAXE) + (a.getMainHandItem().is(Items.WOODEN_PICKAXE) ? 1 : 0);
            helper.assertTrue(picks >= 1, "a wooden pickaxe (" + a.state() + "; " + allWords(a) + ")");
            Kit.log("pai07 made a wooden pickaxe; said " + lastWords(a));
        });
    }

    /** pai08: "mine iron until you have 3": into the rock face, three raw iron. */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pai08_mine_iron")
    public static void pai08_mine_iron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_021_000, y = ground(level, x);
        // a rock face, its front studded with iron
        for (int dx = 4; dx < 14; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = 0; dy < 6; dy++) {
            boolean face = dx == 4;
            boolean ore = face && (dz + dy) % 3 == 0;
            level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), (ore ? Blocks.IRON_ORE : Blocks.STONE).defaultBlockState(), 2);
        }
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
        say(p, a, "mine iron until you have 3");
        helper.succeedWhen(() -> {
            int iron = a.countMatching(s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE));
            helper.assertTrue(iron >= 3, "3 iron: " + iron + " (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("pai08 " + iron + " iron; said " + lastWords(a));
        });
    }

    /** pai09: "build me a small house here": the mod's house drawing, laid where its player stands looking, from its pack. */
    @GameTest(template = EMPTY, timeoutTicks = 24000, batch = "pai09_build_house")
    public static void pai09_build_house(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_024_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        for (int i = 0; i < 4; i++) a.insertGiven(new ItemStack(Items.OAK_PLANKS, 64));
        for (int i = 0; i < 3; i++) a.insertGiven(new ItemStack(Items.COBBLESTONE, 64));
        a.insertGiven(new ItemStack(Items.OAK_LOG, 64));
        a.insertGiven(new ItemStack(Items.OAK_STAIRS, 64));
        a.insertGiven(new ItemStack(Items.OAK_SLAB, 32));
        a.insertGiven(new ItemStack(Items.GLASS_PANE, 32));
        a.insertGiven(new ItemStack(Items.GLASS, 16));
        a.insertGiven(new ItemStack(Items.CHEST, 2));
        a.insertGiven(new ItemStack(Items.CRAFTING_TABLE, 2));
        a.insertGiven(new ItemStack(Items.FURNACE, 2));
        a.insertGiven(new ItemStack(Items.OAK_DOOR, 2));
        a.insertGiven(new ItemStack(Items.TORCH, 16));
        a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
        a.insertGiven(new ItemStack(Items.IRON_AXE));
        say(p, a, "build me a small house here");
        helper.assertTrue(a.plan.total() >= 1 && a.plan.all().get(a.plan.all().size() - 1).label.contains("house"), "the house is planned: " + a.plan.all().size());
        BlockPos anchor = p.blockPosition().relative(p.getDirection(), com.jrpetty.mcassistant.entity.goal.BuildGoal.halfOf("house") + 3);
        helper.succeedWhen(() -> {
            if (a.tickCount % 1000 == 0) Kit.log("pai09 at " + a.tickCount + ": " + a.state() + ", " + a.blockPosition().toShortString() + ", queue " + a.jobLabels());
            helper.assertTrue(a.plan.idle(), "the build finished (" + a.state() + "; " + lastWords(a) + ")");
            int solid = 0, cells = 0;
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = 0; dy <= 4; dy++) {
                cells++;
                if (!level.getBlockState(anchor.offset(dx, dy, dz)).isAir()) solid++;
            }
            helper.assertTrue(solid >= 120, "a house stands: " + solid + " of " + cells + " cells filled round " + anchor.toShortString());
            Kit.log("pai09 house at " + anchor.toShortString() + ": " + solid + "/" + cells + " cells; said " + allWords(a));
        });
    }

    /** pai10: "farm this field": the ripe wheat it looks at harvested and replanted. */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "pai10_farm")
    public static void pai10_farm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_027_000, y = ground(level, x);
        BlockPos water = new BlockPos(x + 6, y - 1, Z);
        level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx == 0 && dz == 0) continue;
            level.setBlock(water.offset(dx, 0, dz), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(water.offset(dx, 1, dz), ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
        }
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.IRON_HOE));
        say(p, a, "farm this field", water.offset(1, 1, 0), -1);
        helper.succeedWhen(() -> {
            int ripe = 0, planted = 0;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                var st = level.getBlockState(water.offset(dx, 1, dz));
                if (st.getBlock() instanceof CropBlock c) { if (c.isMaxAge(st)) ripe++; else planted++; }
            }
            int wheat = count(a, Items.WHEAT);
            helper.assertTrue(ripe == 0 && wheat >= 20 && planted >= 20, "harvested and replanted: ripe " + ripe + ", replanted " + planted + ", wheat " + wheat);
            Kit.log("pai10 field: " + wheat + " wheat, " + planted + " replanted; said " + lastWords(a));
        });
    }

    /** pai11: "guard me": a zombie comes at its player; the assistant kills it. */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "pai11_guard_fight")
    public static void pai11_guard_fight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_030_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        say(p, a, "guard me");
        helper.assertTrue(a.getMode() == AssistantEntity.Mode.GUARD, "guarding");
        Zombie z = EntityType.ZOMBIE.create(level);
        z.moveTo(x + 6.5, y, Z + 0.5, 0, 0);
        z.setPersistenceRequired();
        level.addFreshEntity(z);
        helper.succeedWhen(() -> {
            helper.assertTrue(!z.isAlive(), "the zombie is dead (hp " + z.getHealth() + ", assistant " + a.state() + ")");
            Kit.log("pai11 zombie killed; assistant at " + a.getHealth() + " hp");
        });
    }

    /** pai12: "put everything in that chest" (looking at it): its cobblestone goes in. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pai12_deposit")
    public static void pai12_deposit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_033_000, y = ground(level, x);
        BlockPos chest = new BlockPos(x + 5, y, Z + 2);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 20));
        say(p, a, "put everything in that chest", chest, -1);
        helper.succeedWhen(() -> {
            int in = 0;
            if (level.getBlockEntity(chest) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.COBBLESTONE)) in += c.getItem(i).getCount();
            helper.assertTrue(in >= 20, "20 cobblestone in the chest: " + in + " (" + lastWords(a) + ")");
            Kit.log("pai12 chest holds " + in + " cobblestone");
        });
    }

    /** pai13: a three-step order: "chop 4 logs, then make planks, then put them in that chest". */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pai13_multi_step")
    public static void pai13_multi_step(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_036_000, y = ground(level, x);
        for (int i = 0; i < 2; i++) tree(level, new BlockPos(x + 7 + i * 5, y, Z + 4), 5);
        BlockPos chest = new BlockPos(x + 3, y, Z - 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.IRON_AXE));
        say(p, a, "chop 4 logs, then make planks, then put everything in that chest", chest, -1);
        helper.assertTrue(a.plan.total() == 3, "three steps: " + a.plan.total());
        helper.succeedWhen(() -> {
            int planks = 0;
            if (level.getBlockEntity(chest) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(net.minecraft.tags.ItemTags.PLANKS)) planks += c.getItem(i).getCount();
            helper.assertTrue(planks >= 4 && a.plan.idle(), "planks in the chest: " + planks + " (" + a.state() + "; " + lastWords(a) + ")");
            Kit.log("pai13 chest holds " + planks + " planks; said " + allWords(a));
        });
    }

    /** pai14: an unclear order is asked back, with choices to click; nonsense too; never silence. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pai14_clarify")
    public static void pai14_clarify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_039_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "build");
        helper.assertTrue(lastWords(a).equals("What shall I build?") && a.plan.idle(), "asked what to build: " + lastWords(a));
        say(p, a, "blargh flob");
        helper.assertTrue(lastWords(a).startsWith("I'm not sure what you mean"), "asked back about nonsense: " + lastWords(a));
        say(p, a, "gather unobtainium");
        helper.assertTrue(lastWords(a).contains("unobtainium"), "names what it doesn't know: " + lastWords(a));
        // a big job is checked first, and "no" cancels it
        a.rec().confirm = true;
        say(p, a, "dig a mine down to level -40");
        helper.assertTrue(PaOrders.pending(p.getUUID()) != null && a.plan.idle(), "a big job waits for OK: " + lastWords(a));
        say(p, a, "no");
        helper.assertTrue(PaOrders.pending(p.getUUID()) == null && a.plan.idle(), "\"no\" cancelled it");
        helper.succeed();
    }

    /** pai15: auto-help: a furnace gone cold with ore in it, and coal in its pack: it fuels the furnace by itself, and says so. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pai15_auto_help")
    public static void pai15_auto_help(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_042_000, y = ground(level, x);
        BlockPos furnace = new BlockPos(x + 4, y, Z + 3);
        level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
        if (level.getBlockEntity(furnace) instanceof Container c) c.setItem(0, new ItemStack(Items.RAW_IRON, 8));
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.COAL, 16));
        a.rec().autoHelp = true;
        helper.succeedWhen(() -> {
            ItemStack fuel = level.getBlockEntity(furnace) instanceof Container c ? c.getItem(1) : ItemStack.EMPTY;
            boolean burning = level.getBlockState(furnace).getValue(net.minecraft.world.level.block.FurnaceBlock.LIT);
            helper.assertTrue(!fuel.isEmpty() || burning, "the furnace fuelled (" + a.state() + "; " + lastWords(a) + ")");
            helper.assertTrue(allWords(a).contains("furnace"), "it said what it was doing: " + allWords(a));
            Kit.log("pai15 furnace fuelled on its own; said " + allWords(a));
        });
    }

    /** pai16: only its owner directs it: another player's words, clicks and right-click do nothing to it. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pai16_owner_only")
    public static void pai16_owner_only(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_045_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "stay here");
        ServerPlayer other = player(helper, level, new BlockPos(x + 3, y, Z));
        helper.assertTrue(!other.getUUID().equals(p.getUUID()), "two players");
        CompoundTag t = new CompoundTag();
        t.putString("text", "follow me");
        PaServer.act(other, "talk", t);
        PaServer.act(other, "job", new CompoundTag());
        PaServer.act(other, "dismiss", new CompoundTag());
        helper.assertTrue(a.getMode() == AssistantEntity.Mode.STAY && a.isAlive() && PaData.of(p.getUUID()).size() == 1, "another player's orders do nothing");
        helper.assertTrue(!a.obeys(other) && a.obeys(p), "it obeys its owner only");
        var res = a.interact(other, InteractionHand.MAIN_HAND);
        helper.assertTrue(PaData.of(other.getUUID()).isEmpty(), "the other has no assistant of theirs from it");
        Kit.log("pai16 other player's right-click: " + res + "; mode still " + a.getMode());
        helper.succeed();
    }

    /** pai17: claim safety: on a town's ground it won't build, dig or open the town's chests. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pai17_claim_safety")
    public static void pai17_claim_safety(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_048_000, y = ground(level, x);
        VillageFolkEntity folk = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, new BlockPos(x, y, Z), 0F);
        helper.assertTrue(folk != null && folk.ownerId() != null, "a town");
        ServerPlayer p = player(helper, level, new BlockPos(x + 6, y, Z + 6));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos inTown = new BlockPos(x + 4, y - 1, Z + 4);
        helper.assertTrue(PaSafety.offLimits(a, inTown) && !a.inZone(inTown), "the town's ground is off limits to it");
        say(p, a, "build a small house here");
        helper.assertTrue(a.plan.idle() && lastWords(a).contains("ground"), "it won't build on the town's ground: " + lastWords(a));
        BlockPos chest = new BlockPos(x + 3, y, Z + 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        helper.assertTrue(!PaChests.allowed(a, level, chest), "nor open a chest on it");
        helper.assertTrue(!PaSafety.offLimits(a, new BlockPos(x + 600, y, Z)), "out in the wild it may work");
        Kit.log("pai17 refused: " + lastWords(a));
        helper.succeed();
    }

    /** pai18: kept across a logout (it leaves with its player, back beside them after) and across a save (load its own record). */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pai18_persistence")
    public static void pai18_persistence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_051_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.DIAMOND, 3));
        PaLife.rename(a, "Juniper");
        PaData.Rec r = a.rec();
        java.util.UUID before = a.getUUID();
        PaLife.stash(a);
        helper.assertTrue(!a.isAlive() || a.isRemoved(), "gone with its player");
        helper.assertTrue(r.away && r.snapshot != null && PaLife.find(p) == null, "kept in its record while away");
        p.teleportTo(level, x + 10.5, y, Z + 10.5, Set.of(), 0, 0);
        PaLife.restore(p);
        PlayerAssistantEntity b = PaLife.find(p);
        helper.assertTrue(b != null && b.distanceTo(p) < 6, "back beside its player");
        helper.assertTrue(count(b, Items.DIAMOND) == 3 && b.displayNameCap().equals("Juniper") && p.getUUID().equals(b.getOwnerId()),
            "the same: its diamonds, its name, its owner");
        // a save: the entity written and read back keeps its slot and plan
        say(p, b, "gather 8 stone");
        CompoundTag saved = b.saveWithoutId(new CompoundTag());
        PlayerAssistantEntity c = McAssistantMod.PLAYER_ASSISTANT.get().create(level);
        c.load(saved);
        helper.assertTrue(c.slot() == b.slot() && c.incarnation() == b.incarnation() && !c.plan.idle() && p.getUUID().equals(c.getOwnerId()),
            "a save keeps its slot, incarnation, owner and plan");
        c.discard();
        // and a stale copy (an old incarnation) left in a chunk goes when it ticks
        PlayerAssistantEntity stale = McAssistantMod.PLAYER_ASSISTANT.get().create(level);
        CompoundTag old = saved.copy();
        old.putLong("PaInc", 0);
        old.remove("UUID");
        stale.load(old);
        stale.moveTo(x - 5, y, Z - 5);
        level.addFreshEntity(stale);
        Kit.log("pai18 before " + before + ", after " + b.getUUID() + "; " + b.displayNameCap() + " with " + count(b, Items.DIAMOND) + " diamonds");
        helper.succeedWhen(() -> helper.assertTrue(stale.isRemoved(), "the stale copy sent away"));
    }

    /** pai19: wholly apart from the folk: in a town it is not one of its people, takes no job, bed or wage, and is nobody's crew. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pai19_not_folk")
    public static void pai19_not_folk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_054_000, y = ground(level, x);
        VillageFolkEntity folk = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, new BlockPos(x, y, Z), 0F);
        java.util.UUID town = folk.ownerId();
        int heads = Villages.headcount(town);
        ServerPlayer p = player(helper, level, new BlockPos(x + 3, y, Z + 3));
        PlayerAssistantEntity a = assistant(helper, p);
        a.setOwner(p);
        helper.assertTrue(Villages.headcount(town) == heads, "not counted among the town's people: " + Villages.headcount(town) + " vs " + heads);
        helper.assertTrue(!Villages.folkOf(town).contains(a) && !AssistantEntity.allFor(p.getUUID()).contains(a) && !AssistantEntity.allFor(town).contains(a),
            "on no town's roll and nobody's crew register");
        helper.assertTrue(!a.isSettler() && !(((Object) a) instanceof VillageFolkEntity), "not a settler");
        helper.succeedWhen(() -> {
            helper.assertTrue(a.stationTask() == AssistantEntity.StationTask.NONE, "no town job: " + a.stationTask());
            helper.assertTrue(a.getHome() == null || !a.getHome().closerThan(folk.blockPosition(), 0), "no town home");
            helper.assertTrue(folk.getTarget() != a, "the folk don't go for it");
            helper.assertTrue(a.tickCount > 300, "a while in the town first");
            Kit.log("pai19 after " + a.tickCount + " ticks in town: job " + a.stationTask() + ", headcount " + Villages.headcount(town) + " (" + heads + ")");
        });
    }

    // ================================================================== trouble

    /** pai20: its player far away behind a wall and it can't get there: it comes to them; "come here" from far brings it at once. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pai20_stuck_owner_far")
    public static void pai20_stuck_owner_far(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_057_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        // a pen of bedrock round it: no way out on foot
        BlockPos at = a.blockPosition();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 3; dy++) {
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -1 || dy == 3) level.setBlock(at.offset(dx, dy, dz), Blocks.BEDROCK.defaultBlockState(), 2);
        }
        say(p, a, "follow me");
        p.teleportTo(level, x + 30.5, y, Z + 0.5, Set.of(), 0, 0);
        helper.succeedWhen(() -> {
            helper.assertTrue(a.distanceTo(p) < 8, "it got out to its player: " + String.format("%.1f", a.distanceTo(p)));
            Kit.log("pai20 penned in, got to its player (" + lastWords(a) + ")");
        });
    }

    /** pai21: a full chest, and no tools: it says why it can't, and offers fixes; it never just stands there in silence. */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "pai21_full_chest_no_tool")
    public static void pai21_full_chest_no_tool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_060_000, y = ground(level, x);
        BlockPos chest = new BlockPos(x + 4, y, Z);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(chest) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, new ItemStack(Items.DIRT, 64));
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        // no logs about, no pickaxe: stone it can't get
        say(p, a, "gather 8 stone");
        String noTool = lastWords(a);
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 10));
        a.plan.clear();
        a.clearQueue();
        say(p, a, "put everything in that chest", chest, -1);
        helper.succeedWhen(() -> {
            helper.assertTrue(a.plan.idle(), "the deposit is over (" + a.state() + ")");
            String said = lastWords(a);
            helper.assertTrue(said.toLowerCase().contains("full") || said.toLowerCase().contains("room") || said.toLowerCase().contains("couldn"),
                "it says the chest is full: " + said + " (all: " + allWords(a) + ")");
            Kit.log("pai21 no tool: \"" + noTool + "\"; full chest: \"" + said + "\"");
        });
    }

    /** pai22: night with monsters about, auto-help on: it takes on the zombie coming at its player, unasked. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pai22_night_mobs")
    public static void pai22_night_mobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_063_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        level.setDayTime(18000);
        a.rec().autoHelp = true;
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        Zombie z = EntityType.ZOMBIE.create(level);
        z.moveTo(x + 9.5, y, Z + 3.5, 0, 0);
        z.setPersistenceRequired();
        level.addFreshEntity(z);
        helper.succeedWhen(() -> {
            helper.assertTrue(!z.isAlive(), "the zombie is dealt with (" + a.state() + ")");
            level.setDayTime(2000);
            Kit.log("pai22 night zombie killed; said " + allWords(a));
        });
    }

    /** pai23: its own thinking stays cheap: the time it takes, per server tick, is measured and inside its budget. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pai23_budget")
    public static void pai23_budget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_066_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.rec().autoHelp = true;
        PaBrain.resetStats();
        helper.runAfterDelay(300, () -> {
            double us = PaBrain.microsPerTick();
            Kit.log("pai23 the assistant's own thinking: " + String.format("%.1f", us) + " us per tick, " + PaBrain.overBudgetTicks()
                + " ticks over the " + PaConfig.budgetMicros() + " us budget");
            helper.assertTrue(us < PaConfig.budgetMicros(), "within budget: " + us);
            helper.succeed();
        });
    }

    /** pai24: dug into a pit it can't walk out of: it climbs out on blocks from its pack (no teleport) and follows. */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "pai24_climb_out")
    public static void pai24_climb_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_069_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos pit = new BlockPos(x + 4, y - 3, Z);                     // three deep: the floor is the bottom of the world here
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
            level.setBlock(pit.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
        }
        for (int dy = 0; dy <= 2; dy++) level.setBlock(pit.above(dy), Blocks.AIR.defaultBlockState(), 2);
        a.teleportTo(pit.getX() + 0.5, pit.getY(), pit.getZ() + 0.5);
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 16));
        say(p, a, "follow me");
        helper.succeedWhen(() -> {
            helper.assertTrue(a.getY() >= y - 0.01, "it climbed out: y " + String.format("%.1f", a.getY()) + " of " + y);
            helper.assertTrue(level.getBlockState(pit).is(Blocks.COBBLESTONE), "on its own blocks (pillared, not teleported)");
            helper.assertTrue(a.distanceTo(p) < 6, "and came to its player: " + String.format("%.1f", a.distanceTo(p)));
            Kit.log("pai24 climbed out of a 3-deep pit on its own cobblestone, " + a.countMatching(st -> st.is(Items.COBBLESTONE)) + " left");
        });
    }

    /** pai25: its pickaxe wears out half way through a dig: it makes a new one and finishes the count, no pause. */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "pai25_pickaxe_wears_out")
    public static void pai25_pickaxe_wears_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_072_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        for (int dx = 4; dx <= 10; dx++) for (int dz = -3; dz <= 3; dz++) level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x - 2, y, Z + 2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        ItemStack worn = new ItemStack(Items.WOODEN_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 4);
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, worn);
        a.insertGiven(new ItemStack(Items.STICK, 8));
        a.insertGiven(new ItemStack(Items.OAK_PLANKS, 8));
        say(p, a, "gather 20 stone");
        helper.succeedWhen(() -> {
            helper.assertTrue(a.plan.idle() && !a.plan.paused, "the dig is over and nothing paused (" + a.state() + ": " + lastWords(a) + ")");
            int stone = a.countMatching(st -> st.is(Items.COBBLESTONE));
            helper.assertTrue(stone >= 17, "it finished the count with a new pickaxe: " + stone + " cobblestone");
            helper.assertTrue(PaItems.hasPickaxe(a), "it has a working pickaxe again");
            Kit.log("pai25 pickaxe wore out; made a new one; " + stone + " cobblestone (" + allWords(a) + ")");
        });
    }

    /** pai26: in the middle of a wide dug-out pit: it walks to a wall and builds up against it, then comes out. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pai26_wide_pit")
    public static void pai26_wide_pit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_075_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        BlockPos mid = new BlockPos(x + 8, y - 3, Z);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 2; dy++) {
            boolean inside = Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && dy >= 0;
            level.setBlock(mid.offset(dx, dy, dz), inside ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
        }
        a.teleportTo(mid.getX() + 0.5, mid.getY(), mid.getZ() + 0.5);
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 16));
        say(p, a, "follow me");
        helper.succeedWhen(() -> {
            helper.assertTrue(a.getY() >= y - 1.01, "it built itself up to the rim: y " + String.format("%.1f", a.getY()) + " of " + y);
            helper.assertTrue(a.countMatching(st -> st.is(Items.COBBLESTONE)) < 16, "on its own blocks");
            Kit.log("pai26 got out of a 5x5 pit three deep from its middle; " + a.countMatching(st -> st.is(Items.COBBLESTONE)) + " cobblestone left");
        });
    }

    /** pai27: its player clicks it by mistake, another player hits it: it is not hurt by its own player and turns on nobody. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pai27_never_turns")
    public static void pai27_never_turns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_078_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        ServerPlayer other = player(helper, level, new BlockPos(x + 2, y, Z + 2));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.IRON_SWORD));
        float hp = a.getHealth();
        a.hurt(level.damageSources().playerAttack(p), 4.0F);
        helper.assertTrue(a.getHealth() == hp, "its own player's knock did no harm");
        a.hurt(level.damageSources().playerAttack(other), 2.0F);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(a.getTarget() == null, "it turned on nobody: " + a.getTarget());
            helper.assertTrue(p.getHealth() == p.getMaxHealth() && other.getHealth() == other.getMaxHealth(), "no one was hit back");
            Kit.log("pai27 owner's knock harmless; hit by another player, it turned on nobody");
            helper.succeed();
        });
    }

    /** pai28: no trees within reach: it says where the nearest are, walks there and gets the logs. */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pai28_trees_further")
    public static void pai28_trees_further(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_081_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        for (int t = 0; t < 3; t++) tree(level, new BlockPos(x + 34 + t * 3, y, Z + 1));
        say(p, a, "get me 8 logs");
        helper.succeedWhen(() -> {
            int logs = a.countMatching(st -> st.is(net.minecraft.tags.ItemTags.LOGS)) + p.getInventory().countItem(Items.OAK_LOG);
            helper.assertTrue(logs >= 8, "it went to the trees and got the logs (for you): " + logs + " (" + lastWords(a) + ")");
            helper.assertTrue(allWords(a).contains("Heading there"), "and said where it was going: " + allWords(a));
            Kit.log("pai28 no trees near; went ~35 blocks to them; " + logs + " logs (" + allWords(a) + ")");
        });
    }

    /** pai29: "Break near your builds" off (the default): it fells the tree, not the log beams of a build, nor laid cobblestone. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pai29_spares_builds")
    public static void pai29_spares_builds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_084_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        // a build: two log beams and a cobblestone wall, right beside it
        for (int dy = 0; dy < 3; dy++) {
            level.setBlock(new BlockPos(x + 2, y + dy, Z + 2), Blocks.OAK_LOG.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x + 4, y + dy, Z + 2), Blocks.OAK_LOG.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x + 3, y + dy, Z + 2), Blocks.COBBLESTONE.defaultBlockState(), 2);
        }
        // a tree further off (its crown well clear of the build)
        tree(level, new BlockPos(x + 14, y, Z - 6));
        say(p, a, "chop 4 logs");
        helper.succeedWhen(() -> {
            helper.assertTrue(a.plan.idle(), "the chopping is over (" + a.state() + ")");
            int beams = 0, wall = 0;
            for (int dy = 0; dy < 3; dy++) {
                if (level.getBlockState(new BlockPos(x + 2, y + dy, Z + 2)).is(Blocks.OAK_LOG)) beams++;
                if (level.getBlockState(new BlockPos(x + 4, y + dy, Z + 2)).is(Blocks.OAK_LOG)) beams++;
                if (level.getBlockState(new BlockPos(x + 3, y + dy, Z + 2)).is(Blocks.COBBLESTONE)) wall++;
            }
            helper.assertTrue(beams == 6 && wall == 3, "the build is untouched: beams " + beams + "/6, wall " + wall + "/3");
            helper.assertTrue(a.countMatching(st -> st.is(Items.OAK_LOG)) >= 4, "and it got its logs from the tree (" + lastWords(a) + ")");
            Kit.log("pai29 spared the build (6 beams, 3 cobble); logs from the tree");
        });
    }

    /** pai30: "smelt 4 raw iron": with coal in its pack and a furnace by, four iron ingots come out (it makes the furnace if there is none). */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pai30_smelt")
    public static void pai30_smelt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_087_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        level.setBlock(new BlockPos(x + 3, y, Z + 2), Blocks.FURNACE.defaultBlockState(), 3);
        a.insertGiven(new ItemStack(Items.RAW_IRON, 4));
        a.insertGiven(new ItemStack(Items.COAL, 4));
        say(p, a, "smelt 4 raw iron");
        helper.succeedWhen(() -> {
            int ingots = a.countMatching(st -> st.is(Items.IRON_INGOT));
            helper.assertTrue(ingots >= 4, "four iron ingots: " + ingots + " (" + a.state() + ": " + lastWords(a) + ")");
            Kit.log("pai30 smelted " + ingots + " iron ingots (" + lastWords(a) + ")");
        });
    }

    // ================================================================== round 2 [pa33b]

    /** pai31: at a live town market it buys for its player, never past its spending limit, and sells from its pack for them. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pai31_trade")
    public static void pai31_trade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_090_000, y = ground(level, x);
        level.setDayTime(24000L * 3 + 8000);
        VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a town");
        java.util.UUID id = f.ownerId();
        helper.runAfterDelay(10, () -> {
            Villages.Village v = Villages.get(id);
            BlockPos heart = v.centre();
            f.setJob(AssistantEntity.StationTask.FARM);           // its founder farms: nobody builds with the town's stone meanwhile
            for (int k = 0; k < 6; k++) {                         // a fed town with a glut of cobblestone to sell
                BlockPos at = Kit.surface(level, heart.getX() - 6 + 2 * k, heart.getZ() - 5);
                level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
                com.jrpetty.mcassistant.entity.ZoneChests.mark(level, at);
                if (level.getBlockEntity(at) instanceof Container c) for (int i = 0; i < 27; i++) c.setItem(i, new ItemStack(k == 0 ? Items.BREAD : Items.COBBLESTONE, 64));
            }
            Villages.forgetStores(id);
            com.jrpetty.mcassistant.entity.Budget.forget(id);
            com.jrpetty.mcassistant.village.Ledger.addCoins(id, 500);
            ServerPlayer p = player(helper, level, heart.offset(2, 0, 2));
            p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 60));
            PlayerAssistantEntity a = assistant(helper, p);
            int price = com.jrpetty.mcassistant.entity.Market.quoteFor(level, v, p, new ItemStack(Items.COBBLESTONE));
            helper.assertTrue(price > 0, "the market sells cobblestone: " + price);
            a.rec().spend = price * 2 + price / 2;                 // room for two lots, not three
            int before = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
            Kit.log("pai31 market stock of cobblestone " + com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.COBBLESTONE)));
            say(p, a, "buy 64 cobblestone");
            int[] phase = {0}, mark = {0, 0, 0};
            helper.succeedWhen(() -> {
                if (phase[0] == 0) {
                    helper.assertTrue(a.plan.idle(), "the shopping is done (" + a.state() + ")");
                    int paid = before - com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
                    int got = p.getInventory().countItem(Items.COBBLESTONE);
                    helper.assertTrue(got > 0 && paid > 0, "it bought cobblestone with its player's coin: " + got + " for " + paid + " (" + allWords(a)
                        + "; stock " + com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.COBBLESTONE)) + ")");
                    helper.assertTrue(paid <= a.rec().spend, "never past the limit: paid " + paid + " of " + a.rec().spend);
                    helper.assertTrue(allWords(a).contains("spending limit"), "and said why it stopped: " + lastWords(a));
                    mark[0] = paid;
                    mark[1] = got;
                    mark[2] = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
                    phase[0] = 1;
                    a.insertGiven(new ItemStack(Items.COBBLESTONE, 64));
                    say(p, a, "sell 64 cobblestone");
                    helper.fail("now selling");                    // and wait for the sale
                }
                helper.assertTrue(a.plan.idle() && com.jrpetty.mcassistant.entity.Market.coinsHeld(p) > mark[2],
                    "sold its cobblestone and its player was paid (" + lastWords(a) + "; stock "
                    + com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.COBBLESTONE)) + ")");
                Kit.log("pai31 quote " + price + ", limit " + a.rec().spend + ", paid " + mark[0] + " for " + mark[1] + " cobblestone; sold: +"
                    + (com.jrpetty.mcassistant.entity.Market.coinsHeld(p) - mark[2]) + " coins (" + allWords(a) + ")");
            });
        });
    }

    /** pai32: self-care: hurt with bread in its pack, it eats and heals; hurt with nothing to eat, it says so and asks. */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pai32_self_care")
    public static void pai32_self_care(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_093_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.removeMatching(st -> st.has(net.minecraft.core.component.DataComponents.FOOD), 999);
        a.insertGiven(new ItemStack(Items.BREAD, 4));
        a.setHealth(8.0F);
        helper.runAfterDelay(400, () -> {
            int bread = a.countMatching(st -> st.is(Items.BREAD));
            helper.assertTrue(a.getHealth() > 8.0F && bread < 4, "it ate and healed: hp " + a.getHealth() + ", bread left " + bread);
            float healed = a.getHealth();
            a.removeMatching(st -> st.has(net.minecraft.core.component.DataComponents.FOOD), 999);
            a.setHealth(6.0F);
            helper.succeedWhen(() -> {
                helper.assertTrue(allWords(a).contains("nothing to eat") || allWords(a).contains("out of food") || allWords(a).contains("pantry is empty"),
                    "with no food it asks: " + lastWords(a));
                Kit.log("pai32 ate (hp 8 -> " + healed + ", bread " + bread + "/4); with none: \"" + lastWords(a) + "\"");
            });
        });
    }

    /** pai33: its player gets into a boat: it takes the seat beside them and goes with them; they step out, it steps out. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pai33_boat")
    public static void pai33_boat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_096_000, y = ground(level, x);
        for (int dx = 2; dx <= 14; dx++) for (int dz = -4; dz <= 4; dz++) {
            level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x + dx, y - 2, Z + dz), Blocks.STONE.defaultBlockState(), 2);
        }
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "follow me");
        net.minecraft.world.entity.vehicle.Boat boat = EntityType.BOAT.create(level);
        boat.moveTo(x + 4.5, y - 0.6, Z + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(boat);
        p.startRiding(boat, true);
        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                helper.assertTrue(a.getVehicle() == boat, "it got into the boat with its player: " + a.getVehicle());
                phase[0] = 1;
                boat.setPos(x + 10.5, y - 0.6, Z + 0.5);           // rowed across: both aboard go with it
                helper.fail("rowing");
            }
            if (phase[0] == 1) {
                helper.assertTrue(a.getVehicle() == boat && a.distanceToSqr(boat) < 4, "it went along: " + String.format("%.1f", a.distanceTo(boat)));
                phase[0] = 2;
                p.stopRiding();
                helper.fail("stepping out");
            }
            helper.assertTrue(!a.isPassenger(), "its player stepped out, so did it");
            Kit.log("pai33 into the boat, across the water, and out with its player");
        });
    }

    /** pai34: its player goes to another dimension: it comes along and stands beside them there. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pai34_dimension")
    public static void pai34_dimension(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_099_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        say(p, a, "follow me");
        ServerLevel nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(nether != null, "a nether to go to");
        int nx = x / 8, nz = Z / 8, ny = 100;
        nether.getChunkAt(new BlockPos(nx, ny, nz));
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            nether.setBlock(new BlockPos(nx + dx, ny - 1, nz + dz), Blocks.OBSIDIAN.defaultBlockState(), 2);
            for (int dy = 0; dy < 3; dy++) nether.setBlock(new BlockPos(nx + dx, ny + dy, nz + dz), Blocks.AIR.defaultBlockState(), 2);
        }
        p.teleportTo(nether, nx + 0.5, ny, nz + 0.5, Set.of(), 0.0F, 0.0F);
        helper.succeedWhen(() -> {
            PlayerAssistantEntity there = PaLife.find(p);
            helper.assertTrue(there != null && there.level() == nether && there.distanceTo(p) < 8,
                "it came through to the nether beside its player: " + (there == null ? "none" : there.level().dimension().location() + " " + String.format("%.1f", there.distanceTo(p))));
            Kit.log("pai34 followed its player to the nether: " + String.format("%.1f", there.distanceTo(p)) + " blocks away");
        });
    }

    /** pai35: two players: each has their own assistant and their own limit; B can't direct it, open it, or turn it on anyone. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pai35_two_players")
    public static void pai35_two_players(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_102_000, y = ground(level, x);
        ServerPlayer pa = player(helper, level, new BlockPos(x, y, Z));
        ServerPlayer pb = player(helper, level, new BlockPos(x + 6, y, Z));
        PlayerAssistantEntity a = assistant(helper, pa);
        say(pa, a, "stay here");
        // B: words, the panel, the job list, dismissing: nothing reaches A's assistant
        CompoundTag t = new CompoundTag();
        t.putString("text", "follow me");
        PaServer.act(pb, "talk", t);
        PaServer.act(pb, "dismiss", new CompoundTag());
        CompoundTag bState = com.jrpetty.mcassistant.pa.PaNet.state(pb, PaLife.find(pb));
        helper.assertTrue(a.getMode() == AssistantEntity.Mode.STAY && a.isAlive() && !bState.getBoolean("has"),
            "B's words and panel reach nothing of A's: mode " + a.getMode() + ", B sees one: " + bState.getBoolean("has"));
        // B hits it: no harm done to B, and it turns on nobody
        a.hurt(level.damageSources().playerAttack(pb), 2.0F);
        // each their own: B gets one of their own, A's limit is A's
        PaServer.act(pb, "summon", new CompoundTag());
        PlayerAssistantEntity b = PaLife.find(pb);
        helper.assertTrue(b != null && b != a && b.isOwner(pb) && !b.obeys(pa) && !a.obeys(pb), "B has their own, each obeys its own player");
        int aBefore = PaData.of(pa.getUUID()).size();
        PaServer.act(pa, "summon", new CompoundTag());
        helper.assertTrue(PaData.of(pa.getUUID()).size() == aBefore && PaData.of(pb.getUUID()).size() == 1, "one each: A's second call made none, B has one");
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(a.getTarget() == null && b.getTarget() == null && pb.getHealth() == pb.getMaxHealth(), "nobody turned on anybody");
            Kit.log("pai35 two players: own assistants (" + a.shownName() + ", " + b.shownName() + "), own limits, B can't direct or open A's");
            helper.succeed();
        });
    }

    /** pai36: the talk log keeps tidy: the same line again shows once with a count; a second hello replaces the first. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pai36_talk_log")
    public static void pai36_talk_log(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_105_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        int n = a.rec().log.size();
        com.jrpetty.mcassistant.pa.PaTalk.greet(a, "Welcome back!");
        com.jrpetty.mcassistant.pa.PaTalk.greet(a, "Good to see you again.");
        com.jrpetty.mcassistant.pa.PaTalk.reply(a, "Heading there.");
        com.jrpetty.mcassistant.pa.PaTalk.reply(a, "Heading there.");
        var log = a.rec().log;
        helper.assertTrue(log.size() == n + 2, "two lines, not four: " + (log.size() - n));
        helper.assertTrue(log.peekLast().times() == 2 && log.peekLast().text().equals("Heading there."), "the repeat counted: x" + log.peekLast().times());
        Kit.log("pai36 log tidy: one hello, \"Heading there.\" x2");
        helper.succeed();
    }

    /** pai37: its personality (the triangle), its look and its chattiness save with it and come back after a save. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pai37_persona_saves")
    public static void pai37_persona_saves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_108_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        helper.assertTrue(a.rec().tri().label().equals("Banter"), "a new one starts at Banter: " + a.rec().tri().label());
        CompoundTag t = new CompoundTag();
        t.putFloat("funny", 0.25F);
        t.putFloat("gloomy", 0.6F);
        t.putInt("chatty", 4);
        PaServer.act(p, "settings", t);
        CompoundTag lk = new CompoundTag();
        lk.putInt("look", 14);
        PaServer.act(p, "look", lk);
        PaData.Rec back = PaData.Rec.loadForTests(a.rec().saveForTests());
        helper.assertTrue(Math.abs(back.funny - 0.25F) < 1e-4 && Math.abs(back.gloomy - 0.6F) < 1e-4 && back.chatty == 4 && back.look == 14,
            "saved and loaded: " + back.funny + ", " + back.gloomy + ", " + back.chatty + ", " + back.look);
        helper.assertTrue(a.look() == 14, "its body shows the skeleton look: " + a.look());
        Kit.log("pai37 triangle (0.25 funny, 0.6 gloomy = " + back.tri().label() + "), look 14 and chattiness 4 saved and loaded");
        helper.succeed();
    }

    /** pai38: "shut up" quiets it (answers and warnings only), "no more jokes" leaves plain words, "you can talk again" lifts it; kept over a save. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pai38_mute")
    public static void pai38_mute(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_111_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.rec().chatty = 4;                                       // Won't shut up, at the Joker corner: the Joke Machine
        a.rec().funny = 1.0F;
        a.rec().gloomy = 0.0F;
        helper.assertTrue(com.jrpetty.mcassistant.pa.PaBanter.jokeMachine(a), "Joke Machine on to start");
        say(p, a, "oi, shut up");
        helper.assertTrue(a.rec().mute == 2 && a.mute() == 2, "quiet: " + a.rec().mute);
        String ack = lastWords(a);
        helper.assertTrue(!ack.isEmpty() && ack.length() < 80, "one short acknowledgement: " + ack);
        helper.assertTrue(!com.jrpetty.mcassistant.pa.PaBanter.jokeMachine(a), "no Joke Machine while quiet");
        int[] start = {said(a)}, tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] % 10 == 0) {                              // its player mining, placing, jumping, eating, opening chests, standing about
                String[] did = {"jm.mine", "jm.place", "jm.jump", "jm.eat", "jm.chest"};
                com.jrpetty.mcassistant.pa.PaBrain.ownerDid(a, did[(tick[0] / 10) % did.length]);
                com.jrpetty.mcassistant.pa.PaBanter.quip(a, "quip.idle", 1, null);
                com.jrpetty.mcassistant.pa.PaBanter.quip(a, "quip.rain", 1, null);
            }
        });
        helper.runAfterDelay(1000, () -> {
            int unasked = said(a) - start[0];
            helper.assertTrue(unasked == 0, "nothing unasked in 1000 ticks of a Won't-shut-up Joker: " + unasked + " (" + lastWords(a) + ")");
            int before = said(a);
            say(p, a, "follow me");
            helper.assertTrue(said(a) > before, "orders still answered: " + lastWords(a));
            String plain = lastWords(a);
            net.minecraft.world.entity.monster.Creeper c = EntityType.CREEPER.create(level);
            c.moveTo(x + 5.5, y, Z + 0.5, 0, 0);
            c.setNoAi(true);
            level.addFreshEntity(c);
            int[] w = {said(a)};
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(allWords(a).contains("Creeper near you"), "warnings still come through: " + lastWords(a));
                c.discard();
                PaData.Rec back = PaData.Rec.loadForTests(a.rec().saveForTests());
                helper.assertTrue(back.mute == 2, "quiet is kept over a save: " + back.mute);
                say(p, a, "no more jokes");
                helper.assertTrue(a.rec().mute == 1 && a.tri().serious() > 0.99, "no jokes: plain words (" + a.tri().label() + ")");
                say(p, a, "you can talk again");
                helper.assertTrue(a.rec().mute == 0 && a.tri().funny() > 0.99, "talking again, its own self: " + a.rec().mute + " " + a.tri().label());
                helper.assertTrue(com.jrpetty.mcassistant.pa.PaBanter.jokeMachine(a), "Joke Machine back");
                Kit.log("pai38 quiet: ack \"" + ack + "\"; 0 unasked in 1000 ticks; order answered \"" + plain + "\"; creeper warned; kept over a save; "
                    + "no-jokes then back: \"" + lastWords(a) + "\"");
                helper.succeed();
            });
        });
    }

    /** How many lines it has said (a line said again counts again). */
    private static int said(PlayerAssistantEntity a) {
        int n = 0;
        for (PaData.Line l : a.rec().log) if (l.who() == 1) n += Math.max(1, l.times());
        return n;
    }

    /** A small oak: a five-log trunk and a crown of leaves that stay. */
    private static void tree(ServerLevel level, BlockPos t) {
        for (int dy = 0; dy < 5; dy++) level.setBlock(t.above(dy), Blocks.OAK_LOG.defaultBlockState(), 2);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 4; dy <= 6; dy++) {
            BlockPos l = t.offset(dx, dy, dz);
            if (level.getBlockState(l).isAir()) level.setBlock(l, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
        }
    }
}
