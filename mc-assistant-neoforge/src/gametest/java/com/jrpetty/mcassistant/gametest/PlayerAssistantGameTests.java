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
        a.setPaXpForTests(0);                                     // [pa33d] level 1, whatever an earlier test's copy of it earned
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

    /** Did it say any of these, in any case? (Six or more ways to say each reply: never match one exact wording.) */
    private static boolean saidAny(PlayerAssistantEntity a, String... bits) {
        String said = allWords(a).toLowerCase(java.util.Locale.ROOT);
        for (String b : bits) if (said.contains(b.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
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
        helper.assertTrue(allWords(a).contains(a.displayNameCap()), "it introduced itself by name: " + allWords(a));   // [pa33b] the intro lines vary
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
        helper.assertTrue(lastWords(a).toLowerCase(java.util.Locale.ROOT).contains("unobtainium"), "names what it doesn't know: " + lastWords(a));
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
            helper.assertTrue(saidAny(a, "furnace"), "it said what it was doing: " + allWords(a));
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
        helper.assertTrue(a.plan.idle() && lastWords(a).toLowerCase(java.util.Locale.ROOT).contains("ground"), "it won't build on the town's ground: " + lastWords(a));
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
            helper.assertTrue(saidAny(a, "heading there", "on my way", "off i go"), "and said where it was going: " + allWords(a));
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
            say(p, a, "buy 256 cobblestone");                      // four lots asked for; the limit allows two
            int[] phase = {0}, mark = {0, 0, 0};
            helper.succeedWhen(() -> {
                if (phase[0] == 0) {
                    helper.assertTrue(a.plan.idle(), "the shopping is done (" + a.state() + ")");
                    int paid = before - com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
                    int got = p.getInventory().countItem(Items.COBBLESTONE);
                    helper.assertTrue(got > 0 && paid > 0, "it bought cobblestone with its player's coin: " + got + " for " + paid + " (" + allWords(a)
                        + "; stock " + com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.COBBLESTONE)) + ")");
                    helper.assertTrue(paid <= a.rec().spend, "never past the limit: paid " + paid + " of " + a.rec().spend);
                    helper.assertTrue(saidAny(a, "spending limit"), "and said why it stopped: " + lastWords(a));
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
        a.hunger.set(12, 0, 0);                                   // [pa33c-2b] peckish (player rules: a full bar heals without eating)
        float low = 3.0F;                                         // of its three hearts at level 1
        a.setHealth(low);
        helper.runAfterDelay(400, () -> {
            int bread = a.countMatching(st -> st.is(Items.BREAD));
            helper.assertTrue(a.getHealth() > low && bread < 4, "it ate and healed: hp " + a.getHealth() + " (from " + low + "), bread left " + bread);
            float healed = a.getHealth();
            a.removeMatching(st -> st.has(net.minecraft.core.component.DataComponents.FOOD), 999);
            a.hunger.set(4, 0, 0);                                // hungry too: no healing on its own
            a.setHealth(Math.max(1.0F, a.getMaxHealth() * 0.4F));
            helper.succeedWhen(() -> {
                String said = allWords(a).toLowerCase(java.util.Locale.ROOT);    // any of its six-plus ways to say it, any case
                helper.assertTrue(said.contains("nothing to eat") || said.contains("out of food") || said.contains("pantry is empty"),
                    "with no food it asks: " + lastWords(a));
                Kit.log("pai32 ate (hp " + low + " -> " + healed + ", bread " + bread + "/4); with none: \"" + lastWords(a) + "\"");
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
                helper.assertTrue(saidAny(a, "creeper near you", "creeper"), "warnings still come through: " + lastWords(a));
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

    /** pai39: match my energy: a rude order is done and answered in kind at its player's Language (never past the server cap,
     *  never while hushed); someone else is answered by name in kind, never ordered, defended against, rate-limited. */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "pai39_energy")
    public static void pai39_energy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_114_000, y = ground(level, x);
        ServerPlayer pa = player(helper, level, new BlockPos(x, y, Z));
        ServerPlayer pb = player(helper, level, new BlockPos(x + 4, y, Z));
        PlayerAssistantEntity a = assistant(helper, pa);
        int cap = com.jrpetty.mcassistant.pa.PaConfig.maxLanguage();
        a.rec().language = 2;                                         // Unfiltered asked for; the server's cap rules
        say(pa, a, "go get my wheat you fucking loser");
        String first = lastWords(a);
        helper.assertTrue(!a.plan.idle(), "the wheat is being fetched: " + a.state());
        String lv = cap >= 2 ? "unfiltered" : cap == 1 ? "cheeky" : "clean";
        helper.assertTrue(fromLists(first, "tone.rude." + lv, "tone.rude_again." + lv), "answered in kind at " + lv + " (cap " + cap + "): " + first);
        for (String w : first.split("[^A-Za-z*$']+")) helper.assertTrue(cap >= 2 || !com.jrpetty.mcassistant.pa.PaTone.strong(w), "never past the cap: " + first);
        say(pa, a, "stop");
        a.rec().language = 0;                                         // Clean
        say(pa, a, "get me 16 logs you useless idiot");
        String clean = lastWords(a);
        helper.assertTrue(fromLists(clean, "tone.rude.clean", "tone.rude_again.clean"), "a clean comeback: " + clean);
        for (String w : clean.split("[^A-Za-z*$']+")) helper.assertTrue(!com.jrpetty.mcassistant.pa.PaTone.swear(w) || w.equalsIgnoreCase("hell"), "Clean never swears: " + clean);
        say(pa, a, "stop");
        a.setMute(1);
        int before = said(a);
        say(pa, a, "get me 16 logs you useless idiot");
        String hushed = lastWords(a);
        helper.assertTrue(!fromLists(hushed, "tone.rude.clean", "tone.rude_again.clean") && said(a) - before == 1 && !a.plan.idle(),
            "hushed: the job and a plain reply, no roast: " + hushed);
        say(pa, a, "stop");
        a.setMute(0);
        a.rec().language = 2;
        // someone else
        String[] got = new String[6];
        int n0 = said(a);
        stranger(pb, a, "Hello");
        got[0] = since(a, n0);
        helper.assertTrue(fromLists(got[0], "stranger.polite") && got[0].contains(pb.getGameProfile().getName()), "friendly, by name: " + got[0]);
        helper.runAfterDelay(170, () -> {
            AssistantEntity.Mode m0 = a.getMode();
            int planned = a.plan.steps.size();
            int n1 = said(a);
            stranger(pb, a, "guard me");
            got[1] = since(a, n1);
            helper.assertTrue(fromLists(got[1], "stranger.order") && a.getMode() == m0 && a.plan.steps.size() == planned, "no orders from strangers: " + got[1] + " (" + a.getMode() + ")");
            helper.runAfterDelay(170, () -> {
                int n2 = said(a);
                stranger(pb, a, "You're useless");
                got[2] = since(a, n2);
                helper.assertTrue(fromLists(got[2].replaceAll(" " + java.util.regex.Pattern.quote(pa.getGameProfile().getName()) + ",.*$", ""), "stranger.roast." + lv)
                    || fromLists(got[2], "stranger.roast." + lv), "roasted back at " + lv + ": " + got[2]);
                helper.runAfterDelay(170, () -> {
                    int n3 = said(a);
                    stranger(pb, a, "Your owner's rubbish");
                    got[3] = since(a, n3);
                    helper.assertTrue(got[3].contains(pa.getGameProfile().getName()), "sticks up for its player: " + got[3]);
                    int n = said(a);
                    stranger(pb, a, "you're rubbish");                // within eight seconds
                    helper.assertTrue(said(a) == n, "one reply in eight seconds to the same player");
                    helper.runAfterDelay(170, () -> {
                        a.setMute(2);
                        int m = said(a);
                        stranger(pb, a, "You're useless");
                        helper.assertTrue(said(a) == m, "quiet: not a word to anyone");
                        a.setMute(0);
                        Kit.log("pai39 owner rude at " + lv + ": \"" + first + "\"; clean: \"" + clean + "\"; hushed: \"" + hushed + "\"; stranger: \"" + got[0]
                            + "\" / \"" + got[1] + "\" / \"" + got[2] + "\" / \"" + got[3] + "\"; rate-limited; quiet silences");
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** pai40: its bed: "use this bed" makes it its spawn, it comes back there after a fall, "place a bed here" puts one down
     *  from its pack, and a broken bed falls back (with a word). */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pai40_bed")
    public static void pai40_bed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_117_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        Waiter w = Waiter.on(helper);
        BlockPos foot = new BlockPos(x + 8, y, Z + 6), head = foot.north();
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
        level.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        say(p, a, "use this bed", foot, -1);
        helper.assertTrue(head.equals(a.rec().bed) && head.equals(a.rec().lastBed), "use this bed: its spawn (" + a.rec().bed + ", " + lastWords(a) + ")");
        say(p, a, "where's your bed");
        helper.assertTrue(lastWords(a).contains(head.getX() + " " + head.getY() + " " + head.getZ()), "says where: " + lastWords(a));
        // knocked out far away: it comes back at its bed
        a.teleportTo(x + 30, y, Z + 30);
        a.hurt(level.damageSources().generic(), 1000F);
        helper.runAfterDelay(20, () -> {
            PaData.Rec r = a.rec() != null ? a.rec() : PaData.first(p.getUUID());
            r.deadUntil = level.getGameTime();                    // its time is up
            w.await(helper, 200, () -> { PlayerAssistantEntity q = com.jrpetty.mcassistant.pa.PaLife.find(p); return q != null && q.blockPosition().closerThan(head, 3.5); }, () -> {
                PlayerAssistantEntity b = com.jrpetty.mcassistant.pa.PaLife.find(p);
                helper.assertTrue(b != null && b.blockPosition().closerThan(head, 3.5), "back at its bed: " + (b == null ? "none" : b.blockPosition().toShortString()));
                // place a bed: one from its pack, put down near its player, and its spawn after
                b.insertGiven(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BLUE_BED));
                say(p, b, "place a bed here");
                helper.runAfterDelay(200, () -> {
                    PaData.Rec r2 = b.rec();
                    helper.assertTrue(r2.bed != null && !r2.bed.equals(head) && level.getBlockState(r2.bed).is(Blocks.BLUE_BED), "placed a bed of its own: " + r2.bed + " (" + lastWords(b) + ")");
                    BlockPos placed = r2.bed;
                    // that bed broken: it falls back to beside its player, and says so
                    level.setBlock(placed, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(placed.relative(level.getBlockState(placed).isAir() ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.NORTH), Blocks.AIR.defaultBlockState(), 3);
                    r2.lastBed = placed;
                    b.hurt(level.damageSources().generic(), 1000F);
                    helper.runAfterDelay(20, () -> {
                        PaData.Rec r3 = PaData.first(p.getUUID());
                        r3.deadUntil = level.getGameTime();
                        helper.runAfterDelay(30, () -> {
                            PlayerAssistantEntity c = com.jrpetty.mcassistant.pa.PaLife.find(p);
                            helper.assertTrue(c != null && allWords(c).toLowerCase().contains("bed"), "a broken bed: back elsewhere, and says so: " + (c == null ? "none" : lastWords(c)));
                            Kit.log("pai40 bed: use-this-bed spawn " + head.toShortString() + "; respawned at it; placed " + placed.toShortString() + "; broken -> \"" + lastWords(c) + "\"");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }

    /** pai41: hearts and hunger: three hearts at level 1, a hunger bar that work drains, eating the best food for the moment
     *  (never rotten flesh when there's better, golden apples kept), healing only while fed, kept over a save. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pai41_hunger")
    public static void pai41_hunger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_120_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.removeMatching(st -> st.has(net.minecraft.core.component.DataComponents.FOOD), 999);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(Math.abs(a.getMaxHealth() - 6.0F) < 0.01F, "three hearts at level 1: " + a.getMaxHealth());
            int before = a.hunger.food();
            for (int i = 0; i < 40; i++) a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            a.hunger.exhaust(30F);                                // a hard stint
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(a.hunger.food() < before || a.hunger.saturation() < 5.0F, "work drains it: food " + a.hunger.food() + ", saturation " + a.hunger.saturation());
                a.hunger.set(10, 0, 0);
                a.setHealth(3.0F);
                a.insertGiven(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ROTTEN_FLESH, 4));
                a.insertGiven(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 2));
                a.insertGiven(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF, 1));
                a.insertGiven(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 1));
                helper.runAfterDelay(30, () -> {
                    int beef = a.countMatching(st -> st.is(net.minecraft.world.item.Items.COOKED_BEEF)),
                        flesh = a.countMatching(st -> st.is(net.minecraft.world.item.Items.ROTTEN_FLESH)),
                        apple = a.countMatching(st -> st.is(net.minecraft.world.item.Items.GOLDEN_APPLE));
                    helper.assertTrue(beef == 0 && flesh == 4 && apple == 1, "hurt and hungry: the steak first, no rotten flesh, the golden apple kept (beef " + beef + ", flesh " + flesh + ", apple " + apple + ")");
                    helper.assertTrue(a.hunger.food() > 10, "fed: " + a.hunger.food());
                    CompoundTag t = a.saveWithoutId(new CompoundTag());
                    helper.assertTrue(t.getInt("PaFood") == a.hunger.food(), "kept over a save: " + t.getInt("PaFood"));
                    Kit.log("pai41 hunger: max " + a.getMaxHealth() + " HP at level 1; ate the steak (food now " + a.hunger.food() + ", saturation " + a.hunger.saturation() + ")");
                    helper.succeed();
                });
            });
        });
    }

    private static void stranger(ServerPlayer p, PlayerAssistantEntity a, String text) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", a.getId());
        t.putString("text", text);
        PaServer.act(p, "stranger", t);
    }

    /** Is this said line one of these lists' lines (blanks filled with anything)? */
    private static boolean fromLists(String said, String... keys) {
        for (String k : keys) for (String c : new String[]{"", "@gloomy", "@serious", "@sf", "@sg", "@fg"})
            for (String l : com.jrpetty.mcassistant.pa.PaLines.get(k + c)) {
                String re = java.util.regex.Pattern.quote(l).replaceAll("\\{[a-z]+\\}", "\\\\E.*\\\\Q");
                if (said.matches(re + ".*")) return true;
            }
        return false;
    }

    /** The first line it said after it had said so many (or "" if none). */
    private static String since(PlayerAssistantEntity a, int n) {
        int k = 0;
        for (PaData.Line l : a.rec().log) {
            if (l.who() != 1) continue;
            k += Math.max(1, l.times());
            if (k > n) return l.text();
        }
        return "";
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

    /** pai42: pings: a wheel choice is the order ("chop that" at the pinged tree), a plain marker never is, nor is anyone
     *  else's ping; "remember this spot as the mine" and "go to the mine"; "ping the nearest iron"; "chop that, and that"
     *  with two looks; "do the tree first", "skip that one", "cancel that ping"; an area's two corners cleared. [pa33c-2b] */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pai42_pings")
    public static void pai42_pings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_123_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_AXE));
        a.insertGiven(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        BlockPos t1 = new BlockPos(x + 12, y, Z + 4), t2 = new BlockPos(x + 12, y, Z - 6);
        tree(level, t1, 5);
        tree(level, t2, 4);
        level.setBlock(new BlockPos(x - 9, y - 3, Z + 2), Blocks.IRON_ORE.defaultBlockState(), 3);
        // a bare marker, shared: no order, nothing in its list
        CompoundTag mark = new CompoundTag();
        mark.putInt("x", t1.getX()); mark.putInt("y", t1.getY()); mark.putInt("z", t1.getZ()); mark.putString("label", "Oak tree");
        PaServer.act(p, "mark", mark);
        helper.assertTrue(a.plan.all().isEmpty(), "a bare ping is no order: " + a.plan.all().size());
        // someone else's ping choice: never an order for this one
        ServerPlayer other = player(helper, level, new BlockPos(x + 2, y, Z + 2));
        PaServer.act(other, "ping", pingTag("TREE", "chop", t1, null));
        helper.assertTrue(a.plan.all().isEmpty(), "another player's ping orders no one's assistant");
        // the wheel's "Chop that" on the pinged tree
        PaServer.act(p, "ping", pingTag("TREE", "chop", t1.above(2), null));
        helper.assertTrue(labels(a).contains("chop that tree"), "wheel choice -> job: " + labels(a) + " (" + lastWords(a) + ")");
        // a waypoint, and going to it
        say(p, a, "remember this spot as the mine", new BlockPos(x - 6, y - 1, Z - 6), -1);
        helper.assertTrue(a.rec().waypoints.containsKey("mine"), "waypoint saved: " + a.rec().waypoints.keySet() + " (" + lastWords(a) + ")");
        say(p, a, "go to the mine");
        helper.assertTrue(labels(a).contains("go to mine"), "goes to the mine: " + labels(a));
        // reordered, skipped, cancelled
        say(p, a, "do the mine first");
        helper.assertTrue(a.plan.all().get(0).label.equals("go to mine"), "the mine first: " + labels(a) + " (" + lastWords(a) + ")");
        int before = a.plan.total();
        say(p, a, "skip that one");
        helper.assertTrue(a.plan.total() == before - 1 && !labels(a).contains("go to mine"), "skipped: " + labels(a));
        say(p, a, "cancel that ping");
        helper.assertTrue(a.plan.total() == before - 2, "cancelled: " + labels(a) + " (" + lastWords(a) + ")");
        // the nearest iron, pinged
        say(p, a, "ping the nearest iron");
        helper.assertTrue(fromLists(lastWords(a), "say.find_found") && lastWords(a).toLowerCase().contains("iron"), "found iron: " + lastWords(a));
        // two "that"s with two looks: two trees, in order
        CompoundTag two = new CompoundTag();
        two.putString("text", "chop that, and that");
        two.putInt("bx", t1.getX()); two.putInt("by", t1.getY() + 1); two.putInt("bz", t1.getZ());
        two.putIntArray("looks", new int[]{t1.getX(), t1.getY() + 1, t1.getZ(), t2.getX(), t2.getY() + 1, t2.getZ()});
        PaServer.act(p, "talk", two);
        long chops = a.plan.all().stream().filter(s -> s.label.equals("chop that tree")).count();
        helper.assertTrue(chops == 2, "two trees for two \"that\"s: " + labels(a) + " (" + lastWords(a) + ")");
        // an area between two corners (a moment later: pings are held to one a quarter second)
        say(p, a, "stop");
        helper.runAfterDelay(10, () -> {
            PaServer.pingRateForTests(p.getUUID());               // game ticks outrun the clock here
            PaServer.act(p, "ping", pingTag("AREA", "clear", new BlockPos(x - 4, y, Z + 8), new BlockPos(x + 2, y, Z + 12)));
            helper.assertTrue(labels(a).toLowerCase().contains("clear"), "area cleared: " + labels(a) + " (" + lastWords(a) + ")");
            Kit.log("pai42 pings: " + labels(a) + "; waypoints " + a.rec().waypoints.keySet());
            helper.succeed();
        });
    }

    private static CompoundTag pingTag(String target, String opt, BlockPos at, BlockPos corner) {
        CompoundTag t = new CompoundTag();
        t.putString("target", target);
        t.putString("opt", opt);
        t.putString("label", target.toLowerCase());
        t.putInt("x", at.getX()); t.putInt("y", at.getY()); t.putInt("z", at.getZ());
        if (corner != null) { t.putInt("x2", corner.getX()); t.putInt("y2", corner.getY()); t.putInt("z2", corner.getZ()); }
        return t;
    }

    private static String labels(PlayerAssistantEntity a) {
        StringBuilder b = new StringBuilder();
        for (var s : a.plan.all()) b.append(s.label).append("; ");
        return b.toString();
    }

    /** pai43: the Pack tab: two rows open at first (new piles never start in the third), things handed over both ways and
     *  onto its gear, Equip best, Sort, Keep, Dump junk and Give me everything (kept things stay). [pa33c-2b] */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pai43_pack")
    public static void pai43_pack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_126_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        helper.runAfterDelay(5, () -> {
            var inv = a.getInventoryItems();
            for (int i = 0; i < inv.size(); i++) inv.set(i, net.minecraft.world.item.ItemStack.EMPTY);
            a.teleportTo(x + 2, y, Z + 1);
            int open = com.jrpetty.mcassistant.pa.PaPack.open(a.veteranLevel());
            // fill every open slot with a different thing: the next new pile has nowhere to go
            net.minecraft.world.item.Item[] fill = {Items.STICK, Items.OAK_PLANKS, Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.TORCH, Items.COAL,
                Items.RAW_IRON, Items.IRON_INGOT, Items.BREAD, Items.APPLE, Items.WHEAT_SEEDS, Items.STRING, Items.FLINT, Items.FEATHER, Items.BONE, Items.ROTTEN_FLESH,
                Items.CLAY_BALL, Items.BRICK, Items.GLASS, Items.LADDER, Items.OAK_DOOR, Items.CHEST, Items.FURNACE, Items.CRAFTING_TABLE, Items.BUCKET};
            for (int i = 0; i < open; i++) a.insertGiven(new net.minecraft.world.item.ItemStack(fill[i], 8));
            net.minecraft.world.item.ItemStack left = a.insertGiven(new net.minecraft.world.item.ItemStack(Items.DIAMOND, 2));
            helper.assertTrue(open == 27 || (!left.isEmpty() && inv.get(open).isEmpty() && a.isPackFull()), "closed row stays closed: open " + open + ", left " + left);
            helper.assertTrue(a.insertGiven(new net.minecraft.world.item.ItemStack(Items.STICK, 8)).isEmpty(), "piles still grow");
            // take one to you, give one back into a chosen free slot
            pack(p, "take", "i", 0);
            helper.assertTrue(inv.get(0).isEmpty() && p.getInventory().countItem(Items.STICK) == 16, "taken: " + p.getInventory().countItem(Items.STICK));
            p.getInventory().setItem(3, new net.minecraft.world.item.ItemStack(Items.IRON_CHESTPLATE));
            CompoundTag give = new CompoundTag();
            give.putString("op", "give"); give.putInt("j", 3); give.putInt("i", 0);
            PaServer.act(p, "packop", give);
            helper.assertTrue(inv.get(0).is(Items.IRON_CHESTPLATE) && p.getInventory().getItem(3).isEmpty(), "given into slot 0: " + inv.get(0));
            // Equip best: the chestplate goes on
            pack(p, "best", null, 0);
            helper.assertTrue(a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "equipped best: " + a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
            // keep the dirt, then dump junk: the rotten flesh and gravel go, the kept dirt stays
            int dirtAt = -1;
            for (int i = 0; i < inv.size(); i++) if (inv.get(i).is(Items.DIRT)) dirtAt = i;
            pack(p, "lock", "i", dirtAt);
            helper.assertTrue(a.rec().keep.contains("minecraft:dirt"), "dirt kept");
            pack(p, "dump", null, 0);
            helper.assertTrue(a.countMatching(st -> st.is(Items.ROTTEN_FLESH) || st.is(Items.GRAVEL)) == 0 && a.countMatching(st -> st.is(Items.DIRT)) == 8,
                "junk dumped, kept dirt stays: dirt " + a.countMatching(st -> st.is(Items.DIRT)));
            // sort: tools/armour, food, ores... laid from the first slot, no gaps
            pack(p, "sort", null, 0);
            boolean gap = false, seenEmpty = false;
            for (int i = 0; i < inv.size(); i++) { if (inv.get(i).isEmpty()) seenEmpty = true; else if (seenEmpty) gap = true; }
            helper.assertTrue(!gap, "sorted with no gaps");
            // give me everything: all but the kept dirt
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            pack(p, "all", null, 0);
            int leftIn = a.countMatching(st -> !st.isEmpty());
            helper.assertTrue(a.countMatching(st -> st.is(Items.DIRT)) == 8 && a.countMatching(st -> !st.is(Items.DIRT)) == 0, "everything but the kept: " + leftIn + " left");
            // too far: nothing changes hands
            a.teleportTo(x + 30, y, Z + 30);
            p.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(Items.BREAD, 3));
            CompoundTag far = new CompoundTag();
            far.putString("op", "give"); far.putInt("j", 0);
            PaServer.act(p, "packop", far);
            helper.assertTrue(p.getInventory().getItem(0).getCount() == 3, "too far to hand over");
            Kit.log("pai43 pack: open " + open + ", kept " + a.rec().keep);
            helper.succeed();
        });
    }

    private static void pack(ServerPlayer p, String op, String key, int v) {
        CompoundTag t = new CompoundTag();
        t.putString("op", op);
        if (key != null) t.putInt(key, v);
        PaServer.act(p, "packop", t);
    }

    /** pai44: gestures with its words: a hello waves, an owned bad joke shrugs, a tagged line plays its own; each wears off;
     *  quiet, none. [pa33c-2b] */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pai44_gestures")
    public static void pai44_gestures(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_129_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        helper.runAfterDelay(5, () -> {
            com.jrpetty.mcassistant.pa.PaTalk.reply(a, "Hello again.");
            int wave = com.jrpetty.mcassistant.entity.FolkAction.Act.WAVE.ordinal();
            helper.assertTrue((a.animValue() & 0xFF) == wave, "a hello waves: " + (a.animValue() & 0xFF));
            String bad = null;
            for (var e : com.jrpetty.mcassistant.pa.PaLines.all().entrySet()) {
                for (String l : e.getValue()) if (bad == null && com.jrpetty.mcassistant.pa.PaLines.bad(l) && !l.contains("{")
                    && com.jrpetty.mcassistant.pa.PaAnimWords.tagged(com.jrpetty.mcassistant.pa.PaLines.tags(l)) == null) bad = l;
            }
            helper.assertTrue(bad != null, "an owned bad joke to tell");
            int turn = a.animValue() >> 8;
            com.jrpetty.mcassistant.pa.PaTalk.reply(a, bad);
            helper.assertTrue((a.animValue() & 0xFF) == com.jrpetty.mcassistant.entity.FolkAction.Act.SHRUG.ordinal() && (a.animValue() >> 8) != turn, "a bad joke shrugs");
            helper.runAfterDelay(80, () -> {
                helper.assertTrue((a.animValue() & 0xFF) == 0, "it wears off: " + (a.animValue() & 0xFF));
                a.setMute(2);
                com.jrpetty.mcassistant.pa.PaTalk.reply(a, "Hello again.");
                helper.assertTrue((a.animValue() & 0xFF) == 0, "quiet: no flourish");
                a.setMute(0);
                Kit.log("pai44 gestures: wave, shrug, wore off, none when quiet");
                helper.succeed();
            });
        });
    }

    /** pai45: the ten-level grind: work pays experience (scaled by difficulty), a level gained brings the moment (a cheer,
     *  a line, its hearts and its legs), the server's multiplier speeds it, only operators may change it, and it is kept
     *  over a save. [pa33d] */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pai45_levels")
    public static void pai45_levels(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_132_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        com.jrpetty.mcassistant.pa.PaConfig.xpMultiplierForTests(null);
        a.setPaXpForTests(0);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(a.veteranLevel() == 1, "a new one is level 1: " + a.veteranLevel());
            float speed1 = (float) a.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            long before = a.paXp();
            a.earn("job");
            helper.assertTrue(a.paXp() > before, "a job pays");
            int said = said(a);
            a.setPaXpForTests(com.jrpetty.mcassistant.pa.PaLevels.xpFor(4) - 1);
            a.earn("job");
            helper.assertTrue(a.veteranLevel() == 4, "level 4: " + a.veteranLevel());
            helper.assertTrue((a.animValue() & 0xFF) == com.jrpetty.mcassistant.entity.FolkAction.Act.CHEER.ordinal(), "a cheer");
            helper.assertTrue(said(a) > said && fromLists(lastWords(a), "say.levelup") && lastWords(a).contains("4"), "a line for it: " + lastWords(a));
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(a.getMaxHealth() > 6.0F, "more hearts: " + a.getMaxHealth());
                helper.assertTrue(a.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) > speed1, "quicker on its feet");
                helper.assertTrue(a.experiencePercent() > 0, "quicker at the work");
                // the multiplier: three times the pay
                long b2 = a.paXp();
                a.earn("build");
                long one = a.paXp() - b2;
                com.jrpetty.mcassistant.pa.PaConfig.xpMultiplierForTests(3.0);
                long b3 = a.paXp();
                a.earn("build");
                helper.assertTrue(a.paXp() - b3 == one * 3, "the multiplier: " + one + " then " + (a.paXp() - b3));
                com.jrpetty.mcassistant.pa.PaConfig.xpMultiplierForTests(null);
                // only an operator may change it
                CompoundTag ad = new CompoundTag();
                ad.putDouble("xpMult", 50);
                PaServer.act(p, "admin", ad);
                helper.assertTrue(com.jrpetty.mcassistant.pa.PaConfig.xpMultiplier() == 1.0, "not an operator: unchanged");
                // kept over a save
                CompoundTag saved = new CompoundTag();
                a.addAdditionalSaveData(saved);
                helper.assertTrue(saved.getLong("PaXp") == a.paXp(), "saved");
                // and kept in its record: a body stood up afresh (its copy lost) is not back at level 1
                helper.assertTrue(a.rec().xp == a.paXp(), "kept in its record: " + a.rec().xp);
                PlayerAssistantEntity fresh = com.jrpetty.mcassistant.McAssistantMod.PLAYER_ASSISTANT.get().create(level);
                fresh.restoreXp(a.rec().xp);
                helper.assertTrue(fresh.veteranLevel() == a.veteranLevel(), "a fresh body keeps the level: " + fresh.veteranLevel());
                fresh.discard();
                Kit.log("pai45 levels: level " + a.veteranLevel() + ", xp " + a.paXp() + ", hp " + a.getMaxHealth());
                helper.succeed();
            });
        });
    }

    /** pai46: bedtime: "go to bed" at night and it walks to its bed and lies down; a monster close by wakes it; "wake up"
     *  gets it up; morning gets it up; in the day "go to bed" waits for dark. [pa33d] */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "pai46_sleep")
    public static void pai46_sleep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_135_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        Waiter w = Waiter.on(helper);
        BlockPos foot = new BlockPos(x + 6, y, Z + 3), head = foot.north();
        level.setBlock(foot, Blocks.WHITE_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
        level.setBlock(head, Blocks.WHITE_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        // the night's own monsters kept away (they'd rightly keep it up): only the test's zombie comes near
        net.minecraft.world.entity.Entity[] ours = {null};
        boolean[] trace = {false};
        helper.onEachTick(() -> {
            if (trace[0] && helper.getTick() % 20 == 0) Kit.log("pai46 at " + a.blockPosition().toShortString() + " sleeping " + a.isSleeping() + " wanted " + a.sleepWanted
                + " nav done " + a.getNavigation().isDone() + " goals " + a.goalSelectorForTests() + " mode " + a.getMode() + " plan idle " + a.plan.idle() + " task " + a.task);
            for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,      // slimes too (a flat world's night)
                    new net.minecraft.world.phys.AABB(head).inflate(24), e -> e instanceof net.minecraft.world.entity.monster.Enemy && e != ours[0])) m.discard();
        });
        say(p, a, "use this bed", foot, -1);
        say(p, a, "go to bed");
        helper.assertTrue(!a.isSleeping() && fromLists(lastWords(a), "say.bed_day"), "in the day it waits for dark: " + lastWords(a));
        level.setDayTime(14000);
        say(p, a, "go to bed");
        helper.assertTrue(fromLists(lastWords(a), "say.bed_now"), "bedtime: " + lastWords(a));
        helper.runAfterDelay(160, () -> {
            helper.assertTrue(a.isSleeping() && a.getPose() == net.minecraft.world.entity.Pose.SLEEPING, "asleep in its bed: " + a.getPose() + " at " + a.blockPosition().toShortString());
            BlockPos at = a.blockPosition();
            helper.runAfterDelay(40, () -> {
                helper.assertTrue(a.isSleeping() && a.blockPosition().equals(at), "lies still");
                // a monster close by: up at once
                net.minecraft.world.entity.monster.Zombie z = EntityType.ZOMBIE.create(level);
                z.moveTo(head.getX() + 3.5, y, head.getZ() + 0.5, 0, 0);
                z.setNoAi(true);
                ours[0] = z;
                level.addFreshEntity(z);
                helper.runAfterDelay(30, () -> {
                    helper.assertTrue(!a.isSleeping() && fromLists(lastWords(a), "say.woke_danger"), "a monster wakes it: " + lastWords(a));
                    z.discard();
                    say(p, a, "go to bed");
                    trace[0] = true;
                    w.await(helper, 400, a::isSleeping, () -> {
                        trace[0] = false;
                        helper.assertTrue(a.isSleeping(), "back to bed (wanted " + a.sleepWanted + ", target " + a.getTarget() + ", at " + a.blockPosition().toShortString() + ", " + lastWords(a) + ")");
                        say(p, a, "wake up");
                        helper.assertTrue(!a.isSleeping() && fromLists(lastWords(a), "say.woke_told"), "\"wake up\": " + lastWords(a));
                        say(p, a, "go to bed");
                        w.await(helper, 400, a::isSleeping, () -> {
                            helper.assertTrue(a.isSleeping(), "to bed a third time");
                            level.setDayTime(24000 + 500);
                            helper.runAfterDelay(30, () -> {
                                helper.assertTrue(!a.isSleeping(), "morning: up");
                                Kit.log("pai46 sleep: bed day/night, asleep, woke for a zombie, wake up, morning");
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        });
    }

    private static void atLevel(PlayerAssistantEntity a, int lv) {
        a.setPaXpForTests(com.jrpetty.mcassistant.pa.PaLevels.xpFor(lv));
    }

    /** pai47: skills by level: locked ones say when they open; taming a wolf for its player; trading with a villager with
     *  its emeralds; pillaring up. [pa33d] */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pai47_skills")
    public static void pai47_skills(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_138_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 1);
        say(p, a, "enchant my sword");
        helper.assertTrue(fromLists(lastWords(a), "say.skill_locked") && lastWords(a).contains("6"), "locked till level 6: " + lastWords(a));
        atLevel(a, 3);
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.BONE, 30));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.EMERALD, 5));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 16));
        net.minecraft.world.entity.animal.Wolf wolf = EntityType.WOLF.create(level);
        wolf.moveTo(x + 6.5, y, Z + 2.5, 0, 0);
        wolf.setNoAi(true);
        level.addFreshEntity(wolf);
        say(p, a, "tame that wolf", null, wolf.getId());
        helper.runAfterDelay(400, () -> {
            helper.assertTrue(wolf.isTame() && wolf.isOwnedBy(p), "tamed for its player: " + wolf.isTame() + " (" + lastWords(a) + ")");
            net.minecraft.world.entity.npc.Villager v = EntityType.VILLAGER.create(level);
            v.moveTo(x - 5.5, y, Z + 2.5, 0, 0);
            v.setNoAi(true);
            v.setVillagerData(v.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.FARMER).setLevel(1));
            level.addFreshEntity(v);
            boolean sellsBread = v.getOffers().stream().anyMatch(o -> o.getResult().is(Items.BREAD));
            int bread = a.countMatching(st -> st.is(Items.BREAD));
            say(p, a, "buy bread from that villager", null, v.getId());
            helper.runAfterDelay(200, () -> {
                int got = a.countMatching(st -> st.is(Items.BREAD)) - bread;
                helper.assertTrue(!sellsBread || got > 0 && a.countMatching(st -> st.is(Items.EMERALD)) < 5, "bought bread with its emeralds: " + got + " (" + lastWords(a) + ")");
                int y0 = a.getBlockY();
                say(p, a, "pillar up 4");
                helper.runAfterDelay(160, () -> {
                    helper.assertTrue(a.getBlockY() >= y0 + 4 && level.getBlockState(a.blockPosition().below()).is(Blocks.COBBLESTONE), "pillared up 4: " + (a.getBlockY() - y0) + " (" + lastWords(a) + ")");
                    Kit.log("pai47 skills: locked line, wolf tamed, " + got + " bread bought, pillared " + (a.getBlockY() - y0));
                    helper.succeed();
                });
            });
        });
    }

    /** pai48: moves by level: a trick shot at a target block (level 10: on target), a water-bucket clutch on a long fall
     *  (level 8: no harm, the water back in the bucket), an ender pearl to catch up (level 9). [pa33d] */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "pai48_moves")
    public static void pai48_moves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_141_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        // where a pearl lands, watched each tick (it may walk back to its post after, as told to stay); scheduled from the
        // top, as a test that adds dozens of delayed checks from inside one upsets the test runner's own list
        net.minecraft.world.phys.Vec3[] pearlTo = {null};
        double[] pearlNearest = {Double.MAX_VALUE};
        helper.onEachTick(() -> { if (pearlTo[0] != null) pearlNearest[0] = Math.min(pearlNearest[0], a.position().distanceTo(pearlTo[0])); });
        atLevel(a, 10);
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(Items.BOW));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.ARROW, 16));
        BlockPos target = new BlockPos(x + 12, y + 1, Z);
        level.setBlock(target, Blocks.TARGET.defaultBlockState(), 3);
        a.teleportTo(x + 0.5, y, Z + 0.5);
        int arrows = a.countMatching(st -> st.is(Items.ARROW));
        say(p, a, "shoot that target", target, -1);
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(a.countMatching(st -> st.is(Items.ARROW)) < arrows, "it shot");
            helper.assertTrue(fromLists(lastWords(a), "say.trick_hit", "say.trick_long"), "level 10: on target: " + lastWords(a));
            // the clutch: up twenty blocks with a water bucket
            atLevel(a, 8);
            a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
            a.insertGiven(new net.minecraft.world.item.ItemStack(Items.WATER_BUCKET));
            say(p, a, "stay here");
            a.setHealth(a.getMaxHealth());
            float hp = a.getHealth();
            a.teleportTo(x + 4.5, y + 22, Z + 6.5);
            helper.runAfterDelay(80, () -> {
                helper.assertTrue(a.getHealth() >= a.getMaxHealth() - 0.01F && a.getLastDamageSource() == null, "no harm from the fall: " + a.getHealth() + " of " + a.getMaxHealth()
                    + ", hurt by " + (a.getLastDamageSource() == null ? "nothing" : a.getLastDamageSource().getMsgId()) + " | " + lastWords(a));
                helper.assertTrue(a.countMatching(st -> st.is(Items.WATER_BUCKET)) == 1, "the water back in the bucket");
                boolean wet = false;
                for (BlockPos q : BlockPos.betweenClosed(new BlockPos(x + 2, y - 1, Z + 4), new BlockPos(x + 7, y + 2, Z + 9))) if (level.getBlockState(q).is(Blocks.WATER)) wet = true;
                helper.assertTrue(!wet, "no water left behind");
                // the pearl
                atLevel(a, 9);
                a.insertGiven(new net.minecraft.world.item.ItemStack(Items.ENDER_PEARL, 2));
                a.teleportTo(x + 0.5, y, Z + 0.5);
                com.jrpetty.mcassistant.pa.PaMoves.throwPearlForTests(a, new net.minecraft.world.phys.Vec3(x + 16.5, y, Z + 0.5));
                // where it lands (it may walk back to its post after, as told to stay)
                pearlTo[0] = new net.minecraft.world.phys.Vec3(x + 16.5, y, Z + 0.5);   // watched each tick (see the top)
                helper.runAfterDelay(80, () -> {
                    helper.assertTrue(pearlNearest[0] < 4.0, "pearled across: came within " + pearlNearest[0] + ", now " + a.blockPosition().toShortString());
                    Kit.log("pai48 moves: trick shot hit, clutch, pearl");
                    helper.succeed();
                });
            });
        });
    }

    /** pai49: better gear from its pack (iron ingots into a pickaxe and a sword, put on); a run to a marker against its
     *  player (it wins against one who stands still, and says so). [pa33d] */
    @GameTest(template = EMPTY, timeoutTicks = 1500, batch = "pai49_gear_race")
    public static void pai49_gear_race(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_144_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 6);
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.IRON_INGOT, 5));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.STICK, 8));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.OAK_PLANKS, 16));
        say(p, a, "upgrade your gear");
        helper.runAfterDelay(500, () -> {
            boolean pick = a.countMatching(st -> st.is(Items.IRON_PICKAXE)) > 0 || a.getMainHandItem().is(Items.IRON_PICKAXE);
            boolean sword = a.countMatching(st -> st.is(Items.IRON_SWORD)) > 0 || a.getMainHandItem().is(Items.IRON_SWORD);
            helper.assertTrue(pick && sword, "an iron pickaxe and sword: " + pick + ", " + sword + " (" + lastWords(a) + ")");
            BlockPos goal = new BlockPos(x + 20, y - 1, Z + 4);
            CompoundTag t = new CompoundTag();
            t.putString("text", "race me to that tree");
            t.putInt("bx", goal.getX()); t.putInt("by", goal.getY()); t.putInt("bz", goal.getZ());
            PaServer.act(p, "talk", t);
            helper.assertTrue(fromLists(lastWords(a), "say.challenge_race"), "on your marks: " + lastWords(a));
            helper.succeedWhen(() -> {
                helper.assertTrue(fromLists(lastWords(a), "say.challenge_won", "tone.challenge.won.cheeky", "tone.challenge.won.unfiltered")
                    || lastWords(a).toLowerCase().contains("win"), "it won the run: " + lastWords(a));
                Kit.log("pai49: upgraded, won the run: " + lastWords(a));
            });
        });
    }

    /** pai50: brewing at a stand (level 5): bottles filled at the water, wart and fuel in, then the ingredient: three
     *  healing potions in its pack. [pa33d] */
    @GameTest(template = EMPTY, timeoutTicks = 2000, batch = "pai50_brew")
    public static void pai50_brew(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_147_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 5);
        BlockPos stand = new BlockPos(x + 4, y, Z + 2);
        level.setBlock(stand, Blocks.BREWING_STAND.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x - 3, y - 1, Z + 3), Blocks.WATER.defaultBlockState(), 3);
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.GLASS_BOTTLE, 3));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.NETHER_WART, 2));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.BLAZE_POWDER, 2));
        a.insertGiven(new net.minecraft.world.item.ItemStack(Items.GLISTERING_MELON_SLICE, 2));
        say(p, a, "brew some healing potions");
        helper.succeedWhen(() -> {
            long healing = a.getInventoryItems().stream().filter(st -> st.is(Items.POTION) && st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS) != null
                && st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS).is(net.minecraft.world.item.alchemy.Potions.HEALING)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            helper.assertTrue(healing == 3, "three healing potions: " + healing + " (" + lastWords(a) + ")");
            Kit.log("pai50 brew: " + lastWords(a));
        });
    }

    /** [pa33e] One wait at a time, checked each tick by a listener set up at the test's start (a full run is slow to settle:
     *  new chunks, a busy server), so a test waits for what it's waiting for rather than a fixed count. */
    private static final class Waiter {
        java.util.function.BooleanSupplier until;
        Runnable then;
        long deadline;

        static Waiter on(GameTestHelper h) {
            Waiter w = new Waiter();
            h.onEachTick(() -> {
                if (w.until == null) return;
                if (w.until.getAsBoolean() || h.getTick() >= w.deadline) { Runnable r = w.then; w.until = null; r.run(); }
            });
            return w;
        }

        /** Then (asserting as it likes) once it holds, or after so many ticks at most. */
        void await(GameTestHelper h, int most, java.util.function.BooleanSupplier until, Runnable then) {
            this.then = then;
            this.deadline = h.getTick() + most;
            this.until = until;
        }
    }

    /** The chunks round a spot loaded with their creatures, and ticking. */
    private static boolean entitiesReady(ServerLevel l, int bx, int bz) {
        for (int cx = (bx >> 4) - 1; cx <= (bx >> 4) + 1; cx++)
            for (int cz = (bz >> 4) - 1; cz <= (bz >> 4) + 1; cz++)
                if (!l.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz)) || !l.isPositionEntityTicking(new BlockPos(cx * 16 + 8, 64, cz * 16 + 8))) return false;
        return true;
    }

    /** The chunks round a spot in another dimension held loaded (a real player's arrival loads them; a mock player's tickets lag). */
    private static void forceAround(ServerLevel l, int bx, int bz, boolean on) {
        for (int cx = (bx >> 4) - 1; cx <= (bx >> 4) + 1; cx++) for (int cz = (bz >> 4) - 1; cz <= (bz >> 4) + 1; cz++) l.setChunkForced(cx, cz, on);
    }

    /** pai51: through a portal with its player, at once: carried across beside them the moment they arrive, never stood
     *  in the arrival portal (where, as a mob, it would bounce straight back out), the same body a while later, and home
     *  again with them. [pa33e] */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pai51_portal")
    public static void pai51_portal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_150_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        Waiter w = Waiter.on(helper);
        long[] crossedAt = {-1};
        int[] cameAt = {-1};
        helper.onEachTick(() -> {
            if (crossedAt[0] < 0 || cameAt[0] >= 0) return;
            PlayerAssistantEntity q = PaLife.find(p);
            if (q != null && q.level() != level) cameAt[0] = (int) (helper.getTick() - crossedAt[0]);
        });
        ServerLevel nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        // an arrival portal on an obsidian ledge in the Nether
        int nx = x / 8, ny = 100, nz = Z / 8;
        forceAround(nether, nx, nz, true);                          // loaded as a real player's arrival loads it (a mock player's tickets lag)
        for (BlockPos q : BlockPos.betweenClosed(nx - 6, ny - 1, nz - 6, nx + 6, ny + 5, nz + 6))
            nether.setBlock(q, q.getY() == ny - 1 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        for (int dx = -1; dx <= 2; dx++)
            for (int dy = 0; dy <= 4; dy++)
                nether.setBlock(new BlockPos(nx + dx, ny - 1 + dy, nz), dx == -1 || dx == 2 || dy == 0 || dy == 4 ? Blocks.OBSIDIAN.defaultBlockState()
                    : Blocks.NETHER_PORTAL.defaultBlockState().setValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS, Direction.Axis.X), 18);
        // as a real arrival: the player only gets there once the place is loaded and ticking (a busy server takes a while)
        w.await(helper, 1000, () -> entitiesReady(nether, nx, nz), () -> {
            helper.assertTrue(a.level() == level, "it starts beside its player");
            p.teleportTo(nether, nx + 0.5, ny, nz + 0.5, Set.of(), 0.0F, 0.0F);      // arrived in the portal, as through it
            crossedAt[0] = helper.getTick();                                          // watched each tick (the new chunk's tickets take a moment)
            w.await(helper, 300, () -> cameAt[0] >= 0, () -> {
                PlayerAssistantEntity b = PaLife.find(p);
                PaData.Rec rr = PaData.first(p.getUUID());
                Kit.log("pai51 after: came across " + cameAt[0] + " ticks after its player; rec entity " + (rr == null ? null : rr.entity) + ", found " + b);
                if (b == null) p.teleportTo(level, x + 0.5, y, Z + 0.5, Set.of(), 0.0F, 0.0F);
                helper.assertTrue(b != null && b.level() == nether && cameAt[0] >= 0, "came across with its player at once: " + (b == null ? "none" : b.level().dimension().location()));
                helper.assertTrue(nether.getBlockStates(b.getBoundingBox()).noneMatch(st -> st.is(Blocks.NETHER_PORTAL)), "not stood in the portal");
                int id = b.getId();
                helper.runAfterDelay(60, () -> {
                    PlayerAssistantEntity c = PaLife.find(p);
                    helper.assertTrue(c != null && c.level() == nether && c.getId() == id, "still there, the same body: "
                        + (c == null ? "none" : c.level().dimension().location() + " id " + c.getId() + " was " + id));
                    helper.assertTrue(c.distanceTo(p) < 6.0F, "beside its player: " + c.distanceTo(p));
                    p.teleportTo(level, x + 0.5, y, Z + 0.5, Set.of(), 0.0F, 0.0F);
                    helper.runAfterDelay(3, () -> {
                        PlayerAssistantEntity d = PaLife.find(p);
                        helper.assertTrue(d != null && d.level() == level, "home again with its player");
                        Kit.log("pai51 portal: across and back with its player");
                        forceAround(nether, nx, nz, false);
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** pai52: elytra following (level 8): its player takes to the air and it puts on the elytra from its pack (the
     *  chestplate into the pack), takes off on a rocket, keeps up at their shoulder; when they come down it lands
     *  beside them unhurt and puts its chestplate back on. [pa33e] */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "pai52_elytra")
    public static void pai52_elytra(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_153_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 8);
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        a.insertGiven(new ItemStack(Items.ELYTRA));
        a.insertGiven(new ItemStack(Items.FIREWORK_ROCKET, 8));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        boolean[] flew = {false};
        double[] nearest = {Double.MAX_VALUE};
        long[] up = {-1};                                                              // its player in the air from this tick, for 240
        helper.onEachTick(() -> {
            if (up[0] < 0 || helper.getTick() - up[0] > 240) return;
            long tt = helper.getTick() - up[0];
            p.teleportTo(level, x + 14.5 + tt * 0.05, y + 12, Z + 0.5, Set.of(), -90.0F, 0.0F);   // its player gliding east, slowly
            if (!p.isFallFlying()) p.startFallFlying();
            if (a.isFallFlying()) flew[0] = true;
            nearest[0] = Math.min(nearest[0], a.distanceTo(p));
            if (tt % 5 == 0) Kit.log("pai52 t" + tt + " at " + String.format("%.1f %.1f %.1f", a.getX(), a.getY(), a.getZ()) + " fly " + a.isFallFlying() + " fall " + a.fallDistance
                + " hp " + a.getHealth() + " chest " + a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() + " v " + String.format("%.2f %.2f %.2f", a.getDeltaMovement().x, a.getDeltaMovement().y, a.getDeltaMovement().z)
                + " pitch " + (int) a.getXRot());
        });
        helper.runAfterDelay(20, () -> {
            a.setHealth(a.getMaxHealth());
            up[0] = helper.getTick();
            helper.runAfterDelay(242, () -> {
                helper.assertTrue(flew[0], "it took to the air: chest " + a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST) + ", at " + a.blockPosition().toShortString());
                helper.assertTrue(nearest[0] < 7.0, "kept up with its player: came within " + nearest[0]);
                helper.assertTrue(a.countMatching(st -> st.is(Items.FIREWORK_ROCKET)) < 8, "a rocket fired");
                helper.assertTrue(a.countMatching(st -> st.is(Items.IRON_CHESTPLATE)) == 1, "its chestplate in its pack meanwhile");
                // its player lands
                p.stopFallFlying();
                p.teleportTo(level, x + 30.5, y, Z + 0.5, Set.of(), -90.0F, 0.0F);
                helper.runAfterDelay(260, () -> {
                    helper.assertTrue(!a.isFallFlying() && a.onGround(), "landed: " + a.blockPosition().toShortString());
                    helper.assertTrue(a.distanceTo(p) < 8.0F, "beside its player: " + a.distanceTo(p));
                    helper.assertTrue(a.getLastDamageSource() == null && a.getHealth() >= a.getMaxHealth() - 0.01F, "unhurt: " + a.getHealth() + " of " + a.getMaxHealth()
                        + (a.getLastDamageSource() == null ? "" : ", by " + a.getLastDamageSource().getMsgId()));
                    helper.assertTrue(a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "its chestplate back on: " + a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
                    helper.assertTrue(a.countMatching(st -> st.is(Items.ELYTRA)) == 1, "the elytra back in its pack");
                    Kit.log("pai52 elytra: flew, came within " + nearest[0] + ", landed unhurt");
                    helper.succeed();
                });
            });
        });
    }

    /** pai53: parkour after its player (level 7): a trench three wide across its way and its player on the far side: it
     *  jumps it (or, short, pillars out and bridges it); and down a pit with its player above, it pillars out. [pa33e] */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "pai53_parkour")
    public static void pai53_parkour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_156_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x + 9, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 7);
        a.insertGiven(new ItemStack(Items.COBBLESTONE, 32));
        for (int dz = -24; dz <= 24; dz++)
            for (int dx = 3; dx <= 5; dx++)
                for (int dy = 1; dy <= 4; dy++) level.setBlock(new BlockPos(x + dx, y - dy, Z + dz), Blocks.AIR.defaultBlockState(), 3);
        a.teleportTo(x + 0.5, y, Z + 0.5);
        say(p, a, "follow me");
        helper.runAfterDelay(500, () -> {
            helper.assertTrue(a.getX() > x + 6 && a.getY() >= y - 0.01, "across the trench to its player: " + a.blockPosition().toShortString() + " (" + lastWords(a) + ")");
            // a pit, its player above
            BlockPos pit = new BlockPos(x + 12, y, Z + 3);
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) for (int dy = 1; dy <= 3; dy++) level.setBlock(pit.offset(dx, -dy, dz), Blocks.AIR.defaultBlockState(), 3);
            a.teleportTo(pit.getX() + 0.5, y - 3, pit.getZ() + 0.5);
            helper.runAfterDelay(400, () -> {
                helper.assertTrue(a.getY() >= y - 0.01, "pillared out of the pit: " + a.blockPosition().toShortString() + " (" + lastWords(a) + ")");
                Kit.log("pai53 parkour: across at " + a.blockPosition().toShortString() + "; " + lastWords(a));
                helper.succeed();
            });
        });
    }

    /** pai54: the End at level 10, in a real fight: on a platform in the End with its player, it shoots the crystal off
     *  its pillar first, then puts arrows into the dragon (which feels them: they count as its player's). [pa33e] */
    @GameTest(template = EMPTY, timeoutTicks = 2000, batch = "pai54_end")
    public static void pai54_end(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_159_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        Waiter w = Waiter.on(helper);
        // [pa33f] while it shoots at the dragon it faces the dragon (not its player): the most it is ever turned away, aiming
        net.minecraft.world.entity.Entity[] foe = {null};
        double[] worst = {-1};
        int[] aimed = {0}, facing = {0};                                  // ticks aiming at it, and of those turned within 30 degrees (it flies about)
        helper.onEachTick(() -> {
            PlayerAssistantEntity q = PaLife.find(p);
            if (foe[0] == null || q == null || q.mind.aimUntil <= q.tickCount || q.mind.aimEntity != foe[0]) return;
            Vec3 to = foe[0].getBoundingBox().getCenter().subtract(q.position());
            double want = Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0;
            double off = Math.abs(net.minecraft.util.Mth.wrapDegrees(want - q.getYHeadRot()));
            worst[0] = Math.max(worst[0], off);
            aimed[0]++;
            if (off < 30.0) facing[0]++;
        });
        atLevel(a, 10);
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        a.insertGiven(new ItemStack(Items.ARROW, 64));
        ServerLevel end = level.getServer().getLevel(net.minecraft.world.level.Level.END);
        int ex = 4000, ey = 80, ez = 4000;
        forceAround(end, ex, ez, true);
        for (BlockPos q : BlockPos.betweenClosed(ex - 8, ey - 1, ez - 8, ex + 16, ey + 12, ez + 8))
            end.setBlock(q, q.getY() == ey - 1 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        for (int dy = 0; dy < 5; dy++) end.setBlock(new BlockPos(ex + 12, ey + dy, ez), Blocks.OBSIDIAN.defaultBlockState(), 2);
        w.await(helper, 1000, () -> entitiesReady(end, ex, ez), () -> {
          p.teleportTo(end, ex + 0.5, ey, ez + 0.5, Set.of(), -90.0F, 0.0F);
          w.await(helper, 300, () -> { PlayerAssistantEntity q = PaLife.find(p); return q != null && q.level() == end; }, () -> {
            PlayerAssistantEntity b = PaLife.find(p);
            helper.assertTrue(b != null && b.level() == end, "it came to the End with its player");
            net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal = new net.minecraft.world.entity.boss.enderdragon.EndCrystal(end, ex + 12.5, ey + 5, ez + 0.5);
            end.addFreshEntity(crystal);
            helper.runAfterDelay(260, () -> {
                helper.assertTrue(!crystal.isAlive(), "the crystal shot off its pillar (" + lastWords(b) + ")");
                net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon = EntityType.ENDER_DRAGON.create(end);
                dragon.moveTo(ex + 14.5, ey + 3, ez + 0.5, 0, 0);
                end.addFreshEntity(dragon);
                dragon.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOVERING);   // hovering where it is
                foe[0] = dragon;
                float full = dragon.getHealth();
                helper.runAfterDelay(300, () -> {
                    helper.assertTrue(dragon.getHealth() < full, "arrows in the dragon: " + dragon.getHealth() + " of " + full + " (" + lastWords(b) + ")");
                    helper.assertTrue(aimed[0] > 0 && facing[0] * 10 >= aimed[0] * 9, "it faced the dragon while it shot: " + facing[0] + " of " + aimed[0]
                        + " aiming ticks within 30 degrees (at worst " + (int) worst[0] + ", as it wheels about)");
                    Kit.log("pai54 end: crystal down, dragon at " + dragon.getHealth() + " of " + full + "; " + lastWords(b));
                    dragon.discard();
                    forceAround(end, ex, ez, false);
                    p.teleportTo(level, x + 0.5, y, Z + 0.5, Set.of(), 0.0F, 0.0F);
                    helper.succeed();
                });
            });
          });
        });
    }

    /** pai55: a shooting contest: its player says it with a target block in sight, a countdown, it takes its ten shots
     *  (one every two seconds, whatever tick the game began on), its player's hits count, and the verdict gives the
     *  score. [pa33e] */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "pai55_contest")
    public static void pai55_contest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_162_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 6);
        a.insertGiven(new ItemStack(Items.BOW));
        a.insertGiven(new ItemStack(Items.ARROW, 20));
        BlockPos target = new BlockPos(x + 12, y + 1, Z);
        level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(target, Blocks.TARGET.defaultBlockState(), 3);
        a.teleportTo(x + 0.5, y, Z + 2.5);
        say(p, a, "stay here");
        // [pa33g] every shot visibly drawn (the bow used, a full pull) with its head on the target (not its player) as it looses
        int[] draws = {0}, fullDraws = {0}, lastUse = {0};
        double[] offAtLoose = {0};
        helper.onEachTick(() -> {
            int use = a.isUsingItem() && a.getUseItem().is(Items.BOW) ? a.getTicksUsingItem() : 0;
            if (use >= 18) offAtLoose[0] = Math.max(offAtLoose[0], offAim(a, Vec3.atCenterOf(target)));
            if (use == 0 && lastUse[0] > 0) { draws[0]++; if (lastUse[0] >= 18) fullDraws[0]++; }
            lastUse[0] = use;
        });
        helper.runAfterDelay(10, () -> {
            say(p, a, "shooting contest", target, -1);
            helper.assertTrue(fromLists(lastWords(a), "say.challenge_shoot"), "on your marks: " + lastWords(a));
            int arrows = a.countMatching(st -> st.is(Items.ARROW));
            for (int k = 0; k < 3; k++) helper.runAfterDelay(120 + k * 60, () -> com.jrpetty.mcassistant.pa.PaChallenge.ownerHit(p, target));
            helper.runAfterDelay(560, () -> {
                helper.assertTrue(arrows - a.countMatching(st -> st.is(Items.ARROW)) == 10, "its ten shots: " + (arrows - a.countMatching(st -> st.is(Items.ARROW))));
                helper.assertTrue(lastWords(a).matches(".*\\(3 to [1-9][0-9]?\\)\\s*$"), "the verdict with the score (three to its hits): " + lastWords(a));
                helper.assertTrue(fullDraws[0] >= 10, "each of its ten drawn in full, the bow used (seen): " + fullDraws[0] + " full of " + draws[0]);
                helper.assertTrue(offAtLoose[0] < 8.0, "facing the target (not you) as it loosed: at worst " + String.format("%.1f", offAtLoose[0]) + " degrees off");
                Kit.log("pai55 contest: " + lastWords(a));
                helper.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ [pa33g] archery, level scaling, chunks, come here

    /** The angle (degrees) between where it faces and a point. */
    private static double offAim(PlayerAssistantEntity a, Vec3 at) {
        Vec3 to = at.subtract(a.getEyePosition());
        double want = Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0;
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(want - a.getYHeadRot()));
    }

    /** pai56: a trick shot drawn as a player draws: the bow into its hand, the item used for a full pull (both arms up,
     *  the bow bending), its head brought round no faster than its level allows (never a snap) and on the target when it
     *  looses, and a real arrow that flies by the game's own drag and gravity (never steered). [pa33g] */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pai56_bow_draw")
    public static void pai56_bow_draw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_165_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x - 3, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 7);
        a.insertGiven(new ItemStack(Items.BOW));
        a.insertGiven(new ItemStack(Items.ARROW, 16));
        BlockPos target = new BlockPos(x + 10, y + 1, Z + 14);
        level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(target, Blocks.TARGET.defaultBlockState(), 3);
        a.teleportTo(x + 0.5, y, Z + 0.5);
        say(p, a, "stay here");
        a.setYRot(180.0F);
        a.setYHeadRot(180.0F);                                           // facing away (north): the target is south-east
        int[] pulled = {0}, maxPull = {0};
        double[] maxTurn = {0}, offAtLoose = {-1}, worstFlight = {0};
        float[] lastYaw = {Float.NaN};
        java.util.Map<Integer, Vec3> lastVel = new java.util.HashMap<>();
        int[] flightTicks = {0};
        helper.onEachTick(() -> {
            if (a.isUsingItem() && a.getUseItem().is(Items.BOW)) {
                pulled[0]++;
                maxPull[0] = Math.max(maxPull[0], a.getTicksUsingItem());
                if (a.getTicksUsingItem() >= 18) offAtLoose[0] = Math.max(offAtLoose[0], offAim(a, Vec3.atCenterOf(target)));
            }
            if (com.jrpetty.mcassistant.pa.PaBow.drawing(a) && !Float.isNaN(lastYaw[0]))
                maxTurn[0] = Math.max(maxTurn[0], Math.abs(net.minecraft.util.Mth.wrapDegrees(a.getYHeadRot() - lastYaw[0])));
            lastYaw[0] = a.getYHeadRot();
            for (net.minecraft.world.entity.projectile.Arrow ar : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Arrow.class, a.getBoundingBox().inflate(40))) {
                if (ar.getOwner() != a) continue;
                Vec3 v = ar.getDeltaMovement(), was = lastVel.put(ar.getId(), v);
                if (was == null || ar.isInWater() || v.lengthSqr() < 0.01 || was.lengthSqr() < 0.01) continue;
                if (ar.getPersistentData().getBoolean("pa_counted") || ar.position().y < y) continue;
                Vec3 want = new Vec3(was.x * 0.99, was.y * 0.99 - 0.05, was.z * 0.99);   // the game's flight, nothing more
                if (v.distanceTo(want) < 0.5) { worstFlight[0] = Math.max(worstFlight[0], v.distanceTo(want)); flightTicks[0]++; }
            }
        });
        helper.runAfterDelay(10, () -> {
            say(p, a, "shoot that target", target, -1);
            helper.runAfterDelay(160, () -> {
                double cap = com.jrpetty.mcassistant.pa.PaLevels.turnDegrees(7);
                helper.assertTrue(a.getMainHandItem().is(Items.BOW), "the bow in its hand: " + a.getMainHandItem());
                helper.assertTrue(maxPull[0] >= 18 && pulled[0] >= 18, "a full pull, the bow used as an item (seen by all): " + maxPull[0] + " ticks drawn");
                helper.assertTrue(maxTurn[0] <= cap + 0.5, "its head turned at its level's pace, never a snap: " + String.format("%.1f", maxTurn[0]) + " degrees in a tick (cap " + cap + ")");
                helper.assertTrue(offAtLoose[0] >= 0 && offAtLoose[0] < 8, "on the target as it loosed: " + String.format("%.1f", offAtLoose[0]) + " degrees off");
                helper.assertTrue(flightTicks[0] >= 3 && worstFlight[0] < 0.02, "the arrow flew by the game's own drag and gravity: " + flightTicks[0] + " ticks watched, off by " + worstFlight[0]);
                Kit.log("pai56 bow: pulled " + maxPull[0] + " ticks, head at most " + String.format("%.1f", maxTurn[0]) + " deg/tick, " + String.format("%.1f", offAtLoose[0])
                    + " deg off at the loose; " + lastWords(a));
                helper.succeed();
            });
        });
    }

    /** pai57: archery by level, in the game: level 1 and level 10 each loose sixteen arrows at a target block thirty
     *  blocks off. Level 10 lands far more; neither is shown where the arrow will go. [pa33g] */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "pai57_archery_levels")
    public static void pai57_archery_levels(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_168_000, y = ground(level, x);
        PlayerAssistantEntity[] who = new PlayerAssistantEntity[2];
        BlockPos[] marks = new BlockPos[2];
        int[] shots = new int[2], hits = new int[2];
        for (int k = 0; k < 2; k++) {
            int zz = Z - 14 + k * 28;
            ServerPlayer p = player(helper, level, new BlockPos(x - 4, y, zz));
            PlayerAssistantEntity a = assistant(helper, p);
            atLevel(a, k == 0 ? 1 : 10);
            a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            a.insertGiven(new ItemStack(Items.ARROW, 32));
            a.teleportTo(x + 0.5, y, zz + 0.5);
            say(p, a, "stay here");
            marks[k] = new BlockPos(x + 30, y + 1, zz);
            level.setBlock(marks[k].below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(marks[k], Blocks.TARGET.defaultBlockState(), 3);
            who[k] = a;
        }
        helper.onEachTick(() -> {
            for (int k = 0; k < 2; k++) {
                final int kk = k;
                PlayerAssistantEntity a = who[k];
                if (shots[k] >= 16 || com.jrpetty.mcassistant.pa.PaBow.drawing(a)) continue;
                if (com.jrpetty.mcassistant.pa.PaShots.shootAt(a, marks[k], shot -> { if (shot.willHit()) hits[kk]++; })) shots[k]++;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(shots[0] >= 16 && shots[1] >= 16 && !com.jrpetty.mcassistant.pa.PaBow.drawing(who[0]) && !com.jrpetty.mcassistant.pa.PaBow.drawing(who[1]),
                "both loosed sixteen: " + shots[0] + ", " + shots[1]);
            helper.assertTrue(hits[1] >= hits[0] + 5, "level 10 lands far more at 30 blocks: " + hits[1] + " of 16 against level 1's " + hits[0]);
            helper.assertTrue(hits[0] < 12, "level 1 is no marksman at 30: " + hits[0] + " of 16");
            Kit.log("pai57 archery at 30 blocks: level 1 " + hits[0] + "/16, level 10 " + hits[1] + "/16");
        });
    }

    /** pai58: work by level, timed: two assistants at the same rock face (level 1 and level 10, the same stone pick) each
     *  mine until they have three iron. Level 10 is done well before level 1, its clock quicker by its level. [pa33g] */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "pai58_work_by_level")
    public static void pai58_work_by_level(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_171_000, y = ground(level, x);
        PlayerAssistantEntity[] who = new PlayerAssistantEntity[2];
        long[] doneAt = {-1, -1};
        for (int k = 0; k < 2; k++) {
            int zz = Z - 14 + k * 28;
            for (int dx = 4; dx < 14; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = 0; dy < 6; dy++) {
                boolean ore = dx == 4 && (dz + dy) % 3 == 0;
                level.setBlock(new BlockPos(x + dx, y + dy, zz + dz), (ore ? Blocks.IRON_ORE : Blocks.STONE).defaultBlockState(), 2);
            }
            ServerPlayer p = player(helper, level, new BlockPos(x, y, zz));
            PlayerAssistantEntity a = assistant(helper, p);
            atLevel(a, k == 0 ? 1 : 10);
            a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
            a.teleportTo(x + 0.5, y, zz + 0.5);
            who[k] = a;
        }
        int t1 = who[0].workTicksFor(Blocks.IRON_ORE.defaultBlockState()), t10 = who[1].workTicksFor(Blocks.IRON_ORE.defaultBlockState());
        long start = helper.getTick();
        for (int k = 0; k < 2; k++) say(who[k].getOwnerPlayer() instanceof ServerPlayer sp ? sp : null, who[k], "mine iron until you have 3");
        helper.onEachTick(() -> {
            for (int k = 0; k < 2; k++) if (doneAt[k] < 0 && who[k].countMatching(s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE)) >= 3) doneAt[k] = helper.getTick() - start;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(doneAt[1] >= 0, "level 10 has its three iron");
            helper.assertTrue(t10 * 100 <= t1 * 70, "level 10's clock at the rock is a good deal quicker: " + t10 + " ticks a block against " + t1);
            helper.assertTrue(who[1].reach() > who[0].reach(), "and it reaches further: " + who[1].reach() + " against " + who[0].reach());
            helper.assertTrue(who[1].pacedTicks(20, 100) < who[0].pacedTicks(20, 100), "and crafts quicker: " + who[1].pacedTicks(20, 100) + " against " + who[0].pacedTicks(20, 100));
            helper.assertTrue(doneAt[0] < 0 || doneAt[1] * 100 <= doneAt[0] * 90, "level 10 done well before level 1: " + doneAt[1] + " ticks against " + doneAt[0]);
            if (doneAt[0] < 0) helper.assertTrue(helper.getTick() - start >= doneAt[1] * 100 / 90, "level 1 still at it well after level 10 finished");
            Kit.log("pai58 work by level: three iron at level 10 in " + doneAt[1] + " ticks, level 1 " + (doneAt[0] < 0 ? "still at it" : doneAt[0] + " ticks")
                + "; a block of ore " + t10 + " against " + t1 + " ticks");
        });
    }

    /** pai59: "come here" from 160 blocks: it walks the whole way (running while far, walking at the end), keeping its own
     *  chunks awake as it goes, with a word on the distance; every tick's step is a step (no jump), and no shortcut. [pa33g] */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "pai59_come_far")
    public static void pai59_come_far(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_174_000, y = ground(level, x);
        Kit.hold(level, x + 160, Z, 16);
        Kit.prepare(level, x + 160, Z, 16);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.teleportTo(x + 0.5, y, Z + 0.5);
        say(p, a, "stay here");                                          // left here while its player walks off
        int py = Kit.surface(level, x + 160, Z).getY();
        p.teleportTo(level, x + 160.5, py, Z + 0.5, Set.of(), 90.0F, 0.0F);
        double[] maxStep = {0};
        boolean[] ran = {false}, walkedEnd = {false}, held = {false};
        Vec3[] last = {null};
        com.jrpetty.mcassistant.pa.PaCome.Trip[] trip = {null};
        helper.onEachTick(() -> {
            if (trip[0] == null) return;
            Vec3 now = a.position();
            if (last[0] != null) maxStep[0] = Math.max(maxStep[0], now.distanceTo(last[0]));
            last[0] = now;
            double d = a.distanceTo(p);
            if (d > 30 && a.isSprinting()) ran[0] = true;
            if (d < 8 && !a.isSprinting() && a.getDeltaMovement().horizontalDistance() > 0.02) walkedEnd[0] = true;
            if (com.jrpetty.mcassistant.pa.PaChunks.heldForTests(a) != null) held[0] = true;
        });
        helper.runAfterDelay(5, () -> {
            say(p, a, "come here");
            String said = lastWords(a);
            helper.assertTrue(fromLists(said, "say.come_far") && said.contains("16"), "on its way, with the distance: " + said);
            trip[0] = a.mind.come;
            helper.assertTrue(trip[0] != null, "a trip on foot under way");
            last[0] = a.position();
            helper.succeedWhen(() -> {
                helper.assertTrue(a.distanceTo(p) < 4.0F && a.mind.come == null, "it got to its player: " + String.format("%.1f", a.distanceTo(p)) + " at " + a.blockPosition().toShortString());
                helper.assertTrue(trip[0].shortcuts == 0, "no shortcut taken");
                helper.assertTrue(maxStep[0] < 1.0, "every tick a step, never a jump: the longest " + String.format("%.2f", maxStep[0]));
                helper.assertTrue(ran[0], "it ran while it was far");
                helper.assertTrue(held[0], "it kept its own chunks awake on the way");
                Kit.log("pai59 come far: walked 160 blocks, longest step " + String.format("%.2f", maxStep[0]) + ", ran " + ran[0] + ", walked the end " + walkedEnd[0]
                    + "; " + said + " | " + lastWords(a));
            });
        });
    }

    /** pai60: told to stay with its player 200 blocks off, it keeps a 3x3 of chunks loaded round it (a forced, ticking
     *  ticket) and goes on working there; dismissed, the chunks are let go. [pa33g] */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pai60_stay_far")
    public static void pai60_stay_far(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_177_000, y = ground(level, x);
        PaConfig.chunksForTests();
        for (int dx = 4; dx < 14; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = 0; dy < 6; dy++) {
            boolean ore = dx == 4 && (dz + dy) % 3 == 0;
            level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), (ore ? Blocks.IRON_ORE : Blocks.STONE).defaultBlockState(), 2);
        }
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        atLevel(a, 5);
        a.insertGiven(new ItemStack(Items.STONE_PICKAXE));
        a.teleportTo(x + 0.5, y, Z + 0.5);
        say(p, a, "stay here");
        p.teleportTo(level, x + 200.5, y, Z + 0.5, Set.of(), 0.0F, 0.0F);
        java.util.UUID ticket = com.jrpetty.mcassistant.pa.PaChunks.ticketOwner(a.getUUID());
        int cx = a.getBlockX() >> 4, cz = a.getBlockZ() >> 4;
        Waiter w = Waiter.on(helper);
        boolean[] heldAll = {true}, working = {false};
        helper.onEachTick(() -> {
            if (working[0] && a.isAlive() && !a.plan.idle() && com.jrpetty.mcassistant.pa.PaChunks.heldForTests(a) == null) heldAll[0] = false;
            if (working[0] && helper.getTick() % 200 == 0) Kit.log("pai60 " + a.getMode() + " " + a.state() + " at " + a.blockPosition().toShortString() + " alive " + a.isAlive()
                + " plan idle " + a.plan.idle() + " held " + (com.jrpetty.mcassistant.pa.PaChunks.heldForTests(a) != null));
        });
        helper.runAfterDelay(80, () -> {
            com.jrpetty.mcassistant.pa.PaChunks.Held h = com.jrpetty.mcassistant.pa.PaChunks.heldForTests(a);
            helper.assertTrue(h != null && h.r() == 1, "staying put, its player 200 off: a 3x3 of chunks held: " + h);
            int forced = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (com.jrpetty.mcassistant.ChunkLoad.ownersForTests(level, net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz)).contains(ticket + "+")) forced++;
            helper.assertTrue(forced == 9, "all nine forced (ticking tickets): " + forced);
            helper.assertTrue(a.distanceToSqr(x + 0.5, y, Z + 0.5) < 4, "still where it was told to stay: " + a.blockPosition().toShortString());
            p.teleportTo(level, x + 0.5, y, Z - 2.5, Set.of(), 0.0F, 0.0F);   // back to give it a job, then off again
            say(p, a, "mine iron until you have 2");
            p.teleportTo(level, x + 200.5, y, Z + 0.5, Set.of(), 0.0F, 0.0F);
            working[0] = true;
            w.await(helper, 2600, () -> a.countMatching(s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE)) >= 2, () -> {
                helper.assertTrue(a.countMatching(s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE)) >= 2, "it worked on, its player 200 blocks away ("
                    + a.state() + "; " + lastWords(a) + ")");
                helper.assertTrue(heldAll[0], "its chunks held all the while it worked");
                working[0] = false;
                PaServer.act(p, "dismiss", new CompoundTag());
                long t0 = helper.getTick();
                w.await(helper, 10, () -> helper.getTick() > t0 + 4, () -> {
                    int still = 0;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                        if (!com.jrpetty.mcassistant.ChunkLoad.ownersForTests(level, net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz)).isEmpty()
                            && com.jrpetty.mcassistant.ChunkLoad.ownersForTests(level, net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz)).stream().anyMatch(o -> o.startsWith(ticket.toString()))) still++;
                    helper.assertTrue(still == 0, "dismissed: its chunks let go (" + still + " still held)");
                    Kit.log("pai60 stay far: 9 chunks held, mined 2 iron with its player 200 off; dismissed and let go");
                    helper.succeed();
                });
            });
        });
    }
}
