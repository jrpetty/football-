package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Park;
import com.jrpetty.mcassistant.entity.ParkGround;
import com.jrpetty.mcassistant.entity.Quarters;
import com.jrpetty.mcassistant.entity.Terraform;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Districts.District;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * The town's quarters and its park (Districts, Quarters, Park): the plan puts the crafts and the homes
 * in quarters of their own, an old town's smeltery decides where its crafts go, a home beside a
 * working smeltery is the gloomier and the cheaper while one by the park is the happier and the
 * dearer, the builders put the park up out of what they carry and its keepers plant it and lay its
 * paths, and folk off work of an evening go and sit in it. A park on rough ground gets a level lawn, and
 * a fountain that keeps its water.
 *
 * <p>All between x 280000 and 287000, z 50000, each on its own ground in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DistrictGameTests {

    private static final String EMPTY = "empty";

    /** Level grass this far round the heart, open sky over it, whatever the world put there. */
    private static void flatten(ServerLevel level, BlockPos heart, int r) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        int y = heart.getY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                for (int dy = -3; dy <= 20; dy++) {
                    BlockState want = dy == -1 ? grass : dy < -1 ? dirt : air;
                    p.set(x, y + dy, z);
                    if (level.getBlockState(p) != want) level.setBlock(p, want, 2);
                }
            }
        }
    }

    /** A bed whose head is here, lying north. */
    private static void bed(ServerLevel level, BlockPos head) {
        BlockState b = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(head.south(), b.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(head, b.setValue(BedBlock.PART, BedPart.HEAD), 3);
    }

    /** A marked store chest here, holding these. */
    private static void stores(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length && box != null; i++) box.setItem(i, goods[i]);
    }

    /** The lots of a list by where they are and what they are (a lot's cells are an array: not its identity). */
    private static java.util.Set<String> places(List<TownPlan.Lot> lots) {
        java.util.Set<String> out = new HashSet<>();
        for (TownPlan.Lot l : lots) out.add(l.x() + "," + l.z() + "," + l.kind() + "," + l.use());
        return out;
    }

    private static District at(UUID village, BlockPos heart, BlockPos p) {
        return Quarters.districtOf(village, heart, p);
    }

    private static void clean(ServerLevel level) {
        Kit.reset(level);
        Quarters.resetForTests();
        Park.resetForTests();
    }

    // ============================================================ the plan's quarters

    /**
     * A village whose fields lie to the east: its crafts go on a side of their own (not the fields'),
     * the plan offers a smeltery a lot in the craft quarter first and a house one in the homes quarter,
     * every kind of building is still offered every lot it was (only the order changes), and on the
     * ground the builders' own choice puts the smeltery and the first house in different quarters, a
     * street or more apart. An old town whose smeltery already stands north of the square has its
     * craft quarter there, and its next smithy goes beside it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "dt01_quarters")
    public static void dt01_quarters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(6000);
        int x = 280000, z = 50000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        flatten(level, heart, 56);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID village = first.ownerId();
        helper.runAtTickTime(20, () -> {
            Kit.Expect e = new Kit.Expect();
            Villages.setFieldsSide(village, TownPlan.EAST);
            int craft = Quarters.craftSide(village);
            Kit.log("dt01 fields to the east; the crafts to the " + Districts.sideWord(craft) + ": " + Quarters.planLine(village));
            e.that(craft >= 0 && craft != TownPlan.EAST, "the crafts have a side of their own, not the fields': " + craft);
            e.that(Quarters.craftSide(village) == craft, "and keep it");

            // The plan's offer, before any ground is looked at.
            List<TownPlan.Lot> forSmeltery = Quarters.candidates(village, "smeltery");
            List<TownPlan.Lot> forHouse = Quarters.candidates(village, "house");
            List<TownPlan.Lot> forMarket = Quarters.candidates(village, "market");
            District s0 = Districts.of(forSmeltery.get(0), TownPlan.EAST, craft);
            District h0 = Districts.of(forHouse.get(0), TownPlan.EAST, craft);
            District m0 = Districts.of(forMarket.get(0), TownPlan.EAST, craft);
            Kit.log("dt01 first lots offered: smeltery " + forSmeltery.get(0) + " (" + s0 + "), house " + forHouse.get(0) + " (" + h0
                + "), market " + forMarket.get(0) + " (" + m0 + ")");
            e.that(s0 == District.CRAFTS, "a smeltery is offered the craft quarter first: " + s0);
            e.that(h0 == District.HOMES, "a house is offered the homes quarter first: " + h0);
            e.that(m0 == District.MARKET && "civic".equals(forMarket.get(0).use()), "the market a lot facing the square: " + forMarket.get(0));
            e.that(!"civic".equals(forSmeltery.get(0).use()), "the crafts no longer take the market's lots by the square");
            for (String kind : new String[]{ "smeltery", "smithy", "house", "market", "cafe", "park", "hall", "watchtower", "lighthouse", "well" }) {
                List<TownPlan.Lot> plan = TownPlan.candidates(kind), ours = Quarters.candidates(village, kind);
                e.that(plan.size() == ours.size() && places(plan).equals(places(ours)),
                    "every lot the plan offers a " + kind + " is still offered (" + ours.size() + " of " + plan.size() + ")");
            }

            // The builders' own choice, on the ground.
            Villages.Site smeltery = Villages.siteFor(level, village, "smeltery");
            Villages.Site house = Villages.siteFor(level, village, "house");
            Kit.log("dt01 the builders chose: smeltery " + (smeltery == null ? "nothing" : smeltery.anchor().toShortString() + " "
                + at(village, heart, smeltery.anchor())) + "; house " + (house == null ? "nothing" : house.anchor().toShortString() + " "
                + at(village, heart, house.anchor())) + "; " + Villages.lotReport(village));
            e.that(smeltery != null && house != null, "a lot for each");
            if (smeltery != null && house != null) {
                District sd = at(village, heart, smeltery.anchor()), hd = at(village, heart, house.anchor());
                e.that(sd == District.CRAFTS, "the smeltery goes up in the craft quarter: " + sd);
                e.that(hd == District.HOMES, "the house in the homes quarter: " + hd);
                e.that(sd != hd, "in different quarters");
                double apart = Math.sqrt(smeltery.anchor().distSqr(house.anchor()));
                e.that(apart > Quarters.SMOKE_REACH, "the house out of the smeltery's smoke: " + Math.round(apart) + " blocks apart");
            }

            // An old town: its smeltery stands on the lot north of the square, from before there were quarters.
            UUID old = UUID.randomUUID();
            BlockPos oldHeart = new BlockPos(x + 900, heart.getY(), z);
            Villages.restore(level, old, oldHeart, Villages.Age.STONE, List.of("storage", "smeltery", "house"), 14);
            Ledger.built(old, "smeltery", oldHeart.offset(-8, 0, -22), Direction.NORTH);
            int oldCraft = Quarters.craftSide(old);
            TownPlan.Lot smithy = Quarters.candidates(old, "smithy").get(0);
            Kit.log("dt01 an old town with its smeltery north of the square: crafts to the " + Districts.sideWord(oldCraft)
                + ", its smithy offered " + smithy);
            e.that(oldCraft == TownPlan.NORTH, "an old town's crafts go where its smeltery already stands: " + oldCraft);
            e.that(Districts.of(smithy, -1, oldCraft) == District.CRAFTS && smithy.z() < 0, "and its smithy beside it, north: " + smithy);
            e.that(Districts.isIndustry("tannery") && Districts.forBuilding("bank") != District.CRAFTS,
                "a new trade's works fall into the craft quarter by name, a bank does not");
            helper.assertTrue(e.clean(), e.summary());
            helper.succeed();
        });
    }

    // ============================================================ smoke, noise and the park

    /**
     * Three folk: one sleeps beside a smeltery whose furnace is lit, one beside the park, one well away
     * from both. The first is the gloomier for the smoke (and says so), the second the cheerier for the
     * park, the third neither; a house in the smoke sells and lets for less, one by the park for more.
     * The smelter itself does not mind its own furnace.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "dt02_smoke_and_park")
    public static void dt02_smoke_and_park(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(6000);
        int x = 281500, z = 50000;
        Kit.hold(level, x, z, 72);
        Kit.prepare(level, x, z, 72);
        BlockPos heart = Kit.surface(level, x, z);
        flatten(level, heart, 64);
        VillageFolkEntity smoky = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(smoky != null, "a village");
        UUID village = smoky.ownerId();
        VillageFolkEntity parkside = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        VillageFolkEntity away = VillageFolkSpawnerBlock.raise(level, heart.west(2), 0.0F);
        VillageFolkEntity smelter = VillageFolkSpawnerBlock.raise(level, heart.south(2), 0.0F);
        helper.assertTrue(parkside != null && away != null && smelter != null, "four folk");
        helper.runAtTickTime(20, () -> {
            Kit.Expect e = new Kit.Expect();
            Villages.Village v = Villages.get(village);
            // A smeltery forty out to the south, its furnace lit: coal and ore in it.
            BlockPos s = heart.offset(0, 0, 40);
            Ledger.built(village, "smeltery", s, Direction.SOUTH);
            BlockPos furnace = null;
            for (BuildGoal.Placement p : BuildGoal.plan("smeltery", s, Direction.SOUTH, 13)) {
                if (p.part() == BuildGoal.Part.FURNACE) { furnace = p.pos(); break; }
            }
            helper.assertTrue(furnace != null, "a smeltery's drawing has its furnaces");
            level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
            if (level.getBlockEntity(furnace) instanceof Container c) {
                c.setItem(0, new ItemStack(Items.RAW_IRON, 16));
                c.setItem(1, new ItemStack(Items.COAL, 8));
            }
            // A park forty out to the north.
            BlockPos park = heart.offset(0, 0, -40);
            Ledger.built(village, Park.STRUCTURE, park, Direction.NORTH);
            // Their beds: beside the smeltery, beside the park, out west away from both.
            BlockPos bedSmoke = s.offset(8, 0, 0), bedPark = park.offset(8, 0, 0), bedAway = heart.offset(-40, 0, 0);
            BlockPos bedSmelter = s.offset(-8, 0, 0);
            for (BlockPos b : new BlockPos[]{ bedSmoke, bedPark, bedAway, bedSmelter }) bed(level, b);
            smoky.claimBedNear(bedSmoke);
            parkside.claimBedNear(bedPark);
            away.claimBedNear(bedAway);
            smelter.claimBedNear(bedSmelter);
            smoky.setJob(StationTask.FARM);
            parkside.setJob(StationTask.FARM);
            away.setJob(StationTask.FARM);
            smelter.setJob(StationTask.SMELT);
            BlockPos furnaceAt = furnace;
            helper.runAfterDelay(40, () -> {
                boolean lit = level.getBlockState(furnaceAt).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
                Quarters.scan(level, v);
                Kit.log("dt02 the furnace lit " + lit + "; at work: " + Quarters.sources(village));
                e.that(lit, "the smeltery's furnace is lit");
                e.that(!Quarters.sources(village).isEmpty(), "the smeltery counts as at work");
                List<Object[]> whySmoke = new ArrayList<>(), whyPark = new ArrayList<>(), whyAway = new ArrayList<>(), whySmelter = new ArrayList<>();
                int mSmoke = Quarters.mood(smoky, 60, whySmoke), mPark = Quarters.mood(parkside, 60, whyPark);
                int mAway = Quarters.mood(away, 60, whyAway), mSmelter = Quarters.mood(smelter, 60, whySmelter);
                Kit.log("dt02 spirits from 60: by the smeltery " + mSmoke + " " + keys(whySmoke) + ", by the park " + mPark + " "
                    + keys(whyPark) + ", away " + mAway + " " + keys(whyAway) + ", the smelter " + mSmelter + " " + keys(whySmelter));
                e.that(mSmoke < mAway && keys(whySmoke).contains("smoke"), "the smoke lowers the spirits of who sleeps by it: " + mSmoke);
                e.that(mPark > mAway && keys(whyPark).contains("parkside"), "the park lifts those of who lives by it: " + mPark);
                e.that(mAway == 60 && whyAway.isEmpty(), "and neither touches the one away from both: " + mAway);
                e.that(!keys(whySmelter).contains("smoke"), "the smelter does not mind its own furnace");
                String says = Quarters.words(smoky, "smoke"), card = Quarters.cardLine(smoky), parkCard = Quarters.cardLine(parkside);
                Kit.log("dt02 it says: \"" + says + "\"; its card: " + card + " | the other's: " + parkCard);
                e.that(!says.isEmpty() && says.toLowerCase().contains("smoke"), "it gives the smoke as its reason: " + says);
                e.that(card.contains("smeltery"), "its card says it lives by the smeltery: " + card);
                e.that(parkCard.contains("park"), "the other's card says it lives by the park: " + parkCard);
                // The folk's own spirits, as the village works them out.
                smoky.refreshMood();
                Kit.log("dt02 by the smeltery, refreshed: " + smoky.persona().mood() + " " + smoky.persona().moodWhy());
                if (smoky.persona().rolled()) e.that(smoky.persona().moodWhy().contains("smoke") || smoky.persona().moodWhy().size() >= 3,
                    "the smoke is among its reasons: " + smoky.persona().moodWhy());
                // The houses' prices and rents.
                int pSmoke = Quarters.homePercent(village, bedSmoke), pPark = Quarters.homePercent(village, bedPark),
                    pAway = Quarters.homePercent(village, bedAway);
                int[] tSmoke = Quarters.termsForTests(village, bedSmoke), tPark = Quarters.termsForTests(village, bedPark),
                    tAway = Quarters.termsForTests(village, bedAway);
                Kit.log("dt02 a house in the smoke " + pSmoke + "% (" + tSmoke[0] + "c, " + tSmoke[1] + "c a day), by the park " + pPark
                    + "% (" + tPark[0] + "c, " + tPark[1] + "c), away " + pAway + "% (" + tAway[0] + "c, " + tAway[1] + "c)");
                e.that(pSmoke < 100 && pPark > 100 && pAway == 100, "a house is worth less in the smoke and more by the park");
                e.that(tSmoke[0] < tAway[0] && tAway[0] < tPark[0], "and its price says so: " + tSmoke[0] + " < " + tAway[0] + " < " + tPark[0]);
                helper.assertTrue(e.clean(), e.summary());
                helper.succeed();
            });
        });
    }

    private static List<String> keys(List<Object[]> why) {
        List<String> out = new ArrayList<>();
        for (Object[] w : why) out.add((String) w[0]);
        return out;
    }

    // ============================================================ the park goes up

    /**
     * A Stone Age town of twenty-two wants a park, and the plan gives it a lot in the homes quarter. The
     * village raises it the way it raises anything: its own hand takes the project up (the park asked for
     * first, the rest of its list set aside), draws the makings out of the stores (stone, logs, planks
     * cut to the benches' stairs, torches for the lamps, flowers, buckets filled at the pond), and puts it
     * up: the fountain's basin, its pillar and its water, the benches, the lamps. Then its keepers, out
     * of the stores, plant a tree in its corners and lay its paths.
     *
     * <p>Its lot is made rough first: a bank of earth two and three high over half of it (and out past its
     * edge), and a hollow three deep under one corner (out past its edge too). The builder cuts the bank
     * away and fills the hollow before a stone is laid, so the lawn is level and firm all over; the bank
     * round it is eased to steps of one and two, and the keepers bank the hollow round it up the same way.
     * A while after, not a drop of the fountain's water is anywhere but in the fountain.
     *
     * <p>The clock runs from mid-morning to the afternoon and on to the next mid-morning: no morning
     * assembly (it calls everybody, and what each was doing waits), no dawn bell, no night.
     *
     * <p>The town is one folk with a roll of twenty-two: twenty-two mouths and no fields make it hungry, and
     * its one hand a farmer out at a plot fifty blocks from the heart, with work of its own all day. Nobody
     * of a real town's twenty-two (its storekeeper, a hand worked out or between jobs) is idle at the heart
     * to look at the village's work (considerVillageWork is the agenda's last word, for a hand with nothing
     * of its own in hand, within forty blocks of the heart), so the test has its one hand do as such a one
     * would: at the stores now and then, it looks at the village's work by the real path (the project, its
     * lot, whether the stores pay, stocking up, setting off).
     */
    @GameTest(template = EMPTY, timeoutTicks = 20400, batch = "dt03_park_goes_up")
    public static void dt03_park_goes_up(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        long day0 = level.getDayTime() / 24000L + 1;
        level.setDayTime(day0 * 24000L + DAY_FROM);
        int x = 283000, z = 50000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        flatten(level, heart, 56);
        Kit.pond(level, x + 20, z + 20, 2);                                     // water for the fountain, by the heart
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "a village");
        UUID village = builder.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.STONE);
        Villages.restore(level, village, heart, Villages.Age.STONE, List.of(), 22);
        Villages.setFieldsSide(village, TownPlan.EAST);
        Kit.Expect e = new Kit.Expect();
        boolean wanted = Park.wanted(village, Villages.headcount(village));
        List<String> list = Villages.projectsWanted(village);
        Kit.log("dt03 a town of " + Villages.headcount(village) + " wants: " + list);
        e.that(wanted && list.contains(Park.STRUCTURE), "a Stone Age town of twenty-two wants a park");
        e.that(!Park.wanted(village, Park.FOLK - 1), "a town of nineteen does not yet");
        // The park asked for first (as a player asks the elder: Asks), and the rest of the list set aside,
        // so nothing else is raised out of its makings meanwhile.
        Villages.request(village, Park.STRUCTURE);
        long now = level.getGameTime();
        for (String p : list) if (!p.equals(Park.STRUCTURE)) Villages.defer(village, p, now + 10_000_000L, "the test asks for the park");
        Kit.log("dt03 next to build: " + Villages.nextProject(village));
        e.that(Park.STRUCTURE.equals(Villages.nextProject(village)), "the park is the next thing to build");
        Villages.Site site = Villages.siteFor(level, village, Park.STRUCTURE);
        helper.assertTrue(site != null, "a lot for the park: " + Villages.lotReport(village));
        District d = at(village, heart, site.anchor());
        Kit.log("dt03 the park's lot: " + site.anchor().toShortString() + " facing " + site.facing() + ", " + d);
        e.that(d == District.HOMES, "the park goes among the homes: " + d);
        // Its lot made rough: a bank over half of it, a hollow under a corner.
        BlockPos a = site.anchor();
        rough(level, a);
        Kit.log("dt03 the lot made rough: " + groundLine(level, a));
        // The makings, in the stores at the heart.
        stores(level, heart.offset(3, 0, 3), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.TORCH, 16), new ItemStack(Items.POPPY, 12), new ItemStack(Items.BUCKET, 16));
        Villages.forgetStores(village);
        Villages.forgetStock();
        Park.Layout l = Park.layout(site.anchor(), site.facing());
        // A hand of the village with a trade of its own (so the town's hunger puts nothing down when it takes
        // one up), at the stores, looking at the village's work.
        builder.setJob(StationTask.FARM);
        long[] looked = { level.getGameTime() };
        long[] stoodAt = { -1L };
        lookAtTheWork(level, village, heart, builder, "at the start");
        helper.onEachTick(() -> {
            long t = helper.getTick();
            // A working day, mid-morning to the afternoon, over and over (see above).
            level.setDayTime((day0 + t / DAY_SPAN) * 24000L + DAY_FROM + t % DAY_SPAN);
            if (t % 600 == 0) {
                Kit.log("dt03 @" + t + " built=" + Villages.builtList(village) + ", next " + Villages.nextProject(village)
                    + ", the park's site " + Villages.sitesOf(village).get(Park.STRUCTURE) + " — " + builder.debugLine());
            }
            // Anything else the village comes to want meanwhile waits for the park too.
            String next = Villages.nextProject(village);
            if (next != null && !next.equals(Park.STRUCTURE) && !Villages.builtList(village).contains(Park.STRUCTURE)) {
                Villages.defer(village, next, level.getGameTime() + 10_000_000L, "the test asks for the park");
                Kit.log("dt03 @" + t + " set aside " + next + ", which came before the park");
            }
            if (!Villages.builtList(village).contains(Park.STRUCTURE)) {
                // Not at it (the stores not yet drawn, a run that ran short, its plot calling it back): it looks at
                // the village's work again, as an idle hand at the heart does, now and then.
                Job j = builder.peekJob();
                boolean building = j != null && j.type() == Job.Type.BUILD;
                if (!building && level.getGameTime() - looked[0] >= 400) {
                    looked[0] = level.getGameTime();
                    lookAtTheWork(level, village, heart, builder, "@" + t);
                }
                if (t >= 20000) helper.fail("the park was not built: " + Villages.builtList(village) + ", next " + next + " — "
                    + builder.debugLine());
                return;
            }
            if (stoodAt[0] >= 0) {
                // A while after: the fountain's water is in the fountain and nowhere else.
                if (t < stoodAt[0] + 400) return;
                List<String> stray = strayWater(level, a);
                Kit.log("dt03 " + (t - stoodAt[0]) + " ticks after: water outside the fountain " + stray + "; " + groundLine(level, a));
                e.that(stray.isEmpty(), "no water outside the fountain: " + stray);
                helper.assertTrue(e.clean(), e.summary());
                helper.succeed();
                return;
            }
            stoodAt[0] = t;
            // Its ground: the lawn level and firm all over, no earth left standing on it.
            List<String> lawn = lawnFaults(level, a);
            Kit.log("dt03 the lawn: " + (lawn.isEmpty() ? "level and firm" : lawn) + " — " + groundLine(level, a));
            e.that(lawn.size() <= 2, "the lawn is level and firm: " + lawn);
            int seats = 0, water = 0, stone = 0, lights = 0;
            for (Park.Seat s : l.seats()) if (level.getBlockState(s.at()).getBlock() instanceof StairBlock) seats++;
            for (BlockPos p : l.water()) if (level.getFluidState(p).is(FluidTags.WATER) && level.getFluidState(p).isSource()) water++;
            for (BlockPos p : l.stone()) if (!level.getBlockState(p).isAir()) stone++;
            for (BlockPos p : l.lights()) if (!level.getBlockState(p).isAir()) lights++;
            Kit.log("dt03 the park stands at " + t + ": " + seats + "/" + l.seats().size() + " bench seats, water " + water + "/"
                + l.water().size() + ", stone " + stone + "/" + l.stone().size() + ", lights " + lights + "/" + l.lights().size()
                + " (" + lamps(level, l) + "); in the ledger: " + Park.parks(village).size() + "; torches left in the builder's pack "
                + builder.countMatching(st -> st.is(Items.TORCH)) + " — " + builder.debugLine());
            e.that(seats >= l.seats().size() - 2, "the benches are down: " + seats);
            e.that(stone >= l.stone().size() - 2, "the fountain's stone is laid: " + stone);
            // The builder carries the lamps' torches to the lot and puts them up (it used to drop them on the grass on
            // its way, to light the ground it walked over); one or two may wait for its keepers (every one is lit below).
            e.that(lights >= l.lights().size() - 2, "the lamps are lit: " + lights);
            e.that(!Park.parks(village).isEmpty(), "the park is in the village's register");
            // Its keepers: saplings, a bucket and flowers in the stores; the water, the trees and the
            // paths a visit at a time (the builder's water, if it had none, from the pond).
            stores(level, heart.offset(-3, 0, 3), new ItemStack(Items.OAK_SAPLING, 6), new ItemStack(Items.BIRCH_SAPLING, 2),
                new ItemStack(Items.BUCKET, 2), new ItemStack(Items.DANDELION, 8), new ItemStack(Items.DIRT, 64));
            Villages.forgetStores(village);
            Villages.forgetStock();
            List<String> done = new ArrayList<>();
            Ledger.Building b = Park.parks(village).get(0);
            for (int i = 0; i < 40; i++) {
                String what = Park.tendOne(level, v, b, 8);
                if (what == null) break;
                done.add(what);
            }
            int trees = 0, paths = 0;
            water = 0;
            for (BlockPos p : l.trees()) {
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof SaplingBlock || st.is(BlockTags.LOGS)) trees++;
            }
            for (BlockPos p : l.paths()) if (level.getBlockState(p).is(Blocks.DIRT_PATH)) paths++;
            for (BlockPos p : l.water()) if (level.getFluidState(p).is(FluidTags.WATER) && level.getFluidState(p).isSource()) water++;
            int lit = 0;
            for (BlockPos p : l.lights()) if (!level.getBlockState(p).isAir()) lit++;
            Kit.log("dt03 the keepers: " + done + "; trees " + trees + "/" + l.trees().size() + ", path " + paths + "/" + l.paths().size()
                + ", water " + water + "/" + l.water().size() + ", lamps " + lit + "/" + l.lights().size() + " (" + lamps(level, l) + "); "
                + Park.status(level, v));
            e.that(lit == l.lights().size(), "every lamp lit: " + lit + " (" + lamps(level, l) + ")");
            e.that(water >= l.water().size() - 1, "the fountain holds water: " + water);
            e.that(trees >= 2, "trees planted in its corners: " + trees);
            e.that(paths >= l.paths().size() / 2, "its paths laid: " + paths);
            // The ground round it in steps: none more than a block above or below the lawn a step out, two at two.
            List<String> edges = edgeFaults(level, a);
            Kit.log("dt03 the ground round it: " + (edges.isEmpty() ? "in steps" : edges));
            e.that(edges.size() <= 2, "the ground round it eased in steps: " + edges);
        });
    }

    /** The test's working day: from mid-morning, so long, then the next day's mid-morning. */
    private static final long DAY_FROM = 2000L, DAY_SPAN = 9000L;

    /** A hand at the stores by the heart looks at the village's work, by the real path (considerVillageWork). */
    private static void lookAtTheWork(ServerLevel level, UUID village, BlockPos heart, VillageFolkEntity hand, String when) {
        if (hand.blockPosition().distSqr(heart) > 5 * 5) hand.teleportTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 1.5);
        Villages.retryIn(village, level.getGameTime(), 0L);
        boolean set = hand.villageWorkForTests();
        Kit.log("dt03 " + when + " a hand at the stores looks at the village's work: set off " + set + ", next "
            + Villages.nextProject(village) + ", set aside " + Villages.setAside(village) + " — " + hand.debugLine());
    }

    /** A lot made rough (dt03): a bank of earth two high, then three, over the half of it toward +z and out past
     *  its edge; a hollow three deep (on earth) under its corner toward -x, -z, out past its edge too. */
    private static void rough(ServerLevel level, BlockPos a) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = 2; dz <= 7; dz++) {
                int high = dz <= 4 ? 2 : 3;
                level.setBlock(a.offset(dx, -1, dz), dirt, 2);
                for (int dy = 0; dy < high; dy++) level.setBlock(a.offset(dx, dy, dz), dy == high - 1 ? grass : dirt, 2);
            }
        }
        for (int dx = -7; dx <= -2; dx++) {
            for (int dz = -7; dz <= -2; dz++) {
                level.setBlock(a.offset(dx, -4, dz), dirt, 2);
                for (int dy = -3; dy <= -1; dy++) level.setBlock(a.offset(dx, dy, dz), air, 2);
            }
        }
    }

    /** The first free block over a column's firm ground, looking down from {@code from} (through trees and plants). */
    private static int firstFree(ServerLevel level, int x, int z, int from) {
        for (int y = from; y > level.getMinBuildHeight(); y--) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            if (st.blocksMotion() && !st.is(BlockTags.LEAVES) && !st.is(BlockTags.LOGS) && !(st.getBlock() instanceof SaplingBlock)) return y + 1;
        }
        return level.getMinBuildHeight();
    }

    /** The ground's height (first free block, against the lawn's) along the lot's middle row and across it, for the log. */
    private static String groundLine(ServerLevel level, BlockPos a) {
        StringBuilder sb = new StringBuilder("across");
        for (int d = -7; d <= 7; d++) sb.append(' ').append(firstFree(level, a.getX() + d, a.getZ() - 4, a.getY() + 12) - a.getY());
        sb.append("; along");
        for (int d = -7; d <= 7; d++) sb.append(' ').append(firstFree(level, a.getX() - 4, a.getZ() + d, a.getY() + 12) - a.getY());
        return sb.toString();
    }

    /** What is wrong with a park's lawn: a column with no firm ground under it, or earth standing on it. */
    private static List<String> lawnFaults(ServerLevel level, BlockPos a) {
        List<String> out = new ArrayList<>();
        for (int dx = -ParkGround.HALF; dx <= ParkGround.HALF; dx++) {
            for (int dz = -ParkGround.HALF; dz <= ParkGround.HALF; dz++) {
                if (!level.getBlockState(a.offset(dx, -1, dz)).blocksMotion()) out.add(dx + "," + dz + " hollow");
                for (int dy = 0; dy <= 3; dy++) {
                    if (Terraform.earth(level.getBlockState(a.offset(dx, dy, dz)))) { out.add(dx + "," + dz + " earth at +" + dy); break; }
                }
            }
        }
        return out;
    }

    /** The ground round a park out of step: a column a step out more than one block above or below the lawn, two out
     *  more than two (with its height against the lawn's). */
    private static List<String> edgeFaults(ServerLevel level, BlockPos a) {
        List<String> out = new ArrayList<>();
        int reach = ParkGround.HALF + ParkGround.EDGE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int k = ParkGround.ring(a, a.getX() + dx, a.getZ() + dz);
                if (k == 0) continue;
                int h = firstFree(level, a.getX() + dx, a.getZ() + dz, a.getY() + 12) - a.getY();
                if (h < -k || h > k) out.add(dx + "," + dz + ":" + h);
            }
        }
        return out;
    }

    /** Water round a park that is not in its fountain (the basin, its pillar and the spring's fall). */
    private static List<String> strayWater(ServerLevel level, BlockPos a) {
        List<String> out = new ArrayList<>();
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                for (int dy = -8; dy <= 4; dy++) {
                    boolean fountain = Math.abs(dx) <= ParkGround.BASIN && Math.abs(dz) <= ParkGround.BASIN && dy >= 0 && dy <= 2;
                    if (!fountain && !level.getFluidState(a.offset(dx, dy, dz)).isEmpty()) out.add(dx + "," + dy + "," + dz);
                }
            }
        }
        return out;
    }

    /** Each lamp of a park, what is on it and what it stands on, for the log. */
    private static String lamps(ServerLevel level, Park.Layout l) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : l.lights()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name(level.getBlockState(p))).append(" on ").append(name(level.getBlockState(p.below())));
        }
        return sb.toString();
    }

    private static String name(BlockState st) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }

    /** Its fountain's water that is standing water (a source), of all of it. */
    private static int fountainFull(ServerLevel level, Park.Layout l) {
        int n = 0;
        for (BlockPos p : l.water()) if (level.getFluidState(p).is(FluidTags.WATER) && level.getFluidState(p).isSource()) n++;
        return n;
    }

    // ============================================================ a park on a hillside, its fountain kept tight

    /**
     * A park put up at once (the photographs' way: Park.putUp) on a hillside that rises a block every three to
     * the east and drops four more off its south-west corner: its lawn is laid at the middle height of the
     * ground, cut and filled level and firm, and the ground round it eased and banked in steps. Its fountain is
     * full and not a drop of its water is anywhere else. Then a stone of its rim is knocked out: the fountain is
     * emptied before its water can run anywhere (and the little that ran dries up); its keepers put the stone
     * back out of the stores and fill it again from the pond, and it holds.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "dt05_park_on_a_hillside")
    public static void dt05_park_on_a_hillside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(5000);
        int x = 286000, z = 50000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        flatten(level, heart, 56);
        Kit.pond(level, x - 15, z + 15, 2);                                      // a spring for its keepers' buckets
        VillageFolkEntity founder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(founder != null, "a village");
        UUID village = founder.ownerId();
        Villages.Village v = Villages.get(village);
        // The hillside, well east of the heart (clear of the village's own works).
        BlockPos spot = heart.offset(40, 0, 0);
        int base = heart.getY() + 6;
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                int free = base + Math.floorDiv(dx, 3) - (dx <= -2 && dz >= 2 ? 4 : 0);
                for (int y = heart.getY() - 3; y <= base + 14; y++) {
                    level.setBlock(new BlockPos(spot.getX() + dx, y, spot.getZ() + dz), y < free - 1 ? dirt : y == free - 1 ? grass : air, 2);
                }
            }
        }
        BlockPos ground = new BlockPos(spot.getX(), BuildGoal.groundTop(level, spot.getX(), spot.getZ()), spot.getZ());
        BlockPos a = ParkGround.floorFor(level, Park.STRUCTURE, ground);
        Kit.log("dt05 the hillside: " + groundLine(level, a) + "; the lawn at " + a.toShortString() + " (" + (a.getY() - base)
            + " against the hill's middle), roughness " + ParkGround.roughness(level, Park.STRUCTURE, a));
        helper.assertTrue(a.getY() == base, "the lawn at the middle height of the hillside: " + (a.getY() - base));
        Ledger.Building b = Park.putUp(level, village, a, Direction.NORTH);
        Park.Layout l = Park.layout(b);
        List<String> lawn = lawnFaults(level, a), edges = edgeFaults(level, a);
        Kit.log("dt05 put up: lawn " + (lawn.isEmpty() ? "level and firm" : lawn) + "; round it " + (edges.isEmpty() ? "in steps" : edges)
            + "; the fountain " + fountainFull(level, l) + "/" + l.water().size() + ", tight " + ParkGround.sound(level, l)
            + " — " + groundLine(level, a));
        Kit.Expect e = new Kit.Expect();
        e.that(lawn.isEmpty(), "the lawn is level and firm: " + lawn);
        e.that(edges.isEmpty(), "the ground round it in steps: " + edges);
        e.that(ParkGround.sound(level, l), "the fountain's basin is tight");
        BlockPos rim = l.centre().offset(ParkGround.BASIN, 0, 0);
        long[] knocked = { -1L };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (knocked[0] < 0) {
                if (t < 300) return;
                // Full, and not a drop of it anywhere else.
                List<String> stray = strayWater(level, a);
                int full = fountainFull(level, l);
                Kit.log("dt05 @" + t + ": the fountain " + full + "/" + l.water().size() + ", water outside it " + stray);
                e.that(full == l.water().size(), "the fountain is full: " + full);
                e.that(stray.isEmpty(), "no water outside the fountain: " + stray);
                // A stone of its rim knocked out.
                level.setBlock(rim, Blocks.AIR.defaultBlockState(), 3);
                knocked[0] = t;
                Kit.log("dt05 @" + t + " a stone of the rim knocked out at " + rim.toShortString());
                return;
            }
            long since = t - knocked[0];
            if (since == 40) {
                int full = fountainFull(level, l);
                Kit.log("dt05 @" + t + ": the fountain " + full + "/" + l.water().size() + " (emptied, it would not hold)");
                e.that(full == 0, "the leaking fountain is emptied at once: " + full + " still in it");
            } else if (since == 160) {
                List<String> stray = strayWater(level, a);
                Kit.log("dt05 @" + t + ": water outside the fountain " + stray);
                e.that(stray.isEmpty(), "what ran out has dried up: " + stray);
                // Its keepers: a stone and buckets in the stores.
                stores(level, heart.offset(3, 0, 3), new ItemStack(Items.STONE_BRICKS, 8), new ItemStack(Items.BUCKET, 4));
                Villages.forgetStores(village);
                Villages.forgetStock();
                List<String> done = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    String what = Park.tendOne(level, v, b, 8);
                    if (what == null) break;
                    done.add(what);
                }
                int full = fountainFull(level, l);
                Kit.log("dt05 the keepers: " + done + "; the rim " + level.getBlockState(rim) + ", the fountain " + full + "/"
                    + l.water().size() + ", tight " + ParkGround.sound(level, l));
                e.that(level.getBlockState(rim).blocksMotion(), "the rim's stone is put back");
                e.that(full == l.water().size(), "the fountain is filled again: " + full);
            } else if (since == 460) {
                List<String> stray = strayWater(level, a);
                Kit.log("dt05 @" + t + ": the fountain " + fountainFull(level, l) + "/" + l.water().size() + ", water outside it " + stray);
                e.that(stray.isEmpty(), "and it holds: " + stray);
                e.that(fountainFull(level, l) == l.water().size(), "still full");
                helper.assertTrue(e.clean(), e.summary());
                helper.succeed();
            }
        });
    }

    // ============================================================ an evening in the park

    /**
     * Six folk who live by a park, of an evening off work: on an evening when some of them fancy the park
     * (their natures, their homes, the day), they walk over, sit on its benches and stroll its paths, and
     * their cards say so. A child goes there of an afternoon to play.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6800, batch = "dt04_evening_in_the_park")
    public static void dt04_evening_in_the_park(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(6000);
        int x = 284500, z = 50000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        flatten(level, heart, 56);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.offset(i - 3, 0, 1), 0.0F);
            if (f != null) folk.add(f);
        }
        helper.assertTrue(folk.size() == 6, "six folk: " + folk.size());
        UUID village = folk.get(0).ownerId();
        Villages.Village v = Villages.get(village);
        // The park, put up at once on a lot of the homes quarter (the photographs' way), its paths laid.
        Villages.Site site = Villages.siteFor(level, village, Park.STRUCTURE);
        helper.assertTrue(site != null, "a lot for the park: " + Villages.lotReport(village));
        BuildGoal.stamp(level, Park.STRUCTURE, site.anchor(), site.facing(), 0, Showcase.painter(Showcase.OAK));
        Ledger.built(village, Park.STRUCTURE, site.anchor(), site.facing());
        Ledger.Building park = Park.parks(village).get(0);
        Park.grow(level, park);
        Park.Layout l = Park.layout(park);
        // Their beds, along the street beside it.
        Direction right = site.facing().getClockWise();
        for (int i = 0; i < folk.size(); i++) {
            BlockPos head = site.anchor().relative(right, 8).relative(site.facing(), 4 - 2 * i);
            bed(level, head);
            folk.get(i).claimBedNear(head);
        }
        int[] best = { 0, 0 };
        String[] card = { "" };
        // -1: the afternoon, while they settle in (their natures are rolled); 0: the evening; 1: the
        // afternoon after, a child's (from tick start[0]).
        int[] phase = { -1 };
        long[] start = { 0L };
        long[] evenings = { 0L };
        VillageFolkEntity[] kid = { null };
        long[] kidDay = { 0L };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == -1) {
                if (t < 200) return;
                // An evening when at least two of them fancy the park.
                long day = level.getDayTime() / 24000L;
                int going = 0;
                for (long d = day + 1; d < day + 60; d++) {
                    int n = 0;
                    for (VillageFolkEntity f : folk) if (Park.eveningForTests(f, d)) n++;
                    if (n >= 2) { day = d; going = n; break; }
                }
                Kit.log("dt04 the park at " + site.anchor().toShortString() + "; day " + day + ", when " + going + " of six fancy it");
                helper.assertTrue(going >= 2, "an evening when some of them fancy the park");
                evenings[0] = day * 24000L + 12700L;
                level.setDayTime(evenings[0]);
                start[0] = t;
                phase[0] = 0;
                return;
            }
            if (phase[0] == 1) {
                afternoon(helper, level, kid[0], kidDay[0], t - start[0]);
                return;
            }
            long evening = evenings[0];
            // (Not past the hardest worker's bedtime: an evening out, not a night.)
            if (level.getDayTime() % 24000L > 13100L || level.getDayTime() % 24000L < 12600L) level.setDayTime(evening);
            if (t % 20 != 0) return;
            int there = 0, sat = 0;
            for (VillageFolkEntity f : folk) {
                if (Park.there(f) && Math.abs(f.getX() - site.anchor().getX()) <= 6 && Math.abs(f.getZ() - site.anchor().getZ()) <= 6) there++;
                if (f.getPose() == Pose.SITTING) sat++;
                String d = Park.doing(f);
                if (d != null && Park.there(f) && card[0].isEmpty()) card[0] = f.displayNameCap() + ": " + d;
            }
            best[0] = Math.max(best[0], there);
            best[1] = Math.max(best[1], sat);
            if (t % 200 == 0) {
                StringBuilder sb = new StringBuilder("dt04 @" + t + ": " + there + " in the park, " + sat + " sitting");
                for (VillageFolkEntity f : folk) sb.append(" | ").append(f.displayNameCap()).append(" ")
                    .append(java.util.Arrays.toString(Park.visitForTests(f))).append(" ").append(f.blockPosition().toShortString());
                Kit.log(sb.toString());
            }
            if (best[0] >= 1 && (best[1] >= 1 || t - start[0] > 2400)) {
                Kit.log("dt04 folk in the park: at most " + best[0] + " at once, " + best[1] + " sitting; a card: " + card[0]);
                helper.assertTrue(!card[0].isEmpty(), "a folk's card says it is in the park");
                // The afternoon after: a child goes there to play.
                VillageFolkEntity child = VillageFolkSpawnerBlock.raise(level, v.centre().east(4), 0.0F);
                if (child == null) {
                    Kit.log("dt04 no room for a child in the village: the afternoon is not looked at");
                    helper.succeed();
                    return;
                }
                long d = level.getDayTime() / 24000L + 1;
                while (!Park.playsForTests(child, d)) d++;
                level.setDayTime(d * 24000L + 8000L);
                child.setChild(true);
                child.bornDaysAgo(0);
                Kit.log("dt04 a child, " + child.displayNameCap() + ", the afternoon of day " + d);
                kid[0] = child;
                kidDay[0] = d;
                start[0] = t;
                phase[0] = 1;
            } else if (t - start[0] >= 3000) {
                helper.fail("nobody went to the park of an evening: at most " + best[0] + " there");
            }
        });
    }

    /** The afternoon after the evening: the child goes to the park to play (or the test fails, in time). */
    private static void afternoon(GameTestHelper helper, ServerLevel level, VillageFolkEntity kid, long day, long t) {
        if (level.getDayTime() % 24000L > 10500L || level.getDayTime() % 24000L < 7500L) level.setDayTime(day * 24000L + 8000L);
        if (Park.there(kid)) {
            Kit.log("dt04 the child is in the park after " + t + " ticks: " + Park.doing(kid));
            helper.succeed();
        } else if (t > 0 && t % 200 == 0) {
            Kit.log("dt04 the child is not at the park yet: " + java.util.Arrays.toString(Park.visitForTests(kid)) + " at "
                + kid.blockPosition().toShortString() + ", a child " + kid.isBaby());
            if (t >= 3000) helper.fail("the child never went to the park of an afternoon");
        }
    }
}
