package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Fair;
import com.jrpetty.mcassistant.entity.Festivals;
import com.jrpetty.mcassistant.entity.Fields;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Midwinter;
import com.jrpetty.mcassistant.entity.Seasons;
import com.jrpetty.mcassistant.entity.Sweepers;
import com.jrpetty.mcassistant.entity.TownCalendar;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Winter;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [batchB] Seasons and festivals: the town's year cut into four seasons, the tended fields quicker and
 * slower with them; the May dance round the maypole, the midsummer bonfire, the town fair, the harvest
 * festival, midwinter's lanterns and presents, and the children's snowmen.
 *
 * <ul>
 * <li><b>sf21</b>: a town's year is spring, summer, autumn and winter, a week each, from its Founding Day;
 *     a tended field grows quicker in spring and summer and slower in autumn and winter, never at the wild's
 *     pace or under; the board, the books and the crier say the season; it goes into the chronicle as it
 *     turns; folk talk of it; /village season says it.</li>
 * <li><b>sf22</b>: the maypole goes up on the square out of the stores (five posts, fence posts on a foot of
 *     logs if the town's works have taken a post or two, wool of every colour the stores have); the May dance
 *     kept goes into the chronicle, the memories and the town's contentment; the morning after it comes down
 *     and every post and every block of wool is back in the stores.</li>
 * <li><b>sf23</b>: the May dance itself: the town called round the maypole, and once the elder has spoken
 *     the ring goes round the pole, folk passing from place to place.</li>
 * <li><b>sf24</b>: the midsummer bonfire: five campfires lit on the square, each made of three of the
 *     stores' logs, a coal and three sticks (sawn from their planks when the sticks run out); sung round;
 *     put out at midnight, the ground clear and two charcoal a fire in the stores.</li>
 * <li><b>sf25</b>: the town fair: a player's bread, the stores' bread entered by the farmer (never its ration
 *     loaves, which stay in its pack), the stores' wool entered by the rancher, the fisher's cod out of its
 *     pack; judged by the rules (the player's forty-eight loaves beat the town's thirty-two); ribbons of the
 *     stores' paper and purses out of the treasury; every entry back where it came from (the player's kept
 *     for them, being away); results in the chronicle and on the board.</li>
 * <li><b>sf26</b>: the harvest festival: the year's harvest counted farmer by farmer as it is brought in;
 *     two long tables of slabs sawn from the stores' planks; the year's totals in the chronicle and the
 *     farmer who brought in the most paid its prize out of the treasury; the slabs back in the stores the
 *     next morning.</li>
 * <li><b>sf27</b>: midwinter: twelve lights along the main avenue out of the stores (its six lanterns, then
 *     torches), a present bought out of a folk's own purse over the stores' counter and given to its friend
 *     (its keepsake now), and the lights back into the stores the morning after, into the chronicle.</li>
 * <li><b>sf28</b>: winter in a snowy town: a child gathers the lying snow with the stores' shovel and builds
 *     a snowman of three blocks by the playground; with a carved pumpkin from the stores the next one wakes as
 *     a snow golem, as in the game; folk say it is cold (frost talk where it does not snow); the thaw takes
 *     the snowman.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 620,000 to 634,000, z 50,000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SeasonsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    /** Flat grass round here, clear air above it. Returns the ground's top (where a folk stands). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
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

    /** A marked store chest at this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    private static int stock(ServerLevel level, UUID id, Predicate<ItemStack> what) {
        return Market.stock(level, id, what);
    }

    private static boolean chronicled(UUID id, String words) {
        for (Chronicle.Entry e : Chronicle.of(id)) if (e.text().contains(words)) return true;
        return false;
    }

    private static List<String> chronicle(UUID id) {
        List<String> out = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(id)) out.add("day " + e.day() + ": " + e.text());
        return out;
    }

    /** A town of {@code n} on flat ground at x, the first at the heart. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, BlockPos heart, int n) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + i * 2, 0, 8), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            folk.add(f);
        }
        return folk;
    }

    private static int count(VillageFolkEntity f, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    // ============================================================ sf21: the seasons

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf21_seasons")
    public static void sf21_seasons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 620000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 24);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 2000L);
        List<VillageFolkEntity> folk = town(helper, level, heart, 4);
        VillageFolkEntity farmer = folk.get(1), other = folk.get(2);
        farmer.setJob(StationTask.FARM);
        UUID id = farmer.ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            long today = level.getDayTime() / 24000L;
            Kit.log("sf21 founded day " + Chronicle.foundedOn(id) + ", today " + today + ": " + Seasons.dateLine(id, today)
                + ", day of the year " + Seasons.dayOfYear(id, today));
            helper.assertTrue(Seasons.season(id, today) == Seasons.Season.SPRING && Seasons.dayInSeason(id, today) == 1,
                "a town founded today is on the first day of spring: " + Seasons.dateLine(id, today));
            // The fields, season by season.
            double base = AssistantConfig.villageCropGrowth();
            Map<Seasons.Season, Double> m = new HashMap<>();
            for (Seasons.Season s : Seasons.Season.values()) {
                Festivals.turnTo(id, today, s.ordinal() * Seasons.DAYS + 2);
                helper.assertTrue(Seasons.season(id, today) == s, "the calendar turned to " + s + ": " + Seasons.dateLine(id, today));
                m.put(s, Fields.multiplier(farmer));
            }
            Kit.log("sf21 a tended field at " + base + "x set: " + m + "; the books say: " + Fields.word(id));
            if (base > 1.0) {
                helper.assertTrue(m.get(Seasons.Season.SPRING) > m.get(Seasons.Season.SUMMER)
                    && m.get(Seasons.Season.SUMMER) > base && m.get(Seasons.Season.AUTUMN) < base
                    && m.get(Seasons.Season.WINTER) < m.get(Seasons.Season.AUTUMN), "quicker in spring and summer, slower in autumn and winter: " + m);
                helper.assertTrue(m.get(Seasons.Season.WINTER) > 1.0, "never stopping: even winter beats the wild's pace: " + m);
                helper.assertTrue(Math.abs(m.get(Seasons.Season.SPRING) - (1.0 + (base - 1.0) * Seasons.Season.SPRING.growth)) < 0.25,
                    "spring's pace is the season's share of the tended growth: " + m);
            }
            // Summer, its third day: the board, the books, the crier, the talk.
            Festivals.turnTo(id, today, 9);
            List<String> board = TownCalendar.board(level, id), book = TownCalendar.book(level, id);
            String cry = Seasons.cry(id, today);
            Kit.log("sf21 summer's third day: board " + board + "; books " + book + "; the crier: " + cry);
            helper.assertTrue(board.stream().anyMatch(l -> l.contains("Summer, day 3 of 7")), "the board says the season: " + board);
            helper.assertTrue(book.stream().anyMatch(l -> l.contains("Season: summer, day 3 of 7")), "the books say it: " + book);
            helper.assertTrue(book.stream().anyMatch(l -> l.startsWith("The year's festivals")), "the books list the year's festivals: " + book);
            helper.assertTrue(cry != null && cry.contains("third day of summer"), "the crier says it: " + cry);
            String talked = null;
            for (int seed = 0; seed < 40 && talked == null; seed++) {
                List<String[]> t = Seasons.talkForTests(other, farmer, level, RandomSource.create(seed));
                if (!t.isEmpty()) talked = String.join(" / ", t.get(0));
            }
            Kit.log("sf21 summer talk: " + talked);
            helper.assertTrue(talked != null, "folk talk of the season now and then");
            // The turn of the season, into the chronicle.
            Festivals.turnTo(id, today, 14);
            Festivals.tickForTests(level, v);
            helper.assertTrue(chronicled(id, "autumn came in"), "the season's first day goes into the chronicle: " + chronicle(id));
            List<String> said = Kit.command(level, "execute positioned " + heart.getX() + " " + heart.getY() + " " + heart.getZ()
                + " run village season");
            Kit.log("sf21 /village season: " + said);
            helper.assertTrue(said.stream().anyMatch(l -> l.contains("SEASON") && l.contains("Autumn")), "/village season says it: " + said);
            helper.succeed();
        });
    }

    // ============================================================ sf22: the maypole

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf22_maypole")
    public static void sf22_maypole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 622000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 26);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 2000L);
        List<VillageFolkEntity> folk = town(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        // Ample: the town's own small works (a sign post, a garden fence) take a fence post or two in its first
        // seconds, and the beds want wool.
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.OAK_FENCE, 8), new ItemStack(Items.OAK_LOG, 4),
            new ItemStack(Items.RED_WOOL, 3), new ItemStack(Items.BLUE_WOOL, 3), new ItemStack(Items.YELLOW_WOOL, 3), new ItemStack(Items.WHITE_WOOL, 3));
        Predicate<ItemStack> postish = s -> s.is(ItemTags.WOODEN_FENCES) || s.is(ItemTags.LOGS);
        helper.runAtTickTime(10, () -> {
            int posts0 = stock(level, id, postish), wool0 = stock(level, id, s -> s.is(ItemTags.WOOL));
            String why = Festivals.setUpForTests(level, v, Festivals.Feast.MAYPOLE);
            List<int[]> placed = Festivals.placedForTests(id, Festivals.Feast.MAYPOLE);
            Kit.log("sf22 the maypole: " + (why == null ? "up" : why) + ", " + placed.size() + " blocks; stores: posts " + posts0 + " -> "
                + stock(level, id, postish) + ", wool " + wool0 + " -> " + stock(level, id, s -> s.is(ItemTags.WOOL)));
            helper.assertTrue(why == null && placed.size() >= Festivals.POLE + 2 && placed.size() <= Festivals.POLE + 5,
                "the pole up, five posts and wool of several colours: " + why + ", " + placed.size());
            BlockPos base = new BlockPos(placed.get(0)[0], placed.get(0)[1], placed.get(0)[2]);
            for (int i = 0; i < Festivals.POLE; i++) {
                BlockState post = level.getBlockState(base.above(i));
                helper.assertTrue(post.getBlock() instanceof FenceBlock || post.is(net.minecraft.tags.BlockTags.LOGS),
                    "a post at " + i + " up the pole: " + post);
            }
            helper.assertTrue(level.getBlockState(base.above(Festivals.POLE)).is(net.minecraft.tags.BlockTags.WOOL), "wool at its head");
            helper.assertTrue(Math.max(Math.abs(base.getX() - v.centre().getX()), Math.abs(base.getZ() - v.centre().getZ())) <= 15,
                "on the square: " + base.toShortString() + " from " + v.centre().toShortString());
            helper.assertTrue(posts0 - stock(level, id, postish) == Festivals.POLE
                && wool0 - stock(level, id, s -> s.is(ItemTags.WOOL)) == placed.size() - Festivals.POLE,
                "the posts and the wool out of the stores, a block of wool to each colour");
            // The dance kept: the chronicle, the memories, the town's contentment.
            Festivals.closeForTests(level, v, Festivals.Feast.MAYPOLE, folk);
            long today = level.getDayTime() / 24000L;
            List<String> good = new ArrayList<>();
            int glow = Festivals.contentmentForTests(id, today, good);
            Kit.log("sf22 kept: chronicle " + chronicle(id) + "; contentment +" + glow + " " + good + "; memories "
                + folk.get(1).persona().memories());
            helper.assertTrue(chronicled(id, "danced round the maypole"), "into the chronicle");
            helper.assertTrue(Festivals.keptFor(id, Festivals.Feast.MAYPOLE) == Festivals.dayThisYear(id, today, Festivals.Feast.MAYPOLE),
                "kept for this year");
            helper.assertTrue(glow > 0 && good.contains("the May dance kept"), "the town the happier for it: " + good);
            helper.assertTrue(folk.get(1).persona().memories().stream().anyMatch(mm -> mm.text().contains("May dance")), "remembered");
            // The morning after: down, and back into the stores.
            level.setDayTime((today + 1) * 24000L + 2000L);
            Festivals.takeDownForTests(level, v);
            Kit.log("sf22 the morning after: " + Festivals.placedForTests(id, Festivals.Feast.MAYPOLE).size() + " blocks up; stores: posts "
                + stock(level, id, postish) + ", wool " + stock(level, id, s -> s.is(ItemTags.WOOL)));
            helper.assertTrue(Festivals.placedForTests(id, Festivals.Feast.MAYPOLE).isEmpty() && level.getBlockState(base).isAir()
                && level.getBlockState(base.above(Festivals.POLE)).isAir(), "the pole is down");
            helper.assertTrue(stock(level, id, postish) == posts0 && stock(level, id, s -> s.is(ItemTags.WOOL)) == wool0,
                "every post and every block of wool back in the stores");
            helper.succeed();
        });
    }

    // ============================================================ sf23: the May dance

    @GameTest(template = EMPTY, timeoutTicks = 2600, batch = "sf23_may_dance")
    public static void sf23_may_dance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 624000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 30);
        long day = level.getDayTime() / 24000L + 2;
        final long base = day * 24000L + 9000L;
        level.setDayTime(base);
        List<VillageFolkEntity> folk = town(helper, level, heart, 7);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.OAK_FENCE, 8), new ItemStack(Items.OAK_LOG, 4),
            new ItemStack(Items.RED_WOOL, 2), new ItemStack(Items.LIME_WOOL, 2), new ItemStack(Items.PINK_WOOL, 2));
        BlockPos[] pole = new BlockPos[1];
        Map<UUID, Double> startAngle = new HashMap<>();
        long[] mingleFrom = { -1 };
        helper.runAtTickTime(10, () -> {
            String why = Festivals.setUpForTests(level, v, Festivals.Feast.MAYPOLE);
            helper.assertTrue(why == null, "the maypole up: " + why);
            int[] p = Festivals.placedForTests(id, Festivals.Feast.MAYPOLE).get(0);
            pole[0] = new BlockPos(p[0], p[1], p[2]);
            boolean called = Festivals.callNow(level, v, Festivals.Feast.MAYPOLE);
            Kit.log("sf23 the maypole at " + pole[0].toShortString() + "; the town called: " + called + " " + Assemblies.debug(id));
            helper.assertTrue(called, "the May dance called: " + Assemblies.debug(id));
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t <= 10 || pole[0] == null) return;
            if (level.getDayTime() - base > 3000L) level.setDayTime(base);         // the afternoon, held
            int[] pr = Assemblies.progress(id);
            if (t % 200 == 0) Kit.log("sf23 @" + t + ": " + Assemblies.debug(id) + " " + Assemblies.now(id));
            helper.assertTrue(pr != null || mingleFrom[0] > 0, "the dance under way: " + Assemblies.debug(id));
            if (pr == null) return;
            if (pr[0] == 3 && mingleFrom[0] < 0) {                     // the dancing (Assemblies.Phase.MINGLE)
                mingleFrom[0] = t;
                for (VillageFolkEntity f : folk) {
                    if (f.getUUID().equals(Villages.elder(id))) continue;
                    startAngle.put(f.getUUID(), Math.atan2(f.getZ() - pole[0].getZ() - 0.5, f.getX() - pole[0].getX() - 0.5));
                }
                Kit.log("sf23 the dancing begins at tick " + t + " with " + pr[1] + " in the ring of " + pr[5] + " places");
            }
            if (mingleFrom[0] > 0 && t - mingleFrom[0] >= 300) {
                int moved = 0, near = 0;
                StringBuilder sb = new StringBuilder();
                for (VillageFolkEntity f : folk) {
                    Double a0 = startAngle.get(f.getUUID());
                    if (a0 == null) continue;
                    double dx = f.getX() - pole[0].getX() - 0.5, dz = f.getZ() - pole[0].getZ() - 0.5;
                    double a1 = Math.atan2(dz, dx), turned = Math.abs(Math.atan2(Math.sin(a1 - a0), Math.cos(a1 - a0)));
                    double r = Math.sqrt(dx * dx + dz * dz);
                    if (turned > Math.toRadians(25)) moved++;
                    if (r < 12) near++;
                    sb.append(String.format(java.util.Locale.ROOT, " %s %.0f° at %.1f;", f.displayNameCap(), Math.toDegrees(turned), r));
                }
                Kit.log("sf23 after " + (t - mingleFrom[0]) + " ticks of dancing:" + sb);
                helper.assertTrue(near >= 3, "the town dances round the pole: " + near + " near it");
                helper.assertTrue(moved >= 3, "the ring goes round: " + moved + " moved round the pole by a good step");
                helper.succeed();
            }
            if (t > 2500) helper.fail("sf23 no dancing by tick " + t + ": " + Assemblies.debug(id));
        });
    }

    // ============================================================ sf24: the midsummer bonfire

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf24_bonfire")
    public static void sf24_bonfire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 626000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 26);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 11500L);
        List<VillageFolkEntity> folk = town(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.OAK_LOG, 20), new ItemStack(Items.COAL, 6), new ItemStack(Items.STICK, 4),
            new ItemStack(Items.OAK_PLANKS, 8));
        helper.runAtTickTime(10, () -> {
            // Sticks, and planks at two sticks each, before (the founding stores have planks of their own).
            int sticksBefore = stock(level, id, s -> s.is(Items.STICK)) + stock(level, id, s -> s.is(ItemTags.PLANKS)) * 2;
            String why = Festivals.setUpForTests(level, v, Festivals.Feast.BONFIRE);
            List<int[]> fires = Festivals.placedForTests(id, Festivals.Feast.BONFIRE);
            int lit = 0;
            for (int[] p : fires) {
                BlockState st = level.getBlockState(new BlockPos(p[0], p[1], p[2]));
                if (st.is(Blocks.CAMPFIRE) && st.getValue(CampfireBlock.LIT)) lit++;
            }
            int logs = stock(level, id, s -> s.is(ItemTags.LOGS)), coal = stock(level, id, s -> s.is(Items.COAL));
            Kit.log("sf24 the bonfire: " + (why == null ? "lit" : why) + ", " + fires.size() + " campfires, " + lit + " lit; stores: logs " + logs
                + ", coal " + coal + ", sticks " + stock(level, id, s -> s.is(Items.STICK)) + ", planks " + stock(level, id, s -> s.is(ItemTags.PLANKS)));
            helper.assertTrue(why == null && fires.size() == 5 && lit == 5, "five campfires, lit: " + fires.size() + ", " + lit);
            helper.assertTrue(logs == 5 && coal == 1, "three logs and a coal a fire, out of the stores: logs " + logs + ", coal " + coal);
            helper.assertTrue(sticksBefore - stock(level, id, s -> s.is(Items.STICK)) - stock(level, id, s -> s.is(ItemTags.PLANKS)) * 2 == 15,
                "three sticks a fire, the stores' sticks and then their planks sawn: "
                    + stock(level, id, s -> s.is(Items.STICK)) + " sticks, " + stock(level, id, s -> s.is(ItemTags.PLANKS)) + " planks");
            List<String> lines = Festivals.scriptForTests(level, v, Festivals.Feast.BONFIRE);
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("Midsummer")) && lines.stream().anyMatch(l -> l.contains("sing")),
                "the elder's words: " + lines);
            Festivals.closeForTests(level, v, Festivals.Feast.BONFIRE, folk);
            helper.assertTrue(chronicled(id, "midsummer bonfire was lit"), "into the chronicle: " + chronicle(id));
            // Midnight: out, the ground clear, what is left of the fires into the stores.
            long today = level.getDayTime() / 24000L;
            level.setDayTime(today * 24000L + 18100L);
            Festivals.takeDownForTests(level, v);
            int clear = 0;
            for (int[] p : fires) if (level.getBlockState(new BlockPos(p[0], p[1], p[2])).isAir()) clear++;
            int charcoal = stock(level, id, s -> s.is(Items.CHARCOAL));
            Kit.log("sf24 at midnight: " + clear + " of " + fires.size() + " spots clear; charcoal in the stores " + charcoal);
            helper.assertTrue(clear == fires.size() && Festivals.placedForTests(id, Festivals.Feast.BONFIRE).isEmpty(), "put out, the spot made good");
            helper.assertTrue(charcoal == 10, "two charcoal a fire, as a campfire leaves: " + charcoal);
            helper.succeed();
        });
    }

    // ============================================================ sf25: the town fair

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf25_fair")
    public static void sf25_fair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 628000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 26);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 9000L);
        List<VillageFolkEntity> folk = town(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity farmer = folk.get(1), rancher = folk.get(2), fisher = folk.get(3);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.RED_WOOL, 10), new ItemStack(Items.PAPER, 3));
        helper.runAtTickTime(10, () -> {
            long today = level.getDayTime() / 24000L;
            // One hand to each class's trade (the rest at the woods, which shows at no fair).
            for (VillageFolkEntity f : folk) if (f != farmer && f != rancher && f != fisher) f.setJob(StationTask.WOOD);
            farmer.setJob(StationTask.FARM);
            rancher.setJob(StationTask.RANCH);
            fisher.setJob(StationTask.FISH);
            for (VillageFolkEntity f : folk) {
                for (ItemStack s : f.getInventoryItems()) if (Fair.of(s) != null) s.setCount(0);   // only what the test gives them
            }
            farmer.insertItem(new ItemStack(Items.BREAD, 8));
            fisher.insertItem(new ItemStack(Items.COD, 1));
            Ledger.addCoins(id, 40);
            int coinsBefore = Ledger.coins(id);
            Festivals.turnTo(id, today, Festivals.FAIR_DAY);
            helper.assertTrue(Fair.open(level, v), "fair day: entries open");
            int storesBread = stock(level, id, s -> s.is(Items.BREAD)), storesRed = stock(level, id, s -> s.is(Items.RED_WOOL));
            Player you = helper.makeMockPlayer(GameType.SURVIVAL);
            you.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 64));      // a full stack: the most an entry takes
            String said = Fair.enter(level, you, v, you.getMainHandItem());
            Kit.log("sf25 a player enters: " + said + "; entries " + Fair.entriesForTests(id));
            helper.assertTrue(said.startsWith("Entered for the best bread") && you.getMainHandItem().isEmpty(), "taken into the fair's keeping: " + said);
            // The judging, as the gathering holds it.
            Festivals.closeForTests(level, v, Festivals.Feast.FAIR, folk);
            List<String> results = Fair.resultsForTests(id);
            Kit.log("sf25 the ribbons: " + results + " (the stores held " + storesBread + " loaves); treasury " + coinsBefore + " -> "
                + Ledger.coins(id) + "; owed the player " + Festivals.owedForTests(id) + "; chronicle " + chronicle(id));
            helper.assertTrue(results.size() == 3, "three classes judged (no honey): " + results);
            helper.assertTrue(results.get(0).startsWith("best bread: " + you.getName().getString()) && results.get(0).contains("64 loaves"),
                "a guest's stack of loaves beats the town's baking (or ties it, and entered first): " + results.get(0));
            helper.assertTrue(results.stream().anyMatch(r -> r.startsWith("best wool: " + rancher.displayNameCap()) && r.contains(storesRed + " red wool")),
                "the rancher's entry of the stores' wool: " + results);
            helper.assertTrue(results.stream().anyMatch(r -> r.startsWith("biggest fish: " + fisher.displayNameCap()) && r.contains("cod of")),
                "the fisher's cod: " + results);
            helper.assertTrue(coinsBefore - Ledger.coins(id) == 3 * Fair.PURSE, "a purse a class out of the treasury: "
                + coinsBefore + " -> " + Ledger.coins(id));
            helper.assertTrue(count(farmer, s -> s.is(Items.BREAD)) == 8, "the farmer's ration loaves never entered, still in its pack: "
                + count(farmer, s -> s.is(Items.BREAD)));
            helper.assertTrue(stock(level, id, s -> s.is(Items.BREAD)) == storesBread, "the stores' bread entered and back: "
                + storesBread + " -> " + stock(level, id, s -> s.is(Items.BREAD)));
            helper.assertTrue(stock(level, id, s -> s.is(Items.RED_WOOL)) == storesRed, "the stores' wool back in the stores");
            helper.assertTrue(stock(level, id, s -> s.is(Items.PAPER)) == 0, "three ribbons of the stores' paper");
            helper.assertTrue(count(rancher, s -> s.is(Items.PAPER) && s.has(DataComponents.CUSTOM_NAME)
                && s.getHoverName().getString().startsWith("Ribbon: best wool, Year") && Homes.isKeepsake(s)) == 1, "the rancher keeps its ribbon");
            helper.assertTrue(Festivals.owedForTests(id) == 3, "the player away: their bread, ribbon and purse kept for them: " + Festivals.owedForTests(id));
            helper.assertTrue(chronicled(id, "the town fair was held"), "into the chronicle");
            List<String> board = TownCalendar.board(level, id);
            helper.assertTrue(board.stream().anyMatch(l -> l.contains("The fair's ribbons")), "on the board: " + board);
            helper.assertTrue(!Fair.open(level, v), "judged: entries closed");
            helper.succeed();
        });
    }

    // ============================================================ sf26: the harvest festival

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf26_harvest")
    public static void sf26_harvest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 630000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 26);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 9000L);
        List<VillageFolkEntity> folk = town(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity a = folk.get(1), b = folk.get(2), c = folk.get(3);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.OAK_PLANKS, 12));
        helper.runAtTickTime(10, () -> {
            long today = level.getDayTime() / 24000L;
            Festivals.turnTo(id, today, Festivals.HARVEST_DAY);
            a.setJob(StationTask.FARM);
            b.setJob(StationTask.FARM);
            c.setJob(StationTask.FISH);
            Economy.produced(a, new ItemStack(Items.BEETROOT, 40));
            Economy.produced(b, new ItemStack(Items.BEETROOT, 25));
            Economy.produced(c, new ItemStack(Items.COD, 12));
            double[] h = Festivals.harvestForTests(id);
            Kit.log("sf26 the year's harvest so far: " + java.util.Arrays.toString(h));
            helper.assertTrue(Math.round(h[0]) == 65 && Math.round(h[1]) == 12, "counted as it is brought in, by where from: "
                + java.util.Arrays.toString(h));
            Ledger.addCoins(id, 30);
            int coins = Ledger.coins(id), purse = a.purse(), planks = stock(level, id, s -> s.is(ItemTags.PLANKS));
            String why = Festivals.setUpForTests(level, v, Festivals.Feast.HARVEST);
            List<int[]> tables = Festivals.placedForTests(id, Festivals.Feast.HARVEST);
            int top = 0;
            for (int[] p : tables) {
                BlockState st = level.getBlockState(new BlockPos(p[0], p[1], p[2]));
                if (st.is(net.minecraft.tags.BlockTags.WOODEN_SLABS) && st.getValue(SlabBlock.TYPE) == SlabType.TOP) top++;
            }
            Kit.log("sf26 the long tables: " + (why == null ? "laid" : why) + ", " + tables.size() + " slabs, " + top + " set high; stores planks "
                + stock(level, id, s -> s.is(ItemTags.PLANKS)) + ", slabs " + stock(level, id, s -> s.is(ItemTags.WOODEN_SLABS)));
            helper.assertTrue(why == null && tables.size() == 2 * Festivals.TABLE && top == tables.size(),
                "two long tables of slabs set high: " + tables.size() + ", " + top);
            helper.assertTrue(planks - stock(level, id, s -> s.is(ItemTags.PLANKS)) == 9, "nine planks sawn into eighteen slabs: "
                + planks + " -> " + stock(level, id, s -> s.is(ItemTags.PLANKS)));
            Festivals.closeForTests(level, v, Festivals.Feast.HARVEST, folk);
            Kit.log("sf26 kept: purse " + purse + " -> " + a.purse() + ", treasury " + coins + " -> " + Ledger.coins(id) + "; chronicle " + chronicle(id));
            helper.assertTrue(a.purse() - purse == 10 && coins - Ledger.coins(id) == 10, "the best farmer's prize, out of the treasury");
            helper.assertTrue(chronicled(id, "65 meals from the fields, 12 from the water") && chronicled(id, a.displayNameCap() + " took the farmer's prize"),
                "the year's harvest and the prize in the chronicle: " + chronicle(id));
            helper.assertTrue(a.persona().memories().stream().anyMatch(m -> m.text().contains("harvest prize")), "the winner remembers it");
            level.setDayTime((today + 1) * 24000L + 2000L);
            Festivals.takeDownForTests(level, v);
            int slabs = stock(level, id, s -> s.is(Items.OAK_SLAB));
            Kit.log("sf26 the morning after: " + Festivals.placedForTests(id, Festivals.Feast.HARVEST).size() + " left; slabs in the stores " + slabs);
            helper.assertTrue(Festivals.placedForTests(id, Festivals.Feast.HARVEST).isEmpty() && slabs == 18, "the tables cleared into the stores: " + slabs);
            helper.succeed();
        });
    }

    // ============================================================ sf27: midwinter

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf27_midwinter")
    public static void sf27_midwinter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 632000;
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        BlockPos heart = flat(level, x, Z, 50);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 2000L);
        List<VillageFolkEntity> folk = town(helper, level, heart, 5);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity giver = folk.get(1), friend = folk.get(2);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.LANTERN, 6), new ItemStack(Items.TORCH, 6), new ItemStack(Items.CANDLE, 4));
        helper.runAtTickTime(10, () -> {
            long today = level.getDayTime() / 24000L;
            Festivals.turnTo(id, today, Festivals.MIDWINTER_DAY);
            for (VillageFolkEntity f : folk) {
                f.clearQueue();
                f.setNoAi(true);
            }
            giver.earn(20);
            giver.life().feel(friend.getUUID(), friend.displayNameCap(), 60);
            // At the stores' counter (no shop in a town this young), the friend beside it.
            BlockPos c = v.centre();
            giver.moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
            friend.moveTo(c.getX() + 1.5, c.getY(), c.getZ() + 0.5);
            int lightsBefore = stock(level, id, s -> s.is(Items.LANTERN) || s.is(Items.TORCH));
            String why = Festivals.setUpForTests(level, v, Festivals.Feast.MIDWINTER);
            List<int[]> lights = Festivals.placedForTests(id, Festivals.Feast.MIDWINTER);
            int lanterns = 0, torches = 0;
            for (int[] p : lights) {
                BlockState st = level.getBlockState(new BlockPos(p[0], p[1], p[2]));
                if (st.is(Blocks.LANTERN)) lanterns++;
                if (st.is(Blocks.TORCH)) torches++;
            }
            Kit.log("sf27 the lights: " + (why == null ? "out" : why) + ", " + lanterns + " lanterns and " + torches + " torches; planned "
                + Midwinter.plannedForTests(id));
            helper.assertTrue(lanterns == 6 && torches == 6, "the stores' six lanterns, then torches, along the avenue: " + lanterns + ", " + torches);
            helper.assertTrue(lightsBefore - stock(level, id, s -> s.is(Items.LANTERN) || s.is(Items.TORCH)) == 12
                && stock(level, id, s -> s.is(Items.LANTERN)) == 0, "out of the stores");
            helper.assertTrue(friend.getUUID().equals(Midwinter.plannedForTests(id).get(giver.getUUID())), "a present planned for its friend");
            // Evening, off work: to the counter, bought, and given.
            level.setDayTime(today * 24000L + 13000L);
            // The sky darkens once a tick (Level.updateSkyBrightness): without this, set straight from the morning,
            // it is still day by the sky, a day hand is still on its shift, and the present waits for its own time.
            level.updateSkyBrightness();
            Kit.log("sf27 the evening: night " + level.isNight() + ", the giver off work " + giver.offWorkNow() + ", a job "
                + giver.peekJob() + ", at a gathering " + Assemblies.attending(giver) + ", asleep " + giver.isSleeping()
                + ", bedtime " + giver.bedtimeTick() + ", purse " + giver.purse());
            helper.assertTrue(giver.offWorkNow(), "evening: the giver is off work");
            int purse = giver.purse(), coins = Ledger.coins(id);
            boolean busy1 = Festivals.hold(giver, level);
            boolean busy2 = Festivals.hold(giver, level);
            List<String> given = Midwinter.givenForTests(id);
            Kit.log("sf27 the present: " + busy1 + "/" + busy2 + " " + given + "; purse " + purse + " -> " + giver.purse() + ", treasury " + coins
                + " -> " + Ledger.coins(id));
            helper.assertTrue(given.size() == 1 && given.get(0).startsWith(giver.displayNameCap() + "|" + friend.displayNameCap() + "|"),
                "given to its friend: " + given);
            helper.assertTrue(giver.purse() < purse && Ledger.coins(id) - coins == purse - giver.purse(), "paid for out of its own purse, into the treasury");
            helper.assertTrue(friend.persona().memories().stream().anyMatch(m -> m.text().contains("for midwinter")), "remembered");
            // The morning after: the lights in, a lantern at a time.
            level.setDayTime((today + 1) * 24000L + 2000L);
            for (int i = 0; i < 14; i++) Festivals.takeDownForTests(level, v);
            Kit.log("sf27 the morning after: " + Festivals.placedForTests(id, Festivals.Feast.MIDWINTER).size() + " lights out; stores: lanterns "
                + stock(level, id, s -> s.is(Items.LANTERN)) + ", torches " + stock(level, id, s -> s.is(Items.TORCH)) + "; chronicle " + chronicle(id));
            helper.assertTrue(Festivals.placedForTests(id, Festivals.Feast.MIDWINTER).isEmpty() && stock(level, id, s -> s.is(Items.LANTERN)) == 6
                && stock(level, id, s -> s.is(Items.LANTERN) || s.is(Items.TORCH)) == lightsBefore, "every light back in the stores"); 
            helper.assertTrue(chronicled(id, "midwinter was kept: lanterns along the avenue, and 1 present"), "into the chronicle: " + chronicle(id));
            for (VillageFolkEntity f : folk) f.setNoAi(false);
            helper.succeed();
        });
    }

    // ============================================================ sf28: winter in town

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf28_snowman")
    public static void sf28_snowman(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 634000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 26);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 8000L);
        List<VillageFolkEntity> folk = town(helper, level, heart, 5);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity kid = folk.get(4);
        Container box = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.SNOWBALL, 4));
        helper.runAtTickTime(10, () -> {
            Sweepers.snowyForTests(true);
            try {
                long today = level.getDayTime() / 24000L;
                Festivals.turnTo(id, today, 22);
                kid.setStation(null, StationTask.NONE);
                kid.setChild(true);
                kid.childhoodForTests(1);
                // Snow lying round the square, three layers deep.
                BlockPos c = v.centre();
                int laid = 0;
                for (int dx = -10; dx <= 10; dx++) {
                    for (int dz = -10; dz <= 10; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) < 3 || (dx + dz) % 3 != 0) continue;
                        BlockPos top = Kit.surface(level, c.getX() + dx, c.getZ() + dz);
                        if (!level.getBlockState(top).isAir() || !level.getBlockState(top.below()).is(Blocks.GRASS_BLOCK)) continue;
                        level.setBlock(top, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3), 3);
                        laid++;
                    }
                }
                String first = Winter.buildForTests(level, v, kid);
                List<BlockPos> snow = Festivals.snowmenForTests(id);
                Kit.log("sf28 " + laid + " patches of snow; the first snowman: " + first + "; blocks " + snow + "; stores snowballs "
                    + stock(level, id, s -> s.is(Items.SNOWBALL)) + ", shovel " + stock(level, id, s -> s.is(ItemTags.SHOVELS)));
                helper.assertTrue(first.startsWith("a snowman of snow") && snow.size() == 3, "a snowman of three blocks of snow: " + first);
                BlockPos at = snow.get(0);
                helper.assertTrue(level.getBlockState(at).is(Blocks.SNOW_BLOCK) && level.getBlockState(at.above()).is(Blocks.SNOW_BLOCK)
                    && level.getBlockState(at.above(2)).is(Blocks.SNOW_BLOCK), "stacked by the playground at " + at.toShortString());
                helper.assertTrue(stock(level, id, s -> s.is(ItemTags.SHOVELS)) == 1, "the stores' shovel lent and put back");
                helper.assertTrue(chronicled(id, "built a snowman"), "into the chronicle");
                // A carved pumpkin in the stores: a face for the next, and as in the game it wakes.
                box.setItem(5, new ItemStack(Items.CARVED_PUMPKIN));
                box.setChanged();
                String second = Winter.buildForTests(level, v, kid);
                int golems = level.getEntitiesOfClass(SnowGolem.class, new AABB(c).inflate(16)).size();
                Kit.log("sf28 the second snowman: " + second + "; snow golems about " + golems + "; pumpkins in the stores "
                    + stock(level, id, s -> s.is(Items.CARVED_PUMPKIN)));
                helper.assertTrue(stock(level, id, s -> s.is(Items.CARVED_PUMPKIN)) == 0, "the stores' pumpkin for its face");
                helper.assertTrue(second.contains("came to life") ? golems >= 1 : second.startsWith("a snowman with a pumpkin face"),
                    "a pumpkin-faced snowman (or a snow golem, woken): " + second);
                // The cold: snow talk here, frost talk where it does not snow.
                String snowy = talk(level, folk.get(1), folk.get(2));
                Sweepers.snowyForTests(false);
                String frosty = talk(level, folk.get(1), folk.get(2));
                Kit.log("sf28 winter talk where it snows: " + snowy + "; where it does not: " + frosty);
                helper.assertTrue(snowy != null && (snowy.contains("Cold") || snowy.contains("Snow") || snowy.contains("Brr")),
                    "folk say it is cold: " + snowy);
                helper.assertTrue(frosty != null && !frosty.contains("Snow again") && (frosty.contains("frost") || frosty.contains("Frost")
                    || frosty.contains("Chilly")), "frost talk where no snow falls: " + frosty);
                // The thaw.
                Festivals.turnTo(id, today, 0);
                Festivals.takeDownForTests(level, v);
                Kit.log("sf28 the thaw: " + Festivals.snowmenForTests(id) + " left; at the spot " + level.getBlockState(at));
                helper.assertTrue(level.getBlockState(at).isAir() && level.getBlockState(at.above(2)).isAir(), "gone with the thaw");
                helper.succeed();
            } finally {
                Sweepers.snowyForTests(null);
            }
        });
    }

    /** What the season has two folk say, on the first throw of the dice that has them say anything. */
    private static String talk(ServerLevel level, VillageFolkEntity a, VillageFolkEntity b) {
        for (int seed = 0; seed < 60; seed++) {
            List<String[]> t = Seasons.talkForTests(a, b, level, RandomSource.create(seed));
            if (!t.isEmpty()) return String.join(" / ", t.get(0));
        }
        return null;
    }
}
