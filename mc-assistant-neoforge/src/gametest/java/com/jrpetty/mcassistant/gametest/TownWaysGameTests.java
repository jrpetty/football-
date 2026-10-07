package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Colonies;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Architecture;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Beliefs;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Cuisine;
import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.Droughts;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Gatherings;
import com.jrpetty.mcassistant.entity.Graves;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Palettes;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.TownFeast;
import com.jrpetty.mcassistant.entity.TownSpeech;
import com.jrpetty.mcassistant.entity.TownWays;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.DishItems;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [culture2] A town's own ways: what it eats, how it talks, how it builds, what it feasts and what it believes. Walking
 * from one town into the next must look, sound and taste different, and these say so in numbers.
 *
 * <ul>
 * <li><b>cw01</b>: a harbour town and a hill town, raised side by side, come out different in every way: fish stew and
 *     hotpot, Coastal houses and the Hill Fort, the Herring Fair and the Stone Feast, the Sea and the Stone; "Fair winds"
 *     and "Steady stone"; the hold and the vault; their sayings, their board's line, their page and their talk.</li>
 * <li><b>cw02</b>: each town's cook makes its own dish out of its stores by the game's own recipe, up to the number it
 *     keeps and no more, and never the other town's; served first at the feast, it lifts a folk's spirits by six; it
 *     is first on the café's counter, and a player orders it at the bar for coin into the treasury.</li>
 * <li><b>cw03</b>: a colony founded by the harbour town takes its fish stew to the hills and makes it with mutton (the
 *     lore says so); a couple of the mother's own are carried there on a carrier's back, and a settler who eats one has a
 *     treat.</li>
 * <li><b>cw04</b>: greetings, farewells and the town's own words differ; a real drought coins "dry as day N" in the one
 *     town and a raid at the north gate "steady as the north gate held" in the other; five sayings at least; a smith with
 *     forty pieces off the anvil is "Ironhand"; folk answer "Any sayings?" and talk of them to each other.</li>
 * <li><b>cw05</b>: one house drawing stamped in both towns and dressed out of their stores: Coastal (polished diorite,
 *     birch posts, tinted glass, the town's banners, a barrel by the door) and Hill Fort (deepslate brick, slate roof,
 *     battlements, side windows walled up), what came off back into the stores; and all six styles on six houses differ.</li>
 * <li><b>cw06</b>: the Herring Fair: stalls and lanterns set out round the square out of the stores; the fair called and
 *     held; the town's fish stew eaten at it; the best catch judged, its winner paid a purse and written into the
 *     chronicle; a year on, the catch since the last fair wins; the stalls taken in again.</li>
 * <li><b>cw07</b>: a Sea town gives its dead to the sea: a post and a board on the shore out of the stores and a flower on
 *     the water, no headstone in the yard; the vigil and the weddings on the shore; nobody fishes on the Sea's day (the
 *     fleet kept in, the fisher off work), the farmer works; the morning's rite gives the sea a fish and blesses the
 *     fishers; its children have names of the sea.</li>
 * <li><b>cw08</b>: a Stone town raises a cairn on the high ground for its dead; hangs the first diamond in its chapel out
 *     of the stores; its miners rest on the Stone's day; its children are named for stones.</li>
 * <li><b>cw09</b>: the other faiths' marks: the Harvest's farmers rest on its day and its fields are blessed, its children
 *     named for the green; the shrine's token changes with the faith (the old one back to the stores); the Hearth weds
 *     sooner; the Founders' first house is never touched.</li>
 * <li><b>cw10</b>: a plains town builds Timbered Lowland (dark oak bands, shutters, smoke from the chimney, shingle kept
 *     through the ages, flower boxes everywhere, dark oak lamp posts); a little richer it stays; rich, big in the Iron
 *     Age and merchant at heart it turns Grand Civic, says so, and its houses, lamp posts and benches follow.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 1,500,000 to 1,519,999, z 66,000, in a batch of its own, calling the
 * town's logic directly and logging what it sees.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownWaysGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ============================================================ the ground, the towns, the stores

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
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

    private record Town(UUID id, BlockPos heart, List<VillageFolkEntity> folk) {}

    /** A clean slate, clear skies, and the clock at this hour of a day two on. The day. */
    private static long start(ServerLevel level, long hour) {
        Kit.reset(level);
        level.setWeatherParameters(24000, 0, false, false);
        level.setRainLevel(0.0F);
        level.setThunderLevel(0.0F);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + hour);
        return day;
    }

    /**
     * A town of so many folk on flat ground at this x, of this land. Their pastimes are cards, every one: no stargazer
     * tips the town's faith or its festival, so what the test sets is what decides.
     */
    private static Town town(GameTestHelper helper, ServerLevel level, int x, int n, Homeland.Land land) {
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + i * 3, 0, 7), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        for (VillageFolkEntity f : folk) {
            helper.assertTrue(id != null && id.equals(f.ownerId()), "all of one town");
            Culture.hobbyForTests(f, Persona.Hobby.CARDS);
        }
        Homeland.setForTests(id, land);
        return new Town(id, heart, folk);
    }

    /**
     * The town's folk after its founder set to these trades, in order (the founder keeps its own): what the town is
     * known for counts toward its faith, its festival and its second dish, so the tests say what it is.
     */
    private static void trades(Town t, StationTask... tasks) {
        for (int i = 0; i < tasks.length && i + 1 < t.folk().size(); i++) t.folk().get(i + 1).setJob(tasks[i]);
    }

    /** A town's name in a possessive, as its folk write it: "Ashhaven's", "Ashholmes'". */
    private static String of(String name) {
        return name.endsWith("s") ? name + "'" : name + "'s";
    }

    /** A plain stone mound, its top {@code height} up at the middle, sloping a block a step: the town's high ground. */
    private static void mound(ServerLevel level, int cx, int cz, int half, int height) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                int h = height - ring;
                if (h <= 0) continue;
                BlockPos g = Kit.surface(level, cx + dx, cz + dz);
                for (int i = 0; i < h; i++) level.setBlock(g.above(i), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    private static Villages.Village village(GameTestHelper helper, UUID id) {
        Villages.Village v = Villages.get(id);
        helper.assertTrue(v != null, "the village is on the books");
        return v;
    }

    /** A marked store chest at exactly this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static void put(Container box, ItemStack s) {
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).isEmpty()) {
                box.setItem(i, s);
                box.setChanged();
                Villages.forgetStock();
                return;
            }
        }
    }

    private static Item item(String name) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(name));
    }

    private static int stock(ServerLevel level, UUID id, Item it) {
        Villages.forgetStock();
        return Market.stock(level, id, s -> s.is(it));
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private static String history(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append(" | ").append(e.text());
        return sb.toString();
    }

    /** The books with food to spare: the cook may cook (Cuisine.cook never does on short commons). */
    private static void plenty(UUID village, long day) {
        Leader.booksForTests(village, new Leader.Books(400, 40, 20, 40.0, 20.0, 20.0, Leader.Plan.PLENTY, day));
    }

    /** The first day from this one (this one too) that is the town's sacred day, or -1. */
    private static long sacredFrom(UUID village, long from) {
        for (long d = from; d < from + 8; d++) if (Beliefs.sacred(village, d)) return d;
        return -1;
    }

    /** A day from this one that is not its sacred day. */
    private static long plainFrom(UUID village, long from) {
        for (long d = from; d < from + 8; d++) if (!Beliefs.sacred(village, d)) return d;
        return -1;
    }

    /** What a building's box holds, block by block. */
    private static Map<Block, Integer> blocks(ServerLevel level, BlockPos anchor) {
        Map<Block, Integer> out = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-5, -1, -5), anchor.offset(5, 11, 5))) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir()) out.merge(s.getBlock(), 1, Integer::sum);
        }
        return out;
    }

    private static int count(Map<Block, Integer> m, Block b) {
        return m.getOrDefault(b, 0);
    }

    private static int countTag(Map<Block, Integer> m, java.util.function.Predicate<Block> what) {
        int n = 0;
        for (Map.Entry<Block, Integer> e : m.entrySet()) if (what.test(e.getKey())) n += e.getValue();
        return n;
    }

    /** How many places differ, block for block, between two buildings' boxes (each by its own anchor). */
    private static int differ(ServerLevel level, BlockPos a, BlockPos b) {
        int n = 0;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -1; dy <= 11; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    if (!level.getBlockState(a.offset(dx, dy, dz)).getBlock().equals(level.getBlockState(b.offset(dx, dy, dz)).getBlock())) n++;
                }
            }
        }
        return n;
    }

    private static String[] signLines(ServerLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) return null;
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) out[i] = sign.getFrontText().getMessage(i, false).getString();
        return out;
    }

    private static Player player(GameTestHelper helper, BlockPos at, int coins) {
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (coins > 0) you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return you;
    }

    // ============================================================ cw01: two towns, two ways

    /** A harbour town and a hill town come out different in every one of their ways, and say so everywhere. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw01_two_towns")
    public static void cw01_two_towns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town sea = town(helper, level, 1500000, 4, Homeland.Land.COAST);
        Town hill = town(helper, level, 1500400, 4, Homeland.Land.MOUNTAIN);
        helper.assertTrue(!sea.id().equals(hill.id()), "two towns, not one");
        TownWays.heartForTests(sea.id(), Values.Value.FOOD, Values.Value.FOOD);
        TownWays.heartForTests(hill.id(), Values.Value.SAFETY, Values.Value.SAFETY);
        helper.runAtTickTime(10, () -> {
            Villages.Village a = village(helper, sea.id()), b = village(helper, hill.id());
            trades(sea, StationTask.FISH, StationTask.FISH, StationTask.HAUL);
            trades(hill, StationTask.MINE, StationTask.MINE, StationTask.GUARD);
            for (Villages.Village v : List.of(a, b)) {
                TownWays.workOutForTests(level, v);
                TownFeast.chooseForTests(level, v);
            }
            UUID x = sea.id(), y = hill.id();
            Kit.log("cw01 " + Villages.name(x) + ": " + TownWays.summary(x));
            Kit.log("cw01 " + Villages.name(y) + ": " + TownWays.summary(y));
            for (String s : TownWays.lines(x)) Kit.log("cw01   " + s);
            for (String s : TownWays.lines(y)) Kit.log("cw01   " + s);

            // What each eats, builds, feasts and believes: its land's and its temper's.
            helper.assertTrue(Cuisine.dishOf(x) == Cuisine.Dish.FISH_STEW, "the harbour town's dish is fish stew: " + Cuisine.dishOf(x));
            helper.assertTrue(Cuisine.dishOf(y) == Cuisine.Dish.HOTPOT, "the hill town's is the miner's hotpot: " + Cuisine.dishOf(y));
            helper.assertTrue(Architecture.of(x) == Architecture.Style.COASTAL, "the harbour town builds Coastal: " + Architecture.of(x));
            helper.assertTrue(Architecture.of(y) == Architecture.Style.HILL_FORT, "the hill town builds a Hill Fort: " + Architecture.of(y));
            helper.assertTrue(TownFeast.of(x) == TownFeast.Feast.HERRING_FAIR, "the harbour town keeps the Herring Fair: " + TownFeast.of(x));
            helper.assertTrue(TownFeast.of(y) == TownFeast.Feast.STONE_FEAST, "the hill town keeps the Stone Feast: " + TownFeast.of(y));
            helper.assertTrue(Beliefs.of(x) == Beliefs.Belief.SEA, "the harbour town keeps faith with the Sea: " + Beliefs.of(x));
            helper.assertTrue(Beliefs.of(y) == Beliefs.Belief.STONE, "the hill town with the Stone: " + Beliefs.of(y));
            helper.assertTrue(Beliefs.burial(x) == Beliefs.Burial.SEA && Beliefs.burial(y) == Beliefs.Burial.CAIRN,
                "the one gives its dead to the sea, the other raises cairns");
            // Each choice is written into the town's history.
            helper.assertTrue(told(x, "took to making fish stew") && told(x, "chose to build in the Coastal way")
                && told(x, "keeps faith with the Sea") && told(x, "the Herring Fair every year"), "the harbour town's chronicle:" + history(x));
            helper.assertTrue(told(y, "the Hill Fort way") && told(y, "keeps faith with the Stone") && told(y, "the Stone Feast every year"),
                "the hill town's chronicle:" + history(y));
            // Its new buildings go up in its style's woods.
            helper.assertTrue(!Palettes.of(x).equals(Palettes.of(y)), "the builders reach for other woods: " + Palettes.of(x) + " / " + Palettes.of(y));

            // How they sound.
            VillageFolkEntity fa = sea.folk().get(1), fb = hill.folk().get(1);
            String ha = TownSpeech.helloForTests(fa, "Alex", false), hb = TownSpeech.helloForTests(fb, "Alex", false);
            String ta = TownSpeech.helloForTests(fa, "Alex", true), tb = TownSpeech.helloForTests(fb, "Alex", true);
            Kit.log("cw01 hellos: " + ha + " / " + hb + "; by temper " + ta + " / " + tb + "; farewells " + TownSpeech.farewell(x)
                + " / " + TownSpeech.farewell(y));
            helper.assertTrue(ha.equals("Fair winds, Alex!") && hb.equals("Steady stone, Alex!"), "each its land's hello: " + ha + " / " + hb);
            helper.assertTrue(ta.equals("Full larder, Alex!") && tb.equals("Stand fast, Alex!"), "and its temper's: " + ta + " / " + tb);
            helper.assertTrue(!TownSpeech.farewell(x).equals(TownSpeech.farewell(y)), "and its own goodbye");
            String plain = "It's in the stores, and on the board. Two coins, or a coin if you're a friend.";
            String la = TownSpeech.local(fa, plain), lb = TownSpeech.local(fb, plain);
            Kit.log("cw01 said out loud: " + la + " / " + lb);
            helper.assertTrue(la.contains("the hold") && la.contains("the mast") && la.contains("shells") && la.contains("a shell"),
                "the harbour town's own words: " + la);
            helper.assertTrue(lb.contains("the vault") && lb.contains("the stone") && lb.contains("marks") && lb.contains("a mark"),
                "the hill town's: " + lb);
            Set<String> sa = new HashSet<>(), sb = new HashSet<>();
            for (TownSpeech.Saying s : TownSpeech.sayings(x)) sa.add(s.text());
            for (TownSpeech.Saying s : TownSpeech.sayings(y)) sb.add(s.text());
            Kit.log("cw01 sayings: " + sa + " / " + sb);
            helper.assertTrue(sa.size() >= 5 && sb.size() >= 5, "five sayings at least each: " + sa.size() + ", " + sb.size());
            Set<String> both = new HashSet<>(sa);
            both.retainAll(sb);
            helper.assertTrue(both.isEmpty(), "none of them shared: " + both);

            // Where the player sees it: the board, the Culture page, the folk's own words.
            String board = TownWays.boardLine(x);
            helper.assertTrue(board != null && board.startsWith("FN|Our ways: ") && board.contains("Coastal houses")
                && board.contains("fish stew") && board.contains("the Herring Fair") && board.contains("the Sea"), "the board's line: " + board);
            helper.assertTrue(Culture.board(level, x).contains(board), "on the board: " + Culture.board(level, x));
            helper.assertTrue(!board.equals(TownWays.boardLine(y)), "and the hill town's board says otherwise: " + TownWays.boardLine(y));
            CompoundTag page = Culture.report(level, a).getCompound("ways");
            for (String k : List.of("ways", "table", "tongue", "building", "feast", "faith")) {
                helper.assertTrue(page.contains(k) && !page.getCompound(k).getList("lines", 8).isEmpty(), "the page's " + k + " section: " + page);
            }
            Player you = player(helper, sea.heart(), 0);
            String like = FolkTalk.answer(fa, you, TalkTopic.SAY, "What's this town like?");
            Player me = player(helper, hill.heart(), 0);
            String likeB = FolkTalk.answer(fb, me, TalkTopic.SAY, "What's this town like?");
            Kit.log("cw01 \"What's this town like?\" " + like + " // " + likeB);
            helper.assertTrue(like.contains(Villages.name(x)) && like.contains("harbour-town") && like.contains("fish stew"), "the harbour folk: " + like);
            helper.assertTrue(likeB.contains(Villages.name(y)) && likeB.contains("hill-town") && likeB.contains("hotpot"), "the hill folk: " + likeB);
            String eat = FolkTalk.answer(fa, you, TalkTopic.SAY, "What do you eat here?");
            String faith = FolkTalk.answer(fb, me, TalkTopic.SAY, "What do you believe?");
            Kit.log("cw01 the dish: " + eat + " // the faith: " + faith);
            helper.assertTrue(eat.contains("fish stew"), "its dish, asked: " + eat);
            helper.assertTrue(faith.contains("Stone"), "its faith, asked: " + faith);
            helper.succeed();
        });
    }

    // ============================================================ cw02: the dishes

    /**
     * Each town's cook makes its own dish out of its stores by the game's recipe, to the number the town keeps; the
     * feast serves it first and it lifts the spirits; it is first on the café's counter; a player orders it at the bar.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw02_dishes")
    public static void cw02_dishes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town sea = town(helper, level, 1502000, 4, Homeland.Land.COAST);
        Town wood = town(helper, level, 1502400, 4, Homeland.Land.FOREST);
        VillageFolkEntity seaCook = sea.folk().get(1), woodCook = wood.folk().get(1);
        chestAt(level, sea.heart().offset(3, 0, -3), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.BOWL, 8),
            new ItemStack(Items.COD, 8), new ItemStack(Items.BAKED_POTATO, 8), new ItemStack(Items.CARROT, 20));
        chestAt(level, wood.heart().offset(3, 0, -3), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.WHEAT, 20),
            new ItemStack(Items.BROWN_MUSHROOM, 4), new ItemStack(Items.RED_MUSHROOM, 4), new ItemStack(Items.EGG, 4),
            new ItemStack(Items.COOKED_RABBIT, 4));
        helper.runAtTickTime(10, () -> {
            Villages.Village a = village(helper, sea.id()), b = village(helper, wood.id());
            trades(sea, StationTask.COOK, StationTask.HAUL, StationTask.HAUL);
            trades(wood, StationTask.COOK, StationTask.HAUL, StationTask.HAUL);
            TownWays.workOutForTests(level, a);
            TownWays.workOutForTests(level, b);
            UUID x = sea.id(), y = wood.id();
            helper.assertTrue(Cuisine.dishOf(x) == Cuisine.Dish.FISH_STEW && Cuisine.dishOf(y) == Cuisine.Dish.GAME_PIE,
                "a harbour town's fish stew, a forest town's game pie: " + Cuisine.dishOf(x) + ", " + Cuisine.dishOf(y));
            long day = level.getDayTime() / 24000L;
            plenty(x, day);
            plenty(y, day);
            Item stew = DishItems.FISH_STEW.get(), pie = DishItems.GAME_PIE.get();

            // The harbour cook: fish stew out of the stores, one at a time, till the town has the number it keeps.
            int carrots = stock(level, x, Items.CARROT);
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String s = Cuisine.cookForTests(level, a, seaCook);
                if (s == null) break;
                made.add(s);
            }
            int stews = stock(level, x, stew);
            Kit.log("cw02 the harbour cook: " + made + "; " + stews + " stews; cod " + stock(level, x, Items.COD) + ", bowls "
                + stock(level, x, Items.BOWL) + ", baked potatoes " + stock(level, x, Items.BAKED_POTATO) + ", carrots " + stock(level, x, Items.CARROT));
            helper.assertTrue(stews >= 4 && stews <= 8 && made.size() == stews, "the town's few stews kept, then the cook stops: " + stews
                + " in " + made.size() + " turns");
            helper.assertTrue(made.get(0).contains("fish stew") && made.get(0).contains(Villages.name(x)), "in its own words: " + made.get(0));
            helper.assertTrue(stock(level, x, Items.COD) == 8 - stews && stock(level, x, Items.BOWL) == 8 - stews
                && stock(level, x, Items.BAKED_POTATO) == 8 - stews && stock(level, x, Items.CARROT) == carrots - stews,
                "a fish, a bowl, a baked potato and a carrot a stew, out of the stores");
            helper.assertTrue(stock(level, x, pie) == 0, "and no game pie: that is the forest's");

            // The forest cook: game pie, two a batch, of its own makings.
            List<String> baked = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String s = Cuisine.cookForTests(level, b, woodCook);
                if (s == null) break;
                baked.add(s);
            }
            int pies = stock(level, y, pie);
            Kit.log("cw02 the forest cook: " + baked + "; " + pies + " pies; rabbit " + stock(level, y, Items.COOKED_RABBIT) + ", eggs "
                + stock(level, y, Items.EGG) + ", wheat " + stock(level, y, Items.WHEAT) + ", mushrooms " + stock(level, y, Items.BROWN_MUSHROOM)
                + "/" + stock(level, y, Items.RED_MUSHROOM));
            helper.assertTrue(pies >= 4 && pies % 2 == 0 && baked.size() == pies / 2, "game pies two a batch: " + pies);
            helper.assertTrue(stock(level, y, Items.COOKED_RABBIT) == 4 - pies / 2 && stock(level, y, Items.EGG) == 4 - pies / 2
                && stock(level, y, Items.BROWN_MUSHROOM) == 4 - pies / 2 && stock(level, y, Items.WHEAT) == 20 - pies,
                "a rabbit, an egg, two mushrooms and two wheat a batch");
            helper.assertTrue(stock(level, y, stew) == 0, "and no fish stew in the forest");

            // The feast: the town's own dish first, and spirits up by six.
            for (Town t : List.of(sea, wood)) {
                Villages.Village v = Villages.get(t.id());
                VillageFolkEntity guest = t.folk().get(2);
                guest.refreshMood();
                int before = guest.persona().mood();
                Item own = Cuisine.dishOf(t.id()).item();
                int had = stock(level, t.id(), own), bowls = stock(level, t.id(), Items.BOWL);
                ItemStack plate = Cuisine.feastForTests(level, v, guest);
                List<Object[]> why = new ArrayList<>();
                int lift = Cuisine.mood(guest, level.getDayTime() / 24000L, 0, why);
                Kit.log("cw02 at the feast in " + Villages.name(t.id()) + ": " + guest.displayNameCap() + " had " + plate + "; mood "
                    + before + " -> " + guest.persona().mood() + " " + guest.persona().moodWhy() + "; the card: " + TownWays.cardLine(guest));
                helper.assertTrue(plate.is(own) && stock(level, t.id(), own) == had - 1, "the town's own dish served first, out of the stores");
                helper.assertTrue(lift == 6 && "townfeast".equals(why.get(0)[0]), "six to the spirits: " + lift);
                helper.assertTrue(guest.persona().mood() > before || before >= 94 || guest.persona().moodWhy().contains("townfeast"),
                    "and the folk feels it: " + before + " -> " + guest.persona().mood());
                if (Cuisine.dishOf(t.id()).bowl()) helper.assertTrue(stock(level, t.id(), Items.BOWL) == bowls + 1, "its bowl back into the stores");
                String card = TownWays.cardLine(guest);
                helper.assertTrue(card != null && card.contains("at the feast"), "on its card: " + card);
            }

            // The café's counter: the town's own dish by the door.
            List<ItemStack> menu = Cafe.menuGoods(level, x);
            Kit.log("cw02 the café's counter: " + menu);
            helper.assertTrue(!menu.isEmpty() && menu.get(0).is(stew), "fish stew first on the counter: " + menu);

            // A player at the bar: a portion out of the stores, paid into the treasury.
            Player you = player(helper, sea.heart(), 20);
            int treasury = Ledger.coins(x), before = stock(level, x, stew);
            String said = Cuisine.order(level, a, you);
            int paid = 20 - Market.coinsHeld(you);
            Kit.log("cw02 ordered: " + said + "; paid " + paid);
            helper.assertTrue(paid >= 2 && Ledger.coins(x) - treasury == paid, "paid for, into the treasury: " + paid);
            helper.assertTrue(you.getInventory().countItem(stew) == 1 && stock(level, x, stew) == before - 1, "a stew handed over, out of the stores");
            helper.assertTrue(said.contains("fish stew"), "in so many words: " + said);
            helper.succeed();
        });
    }

    // ============================================================ cw03: a colony's dish

    /** A colony takes its mother's dish to the hills and makes it with mutton; the mother's own reaches it as a treat. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw03_colony_dish")
    public static void cw03_colony_dish(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        int far = 1504260;
        Kit.hold(level, far, Z, 40);
        Kit.prepare(level, far, Z, 40);
        flat(level, far, Z, 26);
        Town mother = town(helper, level, 1504000, 3, Homeland.Land.COAST);
        TownWays.heartForTests(mother.id(), Values.Value.FOOD, Values.Value.FOOD);
        // Stocked like a town that can spare a party (as t16): timber, stone and bread put by, no storehouse units loose.
        Container motherChest = (Container) level.getBlockEntity(mother.heart());
        helper.assertTrue(motherChest != null, "the mother's stores at its heart");
        for (int i = 0; i < motherChest.getContainerSize(); i++) {
            if (motherChest.getItem(i).is(McAssistantMod.STOREHOUSE_ITEM.get())) motherChest.setItem(i, ItemStack.EMPTY);
        }
        for (ItemStack st : List.of(new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.BREAD, 64))) put(motherChest, st);
        helper.runAtTickTime(10, () -> {
            Villages.Village mv = village(helper, mother.id());
            trades(mother, StationTask.FISH, StationTask.HAUL);
            TownWays.workOutForTests(level, mv);
            helper.assertTrue(Cuisine.dishOf(mother.id()) == Cuisine.Dish.FISH_STEW, "the mother's dish: fish stew");
            BlockPos ground = Kit.surface(level, far, Z);
            boolean sent = Colonies.found(level, mv, ground, level.getGameTime());
            Villages.Village colony = Villages.nearest(level, ground, 40);
            helper.assertTrue(sent && colony != null && !colony.id().equals(mother.id()), "a colony founded in the hills: " + sent);
            UUID c = colony.id();
            helper.assertTrue(told(c, "settlers from " + Villages.name(mother.id())), "settled from the mother:" + history(c));

            // Its land is the hills: the settlers' fish stew, made the hill way.
            Homeland.setForTests(c, Homeland.Land.MOUNTAIN);
            Cuisine.Dish d = Cuisine.chooseForTests(level, colony);
            String words = Cuisine.dishWords(c);
            Kit.log("cw03 " + Villages.name(c) + "'s dish: " + d + ", " + words + ";" + history(c));
            helper.assertTrue(d == Cuisine.Dish.FISH_STEW, "the mother's dish went with them, not the hills' hotpot: " + d);
            helper.assertTrue(words.equals("the hill-town fish stew, made with mutton"), "made its own way: " + words);
            helper.assertTrue(told(c, "the settlers brought " + of(Villages.name(mother.id())) + " fish stew with them")
                && told(c, "made with mutton"), "and the chronicle says so:" + history(c));
            for (String s : TownWays.lines(c)) Kit.log("cw03   " + s);
            helper.assertTrue(TownWays.lines(c).get(0).contains("settled from " + Villages.name(mother.id())), "its page: " + TownWays.lines(c).get(0));
            // Its faith: the mother's carried, and the land's own weighed against it.
            Beliefs.Belief faith = Beliefs.chooseForTests(level, colony, true);
            Kit.log("cw03 the colony's faith: " + faith + " (its mother's " + Beliefs.of(mother.id()) + ")");

            // The colony's cook makes it with the hills' mutton: the same makings, the staple swapped.
            chestAt(level, colony.centre().offset(3, 0, -3), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.BOWL, 4),
                new ItemStack(Items.COOKED_MUTTON, 4), new ItemStack(Items.BAKED_POTATO, 4), new ItemStack(Items.CARROT, 16));
            plenty(c, level.getDayTime() / 24000L);
            int mutton = stock(level, c, Items.COOKED_MUTTON), bowls = stock(level, c, Items.BOWL);
            ItemStack made = Cuisine.makeForTests(level, colony, null);
            ItemLore lore = made.get(DataComponents.LORE);
            CustomData data = made.get(DataComponents.CUSTOM_DATA);
            String loreLine = lore == null || lore.lines().isEmpty() ? "" : lore.lines().get(0).getString();
            Kit.log("cw03 made: " + made + " \"" + loreLine + "\"; mutton " + mutton + " -> " + stock(level, c, Items.COOKED_MUTTON));
            helper.assertTrue(made.is(DishItems.FISH_STEW.get()), "a fish stew, by name");
            helper.assertTrue(loreLine.contains("hill-town take on " + of(Villages.name(mother.id())) + " fish stew, made with mutton"),
                "its lore says whose and how: " + loreLine);
            helper.assertTrue(data != null && "mutton".equals(data.copyTag().getString("mca_take")), "and the stack remembers it");
            helper.assertTrue(stock(level, c, Items.COOKED_MUTTON) == mutton - 1 && stock(level, c, Items.BOWL) == bowls - 1,
                "of the colony's mutton and a bowl, out of its stores");
            helper.assertTrue(stock(level, c, DishItems.FISH_STEW.get()) == 1, "into its stores");

            // A taste of the old way: two of the mother's own carried there on a carrier's back.
            for (int i = 0; i < 5; i++) put(motherChest, new ItemStack(DishItems.FISH_STEW.get()));
            VillageFolkEntity carrier = mother.folk().get(1);
            int packed = Cuisine.packDelicacy(level, mv, carrier, c);
            ItemStack lot = ItemStack.EMPTY;
            for (ItemStack s : carrier.getInventoryItems()) if (s.is(DishItems.FISH_STEW.get())) lot = s;
            CustomData from = lot.get(DataComponents.CUSTOM_DATA);
            Kit.log("cw03 packed for the colony: " + packed + " " + lot + " " + (from == null ? "" : from.copyTag()));
            helper.assertTrue(packed == 2 && carrier.countCarried(s -> s.is(DishItems.FISH_STEW.get())) == 2, "two on the carrier's back: " + packed);
            helper.assertTrue(stock(level, mother.id(), DishItems.FISH_STEW.get()) == 3, "out of the mother's stores, three kept back");
            helper.assertTrue(from != null && Villages.name(mother.id()).equals(from.copyTag().getString("mca_from")), "marked as from the mother");
            // Eaten in the colony: a treat, not a taste of home.
            VillageFolkEntity settler = null;
            for (AssistantEntity e : Villages.folkOf(c)) if (e instanceof VillageFolkEntity f && !f.isBaby()) { settler = f; break; }
            helper.assertTrue(settler != null, "a settler");
            Cuisine.ate(settler, lot.copyWithCount(1));
            List<Object[]> why = new ArrayList<>();
            int lift = Cuisine.mood(settler, level.getDayTime() / 24000L, 0, why);
            String card = TownWays.cardLine(settler);
            Kit.log("cw03 " + settler.displayNameCap() + " ate it: +" + lift + " " + (why.isEmpty() ? "" : why.get(0)[0]) + "; card: " + card);
            helper.assertTrue(lift == 4 && "delicacy".equals(why.get(0)[0]), "a delicacy, four to the spirits: " + lift);
            helper.assertTrue(card != null && card.contains("delicacy from " + Villages.name(mother.id())), "on its card: " + card);
            helper.succeed();
        });
    }

    // ============================================================ cw04: the tongue

    /** Greetings and words differ; a real drought and a raid coin sayings; a smith earns "Ironhand"; folk talk of it. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw04_tongue")
    public static void cw04_tongue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town sea = town(helper, level, 1506000, 4, Homeland.Land.COAST);
        Town hill = town(helper, level, 1506400, 4, Homeland.Land.MOUNTAIN);
        TownWays.heartForTests(sea.id(), Values.Value.FOOD, Values.Value.FOOD);
        TownWays.heartForTests(hill.id(), Values.Value.SAFETY, Values.Value.SAFETY);
        helper.runAtTickTime(10, () -> {
            Villages.Village a = village(helper, sea.id()), b = village(helper, hill.id());
            UUID x = sea.id(), y = hill.id();
            trades(sea, StationTask.FISH, StationTask.HAUL, StationTask.HAUL);
            trades(hill, StationTask.MINE, StationTask.HAUL, StationTask.HAUL);
            long day = level.getDayTime() / 24000L;
            // What happened: a drought in the harbour town, by the droughts' own hand; the raiders at the hill town's north
            // gate, in the words the bell's end writes (Raids.end).
            Droughts.droughtForTests(level, x, true);
            Villages.tell(y, day, "5 raiders came at the north gate in the night; the watch killed 3 of them and nobody was lost");
            Kit.log("cw04 the harbour town's history:" + history(x));
            helper.assertTrue(told(x, "a drought:"), "the drought is in the harbour town's history");
            TownWays.workOutForTests(level, a);
            TownWays.workOutForTests(level, b);
            List<TownSpeech.Saying> sx = TownSpeech.sayings(x), sy = TownSpeech.sayings(y);
            Kit.log("cw04 the harbour town says: " + sx);
            Kit.log("cw04 the hill town says: " + sy);
            String dry = "dry as day " + (day + 1);
            helper.assertTrue(sx.stream().anyMatch(s -> s.text().equals(dry) && s.day() == day && s.from().startsWith("drought: a drought:")),
                "the drought coined \"" + dry + "\": " + sx);
            helper.assertTrue(sy.stream().anyMatch(s -> s.text().equals("steady as the north gate held")), "the raid coined its saying: " + sy);
            helper.assertTrue(told(x, "a new saying went round the town: \"" + dry + "\""), "into the chronicle:" + history(x));
            helper.assertTrue(sx.size() >= 5 && sy.size() >= 5, "five at least, the land's own making up the rest: " + sx.size() + ", " + sy.size());
            helper.assertTrue(TownSpeech.coined(x).size() == 1, "one of the harbour town's own so far: " + TownSpeech.coined(x));
            // The same day again coins nothing twice.
            int had = TownSpeech.coinForTests(x, day).size();
            helper.assertTrue(had == sx.size(), "nothing coined twice: " + had);

            // Hello, goodbye, and the town's own words.
            VillageFolkEntity fa = sea.folk().get(2), fb = hill.folk().get(2);
            helper.assertTrue(!TownSpeech.greeting(x).equals(TownSpeech.greeting(y)) && !TownSpeech.farewell(x).equals(TownSpeech.farewell(y)),
                "each its own hello and goodbye: " + TownSpeech.greeting(x) + "/" + TownSpeech.farewell(x) + " and " + TownSpeech.greeting(y)
                + "/" + TownSpeech.farewell(y));
            String said = TownSpeech.localise("The stores are full, the board says so, and it cost a coin.", TownSpeech.petWords(x));
            helper.assertTrue(said.equals("The hold is full, the mast says so, and it cost a shell.")
                || said.equals("The hold are full, the mast says so, and it cost a shell."), "in the harbour's words: " + said);

            // A nickname earned by deeds: forty pieces off the anvil.
            VillageFolkEntity smith = hill.folk().get(3);
            smith.setJob(StationTask.SMITH);
            smith.note(AssistantEntity.Deed.THINGS_MADE, 40);
            int fresh = TownSpeech.nicknamesForTests(level, b);
            String first = smith.displayNameCap().split(" ")[0];
            String nick = TownSpeech.nickname(smith);
            Kit.log("cw04 nicknames: " + fresh + " new, " + smith.displayNameCap() + " is \"" + nick + "\"; gossip " + TownSpeech.gossip(fb));
            helper.assertTrue((first + " Ironhand").equals(nick), "the smith is Ironhand now: " + nick);
            helper.assertTrue(told(y, "the town has taken to calling " + smith.displayNameCap() + " \"" + nick + "\""), "into the chronicle");
            helper.assertTrue(TownSpeech.gossip(fb).stream().anyMatch(s -> s.contains(nick)), "and the talk of the town");
            helper.assertTrue(TownWays.cardLine(smith) != null && TownWays.cardLine(smith).contains(nick), "on its card: " + TownWays.cardLine(smith));
            Player you = player(helper, hill.heart(), 0);
            String called = FolkTalk.answer(smith, you, TalkTopic.SAY, "What do they call you?");
            helper.assertTrue(called.contains(nick), "it says so itself: " + called);

            // Asked, and in passing.
            Player me = player(helper, sea.heart(), 0);
            String asked = FolkTalk.answer(fa, me, TalkTopic.SAY, "Any sayings?");
            Kit.log("cw04 \"Any sayings?\" " + asked);
            helper.assertTrue(asked.contains(dry) && asked.contains("drought"), "the harbour folk tells its own, and where it came from: " + asked);
            List<String[]> chat = TownSpeech.talk(fa, sea.folk().get(3), level, level.getRandom());
            Kit.log("cw04 in passing: " + (chat.isEmpty() ? "nothing" : String.join(" / ", chat.get(0))));
            helper.assertTrue(!chat.isEmpty(), "two folk pass the time with it");
            boolean any = false;
            for (String[] c : chat) for (String s : sx.stream().map(TownSpeech.Saying::text).toList()) if (String.join(" ", c).toLowerCase().contains(s)) any = true;
            helper.assertTrue(any, "a saying of the town's among it");
            helper.succeed();
        });
    }

    // ============================================================ cw05: two styles, one house

    /**
     * One house stamped in a harbour town and a hill town and dressed out of each town's stores: polished diorite,
     * birch posts, tinted glass, banners and a barrel; deepslate brick, a slate roof, battlements and windows walled
     * small. What came off goes back into the stores. Then all six styles on six of the same house, each its own.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw05_styles")
    public static void cw05_styles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town sea = town(helper, level, 1508000, 4, Homeland.Land.COAST);
        Town hill = town(helper, level, 1508400, 4, Homeland.Land.MOUNTAIN);
        TownWays.heartForTests(sea.id(), Values.Value.FOOD, Values.Value.FOOD);
        TownWays.heartForTests(hill.id(), Values.Value.SAFETY, Values.Value.SAFETY);
        helper.runAtTickTime(10, () -> {
            Villages.Village a = village(helper, sea.id()), b = village(helper, hill.id());
            UUID x = sea.id(), y = hill.id();
            TownWays.workOutForTests(level, a);
            TownWays.workOutForTests(level, b);
            helper.assertTrue(Architecture.of(x) == Architecture.Style.COASTAL && Architecture.of(y) == Architecture.Style.HILL_FORT,
                "Coastal and Hill Fort: " + Architecture.of(x) + ", " + Architecture.of(y));
            Villages.ageForTests(x, Villages.Age.IRON);
            Villages.ageForTests(y, Villages.Age.IRON);
            BlockPos atA = sea.heart().offset(0, 0, -14), atB = hill.heart().offset(0, 0, -14);
            BuildGoal.stamp(level, "house", atA, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            BuildGoal.stamp(level, "house", atB, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(x, "house", atA, Direction.NORTH);
            Ledger.built(y, "house", atB, Direction.NORTH);
            Ledger.Building houseA = new Ledger.Building("house", atA, Direction.NORTH), houseB = new Ledger.Building("house", atB, Direction.NORTH);
            int same = differ(level, atA, atB);
            Kit.log("cw05 stamped alike: " + same + " places differ");

            // The stores pay for it: the harbour town's white stone, birch, glass and dye, its colours; the hill town's
            // deepslate, slate, stone bricks and rough stone.
            DyeColor ca = Architecture.trim(x), cb = Architecture.trim(y);
            chestAt(level, sea.heart().offset(3, 0, -3), new ItemStack(Items.POLISHED_DIORITE, 64), new ItemStack(Items.POLISHED_DIORITE, 64),
                new ItemStack(Items.BIRCH_LOG, 32), new ItemStack(Items.GLASS_PANE, 16), new ItemStack(item(ca.getName() + "_dye"), 2),
                new ItemStack(item(ca.getName() + "_banner"), 2), new ItemStack(Items.BARREL, 1));
            chestAt(level, hill.heart().offset(3, 0, -3), new ItemStack(Items.DEEPSLATE_BRICKS, 64), new ItemStack(Items.DEEPSLATE_BRICKS, 64),
                new ItemStack(Items.DEEPSLATE_TILE_STAIRS, 64), new ItemStack(Items.DEEPSLATE_TILE_STAIRS, 64),
                new ItemStack(Items.DEEPSLATE_TILE_SLAB, 64), new ItemStack(Items.STONE_BRICKS, 32), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(item(cb.getName() + "_banner"), 2));
            List<String> planA = Architecture.planForTests(level, x, houseA), planB = Architecture.planForTests(level, y, houseB);
            Kit.log("cw05 the Coastal dressing: " + planA.size() + " changes " + new HashSet<>(planA));
            Kit.log("cw05 the Hill Fort dressing: " + planB.size() + " changes " + new HashSet<>(planB));
            int dioriteBefore = stock(level, x, Items.POLISHED_DIORITE), brickBefore = stock(level, y, Items.DEEPSLATE_BRICKS);
            int planksA = stock(level, x, Items.OAK_PLANKS), planksB = stock(level, y, Items.OAK_PLANKS);
            int nA = Architecture.dress(level, a, houseA, 10000, false), nB = Architecture.dress(level, b, houseB, 10000, false);
            List<String> leftA = Architecture.planForTests(level, x, houseA), leftB = Architecture.planForTests(level, y, houseB);
            Map<Block, Integer> ma = blocks(level, atA), mb = blocks(level, atB);
            int differs = differ(level, atA, atB);
            Kit.log("cw05 dressed: " + nA + " and " + nB + " blocks; left undone " + leftA + " / " + leftB + "; now " + differs + " places differ");
            Kit.log("cw05 the Coastal house: " + ma);
            Kit.log("cw05 the Hill Fort house: " + mb);
            helper.assertTrue(nA >= 30 && nB >= 30, "dozens of blocks each: " + nA + ", " + nB);
            helper.assertTrue(leftA.size() <= 2 && leftB.size() <= 2, "near enough all of it paid for: " + leftA + " / " + leftB);
            helper.assertTrue(differs >= same + 40, "the two houses now differ in real blocks: " + same + " -> " + differs);
            // The harbour town's.
            helper.assertTrue(count(ma, Blocks.POLISHED_DIORITE) >= 20, "white walls: " + count(ma, Blocks.POLISHED_DIORITE));
            helper.assertTrue(count(ma, Blocks.STRIPPED_BIRCH_LOG) >= 4, "birch posts: " + count(ma, Blocks.STRIPPED_BIRCH_LOG));
            Block tinted = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(ca.getName() + "_stained_glass_pane"));
            helper.assertTrue(count(ma, tinted) >= 2, "glass in the town's colour: " + count(ma, tinted));
            helper.assertTrue(countTag(ma, bl -> bl instanceof WallBannerBlock) >= 1, "its banners at the corners");
            helper.assertTrue(count(ma, Blocks.BARREL) >= 1, "a barrel by the door");
            helper.assertTrue(count(ma, Blocks.DEEPSLATE_BRICKS) == 0 && count(ma, Blocks.COBBLESTONE_WALL) == 0, "nothing of the hill town's");
            // The hill town's.
            helper.assertTrue(count(mb, Blocks.DEEPSLATE_BRICKS) >= 20, "deepslate brick walls: " + count(mb, Blocks.DEEPSLATE_BRICKS));
            helper.assertTrue(count(mb, Blocks.DEEPSLATE_TILE_STAIRS) >= 10, "a slate roof: " + count(mb, Blocks.DEEPSLATE_TILE_STAIRS));
            helper.assertTrue(count(mb, Blocks.COBBLESTONE_WALL) >= 2, "battlements on the eaves: " + count(mb, Blocks.COBBLESTONE_WALL));
            helper.assertTrue(count(mb, Blocks.STONE_BRICKS) >= 4, "stone corners: " + count(mb, Blocks.STONE_BRICKS));
            helper.assertTrue(count(mb, Blocks.POLISHED_DIORITE) == 0, "no white walls in the hills");
            helper.assertTrue(planB.contains("the side windows walled up small"), "its side windows walled small: " + new HashSet<>(planB));
            // Out of the stores, and the old back into them.
            helper.assertTrue(dioriteBefore - stock(level, x, Items.POLISHED_DIORITE) >= 20, "the diorite came out of the stores");
            helper.assertTrue(brickBefore - stock(level, y, Items.DEEPSLATE_BRICKS) >= 20, "the brick too");
            helper.assertTrue(stock(level, x, Items.OAK_PLANKS) >= planksA + 20 && stock(level, y, Items.OAK_PLANKS) >= planksB + 20,
                "the old planks back into the stores: " + planksA + " -> " + stock(level, x, Items.OAK_PLANKS) + ", " + planksB + " -> "
                + stock(level, y, Items.OAK_PLANKS));
            // A house dressed is left alone: nothing more to do in its style and age.
            helper.assertTrue(Architecture.dress(level, a, houseA, 10000, false) == 0, "nothing more to do in the harbour town");

            // All six styles, on six of the same house (the stage's street): no two alike.
            List<Map<Block, Integer>> looks = new ArrayList<>();
            Architecture.Style[] all = Architecture.Style.values();
            for (int i = 0; i < all.length; i++) {
                BlockPos at = sea.heart().offset(-25 + i * 10, 0, 18);
                BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
                int n = Architecture.dressAs(level, x, new Ledger.Building("house", at, Direction.NORTH), all[i], Villages.Age.IRON);
                Map<Block, Integer> m = blocks(level, at);
                Kit.log("cw05 " + all[i].words + ": " + n + " blocks; " + m);
                helper.assertTrue(n >= 10, all[i].words + " changes the house: " + n);
                looks.add(m);
            }
            for (int i = 0; i < looks.size(); i++) {
                for (int j = i + 1; j < looks.size(); j++) {
                    helper.assertTrue(!looks.get(i).equals(looks.get(j)), all[i].words + " and " + all[j].words + " look different");
                }
            }
            helper.succeed();
        });
    }

    // ============================================================ cw06: the Herring Fair

    /**
     * The harbour town's own festival: stalls and lanterns out of the stores round the square, the fair called and held,
     * fish stew eaten at it, the best catch judged and paid and written down; a year on the catch since the last fair
     * wins; the stalls back into the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "cw06_herring_fair")
    public static void cw06_herring_fair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = start(level, 9000L);
        final long base = day * 24000L + 9000L;
        Town t = town(helper, level, 1510000, 7, Homeland.Land.COAST);
        UUID id = t.id();
        TownWays.heartForTests(id, Values.Value.FOOD, Values.Value.FOOD);
        VillageFolkEntity f1 = t.folk().get(1), f2 = t.folk().get(2), f3 = t.folk().get(3);
        chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.BARREL, 4), new ItemStack(Items.LANTERN, 4),
            new ItemStack(DishItems.FISH_STEW.get(), 8), new ItemStack(Items.BOWL, 2));
        long[] mingleFrom = { -1 };
        int[] stewsAtStart = { 0 };
        boolean[] done = { false };
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            TownFeast.setForTests(id, TownFeast.Feast.HERRING_FAIR);
            Cuisine.setForTests(id, Cuisine.Dish.FISH_STEW, null);
            for (VillageFolkEntity f : List.of(f1, f2, f3)) f.setJob(StationTask.FISH);
            f1.note(AssistantEntity.Deed.FISH_CAUGHT, 12);
            f2.note(AssistantEntity.Deed.FISH_CAUGHT, 30);
            f3.note(AssistantEntity.Deed.FISH_CAUGHT, 51);
            Ledger.addCoins(id, 30);
            int out = TownFeast.setOutForTests(level, v);
            List<BlockPos> placed = TownFeast.placedForTests(id);
            int barrels = 0, lanterns = 0;
            for (BlockPos p : placed) {
                if (level.getBlockState(p).is(Blocks.BARREL)) barrels++;
                if (level.getBlockState(p).is(Blocks.LANTERN)) lanterns++;
            }
            Kit.log("cw06 set out: " + out + " (" + barrels + " stalls, " + lanterns + " lanterns) at " + placed);
            helper.assertTrue(out >= 6 && barrels >= 3 && lanterns >= 3, "stalls and lanterns round the square: " + barrels + ", " + lanterns);
            helper.assertTrue(stock(level, id, Items.BARREL) == 4 - barrels && stock(level, id, Items.LANTERN) == 4 - lanterns,
                "out of the stores");
            for (BlockPos p : placed) {
                int d = Math.max(Math.abs(p.getX() - v.centre().getX()), Math.abs(p.getZ() - v.centre().getZ()));
                helper.assertTrue(d >= 4 && d <= 6, "round the square: " + p.toShortString());
            }
            stewsAtStart[0] = stock(level, id, DishItems.FISH_STEW.get());
            boolean called = TownFeast.callNow(level, v);
            Kit.log("cw06 the fair called: " + called + " " + Assemblies.debug(id) + " — " + Assemblies.now(id));
            helper.assertTrue(called && String.valueOf(Assemblies.now(id)).contains("Herring Fair"), "the Herring Fair under way: " + Assemblies.now(id));
        });
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick <= 10 || done[0]) return;
            if (level.getDayTime() - base > 3000L) level.setDayTime(base);             // the afternoon, held
            int[] pr = Assemblies.progress(id);
            if (tick % 200 == 0) Kit.log("cw06 @" + tick + ": " + Assemblies.debug(id));
            if (pr != null && pr[0] == 3 && mingleFrom[0] < 0) {
                mingleFrom[0] = tick;
                Kit.log("cw06 the fair is in full swing at tick " + tick + ": " + pr[1] + " on the square");
            }
            helper.assertTrue(pr != null || mingleFrom[0] > 0, "the fair under way: " + Assemblies.debug(id));
            if (mingleFrom[0] < 0 || pr != null && tick - mingleFrom[0] < 300) return;
            done[0] = true;
            Villages.Village v = village(helper, id);
            int purse = f3.purse();
            String result = pr == null ? TownFeast.lastResult(id) : TownFeast.closeForTests(level, v);
            Assemblies.resetForTests();
            int eaten = stewsAtStart[0] - stock(level, id, DishItems.FISH_STEW.get());
            Kit.log("cw06 the fair closed: " + result + "; " + eaten + " stews eaten; " + f3.displayNameCap() + "'s purse " + purse + " -> " + f3.purse());
            helper.assertTrue(eaten >= 1, "the town's fish stew eaten at the fair: " + eaten);
            helper.assertTrue(result.startsWith(f3.displayNameCap() + " won the best catch, 51 fish"), "the best catch wins: " + result);
            helper.assertTrue(result.contains("a purse of 5 coins") && f3.purse() >= purse + 5, "a purse out of the treasury: " + result);
            helper.assertTrue(told(id, Villages.name(id) + " kept the Herring Fair") && told(id, "won the best catch"), "into the chronicle:" + history(id));
            List<Object[]> why = new ArrayList<>();
            int was = 0;
            for (VillageFolkEntity f : t.folk()) {
                why.clear();
                if (TownFeast.mood(f, level.getDayTime() / 24000L, 0, why) == 5) was++;
            }
            Kit.log("cw06 " + was + " of the town were at the fair (five to their spirits)");
            helper.assertTrue(was >= 2, "the fair lifts the spirits of those who came: " + was);

            // A year on: the catch since the last fair counts, not the catch of a lifetime.
            f2.note(AssistantEntity.Deed.FISH_CAUGHT, 40);
            f3.note(AssistantEntity.Deed.FISH_CAUGHT, 5);
            String next = TownFeast.closeForTests(level, v);
            Kit.log("cw06 the next fair: " + next);
            helper.assertTrue(next.startsWith(f2.displayNameCap() + " won the best catch, 40 fish since the last fair"), "the year's catch wins: " + next);

            // The morning after: the stalls in again.
            int in = TownFeast.takeDownForTests(level, v);
            Kit.log("cw06 taken in: " + in + "; barrels " + stock(level, id, Items.BARREL) + ", lanterns " + stock(level, id, Items.LANTERN));
            helper.assertTrue(stock(level, id, Items.BARREL) == 4 && stock(level, id, Items.LANTERN) == 4, "every stall and lantern back in the stores");
            helper.assertTrue(TownFeast.placedForTests(id).isEmpty(), "and nothing left out");
            helper.succeed();
        });
    }

    // ============================================================ cw07: the Sea

    /**
     * A Sea town: its dead given to the sea (a post and a board on the shore, a flower on the water, no headstone), its
     * vigils and weddings on the shore, no fishing on the Sea's day, the morning's fish to the sea and its fishers blessed,
     * and names of the sea for its children.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw07_sea_faith")
    public static void cw07_sea_faith(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = start(level, 2000L);
        Town t = town(helper, level, 1512000, 6, Homeland.Land.COAST);
        UUID id = t.id();
        Kit.pond(level, t.heart().getX() + 16, Z, 5);
        TownWays.heartForTests(id, Values.Value.FOOD, Values.Value.FOOD);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            VillageFolkEntity elder = t.folk().get(0), fisher = t.folk().get(1), farmer = t.folk().get(2), guard = t.folk().get(3);
            VillageFolkEntity a = t.folk().get(4), gone = t.folk().get(5);
            trades(t, StationTask.FISH, StationTask.FARM, StationTask.GUARD, StationTask.HAUL, StationTask.HAUL);
            TownWays.workOutForTests(level, v);
            helper.assertTrue(Beliefs.of(id) == Beliefs.Belief.SEA && Beliefs.burial(id) == Beliefs.Burial.SEA, "a Sea town: " + Beliefs.of(id));
            // A day on: the town is settled, and keeps its ways.
            long d1 = Chronicle.foundedOn(id) + 1;
            level.setDayTime(d1 * 24000L + 2000L);

            // Weddings on the shore, the vigil there too.
            BlockPos shore = Beliefs.weddingAt(level, v, new Gatherings.Wedding(a.getUUID(), fisher.getUUID(), "two of them", d1));
            Kit.log("cw07 the shore: " + (shore == null ? "none" : shore.toShortString()));
            helper.assertTrue(shore != null && Math.abs(shore.getX() - (t.heart().getX() + 16)) <= 7 && Math.abs(shore.getZ() - Z) <= 7,
                "weddings by the water: " + shore);
            helper.assertTrue(shore.equals(Beliefs.vigilAt(id, t.heart())), "and the vigil for the dead: " + Beliefs.vigilAt(id, t.heart()));

            // A death: given to the sea.
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.SPRUCE_FENCE, 1), new ItemStack(Items.OAK_SIGN, 4),
                new ItemStack(Items.POPPY, 2), new ItemStack(Items.COD, 3), new ItemStack(Items.COBBLESTONE, 32));
            String name = gone.displayNameCap();
            gone.kill();
            helper.assertTrue(told(id, name + " is to be given to the sea"), "the town's way with its dead:" + history(id));
            int marked = Beliefs.tendForTests(level, v, false);
            BlockPos post = Beliefs.markerForTests(id, name);
            String[] board = post == null ? null : signLines(level, post.above());
            Kit.log("cw07 marked " + marked + ": " + (post == null ? "none" : post.toShortString()) + " " + (board == null ? "" : String.join(" / ", board)));
            helper.assertTrue(marked == 1 && post != null, "a post on the shore for " + name);
            helper.assertTrue(level.getBlockState(post).is(BlockTags.WOODEN_FENCES) && level.getBlockState(post.above()).getBlock() instanceof StandingSignBlock,
                "a post with a board on it");
            helper.assertTrue(board != null && "Given to the sea".equals(board[0]) && name.equals(board[1]), "\"Given to the sea\", and its name");
            helper.assertTrue(post.distManhattan(shore) <= 6, "on the shore: " + post.toShortString());
            helper.assertTrue(stock(level, id, Items.SPRUCE_FENCE) == 0 && stock(level, id, Items.POPPY) == 1, "the post and a flower out of the stores");
            List<ItemEntity> flowers = level.getEntitiesOfClass(ItemEntity.class, new AABB(shore).inflate(8), e -> e.getItem().is(Items.POPPY));
            helper.assertTrue(!flowers.isEmpty(), "a flower set on the water");
            // No headstone for the sea's dead.
            BlockPos yardAt = t.heart().offset(-16, 0, -16);
            BuildGoal.stamp(level, "graveyard", yardAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "graveyard", yardAt, Direction.NORTH);
            Ledger.Grave grave = null;
            for (Ledger.Grave g : Ledger.graves(id)) if (g.name().equals(name)) grave = g;
            helper.assertTrue(grave != null && !Beliefs.inYard(id, grave), "on the register, not in the yard");
            int stones = Graves.tend(level, id);
            helper.assertTrue(stones == 0, "no headstone in the yard: " + stones);

            // The Sea's day: nobody fishes; the farmer works; the watch is never stood down.
            long sacred = sacredFrom(id, d1), plain = plainFrom(id, d1);
            level.setDayTime(sacred * 24000L + 3000L);
            String taboo = Beliefs.tabooForTests(fisher);
            Kit.log("cw07 day " + sacred + " is the Sea's: the fisher " + taboo + ", off work " + fisher.offWorkNow() + "; the fleet: "
                + Beliefs.keptIn(level, id, sacred) + "; the farmer " + Beliefs.tabooForTests(farmer));
            helper.assertTrue(taboo != null && taboo.contains("Sea's day") && fisher.offWorkNow(), "no fishing on the Sea's day: " + taboo);
            helper.assertTrue("the Sea's day".equals(Beliefs.keptIn(level, id, sacred)), "the fleet kept in");
            helper.assertTrue(Beliefs.tabooForTests(farmer) == null && Beliefs.tabooForTests(guard) == null, "the farmer and the watch at work");
            // The morning's rite: the first fish to the sea, the fishers blessed.
            int cod = stock(level, id, Items.COD);
            String rite = Beliefs.riteForTests(level, v, elder);
            List<Object[]> why = new ArrayList<>();
            int blessed = Beliefs.mood(fisher, sacred, 0, why);
            Kit.log("cw07 the rite: " + rite + "; cod " + cod + " -> " + stock(level, id, Items.COD) + "; the fisher +" + blessed);
            helper.assertTrue(rite != null && rite.contains("gave the sea the first fish"), "the sea's offering: " + rite);
            helper.assertTrue(stock(level, id, Items.COD) == cod - 1, "a fish out of the stores");
            helper.assertTrue(blessed == 3 && "blessed".equals(why.get(0)[0]), "the fisher blessed: " + blessed);
            helper.assertTrue(Beliefs.mood(farmer, sacred, 0, new ArrayList<>()) == 0, "not the farmer");
            level.setDayTime(plain * 24000L + 3000L);
            helper.assertTrue(Beliefs.tabooForTests(fisher) == null && Beliefs.keptIn(level, id, plain) == null, "the boats out on any other day");

            // Its children: names of the sea.
            String child = Beliefs.childNameForTests(id, level.getRandom(), a, fisher);
            Kit.log("cw07 a child of the sea: " + child);
            helper.assertTrue(Beliefs.faithName(id, child) && List.of("Marin", "Coral", "Pearl", "Morwen", "Tide", "Shelly", "Nerys", "Dory",
                "Kelda", "Brine").contains(child), "a name of the sea: " + child);
            helper.succeed();
        });
    }

    // ============================================================ cw08: the Stone

    /** A Stone town: a cairn on the high ground for its dead, the first diamond in its chapel, the miners' rest, stone names. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw08_stone_faith")
    public static void cw08_stone_faith(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town t = town(helper, level, 1514000, 5, Homeland.Land.MOUNTAIN);
        UUID id = t.id();
        mound(level, t.heart().getX() + 24, Z, 5, 6);
        TownWays.heartForTests(id, Values.Value.SAFETY, Values.Value.SAFETY);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            VillageFolkEntity miner = t.folk().get(1), farmer = t.folk().get(2), a = t.folk().get(3), gone = t.folk().get(4);
            trades(t, StationTask.MINE, StationTask.FARM, StationTask.HAUL, StationTask.HAUL);
            TownWays.workOutForTests(level, v);
            helper.assertTrue(Beliefs.of(id) == Beliefs.Belief.STONE && Beliefs.burial(id) == Beliefs.Burial.CAIRN, "a Stone town: " + Beliefs.of(id));
            long d1 = Chronicle.foundedOn(id) + 1;
            level.setDayTime(d1 * 24000L + 2000L);
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_SIGN, 4),
                new ItemStack(Items.DIAMOND, 1), new ItemStack(Items.ITEM_FRAME, 1));

            // A cairn on the high ground.
            String name = gone.displayNameCap();
            gone.kill();
            helper.assertTrue(told(id, "a cairn is to be raised on the high ground for " + name), "the town's way with its dead:" + history(id));
            int cobble = stock(level, id, Items.COBBLESTONE);
            int marked = Beliefs.tendForTests(level, v, false);
            BlockPos cairn = Beliefs.markerForTests(id, name);
            String[] board = cairn == null ? null : signLines(level, cairn.north());
            Kit.log("cw08 marked " + marked + ": " + (cairn == null ? "none" : cairn.toShortString()) + " "
                + (board == null ? "" : String.join(" / ", board)) + "; cobble " + cobble + " -> " + stock(level, id, Items.COBBLESTONE));
            helper.assertTrue(marked == 1 && cairn != null, "a cairn raised for " + name);
            helper.assertTrue(level.getBlockState(cairn).is(Blocks.COBBLESTONE) && level.getBlockState(cairn.above()).is(Blocks.COBBLESTONE_WALL),
                "of rough stone");
            helper.assertTrue(cairn.getY() > t.heart().getY() && Math.abs(cairn.getX() - (t.heart().getX() + 24)) <= 5
                && Math.abs(cairn.getZ() - Z) <= 5, "on the high ground, off the square: " + cairn.toShortString());
            helper.assertTrue(board != null && "Under the stone".equals(board[0]) && name.equals(board[1]), "\"Under the stone\", and its name");
            helper.assertTrue(stock(level, id, Items.COBBLESTONE) == cobble - 2, "two of the stores' cobblestone");

            // The first diamond on the chapel's wall.
            BlockPos chapelAt = t.heart().offset(-14, 0, -18);
            BuildGoal.stamp(level, "chapel", chapelAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "chapel", chapelAt, Direction.NORTH);
            boolean hung = Beliefs.shrineForTests(level, v, false);
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(chapelAt).inflate(14), e -> e.getItem().is(Items.DIAMOND));
            Kit.log("cw08 the shrine: " + hung + ", " + frames.size() + " frames with a diamond;" + history(id));
            helper.assertTrue(hung && frames.size() == 1, "the diamond hung in the chapel");
            helper.assertTrue(stock(level, id, Items.DIAMOND) == 0 && stock(level, id, Items.ITEM_FRAME) == 0, "the diamond and a frame out of the stores");
            helper.assertTrue(told(id, "in the chapel, for the Stone"), "into the chronicle");

            // The Stone's day: the mountain rests.
            long sacred = sacredFrom(id, d1);
            level.setDayTime(sacred * 24000L + 3000L);
            String taboo = Beliefs.tabooForTests(miner);
            Kit.log("cw08 the Stone's day " + sacred + ": the miner " + taboo + "; the farmer " + Beliefs.tabooForTests(farmer));
            helper.assertTrue(taboo != null && taboo.contains("mountain rests") && miner.offWorkNow(), "no mining on the Stone's day: " + taboo);
            helper.assertTrue(Beliefs.tabooForTests(farmer) == null, "the farmer at work");

            // Stone names.
            String child = Beliefs.childNameForTests(id, level.getRandom(), a, miner);
            Kit.log("cw08 a child of the Stone: " + child);
            helper.assertTrue(List.of("Flint", "Jasper", "Garnet", "Slate", "Agate", "Beryl", "Onyx", "Opal", "Cobble", "Jet").contains(child),
                "a stone's name: " + child);
            helper.succeed();
        });
    }

    // ============================================================ cw09: the other faiths

    /**
     * The Harvest: its farmers rest on its day, its fields are blessed, its children named for the green, the first
     * sheaf in its chapel; turned to the Stars, the spyglass takes the sheaf's place (the sheaf back to the stores) and
     * the woodcutters rest; the Hearth weds sooner; the Founders' first house is never touched.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw09_other_faiths")
    public static void cw09_other_faiths(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town t = town(helper, level, 1516000, 5, Homeland.Land.PLAINS);
        UUID id = t.id();
        TownWays.heartForTests(id, Values.Value.FOOD, Values.Value.FOOD);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            VillageFolkEntity elder = t.folk().get(0), farmer = t.folk().get(1), woodcutter = t.folk().get(2), a = t.folk().get(3);
            trades(t, StationTask.FARM, StationTask.WOOD, StationTask.HAUL, StationTask.HAUL);
            TownWays.workOutForTests(level, v);
            helper.assertTrue(Beliefs.of(id) == Beliefs.Belief.HARVEST, "a plains town of the Harvest: " + Beliefs.of(id));
            long d1 = Chronicle.foundedOn(id) + 1;
            long sacred = sacredFrom(id, d1);
            level.setDayTime(sacred * 24000L + 3000L);
            helper.assertTrue(Beliefs.tabooForTests(farmer) != null && Beliefs.tabooForTests(woodcutter) == null,
                "the fields rest on the Harvest's day, the woods do not: " + Beliefs.tabooForTests(farmer));
            String rite = Beliefs.riteForTests(level, v, elder);
            int lift = Beliefs.mood(farmer, sacred, 0, new ArrayList<>());
            Kit.log("cw09 the Harvest's day: " + rite + "; the farmer +" + lift);
            helper.assertTrue(rite != null && rite.contains("blessed the fields") && lift == 3, "the fields blessed, the farmer too: " + rite);
            String child = Beliefs.childNameForTests(id, level.getRandom(), a, farmer);
            helper.assertTrue(List.of("Barley", "Rowan", "Hazel", "Clover", "Sorrel", "Bramble", "Linden", "Fennel", "Sage", "Willow").contains(child),
                "a green name: " + child);

            // The first sheaf on the chapel's wall; turned to the Stars, a spyglass in its place and the sheaf back.
            BlockPos chapelAt = t.heart().offset(0, 0, -18);
            BuildGoal.stamp(level, "chapel", chapelAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "chapel", chapelAt, Direction.NORTH);
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.WHEAT, 1), new ItemStack(Items.ITEM_FRAME, 1),
                new ItemStack(Items.SPYGLASS, 1));
            helper.assertTrue(Beliefs.shrineForTests(level, v, false), "the sheaf hung");
            Beliefs.setForTests(id, Beliefs.Belief.STARS);
            helper.assertTrue(Beliefs.shrineForTests(level, v, false), "the spyglass hung");
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(chapelAt).inflate(14), e -> true);
            Kit.log("cw09 the shrine: " + frames.stream().map(f -> f.getItem().toString()).toList() + ";" + history(id));
            helper.assertTrue(frames.size() == 1 && frames.get(0).getItem().is(Items.SPYGLASS), "one frame, the spyglass in it");
            helper.assertTrue(stock(level, id, Items.WHEAT) == 1 && stock(level, id, Items.SPYGLASS) == 0, "the sheaf back in the stores");
            helper.assertTrue(told(id, "for the Harvest") && told(id, "for the Stars"), "both in the chronicle");
            helper.assertTrue(Beliefs.tabooForTests(woodcutter) != null && Beliefs.tabooForTests(farmer) == null,
                "no tree felled on the Stars' day: " + Beliefs.tabooForTests(woodcutter));

            // The Hearth weds sooner.
            Beliefs.setForTests(id, Beliefs.Belief.HEARTH);
            int hearth = Beliefs.weddingWarmth(id);
            Beliefs.setForTests(id, Beliefs.Belief.SEA);
            helper.assertTrue(hearth == 68 && Beliefs.weddingWarmth(id) == 75, "the Hearth's couples wed sooner: " + hearth);

            // The Founders: the first house as they left it.
            Beliefs.setForTests(id, Beliefs.Belief.FOUNDERS);
            BlockPos first = t.heart().offset(-14, 0, 16), second = t.heart().offset(14, 0, 16);
            Ledger.built(id, "house", first, Direction.NORTH);
            Ledger.built(id, "house", second, Direction.NORTH);
            helper.assertTrue(Beliefs.untouchable(id, new Ledger.Building("house", first, Direction.NORTH))
                && !Beliefs.untouchable(id, new Ledger.Building("house", second, Direction.NORTH)), "the founders' house kept, the next one not");
            helper.succeed();
        });
    }

    // ============================================================ cw10: a style, and a change of it

    /**
     * A plains town builds Timbered Lowland (dark oak bands, shutters, a fire in the chimney pot, its shingle kept through
     * the ages, flower boxes and dark oak lamp posts); a little richer it stays; rich, big in the Iron Age and merchant at
     * heart it turns Grand Civic, says so, and its houses, its lamp posts and its benches follow.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cw10_style_change")
    public static void cw10_style_change(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town t = town(helper, level, 1518000, 4, Homeland.Land.PLAINS);
        UUID id = t.id();
        TownWays.heartForTests(id, Values.Value.FOOD, Values.Value.FOOD);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            TownWays.workOutForTests(level, v);
            helper.assertTrue(Architecture.of(id) == Architecture.Style.TIMBERED, "a plains town builds Timbered Lowland: " + Architecture.of(id));
            Villages.ageForTests(id, Villages.Age.STONE);
            BlockPos at = t.heart().offset(0, 0, -14);
            BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", at, Direction.NORTH);
            Ledger.Building house = new Ledger.Building("house", at, Direction.NORTH);
            int n = Architecture.dressForTests(level, v, house);
            Map<Block, Integer> m = blocks(level, at);
            Kit.log("cw10 the Timbered house: " + n + " blocks; " + m);
            helper.assertTrue(count(m, Blocks.DARK_OAK_LOG) >= 6, "dark oak timber bands: " + count(m, Blocks.DARK_OAK_LOG));
            helper.assertTrue(count(m, Blocks.SPRUCE_TRAPDOOR) >= 2, "shutters at the windows: " + count(m, Blocks.SPRUCE_TRAPDOOR));
            helper.assertTrue(count(m, Blocks.CAMPFIRE) == 1, "smoke from the chimney pot");
            helper.assertTrue(count(m, Blocks.BRICKS) >= 2, "a brick chimney: " + count(m, Blocks.BRICKS));
            helper.assertTrue(!Architecture.ageMayChange(id, house, Blueprints.Style.ROOF_STAIR) && Architecture.ageMayChange(id, house, Blueprints.Style.WALL),
                "its shingle roof kept through the ages, its walls not");
            helper.assertTrue(Architecture.boxesEverywhere(id), "flower boxes under every window");
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.DARK_OAK_LOG, 4), new ItemStack(Items.STONE_BRICKS, 8));
            Architecture.Post post = Architecture.lampPost(level, v);
            helper.assertTrue(post != null && post.block() == Blocks.DARK_OAK_FENCE, "dark oak lamp posts: " + post);
            helper.assertTrue(Architecture.benchStair(level, v) == null, "wooden benches");
            Palettes.Look timbered = Palettes.of(id);

            // A little richer, and learned: it stays as it is.
            TownWays.heartForTests(id, Values.Value.PROGRESS, null);
            Ledger.addCoins(id, 100);
            helper.assertTrue(Architecture.chooseForTests(level, v, true) == Architecture.Style.TIMBERED, "not enough to turn it");
            // Rich, in the Iron Age, merchant at heart: Grand Civic.
            TownWays.heartForTests(id, Values.Value.WEALTH, null);
            Ledger.addCoins(id, 700);
            Villages.ageForTests(id, Villages.Age.IRON);
            Architecture.Style now = Architecture.chooseForTests(level, v, true);
            Kit.log("cw10 the town now builds: " + now + ";" + history(id));
            helper.assertTrue(now == Architecture.Style.GRAND_CIVIC, "a rich merchant town turns Grand Civic: " + now);
            helper.assertTrue(told(id, "turned from its Timbered Lowland ways to the Grand Civic"), "and says so");
            helper.assertTrue(!Palettes.of(id).equals(timbered), "its new buildings in other woods: " + timbered + " -> " + Palettes.of(id));
            List<String> plan = Architecture.planForTests(level, id, house);
            Kit.log("cw10 to dress anew: " + new HashSet<>(plan));
            helper.assertTrue(plan.contains("the walls") && plan.contains("the corner posts"), "its houses to be dressed anew: " + new HashSet<>(plan));
            Architecture.dressForTests(level, v, house);
            Map<Block, Integer> civic = blocks(level, at);
            helper.assertTrue(count(civic, Blocks.POLISHED_ANDESITE) >= 20 && count(civic, Blocks.CHISELED_STONE_BRICKS) >= 4,
                "polished stone and columns: " + civic);
            helper.assertTrue(Architecture.ageMayChange(id, house, Blueprints.Style.ROOF_STAIR), "the age may slate its roof now");
            helper.assertTrue(!Architecture.boxesEverywhere(id), "flower boxes for the well-off only");
            Architecture.Post stone = Architecture.lampPost(level, v);
            helper.assertTrue(stone != null && stone.block() == Blocks.STONE_BRICK_WALL, "stone brick lamp posts: " + stone);
            int bricks = stock(level, id, Items.STONE_BRICKS);
            helper.assertTrue(Architecture.benchStair(level, v) == Blocks.STONE_BRICK_STAIRS && stock(level, id, Items.STONE_BRICKS) == bricks - 1,
                "stone benches, out of the stores");
            helper.succeed();
        });
    }
}
