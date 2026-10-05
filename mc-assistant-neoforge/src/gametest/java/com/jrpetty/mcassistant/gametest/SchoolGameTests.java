package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.School;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The village school (entity/School): a schoolhouse planned once a Stone Age village of ten has three
 * children, and built by a builder like any other building, its blackboard and the lectern's book then
 * put up out of the stores; a child at its desk in a working morning, with the teacher at the lectern,
 * learning the trade it leans to; and a child that went to school growing up into that trade above level
 * nought, where one that did not starts at nought as before.
 *
 * <p>Each runs on its own ground in the band x 250000 to 257000, z 50000, in a batch of its own, with
 * generous timeouts and everything it sees logged.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SchoolGameTests {

    private static final String EMPTY = "empty";

    /** Flat grass round here, clear air above it (whatever the world put there). Returns the ground's top. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A marked store chest at exactly this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    /** A child of the village, a day old (of school age). */
    private static VillageFolkEntity child(GameTestHelper helper, ServerLevel level, BlockPos at, UUID village) {
        VillageFolkEntity kid = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        helper.assertTrue(kid != null && village.equals(kid.ownerId()), "a child of the village");
        kid.setChild(true);
        kid.bornDaysAgo(1);
        return kid;
    }

    /** The first working morning (not the day of rest) on or after this day. */
    private static long workingDay(UUID village, long from) {
        long d = from;
        while (RestDay.today(village, d)) d++;
        return d;
    }

    // ============================================================ planned, and built

    /**
     * A Stone Age village of twelve with no children wants no school; with three children it does, and the
     * plan finds it a lot facing the square. A builder raises it there from what it carries, like any other
     * building: the lectern, the benches (stairs) and the desks (slabs) are in it. Then the teacher's work
     * before a lesson, out of the stores: the blackboard of six black wool, and a book on the lectern; the
     * stores the poorer by them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 14000, batch = "sc01_school_built")
    public static void sc01_school_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        final int x = 250000, z = 50000;
        Kit.hold(level, x, z, 56);
        Kit.prepare(level, x, z, 56);
        BlockPos heart = flat(level, x, z, 50);
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "a village");
        UUID id = builder.ownerId();
        final Villages.Site[] site = new Villages.Site[1];
        final int[] stores = new int[2];                          // black wool and books in the stores at the start
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            Villages.restore(level, id, heart, Villages.Age.STONE, List.of(), 12);
            List<String> without = Villages.projectsWanted(id);
            Kit.log("sc01 no children: wanted " + without);
            helper.assertFalse(without.contains("school"), "no children, no school: " + without);
            for (int i = 0; i < 3; i++) child(helper, level, heart.offset(3 + i, 0, 3), id);
            List<String> wanted = Villages.projectsWanted(id);
            Kit.log("sc01 three children: wanted " + wanted + "; why: " + Villages.whyBuild(id, "school"));
            helper.assertTrue(wanted.contains("school"), "three children and twelve folk: a school is wanted, got " + wanted);
            site[0] = Villages.siteFor(level, id, "school");
            helper.assertTrue(site[0] != null, "the plan finds the school a lot");
            Kit.log("sc01 the school's lot: " + site[0]);
            // The stores, for the teacher's work: books and black wool.
            chestAt(level, heart.offset(-3, 0, 2), new ItemStack(Items.BOOK, 6), new ItemStack(Items.BLACK_WOOL, 8));
            Villages.forgetStock();
            stores[0] = Market.stock(level, id, s -> s.is(Items.BLACK_WOOL));
            stores[1] = Market.stock(level, id, s -> s.is(Items.BOOK));
            // The builder's load: the drawing's timber, stone, roof, glass and furniture.
            builder.getInventoryItems().clear();
            builder.insertItem(new ItemStack(Items.BREAD, 8));
            for (int i = 0; i < 3; i++) builder.insertItem(new ItemStack(Items.OAK_PLANKS, 64));
            builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
            builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
            builder.insertItem(new ItemStack(Items.OAK_SLAB, 32));
            builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
            builder.insertItem(new ItemStack(Items.SPRUCE_LOG, 64));
            builder.insertItem(new ItemStack(Items.GLASS_PANE, 16));
            builder.insertItem(new ItemStack(Items.BOOKSHELF, 4));
            builder.insertItem(new ItemStack(Items.BARREL, 3));
            builder.insertItem(new ItemStack(Items.LANTERN, 2));
            builder.insertItem(new ItemStack(Items.LECTERN, 1));
            builder.insertItem(new ItemStack(Items.OAK_DOOR, 1));
            builder.insertItem(new ItemStack(Items.COBBLESTONE_STAIRS, 2));
            builder.enqueue(Job.buildAt("school", site[0].anchor(), site[0].facing(), site[0].radius()));
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 10 || site[0] == null) return;
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);      // building is day work
            if (t % 1200 == 0) Kit.log("sc01 @" + t + " built=" + Villages.builtList(id) + " — " + builder.debugLine());
            if (!Villages.builtList(id).contains("school")) {
                if (t >= 13800) helper.fail("the school was not built in 13800 ticks: " + builder.debugLine());
                return;
            }
            boolean ledger = false;
            for (Ledger.Building b : Ledger.buildings(id)) if (b.structure().equals("school")) ledger = true;
            helper.assertTrue(ledger && School.stands(id), "the school is in the town's register");
            BlockPos lectern = School.lecternForTests(id);
            List<BlockPos> seats = School.seatsForTests(id);
            int benches = 0, desks = 0;
            Direction back = site[0].facing();
            for (BlockPos s : seats) {
                if (level.getBlockState(s).getBlock() instanceof StairBlock) benches++;
                if (level.getBlockState(s.relative(back)).getBlock() instanceof SlabBlock) desks++;
            }
            Kit.log("sc01 the school stands at tick " + t + ": lectern " + level.getBlockState(lectern) + ", benches " + benches
                + " of " + seats.size() + ", desks " + desks);
            helper.assertTrue(level.getBlockState(lectern).getBlock() instanceof LecternBlock, "the teacher's lectern stands");
            helper.assertTrue(seats.size() == 8 && benches >= 6 && desks >= 6, "two rows of desks with benches: " + benches + " benches, " + desks + " desks");
            // The teacher's work before a lesson, out of the stores.
            int woolBefore = Market.stock(level, id, s -> s.is(Items.BLACK_WOOL));
            int booksBefore = Market.stock(level, id, s -> s.is(Items.BOOK));
            int put = School.fitOutForTests(level, id);
            int board = 0;
            for (BlockPos p : School.boardForTests(id)) if (level.getBlockState(p).is(Blocks.BLACK_WOOL)) board++;
            BlockState lec = level.getBlockState(lectern);
            int woolAfter = Market.stock(level, id, s -> s.is(Items.BLACK_WOOL));
            int booksAfter = Market.stock(level, id, s -> s.is(Items.BOOK));
            Kit.log("sc01 put to rights: " + put + " pieces; board " + board + " of 6; book on the lectern "
                + (lec.getBlock() instanceof LecternBlock && lec.getValue(LecternBlock.HAS_BOOK)) + "; black wool " + woolBefore + " -> " + woolAfter
                + ", books " + booksBefore + " -> " + booksAfter);
            helper.assertTrue(board == 6, "the blackboard is six black wool: " + board);
            helper.assertTrue(lec.getBlock() instanceof LecternBlock && lec.getValue(LecternBlock.HAS_BOOK), "a book on the lectern");
            // (The teacher may have put it up itself a moment before: what counts is the stores against the start.)
            helper.assertTrue(woolAfter == stores[0] - 6, "the board came out of the stores' wool: " + stores[0] + " -> " + woolAfter);
            helper.assertTrue(booksAfter <= stores[1] - 1, "the book came out of the stores: " + stores[1] + " -> " + booksAfter);
            helper.succeed();
        });
    }

    // ============================================================ a lesson

    /**
     * A working morning, a schoolhouse standing, two grown hands with trades and a child of a day old: a
     * teacher is chosen, goes to the lectern and is off its own trade's shift while it teaches; the child
     * goes to its desk, takes a leaning, and every beat of the lesson learns a little of that trade. Its
     * card says so; the Jobs page shows the Teacher, and the teacher is paid for it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "sc02_lesson")
    public static void sc02_lesson(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1600);
        final int x = 251500, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = flat(level, x, z, 34);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        VillageFolkEntity second = VillageFolkSpawnerBlock.raise(level, heart.offset(2, 0, 0), 0.0F);
        helper.assertTrue(second != null && id.equals(second.ownerId()), "a second grown hand");
        BlockPos anchor = new BlockPos(x, heart.getY(), z + 14);
        BuildGoal.stamp(level, "school", anchor, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "school", anchor, Direction.NORTH);
        Villages.ageForTests(id, Villages.Age.STONE);
        Villages.noteProject(id, "school", level.getGameTime());
        final long day = workingDay(id, 8);
        level.setDayTime(day * 24000L + 1600L);
        VillageFolkEntity kid = child(helper, level, heart.offset(-2, 0, 2), id);
        kid.life().setTraitsForTests(Social.Trait.HARDWORKING, Social.Trait.CURIOUS);      // never plays truant
        first.setJob(StationTask.MINE);
        second.setJob(StationTask.FARM);
        first.life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.EASYGOING);
        second.life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.EASYGOING);
        final boolean[] moved = { false };
        final boolean[] sawTeaching = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 20) return;
            // The morning, held: lessons are from half past seven to half past eleven on a working day.
            if (level.getDayTime() % 24000L >= School.LESSON_FROM + 3000L) level.setDayTime(day * 24000L + 1600L);
            VillageFolkEntity teacher = School.teacher(level, id, false);
            if (teacher != null && School.teaching(teacher)) {
                sawTeaching[0] = true;
                helper.assertFalse(teacher.onShift(), "the teacher is off its own trade's shift while it teaches");
            }
            // Robust against a path that will not come right: after a while, set them in the schoolroom (said so).
            if (t == 1400 && !moved[0] && teacher != null && (!School.inClassForTests(kid) || !School.inClassForTests(teacher))) {
                moved[0] = true;
                List<BlockPos> seats = School.seatsForTests(id);
                BlockPos lec = School.lecternForTests(id);
                Kit.log("sc02 not in class by tick 1400 (child at " + kid.blockPosition() + ", teacher at " + teacher.blockPosition()
                    + "): set them in the schoolroom");
                kid.teleportTo(seats.get(0).getX() + 0.5, seats.get(0).getY() + 0.5, seats.get(0).getZ() + 0.5);
                BlockPos spot = lec.relative(Direction.NORTH);
                teacher.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            }
            StationTask leaning = School.leaningOf(kid);
            int xp = leaning == StationTask.NONE ? 0 : kid.xpInTrade(leaning);
            if (t % 200 == 0) {
                Kit.log("sc02 @" + t + " time " + level.getDayTime() % 24000L + ": teacher " + (teacher == null ? "none" : teacher.displayNameCap()
                    + " at " + teacher.blockPosition() + " in class " + School.inClassForTests(teacher) + " teaching " + School.teaching(teacher))
                    + "; child at " + kid.blockPosition() + " in class " + School.inClassForTests(kid) + ", leans to " + leaning + ", xp " + xp
                    + ", mornings " + School.morningsOf(kid) + ", doing " + FolkTalk.nowDoing(kid));
            }
            if (teacher != null && leaning != StationTask.NONE && xp > 0 && School.morningsOf(kid) >= 1) {
                String card = FolkTalk.card(kid);
                String teacherCard = FolkTalk.card(teacher);
                CompoundTag row = School.jobsRow(id);
                int pay = School.pay(teacher);
                Kit.log("sc02 learned at tick " + t + ": " + leaning + " xp " + xp + " (level " + kid.tradeLevel(leaning) + "), the teacher "
                    + teacher.displayNameCap() + " paid " + pay + " (" + Wealth.breakdown(teacher) + "); the child's card: "
                    + card.replace('\n', ' ') + " | the teacher's: " + teacherCard.replace('\n', ' '));
                helper.assertTrue(sawTeaching[0], "the teacher was seen at the lectern");
                helper.assertTrue(card.contains("School|at school: learning to be " + School.a(leaning)),
                    "the child's card says what it is learning: " + card);
                helper.assertTrue(teacherCard.contains("Teaches|"), "the teacher's card says it teaches");
                helper.assertTrue(row != null && "Teacher".equals(row.getString("title")) && row.getInt("hands") == 1,
                    "the Jobs page shows the Teacher: " + row);
                helper.assertTrue(pay > 0 && Wealth.breakdown(teacher).contains("teaching the school"), "the teacher is paid for it");
                helper.succeed();
            } else if (t >= 3400) {
                helper.fail("the child learned nothing in 3400 ticks: leans to " + leaning + ", xp " + xp + ", teacher "
                    + (teacher == null ? "none" : teacher.displayNameCap() + " " + teacher.debugLine()));
            }
        });
    }

    // ============================================================ grown up

    /**
     * Two children; one goes to school (a full schooling's lessons, leaning to the mine), one never does.
     * Grown up, the schooled one takes up mining at the level its lessons made, above nought, and the school's
     * books have it among those who left; the other starts whatever trade it is given at nought, as before.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "sc03_grown_schooled")
    public static void sc03_grown_schooled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 10 + 7000L);
        final int x = 253000, z = 50000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = flat(level, x, z, 28);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        VillageFolkEntity second = VillageFolkSpawnerBlock.raise(level, heart.offset(2, 0, 0), 0.0F);
        helper.assertTrue(second != null && id.equals(second.ownerId()), "a second grown hand");
        BlockPos anchor = new BlockPos(x, heart.getY(), z + 14);
        BuildGoal.stamp(level, "school", anchor, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "school", anchor, Direction.NORTH);
        VillageFolkEntity schooled = child(helper, level, heart.offset(-2, 0, 2), id);
        VillageFolkEntity unschooled = child(helper, level, heart.offset(-3, 0, -2), id);
        helper.runAtTickTime(20, () -> {
            first.setJob(StationTask.FARM);
            second.setJob(StationTask.WOOD);
            first.life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.EASYGOING);
            second.life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.EASYGOING);
            schooled.life().setTraitsForTests(Social.Trait.HARDWORKING, Social.Trait.CURIOUS);
            VillageFolkEntity teacher = School.teacher(level, id, true);
            helper.assertTrue(teacher != null, "a teacher is chosen");
            int cap = School.capLevel(teacher);
            School.leanForTests(schooled, StationTask.MINE);
            School.lessonForTests(schooled, teacher, 70);         // about two full mornings at its desk
            int lv = schooled.tradeLevel(StationTask.MINE);
            Kit.log("sc03 teacher " + teacher.displayNameCap() + " (a schooling to level " + cap + "); the schooled child: mining level "
                + lv + " (" + schooled.xpInTrade(StationTask.MINE) + " xp), " + School.morningsOf(schooled) + " mornings; card: "
                + FolkTalk.card(schooled).replace('\n', ' '));
            helper.assertTrue(lv == cap && cap >= 5 && cap <= 8, "a full schooling: level " + lv + " of " + cap);
            helper.assertTrue(schooled.stationTask() == StationTask.NONE && schooled.isBaby(), "still a child, with no trade yet");
            // Grown up.
            schooled.childhoodForTests(VillageFolkEntity.GROW_DAYS);
            unschooled.childhoodForTests(VillageFolkEntity.GROW_DAYS);
            Kit.log("sc03 grown: the schooled one a " + schooled.stationTask() + " at level " + schooled.veteranLevel()
                + "; the other a " + unschooled.stationTask() + " at level " + unschooled.veteranLevel()
                + " (all its trades: " + unschooled.tradeLevels() + ")");
            helper.assertTrue(!schooled.isBaby() && schooled.stationTask() == StationTask.MINE,
                "it takes up the trade it leaned to at school, got " + schooled.stationTask());
            helper.assertTrue(schooled.veteranLevel() >= 5 && schooled.veteranLevel() == lv,
                "and starts it at the level its lessons made: " + schooled.veteranLevel());
            helper.assertTrue(!unschooled.isBaby() && unschooled.veteranLevel() == 0,
                "a child that never went to school starts at nought: " + unschooled.stationTask() + " " + unschooled.veteranLevel());
            List<String> books = School.lines(level, Villages.get(id));
            Kit.log("sc03 the school's books: " + String.join(" / ", books));
            boolean listed = false;
            for (String l : books) if (l.contains("Left school") && l.contains(schooled.displayNameCap()) && l.contains("miner")) listed = true;
            helper.assertTrue(listed, "the school's books have it among those who left school");
            helper.assertTrue(School.leaningOf(schooled) == StationTask.NONE, "its desk is cleared");
            helper.succeed();
        });
    }

    // ============================================================ what a child leans to

    /**
     * A child leans to its parent's trade when nothing else pulls harder: a farmer's child with no strong
     * nature of its own leans to the fields. And a hardworking child never plays truant, an easygoing,
     * grumpy one sometimes does.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc04_leaning")
    public static void sc04_leaning(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 10 + 2000L);
        final int x = 254500, z = 50000;
        Kit.hold(level, x, z, 24);
        Kit.prepare(level, x, z, 24);
        BlockPos heart = flat(level, x, z, 20);
        VillageFolkEntity parent = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(parent != null, "a village");
        UUID id = parent.ownerId();
        VillageFolkEntity kid = child(helper, level, heart.offset(2, 0, 2), id);
        VillageFolkEntity idle = child(helper, level, heart.offset(-2, 0, 2), id);
        helper.runAtTickTime(20, () -> {
            parent.setJob(StationTask.FARM);
            kid.parentIds().add(parent.getUUID());
            kid.life().setTraitsForTests(Social.Trait.GENEROUS, Social.Trait.HARDWORKING);
            School.lessonForTests(kid, parent, 1);
            StationTask leaning = School.leaningOf(kid);
            Kit.log("sc04 a farmer's generous, hardworking child leans to " + leaning + ": " + FolkTalk.card(kid).replace('\n', ' '));
            helper.assertTrue(leaning == StationTask.FARM, "a farmer's child, generous by nature, leans to the fields: " + leaning);
            idle.life().setTraitsForTests(Social.Trait.EASYGOING, Social.Trait.GRUMPY);
            int truant = 0, never = 0;
            long today = level.getDayTime() / 24000L;
            for (long d = today; d < today + 60; d++) {
                level.setDayTime(d * 24000L + 2000L);
                if (School.truantForTests(idle)) truant++;
                if (School.truantForTests(kid)) never++;
            }
            Kit.log("sc04 truant mornings in sixty: the easygoing, grumpy child " + truant + ", the hardworking one " + never);
            helper.assertTrue(never == 0, "a hardworking child never plays truant");
            helper.assertTrue(truant > 5 && truant < 50, "an easygoing, grumpy child sometimes does: " + truant + " of 60");
            helper.succeed();
        });
    }
}
