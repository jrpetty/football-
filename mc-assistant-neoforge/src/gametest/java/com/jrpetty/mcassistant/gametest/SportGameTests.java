package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Archery;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Contests;
import com.jrpetty.mcassistant.entity.Football;
import com.jrpetty.mcassistant.entity.FoundingDay;
import com.jrpetty.mcassistant.entity.Friendlies;
import com.jrpetty.mcassistant.entity.League;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Pitch;
import com.jrpetty.mcassistant.entity.Sport;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sport and play [batchC]: the football pitch and its keepers, a match on it (the referee's eye for a goal, a
 * short match with a real ball out of the stores), the league and the cup in the hall, a friendly between two
 * towns (the visitors walking there and home), the fishing contest, the children's race, and the archery range.
 *
 * <ul>
 * <li><b>sp01</b>: a town of twenty in stone wants a pitch; it goes on a long lot; drawn, it has its goals,
 *     benches and lamps; its keepers cut a bump away, fill a hole, turf the builder's cobble and lay every line
 *     in the stores' white wool.</li>
 * <li><b>sp02</b>: the referee: a ball over the line between the posts and under the bar is a goal at that end,
 *     wide or over the bar or over a touchline is out. A match between two ends with the stores' slime ball: the
 *     ball on the centre spot at the kick-off, a goal counted and the ball back to the centre, the players running
 *     at it and kicking it, and at the whistle the result in the chronicle and the table and the ball back in
 *     the stores.</li>
 * <li><b>sp03</b>: three league results; the table in order; the year turns and the top end are champions; the
 *     cup made of the stores' gold ingot in the stores' item frame on the hall's back wall, its name the town's;
 *     the next year's champions engraved on the same cup with no more gold.</li>
 * <li><b>sp04</b>: two friendly towns a hundred and ten blocks apart: a side sent and the match called off at
 *     once (both chronicles, the side home again); then a side sent that walks to the other's pitch, plays the
 *     host's turn-out, the result in both chronicles, the towns the warmer for it, and the side walks home.</li>
 * <li><b>sp05</b>: two fishers (one with a rod borrowed from the stores) fish the contest at a pond; the fish go
 *     into the stores, the rod back, and the winner gets five coins out of the treasury.</li>
 * <li><b>sp06</b>: two children race on the square, their parents at the finish; the winner gets a cookie out of
 *     the stores.</li>
 * <li><b>sp07</b>: an Iron Age town with two guards wants a range; its butts get targets of the stores' redstone;
 *     a guard practises with six of the stores' arrows and a borrowed bow, pulls them and puts them back, and
 *     is the better at its trade for it; then the archery contest between the two.</li>
 * </ul>
 *
 * <p>Each on its own ground in the band x 640,000 to 659,999, z 50,000, calling the town's logic directly where
 * it can and logging what it measures.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SportGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    /** Flat grass round here, clear air above it. Returns the ground's top (the first free block). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int rx, int rz) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - rx; x <= cx + rx; x++) {
            for (int z = cz - rz; z <= cz + rz; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 16; dy++) {
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

    private static int stock(ServerLevel level, UUID village, Item item) {
        return Market.stock(level, village, s -> s.is(item));
    }

    private static int fishIn(ServerLevel level, UUID village) {
        return stock(level, village, Items.COD) + stock(level, village, Items.SALMON) + stock(level, village, Items.PUFFERFISH)
            + stock(level, village, Items.TROPICAL_FISH);
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private static String chronicle(UUID village) {
        List<String> out = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(village)) out.add(e.text());
        return String.join(" / ", out);
    }

    private static List<VillageFolkEntity> raise(GameTestHelper helper, ServerLevel level, BlockPos heart, int n) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + (i % 5) * 3, 0, 5 + (i / 5) * 3), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            out.add(f);
        }
        return out;
    }

    private static long morning(ServerLevel level) {
        long day = level.getDayTime() / 24000L + 1;
        level.setDayTime(day * 24000L + 2000L);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
        return day;
    }

    // ============================================================ sp01: the pitch

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "sp01_pitch")
    public static void sp01_pitch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 640000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40, 40);
        morning(level);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        helper.assertTrue(BuildGoal.STRUCTURES.contains(Pitch.STRUCTURE), "the builders know the pitch");
        helper.assertTrue(Pitch.wanted(id, 20) && !Pitch.wanted(id, 19), "wanted at twenty folk in stone, not at nineteen");
        helper.assertTrue(Villages.whyBuild(id, Pitch.STRUCTURE).contains("football"), "the reason for it: " + Villages.whyBuild(id, Pitch.STRUCTURE));
        helper.assertTrue("field".equals(TownPlan.placeFor(Pitch.STRUCTURE)), "the plan's place for it is a field");
        List<TownPlan.Lot> lots = TownPlan.candidates(Pitch.STRUCTURE);
        helper.assertTrue(!lots.isEmpty() && lots.get(0).kind() == TownPlan.Kind.LONG, "a long lot first: " + (lots.isEmpty() ? "none" : lots.get(0)));
        int[] half = BuildGoal.footprint(Pitch.STRUCTURE);
        helper.assertTrue(half[0] <= lots.get(0).halfAcross() && half[1] <= lots.get(0).halfDeep(),
            "the drawing fits a long lot: " + half[0] + " by " + half[1]);
        Ledger.Building b = Pitch.putUp(level, id, heart.offset(0, 0, 24), Direction.NORTH);
        helper.assertTrue(b != null && Pitch.of(id) != null, "the pitch in the town's ledger");
        for (int end : new int[]{ -1, 1 }) {
            for (int u : new int[]{ -2, 2 }) {
                helper.assertTrue(level.getBlockState(Pitch.at(b, u, end * 7)).getBlock() instanceof FenceBlock
                    && level.getBlockState(Pitch.at(b, u, end * 7).above()).getBlock() instanceof FenceBlock, "a goal post of two fences at " + u + ", " + end * 7);
            }
            helper.assertTrue(level.getBlockState(Pitch.at(b, 0, end * 7).above(2)).getBlock() instanceof FenceBlock, "a crossbar over the goal at " + end * 7);
            helper.assertTrue(level.getBlockState(Pitch.at(b, 0, end * 9)).getBlock() instanceof FenceBlock, "a net of fences behind the goal at " + end * 9);
        }
        helper.assertTrue(level.getBlockState(Pitch.at(b, -5, 4)).getBlock() instanceof StairBlock
            && level.getBlockState(Pitch.at(b, 5, -4)).getBlock() instanceof StairBlock, "benches down both sides");
        // A bump on the field, a hole in it, and a patch of the builder's cobble.
        BlockPos bump = Pitch.at(b, 1, 3), hole = Pitch.at(b, -1, -3).below(), rock = Pitch.at(b, 2, 2).below();
        level.setBlock(bump, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(hole, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(rock, Blocks.COBBLESTONE.defaultBlockState(), 3);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.WHITE_WOOL, 64), new ItemStack(Items.DIRT, 10));
        int wool0 = stock(level, id, Items.WHITE_WOOL), dirt0 = stock(level, id, Items.DIRT);
        List<String> did = new ArrayList<>();
        String done;
        while (did.size() < 40 && (done = Pitch.tendOne(level, v, b, 8)) != null) did.add(done);
        int lines = Pitch.lines(b).size(), laid = 0, wool = 0;
        for (BlockPos p : Pitch.lines(b)) {
            if (level.getBlockState(p).is(Blocks.WHITE_WOOL)) { laid++; wool++; }
            else if (level.getBlockState(p).is(Blocks.BIRCH_PLANKS)) laid++;
        }
        int wool1 = stock(level, id, Items.WHITE_WOOL), dirt1 = stock(level, id, Items.DIRT);
        Kit.log("sp01 the pitch at " + b.anchor().toShortString() + " facing " + b.facing() + "; the keepers: " + did
            + "; lines " + laid + " of " + lines + " (" + wool + " wool); wool " + wool0 + " -> " + wool1 + ", dirt " + dirt0 + " -> " + dirt1
            + "; " + Pitch.status(level, id));
        helper.assertTrue(level.getBlockState(bump).isAir(), "the bump cut away: " + level.getBlockState(bump));
        helper.assertTrue(level.getBlockState(hole).is(Blocks.DIRT), "the hole filled with the stores' earth: " + level.getBlockState(hole));
        helper.assertTrue(level.getBlockState(rock).is(Blocks.DIRT), "the cobble turfed: " + level.getBlockState(rock));
        helper.assertTrue(laid == lines && wool == lines, "every line laid in white wool: " + laid + " of " + lines);
        helper.assertTrue(wool0 - wool1 == lines, "the wool out of the stores, a block a line cell: " + (wool0 - wool1));
        helper.assertTrue(stock(level, id, Items.COBBLESTONE) > 0, "the cobble went into the stores");
        helper.assertTrue(Pitch.status(level, id).contains("all laid"), "the books say the lines are laid: " + Pitch.status(level, id));
        helper.succeed();
    }

    // ============================================================ sp02: a match

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "sp02_football")
    public static void sp02_football(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 642000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40, 40);
        morning(level);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Ledger.Building b = Pitch.putUp(level, id, heart.offset(0, 0, 24), Direction.NORTH);
        // The referee's eye.
        int back = Football.judge(b, Pitch.point(b, 0, 8.0)), front = Football.judge(b, Pitch.point(b, 0.5, -8.2));
        int wide = Football.judge(b, Pitch.point(b, 3.0, 8.0)), over = Football.judge(b, Pitch.point(b, 0, 8.0).add(0, 2.5, 0));
        int touch = Football.judge(b, Pitch.point(b, 4.8, 0)), centre = Football.judge(b, Pitch.point(b, 0, 0));
        int corner = Football.judge(b, Pitch.point(b, -3.9, 6.9));
        Kit.log("sp02 the referee: back goal " + back + ", front goal " + front + ", wide " + wide + ", over the bar " + over
            + ", over the touchline " + touch + ", centre " + centre + ", in the corner " + corner);
        helper.assertTrue(back == 1 && front == -1, "a goal at each end, between the posts and under the bar");
        helper.assertTrue(wide == 2 && over == 2 && touch == 2, "wide, over the bar and over the touchline are out");
        helper.assertTrue(centre == 0 && corner == 0, "on the field is in play");
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.SLIME_BALL, 1));
        String end = Football.teamOf(folk.get(0));
        helper.assertTrue(end != null && end.endsWith(" End"), "every folk has its end of the town: " + end);
        String on = Football.startForTests(level, v, folk.subList(0, 3), "the North End", folk.subList(3, 6), "the South End", true);
        helper.assertTrue(on != null, "a match begun");
        helper.assertTrue(stock(level, id, Items.SLIME_BALL) == 0, "the ball out of the stores");
        final int[] phase = { 0 };
        final long[] at = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == 0) {
                if (t < 40) return;
                helper.assertTrue(Football.kickOffForTests(level, id), "the kick-off");
                ItemEntity ball = Football.ballForTests(level, id);
                helper.assertTrue(ball != null && ball.getItem().is(Items.SLIME_BALL), "the stores' slime ball on the centre spot");
                double[] uw = Pitch.local(b, ball.position());
                helper.assertTrue(Football.scoreForTests(id)[2] > 0 || Math.abs(uw[0]) < 0.6 && Math.abs(uw[1]) < 0.6,
                    "on the centre spot: " + uw[0] + ", " + uw[1]);
                // Into the back goal (the home side's to attack): a goal.
                Vec3 in = Pitch.point(b, 0.2, 7.9);
                ball.moveTo(in.x, in.y + 0.1, in.z);
                ball.setDeltaMovement(Vec3.ZERO);
                Football.refereeForTests(level, id);
                int[] score = Football.scoreForTests(id);
                double[] after = Pitch.local(b, ball.position());
                Kit.log("sp02 the ball put in the back goal: score " + score[0] + "-" + score[1] + "; the ball back at " + after[0] + ", " + after[1]);
                helper.assertTrue(score[0] == 1 && score[1] == 0, "a goal to the home side: " + score[0] + "-" + score[1]);
                helper.assertTrue(Math.abs(after[0]) < 0.6 && Math.abs(after[1]) < 0.6, "the ball back on the centre spot for the kick-off");
                phase[0] = 1;
                at[0] = t;
                return;
            }
            if (phase[0] == 1) {
                int[] score = Football.scoreForTests(id);
                if (score == null) { helper.fail("sp02 the match ended before the whistle: " + Football.lastForTests(id)); return; }
                if (t % 100 == 0) {
                    ItemEntity ball = Football.ballForTests(level, id);
                    Kit.log("sp02 tick " + t + ": " + score[0] + "-" + score[1] + ", " + score[2] + " kicks; the ball at "
                        + (ball == null ? "?" : ball.blockPosition().toShortString()));
                    for (VillageFolkEntity f : folk) Kit.log("   " + f.displayNameCap() + " at " + f.blockPosition().toShortString() + ": " + f.hobbyNow());
                }
                if (score[2] < 3 && t - at[0] < 900) return;
                helper.assertTrue(score[2] >= 3, "the players ran at the ball and kicked it: " + score[2] + " kicks");
                helper.assertTrue(folk.get(0).hobbyNow() != null && folk.get(0).hobbyNow().contains("football"), "its card says it is playing football: " + folk.get(0).hobbyNow());
                Football.endForTests(level, id);
                String last = Football.lastForTests(id);
                Kit.log("sp02 the final whistle: " + last + "; the table " + League.table(id).size() + " rows; the chronicle: " + chronicle(id));
                helper.assertTrue(Football.ballForTests(level, id) == null && stock(level, id, Items.SLIME_BALL) == 1, "the ball back in the stores");
                helper.assertTrue(last != null && last.contains("the North End") && last.contains("the South End"), "the result: " + last);
                helper.assertTrue(told(id, "on the football pitch"), "the result in the chronicle");
                List<League.Row> rows = League.table(id);
                helper.assertTrue(rows.size() == 2 && rows.get(0).played == 1 && rows.get(1).played == 1, "both ends in the league table, a game each");
                helper.assertTrue(!Sport.busy(folk.get(0)), "the players back to their own day");
                helper.succeed();
            }
        });
    }

    // ============================================================ sp03: the league and the cup

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sp03_league_cup")
    public static void sp03_league_cup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 644000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 36, 36);
        long day = morning(level);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 2);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        // Its year counted from today (a day of the world's own; the world may be young, so never before day 0).
        FoundingDay.foundedForTests(id, day);
        BlockPos hallAt = heart.offset(0, 0, 20);
        BuildGoal.stamp(level, "hall", hallAt, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "hall", hallAt, Direction.NORTH);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.GOLD_INGOT, 1), new ItemStack(Items.ITEM_FRAME, 1));
        helper.runAtTickTime(10, () -> {
            League.played(level, v, day, "the North End", "the South End", 3, 1, true);
            League.played(level, v, day + 1, "the East End", "the North End", 0, 0, true);
            League.played(level, v, day + 2, "the South End", "the East End", 2, 2, true);
            List<League.Row> rows = League.table(id);
            StringBuilder sb = new StringBuilder();
            for (League.Row r : rows) sb.append(r.team).append(' ').append(r.points()).append("; ");
            Kit.log("sp03 the table: " + sb);
            helper.assertTrue(rows.size() == 3 && rows.get(0).team.equals("the North End") && rows.get(0).points() == 4
                && rows.get(1).team.equals("the East End") && rows.get(2).team.equals("the South End"), "the table in order: " + sb);
            // The year turns.
            int gold0 = stock(level, id, Items.GOLD_INGOT), frame0 = stock(level, id, Items.ITEM_FRAME);
            League.daily(level, v, day + 28);
            String champs = League.cupHolder(id);
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(hallAt).inflate(8, 8, 12),
                f -> f.isAlive() && f.getItem().is(Items.GOLD_INGOT));
            ItemFrame cup = frames.isEmpty() ? null : frames.get(0);
            Kit.log("sp03 the champions " + champs + "; frames in the hall " + frames.size() + (cup == null ? "" : " at " + cup.blockPosition().toShortString()
                + " holding " + cup.getItem() + " " + cup.getItem().get(DataComponents.CUSTOM_NAME)) + "; the chronicle: " + chronicle(id));
            helper.assertTrue("the North End".equals(champs), "the North End are champions: " + champs);
            helper.assertTrue(told(id, "won the league"), "the champions in the chronicle");
            helper.assertTrue(League.table(id).isEmpty(), "a new table for the new year");
            helper.assertTrue(cup != null && cup.getItem().is(Items.GOLD_INGOT), "the cup in its frame in the hall");
            String name = cup.getItem().get(DataComponents.CUSTOM_NAME) == null ? "" : cup.getItem().get(DataComponents.CUSTOM_NAME).getString();
            helper.assertTrue(name.equals("The " + Villages.name(id) + " Cup"), "the cup named for the town: " + name);
            ItemLore lore = cup.getItem().get(DataComponents.LORE);
            helper.assertTrue(lore != null && lore.lines().size() == 1 && lore.lines().get(0).getString().contains("the North End"), "the champions on it: " + lore);
            helper.assertTrue(stock(level, id, Items.GOLD_INGOT) == gold0 - 1 && stock(level, id, Items.ITEM_FRAME) == frame0 - 1,
                "the stores' gold ingot and frame went into it");
            // The next year: the South End's, on the same cup, no gold wanted.
            League.played(level, v, day + 29, "the South End", "the North End", 2, 0, true);
            League.daily(level, v, day + 56);
            ItemLore again = cup.getItem().get(DataComponents.LORE);
            Kit.log("sp03 the second year: " + League.cupHolder(id) + "; the cup reads " + again + "; " + level.getEntitiesOfClass(ItemFrame.class,
                new AABB(hallAt).inflate(8, 8, 12), f -> f.isAlive() && f.getItem().is(Items.GOLD_INGOT)).size() + " cups");
            helper.assertTrue("the South End".equals(League.cupHolder(id)), "the South End's year: " + League.cupHolder(id));
            helper.assertTrue(cup.isAlive() && again != null && again.lines().size() == 2, "the same cup, both years on it: " + again);
            helper.assertTrue(level.getEntitiesOfClass(ItemFrame.class, new AABB(hallAt).inflate(8, 8, 12),
                f -> f.isAlive() && f.getItem().is(Items.GOLD_INGOT)).size() == 1, "one cup");
            helper.succeed();
        });
    }

    // ============================================================ sp04: a friendly

    @GameTest(template = EMPTY, timeoutTicks = 7000, batch = "sp04_friendly")
    public static void sp04_friendly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int vx = 646000, hx = 646110;
        Kit.hold(level, (vx + hx) / 2, Z, 100);
        Kit.prepare(level, (vx + hx) / 2, Z, 100);
        int y = flat(level, (vx + hx) / 2, Z, 80, 18).getY();
        BlockPos vHeart = new BlockPos(vx, y, Z), hHeart = new BlockPos(hx, y, Z);
        morning(level);
        Villages.Village visitors = Villages.found(level, vHeart), host = Villages.found(level, hHeart);
        List<VillageFolkEntity> side = raise(helper, level, vHeart, 5);
        List<VillageFolkEntity> home = raise(helper, level, hHeart, 4);
        for (VillageFolkEntity f : side) helper.assertTrue(visitors.id().equals(f.ownerId()), "a folk of the visitors' town");
        for (VillageFolkEntity f : home) helper.assertTrue(host.id().equals(f.ownerId()), "a folk of the host town");
        Ledger.Building pitch = Pitch.putUp(level, host.id(), hHeart.offset(0, 0, 27), Direction.NORTH);
        chestAt(level, hHeart.offset(3, 0, -3), new ItemStack(Items.SLIME_BALL, 1));
        Ledger.relate(visitors.id(), host.id(), 40);
        // Already acquainted: no envoy goes off with one of the sides to say hello (Envoys).
        Ledger.note(visitors.id(), "envoyed/" + host.id(), "0");
        Ledger.note(host.id(), "envoyed/" + visitors.id(), "0");
        Football.quickForTests(true);
        final int[] phase = { 0 };
        final long[] at = { 0 };
        final int[] rel = { 0 };
        final Friendlies.Tour[] tour = { null };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 5) return;
            switch (phase[0]) {
                case 0 -> {
                    // A side sent, and the match called off at once: both chronicles, and home again.
                    Friendlies.Tour off = Friendlies.sendForTests(level, visitors, host);
                    helper.assertTrue(off != null && Friendlies.stateForTests(off)[0] >= 3, "a side of three or more sent");
                    helper.assertTrue(side.stream().anyMatch(f -> f.getPersistentData().hasUUID("mca_sport_away")), "a walker carries the mark of home");
                    Friendlies.callOffForTests(level, off, "the rain");
                    Kit.log("sp04 called off: " + Friendlies.outcomeForTests(off));
                    helper.assertTrue(told(visitors.id(), "was called off") && told(host.id(), "was called off"), "the call-off in both chronicles");
                    tour[0] = off;
                    phase[0] = 1;
                    at[0] = t;
                }
                case 1 -> {
                    int[] s = Friendlies.stateForTests(tour[0]);
                    if (s[2] < s[0]) {
                        if (t - at[0] > 600) helper.fail("sp04 the called-off side never got home: " + java.util.Arrays.toString(s));
                        return;
                    }
                    helper.assertTrue(side.stream().noneMatch(f -> f.getPersistentData().hasUUID("mca_sport_away")), "the mark off, home again");
                    tour[0] = Friendlies.sendForTests(level, visitors, host);
                    helper.assertTrue(tour[0] != null, "a side sent again");
                    Kit.log("sp04 the side of " + Friendlies.stateForTests(tour[0])[0] + " sets out at tick " + t);
                    phase[0] = 2;
                    at[0] = t;
                }
                case 2 -> {
                    int[] s = Friendlies.stateForTests(tour[0]);
                    if (t % 200 == 0) {
                        Kit.log("sp04 tick " + t + ": " + java.util.Arrays.toString(s));
                        for (VillageFolkEntity f : side) Kit.log("   " + f.displayNameCap() + " at " + f.blockPosition().toShortString() + ": " + f.hobbyNow());
                    }
                    boolean ready = s[1] == s[0] || s[1] >= 2 && t - at[0] > 3000;
                    if (!ready) {
                        if (t - at[0] > 4200) helper.fail("sp04 the side never got to the pitch: " + java.util.Arrays.toString(s));
                        return;
                    }
                    helper.assertTrue(side.stream().anyMatch(f -> f.hobbyNow() != null && f.hobbyNow().contains("pitch")), "its card says it is waiting by the pitch");
                    double d = side.get(0).position().distanceTo(Pitch.point(pitch, 0, 0));
                    Kit.log("sp04 the side there at tick " + t + " (" + s[1] + " of " + s[0] + "), " + String.format("%.1f", d) + " from the centre spot");
                    helper.assertTrue(Friendlies.startForTests(level, host, tour[0]), "the host turns out and the match is on");
                    helper.assertTrue(Football.kickOffForTests(level, host.id()), "the kick-off");
                    phase[0] = 3;
                    at[0] = t;
                }
                case 3 -> {
                    if (t - at[0] < 200) return;
                    rel[0] = Ledger.relation(visitors.id(), host.id());
                    Football.endForTests(level, host.id());
                    String out = Friendlies.outcomeForTests(tour[0]);
                    int now = Ledger.relation(visitors.id(), host.id());
                    Kit.log("sp04 the result: " + out + "; the relation " + rel[0] + " -> " + now);
                    helper.assertTrue(out != null && out.contains(Villages.name(visitors.id())) && out.contains(Villages.name(host.id())), "both towns in the result");
                    helper.assertTrue(told(host.id(), "a friendly on our pitch against " + Villages.name(visitors.id())), "in the host's chronicle");
                    helper.assertTrue(told(visitors.id(), "a friendly away at " + Villages.name(host.id())), "in the visitors' chronicle");
                    helper.assertTrue(now - rel[0] == 3 || now - rel[0] == 1, "the towns the warmer for it: " + rel[0] + " -> " + now);
                    helper.assertTrue(Friendlies.stateForTests(tour[0])[3] == 1, "the side turned for home");
                    helper.assertTrue(stock(level, host.id(), Items.SLIME_BALL) == 1, "the host's ball back in its stores");
                    phase[0] = 4;
                    at[0] = t;
                }
                case 4 -> {
                    int[] s = Friendlies.stateForTests(tour[0]);
                    if (s[2] < s[0]) {
                        if (t % 200 == 0) Kit.log("sp04 walking home, tick " + t + ": " + java.util.Arrays.toString(s));
                        return;
                    }
                    // Where each got home (it goes on with its own day from there, so where it is now says nothing).
                    List<BlockPos> ended = Friendlies.homeForTests(tour[0]);
                    Kit.log("sp04 the side home at tick " + t + ", got in at " + ended);
                    helper.assertTrue(ended.size() == s[0], "every walker home: " + ended.size() + " of " + s[0]);
                    for (BlockPos p : ended) {
                        helper.assertTrue(p.distSqr(new BlockPos(vHeart.getX(), p.getY(), vHeart.getZ())) < 24 * 24, "home again: " + p.toShortString());
                    }
                    helper.assertTrue(side.stream().noneMatch(f -> f.getPersistentData().hasUUID("mca_sport_away")), "the mark of an away day off");
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ============================================================ sp05: the fishing contest

    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "sp05_fishing")
    public static void sp05_fishing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 648000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30, 30);
        Kit.pond(level, x + 10, Z, 3);
        long day = morning(level);
        level.setDayTime(day * 24000L + 7000L);           // past the morning's wages
        level.updateSkyBrightness();
        List<VillageFolkEntity> folk = raise(helper, level, heart, 3);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity a = folk.get(1), b = folk.get(2);
        a.setJob(StationTask.FISH);
        b.setJob(StationTask.FISH);
        a.insertItem(new ItemStack(Items.FISHING_ROD));
        chestAt(level, heart.offset(-3, 0, -3), new ItemStack(Items.FISHING_ROD, 1));
        Ledger.addCoins(id, 20);
        Contests.quickForTests(true);
        String r = Contests.fishingForTests(level, v);
        Kit.log("sp05 " + r);
        helper.assertTrue(r.contains("fishing contest is on"), "the contest begun: " + r);
        helper.assertTrue(stock(level, id, Items.FISHING_ROD) == 0, "a rod borrowed from the stores");
        // The weigh-in is called here, once a few fish are in, and read back at once: the town's other doings
        // (a pedlar paid out of the treasury, wages, a fisher back at its trade drawing a rod from the stores the
        // moment it is free) go on round the contest, and what is measured is the weigh-in's own doing.
        helper.onEachTick(() -> {
            long t = helper.getTick();
            int[] s = Contests.fishingStateForTests(id);
            helper.assertTrue(s != null, "the contest still on until the weigh-in");
            if (t % 100 == 0) Kit.log("sp05 tick " + t + ": " + s[0] + " at the bank, " + s[1] + " fish; " + a.blockPosition().toShortString() + " / " + b.blockPosition().toShortString());
            if (s[1] < 4 && t < 900) return;
            int caught = s[1];
            int fish0 = fishIn(level, id), rods0 = stock(level, id, Items.FISHING_ROD), coins0 = Ledger.coins(id);
            int[] purse0 = { folk.get(0).purse(), a.purse(), b.purse() };
            String line = Contests.weighInForTests(level, id);
            int fish = fishIn(level, id), rods = stock(level, id, Items.FISHING_ROD), coins = Ledger.coins(id), winners = 0;
            int[] won = new int[3];
            for (int i = 0; i < 3; i++) {
                won[i] = folk.get(i).purse() - purse0[i];
                if (won[i] == Contests.PURSE) winners++;
            }
            Kit.log("sp05 the weigh-in at tick " + t + ": " + line + "; " + caught + " fish caught, the stores' fish " + fish0 + " -> " + fish
                + "; rods " + rods0 + " -> " + rods + "; the treasury " + coins0 + " -> " + coins + "; purses +" + java.util.Arrays.toString(won));
            helper.assertTrue(caught > 0 && fish - fish0 == caught, "the catch into the stores: " + (fish - fish0) + " of " + caught);
            helper.assertTrue(rods - rods0 == 1, "the borrowed rod back in the stores: " + rods0 + " -> " + rods);
            helper.assertTrue(told(id, "won the fishing contest"), "the winner in the chronicle");
            helper.assertTrue(coins0 - coins == Contests.PURSE && winners == 1, "the purse out of the treasury to the winner alone");
            helper.assertTrue(!Sport.busy(a) && !Sport.busy(b), "back to their own day");
            helper.succeed();
        });
    }

    // ============================================================ sp06: the children's race

    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "sp06_sports_day")
    public static void sp06_sports_day(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 650000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 32, 32);
        morning(level);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 2);
        VillageFolkEntity mother = folk.get(0), father = folk.get(1);
        UUID id = mother.ownerId();
        Villages.Village v = Villages.get(id);
        helper.assertTrue(mother.raiseChildWith(father) != null && mother.raiseChildWith(father) != null, "two children born");
        List<VillageFolkEntity> kids = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && f.isBaby()) kids.add(f);
        helper.assertTrue(kids.size() >= 2, "children to race: " + kids.size());
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.COOKIE, 2));
        String r = Contests.raceForTests(level, v);
        Kit.log("sp06 " + r);
        helper.assertTrue(r.contains("race is on"), "the race set: " + r);
        helper.assertTrue(Contests.watchingRaceForTests(mother.getUUID()) && Contests.watchingRaceForTests(father.getUUID()), "the parents to the finish");
        helper.onEachTick(() -> {
            long t = helper.getTick();
            int stage = Contests.raceStageForTests(id);
            if (stage >= 0) {
                if (t % 50 == 0) {
                    StringBuilder sb = new StringBuilder();
                    for (VillageFolkEntity k : kids) sb.append(k.displayNameCap()).append(' ').append(k.blockPosition().toShortString()).append("; ");
                    Kit.log("sp06 tick " + t + " stage " + stage + ": " + sb + " mother at " + mother.blockPosition().toShortString());
                }
                return;
            }
            VillageFolkEntity winner = null;
            for (VillageFolkEntity k : kids) for (ItemStack s : k.getInventoryItems()) if (s.is(Items.COOKIE)) winner = k;
            Kit.log("sp06 the race over at tick " + t + ": " + (winner == null ? "no cookie in a pack" : winner.displayNameCap() + " has the cookie")
                + "; the stores' cookies " + stock(level, id, Items.COOKIE) + "; the chronicle: " + chronicle(id));
            helper.assertTrue(told(id, "won the children's race"), "the winner in the chronicle");
            helper.assertTrue(winner != null && stock(level, id, Items.COOKIE) == 1, "a cookie out of the stores to the winner");
            helper.succeed();
        });
    }

    // ============================================================ sp07: the archery range

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "sp07_archery")
    public static void sp07_archery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 652000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 32, 32);
        morning(level);
        long day = level.getDayTime() / 24000L;
        level.setDayTime(day * 24000L + 5000L);           // past the morning's practice: nobody goes down to the butts unbidden
        level.updateSkyBrightness();
        List<VillageFolkEntity> folk = raise(helper, level, heart, 3);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity g1 = folk.get(1), g2 = folk.get(2);
        g1.setJob(StationTask.GUARD);
        g2.setJob(StationTask.GUARD);
        g1.removeMatching(s -> s.is(Items.BOW), 64);
        if (g1.getMainHandItem().is(Items.BOW)) g1.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        g2.insertItem(new ItemStack(Items.BOW));
        // A full quiver each for the watch's own use: a guard with a bow and few arrows tops its kit up from the
        // stores (AssistantEntity), which is its trade's doing and not the range's.
        g1.insertItem(new ItemStack(Items.ARROW, 32));
        g2.insertItem(new ItemStack(Items.ARROW, 32));
        Villages.ageForTests(id, Villages.Age.IRON);
        helper.assertTrue(Archery.wanted(id), "an Iron Age town with two guards wants a range");
        helper.assertTrue("corner".equals(TownPlan.placeFor(Archery.STRUCTURE)), "by the wall, on a corner lot");
        BlockPos at = heart.offset(0, 0, 20);
        BuildGoal.stamp(level, Archery.STRUCTURE, at, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, Archery.STRUCTURE, at, Direction.NORTH);
        Ledger.Building range = Archery.of(id);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.ARROW, 24), new ItemStack(Items.BOW, 1), new ItemStack(Items.REDSTONE, 8));
        List<String> did = new ArrayList<>();
        String done;
        while (did.size() < 10 && (done = Archery.tendOne(level, v, range)) != null) did.add(done);
        int targets = 0;
        for (Archery.Lane l : Archery.lanes(range)) if (level.getBlockState(l.target()).is(Blocks.TARGET)) targets++;
        Kit.log("sp07 the range at " + range.anchor().toShortString() + "; the keepers: " + did + "; " + targets + " targets; redstone left "
            + stock(level, id, Items.REDSTONE));
        helper.assertTrue(targets == 2 && stock(level, id, Items.REDSTONE) == 0, "two targets of the stores' eight redstone, the third butt hay");
        int arrows0 = stock(level, id, Items.ARROW), xp0 = g1.xpInTrade(StationTask.GUARD);
        helper.assertTrue(Archery.practiseForTests(g1, level), "the guard goes down to practise");
        helper.assertTrue(stock(level, id, Items.BOW) == 0 && stock(level, id, Items.ARROW) == arrows0 - Archery.ARROWS, "a bow borrowed and six arrows out of the stores");
        final int[] phase = { 0 };
        final long[] since = { 0 };
        final int[] arrows = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == 0) {
                if (Archery.sessionForTests(g1.getUUID())) {
                    if (t % 100 == 0) Kit.log("sp07 tick " + t + ": " + g1.displayNameCap() + " at " + g1.blockPosition().toShortString() + ", " + g1.hobbyNow());
                    if (t > 1600) helper.fail("sp07 the practice never finished");
                    return;
                }
                int[] last = Archery.lastForTests(g1.getUUID());
                int arrowsNow = stock(level, id, Items.ARROW), xp = g1.xpInTrade(StationTask.GUARD);
                Kit.log("sp07 practice over at tick " + t + ": shot " + last[0] + ", hits " + last[1] + ", points " + last[2] + ", pulled " + last[3]
                    + "; arrows " + arrows0 + " -> " + arrowsNow + "; xp " + xp0 + " -> " + xp);
                helper.assertTrue(last[0] == Archery.ARROWS, "six arrows shot: " + last[0]);
                helper.assertTrue(arrowsNow == arrows0 - Archery.ARROWS + last[3] && last[3] >= 4, "the arrows pulled and back in the stores: " + last[3]);
                helper.assertTrue(stock(level, id, Items.BOW) == 1, "the borrowed bow back");
                helper.assertTrue(xp > xp0, "the better at its trade for the morning: " + xp0 + " -> " + xp);
                arrows[0] = arrowsNow;
                // How the watch stands when the contest is called (the leader's escort, a fight, asleep, away).
                for (VillageFolkEntity g : new VillageFolkEntity[]{ g1, g2 }) {
                    Kit.log("sp07 " + g.displayNameCap() + ": asleep " + g.isSleeping() + ", target " + g.getTarget() + ", escorting "
                        + com.jrpetty.mcassistant.entity.Patrols.escorting(g) + ", trip " + (g.trip() != null) + ", doing " + g.hobbyNow());
                }
                String c = Archery.contestForTests(level, v);
                Kit.log("sp07 " + c);
                helper.assertTrue(c.contains("archery contest is on"), "the contest called: " + c);
                phase[0] = 1;
                since[0] = t;
                return;
            }
            if (phase[0] == 1) {
                // Every entrant shoots its six once it is free (one at the leader's shoulder comes when it is let go);
                // the contest is judged when all have, or after a while with whoever has.
                if (!Archery.contestDoneForTests(id) && t - since[0] < 2000) {
                    if (t % 200 == 0) Kit.log("sp07 the contest, tick " + t + ": " + g1.hobbyNow() + " / " + g2.hobbyNow());
                    return;
                }
                String result = Archery.resultForTests(level, id);
                Kit.log("sp07 the contest: " + result + "; the chronicle: " + chronicle(id));
                helper.assertTrue(result != null && (told(id, "won the archery contest") || told(id, "nobody found the butts")), "the contest in the chronicle");
                phase[0] = 2;
                since[0] = t;
                return;
            }
            // The arrows counted once nobody is at the butts (a session still open when the contest was judged
            // finishes, and pulls its arrows, first).
            if ((Archery.sessionForTests(g1.getUUID()) || Archery.sessionForTests(g2.getUUID())) && t - since[0] < 800) return;
            Kit.log("sp07 arrows after the contest " + arrows[0] + " -> " + stock(level, id, Items.ARROW));
            helper.assertTrue(stock(level, id, Items.ARROW) >= arrows[0] - 4, "the contest's arrows back in the stores, near enough all");
            helper.succeed();
        });
    }
}
