package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.SmeltGoal;
import com.jrpetty.mcassistant.village.VillageMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Coming of age out of the Stone Age (Villages.wantsFor): the stone, the coal, the wall, room to
 * spare, a smeltery, food — and a meeting hall. A long real-terrain game's mountain town reached
 * the Stone Age on its sixteenth day and was still in it on its fifty-seventh, eighty-nine strong,
 * with two thousand stone, seventeen hundred logs and no hall: a house kept spare stood in front of
 * the hall on its list; its builders read the stores a hundred and twelve blocks out and thirty-two
 * up and down while the plan counted two hundred and twenty out and sixty-four, so they found the
 * hall "cannot afford it yet" beside the stores the plan said were plenty; a hall on a mountainside
 * wanted more under its floor than one pack can carry, and the builder waited for three parts in
 * four of it in the pack; and the smelters burnt the coal the age was asking for.
 *
 * <p>Like the village tests, each runs on its own ground, far from the others, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class AgeGameTests {

    private static final String EMPTY = "empty";

    /** A marked store chest at exactly this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    /** So many stacks of one thing. */
    private static ItemStack[] stacks(net.minecraft.world.item.Item item, int full, ItemStack... more) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < full; i++) out.add(new ItemStack(item, 64));
        out.addAll(List.of(more));
        return out.toArray(new ItemStack[0]);
    }

    /** Nothing in the village's stores (the founders' chest emptied): only what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    /** How much of this the marked stores within this box hold (as a builder used to count them). */
    private static int heldWithin(ServerLevel level, BlockPos heart, int radius, int tall, Predicate<ItemStack> what) {
        boolean before = ZoneChests.askAs(true);
        try {
            return ZoneChests.countIn(ZoneChests.around(level, heart, radius, tall).stream()
                .filter(f -> f.stillThere() && ZoneChests.isStashable(f)).toList(), what);
        } finally {
            ZoneChests.askAs(before);
        }
    }

    /** How much of this every container round here holds, every slot (a furnace's fuel and output too). */
    private static int inContainers(ServerLevel level, int cx, int cz, int r, Predicate<ItemStack> what) {
        int n = 0;
        for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
            for (int chunkZ = (cz - r) >> 4; chunkZ <= (cz + r) >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (var be : new ArrayList<>(level.getChunk(chunkX, chunkZ).getBlockEntities().values())) {
                    if (!(be instanceof Container c)) continue;
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        ItemStack s = c.getItem(i);
                        if (!s.isEmpty() && what.test(s)) n += s.getCount();
                    }
                }
            }
        }
        return n;
    }

    /** Everything this folk carries goes; it keeps a loaf, a pick and an axe, as a builder would. */
    private static void plainPack(VillageFolkEntity f) {
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.BREAD, 8));
        f.insertItem(new ItemStack(Items.STONE_PICKAXE));
        f.insertItem(new ItemStack(Items.STONE_AXE));
    }

    // ============================================================ the hall's turn

    /**
     * A Stone Age village with the stone, the coal and the food the age asks for, its wall, its
     * smeltery and four houses, and a house's worth short of the five homes it keeps spare: the
     * meeting hall is its next project, not the spare house (which goes up meanwhile only if the
     * hall is set aside). A village with every bed but one taken still raises a house first.
     * When the hall stands (room for six more besides), nothing is left on the Stone Age's list
     * and the village comes into the Iron Age.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "ag01_hall_before_a_spare_house")
    public static void ag01_hall_before_a_spare_house(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 150000, z = 40000;
        Kit.hold(level, x, z, 16);
        Kit.prepare(level, x, z, 16);
        BlockPos heart = Kit.surface(level, x, z);
        long now = level.getGameTime();
        Villages.projectDue(UUID.randomUUID(), now);                    // the villages' clock, as t15 has it
        List<String> built = List.of("storage", "storehouse", "shelter", "house", "house", "house", "house", "well",
            "fortify", "smeltery", "colony", "colony", "fountain");
        UUID id = UUID.randomUUID();
        Villages.restore(level, id, heart, Villages.Age.STONE, built, 0);
        int room = Villages.housing(id);
        int folk = room - 4;                          // short of the five spare, but nobody without a bed
        Villages.restore(level, id, heart, Villages.Age.STONE, built, folk);
        // The stores: what the Stone Age asks for (for up to Villages.AGE_FOLK of stone and coal), and a
        // larder for everybody.
        int stoneWanted = VillageMath.stoneWanted(Math.min(folk, Villages.AGE_FOLK));
        int coalWanted = VillageMath.coalWanted(Math.min(folk, Villages.AGE_FOLK));
        int foodWanted = VillageMath.foodWanted(folk);
        chestAt(level, heart.offset(2, 0, 0), stacks(Items.COBBLESTONE, (stoneWanted + 63) / 64 + 1,
            new ItemStack(Items.COAL, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64),
            new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64)));
        helper.assertTrue(coalWanted <= 64 && foodWanted <= 256, "the stores staged hold what the age asks: coal "
            + coalWanted + ", food " + foodWanted);
        Villages.forgetStock();

        List<String> wanted = Villages.projectsWanted(id);
        String next = Villages.nextProject(id);
        List<String> shortOf = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, id)) shortOf.add(n.what());
        Kit.log("ag01 " + folk + " folk, room for " + room + ": wanted " + wanted + ", next " + next + "; short of " + shortOf);
        helper.assertTrue(Villages.ageOf(id) == Villages.Age.STONE, "still in the Stone Age before the hall");
        helper.assertTrue(shortOf.contains("a meeting hall") && !shortOf.contains("stone") && !shortOf.contains("coal")
                && !shortOf.contains("food in the stores"),
            "the stores hold the stone, the coal and the food: the age is short of its hall (and room), got " + shortOf);
        helper.assertTrue("hall".equals(next), "the meeting hall goes up before a house kept spare, got " + next + " of " + wanted);
        helper.assertTrue(wanted.indexOf("house") > wanted.indexOf("hall"), "the spare house still waits its turn: " + wanted);

        // Set aside (the stores short of it, or no lot yet), the house goes up meanwhile.
        Villages.defer(id, "hall", now + 1000000L, "cannot afford it yet");
        String meanwhile = Villages.nextProject(id);
        helper.assertTrue("house".equals(meanwhile), "with the hall set aside a house goes up meanwhile, got " + meanwhile);

        // Every bed but one taken: a house first, whatever the age wants.
        UUID full = UUID.randomUUID();
        Villages.restore(level, full, heart.offset(400, 0, 0), Villages.Age.STONE, built, room - 1);
        String crowded = Villages.nextProject(full);
        Kit.log("ag01 " + (room - 1) + " folk in room for " + room + ": next " + crowded);
        helper.assertTrue("house".equals(crowded), "a village with every bed but one taken builds a house first, got " + crowded);

        // The hall goes up: room for six more, nothing left on the list, and the Iron Age.
        Villages.noteProject(id, "hall", now);
        List<Villages.Need> after = Villages.needs(level, id);
        Kit.log("ag01 the hall stands: room for " + Villages.housing(id) + ", needs " + after + ", " + Villages.ageOf(id));
        helper.assertTrue(Villages.ageOf(id) == Villages.Age.IRON,
            "with its hall up and everything else in hand the village comes into the Iron Age, got " + Villages.ageOf(id)
                + " short of " + after);
        helper.succeed();
    }

    // ============================================================ paying for it on a mountain

    /**
     * The meeting hall on a lot at the foot of a bank (built up to its floor: eight under every
     * column but the bank's), in a village of forty whose stores are where a mountain town's are:
     * a few stacks at the heart, timber in a chest a hundred and forty blocks out, stone in one
     * forty-five above. The plan counts all of it; so do the builders now, so the hall can be paid
     * for. It wants more than one pack can carry — three parts in four of it never fit — and the
     * lead sets off with a full pack: the chests and the bench first, then the drawing's timber and
     * stone. Another hand that comes by while it holds the post does not judge the building.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ag02_hall_paid_for_on_a_slope")
    public static void ag02_hall_paid_for_on_a_slope(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        final int x = 150400, z = 40000, far = 140;
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        Kit.hold(level, x + far, z, 8);
        Kit.prepare(level, x + far, z, 8);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity lead = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(lead != null, "a village");
        UUID village = lead.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.ageForTests(village, Villages.Age.STONE);
            Villages.restore(level, village, heart, Villages.Age.STONE, List.of(), 40);   // forty on the roll
            emptyStores(level, village);
            plainPack(lead);
            int reach = Villages.storesRadius(village);
            helper.assertTrue(reach > far + 8, "a village of forty counts its stores " + reach + " out, past " + far);

            // The lot: thirty north of the heart, its floor eight up, level with the top of a bank along
            // its back; everywhere else the ground is eight below the floor. The ground is made level
            // first (stone up to the heart's height, air above), whatever the world put there.
            int base = heart.getY();
            for (int dx = -7; dx <= 7; dx++) {
                for (int dz = -11; dz <= 11; dz++) {
                    int cx = x + dx, cz = z - 30 + dz;
                    int top = Kit.surface(level, cx, cz).getY();
                    for (int y = Math.min(top, base) - 1; y < base; y++) level.setBlock(new BlockPos(cx, y, cz), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = base; y < Math.max(top, base) + 24; y++) level.setBlock(new BlockPos(cx, y, cz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            for (int dx = -7; dx <= 7; dx++) {
                for (int dy = 0; dy < 8; dy++) level.setBlock(new BlockPos(x + dx, base + dy, z - 30 - 8),
                    Blocks.STONE.defaultBlockState(), 3);
            }
            BlockPos anchor = new BlockPos(x, base + 8, z - 30);
            Villages.Site site = new Villages.Site(anchor, Direction.NORTH, 0);
            int[] g = BuildGoal.footprint("hall");
            int fill = BuildGoal.fillCells(level, anchor, Direction.NORTH, g[0], g[1]).size();
            int toLay = lead.blocksToLayForTests("hall", site);
            int least = toLay * 3 / 4;
            // The hall's own fixtures and finishing (two chests, a bench, the fences, the doors), a
            // loaf, a pick and an axe take seven slots: the rest is all a pack can carry of blocks.
            int packBlocks = (AssistantEntity.INVENTORY_SIZE - 7) * 64;
            Kit.log("ag02 the hall on the slope: fill " + fill + ", " + toLay + " blocks to lay, three in four " + least
                + ", a pack carries at most about " + packBlocks);
            helper.assertTrue(fill >= 1000, "the lot is built up eight under all but its back row: " + fill);

            // The stores, spread the way a mountain town's are.
            BlockPos atHeart = heart.offset(2, 0, 2);
            chestAt(level, atHeart, stacks(Items.COBBLESTONE, 6, new ItemStack(Items.CHEST, 2),
                new ItemStack(Items.CRAFTING_TABLE, 1), new ItemStack(Items.OAK_FENCE, 8)));
            chestAt(level, Kit.surface(level, x + far, z), stacks(Items.OAK_PLANKS, 10));
            chestAt(level, heart.offset(3, 45, -3), stacks(Items.COBBLESTONE, 10));
            Villages.forgetStock();
            int plan = Villages.stock(level, heart, Villages.Task.STONE, reach) + Villages.stock(level, heart, Villages.Task.LOGS, reach);
            int old = heldWithin(level, heart, Math.min(112, reach), 32, BuildGoal::isBuildingBlock);
            boolean before = ZoneChests.askAs(true);
            int sees;
            boolean affords;
            try {
                sees = lead.buildersSeeForTests(BuildGoal::isBuildingBlock);
                affords = lead.affordsForTests("hall", site);
            } finally {
                ZoneChests.askAs(before);
            }
            Kit.log("ag02 the plan counts " + plan + " stone and timber; the builders saw " + old + " (a hundred and twelve out,"
                + " thirty-two up and down) and now see " + sees + "; the hall wants " + least + " to begin: affords " + affords);
            helper.assertTrue(old < least, "the builders' old look at the stores could not pay for the hall: " + old + " < " + least);
            helper.assertTrue(sees >= plan && plan >= least, "the builders see what the plan counts (" + sees + " of " + plan + ")");
            helper.assertTrue(affords, "and the village can afford its hall");

            // The lead stocks up for it: the chests and the bench, then as much timber and stone as a pack holds.
            helper.assertTrue(Villages.isLead(village, lead.getUUID(), level.getGameTime()), "the first to ask leads");
            VillageFolkEntity passer = VillageFolkSpawnerBlock.raise(level, heart.offset(1, 0, 1), 0.0F);
            helper.assertTrue(passer != null && village.equals(passer.ownerId()), "another hand in the same village");
            helper.assertTrue(Villages.ledByAnother(village, passer.getUUID(), level.getGameTime())
                    && !Villages.ledByAnother(village, lead.getUUID(), level.getGameTime()),
                "a hand passing while another leads leaves the building to the lead");
            before = ZoneChests.askAs(true);
            boolean stocked;
            try {
                stocked = lead.stockedForTests("hall", site);
            } finally {
                ZoneChests.askAs(before);
            }
            int blocks = lead.countCarried(BuildGoal::isBuildingBlock);
            // (The roof's stairs and slabs are laid in place of blocks, and count as blocks to begin with.)
            int load = blocks + lead.countCarried(s -> (s.is(net.minecraft.tags.ItemTags.STAIRS) || s.is(net.minecraft.tags.ItemTags.SLABS))
                && !BuildGoal.isBuildingBlock(s));
            int chests = lead.countCarried(s -> s.is(Items.CHEST));
            int tables = lead.countCarried(s -> s.is(Items.CRAFTING_TABLE));
            int fences = lead.countCarried(s -> s.is(Items.OAK_FENCE));
            int free = 0;
            for (ItemStack st : lead.getInventoryItems()) if (st.isEmpty()) free++;
            Kit.log("ag02 stocked " + stocked + ": a load of " + load + " (" + blocks + " blocks), " + chests + " chests, " + tables
                + " bench, " + fences + " fences, " + free + " slots free; three in four is " + least + " — " + lead.debugLine());
            helper.assertTrue(chests >= 2 && tables >= 1 && fences >= 7, "the hall's chests, bench and fences are in the pack");
            helper.assertTrue(free == 0 && blocks >= 6 * 64, "the pack is full of timber and stone: " + blocks);
            helper.assertTrue(load < least, "three parts in four of the hall here is more than the full pack holds: " + load
                + " of " + least);
            helper.assertTrue(stocked, "a full pack is a load to begin on: the hall goes up a pack at a time");
            helper.succeed();
        });
    }

    // ============================================================ the coal the age wants

    /**
     * A Stone Age village with ten coal in its stores, short of the forty-eight the age asks for. Its
     * smelter, with raw iron, twenty coal and sixteen logs in its pack, keeps none of the coal back
     * from the stores and smelts the iron on the logs: not a lump of coal goes on the fire, and the
     * coal the village has (its stores, the smelter's pack, the furnace) never falls. Once the stores
     * hold what the age wants, a smelter keeps coal as fuel again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "ag03_coal_kept_for_the_age")
    public static void ag03_coal_kept_for_the_age(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        final int x = 150900, z = 40000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        UUID village = f.ownerId();
        Villages.ageForTests(village, Villages.Age.STONE);
        emptyStores(level, village);
        chestAt(level, Kit.surface(level, x + 3, z + 1), new ItemStack(Items.COAL, 10));
        Villages.forgetStock();
        f.setJob(StationTask.SMELT);
        // The furnace, and the smelter's ground round it.
        BlockPos forge = Kit.surface(level, x - 4, z);
        level.setBlock(forge, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(forge, Blocks.FURNACE.defaultBlockState(), 3);
        ZoneChests.mark(level, forge);
        f.assignPlot(WorkZone.around(forge, 4, WorkZone.DEFAULT_DEPTH), "The Forge");
        f.moveTo(forge.getX() + 1.5, forge.getY(), forge.getZ() + 0.5, 0.0F, 0.0F);
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.BREAD, 8));
        f.insertItem(new ItemStack(Items.RAW_IRON, 8));
        f.insertItem(new ItemStack(Items.COAL, 20));
        f.insertItem(new ItemStack(Items.OAK_LOG, 16));

        Predicate<ItemStack> coal = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
        boolean saving = f.savingCoal();
        int stashable = f.countStashable(s -> s.is(Items.COAL));
        Kit.log("ag03 the village short of coal: saving " + saving + "; of the smelter's 20 coal " + stashable + " is the stores'");
        helper.assertTrue(saving, "ten coal is short of what the Stone Age asks: the village puts its coal by");
        helper.assertTrue(stashable == 20, "the smelter keeps none of the coal back from the stores: " + stashable);
        helper.assertTrue(!SmeltGoal.burns(new ItemStack(Items.COAL), true) && !SmeltGoal.burns(new ItemStack(Items.CHARCOAL), true)
                && !SmeltGoal.burns(new ItemStack(Items.COAL_BLOCK), true)
                && SmeltGoal.burns(new ItemStack(Items.OAK_LOG), true) && SmeltGoal.burns(new ItemStack(Items.OAK_PLANKS), true),
            "saving coal, the furnaces burn wood and never coal or charcoal");
        helper.assertTrue(SmeltGoal.burns(new ItemStack(Items.COAL), false), "and burn coal again when the stores have it");

        final int start = 30;                                           // twenty carried, ten in the stores
        f.enqueue(Job.smelt("iron", 8));
        final int[] seen = { Integer.MAX_VALUE, 0 };                    // the least coal the village held; coal seen on the fire
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);
            if (t % 10 != 0) return;
            // The coal the village holds: the smelter's pack, the stores, and whatever sits in the
            // furnace unburnt (its fuel slot, and charcoal come out of it).
            int held = f.countCarried(coal) + inContainers(level, x, z, 40, coal);
            int ingots = f.countCarried(s -> s.is(Items.IRON_INGOT)) + inContainers(level, x, z, 40, s -> s.is(Items.IRON_INGOT));
            if (level.getBlockEntity(forge) instanceof AbstractFurnaceBlockEntity fb && coal.test(fb.getItem(1))) seen[1]++;
            seen[0] = Math.min(seen[0], held);
            if (t % 200 == 0) Kit.log("ag03 @" + t + ": coal held " + held + ", ingots " + ingots + " — " + f.debugLine());
            if (seen[1] > 0 || seen[0] < start) {
                helper.fail("coal went on the fire while the age was short of it: least held " + seen[0] + " of " + start
                    + ", coal seen in the fuel slot " + seen[1] + " times — " + f.debugLine());
                return;
            }
            if (ingots >= 1) {
                Kit.log("ag03 iron smelted on wood at " + t + "; the village still holds " + held + " coal");
                // The stores fill with what the age wants: a smelter keeps coal as its fuel again.
                chestAt(level, Kit.surface(level, x + 3, z - 2), new ItemStack(Items.COAL, 64));
                Villages.forgetStock();
                VillageFolkEntity next = VillageFolkSpawnerBlock.raise(level, heart.offset(-1, 0, 1), 0.0F);
                helper.assertTrue(next != null && village.equals(next.ownerId()), "a second smelter in the village");
                next.setJob(StationTask.SMELT);
                next.getInventoryItems().clear();
                next.insertItem(new ItemStack(Items.COAL, 20));
                boolean stillSaving = next.savingCoal();
                int kept = 20 - next.countStashable(s -> s.is(Items.COAL));
                Kit.log("ag03 with " + (held + 64) + " coal about the village: saving " + stillSaving + ", the smelter keeps " + kept);
                helper.assertTrue(!stillSaving && kept == 20, "with the coal the age wants in the stores, a smelter keeps its fuel");
                helper.succeed();
            } else if (t >= 3000) {
                helper.fail("no iron smelted on wood: coal held " + seen[0] + ", ingots " + ingots + " — "
                    + f.smeltStateForTests() + " — " + f.debugLine());
            }
        });
    }
}
