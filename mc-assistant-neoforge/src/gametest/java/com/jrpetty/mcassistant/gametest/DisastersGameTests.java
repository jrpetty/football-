package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.BucketChain;
import com.jrpetty.mcassistant.entity.Disasters;
import com.jrpetty.mcassistant.entity.Droughts;
import com.jrpetty.mcassistant.entity.FireBrigade;
import com.jrpetty.mcassistant.entity.FireSafety;
import com.jrpetty.mcassistant.entity.Floods;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Rebuilding;
import com.jrpetty.mcassistant.entity.TownBell;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [disasters] Fire, flood and drought, and the town's answer to each.
 *
 * <ul>
 * <li><b>dd01</b>: a lit forge in the smithy with timber beside it throws a spark; the fire bell rings (a dozen
 *     strokes on the town's bell), a hand puts it out with the stores' bucket from the pond, the books put it down
 *     to the forge, and the town lays stone round its forges after (nothing burnable is left by the furnace).</li>
 * <li><b>dd02</b>: a big fire (six blocks alight) a dozen blocks from a pond draws a bucket chain of four or more:
 *     the buckets pass hand to hand, the end throws them on the fire, and when it is out every bucket the chain had
 *     (the stores' two and those made of their iron) is back in the stores.</li>
 * <li><b>dd03</b>: a house's wall burnt: what burned is written down, put back exactly as it stood out of the stores'
 *     planks (the stores go down by as many), waiting when the planks run short; its household sleeps at a
 *     neighbour's till it is done.</li>
 * <li><b>dd04</b>: a forced flood on a river beside a low street: water only in empty cells a block over the river,
 *     nothing else in the ground round it touched, the folk in the low house gets out to the high ground, nobody
 *     drowns, and when it drains every cell of it is as it was and no water is left anywhere (what the town's own
 *     work changed meanwhile is told, not counted).</li>
 * <li><b>dd05</b>: after the flood the town raises a levee along the bank out of the stores' earth, and the same
 *     flood stops at it: no water on the low ground, none in the house.</li>
 * <li><b>dd06</b>: a forced drought: a dry field's crops grow at under three fifths of their pace (a watered field's
 *     not at all slowed); then the town digs irrigation through the dry field out of the pond, and it is wet.</li>
 * <li><b>dd07</b>: never ruinous: one fire along three houses at once (every flame of it alight); the third is
 *     never let burn.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DisastersGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ helpers

    /** A village's first folk on clean ground at x, Z, in the working day. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, int r) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        level.updateSkyBrightness();
        Kit.hold(level, x, Z, r);
        Kit.prepare(level, x, Z, r);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        Disasters.onForTests(true);
        return f;
    }

    private static VillageFolkEntity another(GameTestHelper helper, BlockPos at, UUID village) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(helper.getLevel(), at, 0.0F);
        helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village at " + at.toShortString());
        f.removeMatching(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET), 64);
        return f;
    }

    /** Nothing in the village's stores but what a test puts there: one chest of the stores, filled with these. */
    private static Container stores(ServerLevel level, UUID village, BlockPos at, ItemStack... goods) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    /** A level patch of {@code top} at height y (its top), dirt under it, cleared above. */
    private static void ground(ServerLevel level, int x0, int z0, int x1, int z1, int y, BlockState top) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = 1; dy <= 14; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                for (int dy = -4; dy < 0; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y, z), top, 2);
            }
        }
    }

    /** Fire held where it is set while a test runs (no spreading, no burning away), put back as it was after. */
    private static boolean holdFire(ServerLevel level) {
        GameRules.BooleanValue rule = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean was = rule.get();
        rule.set(false, level.getServer());
        return was;
    }

    private static void letFire(ServerLevel level, boolean was) {
        level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(was, level.getServer());
    }

    private static int fires(ServerLevel level, BlockPos c, int r) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -4, -r), c.offset(r, 8, r))) if (level.getBlockState(p).is(BlockTags.FIRE)) n++;
        return n;
    }

    // ============================================================ dd01: a spark from the forge

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "dd01_forge_spark")
    public static void dd01_forge_spark(GameTestHelper helper) {
        final int x = 1020000;
        VillageFolkEntity first = founder(helper, x, 40);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        int gy = heart.getY() - 1;
        ground(level, x + 5, Z - 4, x + 24, Z + 16, gy, Blocks.GRASS_BLOCK.defaultBlockState());
        List<VillageFolkEntity> folk = new ArrayList<>(List.of(first));
        first.removeMatching(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET), 64);
        folk.add(another(helper, Kit.surface(level, x + 2, Z + 2), id));
        folk.add(another(helper, Kit.surface(level, x - 2, Z + 2), id));
        // The smithy, its furnace lit (coal and iron in it), its timber walls beside it; a pond, and a bell at the heart.
        BlockPos smithy = new BlockPos(x + 14, gy + 1, Z + 4);
        BuildGoal.stamp(level, "smithy", smithy, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "smithy", smithy, Direction.NORTH);
        BlockPos furnace = null;
        for (BuildGoal.Placement p : BuildGoal.plan("smithy", smithy, Direction.NORTH, 13)) {
            if (p.part() == BuildGoal.Part.FURNACE && level.getBlockEntity(p.pos()) instanceof AbstractFurnaceBlockEntity fb) {
                fb.setItem(0, new ItemStack(Items.RAW_IRON, 16));
                fb.setItem(1, new ItemStack(Items.COAL, 16));
                fb.setChanged();
                if (furnace == null) furnace = p.pos();
            }
        }
        helper.assertTrue(furnace != null, "the smithy has a furnace");
        Kit.pond(level, x + 8, Z + 11, 2);
        level.setBlock(Kit.surface(level, x + 1, Z - 2), Blocks.BELL.defaultBlockState(), 3);
        TownBell.forgetForTests(id);
        stores(level, id, Kit.surface(level, x - 3, Z - 3), new ItemStack(Items.BUCKET), new ItemStack(Items.BREAD, 16), new ItemStack(Items.COBBLESTONE, 32));
        final BlockPos forge = furnace;
        final boolean was = holdFire(level);
        final BlockPos[] spark = { null };
        final long[] outAt = { -1 };
        final int[] firesBefore = { Annals.fires(id) };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000L > 10000) { level.setDayTime(2000); level.updateSkyBrightness(); }
            for (VillageFolkEntity f : folk) if (f.breakNowForTests()) { level.setDayTime(level.getDayTime() + 200); level.updateSkyBrightness(); }
            if (spark[0] == null) {
                BlockState st = level.getBlockState(forge);
                if (t < 10 || !st.getValue(AbstractFurnaceBlock.LIT)) {
                    if (t > 400) { letFire(level, was); helper.fail("the furnace never lit: " + st); }
                    return;
                }
                int burn = FireSafety.burnablesForTests(level, forge);
                spark[0] = FireSafety.sparkForTests(level, id);
                Kit.log("dd01 the forge at " + forge.toShortString() + " lit at tick " + t + ", " + burn + " burnable blocks beside it; spark caught at "
                    + (spark[0] == null ? "nowhere" : spark[0].toShortString()));
                if (spark[0] == null) { letFire(level, was); helper.fail("no spark from a lit forge with " + burn + " burnables beside it"); return; }
                FireBrigade.watchForTests(level, id);
                return;
            }
            boolean burning = fires(level, smithy, 8) > 0;
            if (t % 100 == 0) {
                StringBuilder sb = new StringBuilder();
                for (VillageFolkEntity f : folk) sb.append(" | ").append(FireBrigade.onIt(f) ? "AT THE FIRE " : "").append(f.debugLine());
                Kit.log("dd01 @" + t + " fires " + fires(level, smithy, 8) + ", bell strokes " + FireBrigade.alarmForTests(id) + sb);
            }
            if (!burning && outAt[0] < 0) {
                outAt[0] = t;
                FireBrigade.watchForTests(level, id);                       // the books closed on it
            }
            if (outAt[0] >= 0 && t >= outAt[0] + 2) {
                letFire(level, was);
                List<String> log = Annals.fireLog(id);
                String last = log.isEmpty() ? "" : log.get(log.size() - 1);
                int strokes = FireBrigade.alarmForTests(id);
                int[] care = FireSafety.careForTests(id);
                Kit.log("dd01 out at tick " + outAt[0] + "; bell strokes " + strokes + "; books: " + Annals.fires(id) + " fires, last: " + last
                    + "; care {stone rule, laid, cauldron rule, set, station} " + java.util.Arrays.toString(care));
                helper.assertTrue(strokes >= 1, "the fire bell rang: " + strokes + " strokes");
                helper.assertTrue(Annals.fires(id) == firesBefore[0] + 1 && last.contains("spark from") && last.contains("put out by"),
                    "the books put the fire down to the forge's spark, and who put it out: " + last);
                helper.assertTrue(care[0] == 1 && care[2] == 1, "after a forge's fire the town resolves on stone round its forges and cauldrons");
                // The town takes care: stone round the forge, out of the stores; nothing burnable left beside it.
                int burnBefore = FireSafety.burnablesForTests(level, forge);
                int cobble = stock(level, id, s -> s.is(Items.COBBLESTONE));
                FireSafety.worksForTests(level, id);
                int burnAfter = FireSafety.burnablesForTests(level, forge);
                int cobbleAfter = stock(level, id, s -> s.is(Items.COBBLESTONE));
                int planks = stock(level, id, s -> s.is(ItemTags.PLANKS));
                Kit.log("dd01 stone round the forge: burnables beside it " + burnBefore + " -> " + burnAfter + "; cobblestone " + cobble + " -> "
                    + cobbleAfter + "; timber taken out into the stores: " + planks + " planks; " + java.util.Arrays.toString(FireSafety.careForTests(id)));
                helper.assertTrue(burnAfter < burnBefore && cobbleAfter < cobble, "stone laid round the forge out of the stores: burnables "
                    + burnBefore + " -> " + burnAfter + ", cobble " + cobble + " -> " + cobbleAfter);
                helper.succeed();
            } else if (t >= 2200) {
                letFire(level, was);
                helper.fail("the fire was not put out: " + fires(level, smithy, 8) + " alight, " + java.util.Arrays.toString(FireBrigade.townForTests(id)));
            }
        });
    }

    // ============================================================ dd02: the bucket chain

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "dd02_bucket_chain")
    public static void dd02_bucket_chain(GameTestHelper helper) {
        final int x = 1022000;
        VillageFolkEntity first = founder(helper, x, 40);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        int gy = heart.getY() - 1;
        ground(level, x + 5, Z - 12, x + 24, Z + 14, gy, Blocks.GRASS_BLOCK.defaultBlockState());
        first.removeMatching(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET), 64);
        List<VillageFolkEntity> folk = new ArrayList<>(List.of(first));
        // Five folk: the chain takes them all (a free hand's fists would have the fire out before the chain got going).
        for (int i = 0; i < 4; i++) folk.add(another(helper, Kit.surface(level, x - 3 + 2 * i, Z + 3), id));
        // Two of the stores' buckets and three iron (a third bucket); a pond; six blocks alight on a timber floor.
        stores(level, id, Kit.surface(level, x - 4, Z - 4), new ItemStack(Items.BUCKET, 2), new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.BREAD, 16));
        Kit.pond(level, x + 8, Z + 8, 2);
        List<BlockPos> lit = new ArrayList<>();
        for (int dx = 12; dx <= 14; dx++) {
            for (int dz = -5; dz <= -4; dz++) {
                BlockPos plank = new BlockPos(x + dx, gy, Z + dz);
                level.setBlock(plank, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                BlockPos f = plank.above();
                level.setBlock(f, BaseFireBlock.getState(level, f), 3);
                lit.add(f);
            }
        }
        final boolean was = holdFire(level);
        final int bucketsBefore = stock(level, id, s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET));
        FireBrigade.watchForTests(level, id);
        int[] chain = BucketChain.nowForTests(id);
        Kit.log("dd02 fire of " + lit.size() + " blocks; chain " + (chain == null ? "none" : java.util.Arrays.toString(chain))
            + " {links, passes, fills, pours, out, buckets}; places " + BucketChain.spotsForTests(id));
        helper.assertTrue(chain != null && chain[0] >= 4, "a bucket chain of four or more: " + (chain == null ? "none" : chain[0]));
        final long[] outAt = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000L > 10000) { level.setDayTime(2000); level.updateSkyBrightness(); }
            for (VillageFolkEntity f : folk) if (f.breakNowForTests()) { level.setDayTime(level.getDayTime() + 200); level.updateSkyBrightness(); }
            int alight = 0;
            for (BlockPos p : lit) if (level.getBlockState(p).is(BlockTags.FIRE)) alight++;
            if (t % 100 == 0) {
                int[] now = BucketChain.nowForTests(id);
                StringBuilder sb = new StringBuilder();
                for (VillageFolkEntity f : folk) sb.append(" | ").append(f.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).getHoverName().getString())
                    .append(" ").append(f.blockPosition().toShortString()).append(" ").append(f.hobbyNow());
                Kit.log("dd02 @" + t + " alight " + alight + ", chain " + (now == null ? "stood down" : java.util.Arrays.toString(now)) + sb);
            }
            if (alight == 0 && outAt[0] < 0) {
                outAt[0] = t;
                FireBrigade.watchForTests(level, id);
            }
            if (outAt[0] >= 0 && t >= outAt[0] + 2) {
                letFire(level, was);
                int[] last = BucketChain.lastForTests(id);
                int bucketsAfter = stock(level, id, s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET));
                int carried = 0;
                for (VillageFolkEntity f : folk) carried += f.countCarried(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET));
                int iron = stock(level, id, s -> s.is(Items.IRON_INGOT));
                List<String> log = Annals.fireLog(id);
                Kit.log("dd02 out at " + outAt[0] + "; the chain {links, passes, fills, pours, out, buckets, made of iron, given back} "
                    + java.util.Arrays.toString(last) + "; buckets in the stores " + bucketsBefore + " -> " + bucketsAfter + ", still carried "
                    + carried + ", iron left " + iron + "; books: " + (log.isEmpty() ? "" : log.get(log.size() - 1)));
                helper.assertTrue(last != null && last[0] >= 4, "a chain of four or more stood down: " + java.util.Arrays.toString(last));
                helper.assertTrue(last[1] >= last[0] && last[3] >= 1, "buckets passed hand to hand (" + last[1] + " passes) and thrown on the fire (" + last[3] + ")");
                helper.assertTrue(last[7] == last[5], "every bucket the chain had went back: " + last[7] + " of " + last[5]);
                helper.assertTrue(bucketsAfter >= bucketsBefore && carried == 0, "the buckets are back in the stores: " + bucketsBefore + " -> " + bucketsAfter
                    + ", " + carried + " still carried");
                helper.succeed();
            } else if (t >= 2800) {
                letFire(level, was);
                helper.fail("the big fire was not put out: " + alight + " alight; chain " + java.util.Arrays.toString(BucketChain.nowForTests(id)));
            }
        });
    }

    // ============================================================ dd03: rebuilt from the stores

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dd03_rebuilt")
    public static void dd03_rebuilt(GameTestHelper helper) {
        final int x = 1024000;
        VillageFolkEntity first = founder(helper, x, 40);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        int gy = heart.getY() - 1;
        ground(level, x + 5, Z - 4, x + 24, Z + 16, gy, Blocks.GRASS_BLOCK.defaultBlockState());
        BlockPos anchor = new BlockPos(x + 14, gy + 1, Z + 6);
        BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "house", anchor, Direction.NORTH);
        Villages.recountBeds(id);
        Homes.tickForTests(level, v);
        // Three planks in the stores to begin with: not enough.
        final BlockPos chest = Kit.surface(level, x - 3, Z - 3);
        stores(level, id, chest, new ItemStack(Items.OAK_PLANKS, 3), new ItemStack(Items.BREAD, 16));
        final boolean was = holdFire(level);
        helper.runAtTickTime(5, () -> {
            // A flame against the house's back wall; the brigade sees it, and the house is taken down as it stands.
            List<BlockPos> wall = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan("house", anchor, Direction.NORTH, 13)) {
                if (level.getBlockState(p.pos()).is(Blocks.OAK_PLANKS) && p.pos().getZ() == anchor.getZ() - 3) wall.add(p.pos());
            }
            helper.assertTrue(wall.size() >= 6, "the house's back wall is of planks: " + wall.size());
            BlockPos flame = wall.get(0).north();
            level.setBlock(flame, BaseFireBlock.getState(level, flame), 3);
            FireBrigade.watchForTests(level, id);
            // It burns: six of the wall's planks gone. Then it is put out.
            Map<BlockPos, BlockState> stood = new HashMap<>();
            for (int i = 0; i < 6; i++) {
                stood.put(wall.get(i), level.getBlockState(wall.get(i)));
                level.setBlock(wall.get(i), Blocks.AIR.defaultBlockState(), 3);
            }
            level.removeBlock(flame, false);
            FireBrigade.watchForTests(level, id);
            letFire(level, was);
            Map<BlockPos, Integer> waiting = Rebuilding.waitingForTests(id);
            Map<BlockPos, BlockState> cells = Rebuilding.cellsForTests(id);
            int[] counts = Disasters.countsForTests(id);
            Kit.log("dd03 burnt: " + waiting + " (blocks to put back by building); counts {burnt, rebuilt, ...} " + java.util.Arrays.toString(counts));
            helper.assertTrue(waiting.getOrDefault(anchor, 0) == 6 && cells.keySet().equals(stood.keySet()),
                "the six burnt planks written down to be put back: " + waiting + " " + cells.keySet());
            boolean displaced = false;
            for (UUID m : Homes.membersForTests(id, anchor)) {
                for (com.jrpetty.mcassistant.entity.AssistantEntity a : Villages.folkOf(id)) {
                    if (a.getUUID().equals(m) && a instanceof VillageFolkEntity f && Rebuilding.displacedForTests(f)) displaced = true;
                }
            }
            Kit.log("dd03 the household of the burnt house " + Homes.membersForTests(id, anchor) + " displaced: " + displaced);
            // Three planks: three go back, and the work waits for more. The stores set to three planks again now: the
            // town's other work had five ticks at the first three (a sign is two planks), and that is not the fire's.
            int had = stock(level, id, s -> s.is(ItemTags.PLANKS));
            Container box = stores(level, id, chest, new ItemStack(Items.OAK_PLANKS, 3), new ItemStack(Items.BREAD, 16));
            Kit.log("dd03 planks in the stores when the fire was out: " + had + " of the three put there; three again now");
            int put = Rebuilding.workForTests(level, id);
            String waits = Rebuilding.waitsForTests(id);
            int planks = stock(level, id, s -> s.is(ItemTags.PLANKS));
            Kit.log("dd03 with three planks: " + put + " put back, waits for '" + waits + "', planks left " + planks);
            helper.assertTrue(put == 3 && planks == 0 && waits.contains("plank"), "three put back for the three planks, then it waits: " + put + ", '" + waits + "'");
            // Ten more planks: the rest go back, as they stood, and the stores go down by three more.
            box.setItem(5, new ItemStack(Items.OAK_PLANKS, 10));
            box.setChanged();
            int more = Rebuilding.workForTests(level, id);
            int left = stock(level, id, s -> s.is(ItemTags.PLANKS));
            int same = 0;
            for (Map.Entry<BlockPos, BlockState> e : stood.entrySet()) if (level.getBlockState(e.getKey()).equals(e.getValue())) same++;
            Kit.log("dd03 then " + more + " more put back; planks 10 -> " + left + "; " + same + " of 6 as they stood; waiting "
                + Rebuilding.waitingForTests(id) + "; record: " + Disasters.logForTests(id));
            helper.assertTrue(more == 3 && left == 7 && same == 6, "the rest put back out of the stores, as they stood: " + more + " put, planks "
                + left + ", " + same + " the same");
            helper.assertTrue(Rebuilding.waitingForTests(id).isEmpty(), "nothing left to rebuild");
            helper.succeed();
        });
    }

    // ============================================================ dd04 / dd05: the flood, and the levee

    /**
     * A river and its low ground: high ground (its top a block over the river) everywhere round, a basin of low
     * stone ground (its top level with the river's water) between the river and the high ground, a low street of
     * beaten earth along the bank, and a house of the town's in the basin. Returns {the house's anchor, the
     * river's water level (as a BlockPos y), the basin's corner and its far corner}.
     */
    private static BlockPos[] riverside(ServerLevel level, UUID id, int x, int gy) {
        int high = gy + 1, low = gy;
        // High ground everywhere about (the river's ends closed by it too).
        ground(level, x - 30, Z + 5, x + 30, Z + 44, high, Blocks.STONE.defaultBlockState());
        // The basin: low stone ground.
        ground(level, x - 18, Z + 10, x + 18, Z + 26, low, Blocks.STONE.defaultBlockState());
        // The river: across the whole width, its water level with the basin's floor, two deep.
        for (int xx = x - 26; xx <= x + 26; xx++) {
            for (int z = Z + 27; z <= Z + 31; z++) {
                for (int dy = 1; dy <= 14; dy++) level.setBlock(new BlockPos(xx, low + dy, z), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(xx, low - 2, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(xx, low - 1, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(xx, low, z), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        // The low street of beaten earth along the bank.
        for (int xx = x - 18; xx <= x + 18; xx++) {
            for (int z = Z + 24; z <= Z + 26; z++) level.setBlock(new BlockPos(xx, low, z), Blocks.DIRT_PATH.defaultBlockState(), 2);
        }
        BlockPos anchor = new BlockPos(x + 8, low + 1, Z + 17);
        BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "house", anchor, Direction.NORTH);
        // This is the town's river (whatever water the world has about), as a scene set out for the pictures is.
        Floods.riverForTests(id, new BlockPos(x - 30, low, Z + 5), new BlockPos(x + 30, low, Z + 44), low);
        return new BlockPos[]{ anchor, new BlockPos(x, low, Z + 29), new BlockPos(x - 18, low, Z + 10), new BlockPos(x + 18, low, Z + 26) };
    }

    /** Every block in a box, as it is. */
    private static Map<BlockPos, BlockState> look(ServerLevel level, BlockPos a, BlockPos b) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(a, b)) out.put(p.immutable(), level.getBlockState(p));
        return out;
    }

    /** How many blocks of the first look are not the same in the second, and the first few of them, for the log. */
    private static String differences(Map<BlockPos, BlockState> was, Map<BlockPos, BlockState> now) {
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<BlockPos, BlockState> e : was.entrySet()) {
            BlockState st = now.get(e.getKey());
            if (st == null || st.equals(e.getValue())) continue;
            if (n++ < 8) sb.append(n == 1 ? " (" : ", ").append(e.getKey().toShortString()).append(' ')
                .append(e.getValue().getBlock().getName().getString()).append(" -> ").append(st.getBlock().getName().getString());
        }
        return n + (n > 0 ? sb + (n > 8 ? ", ...)" : ")") : "");
    }

    /** Is this in the house's footprint (within its walls)? */
    private static boolean inHouse(BlockPos anchor, BlockPos p) {
        return Math.abs(p.getX() - anchor.getX()) <= 2 && Math.abs(p.getZ() - anchor.getZ()) <= 2;
    }

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "dd04_flood")
    public static void dd04_flood(GameTestHelper helper) {
        final int x = 1026000;
        VillageFolkEntity first = founder(helper, x, 56);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        int gy = heart.getY() - 1;
        BlockPos[] site = riverside(level, id, x, gy);
        BlockPos anchor = site[0];
        int w = site[1].getY();
        Homes.tickForTests(level, v);
        final BlockPos boxA = new BlockPos(x - 30, w - 2, Z + 5), boxB = new BlockPos(x + 30, w + 6, Z + 44);
        final Map<BlockPos, BlockState> setUp = look(level, boxA, boxB);
        // The ground as it is the moment the river comes up; and the flood's cells.
        final Map<BlockPos, BlockState> before = new HashMap<>();
        final java.util.Set<BlockPos> cells = new java.util.HashSet<>();
        final VillageFolkEntity[] in = { null };
        final float[] health = { 0 };
        helper.runAtTickTime(5, () -> {
            // A folk in the low house, on its floor, as the river comes up.
            in[0] = another(helper, anchor, id);
            health[0] = in[0].getHealth();
            // The town's own work has had five ticks at the ground since it was laid out (a road, a lamp, a sign):
            // told, and the flood measured against the ground as it is now.
            before.putAll(look(level, boxA, boxB));
            Kit.log("dd04 the town's own work since the ground was laid out: " + differences(setUp, before));
            int n = Floods.floodForTests(level, id, 1);
            cells.addAll(Floods.cellsForTests(id));
            int[] now = Floods.floodNowForTests(id);
            int wrong = 0, notAir = 0, inside_ = 0, onRiver = 0, onLow = 0;
            for (BlockPos p : cells) {
                if (p.getY() != w + 1) wrong++;
                BlockState was = before.get(p);
                if (was != null && !was.isAir()) notAir++;
                if (!level.getBlockState(p).is(Blocks.WATER)) wrong++;
                if (inHouse(anchor, p)) inside_++;
                if (level.getFluidState(p.below()).isSource() && p.getZ() >= Z + 27) onRiver++;
                else onLow++;
            }
            // Nothing else in the ground round it changed: every other block as it was.
            int changed = 0;
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                if (cells.contains(e.getKey())) continue;
                BlockState st = level.getBlockState(e.getKey());
                if (!st.equals(e.getValue()) && !(st.getBlock() instanceof DoorBlock)) changed++;
            }
            Kit.log("dd04 flood: " + n + " cells (" + onRiver + " over the river, " + onLow + " on the low ground, " + inside_ + " in the house); "
                + "river at y " + w + "; {w, rise, homes, spoiled, soaked} " + java.util.Arrays.toString(now) + "; not a block " + w + "+1: " + wrong
                + ", not empty before: " + notAir + ", other blocks changed: " + changed);
            helper.assertTrue(n > 100 && wrong == 0 && notAir == 0, "water only in empty cells a block over the river: " + n + " cells, " + wrong
                + " wrong, " + notAir + " not empty before");
            helper.assertTrue(onLow > 50 && inside_ > 0 && now != null && now[2] >= 1, "the low ground and the low house under water: " + onLow + " low, "
                + inside_ + " in the house");
            helper.assertTrue(changed == 0, "nothing else changed: " + changed);
        });
        final long[] outAt = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            VillageFolkEntity inside = in[0];
            if (t < 6 || inside == null) return;
            if (level.getDayTime() % 24000L > 10000) { level.setDayTime(2000); level.updateSkyBrightness(); }
            if (inside.breakNowForTests()) { level.setDayTime(level.getDayTime() + 200); level.updateSkyBrightness(); }
            BlockPos at = inside.blockPosition();
            boolean wet = !level.getFluidState(at).isEmpty();
            if (t % 60 == 0) Kit.log("dd04 @" + t + " " + inside.displayNameCap() + " at " + at.toShortString() + (wet ? " in the water" : " dry")
                + (inHouse(anchor, at) ? " IN THE HOUSE" : "") + ", breath given " + Floods.breathForTests(inside) + "; " + inside.debugLine());
            if (outAt[0] < 0 && !inHouse(anchor, at) && !wet && at.getY() >= w + 2) outAt[0] = t;
            if (outAt[0] >= 0 && t >= outAt[0] + 20) {
                final Map<BlockPos, BlockState> high = look(level, boxA, boxB);
                int drained = Floods.drainForTests(level, id);
                // The flood taken up exactly: no water left where there was none, every cell of it as it was before
                // the river came up, and the draining touching nothing else. What else changed while it stood (the
                // town's own work, a door opened or shut) is told, not counted: the flood never left its cells.
                int water = 0, notBack = 0, touched = 0;
                StringBuilder cellsOff = new StringBuilder();
                Map<BlockPos, BlockState> after = new HashMap<>();
                for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                    BlockPos p = e.getKey();
                    BlockState st = level.getBlockState(p);
                    after.put(p, st);
                    // Water of any kind (a waterlogged block too) where there was none.
                    if (!st.getFluidState().isEmpty() && e.getValue().getFluidState().isEmpty()) water++;
                    if (cells.contains(p)) {
                        // A cell the town built in while the water stood (a lamp post, a fence) is the town's now, not the
                        // flood's: the draining leaves it be, as it would a player's block. The rest are as they were.
                        BlockState risen = high.get(p);
                        boolean built = risen != null && !risen.is(Blocks.WATER) && risen.getFluidState().isEmpty();
                        if (!built && !st.equals(e.getValue()) && notBack++ < 8) cellsOff.append(" | ").append(p.toShortString()).append(' ')
                            .append(e.getValue()).append(" -> ").append(high.get(p)).append(" -> ").append(st);
                    } else if (!st.equals(high.get(p))) {
                        touched++;
                    }
                }
                Map<BlockPos, BlockState> others = new HashMap<>(before);
                others.keySet().removeAll(cells);
                Kit.log("dd04 out of the house by tick " + outAt[0] + " to " + inside.blockPosition().toShortString() + " (health " + health[0] + " -> "
                    + inside.getHealth() + "); drained " + drained + " cells; water left where none was: " + water + ", cells not as they were: " + notBack + cellsOff
                    + ", other blocks the draining changed: " + touched + "; changed meanwhile by the town: " + differences(others, after)
                    + "; levee planned: " + Floods.leveePlanForTests(id).size() + "; record: " + Disasters.logForTests(id));
                helper.assertTrue(inside.isAlive() && inside.getHealth() >= health[0], "nobody drowned: " + inside.getHealth());
                helper.assertTrue(water == 0 && notBack == 0 && touched == 0, "the flood taken up, exactly: " + water + " water left, " + notBack
                    + " of its cells not as they were, " + touched + " other blocks changed by the draining");
                helper.succeed();
            } else if (t >= 1400) {
                Floods.drainForTests(level, id);
                helper.fail("the folk did not leave the flooded house: " + inside.blockPosition().toShortString() + " " + inside.debugLine());
            }
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dd05_levee")
    public static void dd05_levee(GameTestHelper helper) {
        final int x = 1028000;
        VillageFolkEntity first = founder(helper, x, 56);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        int gy = heart.getY() - 1;
        BlockPos[] site = riverside(level, id, x, gy);
        BlockPos anchor = site[0];
        int w = site[1].getY();
        Homes.tickForTests(level, v);
        stores(level, id, Kit.surface(level, x - 3, Z - 3), new ItemStack(Items.DIRT, 64), new ItemStack(Items.COBBLESTONE, 16), new ItemStack(Items.BREAD, 8));
        helper.runAtTickTime(5, () -> {
            int first_ = Floods.floodForTests(level, id, 1);
            int drained = Floods.drainForTests(level, id);
            List<BlockPos> plan = Floods.leveePlanForTests(id);
            int dirt = stock(level, id, s -> s.is(Items.DIRT)), cobble = stock(level, id, s -> s.is(Items.COBBLESTONE));
            int raised = Floods.leveeForTests(level, id);
            int dirtAfter = stock(level, id, s -> s.is(Items.DIRT)), cobbleAfter = stock(level, id, s -> s.is(Items.COBBLESTONE));
            int standing = 0, earth = 0, steps = 0;
            for (BlockPos p : plan) {
                BlockState st = level.getBlockState(p);
                if (!st.isAir() && level.getFluidState(p).isEmpty()) standing++;
                if (st.is(Blocks.DIRT)) earth++;
                if (st.is(Blocks.COBBLESTONE_SLAB)) steps++;
            }
            Kit.log("dd05 the flood: " + first_ + " cells, drained " + drained + "; levee planned " + plan.size() + " blocks, raised " + raised
                + " (" + standing + " standing: " + earth + " of earth, " + steps + " steps of slab where a street goes down); dirt " + dirt + " -> "
                + dirtAfter + ", cobblestone " + cobble + " -> " + cobbleAfter);
            helper.assertTrue(first_ > 50 && plan.size() >= 20, "a flood, and a levee planned along the bank: " + plan.size());
            helper.assertTrue(raised == plan.size() && standing == plan.size(), "the levee raised: " + raised + " of " + plan.size());
            helper.assertTrue(dirt - dirtAfter == earth && (cobble - cobbleAfter) * 2 >= steps && cobble - cobbleAfter <= steps,
                "out of the stores' earth and stone: dirt " + dirt + " -> " + dirtAfter + " for " + earth + ", cobble " + cobble + " -> "
                + cobbleAfter + " for " + steps + " steps (a cobblestone cut into two)");
            // The same flood again: it stops at the levee.
            Floods.againForTests(id);
            int again = Floods.floodForTests(level, id, 1);
            int onLow = 0, inside_ = 0;
            for (BlockPos p : Floods.cellsForTests(id)) {
                if (p.getZ() < Z + 27) onLow++;
                if (inHouse(anchor, p)) inside_++;
            }
            int[] now = Floods.floodNowForTests(id);
            Kit.log("dd05 the same flood after the levee: " + again + " cells, " + onLow + " on the low ground, " + inside_ + " in the house; "
                + java.util.Arrays.toString(now) + "; record: " + Disasters.logForTests(id));
            Floods.drainForTests(level, id);
            helper.assertTrue(onLow == 0 && inside_ == 0, "the levee keeps the river off the low ground: " + onLow + " cells on it, " + inside_ + " in the house");
            helper.succeed();
        });
    }

    // ============================================================ dd06: the drought, and irrigation

    /** Growth stages a field's crops make in so many of the game's random ticks each. */
    private static int grow(ServerLevel level, List<BlockPos> crops, int ticks, RandomSource r) {
        int before = 0, after = 0;
        for (BlockPos p : crops) before += ((CropBlock) Blocks.WHEAT).getAge(level.getBlockState(p));
        for (int i = 0; i < ticks; i++) {
            for (BlockPos p : crops) {
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof CropBlock) st.randomTick(level, p, r);
            }
        }
        for (BlockPos p : crops) after += ((CropBlock) Blocks.WHEAT).getAge(level.getBlockState(p));
        return after - before;
    }

    /** A five-by-five field of seedling wheat here, its farmland dry or wet. */
    private static List<BlockPos> field(ServerLevel level, BlockPos c, boolean wet) {
        List<BlockPos> crops = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = c.offset(dx, 0, dz);
                if (wet && dx == 0 && dz == 0) {
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);   // a source in the middle: a watered field
                    continue;
                }
                level.setBlock(p, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, wet ? 7 : 0), 2);
                level.setBlock(p.above(), ((CropBlock) Blocks.WHEAT).getStateForAge(0), 2);
                crops.add(p.above());
            }
        }
        return crops;
    }

    private static void seedlings(ServerLevel level, List<BlockPos> crops) {
        for (BlockPos p : crops) level.setBlock(p, ((CropBlock) Blocks.WHEAT).getStateForAge(0), 2);
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dd06_drought")
    public static void dd06_drought(GameTestHelper helper) {
        final int x = 1030000;
        VillageFolkEntity first = founder(helper, x, 40);
        ServerLevel level = helper.getLevel();
        level.setDayTime(6000);
        level.updateSkyBrightness();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        int gy = heart.getY() - 1;
        ground(level, x - 4, Z + 6, x + 30, Z + 30, gy, Blocks.STONE.defaultBlockState());
        BlockPos dryC = new BlockPos(x + 8, gy, Z + 12), wetC = new BlockPos(x + 22, gy, Z + 12);
        List<BlockPos> dry = field(level, dryC, false), wet = field(level, wetC, true);
        Droughts.fieldForTests(id, dryC, 2);
        Droughts.fieldForTests(id, wetC, 2);
        // A pond to dig the channels from, and the stores' bucket to carry the water in.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(new BlockPos(x + 8 + dx, gy, Z + 22 + dz), Blocks.WATER.defaultBlockState(), 3);
        stores(level, id, Kit.surface(level, x - 3, Z - 3), new ItemStack(Items.BUCKET, 2), new ItemStack(Items.BREAD, 8));
        helper.runAtTickTime(5, () -> {
            RandomSource r = RandomSource.create(4242L);
            int ticks = 24;
            // No drought: the dry field at its own (slow) pace, the control.
            int dryNormal = grow(level, dry, ticks, r);
            seedlings(level, dry);
            Droughts.droughtForTests(level, id, true);
            boolean stuntDry = Droughts.stuntedForTests(level, dry.get(0)), stuntWet = Droughts.stuntedForTests(level, wet.get(0));
            int dryDrought = grow(level, dry, ticks, r);
            int wetDrought = grow(level, wet, ticks, r);
            List<BlockPos> waiting = Droughts.dryFieldsForTests(id);
            Kit.log("dd06 growth in " + ticks + " random ticks a crop (25/24 crops): the dry field " + dryNormal + " stages without a drought, "
                + dryDrought + " in it; the watered field " + wetDrought + " in it; stunted: dry " + stuntDry + ", watered " + stuntWet
                + "; dry fields to irrigate: " + waiting);
            helper.assertTrue(stuntDry && !stuntWet, "the dry field withers, the watered one not: " + stuntDry + ", " + stuntWet);
            helper.assertTrue(dryDrought * 5 < dryNormal * 3, "the drought slows the dry field: " + dryDrought + " against " + dryNormal);
            helper.assertTrue(wetDrought > dryDrought * 2, "the watered field grows on: " + wetDrought + " against the dry field's " + dryDrought);
            helper.assertTrue(waiting.size() == 1 && waiting.get(0).getX() == dryC.getX(), "the dry field (only) set to be irrigated: " + waiting);
            // The town digs irrigation through it, a bucket of the stores' carried from the pond.
            int dirt = stock(level, id, s -> s.is(Items.DIRT));
            int dug = Droughts.irrigateForTests(level, id);
            int water = 0;
            for (BlockPos p : BlockPos.betweenClosed(dryC.offset(-2, 0, -2), dryC.offset(2, 0, 2))) if (level.getBlockState(p).is(Blocks.WATER)) water++;
            // The farmland drinks it in (the game's own look at its farmland).
            for (BlockPos p : BlockPos.betweenClosed(dryC.offset(-2, 0, -2), dryC.offset(2, 0, 2))) {
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof FarmBlock) st.randomTick(level, p.immutable(), r);
            }
            int stillDry = 0;
            for (BlockPos p : dry) if (level.getBlockState(p).getBlock() instanceof CropBlock && Droughts.stuntedForTests(level, p)) stillDry++;
            int[] counts = Disasters.countsForTests(id);
            int dirtAfter = stock(level, id, s -> s.is(Items.DIRT));
            Kit.log("dd06 irrigation: " + dug + " blocks of channel dug, " + water + " water in the field, soil into the stores " + dirt + " -> " + dirtAfter
                + "; crops still withering " + stillDry + "; counts " + java.util.Arrays.toString(counts) + "; record: " + Disasters.logForTests(id));
            helper.assertTrue(dug > 0 && water >= dug && counts[8] == 1, "the town dug irrigation through the dry field: " + dug + " blocks, "
                + counts[8] + " fields");
            helper.assertTrue(stillDry == 0, "the next drought passes it by: " + stillDry + " crops still dry");
            helper.assertTrue(dirtAfter == dirt + dug, "the soil dug out went into the stores: " + dirt + " -> " + dirtAfter);
            Droughts.droughtForTests(level, id, false);
            helper.succeed();
        });
    }

    // ============================================================ dd07: never ruinous

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "dd07_never_three")
    public static void dd07_never_three(GameTestHelper helper) {
        final int x = 1032000;
        VillageFolkEntity first = founder(helper, x, 48);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        int gy = heart.getY() - 1;
        ground(level, x + 5, Z - 4, x + 44, Z + 18, gy, Blocks.GRASS_BLOCK.defaultBlockState());
        // Three houses in a row, close together.
        List<BlockPos> houses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BlockPos a = new BlockPos(x + 12 + 10 * i, gy + 1, Z + 8);
            BuildGoal.stamp(level, "house", a, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", a, Direction.NORTH);
            houses.add(a);
        }
        final boolean was = holdFire(level);
        helper.runAtTickTime(5, () -> {
            // A line of flames along the backs of all three, every three blocks, on the grass: one fire. (On the ground,
            // so each stays alight where it is set: a flame in the air goes out at once unless what is beside it burns,
            // and in the gaps between the houses and against a window nothing does.)
            List<BlockPos> flames = new ArrayList<>();
            for (int fx = houses.get(0).getX() - 3; fx <= houses.get(2).getX() + 3; fx += 3) {
                BlockPos f = new BlockPos(fx, gy + 1, houses.get(0).getZ() - 4);
                level.setBlock(f, BaseFireBlock.getState(level, f), 3);
                flames.add(f);
            }
            int set = 0;
            for (BlockPos f : flames) if (level.getBlockState(f).is(BlockTags.FIRE)) set++;
            FireBrigade.watchForTests(level, id);
            int[] alight = new int[3];
            for (BlockPos f : flames) {
                if (!level.getBlockState(f).is(BlockTags.FIRE)) continue;
                int best = 0;
                for (int i = 1; i < 3; i++) if (Math.abs(f.getX() - houses.get(i).getX()) < Math.abs(f.getX() - houses.get(best).getX())) best = i;
                alight[best]++;
            }
            int[] town = FireBrigade.townForTests(id);
            int burning = 0;
            for (int a : alight) if (a > 0) burning++;
            Kit.log("dd07 a line of " + flames.size() + " flames along three houses (" + set + " alight as set): still alight by each house "
                + java.util.Arrays.toString(alight) + "; the town's fires {fires, blocks, hands} " + java.util.Arrays.toString(town));
            for (BlockPos f : flames) level.removeBlock(f, false);
            FireBrigade.watchForTests(level, id);
            letFire(level, was);
            helper.assertTrue(set == flames.size(), "every flame of the line alight as it was set: " + set + " of " + flames.size());
            helper.assertTrue(town[0] == 1, "one fire: " + town[0]);
            helper.assertTrue(burning <= 2, "no fire is let burn more than two buildings: " + java.util.Arrays.toString(alight));
            helper.succeed();
        });
    }
}
