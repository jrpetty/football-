package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.pa.PaBlueprint;
import com.jrpetty.mcassistant.pa.PaBlueprints;
import com.jrpetty.mcassistant.pa.PaBuild;
import com.jrpetty.mcassistant.pa.PaConfig;
import com.jrpetty.mcassistant.pa.PaData;
import com.jrpetty.mcassistant.pa.PaErrand;
import com.jrpetty.mcassistant.pa.PaLevels;
import com.jrpetty.mcassistant.pa.PaLife;
import com.jrpetty.mcassistant.pa.PaMemoryAct;
import com.jrpetty.mcassistant.pa.PaMineLock;
import com.jrpetty.mcassistant.pa.PaServer;
import com.jrpetty.mcassistant.pa.PaSlips;
import com.jrpetty.mcassistant.pa.PaStance;
import com.jrpetty.mcassistant.pa.PaStore;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [featpa] The companion's three new trades, as a player meets them: a building shown to it and built again elsewhere,
 * turned and mirrored, by hand, block states and all (stairs, a door, a bed, slabs, a double chest come out empty), never
 * a teleport and never through a block of yours; an errand that walks to a town's market stall, sells, buys and walks
 * back with the goods and a word on what it got; each combat stance in a fight (the shield calls a zombie off a
 * villager and blocks, the archer shoots from range, the medic pulls a badly hurt villager clear and heals them); and the
 * companion joining a town's defence when its alarm goes up.
 *
 * <p>Each on its own ground, x 11_400_000 up (3000 apart), z 70000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PaKitGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    private static int ground(ServerLevel level, int x) {
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        PaConfig.perPlayerForTests(null);
        PaConfig.respawnSecondsForTests(null);
        PaConfig.ladderForTests(false, null);
        PaMemoryAct.resetForTests();
        PaMineLock.resetForTests();
        PaSlips.testing = false;
        PaSlips.testDice = null;
        PaBuild.paceForTests = 0;
        PaErrand.hoursOffForTests = false;
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        Kit.live(level, x, Z, 44);
        int y = Kit.surface(level, x, Z).getY();
        for (int dx = -40; dx <= 40; dx++) for (int dz = -40; dz <= 40; dz++) {
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
        r.stance = 0;
        r.guardWho = null;
        r.helpTown = true;
        a.setPaXpForTests(PaLevels.xpFor(level));
        return a;
    }

    private static void kit(ServerPlayer p, String op, CompoundTag t) {
        t.putString("op", op);
        PaServer.act(p, "kit", t);
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

    /** The longest single-tick step it takes, watched every tick (a teleport would be many blocks in one tick). */
    private static final class Steps {
        Vec3 last;
        double most;

        void watch(PlayerAssistantEntity a) {
            if (last != null) most = Math.max(most, a.position().distanceTo(last));
            last = a.position();
        }
    }

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // ================================================================== a blueprint shown, and built again

    /** kit01: a little house (stairs, a door, a bed, slabs, a double chest with a diamond in, a torch, a window) copied from
     *  its two pinged corners, then built again turned a quarter and mirrored: every block's state as the game turns it,
     *  the chest empty, a block of the player's own left standing, its pack topped up from a chest of theirs, and never a
     *  step longer than walking. */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "kit01_blueprint")
    public static void kit01_blueprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 11_400_000, y = ground(level, x);
        PaBlueprints.resetForTests();
        PaBuild.paceForTests = 2;
        int x0 = x + 4, z0 = Z + 4;
        // the house: a plank floor, cobblestone walls three high, a slab roof (one double), and its fittings
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) {
            level.setBlock(new BlockPos(x0 + dx, y, z0 + dz), Blocks.OAK_PLANKS.defaultBlockState(), FLAGS);
            boolean edge = dx == 0 || dz == 0 || dx == 4 || dz == 4;
            for (int dy = 1; dy <= 3; dy++) if (edge) level.setBlock(new BlockPos(x0 + dx, y + dy, z0 + dz), Blocks.COBBLESTONE.defaultBlockState(), FLAGS);
            level.setBlock(new BlockPos(x0 + dx, y + 4, z0 + dz), Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, dx == 2 && dz == 2 ? SlabType.DOUBLE : SlabType.TOP), FLAGS);
        }
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        level.setBlock(new BlockPos(x0 + 2, y + 1, z0 + 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), FLAGS);
        level.setBlock(new BlockPos(x0 + 2, y + 2, z0 + 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), FLAGS);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(new BlockPos(x0 + 1, y + 1, z0 + 2), bed.setValue(BedBlock.PART, BedPart.FOOT), FLAGS);
        level.setBlock(new BlockPos(x0 + 1, y + 1, z0 + 1), bed.setValue(BedBlock.PART, BedPart.HEAD), FLAGS);
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        level.setBlock(new BlockPos(x0 + 2, y + 1, z0 + 1), chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), FLAGS);
        level.setBlock(new BlockPos(x0 + 3, y + 1, z0 + 1), chest.setValue(ChestBlock.TYPE, ChestType.LEFT), FLAGS);
        if (level.getBlockEntity(new BlockPos(x0 + 2, y + 1, z0 + 1)) instanceof Container c) c.setItem(0, new ItemStack(Items.DIAMOND, 3));
        level.setBlock(new BlockPos(x0 + 3, y + 1, z0 + 3), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), FLAGS);
        level.setBlock(new BlockPos(x0 + 1, y + 2, z0 + 3), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST), FLAGS);
        level.setBlock(new BlockPos(x0, y + 2, z0 + 2), Blocks.GLASS_PANE.defaultBlockState(), FLAGS);
        // the whole building is found from one of its walls
        var box = PaBlueprint.detect(level, new BlockPos(x0, y + 1, z0 + 1));
        helper.assertTrue(box != null && box.minX() == x0 && box.maxX() == x0 + 4 && box.minZ() == z0 && box.maxZ() == z0 + 4 && box.maxY() == y + 4,
            "the building found from one wall: " + box);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p, 3);
        // shown it: two corners pinged on the ground, "Copy this building" on the wheel
        CompoundTag ping = new CompoundTag();
        ping.putString("target", "area");
        ping.putString("opt", "copy");
        ping.putInt("x", x0); ping.putInt("y", y - 1); ping.putInt("z", z0);
        ping.putInt("x2", x0 + 4); ping.putInt("y2", y - 1); ping.putInt("z2", z0 + 4);
        PaServer.pingRateForTests(p.getUUID());
        PaServer.act(p, "ping", ping);
        List<String> names = PaBlueprints.names(p.getUUID());
        helper.assertTrue(names.size() == 1, "one blueprint kept: " + names + " (" + allWords(a) + ")");
        PaBlueprint bp = PaBlueprints.find(p.getUUID(), names.get(0));
        helper.assertTrue(bp.sx == 5 && bp.sz == 5 && bp.sy == 5, "the ground left out, the air above trimmed: " + bp.sx + "x" + bp.sz + "x" + bp.sy);
        Map<Item, Integer> mat = bp.materials();
        helper.assertTrue(mat.get(Items.OAK_DOOR) == 1 && mat.get(Items.RED_BED) == 1 && mat.get(Items.CHEST) == 2 && mat.get(Items.OAK_SLAB) == 26,
            "one door, one bed, two chests, 26 slabs (one double): " + mat);
        // the copy's spot: on clear ground west of the player; a block of the player's own and a clod of earth in it
        BlockPos anchor = new BlockPos(x - 14, y, Z - 6);
        BlockPos mine = anchor.offset(2, 3, 2), clod = anchor.offset(2, 1, 1);
        level.setBlock(mine, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(clod, Blocks.DIRT.defaultBlockState(), 3);
        // its materials: a few in its pack, the rest in a chest of the player's by the site
        BlockPos store = new BlockPos(x - 6, y, Z - 2);
        level.setBlock(store, Blocks.CHEST.defaultBlockState(), 3);
        Container sc = (Container) level.getBlockEntity(store);
        int slot = 0;
        for (Map.Entry<Item, Integer> e : mat.entrySet()) {
            int n = e.getValue();
            if (e.getKey() == Items.COBBLESTONE) { a.insertItem(new ItemStack(Items.COBBLESTONE, 10)); n -= 10; }
            while (n > 0 && slot < 27) { int k = Math.min(n, e.getKey().getDefaultMaxStackSize()); sc.setItem(slot++, new ItemStack(e.getKey(), k)); n -= k; }
        }
        PaStore.learn(p.getUUID(), level.dimension().location().toString(), store, "minecraft:chest", level.getGameTime());
        // the ghost: turned a quarter, mirrored, and its clash with the player's block seen
        PaBuild.Preview pv = new PaBuild.Preview();
        pv.name = bp.name;
        pv.anchor = anchor;
        pv.rot = 1;
        pv.mirror = true;
        pv.clearMine = false;
        helper.assertTrue(PaBuild.clashes(level, bp, pv).contains(mine) && !PaBuild.clashes(level, bp, pv).contains(clod),
            "its own block is a clash; the earth isn't: " + PaBuild.clashes(level, bp, pv));
        PaBuild.rememberPreviewForTests(p.getUUID(), pv);
        kit(p, "bp_confirm", new CompoundTag());
        PaBuild.Run run = PaBuild.run(a);
        helper.assertTrue(run != null && run.total > 60, "the build begun: " + (run == null ? "none" : run.total + " to do") + " (" + allWords(a) + ")");
        Steps steps = new Steps();
        List<PaBlueprint.Spot> spots = bp.spots(anchor, true, 1);
        int[] t = {0};
        helper.onEachTick(() -> steps.watch(a));
        helper.succeedWhen(() -> {
            t[0]++;
            if (t[0] % 200 == 0) Kit.log("[VT] kit01 t" + t[0] + " placed " + run.placed + " dug " + run.dug + " of " + run.total + ", layer " + run.layer
                + ", missing " + run.missing + ", step " + a.plan.current + ", at " + a.blockPosition());
            helper.assertTrue(run.done, "built (" + run.placed + "+" + run.dug + "/" + run.total + ", " + a.state() + ")");
            int wrong = 0;
            StringBuilder why = new StringBuilder();
            for (PaBlueprint.Spot s : spots) {
                if (s.pos().equals(mine)) continue;
                BlockState now = level.getBlockState(s.pos());
                boolean ok = s.state().isAir() ? now.isAir() : PaBuild.same(now, s.state());
                if (!ok && wrong++ < 4) why.append(s.pos()).append(" wants ").append(s.state()).append(" has ").append(now).append("; ");
            }
            helper.assertTrue(wrong == 0, wrong + " blocks not as drawn: " + why);
            helper.assertTrue(level.getBlockState(mine).is(Blocks.STONE_BRICKS) && run.aroundYours >= 1, "the player's own block left standing");
            // the turned states, by the game's rules: the door faced south, now (mirrored, a quarter turned) west
            int doors = 0, chestsEmpty = 0, chests = 0;
            for (PaBlueprint.Spot s : spots) {
                BlockState now = level.getBlockState(s.pos());
                if (now.getBlock() instanceof DoorBlock && now.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    doors++;
                    helper.assertTrue(now.getValue(DoorBlock.FACING) == PaBlueprint.turn(door, true, 1).getValue(DoorBlock.FACING), "the door turned: " + now);
                    helper.assertTrue(level.getBlockState(s.pos().above()).getBlock() instanceof DoorBlock, "and its top half");
                }
                if (now.getBlock() instanceof ChestBlock) {
                    chests++;
                    if (level.getBlockEntity(s.pos()) instanceof Container c && c.isEmpty()) chestsEmpty++;
                }
            }
            helper.assertTrue(doors == 1 && chests == 2 && chestsEmpty == 2, "one door; two chest halves, both empty: " + doors + ", " + chests + ", " + chestsEmpty);
            helper.assertTrue(steps.most < 1.5, "never a teleport: longest step " + steps.most);
            helper.assertTrue(saidAny(a, bp.name), "it said so in its own words: " + allWords(a));
            Kit.log("[VT] kit01 built " + bp.name + " in " + t[0] + " ticks: placed " + run.placed + ", dug " + run.dug + ", around yours " + run.aroundYours
                + ", longest step " + String.format("%.2f", steps.most) + " | " + allWords(a));
        });
    }

    // ================================================================== an errand to town

    /** kit02: handed cobblestone to sell and coins, with bread on its list, it walks to the town's market stall, sells and
     *  buys at the town's prices, walks back, hands over the bread and the coins, and says what it got and for how much. */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "kit02_errand")
    public static void kit02_errand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 11_403_000, y = ground(level, x);
        level.setDayTime(24000L * 3 + 2500);
        PaErrand.hoursOffForTests = true;
        VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a town");
        java.util.UUID id = f.ownerId();
        helper.runAfterDelay(10, () -> {
            Villages.Village v = Villages.get(id);
            BlockPos heart = v.centre();
            f.setJob(AssistantEntity.StationTask.FARM);
            for (int k = 0; k < 4; k++) {                                // stores: bread to sell, room for cobblestone
                BlockPos at = Kit.surface(level, heart.getX() - 6 + 2 * k, heart.getZ() - 5);
                level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
                com.jrpetty.mcassistant.entity.ZoneChests.mark(level, at);
                if (k == 0 && level.getBlockEntity(at) instanceof Container c) for (int i = 0; i < 27; i++) c.setItem(i, new ItemStack(Items.BREAD, 64));
            }
            Villages.forgetStores(id);
            com.jrpetty.mcassistant.entity.Budget.forget(id);
            com.jrpetty.mcassistant.village.Ledger.addCoins(id, 500);
            // a market stall: a barrel with its frame of goods
            BlockPos counter = Kit.surface(level, heart.getX() + 4, heart.getZ() + 4);
            level.setBlock(counter, Blocks.BARREL.defaultBlockState(), 3);
            ItemFrame frame = new ItemFrame(level, counter.above(), Direction.UP);
            frame.setItem(new ItemStack(Items.BREAD));
            frame.addTag("mca_stall");
            level.addFreshEntity(frame);
            ServerPlayer p = player(helper, level, heart.offset(30, 0, 2));
            p.getInventory().setItem(3, new ItemStack(Items.COBBLESTONE, 64));
            p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 40));
            PlayerAssistantEntity a = assistant(helper, p, 3);
            a.rec().spend = 64;
            // the screen: cobblestone handed over to sell, bread on the list, 32 coins handed over, sent
            CompoundTag give = new CompoundTag();
            give.putInt("slot", 3);
            kit(p, "er_give", give);
            helper.assertTrue(a.countMatching(s -> s.is(Items.COBBLESTONE)) == 64 && p.getInventory().countItem(Items.COBBLESTONE) == 0, "the cobblestone is in its pack");
            CompoundTag buy = new CompoundTag();
            buy.putString("item", "minecraft:bread");
            buy.putInt("by", 8);
            kit(p, "er_buy", buy);
            CompoundTag coins = new CompoundTag();
            coins.putInt("by", 32);
            kit(p, "er_coins", coins);
            int coinsBefore = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
            kit(p, "er_go", new CompoundTag());
            PaErrand.Run run = PaErrand.run(a);
            helper.assertTrue(run != null, "sent (" + allWords(a) + ")");
            Kit.log("[VT] kit02 sent: market " + run.market + ", heart " + heart + ", player coins " + coinsBefore + " | " + allWords(a));
            Steps steps = new Steps();
            int[] t = {0};
            helper.onEachTick(() -> steps.watch(a));
            helper.succeedWhen(() -> {
                t[0]++;
                if (t[0] % 200 == 0) Kit.log("[VT] kit02 t" + t[0] + " stage " + run.stage + " at " + a.blockPosition() + " nearest " + run.nearestToMarket
                    + " sold " + run.sold + " bought " + run.bought + " notes " + run.notes);
                helper.assertTrue(run.stage == PaErrand.Stage.DONE && run.reported, "the errand done (" + run.stage + ")");
                helper.assertTrue(run.nearestToMarket <= 5, "it walked to the stall: nearest " + run.nearestToMarket);
                helper.assertTrue(run.sold.getOrDefault("minecraft:cobblestone", 0) > 0 && run.earned > 0, "it sold cobblestone: " + run.sold + " for " + run.earned + " (" + run.notes + ")");
                helper.assertTrue(run.bought.getOrDefault("minecraft:bread", 0) > 0 && run.spent > 0, "it bought bread: " + run.bought + " for " + run.spent + " (" + run.notes + ")");
                helper.assertTrue(p.getInventory().countItem(Items.BREAD) > 0, "the bread is in its player's hands");
                helper.assertTrue(a.distanceTo(p) < 5, "and it's back by its player: " + a.distanceTo(p));
                helper.assertTrue(com.jrpetty.mcassistant.entity.Market.coinsHeld(p) > coinsBefore, "the change and the takings handed over");
                helper.assertTrue(steps.most < 1.5, "on foot the whole way: longest step " + steps.most);
                helper.assertTrue(saidAny(a, "bread") && saidAny(a, "cobblestone"), "it said what it got: " + allWords(a));
                Kit.log("[VT] kit02 done in " + t[0] + " ticks: sold " + run.sold + " for " + run.earned + ", bought " + run.bought + " for " + run.spent
                    + ", longest step " + String.format("%.2f", steps.most) + " | " + allWords(a));
            });
        });
    }

    // ================================================================== the stances in a fight

    private static Zombie zombie(ServerLevel level, BlockPos at) {
        Zombie z = EntityType.ZOMBIE.create(level);
        z.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));   // no burning in the sun
        z.setPersistenceRequired();
        level.addFreshEntity(z);
        return z;
    }

    private static Villager villager(ServerLevel level, BlockPos at) {
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        v.setPersistenceRequired();
        level.addFreshEntity(v);
        return v;
    }

    private static void stance(ServerPlayer p, PaStance.Kind k) {
        CompoundTag t = new CompoundTag();
        t.putInt("k", k.ordinal());
        kit(p, "st_set", t);
    }

    /** kit03: Shield: guarding a villager, it calls the zombie going for them onto itself, takes its blows on a raised
     *  shield, and puts it down. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "kit03_shield")
    public static void kit03_shield(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 11_406_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p, 5);
        a.insertItem(new ItemStack(Items.SHIELD));
        a.insertItem(new ItemStack(Items.IRON_SWORD));
        Villager vil = villager(level, new BlockPos(x + 3, y, Z));
        stance(p, PaStance.Kind.SHIELD);
        CompoundTag w = new CompoundTag();
        w.putInt("id", vil.getId());
        kit(p, "st_ward", w);
        helper.assertTrue(vil.getUUID().equals(a.rec().guardWho), "it guards the villager");
        Zombie z = zombie(level, new BlockPos(x + 12, y, Z + 2));
        z.setTarget(vil);
        boolean[] onMe = {false}, blocked = {false};
        helper.onEachTick(() -> {
            if (z.isAlive() && z.getTarget() == a) onMe[0] = true;
            if (a.isUsingItem() && a.getUseItem().is(Items.SHIELD)) blocked[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(onMe[0], "the zombie turned on it, off the villager");
            helper.assertTrue(blocked[0], "its shield went up");
            helper.assertTrue(!z.isAlive(), "the zombie is down (" + z.getHealth() + ")");
            helper.assertTrue(vil.isAlive(), "the villager is safe");
            helper.assertTrue(a.getOffhandItem().is(Items.SHIELD), "a shield in its off hand");
            int[] tl = PaStance.talliesForTests(a);
            Kit.log("[VT] kit03 shield: taunts " + tl[0] + ", villager " + vil.getHealth() + "/" + vil.getMaxHealth() + " | " + allWords(a));
        });
    }

    /** kit04: Archer: a zombie going for a villager, shot from range (it never lets it close), till it's down. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "kit04_archer")
    public static void kit04_archer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 11_409_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p, 5);
        a.insertItem(new ItemStack(Items.BOW));
        a.insertItem(new ItemStack(Items.ARROW, 64));
        Villager vil = villager(level, new BlockPos(x + 2, y, Z + 2));
        stance(p, PaStance.Kind.ARCHER);
        int shots0 = a.rec().shots;
        Zombie z = zombie(level, new BlockPos(x + 16, y, Z + 1));
        z.setTarget(vil);
        z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 60, 1));
        double[] closest = {Double.MAX_VALUE};
        helper.onEachTick(() -> { if (z.isAlive()) closest[0] = Math.min(closest[0], a.distanceTo(z)); });
        helper.succeedWhen(() -> {
            int shots = a.rec().shots - shots0;
            helper.assertTrue(shots >= 2, "arrows loosed: " + shots);
            helper.assertTrue(!z.isAlive(), "the zombie is down (" + z.getHealth() + ", shots " + shots + ")");
            helper.assertTrue(a.getMainHandItem().is(Items.BOW), "a bow in its hand");
            helper.assertTrue(closest[0] > 2.5, "it kept its distance: closest " + closest[0]);
            Kit.log("[VT] kit04 archer: shots " + shots + ", closest " + String.format("%.1f", closest[0]) + " | " + allWords(a));
        });
    }

    /** kit05: Medic: a villager badly hurt with a (slowed) zombie on them is pulled clear and healed. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "kit05_medic")
    public static void kit05_medic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 11_412_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p, 5);
        a.insertItem(new ItemStack(Items.GOLDEN_APPLE, 2));
        a.insertItem(new ItemStack(Items.BREAD, 8));
        stance(p, PaStance.Kind.MEDIC);
        Villager vil = villager(level, new BlockPos(x + 8, y, Z + 3));
        vil.setHealth(6.0F);
        Zombie z = zombie(level, new BlockPos(x + 12, y, Z + 3));
        z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 60, 6));
        z.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 60, 4));
        z.setTarget(vil);
        float[] most = {0};
        helper.onEachTick(() -> most[0] = Math.max(most[0], vil.getHealth()));
        helper.succeedWhen(() -> {
            int[] tl = PaStance.talliesForTests(a);
            helper.assertTrue(vil.isAlive(), "the villager lives");
            helper.assertTrue(tl[1] >= 1, "it treated someone: " + tl[1]);
            helper.assertTrue(most[0] >= 12.0F, "the villager healed: " + vil.getHealth() + " (best " + most[0] + ")");
            helper.assertTrue(a.countMatching(s -> s.is(Items.GOLDEN_APPLE)) < 2, "a golden apple used on the badly hurt");
            helper.assertTrue(tl[2] >= 1, "it pulled them clear first: " + tl[2]);
            Kit.log("[VT] kit05 medic: treated " + tl[1] + ", pulled " + tl[2] + ", villager " + vil.getHealth() + " | " + allWords(a));
        });
    }

    // ================================================================== a town's defence

    /** kit06: a town's alarm goes up with raiders at its square: it joins the defence (says so), goes to the square, and
     *  fights the raiders in its stance; at the all-clear it leaves. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "kit06_raid")
    public static void kit06_raid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 11_415_000, y = ground(level, x);
        level.setDayTime(24000L * 3 + 2500);
        VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a town");
        java.util.UUID id = f.ownerId();
        helper.runAfterDelay(10, () -> {
            Villages.Village v = Villages.get(id);
            BlockPos heart = v.centre();
            ServerPlayer p = player(helper, level, heart.offset(28, 0, 0));
            PlayerAssistantEntity a = assistant(helper, p, 5);
            a.insertItem(new ItemStack(Items.IRON_SWORD));
            a.insertItem(new ItemStack(Items.BOW));
            a.insertItem(new ItemStack(Items.ARROW, 64));
            stance(p, PaStance.Kind.ARCHER);
            com.jrpetty.mcassistant.entity.Raids.alarm(level, v, "raiders at the gate", null);
            Pillager[] band = new Pillager[2];
            for (int i = 0; i < band.length; i++) {
                Pillager pl = EntityType.PILLAGER.create(level);
                BlockPos at = Kit.surface(level, heart.getX() - 8 + 3 * i, heart.getZ() - 10);
                pl.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                pl.setPersistenceRequired();
                pl.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);          // no crossbow: the test is the joining, not a firefight
                level.addFreshEntity(pl);
                band[i] = pl;
            }
            boolean[] joined = {false}, fought = {false};
            double[] nearest = {Double.MAX_VALUE};
            helper.onEachTick(() -> {
                if (PaStance.defending(a) != null) joined[0] = true;
                if (a.getTarget() instanceof Raider) fought[0] = true;
                nearest[0] = Math.min(nearest[0], Math.sqrt(a.distanceToSqr(Vec3.atCenterOf(heart))));
            });
            int[] t = {0};
            helper.succeedWhen(() -> {
                t[0]++;
                helper.assertTrue(joined[0], "it joined the town's defence");
                helper.assertTrue(saidAny(a, Villages.name(id)), "and said so: " + allWords(a));
                helper.assertTrue(fought[0], "it went for the raiders");
                helper.assertTrue(nearest[0] < 24, "it came in towards the square: " + nearest[0]);
                boolean down = true;
                for (Pillager pl : band) if (pl.isAlive()) down = false;
                helper.assertTrue(down || t[0] > 600, "the raiders are down (or it held the square a good while)");
                Kit.log("[VT] kit06 raid: joined " + joined[0] + ", fought " + fought[0] + ", nearest the square " + String.format("%.1f", nearest[0])
                    + ", raiders down " + down + " | " + allWords(a));
                com.jrpetty.mcassistant.entity.Raids.allClear(level, v);
                for (Pillager pl : band) pl.discard();
            });
        });
    }
}
