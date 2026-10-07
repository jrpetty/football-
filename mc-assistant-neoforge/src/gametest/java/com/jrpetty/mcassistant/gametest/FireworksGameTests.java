package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Fashion;
import com.jrpetty.mcassistant.entity.Festivals;
import com.jrpetty.mcassistant.entity.FireworkShows;
import com.jrpetty.mcassistant.entity.FireworksMaker;
import com.jrpetty.mcassistant.entity.FoundingDay;
import com.jrpetty.mcassistant.entity.Gatherings;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Weather;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.gametest.CaveDwellerGameTests.Town;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [fireworks] The fireworks maker (FireworksMaker) and the town's displays (FireworkShows). Each test on its own ground
 * (x 1440000 to 1458000, z 66000), in a batch of its own, on the cave team's test towns (CaveDwellerGameTests.town): a
 * Stone Age town of eight, its stores a storehouse.
 *
 * <ul>
 * <li>fw01: the trade opens only in the Stone Age, once the town has kept a couple of festivals and has gunpowder put by;
 *     the powder hut is wished for then (at the edge of the town); built, the town chooses the right hand for it (the
 *     cheerful, curious one, not the grumpy, idle ones), one maker and no more.</li>
 * <li>fw02: the hut fitted out: its chests named the hut's (the town's goods in them back to the stores, and the stores
 *     never count them again), the cauldron filled with the stores' water bucket (the bucket back), the sign over the
 *     door ("POWDER HUT, no naked flames"), the powder fetched in: no fire is started inside it.</li>
 * <li>fw03: stars and rockets by the game's recipes out of the stores: gunpowder, paper made of sugar cane, the town's
 *     dyes, a gold nugget cut from an ingot for the star shape, glowstone for the twinkle; the rockets are the game's own,
 *     in the town's colours, their flight and stars as designed; Founding Day's, with a diamond's trail (a rich town) and
 *     a fade to white of bone meal; counted on the Production page and in the trade's book.</li>
 * <li>fw04: a wedding: the maker makes for it in the couple's own colours, and after the vows at the real wedding
 *     gathering real rocket entities go up, out of the stores, in the couple's colours.</li>
 * <li>fw05: no rockets in the stores (paper and powder, and elytra rockets, but no display rockets): no display, no
 *     salute, no rocket entity anywhere, the paper and powder untouched; the gazette says so.</li>
 * <li>fw06: elytra rockets: made of paper and one to three gunpowder while the town has gunpowder (short of the dyes for
 *     its colours, it gets on with them), sold by the maker to a player by the eight, priced by how long they fly.</li>
 * <li>fw07: no launch in a thunderstorm: the display waits, nothing out of the stores; the thunder passes and up they go.</li>
 * <li>fw08: a creeper the watch kills drops its gunpowder: the guard fetches it and it reaches the stores.</li>
 * <li>fw09: the designs: a victory's large balls with crackle and a burst with a trail (a fire charge made of blaze
 *     powder, coal and gunpowder); Remembrance in white alone (white of bone meal), and its display takes only white,
 *     one at a time; a victory won puts the victory's rockets first on the maker's list.</li>
 * <li>fw10: safety and the review: the hut never holds more than sixteen gunpowder; a rocket is never lit with folk on
 *     the rack (it waits); the display done, the chronicle has it and the next morning's gazette reviews it.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FireworksGameTests {

    private static final String EMPTY = CaveDwellerGameTests.EMPTY;
    private static final int Z = CaveDwellerGameTests.Z;

    /** A Stone Age town of eight: three farmers, a woodcutter, a miner, a guard and two between trades, every one grumpy
     *  and easygoing (no hand for the powder hut) unless a test says otherwise. */
    private static Town town(GameTestHelper helper, int x) {
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.STONE, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.WOOD, StationTask.MINE, StationTask.GUARD, StationTask.NONE, StationTask.NONE);
        for (VillageFolkEntity f : t.folk()) f.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.EASYGOING);
        return t;
    }

    /** The powder hut, as the builders leave it (its chests the town's, as BuildGoal marks them), on the town's books. */
    private static Ledger.Building hut(ServerLevel level, Town t, int dx, int dz) {
        BlockPos ground = Kit.surface(level, t.heart().getX() + dx, t.heart().getZ() + dz);
        Showcase.stage(level, ground.getX() - 6, ground.getX() + 6, ground.getZ() - 6, ground.getZ() + 7, ground.getY());
        BuildGoal.stamp(level, FireworksMaker.STRUCTURE, ground, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        for (BuildGoal.Placement p : BuildGoal.plan(FireworksMaker.STRUCTURE, ground, Direction.NORTH, 13)) {
            if (p.part() == BuildGoal.Part.CHEST) ZoneChests.mark(level, p.pos());
        }
        Ledger.built(t.village(), FireworksMaker.STRUCTURE, ground, Direction.NORTH);
        Villages.forgetStores(t.village());
        return FireworksMaker.hut(t.village());
    }

    /** The trade opened, the hut up, the town's look: its maker (one of the two between trades, made the right hand). */
    private static VillageFolkEntity maker(GameTestHelper helper, ServerLevel level, Town t, ItemStack... goods) {
        t.folk().get(6).life().setTraitsForTests(Social.Trait.CHEERFUL, Social.Trait.CURIOUS);
        List<ItemStack> all = new ArrayList<>(List.of(goods));
        if (all.stream().noneMatch(s -> s.is(Items.GUNPOWDER))) all.add(new ItemStack(Items.GUNPOWDER, 4));
        CaveDwellerGameTests.fill(t, all.toArray(new ItemStack[0]));
        FireworksMaker.festivalsForTests(t.village(), FireworksMaker.FESTIVALS);
        FireworksMaker.tickForTests(level, t.v());
        hut(level, t, -22, -22);
        FireworksMaker.tickForTests(level, t.v());
        VillageFolkEntity m = FireworksMaker.maker(t.village());
        helper.assertTrue(m != null, "a fireworks maker: " + FireworkShows.status(level, t.v()));
        return m;
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    private static int stock(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    private static ItemStack dye(DyeColor c, int n) {
        return new ItemStack(DyeItem.byColor(c), n);
    }

    /** The colours of a rocket's stars, as a set of dye colours. */
    private static Set<DyeColor> colours(ItemStack rocket) {
        return new HashSet<>(FireworksMaker.coloursOf(rocket));
    }

    /** The first stack in the stores that matches. */
    private static ItemStack first(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) return c.getItem(i).copy();
        }
        return ItemStack.EMPTY;
    }

    private static List<FireworkRocketEntity> rocketsUp(ServerLevel level, BlockPos around, int r) {
        return level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(around).inflate(r, 64, r), e -> e.getTags().contains("mca_fireworks"));
    }

    private static boolean chronicle(UUID village, String... words) {
        for (Chronicle.Entry e : Chronicle.of(village)) {
            boolean all = true;
            for (String w : words) if (!e.text().contains(w)) { all = false; break; }
            if (all) return true;
        }
        return false;
    }

    // ============================================================ fw01: the trade opens, the right hand takes it

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fw01_trade_opens")
    public static void fw01_trade_opens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1440000);
        UUID id = t.village();
        VillageFolkEntity right = t.folk().get(6), idle = t.folk().get(7);
        right.life().setTraitsForTests(Social.Trait.CHEERFUL, Social.Trait.CURIOUS);
        // The Wood Age: no fireworks, however many festivals it has kept.
        CaveDwellerGameTests.fill(t, new ItemStack(Items.GUNPOWDER, 6), new ItemStack(Items.BREAD, 64));
        Villages.ageForTests(id, Villages.Age.WOOD);
        FireworksMaker.festivalsForTests(id, 3);
        FireworksMaker.tickForTests(level, t.v());
        boolean wood = FireworksMaker.opened(id);
        // The Stone Age, one festival kept.
        Villages.ageForTests(id, Villages.Age.STONE);
        FireworksMaker.festivalsForTests(id, 1);
        FireworksMaker.tickForTests(level, t.v());
        boolean one = FireworksMaker.opened(id);
        // Two kept, but no gunpowder put by.
        CaveDwellerGameTests.fill(t, new ItemStack(Items.BREAD, 64));
        FireworksMaker.festivalsForTests(id, 2);
        FireworksMaker.tickForTests(level, t.v());
        boolean dry = FireworksMaker.opened(id);
        Kit.log("fw01 opened: wood age " + wood + ", one festival " + one + ", no gunpowder " + dry);
        helper.assertTrue(!wood && !one && !dry, "not in the Wood Age, nor with one festival, nor with no gunpowder: " + wood + one + dry);
        // And with gunpowder: the trade opens, into the chronicle; the powder hut is wished for, out at the edge.
        CaveDwellerGameTests.fill(t, new ItemStack(Items.GUNPOWDER, 6), new ItemStack(Items.BREAD, 64));
        FireworksMaker.tickForTests(level, t.v());
        List<String> wanted = Villages.projectsWanted(id);
        String why = FireworksMaker.why(id);
        Kit.log("fw01 opened " + FireworksMaker.opened(id) + "; wanted " + wanted + "; why: " + why);
        helper.assertTrue(FireworksMaker.opened(id) && chronicle(id, "wants fireworks of its own"), "the trade opens, and the chronicle says so");
        helper.assertTrue(FireworksMaker.hutWanted(id) && wanted.contains(FireworksMaker.STRUCTURE), "the powder hut wished for: " + wanted);
        helper.assertTrue(why.contains("away from the houses") && TownPlan.placeFor(FireworksMaker.STRUCTURE).equals("edge"),
            "at the edge of the town, away from the houses: " + why);
        helper.assertTrue(BuildGoal.STRUCTURES.contains(FireworksMaker.STRUCTURE), "a building the builders know");
        // No hut yet: no maker.
        FireworksMaker.tickForTests(level, t.v());
        helper.assertTrue(FireworksMaker.maker(id) == null && !FireworksMaker.ready(id), "no maker before its hut stands");
        // The hut up: the right hand takes it up.
        Ledger.Building b = hut(level, t, -22, -22);
        helper.assertTrue(b != null && FireworksMaker.ready(id) && !FireworksMaker.hutWanted(id)
            && !Villages.projectsWanted(id).contains(FireworksMaker.STRUCTURE), "the hut on the books, wished for no more");
        FireworksMaker.tickForTests(level, t.v());
        VillageFolkEntity m = FireworksMaker.maker(id);
        Kit.log("fw01 the maker: " + (m == null ? "none" : m.displayNameCap() + " (" + m.life().traits() + "), zone "
            + (m.workZone() == null ? "none" : m.workZone().center().toShortString())) + "; hut at " + b.anchor().toShortString());
        helper.assertTrue(m == right, "the cheerful, curious hand took it up: " + (m == null ? "none" : m.displayNameCap()));
        helper.assertTrue(m.stationTask() == StationTask.FIREWORKS && m.workZone() != null && m.workZone().center().distSqr(b.anchor()) <= 4,
            "its station the powder hut: " + m.workZone());
        helper.assertTrue(chronicle(id, m.displayNameCap(), "took up the fireworks"), "into the chronicle");
        helper.assertTrue(idle.stationTask() == StationTask.NONE, "the idle grumbler left as it was");
        // One maker, however often the town looks.
        FireworksMaker.tickForTests(level, t.v());
        FireworksMaker.tickForTests(level, t.v());
        int makers = 0;
        for (VillageFolkEntity f : t.folk()) if (f.stationTask() == StationTask.FIREWORKS) makers++;
        helper.assertTrue(makers == 1, "one maker: " + makers);
        helper.succeed();
    }

    // ============================================================ fw02: the hut fitted out

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fw02_hut")
    public static void fw02_hut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1442000);
        UUID id = t.village();
        VillageFolkEntity m = maker(helper, level, t, new ItemStack(Items.GUNPOWDER, 20), new ItemStack(Items.BREAD, 64),
            new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.OAK_SIGN));
        Ledger.Building b = FireworksMaker.hut(id);
        BlockPos[] fit = FireworksMaker.fittingsForTests(id);
        BlockPos bench = fit[0], cauldron = fit[2], powder = fit[3], spare = fit[4];
        helper.assertTrue(bench != null && cauldron != null && powder != null && spare != null, "the hut as drawn: a bench, a cauldron, two chests");
        helper.assertTrue(level.getBlockState(bench).is(Blocks.CRAFTING_TABLE) && level.getBlockState(cauldron).is(Blocks.CAULDRON),
            "its crafting table and its (empty) cauldron: " + level.getBlockState(cauldron));
        // The town's goods in the powder chest, as the stores' couriers might have left them.
        Container pc = (Container) level.getBlockEntity(powder);
        pc.setItem(0, new ItemStack(Items.BREAD, 5));
        pc.setItem(1, new ItemStack(Items.GUNPOWDER, 2));
        pc.setChanged();
        Villages.forgetStores(id);
        int breadBefore = stock(level, id, Items.BREAD);
        helper.assertTrue(breadBefore == 69 && Villages.storeChests(level, id).contains(powder), "the chest counted with the stores till fitted out: " + breadBefore);
        String made = FireworksMaker.now(m, level, t.v(), b);
        Villages.forgetStores(id);
        String n0 = ((ChestBlockEntity) level.getBlockEntity(powder)).getCustomName() == null ? "" : ((ChestBlockEntity) level.getBlockEntity(powder)).getCustomName().getString();
        String n1 = ((ChestBlockEntity) level.getBlockEntity(spare)).getCustomName() == null ? "" : ((ChestBlockEntity) level.getBlockEntity(spare)).getCustomName().getString();
        int hutPowder = FireworksMaker.hutPowder(level, id), storesPowder = stock(level, id, Items.GUNPOWDER);
        BlockState c = level.getBlockState(cauldron);
        BlockPos signAt = FireworksMaker.signAt(id);
        String[] sign = new String[4];
        if (signAt != null && level.getBlockEntity(signAt) instanceof SignBlockEntity s) {
            for (int i = 0; i < 4; i++) sign[i] = s.getFrontText().getMessage(i, false).getString();
        }
        Kit.log("fw02 fitted out (" + made + "): chests '" + n0 + "', '" + n1 + "'; bread in the stores " + stock(level, id, Items.BREAD)
            + " (storehouse " + CaveDwellerGameTests.inStorehouse(t, Items.BREAD) + "); powder hut " + hutPowder + ", stores " + storesPowder
            + "; cauldron " + c + "; buckets " + CaveDwellerGameTests.inStorehouse(t, Items.BUCKET) + "; sign " + String.join(" / ", sign));
        helper.assertTrue(FireworksMaker.HUT_NAME.equals(n0) && FireworksMaker.HUT_NAME.equals(n1), "both chests the hut's own: " + n0 + ", " + n1);
        helper.assertTrue(!Villages.storeChests(level, id).contains(powder) && !Villages.storeChests(level, id).contains(spare),
            "the stores never count the hut's chests");
        helper.assertTrue(CaveDwellerGameTests.inStorehouse(t, Items.BREAD) == 69 && stock(level, id, Items.BREAD) == 69,
            "the town's bread in the chest went to the storehouse");
        helper.assertTrue(hutPowder == FireworksMaker.HUT_KEEP && storesPowder == 22 - FireworksMaker.HUT_KEEP,
            "the day's powder fetched in, a dozen, the rest in the stores: " + hutPowder + ", " + storesPowder);
        helper.assertTrue(c.is(Blocks.WATER_CAULDRON) && c.getValue(LayeredCauldronBlock.LEVEL) == 3 && FireworksMaker.cauldronFull(level, id),
            "the cauldron full of water: " + c);
        helper.assertTrue(CaveDwellerGameTests.inStorehouse(t, Items.BUCKET) == 1 && CaveDwellerGameTests.inStorehouse(t, Items.WATER_BUCKET) == 0,
            "the stores' water bucket emptied into it, the bucket back in the stores");
        helper.assertTrue(signAt != null && level.getBlockState(signAt).getBlock() instanceof WallSignBlock && "POWDER HUT".equals(sign[0])
            && "No naked flames".equals(sign[2]) && m.displayNameCap().startsWith(sign[3]) && CaveDwellerGameTests.inStorehouse(t, Items.OAK_SIGN) == 0,
            "its sign over the door, of the stores' sign, its maker's name on it");
        // No fire starts in it: stone and a cauldron, and the fire safety's sparks never land inside.
        helper.assertTrue(FireworksMaker.inHut(id, bench) && FireworksMaker.inHut(id, cauldron) && !FireworksMaker.inHut(id, t.heart()),
            "the hut's ground known");
        int burns = 0;
        for (BlockPos p : BlockPos.betweenClosed(b.anchor().offset(-4, -1, -4), b.anchor().offset(4, 3, 4))) {
            if (level.getBlockState(p).isFlammable(level, p, Direction.UP)) burns++;
        }
        Kit.log("fw02 blocks in the hut that would burn: " + burns);
        helper.assertTrue(burns <= 2, "nothing in the hut that burns (the door aside): " + burns);
        helper.succeed();
    }

    // ============================================================ fw03: stars and rockets by the recipes

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fw03_making")
    public static void fw03_making(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1444000);
        UUID id = t.village();
        List<DyeColor> town = FireworksMaker.townColours(id);
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.GUNPOWDER, 20), new ItemStack(Items.SUGAR_CANE, 6),
            new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.GLOWSTONE_DUST, 3), new ItemStack(Items.BREAD, 64),
            new ItemStack(Items.BONE_MEAL, 2), new ItemStack(Items.DANDELION, 2), new ItemStack(Items.DIAMOND, FireworksMaker.RICH_DIAMONDS_FOR_TESTS)));
        for (DyeColor c : town) goods.add(dye(c, 2));
        VillageFolkEntity m = maker(helper, level, t, goods.toArray(new ItemStack[0]));
        Ledger.Building b = FireworksMaker.hut(id);
        int[] dyesBefore = new int[town.size()];
        for (int i = 0; i < town.size(); i++) dyesBefore[i] = stock(level, id, DyeItem.byColor(town.get(i)));
        // A piece of the maker's work: the town's colours, kept ready for the night nobody saw coming.
        String made = FireworksMaker.now(m, level, t.v(), b);
        ItemStack r = first(level, id, s -> s.is(Items.FIREWORK_ROCKET));
        Fireworks fw = r.get(DataComponents.FIREWORKS);
        Kit.log("fw03 made: " + made + "; the rocket " + fw + "; town colours " + town);
        int rockets = stock(level, id, Items.FIREWORK_ROCKET);
        helper.assertTrue(made != null && rockets == 3, "three rockets of a filling into the stores: " + rockets + " (" + made + ")");
        helper.assertTrue(fw != null && fw.flightDuration() == 2 && fw.explosions().size() == 1, "a rocket of flight two with its star in it: " + fw);
        FireworkExplosion e = fw.explosions().get(0);
        helper.assertTrue(e.shape() == FireworkExplosion.Shape.STAR && e.hasTwinkle() && !e.hasTrail(),
            "star-shaped (a gold nugget), twinkling (glowstone): " + e);
        helper.assertTrue(colours(r).equals(new HashSet<>(town)) && FireworksMaker.fits(r, town), "in the town's colours " + town + ": " + colours(r));
        int powderStores = stock(level, id, Items.GUNPOWDER), powderHut = FireworksMaker.hutPowder(level, id);
        Kit.log("fw03 gunpowder: stores " + powderStores + ", hut " + powderHut + "; sugar cane " + stock(level, id, Items.SUGAR_CANE)
            + ", paper " + stock(level, id, Items.PAPER) + "; gold ingot " + stock(level, id, Items.GOLD_INGOT) + ", nuggets "
            + stock(level, id, Items.GOLD_NUGGET) + "; glowstone " + stock(level, id, Items.GLOWSTONE_DUST));
        helper.assertTrue(powderStores + powderHut == 20 - 3 && powderHut == FireworksMaker.HUT_KEEP - 3,
            "three gunpowder used (one for the star, two for the flight), out of the hut's dozen: " + powderStores + " + " + powderHut);
        helper.assertTrue(stock(level, id, Items.SUGAR_CANE) == 3 && stock(level, id, Items.PAPER) == 2,
            "the paper made of three sugar cane, the two sheets over back in the stores");
        helper.assertTrue(stock(level, id, Items.GOLD_INGOT) == 0 && stock(level, id, Items.GOLD_NUGGET) == 8, "the ingot cut into nine, eight back");
        helper.assertTrue(stock(level, id, Items.GLOWSTONE_DUST) == 2, "a glowstone dust for the twinkle");
        for (int i = 0; i < town.size(); i++) {
            int now = stock(level, id, DyeItem.byColor(town.get(i)));
            helper.assertTrue(now == dyesBefore[i] - 1, "one " + town.get(i) + " dye used: " + dyesBefore[i] + " -> " + now);
        }
        int[] tally = Economy.todayForTests(id, "firework_rocket");
        helper.assertTrue(tally[0] == 3, "three rockets made today, on the Production page: " + tally[0]);
        // Founding Day's design: two stars, a burst (or a star, with no feather) with a trail and a fade to white, and gold.
        FireworksMaker.keepPowder(level, t.v(), b);
        int diamonds = stock(level, id, Items.DIAMOND);
        FireworksMaker.Made f = FireworksMaker.make(level, t.v(), b, FireworksMaker.design(level, id, FireworkShows.Occasion.FOUNDING));
        Fireworks ff = f.rocket().get(DataComponents.FIREWORKS);
        Kit.log("fw03 Founding Day's: " + f.words() + "; " + ff + "; diamonds " + diamonds + " -> " + stock(level, id, Items.DIAMOND)
            + "; bone meal " + stock(level, id, Items.BONE_MEAL) + ", dandelions " + stock(level, id, Items.DANDELION));
        helper.assertTrue(f.rockets() == 3 && ff != null && ff.explosions().size() == 2, "Founding Day's: two stars a rocket: " + ff);
        FireworkExplosion a = ff.explosions().get(0), g = ff.explosions().get(1);
        helper.assertTrue(a.hasTrail() && stock(level, id, Items.DIAMOND) == diamonds - 1, "a rich town spends a diamond on a trail: " + a);
        helper.assertTrue(a.fadeColors().size() == 1 && a.fadeColors().getInt(0) == DyeColor.WHITE.getFireworkColor(), "fading to white: " + a);
        helper.assertTrue(g.colors().size() == 1 && g.colors().getInt(0) == DyeColor.YELLOW.getFireworkColor(), "and a star of gold: " + g);
        if (!town.contains(DyeColor.WHITE)) helper.assertTrue(stock(level, id, Items.BONE_MEAL) == 1, "the white of bone meal, by the recipe");
        if (!town.contains(DyeColor.YELLOW)) helper.assertTrue(stock(level, id, Items.DANDELION) == 1, "the yellow of a dandelion, by the recipe");
        List<String> notes = FireworksMaker.bookNotesForTests(level, t.v());
        Kit.log("fw03 the trade's book: " + notes);
        helper.assertTrue(!notes.isEmpty() && notes.get(0).contains("stars") && notes.get(0).contains("rockets"), "the trade's book counts them: " + notes);
        helper.succeed();
    }

    // ============================================================ fw04: a wedding's display

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "fw04_wedding")
    public static void fw04_wedding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1446000);
        UUID id = t.village();
        VillageFolkEntity m = maker(helper, level, t, new ItemStack(Items.GUNPOWDER, 24), new ItemStack(Items.PAPER, 6), dye(DyeColor.RED, 4),
            dye(DyeColor.LIGHT_BLUE, 4), dye(DyeColor.WHITE, 4), new ItemStack(Items.GOLD_NUGGET, 4), new ItemStack(Items.GLOWSTONE_DUST, 4),
            new ItemStack(Items.BREAD, 64));
        VillageFolkEntity a = t.folk().get(1), b = t.folk().get(2);
        Fashion.coloursForTests(a, DyeColor.RED, DyeColor.WHITE);
        Fashion.coloursForTests(b, DyeColor.LIGHT_BLUE, DyeColor.WHITE);
        // An evening with no festival and no Founding Day on it.
        long today = level.getDayTime() / 24000L, day = today;
        for (long d = today + 1; d < today + 20; d++) {
            boolean busy = FoundingDay.due(id, d) || FoundingDay.due(id, d - 1);
            for (Festivals.Feast fe : Festivals.Feast.values()) {
                if (Festivals.next(id, d - 1, fe) == d || Festivals.next(id, d - 1, fe) == d - 1) busy = true;
            }
            if (!busy && d % 7 != 6) { day = d; break; }
        }
        final long wed = day;
        Gatherings.pledged(id, a, b, wed);
        Ledger.Building hut = FireworksMaker.hut(id);
        for (int i = 0; i < 3; i++) FireworksMaker.now(m, level, t.v(), hut);
        List<DyeColor> couple = List.of(DyeColor.RED, DyeColor.LIGHT_BLUE);
        int ready = stock(level, id, s -> FireworksMaker.fits(s, couple));
        ItemStack sample = first(level, id, s -> FireworksMaker.fits(s, couple));
        Kit.log("fw04 the wedding's rockets: " + ready + ", " + sample.get(DataComponents.FIREWORKS) + "; the evening of day " + wed);
        helper.assertTrue(ready == 9, "nine rockets in the couple's colours, made for their wedding: " + ready);
        FireworkExplosion e = sample.get(DataComponents.FIREWORKS).explosions().get(0);
        helper.assertTrue(e.fadeColors().contains(DyeColor.WHITE.getFireworkColor()) && e.shape() == FireworkExplosion.Shape.STAR,
            "star-shaped, fading to white: " + e);
        // The paper put away: nothing more is made while the test watches (a rocket of the town's colours that happened
        // to share one of the couple's would be counted with theirs).
        for (int i = 0; i < t.store().getContainerSize(); i++) if (t.store().getItem(i).is(Items.PAPER)) t.store().setItem(i, ItemStack.EMPTY);
        Villages.forgetStock();
        long evening = wed * 24000L + 12150L;
        level.setDayTime(evening);
        int[] seen = { 0 };
        boolean[] good = { true };
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            level.setDayTime(evening);
            if (tick % 20 == 0) Assemblies.tick(level, t.v());
            for (FireworkRocketEntity r : rocketsUp(level, t.heart(), 48)) {
                seen[0] = Math.max(seen[0], 1);
                if (!FireworksMaker.fits(r.getItem(), couple)) good[0] = false;
            }
            int fired = FireworkShows.fired(id, wed);
            if (tick % 200 == 0) Kit.log("fw04 tick " + tick + ": " + Assemblies.debug(id) + "; fired " + fired + ", show " + java.util.Arrays.toString(FireworkShows.showForTests(id)));
            if (fired < 3 || seen[0] == 0) {
                if (tick > 3800) helper.fail("no display at the wedding: " + Assemblies.debug(id) + "; " + FireworkShows.status(level, t.v()));
                return;
            }
            int left = stock(level, id, s -> FireworksMaker.fits(s, couple));
            Kit.log("fw04 the wedding's display: " + fired + " up, " + left + " left in the stores; " + FireworkShows.status(level, t.v()));
            helper.assertTrue(good[0], "every rocket that went up in the couple's colours");
            helper.assertTrue(left == 9 - fired, "every one of them out of the stores: " + left + " left of 9, " + fired + " up");
            helper.succeed();
        });
    }

    // ============================================================ fw05: no rockets, no fireworks

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fw05_none")
    public static void fw05_none(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1448000);
        UUID id = t.village();
        // The makings of rockets, and plain rockets for the elytra, but not a display rocket among them.
        ItemStack wings = FireworksMaker.elytraRocket(2).copyWithCount(8);
        maker(helper, level, t, new ItemStack(Items.GUNPOWDER, 10), new ItemStack(Items.PAPER, 10), dye(DyeColor.RED, 4), wings,
            new ItemStack(Items.BREAD, 64));
        long day = level.getDayTime() / 24000L;
        int powder = stock(level, id, Items.GUNPOWDER) + FireworksMaker.hutPowder(level, id);
        boolean show = FireworkShows.begin(level, t.v(), FireworkShows.Occasion.CELEBRATION, t.heart(), "the Stone Age");
        int salute = FireworkShows.salute(level, t.v(), t.heart(), 3);
        helper.runAfterDelay(40, () -> {
            int up = level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(t.heart()).inflate(64, 96, 64)).size();
            int powderNow = stock(level, id, Items.GUNPOWDER) + FireworksMaker.hutPowder(level, id);
            String gazette = FireworkShows.gazette(id, day + 1);
            Kit.log("fw05 display " + show + ", salute " + salute + ", rockets up " + up + "; gunpowder " + powder + " -> " + powderNow
                + ", paper " + stock(level, id, Items.PAPER) + ", elytra rockets " + stock(level, id, s -> FireworksMaker.elytra(s, 2)) + "; gazette: " + gazette);
            helper.assertTrue(!show && salute == 0 && up == 0, "no display, no salute, not a rocket in the sky: " + show + ", " + salute + ", " + up);
            helper.assertTrue(powderNow == powder && stock(level, id, Items.PAPER) == 10, "the paper and the gunpowder untouched: nothing made of nothing");
            helper.assertTrue(stock(level, id, s -> FireworksMaker.elytra(s, 2)) == 8, "the elytra rockets are the players', not a display");
            helper.assertTrue(gazette != null && gazette.contains("No fireworks") && gazette.contains("no rockets"), "the gazette: a quieter night: " + gazette);
            helper.succeed();
        });
    }

    // ============================================================ fw06: elytra rockets sold to a player

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fw06_elytra")
    public static void fw06_elytra(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1450000);
        UUID id = t.village();
        // Gunpowder and paper, and not a dye: the town's colours wait; its elytra rockets do not.
        VillageFolkEntity m = maker(helper, level, t, new ItemStack(Items.GUNPOWDER, 48), new ItemStack(Items.PAPER, 16), new ItemStack(Items.BREAD, 64));
        Ledger.Building hut = FireworksMaker.hut(id);
        int powder = stock(level, id, Items.GUNPOWDER) + FireworksMaker.hutPowder(level, id);
        for (int i = 0; i < 12; i++) FireworksMaker.now(m, level, t.v(), hut);
        int[] n = new int[4];
        for (int f = 1; f <= 3; f++) { final int fl = f; n[f] = stock(level, id, s -> FireworksMaker.elytra(s, fl)); }
        int used = powder - stock(level, id, Items.GUNPOWDER) - FireworksMaker.hutPowder(level, id);
        Kit.log("fw06 elytra rockets: flight 1 " + n[1] + ", flight 2 " + n[2] + ", flight 3 " + n[3] + "; gunpowder used " + used
            + ", paper left " + stock(level, id, Items.PAPER));
        helper.assertTrue(n[1] >= 8 && n[2] >= 8 && n[3] >= 6, "elytra rockets of every flight in stock: " + n[1] + ", " + n[2] + ", " + n[3]);
        helper.assertTrue(used == (n[1] + 2 * n[2] + 3 * n[3]) / 3, "a gunpowder a flight a filling of three: " + used);
        helper.assertTrue(stock(level, id, Items.PAPER) == 16 - (n[1] + n[2] + n[3]) / 3, "a sheet of paper a filling");
        ItemStack one = FireworksMaker.elytraRocket(1), three = FireworksMaker.elytraRocket(3);
        Market.Good g1 = Market.goodFor(one), g3 = Market.goodFor(three);
        int p1 = Market.price(level, id, g1, one, n[1], false), p3 = Market.price(level, id, g3, three, n[3], false);
        Kit.log("fw06 the shop's prices: " + g1.name() + " " + p1 + ", " + g3.name() + " " + p3 + "; on the counter " + Cafe.shopWorthy(one));
        helper.assertTrue(p3 > p1 && g1.bundle() == 8, "priced by how long they fly, by the eight: " + p1 + " < " + p3);
        helper.assertTrue(Cafe.shopWorthy(FireworksMaker.elytraRocket(2)), "on the shop's counter");
        // A player buys eight of flight two from the maker at its hut.
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 40));
        int coins = Market.coinsHeld(p), treasury = Ledger.coins(id), before = n[2];
        String said = FireworksMaker.talk(m, p, "Could I buy some rockets for my elytra? Flight two, please.");
        int got = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (FireworksMaker.elytra(s, 2)) got += s.getCount();
        }
        int paid = coins - Market.coinsHeld(p);
        Kit.log("fw06 the maker said: \"" + said + "\"; got " + got + ", paid " + paid + "; treasury " + treasury + " -> " + Ledger.coins(id));
        helper.assertTrue(said.startsWith("Bought") && got == 8, "eight rockets of flight two for the player: " + said);
        helper.assertTrue(paid > 0 && Ledger.coins(id) == treasury + paid, "paid for, into the treasury: " + paid);
        helper.assertTrue(stock(level, id, s -> FireworksMaker.elytra(s, 2)) == before - 8, "out of the stores");
        String other = FireworksMaker.talk(t.folk().get(0), p, "Have you any elytra rockets?");
        helper.assertTrue(other != null && other.contains(m.displayNameCap()) && other.contains("powder hut"), "anybody else sends you to the maker: " + other);
        Kit.noLeftoverPlayers(level);
        helper.succeed();
    }

    // ============================================================ fw07: no launch in a thunderstorm

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "fw07_storm")
    public static void fw07_storm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1452000);
        UUID id = t.village();
        List<DyeColor> town = FireworksMaker.townColours(id);
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.GUNPOWDER, 20), new ItemStack(Items.PAPER, 4),
            new ItemStack(Items.GOLD_NUGGET, 4), new ItemStack(Items.GLOWSTONE_DUST, 4), new ItemStack(Items.BREAD, 64)));
        for (DyeColor c : town) goods.add(dye(c, 3));
        VillageFolkEntity m = maker(helper, level, t, goods.toArray(new ItemStack[0]));
        Ledger.Building hut = FireworksMaker.hut(id);
        FireworksMaker.now(m, level, t.v(), hut);
        FireworksMaker.now(m, level, t.v(), hut);
        int ready = stock(level, id, FireworksMaker::display);
        helper.assertTrue(ready == 6, "six rockets of the maker's making: " + ready);
        long day = level.getDayTime() / 24000L;
        Weather.stormForTests(true);
        boolean on = FireworkShows.begin(level, t.v(), FireworkShows.Occasion.FESTIVAL, t.heart(), "the storm test");
        helper.assertTrue(on, "the display called, the rockets ready");
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            int fired = FireworkShows.fired(id, day), left = stock(level, id, FireworksMaker::display), up = rocketsUp(level, t.heart(), 48).size();
            if (tick <= 200) {
                if (fired > 0 || left != 6 || up > 0) {
                    Weather.stormForTests(null);
                    helper.fail("a rocket lit in the thunderstorm: fired " + fired + ", left " + left + ", up " + up);
                }
                if (tick == 200) {
                    Kit.log("fw07 two hundred ticks of thunder: fired " + fired + ", " + left + " still in the stores; the thunder passes");
                    Weather.stormForTests(false);
                }
                return;
            }
            if (fired == 0) {
                if (tick > 560) {
                    Weather.stormForTests(null);
                    helper.fail("nothing went up after the storm: " + FireworkShows.status(level, t.v()));
                }
                return;
            }
            Weather.stormForTests(null);
            Kit.log("fw07 after the storm: " + fired + " up, " + left + " left");
            helper.assertTrue(left == 6 - fired, "out of the stores once the thunder had passed: " + left);
            helper.succeed();
        });
    }

    // ============================================================ fw08: the watch's creeper, its powder in the stores

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "fw08_creeper")
    public static void fw08_creeper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = CaveDwellerGameTests.town(helper, 1454000, Villages.Age.STONE, StationTask.FARM, StationTask.GUARD);
        UUID id = t.village();
        VillageFolkEntity g = t.folk().get(1);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.BREAD, 32));
        level.setDayTime(14000);                                          // the watch's night (no leader's escort to walk)
        BlockPos at = Kit.surface(level, g.getBlockX() + 5, g.getBlockZ() + 2);
        int killed = 0;
        for (int i = 0; i < 10 && !FireworksMaker.fetching(g); i++) {
            Creeper c = EntityType.CREEPER.create(level);
            c.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
            c.setNoAi(true);
            level.addFreshEntity(c);
            c.hurt(level.damageSources().mobAttack(g), 1000.0F);
            killed++;
        }
        int dropped = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(at).inflate(3),
            e -> e.getItem().is(Items.GUNPOWDER)).stream().mapToInt(e -> e.getItem().getCount()).sum();
        Kit.log("fw08 creepers killed by " + g.displayNameCap() + ": " + killed + "; gunpowder dropped " + dropped + "; fetching " + FireworksMaker.fetching(g));
        helper.assertTrue(FireworksMaker.fetching(g) && dropped > 0, "the guard sent for the creeper's gunpowder");
        helper.onEachTick(() -> {
            level.setDayTime(14000);
            long tick = helper.getTick();
            int stores = stock(level, id, Items.GUNPOWDER), carried = g.countCarried(s -> s.is(Items.GUNPOWDER));
            if (tick % 100 == 0) Kit.log("fw08 tick " + tick + ": stores " + stores + ", carried " + carried + ", fetching " + FireworksMaker.fetching(g)
                + ", job " + g.peekJob());
            if (stores < dropped) {
                if (tick > 1100) helper.fail("the creeper's gunpowder never reached the stores: stores " + stores + ", carried " + carried);
                return;
            }
            long[] tally = FireworksMaker.tally(id);
            Kit.log("fw08 in the stores: " + stores + " gunpowder; the trade's tally of the watch's powder " + tally[5]);
            helper.assertTrue(carried == 0 && tally[5] == dropped, "all of it in, and counted: carried " + carried + ", tallied " + tally[5]);
            helper.succeed();
        });
    }

    // ============================================================ fw09: a victory's, and Remembrance's

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fw09_designs")
    public static void fw09_designs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1456000);
        UUID id = t.village();
        List<DyeColor> town = FireworksMaker.townColours(id);
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.GUNPOWDER, 30), new ItemStack(Items.PAPER, 6),
            new ItemStack(Items.BLAZE_POWDER), new ItemStack(Items.COAL, 2), new ItemStack(Items.GLOWSTONE_DUST, 4),
            new ItemStack(Items.FEATHER, 2), new ItemStack(Items.DIAMOND, FireworksMaker.RICH_DIAMONDS_FOR_TESTS), new ItemStack(Items.BONE_MEAL, 4),
            new ItemStack(Items.BREAD, 64)));
        for (DyeColor c : town) if (c != DyeColor.WHITE) goods.add(dye(c, 4));
        maker(helper, level, t, goods.toArray(new ItemStack[0]));
        Ledger.Building hut = FireworksMaker.hut(id);
        FireworksMaker.keepPowder(level, t.v(), hut);
        // A victory: large balls with crackle, a burst with a trail; flight three.
        FireworksMaker.Made v = FireworksMaker.make(level, t.v(), hut, FireworksMaker.design(level, id, FireworkShows.Occasion.VICTORY));
        Fireworks vf = v.rocket().get(DataComponents.FIREWORKS);
        Kit.log("fw09 the victory's: " + v.words() + "; " + vf + "; fire charges in the stores " + stock(level, id, Items.FIRE_CHARGE)
            + ", blaze powder " + stock(level, id, Items.BLAZE_POWDER) + ", coal " + stock(level, id, Items.COAL));
        helper.assertTrue(v.rockets() == 3 && vf != null && vf.flightDuration() == 3 && vf.explosions().size() == 2, "a victory's, flying high: " + vf);
        FireworkExplosion big = vf.explosions().get(0), burst = vf.explosions().get(1);
        helper.assertTrue(big.shape() == FireworkExplosion.Shape.LARGE_BALL && big.hasTwinkle(), "large balls with crackle: " + big);
        helper.assertTrue(burst.shape() == FireworkExplosion.Shape.BURST && burst.hasTrail(), "a burst with a trail: " + burst);
        helper.assertTrue(stock(level, id, Items.FIRE_CHARGE) == 2 && stock(level, id, Items.BLAZE_POWDER) == 0 && stock(level, id, Items.COAL) == 1,
            "a fire charge of blaze powder, coal and gunpowder, by the recipe: two over");
        helper.assertTrue(FireworksMaker.fits(v.rocket(), town), "in the town's colours");
        // Remembrance: white alone, of bone meal.
        FireworksMaker.keepPowder(level, t.v(), hut);
        FireworksMaker.Made w = FireworksMaker.make(level, t.v(), hut, FireworksMaker.design(level, id, FireworkShows.Occasion.REMEMBRANCE));
        Fireworks wf = w.rocket().get(DataComponents.FIREWORKS);
        Kit.log("fw09 Remembrance's: " + wf + "; bone meal " + stock(level, id, Items.BONE_MEAL));
        helper.assertTrue(w.rockets() == 3 && FireworksMaker.coloursOf(w.rocket()).equals(List.of(DyeColor.WHITE))
            && wf.explosions().get(0).shape() == FireworkExplosion.Shape.SMALL_BALL && !wf.explosions().get(0).hasTwinkle(), "white alone, plain: " + wf);
        // White for Remembrance's star (and for each of the victory's two, if white is one of the town's colours): bone meal.
        int boneMeal = 4 - (town.contains(DyeColor.WHITE) ? 2 : 0) - 1;
        helper.assertTrue(stock(level, id, Items.BONE_MEAL) == boneMeal, "the white of bone meal, by the recipe: " + stock(level, id, Items.BONE_MEAL)
            + " left, " + boneMeal + " expected");
        // Remembrance's display takes the white ones only, one at a time.
        boolean on = FireworkShows.begin(level, t.v(), FireworkShows.Occasion.REMEMBRANCE, t.heart(), "Remembrance Day");
        int[] show = FireworkShows.showForTests(id);
        FireworkShows.stepForTests(level, id);
        List<FireworkRocketEntity> up = rocketsUp(level, t.heart(), 48);
        Kit.log("fw09 Remembrance's display: " + on + " " + java.util.Arrays.toString(show) + "; up " + up.size()
            + "; the victory's left " + stock(level, id, s -> FireworksMaker.fits(s, town) && !FireworksMaker.fits(s, List.of(DyeColor.WHITE))));
        helper.assertTrue(on && show != null && show[1] == 3 && show[2] == 3, "three white rockets, one at a time: " + java.util.Arrays.toString(show));
        helper.assertTrue(up.size() == 1 && FireworksMaker.coloursOf(up.get(0).getItem()).equals(List.of(DyeColor.WHITE)), "the first, white: " + up.size());
        helper.assertTrue(stock(level, id, s -> FireworksMaker.display(s) && !FireworksMaker.fits(s, List.of(DyeColor.WHITE))) == 3,
            "the victory's rockets left in the stores");
        // A war won: the victory's rockets go to the head of the maker's list.
        long day = level.getDayTime() / 24000L;
        FireworksMaker.victory(id, UUID.randomUUID(), day + 2);
        FireworksMaker.Order next = FireworksMaker.next(level, t.v());
        Kit.log("fw09 after the war: next to make " + (next == null ? "nothing" : next.design().words() + " for " + next.forWhat()));
        helper.assertTrue(next != null && next.design().occasion() == FireworkShows.Occasion.VICTORY && next.forWhat().contains("victory"),
            "the victory's first: " + (next == null ? "nothing" : next.forWhat()));
        helper.succeed();
    }

    // ============================================================ fw10: safety, and the gazette's review

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "fw10_safety")
    public static void fw10_safety(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1458000);
        UUID id = t.village();
        List<DyeColor> town = FireworksMaker.townColours(id);
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.GUNPOWDER, 20), new ItemStack(Items.PAPER, 4),
            new ItemStack(Items.GOLD_NUGGET, 4), new ItemStack(Items.GLOWSTONE_DUST, 4), new ItemStack(Items.BREAD, 64)));
        for (DyeColor c : town) goods.add(dye(c, 3));
        VillageFolkEntity m = maker(helper, level, t, goods.toArray(new ItemStack[0]));
        Ledger.Building hut = FireworksMaker.hut(id);
        FireworksMaker.now(m, level, t.v(), hut);
        FireworksMaker.now(m, level, t.v(), hut);
        // Forty gunpowder left in the powder chest: never more than sixteen in the hut; the rest to the stores.
        Container powder = (Container) level.getBlockEntity(FireworksMaker.fittingsForTests(id)[3]);
        int stores = stock(level, id, Items.GUNPOWDER);
        powder.setItem(10, new ItemStack(Items.GUNPOWDER, 40));
        powder.setChanged();
        int inHut = FireworksMaker.hutPowder(level, id);
        FireworksMaker.keepPowder(level, t.v(), hut);
        Kit.log("fw10 the powder chest: " + inHut + " -> " + FireworksMaker.hutPowder(level, id) + "; the stores " + stores + " -> " + stock(level, id, Items.GUNPOWDER));
        helper.assertTrue(FireworksMaker.hutPowder(level, id) == FireworksMaker.HUT_KEEP && stock(level, id, Items.GUNPOWDER) == stores + inHut - FireworksMaker.HUT_KEEP,
            "the hut back to a dozen, the rest to the stores");
        // Folk on every place of the rack: nothing lit till they have moved off.
        long day = level.getDayTime() / 24000L;
        boolean on = FireworkShows.begin(level, t.v(), FireworkShows.Occasion.FESTIVAL, t.heart(), "the safety test");
        List<BlockPos> rack = FireworkShows.rackForTests(id);
        List<UUID> crew = FireworkShows.crewForTests(id);
        List<VillageFolkEntity> standers = new ArrayList<>();
        for (VillageFolkEntity f : t.folk()) if (!crew.contains(f.getUUID()) && standers.size() < rack.size()) standers.add(f);
        for (int i = 0; i < standers.size(); i++) {
            BlockPos p = rack.get(i);
            standers.get(i).moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
        }
        FireworkShows.stepForTests(level, id);
        int blocked = FireworkShows.fired(id, day);
        Kit.log("fw10 the display " + on + ", rack " + rack + "; " + standers.size() + " folk stood on it: fired " + blocked
            + ", still on " + FireworkShows.on(id));
        helper.assertTrue(on && rack.size() == 5 && standers.size() == 5, "the display on, five on the rack");
        helper.assertTrue(blocked == 0 && FireworkShows.on(id) && stock(level, id, FireworksMaker::display) == 6, "nothing lit with folk on the rack, and it waits");
        for (VillageFolkEntity f : standers) f.moveTo(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 0.5, 0.0F, 0.0F);
        FireworkShows.stepForTests(level, id);
        int fired = FireworkShows.fired(id, day);
        boolean clear = true;
        for (FireworkRocketEntity r : rocketsUp(level, t.heart(), 48)) {
            if (!level.getEntitiesOfClass(LivingEntity.class, r.getBoundingBox().inflate(1.0), LivingEntity::isAlive).isEmpty()) clear = false;
        }
        helper.assertTrue(fired > 0 && clear, "lit once the rack was clear, and never by anybody: " + fired);
        helper.onEachTick(() -> {
            if (FireworkShows.on(id)) {
                if (helper.getTick() > 380) helper.fail("the display never finished: " + FireworkShows.status(level, t.v()));
                return;
            }
            String review = FireworkShows.gazette(id, day + 1);
            Kit.log("fw10 the gazette: " + review);
            helper.assertTrue(review != null && review.contains("Fireworks for the safety test: 6 rockets") && review.contains("finale"),
                "the next morning's gazette reviews it: " + review);
            helper.assertTrue(chronicle(id, "put on a display of 6 rockets", "the safety test"), "into the chronicle");
            helper.assertTrue(FireworksMaker.tally(id)[3] == 1 && FireworksMaker.tally(id)[4] == 6, "the trade's tally: one display, six fired");
            helper.succeed();
        });
    }
}
