package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Crier;
import com.jrpetty.mcassistant.entity.Gazette;
import com.jrpetty.mcassistant.entity.Greetings;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.NightLight;
import com.jrpetty.mcassistant.entity.Orders;
import com.jrpetty.mcassistant.entity.Seats;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WelcomeSign;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Town life: the town crier at noon, the gazette on the meeting hall's lectern, the welcome sign at
 * the edge of town, folk sitting down on their breaks, a wave and a hello by name (and a child tagging
 * along), and a light in the hand after dark.
 *
 * <ul>
 * <li><b>tl01</b>: at noon in a town of five with no bell rung, a crier is chosen (not the leader) and
 *     reads out the news on the square, a line every few seconds, yesterday's birth among it.</li>
 * <li><b>tl02</b>: a meeting hall: a lectern out of the stores by the elder's chair; no book in the
 *     stores, no gazette; three paper and a leather, and the gazette is laid on it with yesterday's
 *     birth, death and building, the elder's order and the prices; the next morning the same book is
 *     written up again with the new day's news.</li>
 * <li><b>tl03</b>: the welcome sign goes up at the edge of town beside the main road, out of four of the
 *     stores' planks, with the town's name, population and age; written up again the next day with
 *     the new population, and no more planks.</li>
 * <li><b>tl04</b>: on its break a folk sits on a bench near it, stays sat, and stands up when its break is over.</li>
 * <li><b>tl05</b>: a folk waves to a player it knows, by name, once a day (and not to a stranger); a child
 *     who likes the player tags along after them for half a minute, then goes back to its day.</li>
 * <li><b>tl06</b>: after dark, out of doors, a folk holds the lantern out of its pack (the best light it
 *     has); indoors and at daybreak it is back in the pack; a folk with no light holds none.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 420,000 to 426,000, z 50,000, in a batch of its own,
 * calling the town's logic directly where it can and logging what it sees.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownLifeGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    /** Flat grass round here, clear air above it (whatever the world put there). Returns the ground's top. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        return flatAt(level, cx, cz, r, Kit.surface(level, cx, cz).getY());
    }

    private static BlockPos flatAt(ServerLevel level, int cx, int cz, int r, int y) {
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

    private static void put(Container box, ItemStack s) {
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).isEmpty()) {
                box.setItem(i, s);
                box.setChanged();
                return;
            }
        }
    }

    private static double flatDistance(VillageFolkEntity f, BlockPos p) {
        double dx = f.getX() - (p.getX() + 0.5), dz = f.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ============================================================ tl01: the town crier

    /**
     * Noon in a town of five that rang no bell today (no dawn bell, so no noon bell either): by the clock
     * a crier is chosen — never the leader — walks to the square and reads out the news, a line every few
     * seconds, beginning with the call, with yesterday's birth among the lines.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2600, batch = "tl01_crier")
    public static void tl01_crier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 420000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 20);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 6050L);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + i * 3, 0, 7), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        Villages.tell(village, day - 1, "Ada and Bert had a child, Cora");
        Kit.log("tl01 a town of " + folk.size() + " at " + heart.toShortString() + ", day " + day + ", " + level.getDayTime() % 24000L);
        boolean[] seen = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            UUID crier = Crier.crier(village);
            List<String> read = Crier.readForTests(village);
            if (t % 200 == 0) {
                Kit.log("tl01 tick " + t + " time " + level.getDayTime() % 24000L + ": crier " + crier + ", read " + read.size()
                    + " of " + Crier.linesForTests(village).size());
                for (VillageFolkEntity f : folk) Kit.log("   " + f.displayNameCap() + " at " + f.blockPosition().toShortString()
                    + " elder=" + f.isElder() + " doing=" + f.hobbyNow() + " | " + f.debugLine());
            }
            if (crier != null && !seen[0]) {
                seen[0] = true;
                Kit.log("tl01 the crier is chosen at tick " + t + "; it will read: " + Crier.linesForTests(village));
            }
            if (read.size() < 3) {
                if (t > 2400) helper.fail("tl01 no news cried by tick " + t + ": crier " + crier + ", read " + read);
                return;
            }
            VillageFolkEntity f = null;
            for (VillageFolkEntity o : folk) if (o.getUUID().equals(crier)) f = o;
            List<String> lines = Crier.linesForTests(village);
            BlockPos stand = Crier.standForTests(village);
            double far = f == null || stand == null ? 99 : flatDistance(f, stand);
            Kit.log("tl01 read so far at tick " + t + ": " + read + "; the crier " + (f == null ? "?" : f.displayNameCap())
                + " stands " + String.format("%.1f", far) + " from " + stand);
            helper.assertTrue(f != null && !f.isBaby(), "the crier is a grown folk of the town");
            helper.assertFalse(f.isElder(), "the leader does not cry the news itself");
            helper.assertTrue(far <= 6.5, "the crier reads on the square: " + String.format("%.1f", far) + " blocks from it");
            helper.assertTrue(read.equals(lines.subList(0, read.size())), "the lines are read in order: " + read + " of " + lines);
            helper.assertTrue(read.get(0).contains(Villages.name(village)), "it opens with the call and the town's name: " + read.get(0));
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("Cora")), "yesterday's birth is in the news: " + lines);
            helper.assertTrue("crying the news on the square".equals(f.hobbyNow()), "its card says it is crying the news: " + f.hobbyNow());
            helper.succeed();
        });
    }

    // ============================================================ tl02: the gazette

    /**
     * A meeting hall stamped where the town has it in its books, a lectern in the stores and no book.
     * The gazette's lectern goes up by the elder's chair; with no book there is no gazette. Three paper
     * and a leather put in, the gazette is made of them and laid on the lectern: yesterday's birth, death
     * and building, the elder's order, the prices. The next morning the same book is written up again
     * with that day's news, and no more paper is used.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "tl02_gazette")
    public static void tl02_gazette(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 420600;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 2000L);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        for (int i = 0; i < 3; i++) VillageFolkSpawnerBlock.raise(level, heart.offset(-4 + i * 3, 0, -5), 0.0F);
        BlockPos anchor = heart.offset(0, 0, 16);
        BuildGoal.stamp(level, "hall", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "hall", anchor, Direction.NORTH);
        Container box = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.LECTERN));
        Villages.tell(id, day - 1, "Ada and Bert had a child, Cora");
        Villages.tell(id, day - 1, "Old Tom died, aged 80, of old age");
        Villages.tell(id, day - 1, "the meeting hall went up");
        Ledger.note(id, "order", "DIG|" + (day - 1) + "|Ada");
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            helper.assertTrue(v != null, "the village");
            BlockPos at = Gazette.lecternForTests(level, id);
            helper.assertTrue(at != null, "the hall has a place for the gazette's lectern");
            Kit.log("tl02 the hall at " + anchor.toShortString() + ", the gazette's lectern at " + at.toShortString());
            // No book: the lectern goes up, but no gazette.
            String a = Gazette.writeForTests(level, v);
            BlockState st = level.getBlockState(at);
            Kit.log("tl02 with no book: " + a + "; at the spot " + st);
            helper.assertTrue(st.getBlock() instanceof LecternBlock, "a lectern set up by the elder's chair: " + st);
            helper.assertFalse(st.getValue(LecternBlock.HAS_BOOK), "no book in the stores, no gazette");
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.LECTERN)) == 0, "the lectern came out of the stores");
            // Paper and leather: the gazette.
            put(box, new ItemStack(Items.PAPER, 3));
            put(box, new ItemStack(Items.LEATHER, 1));
            String b = Gazette.writeForTests(level, v);
            st = level.getBlockState(at);
            ItemStack book = level.getBlockEntity(at) instanceof LecternBlockEntity le ? le.getBook() : ItemStack.EMPTY;
            String text = Gazette.textForTests(book);
            Kit.log("tl02 with paper and leather: " + b + "; the gazette reads: " + text.replace('\n', '/'));
            helper.assertTrue(st.getValue(LecternBlock.HAS_BOOK) && Gazette.isGazette(book), "the gazette lies on the lectern: " + book);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.PAPER)) == 0 && Market.stock(level, id, s -> s.is(Items.LEATHER)) == 0,
                "made of the stores' three paper and leather");
            helper.assertTrue(text.contains("Gazette") && text.contains(Villages.name(id)), "its masthead names the town");
            helper.assertTrue(text.contains("Cora"), "yesterday's birth");
            helper.assertTrue(text.contains("Old Tom"), "yesterday's death");
            helper.assertTrue(text.contains("meeting hall went up"), "yesterday's building");
            Orders.Order o = Orders.current(id);
            helper.assertTrue(o == null ? text.contains("None given") : text.contains(o.title), "the elder's order (" + o + ")");
            helper.assertTrue(text.matches("(?s).*Prices.*\\d+c.*"), "the market's prices");
            // The next morning: written up again on the same book.
            level.setDayTime((day + 1) * 24000L + 2000L);
            Villages.tell(id, day, "Old Meg died peacefully in their sleep, aged 91");
            put(box, new ItemStack(Items.PAPER, 3));
            put(box, new ItemStack(Items.LEATHER, 1));
            String c = Gazette.writeForTests(level, v);
            ItemStack next = level.getBlockEntity(at) instanceof LecternBlockEntity le2 ? le2.getBook() : ItemStack.EMPTY;
            String again = Gazette.textForTests(next);
            Kit.log("tl02 the next morning: " + c + "; it reads: " + again.replace('\n', '/'));
            helper.assertTrue(Gazette.isGazette(next) && again.contains("Old Meg") && !again.contains("Cora"), "the new day's news, yesterday's gone");
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.PAPER)) == 3, "the same book written up again: no more paper used");
            helper.succeed();
        });
    }

    // ============================================================ tl03: the welcome sign

    /**
     * A town of five: the welcome sign goes up at the edge of town beside the main road, out of four of
     * the stores' planks, its face reading the town's name, population and age. A folk more and a day on,
     * it is written up again with the new population, out of nothing more.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "tl03_welcome_sign")
    public static void tl03_welcome_sign(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 421200;
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        BlockPos heart = flat(level, x, Z, 8);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 2000L);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        for (int i = 0; i < 4; i++) VillageFolkSpawnerBlock.raise(level, heart.offset(-4 + i * 2, 0, 4), 0.0F);
        // Its fields to the north (no roads, no neighbours): the main road out is the avenue going south.
        Villages.setFieldsSide(id, TownPlan.NORTH);
        BlockPos[] post = { null };
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            helper.assertTrue(v != null, "the village");
            BlockPos col = WelcomeSign.columnForTests(level, v);
            flatAt(level, col.getX(), col.getZ(), 3, heart.getY());
            chestAt(level, heart.offset(-3, 0, -3), new ItemStack(Items.OAK_PLANKS, 16));   // planks the builders have not had first
            int before = Market.stock(level, id, s -> s.is(ItemTags.PLANKS));
            String did = WelcomeSign.putForTests(level, v);
            int after = Market.stock(level, id, s -> s.is(ItemTags.PLANKS));
            Direction out = WelcomeSign.wayForTests(level, v);
            Kit.log("tl03 the way out " + out + ", the column " + col.toShortString() + ": " + did + "; planks " + before + " -> " + after);
            helper.assertTrue(did != null && did.startsWith("put up"), "the welcome sign goes up: " + did);
            BlockPos p = new BlockPos(col.getX(), heart.getY(), col.getZ());
            helper.assertTrue(level.getBlockState(p).getBlock() instanceof FenceBlock, "a post at the edge of town: " + level.getBlockState(p));
            helper.assertTrue(level.getBlockState(p.above()).getBlock() instanceof StandingSignBlock, "the sign on it: " + level.getBlockState(p.above()));
            int along = Math.abs(out.getAxis() == Direction.Axis.X ? p.getX() - heart.getX() : p.getZ() - heart.getZ());
            int across = Math.abs(out.getAxis() == Direction.Axis.X ? p.getZ() - heart.getZ() : p.getX() - heart.getX());
            helper.assertTrue(along == Villages.townReach(id) && across == 3, "beside the main road at the town's edge: "
                + along + " out (the edge is " + Villages.townReach(id) + "), " + across + " across");
            helper.assertTrue(before - after == 4, "the post and the sign out of four of the stores' planks: " + (before - after));
            SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(p.above());
            String[] face = new String[4];
            for (int i = 0; i < 4; i++) face[i] = sign.getFrontText().getMessage(i, false).getString();
            Kit.log("tl03 the sign reads: " + String.join(" / ", face) + " (headcount " + Villages.headcount(id) + ")");
            helper.assertTrue(face[0].equals("Welcome to") && face[1].equals(Villages.name(id)), "its name: " + String.join("/", face));
            helper.assertTrue(face[2].equals("Population " + Villages.headcount(id)), "its population: " + face[2]);
            helper.assertTrue(face[3].toLowerCase().contains("age"), "its age: " + face[3]);
            helper.assertTrue(out == Direction.SOUTH, "out along the avenue away from its fields: " + out);
            post[0] = p;
            helper.assertTrue(VillageFolkSpawnerBlock.raise(level, heart.offset(3, 0, -3), 0.0F) != null, "one folk more");
        });
        helper.runAtTickTime(30, () -> {
            Villages.Village v = Villages.get(id);
            level.setDayTime((day + 1) * 24000L + 2000L);
            int before = Market.stock(level, id, s -> s.is(ItemTags.PLANKS));
            String did = WelcomeSign.putForTests(level, v);
            int now = Market.stock(level, id, s -> s.is(ItemTags.PLANKS));
            SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(post[0].above());
            String pop = sign.getFrontText().getMessage(2, false).getString();
            Kit.log("tl03 the next day: " + did + "; the sign's population " + pop + " (headcount " + Villages.headcount(id) + "), planks "
                + before + " -> " + now + "; its back: " + sign.getBackText().getMessage(2, false).getString());
            helper.assertTrue("written up".equals(did), "the same sign written up: " + did);
            helper.assertTrue(pop.equals("Population " + Villages.headcount(id)) && Villages.headcount(id) == 6, "today's population: " + pop);
            helper.assertTrue(now == before, "no more planks for writing it up: " + before + " -> " + now);
            helper.succeed();
        });
    }

    // ============================================================ tl04: sitting down

    /**
     * A folk on its break with a bench (two stairs) beside it: it sits down on it, lowered onto the seat
     * and facing out from its back, and stays sat; when its break is over it stands up.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "tl04_sitting")
    public static void tl04_sitting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 421800;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos heart = flat(level, x, Z, 16);
        long base = (level.getDayTime() / 24000L + 2) * 24000L;
        level.setDayTime(base + 1000L);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a folk");
        f.setJob(StationTask.WOOD);
        BlockPos seat = heart.offset(4, 0, 3);
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH).setValue(StairBlock.HALF, Half.BOTTOM);
        level.setBlock(seat, stair, 3);
        level.setBlock(seat.east(), stair, 3);
        long[] breakAt = { -1 }, afterAt = { -1 };
        int[] phase = { 0 };
        long[] mark = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 10) return;
            switch (phase[0]) {
                case 0 -> {
                    // Its break: the hour is its own (from its id), so look for it on the clock.
                    for (long tod = 1000L; tod < 11000L && breakAt[0] < 0; tod += 25L) {
                        level.setDayTime(base + tod);
                        if (f.breakNowForTests()) breakAt[0] = tod + 100L;
                    }
                    helper.assertTrue(breakAt[0] > 0, "the folk has a break in the day");
                    for (long tod = breakAt[0]; tod < 23000L && afterAt[0] < 0; tod += 25L) {
                        level.setDayTime(base + tod);
                        if (!f.breakNowForTests()) afterAt[0] = tod + 50L;
                    }
                    helper.assertTrue(afterAt[0] > 0, "its break ends");
                    level.setDayTime(base + breakAt[0]);
                    f.clearQueue();
                    f.teleportTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 1.5);      // just in front of the bench
                    boolean going = Seats.restForTests(f);
                    Kit.log("tl04 its break at " + breakAt[0] + "; on it: " + f.breakNowForTests() + "; going to sit: " + going
                        + " on " + Seats.seatForTests(f));
                    helper.assertTrue(going && (seat.equals(Seats.seatForTests(f)) || seat.east().equals(Seats.seatForTests(f))),
                        "on its break it goes to sit on the bench beside it: " + Seats.seatForTests(f));
                    mark[0] = t;
                    phase[0] = 1;
                }
                case 1 -> {
                    level.setDayTime(base + breakAt[0]);              // its break, held
                    clearJobs(f, t);
                    if (!(f.getPose() == Pose.SITTING && Seats.seated(f))) {
                        if (t - mark[0] > 300) helper.fail("tl04 not sat down by tick " + t + ": pose " + f.getPose() + " at "
                            + f.blockPosition().toShortString() + ", doing " + f.hobbyNow() + " | " + f.debugLine());
                        return;
                    }
                    Kit.log("tl04 sat down at tick " + t + " at " + f.position() + ", facing " + f.getYRot() + ", doing " + f.hobbyNow());
                    mark[0] = t;
                    phase[0] = 2;
                }
                case 2 -> {
                    level.setDayTime(base + breakAt[0]);
                    clearJobs(f, t);
                    BlockPos on = Seats.seatForTests(f);
                    boolean still = f.getPose() == Pose.SITTING && on != null && f.blockPosition().equals(on);
                    if (!still) helper.fail("tl04 stood up during its break at tick " + t + ": pose " + f.getPose() + " at "
                        + f.blockPosition().toShortString() + " | " + f.debugLine());
                    if (t - mark[0] < 80) return;
                    helper.assertTrue(f.hobbyNow() != null && f.hobbyNow().startsWith("sitting on a bench"), "its card: " + f.hobbyNow());
                    // Its break over: back to work.
                    level.setDayTime(base + afterAt[0]);
                    helper.assertFalse(f.breakNowForTests(), "the clock set past its break");
                    mark[0] = t;
                    phase[0] = 3;
                }
                case 3 -> {
                    level.setDayTime(base + afterAt[0]);
                    if (f.getPose() == Pose.SITTING || Seats.seated(f)) {
                        if (t - mark[0] > 100) helper.fail("tl04 still sat down after its break, tick " + t);
                        return;
                    }
                    Kit.log("tl04 up again at tick " + t + " (" + (t - mark[0]) + " ticks after its break), pose " + f.getPose());
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    /**
     * A job the agenda queued for it on its break (a tool to fetch from the stores, say) let go: the test
     * is of the seat, not of what the town has it fetch, and a job's walk would have it up off the seat.
     */
    private static void clearJobs(VillageFolkEntity f, long t) {
        if (f.peekJob() == null) return;
        Kit.log("tl04 tick " + t + ": a job queued on its break let go (" + f.peekJob() + ")");
        f.clearQueue();
    }

    // ============================================================ tl05: a wave, and a child tagging along

    /**
     * A folk who has never met the player gives no wave; one who knows them waves (its arm swings) and
     * says hello by name, once a day; the next day again. A child who likes the player tags along after
     * them for half a minute — it closes the distance, its card says so — and then lets them go.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "tl05_greeting")
    public static void tl05_greeting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 422400;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos heart = flat(level, x, Z, 18);
        long base = (level.getDayTime() / 24000L + 2) * 24000L;
        level.setDayTime(base + 2000L);
        VillageFolkEntity grown = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity kid = VillageFolkSpawnerBlock.raise(level, heart.offset(-6, 0, 6), 0.0F);
        helper.assertTrue(grown != null && kid != null, "a folk and a child");
        kid.setChild(true);
        kid.bornDaysAgo(1);
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        String name = you.getName().getString();
        double[] start = { 0 };
        long[] mark = { 0 };
        int[] phase = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 10) return;
            switch (phase[0]) {
                case 0 -> {
                    grown.ensurePersona();
                    kid.ensurePersona();
                    you.setPos(grown.getX() + 2.0, grown.getY(), grown.getZ());
                    boolean stranger = Greetings.waveForTests(grown, you);
                    helper.assertFalse(stranger, "no wave for a player it has never met");
                    grown.persona().feelFor(you.getUUID(), name, 30);
                    boolean waved = Greetings.waveForTests(grown, you);
                    Kit.log("tl05 waved to a stranger " + stranger + "; to a player it knows " + waved + " (swinging " + grown.swinging + ")");
                    helper.assertTrue(waved && grown.swinging, "a wave (its arm up) for a player it knows");
                    helper.assertFalse(Greetings.waveForTests(grown, you), "once a day");
                    level.setDayTime(base + 24000L + 2000L);
                    helper.assertTrue(Greetings.waveForTests(grown, you), "and again the next day");
                    // The child: likes the player, who is a little way off.
                    kid.persona().feelFor(you.getUUID(), name, 40);
                    you.setPos(kid.getX() + 9.0, kid.getY(), kid.getZ());
                    start[0] = kid.distanceTo(you);
                    helper.assertTrue(Greetings.waveForTests(kid, you), "the child waves too");
                    helper.assertTrue(you.getUUID().equals(Greetings.following(kid)), "and tags along after the player");
                    Kit.log("tl05 the child " + kid.displayNameCap() + " tags along from " + String.format("%.1f", start[0]) + " blocks off");
                    mark[0] = t;
                    phase[0] = 1;
                }
                case 1 -> {
                    double d = kid.distanceTo(you);
                    if (t % 40 == 0) Kit.log("tl05 tick " + t + ": the child " + String.format("%.1f", d) + " from the player, doing " + kid.hobbyNow());
                    if (d > 4.0) {
                        if (t - mark[0] > 400) helper.fail("tl05 the child never came up with the player: " + String.format("%.1f", d)
                            + " blocks | " + kid.debugLine());
                        return;
                    }
                    helper.assertTrue(kid.hobbyNow() != null && kid.hobbyNow().contains("tagging along after " + name), "its card: " + kid.hobbyNow());
                    phase[0] = 2;
                }
                case 2 -> {
                    if (Greetings.following(kid) != null) {
                        if (t - mark[0] > 900) helper.fail("tl05 the child still tagging along at tick " + t);
                        return;
                    }
                    Kit.log("tl05 the child went back to its day " + (t - mark[0]) + " ticks after the wave");
                    helper.assertTrue(t - mark[0] >= 500, "it tagged along for a good while: " + (t - mark[0]) + " ticks");
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ============================================================ tl06: a light after dark

    /**
     * After dark, out of doors, a folk with a lantern and torches in its pack holds the lantern in its free
     * hand (one out of the pack, none made); under a roof it is back in the pack, out again under the sky,
     * and back at daybreak. A folk with no light in its pack holds none.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "tl06_night_light")
    public static void tl06_night_light(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 423000;
        Kit.hold(level, x, Z, 24);
        Kit.prepare(level, x, Z, 24);
        BlockPos heart = flat(level, x, Z, 10);
        long base = (level.getDayTime() / 24000L + 2) * 24000L;
        level.setDayTime(base + 2000L);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity none = VillageFolkSpawnerBlock.raise(level, heart.offset(4, 0, 4), 0.0F);
        helper.assertTrue(f != null && none != null, "two folk");
        helper.runAtTickTime(20, () -> {
            for (VillageFolkEntity o : List.of(f, none)) {
                o.setJob(StationTask.WOOD);
                o.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                o.removeMatching(NightLight::isLight, 999);
            }
            f.insertItem(new ItemStack(Items.TORCH, 3));
            f.insertItem(new ItemStack(Items.LANTERN, 1));
            level.setDayTime(base + 14000L);
            BlockPos roof = f.blockPosition().above(3);
            level.setBlock(roof, Blocks.AIR.defaultBlockState(), 3);
            NightLight.tickForTests(f);
            NightLight.tickForTests(none);
            ItemStack held = f.getItemBySlot(EquipmentSlot.OFFHAND);
            Kit.log("tl06 after dark, outside: holds " + held + "; pack lanterns " + f.countMatching(s -> s.is(Items.LANTERN))
                + ", torches " + f.countMatching(s -> s.is(Items.TORCH)) + "; the folk with none holds " + none.getItemBySlot(EquipmentSlot.OFFHAND));
            helper.assertTrue(held.is(Items.LANTERN) && held.getCount() == 1, "the lantern in its hand after dark: " + held);
            helper.assertTrue(f.countMatching(s -> s.is(Items.LANTERN)) == 0 && f.countMatching(s -> s.is(Items.TORCH)) == 3,
                "the lantern out of its own pack, the torches left in it");
            helper.assertTrue(none.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(), "no light in its pack, none in its hand");
            // Under a roof: put away.
            level.setBlock(roof, Blocks.STONE.defaultBlockState(), 3);
            NightLight.tickForTests(f);
            helper.assertTrue(f.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty() && f.countMatching(s -> s.is(Items.LANTERN)) == 1,
                "indoors it is back in the pack");
            level.setBlock(roof, Blocks.AIR.defaultBlockState(), 3);
            NightLight.tickForTests(f);
            helper.assertTrue(f.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.LANTERN), "out again under the sky");
            // Daybreak: put away.
            level.setDayTime(base + 24000L + 300L);
            NightLight.tickForTests(f);
            int lights = f.countCarried(NightLight::isLight);
            Kit.log("tl06 at daybreak: holds " + f.getItemBySlot(EquipmentSlot.OFFHAND) + "; lights carried " + lights);
            helper.assertTrue(f.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty() && f.countMatching(s -> s.is(Items.LANTERN)) == 1,
                "at daybreak it is back in the pack");
            helper.assertTrue(lights == 4, "nothing lost and nothing made: " + lights);
            helper.succeed();
        });
    }
}
