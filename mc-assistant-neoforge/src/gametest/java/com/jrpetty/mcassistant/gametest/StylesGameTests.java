package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Ages;
import com.jrpetty.mcassistant.entity.Architecture;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Materials;
import com.jrpetty.mcassistant.entity.Palettes;
import com.jrpetty.mcassistant.entity.Renovation;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.StylesLegacy;
import com.jrpetty.mcassistant.entity.Themes;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.ThemeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * [styles] The towns' looks as data (themes), and the renovation of their older buildings.
 *
 * <ul>
 * <li><b>st01</b>: the medieval theme chooses exactly the blocks the code chose before the look was data
 *     (entity/StylesLegacy, the old code frozen): every land's and building style's look; every part's ranked blocks for
 *     every land and style; every age's make-over of every kind of block, for every building, over every kind of block
 *     that may stand there and every land stone; the land stones; the lamp posts, the benches, the shingle kept, the
 *     flower boxes, the storey palettes and the street's lamp; and the whole dressing of real houses (and an inn) in
 *     every style, at every age, over every style's dressing, change for change.</li>
 * <li><b>st02</b>: the materials: never a precious block or one of the Nether's or the End's, at any age; from the Iron
 *     Age no earth, gravel or sand in a wall, a floor or a roof, the builder going down the theme's stand-ins (stone
 *     bricks, cobblestone, stone, planks...) and else waiting; a young town's wall is never of earth either (it waits); the
 *     earth of a flower box is earth; the theme's own quartz footings are not held against it.</li>
 * <li><b>st03</b>: a second theme, pure data (the test theme), end to end: not offered; given to a town, its blocks
 *     first, its own house design, its own ages; a medieval house of the town's renovated into it.</li>
 * <li><b>st04</b>: an old house renovated to today's design, out of the stores, turn by turn: the household kept and
 *     never a bed short, the chest moved with all it held, the old partition down, earth and gold out of the walls.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 1,680,000 to 1,699,999, z 66,000, calling the town's logic directly and
 * logging what it sees.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class StylesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ============================================================ the ground and the towns

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

    record Town(UUID id, BlockPos heart, List<VillageFolkEntity> folk) {}

    static long start(ServerLevel level, long hour) {
        Kit.reset(level);
        Themes.resetForTests();
        level.setWeatherParameters(24000, 0, false, false);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + hour);
        return day;
    }

    static Town town(GameTestHelper helper, ServerLevel level, int x, int n, Homeland.Land land) {
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
        Homeland.setForTests(id, land);
        return new Town(id, heart, folk);
    }

    static Villages.Village village(GameTestHelper helper, UUID id) {
        Villages.Village v = Villages.get(id);
        helper.assertTrue(v != null, "the village is on the books");
        return v;
    }

    // ============================================================ st01: medieval, block for block

    /** Every kind of block that may stand in a building's place when an age or a style comes to it. */
    private static List<BlockState> standing() {
        List<BlockState> out = new ArrayList<>();
        Block[] plain = { Blocks.AIR, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_PLANKS,
            Blocks.MANGROVE_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICKS, Blocks.STONE, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_BRICKS, Blocks.BRICKS,
            Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE, Blocks.TERRACOTTA,
            Blocks.CUT_COPPER, Blocks.QUARTZ_BRICKS, Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_LOG, Blocks.STRIPPED_BIRCH_LOG,
            Blocks.GLASS, Blocks.GLASS_PANE, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE, Blocks.DIRT, Blocks.WHITE_WOOL,
            WorkItems.THATCH.get() };
        for (Block b : plain) out.add(b.defaultBlockState());
        Block[] stairs = { Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DEEPSLATE_TILE_STAIRS,
            Blocks.STONE_BRICK_STAIRS, Blocks.COBBLESTONE_STAIRS, Blocks.CUT_COPPER_STAIRS, Blocks.SANDSTONE_STAIRS, Blocks.BRICK_STAIRS,
            WorkItems.THATCH_STAIRS.get() };
        for (Block b : stairs) {
            for (Direction d : new Direction[]{ Direction.NORTH, Direction.EAST }) {
                for (Half h : Half.values()) {
                    out.add(b.defaultBlockState().setValue(StairBlock.FACING, d).setValue(StairBlock.HALF, h));
                }
            }
        }
        Block[] slabs = { Blocks.OAK_SLAB, Blocks.SPRUCE_SLAB, Blocks.DEEPSLATE_TILE_SLAB, Blocks.STONE_BRICK_SLAB, Blocks.COBBLESTONE_SLAB,
            Blocks.CUT_COPPER_SLAB, Blocks.MOSSY_COBBLESTONE_SLAB, WorkItems.THATCH_SLAB.get() };
        for (Block b : slabs) {
            for (SlabType t : new SlabType[]{ SlabType.BOTTOM, SlabType.TOP }) out.add(b.defaultBlockState().setValue(SlabBlock.TYPE, t));
        }
        return out;
    }

    /**
     * The medieval theme against the code it replaced, everywhere it chooses a block. A difference anywhere fails, with
     * the first few written out.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "st01_medieval")
    public static void st01_medieval_identical(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town coast = town(helper, level, 1680000, 2, Homeland.Land.COAST);
        Town forest = town(helper, level, 1680400, 2, Homeland.Land.FOREST);
        helper.runAtTickTime(10, () -> {
            ThemeData med = Themes.medieval();
            helper.assertTrue(Themes.of(coast.id()).id.equals(ThemeData.DEFAULT), "a town builds medieval unless told: " + Themes.of(coast.id()).id);
            List<String> bad = new ArrayList<>();
            int checked = 0;

            // (a) Every land's and style's look, and every part's ranked blocks.
            for (Homeland.Land land : Homeland.Land.values()) {
                List<Architecture.Style> styles = new ArrayList<>();
                styles.add(null);
                styles.addAll(List.of(Architecture.Style.values()));
                for (Architecture.Style st : styles) {
                    UUID id = UUID.nameUUIDFromBytes(("st01|" + land + "|" + st).getBytes());
                    Homeland.setForTests(id, land);
                    if (st != null) Architecture.setForTests(id, st);
                    Palettes.forget(id);
                    Palettes.Look old = StylesLegacy.look(st, StylesLegacy.forLand(land));
                    checked++;
                    if (!old.equals(Palettes.of(id))) bad.add("look " + land + "/" + st + ": " + old + " vs " + Palettes.of(id));
                    for (Blueprints.Style part : Blueprints.Style.values()) {
                        List<Item> was = StylesLegacy.ranked(old, part), now = Palettes.ranked(id, part);
                        checked++;
                        if (!was.equals(now)) bad.add("ranked " + land + "/" + st + "/" + part + ": " + was + " vs " + now);
                    }
                }
                // The land's stone.
                Homeland.Stone a = StylesLegacy.landStone(land), b = Themes.landStone(med, land);
                checked++;
                if ((a == null) != (b == null) || a != null && (a.block() != b.block() || a.each() != b.each() || !samePay(a.pay(), b.pay()))) {
                    bad.add("land stone " + land + ": " + (a == null ? null : a.block()) + " vs " + (b == null ? null : b.block()));
                }
            }
            Kit.log("st01 (a) looks, ranked parts, land stones: " + checked + " checked, " + bad.size() + " differ");

            // (b) Every age's look of every kind of block, for every building, over everything that may stand there.
            // (What a building is matters to the ages only as a home, the manor, or one of the great buildings: those, and
            // a few others, each with a storey on too.)
            List<String> drawings = new ArrayList<>(List.of("house", "house2", "manor", "villa", "smithy", "shop", "well", "store", "bakery"));
            for (String gr : med.set("great")) if (!drawings.contains(gr)) drawings.add(gr);
            for (String s : new ArrayList<>(drawings)) drawings.add(s + Blueprints.TALL);
            Block[] stones = { null, Blocks.CUT_SANDSTONE, Blocks.TERRACOTTA, Blocks.POLISHED_ANDESITE, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICKS };
            BlockPos[] spots = { new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0), new BlockPos(5, 70, -3) };
            List<BlockState> standing = standing();
            int before = bad.size(), agesChecked = 0;
            for (Villages.Age age : Villages.Age.values()) {
                for (String d : drawings) {
                    for (Blueprints.Style part : Blueprints.Style.values()) {
                        boolean footing = part == Blueprints.Style.FOUNDATION || part == Blueprints.Style.WALL_LOW;
                        BlockPos[] at = footing ? spots : new BlockPos[]{ spots[0] };
                        Block[] lands = part == Blueprints.Style.WALL ? stones : new Block[]{ null, Blocks.STONE_BRICKS };
                        for (BlockState now : standing) {
                            for (BlockPos pos : at) {
                                for (Block stone : lands) {
                                    BlockState was = StylesLegacy.ageLook(age, d, part, now, pos, stone);
                                    BlockState got = Themes.ageLook(med, age, d, part, now, pos, stone);
                                    agesChecked++;
                                    if (!Objects.equals(was, got) && bad.size() < before + 40) {
                                        bad.add("age " + age + " " + d + " " + part + " over " + now + " at " + pos.toShortString()
                                            + " stone " + stone + ": " + was + " vs " + got);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            checked += agesChecked;
            Kit.log("st01 (b) the ages' looks: " + agesChecked + " checked, " + (bad.size() - before) + " differ");

            // (c) The streets, the roofs kept, the flower boxes, the palettes.
            for (Architecture.Style st : Architecture.Style.values()) {
                Architecture.setForTests(coast.id(), st);
                Architecture.Post a = StylesLegacy.post(st), b = StylesLegacy.postNow(coast.id());
                checked++;
                if ((a == null) != (b == null) || a != null && (a.block() != b.block() || a.cost() != b.cost() || !samePay(a.pay(), b.pay()))) {
                    bad.add("lamp post " + st + ": " + (a == null ? null : a.block()) + " vs " + (b == null ? null : b.block()));
                }
                List<Architecture.Post> was = StylesLegacy.benches(st), now = StylesLegacy.benchesNow(coast.id());
                checked++;
                boolean same = was.size() == now.size();
                for (int i = 0; same && i < was.size(); i++) same = was.get(i).block() == now.get(i).block() && samePay(was.get(i).pay(), now.get(i).pay());
                if (!same) bad.add("benches " + st);
                Ledger.Building house = new Ledger.Building("house", coast.heart(), Direction.NORTH);
                checked++;
                boolean keeps = !Architecture.ageMayChange(coast.id(), house, Blueprints.Style.ROOF_STAIR);
                if (keeps != StylesLegacy.keepsRoof(st)) bad.add("shingle kept " + st + ": " + keeps);
                checked++;
                if (Architecture.boxesEverywhere(coast.id()) != StylesLegacy.boxes(st)) bad.add("flower boxes " + st);
            }
            String pal = StylesLegacy.palettes(med);
            checked++;
            if (!pal.isEmpty()) bad.add("palettes differ: " + pal);
            checked++;
            if (!StylesLegacy.streetNow(coast.id()).equals(StylesLegacy.streetPost() + " " + Blocks.LANTERN)) {
                bad.add("street lamp: " + StylesLegacy.streetNow(coast.id()));
            }

            // (d) Real houses (and an inn), made over for every age and dressed in every style, then planned in every
            // style again: the old plan and the new, change for change.
            int plans = 0;
            before = bad.size();
            for (Town t : List.of(coast, forest)) {
                Villages.Village v = village(helper, t.id());
                Architecture.setForTests(t.id(), Architecture.Style.FOREST_LODGE);   // its own frame wood is the lodge's
                for (String design : List.of("house", "tavern")) {
                    BlockPos at = t.heart().offset(0, 0, -16);
                    Ledger.Building b = new Ledger.Building(design, at, Direction.NORTH);
                    for (Villages.Age age : Villages.Age.values()) {
                        List<Architecture.Style> firsts = new ArrayList<>();
                        firsts.add(null);
                        firsts.addAll(List.of(Architecture.Style.values()));
                        for (Architecture.Style first : firsts) {
                            clearBox(level, at);
                            BuildGoal.stamp(level, design, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
                            Ages.now(level, v, b, age);
                            if (first != null) Architecture.dressAs(level, t.id(), b, first, age);
                            for (Architecture.Style st : Architecture.Style.values()) {
                                List<String> was = StylesLegacy.planWords(level, t.id(), b, st, age, true);
                                List<String> now = StylesLegacy.planWords(level, t.id(), b, st, age, false);
                                plans++;
                                if (!was.equals(now) && bad.size() < before + 20) {
                                    bad.add("dressing " + design + " " + age + " over " + first + " in " + st + ": " + firstDiff(was, now));
                                }
                            }
                        }
                    }
                    clearBox(level, at);
                }
            }
            checked += plans;
            Kit.log("st01 (d) the dressing of real houses: " + plans + " plans compared, " + (bad.size() - before) + " differ");
            Kit.log("st01 " + checked + " choices checked; " + bad.size() + " differ" + (bad.isEmpty() ? "" : ": " + bad.subList(0, Math.min(12, bad.size()))));
            helper.assertTrue(bad.isEmpty(), "the medieval theme chooses what the code chose: " + bad.subList(0, Math.min(6, bad.size())));
            helper.assertTrue(checked > 100000, "and every choice was looked at: " + checked);
            helper.succeed();
        });
    }

    private static final Item[] PAY_SAMPLES = { Items.DARK_OAK_LOG, Items.DARK_OAK_FENCE, Items.COBBLESTONE, Items.COBBLESTONE_WALL,
        Items.BIRCH_LOG, Items.BIRCH_FENCE, Items.SANDSTONE, Items.SANDSTONE_WALL, Items.SANDSTONE_STAIRS, Items.STONE_BRICKS,
        Items.STONE_BRICK_WALL, Items.STONE_BRICK_STAIRS, Items.COBBLESTONE_STAIRS, Items.OAK_LOG, Items.CUT_SANDSTONE, Items.TERRACOTTA,
        Items.ORANGE_TERRACOTTA, Items.RED_TERRACOTTA, Items.BROWN_TERRACOTTA, Items.YELLOW_TERRACOTTA, Items.WHITE_TERRACOTTA,
        Items.ANDESITE, Items.POLISHED_ANDESITE, Items.MOSSY_STONE_BRICKS, Items.DIRT };

    private static boolean samePay(java.util.function.Predicate<ItemStack> a, java.util.function.Predicate<ItemStack> b) {
        for (Item it : PAY_SAMPLES) if (a.test(new ItemStack(it)) != b.test(new ItemStack(it))) return false;
        return true;
    }

    private static String firstDiff(List<String> a, List<String> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : "-", y = i < b.size() ? b.get(i) : "-";
            if (!x.equals(y)) return "#" + i + " " + x + " vs " + y + " (" + a.size() + " vs " + b.size() + ")";
        }
        return "?";
    }

    /** The box a building stands in, emptied (its ground left). */
    static void clearBox(ServerLevel level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-9, 0, -9), at.offset(9, 16, 9))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-9, -1, -9), at.offset(9, -1, 9))) {
            level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
        }
    }

    // ============================================================ st02: the materials

    private static List<ItemStack> held(Item... items) {
        List<ItemStack> out = new ArrayList<>();
        for (Item it : items) out.add(new ItemStack(it, 16));
        return out;
    }

    private static String name(Item it) {
        return it == null ? "nothing" : BuiltInRegistries.ITEM.getKey(it).getPath();
    }

    /** What the builders build with, age by age, out of what they have; and what is never building material. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "st02_materials")
    public static void st02_materials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Town young = town(helper, level, 1681000, 2, Homeland.Land.PLAINS);
        Town grown = town(helper, level, 1681400, 2, Homeland.Land.PLAINS);
        helper.runAtTickTime(10, () -> {
            village(helper, young.id());
            village(helper, grown.id());
            Villages.ageForTests(young.id(), Villages.Age.WOOD);
            Villages.ageForTests(grown.id(), Villages.Age.IRON);
            UUID y = young.id(), g = grown.id();
            List<String> seen = new ArrayList<>();
            // An Iron Age wall: nothing of earth, gravel, sand or wool, nothing precious, nothing of the Nether or the End.
            Item junk = Materials.choiceForTests(g, Blueprints.Style.WALL, held(Items.DIRT, Items.GRAVEL, Items.SAND, Items.WHITE_WOOL,
                Items.DIAMOND_BLOCK, Items.GOLD_BLOCK, Items.NETHERRACK, Items.NETHER_BRICKS, Items.BLACKSTONE, Items.QUARTZ_BLOCK,
                Items.OBSIDIAN, Items.END_STONE, Items.PURPUR_BLOCK, Items.BASALT));
            seen.add("iron wall of junk: " + name(junk));
            helper.assertTrue(junk == null, "an Iron Age builder waits rather than wall in junk: " + name(junk));
            // The stand-ins in order: stone bricks before cobblestone before stone before planks.
            Item a = Materials.choiceForTests(g, Blueprints.Style.WALL, held(Items.DIRT, Items.STONE, Items.COBBLESTONE, Items.STONE_BRICKS));
            Item b = Materials.choiceForTests(g, Blueprints.Style.WALL, held(Items.DIRT, Items.STONE, Items.COBBLESTONE));
            Item c = Materials.choiceForTests(g, Blueprints.Style.WALL, held(Items.DIRT, Items.STONE, Items.DIAMOND_BLOCK));
            seen.add("iron wall stand-ins: " + name(a) + ", " + name(b) + ", " + name(c));
            helper.assertTrue(a == Items.STONE_BRICKS && b == Items.COBBLESTONE && c == Items.STONE, "stone bricks, cobblestone, stone: "
                + name(a) + ", " + name(b) + ", " + name(c));
            // The town's own look first, as ever: its planks before any stand-in.
            Item own = Materials.choiceForTests(g, Blueprints.Style.WALL, held(Items.COBBLESTONE, Items.OAK_PLANKS));
            seen.add("own planks: " + name(own));
            helper.assertTrue(own == Items.OAK_PLANKS, "the town's own wood first: " + name(own));
            // A footing: rough stone, never blackstone; a roof: no gravel, no Nether brick stairs.
            Item foot = Materials.choiceForTests(g, Blueprints.Style.FOUNDATION, held(Items.BLACKSTONE, Items.ANDESITE));
            Item roof = Materials.choiceForTests(g, Blueprints.Style.ROOF_STAIR, held(Items.GRAVEL, Items.NETHER_BRICK_STAIRS, Items.BLACKSTONE_STAIRS));
            Item roof2 = Materials.choiceForTests(g, Blueprints.Style.ROOF_STAIR, held(Items.GRAVEL, Items.STONE_BRICK_STAIRS));
            seen.add("footing " + name(foot) + ", roof of junk " + name(roof) + ", roof " + name(roof2));
            helper.assertTrue(foot == Items.ANDESITE, "a footing of andesite, not blackstone: " + name(foot));
            helper.assertTrue(roof == null && roof2 == Items.STONE_BRICK_STAIRS, "no roof of gravel or the Nether's: " + name(roof) + ", " + name(roof2));
            // The earth of a flower box is earth, in any age.
            Item soil = Materials.choiceForTests(g, Blueprints.Style.SOIL, held(Items.DIRT));
            helper.assertTrue(soil == Items.DIRT, "a flower box of earth: " + name(soil));
            // A young town's wall is never of earth either ([integrity]: earth only where earth goes), nor the Nether's or a
            // precious block: with only those in hand it waits for planks or stone; with planks it builds.
            Item early = Materials.choiceForTests(y, Blueprints.Style.WALL, held(Items.NETHERRACK, Items.DIAMOND_BLOCK, Items.DIRT));
            Item early2 = Materials.choiceForTests(y, Blueprints.Style.WALL, held(Items.NETHERRACK, Items.EMERALD_BLOCK));
            Item early3 = Materials.choiceForTests(y, Blueprints.Style.WALL, held(Items.NETHERRACK, Items.DIRT, Items.OAK_PLANKS));
            seen.add("wood age: " + name(early) + ", " + name(early2) + ", " + name(early3));
            helper.assertTrue(early == null && early2 == null, "a Wood Age wall waits rather than lay earth, never netherrack or emerald: "
                + name(early) + ", " + name(early2));
            helper.assertTrue(early3 == Items.OAK_PLANKS, "and is built of planks when it has them: " + name(early3));
            // What stands in a building that is not fit, for the renovation.
            boolean dirtIron = Materials.unfit(g, Blueprints.Style.WALL, Blocks.DIRT.defaultBlockState());
            boolean dirtWood = Materials.unfit(y, Blueprints.Style.WALL, Blocks.DIRT.defaultBlockState());
            boolean gold = Materials.unfit(y, Blueprints.Style.WALL, Blocks.GOLD_BLOCK.defaultBlockState());
            boolean quartz = Materials.unfit(g, Blueprints.Style.FOUNDATION, Blocks.QUARTZ_BRICKS.defaultBlockState());
            boolean soilBox = Materials.unfit(g, Blueprints.Style.SOIL, Blocks.DIRT.defaultBlockState());
            seen.add("unfit: dirt(iron) " + dirtIron + ", dirt(wood) " + dirtWood + ", gold " + gold + ", quartz footing " + quartz + ", soil " + soilBox);
            helper.assertTrue(dirtIron && !dirtWood && gold, "earth unfit from the Iron Age, gold always");
            helper.assertTrue(!quartz && !soilBox, "the theme's own quartz footings and a flower box's earth are fit");
            Kit.log("st02 " + seen);
            helper.succeed();
        });
    }

    // ============================================================ st03: a second theme, end to end

    private static java.util.Map<Block, Integer> blocks(ServerLevel level, BlockPos anchor) {
        java.util.Map<Block, Integer> out = new java.util.HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-6, -1, -6), anchor.offset(6, 11, 6))) {
            BlockState st = level.getBlockState(p);
            if (!st.isAir()) out.merge(st.getBlock(), 1, Integer::sum);
        }
        return out;
    }

    private static int count(java.util.Map<Block, Integer> m, Block b) {
        return m.getOrDefault(b, 0);
    }

    /**
     * A second theme, pure data (the test theme: end stone and purpur, its own house with whole-glass front windows,
     * its own Iron Age rule, its own street lamp): not offered to the towns; given to one, its builders reach for its
     * blocks over the medieval ones they also carry, build its own design of the house, and its older medieval house is
     * renovated into it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "st03_theme")
    public static void st03_second_theme(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Renovation.resetForTests();
        Town t = town(helper, level, 1682000, 2, Homeland.Land.PLAINS);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, t.id());
            UUID id = t.id();
            Villages.ageForTests(id, Villages.Age.IRON);
            ThemeData test = Themes.get("styles_test");
            helper.assertTrue(test != null, "the test theme is read");
            boolean offered = false;
            for (ThemeData o : Themes.offered()) offered |= o.id.equals("styles_test");
            helper.assertTrue(!offered, "the test theme is not offered to a town");
            // A house of the town's medieval days.
            BlockPos old = t.heart().offset(-10, 0, -16);
            BuildGoal.stamp(level, "house", old, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", old, Direction.NORTH);
            Renovation.Stamp was = Renovation.stamp(id, old);
            helper.assertTrue(was != null && was.theme().equals("medieval"), "stamped medieval as it went up: " + was);
            // The theme given.
            helper.assertTrue(Themes.set(id, "styles_test"), "the theme given to the town");
            List<Item> walls = Palettes.ranked(id, Blueprints.Style.WALL), roof = Palettes.ranked(id, Blueprints.Style.ROOF_STAIR);
            helper.assertTrue(!walls.isEmpty() && walls.get(0) == Items.END_STONE_BRICKS && roof.get(0) == Items.PURPUR_STAIRS,
                "its builders reach for its blocks first: " + walls + " / " + roof);
            String drawing = Themes.design(id, "house");
            helper.assertTrue(drawing.equals("styles_test/house") && Themes.design(id, "smithy").equals("smithy"),
                "its own house design, the usual one for the rest: " + drawing);
            // A new house, each block what a builder carrying both looks' blocks lays (BuildGoal's own order).
            List<ItemStack> pack = held(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.DARK_OAK_STAIRS, Items.DARK_OAK_SLAB, Items.DARK_OAK_PLANKS,
                Items.SPRUCE_LOG, Items.COBBLESTONE, Items.STONE_BRICKS, Items.END_STONE_BRICKS, Items.PURPUR_BLOCK, Items.PURPUR_STAIRS,
                Items.PURPUR_SLAB, Items.PURPUR_PILLAR, Items.END_STONE);
            java.util.function.Function<BuildGoal.Placement, BlockState> oak = Showcase.painter(Showcase.OAK);
            BlockPos at = t.heart().offset(10, 0, -16);
            BuildGoal.stamp(level, drawing, at, Direction.NORTH, 13, p -> {
                if (p.part() != BuildGoal.Part.BLOCK) return oak.apply(p);
                Item it = BuildGoal.choose(id, p.style(), pack);
                return it instanceof net.minecraft.world.item.BlockItem bi ? bi.getBlock().defaultBlockState() : null;
            });
            Ledger.built(id, "house", at, Direction.NORTH);
            java.util.Map<Block, Integer> m = blocks(level, at);
            Kit.log("st03 the test theme's house: " + m);
            helper.assertTrue(count(m, Blocks.END_STONE_BRICKS) >= 20 && count(m, Blocks.PURPUR_STAIRS) >= 10 && count(m, Blocks.PURPUR_PILLAR) >= 4,
                "end stone walls, a purpur roof and pillars: " + m);
            helper.assertTrue(count(m, Blocks.OAK_PLANKS) == 0 && count(m, Blocks.DARK_OAK_STAIRS) == 0, "nothing of the medieval look in it");
            helper.assertTrue(count(m, Blocks.GLASS) >= 2, "its own design's whole-glass front windows: " + count(m, Blocks.GLASS));
            // Its ages are its own: its Iron Age rule, and none of the medieval ones.
            BlockState ironRoof = Themes.ageLook(Themes.of(id), Villages.Age.IRON, "house", Blueprints.Style.ROOF_BLOCK,
                Blocks.PURPUR_BLOCK.defaultBlockState(), at, null);
            BlockState ironWall = Themes.ageLook(Themes.of(id), Villages.Age.IRON, "smithy", Blueprints.Style.WALL,
                Blocks.OAK_PLANKS.defaultBlockState(), at, null);
            helper.assertTrue(ironRoof != null && ironRoof.is(Blocks.PRISMARINE_BRICKS) && ironWall == null,
                "its own Iron Age (prismarine over purpur), not the medieval one's stone walls: " + ironRoof + ", " + ironWall);
            // The materials let it have the end stone it names.
            helper.assertTrue(!Materials.unfit(id, Blueprints.Style.WALL, Blocks.END_STONE_BRICKS.defaultBlockState())
                && Materials.unfit(id, Blueprints.Style.WALL, Blocks.NETHER_BRICKS.defaultBlockState()), "its own end stone fit, the Nether's not");
            // The medieval house renovated into the theme.
            List<BlockPos> q = Renovation.surveyForTests(level, v);
            helper.assertTrue(q.contains(old), "the medieval house is on the list: " + q);
            int n = Renovation.renovateNow(level, v, new Ledger.Building("house", old, Direction.NORTH));
            java.util.Map<Block, Integer> r = blocks(level, old);
            Renovation.Stamp now = Renovation.stamp(id, old);
            Kit.log("st03 the old house renovated (" + n + " blocks): " + r + "; stamp " + now);
            helper.assertTrue(count(r, Blocks.END_STONE_BRICKS) >= 20 && count(r, Blocks.PURPUR_STAIRS) >= 10, "renovated into end stone and purpur: " + r);
            helper.assertTrue(count(r, Blocks.OAK_PLANKS) == 0 && count(r, Blocks.SPRUCE_PLANKS) == 0 && count(r, Blocks.DARK_OAK_STAIRS) == 0,
                "no medieval timber left: " + r);
            helper.assertTrue(count(r, Blocks.GLASS) >= 2, "its windows to the theme's own design");
            helper.assertTrue(now != null && now.theme().equals("styles_test") && now.version() == Blueprints.version("styles_test/house"),
                "stamped as the theme's: " + now);
            helper.assertTrue(Renovation.workForTests(level, v, new Ledger.Building("house", old, Direction.NORTH)).isEmpty(), "nothing left to do");
            helper.succeed();
        });
    }

    // ============================================================ st04: an old house renovated to the new design

    /**
     * A house built to an older design (its beds and chest elsewhere, a partition by the door, a side window walled, and
     * earth and a block of gold in its walls) is renovated in place to today's, turn by turn, out of the stores: its
     * household stays, every one with a bed, never a bed short between turns; its chest moves whole with everything in
     * it; the old partition comes down; the earth and the gold go back into the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "st04_renovation")
    public static void st04_renovation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000L);
        Renovation.resetForTests();
        Town t = town(helper, level, 1683000, 4, Homeland.Land.PLAINS);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, t.id());
            UUID id = t.id();
            Villages.ageForTests(id, Villages.Age.IRON);
            BlockPos at = t.heart().offset(0, 0, -16);
            Ledger.Building b = new Ledger.Building("house", at, Direction.NORTH);
            long oldV = Blueprints.version("styles_old/house"), newV = Blueprints.version("house");
            helper.assertTrue(oldV != newV, "two versions of the design");
            BuildGoal.stamp(level, "styles_old/house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", at, Direction.NORTH);
            long day = level.getDayTime() / 24000L;
            Renovation.stampForTests(id, at, new Renovation.Stamp(oldV, "styles_old/house", "medieval", "STONE", "-", day - 20));
            // Earth and a block of gold in its walls (an older town's makeshift).
            List<BlockPos> wallCells = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan("house", at, Direction.NORTH, 13)) {
                if (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.WALL && p.pos().getY() == at.getY() + 2) wallCells.add(p.pos());
            }
            level.setBlock(wallCells.get(0), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(wallCells.get(1), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            // Its beds counted for the house, and its household moved in.
            List<String> heads = new ArrayList<>();
            BlockPos chestAt = null;
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-5, 0, -5), at.offset(5, 6, 5))) {
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                        && st.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                    heads.add(Long.toString(p.asLong()));
                }
                if (st.is(Blocks.CHEST)) chestAt = p.immutable();
            }
            helper.assertTrue(heads.size() == 4 && chestAt != null, "the old house's four beds and its chest: " + heads.size() + ", " + chestAt);
            Ledger.note(id, "homebeds/" + at.asLong(), String.join(",", heads));
            for (int i = 0; i < 6 && Homes.membersForTests(id, at).isEmpty(); i++) Homes.tickForTests(level, v);
            List<UUID> members = Homes.membersForTests(id, at);
            helper.assertTrue(!members.isEmpty(), "a household lives in it");
            // What the household keeps in its chest.
            net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chestAt);
            box.setItem(0, new ItemStack(Items.BREAD, 20));
            box.setItem(5, new ItemStack(Items.DIAMOND, 3));
            ItemStack keepsake = new ItemStack(Items.WRITABLE_BOOK);
            keepsake.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Grandmother's recipes"));
            box.setItem(9, keepsake);
            box.setChanged();
            // The stores.
            BlockPos storeAt = t.heart().offset(4, 0, 4);
            level.setBlock(storeAt, Blocks.CHEST.defaultBlockState(), 3);
            com.jrpetty.mcassistant.entity.ZoneChests.mark(level, storeAt);
            net.minecraft.world.Container stores = (net.minecraft.world.Container) level.getBlockEntity(storeAt);
            Item[] goods = { Items.OAK_PLANKS, Items.OAK_PLANKS, Items.COBBLESTONE, Items.STONE_BRICKS, Items.GLASS_PANE, Items.SPRUCE_LOG,
                Items.DARK_OAK_STAIRS, Items.DARK_OAK_SLAB, Items.OAK_DOOR, Items.LADDER };
            for (int i = 0; i < goods.length; i++) stores.setItem(i, new ItemStack(goods[i], goods[i] == Items.OAK_DOOR ? 2 : 64));
            stores.setChanged();
            Villages.forgetStock();
            int goldBefore = com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.GOLD_BLOCK));
            // On the list, and what it will take.
            List<BlockPos> q = Renovation.surveyForTests(level, v);
            java.util.Map<Renovation.Kind, Integer> work = Renovation.workForTests(level, v, b);
            Kit.log("st04 the list " + q + "; the work " + work);
            helper.assertTrue(q.contains(at), "the old house is on the renovation list");
            helper.assertTrue(work.getOrDefault(Renovation.Kind.BEDS, 0) == 1 && work.getOrDefault(Renovation.Kind.MOVE, 0) >= 1
                && work.getOrDefault(Renovation.Kind.CLEAR, 0) >= 2 && work.getOrDefault(Renovation.Kind.BLOCK, 0) >= 2
                && work.getOrDefault(Renovation.Kind.PART, 0) >= 1, "beds and the chest to move, the partition down, the walls mended, a window: " + work);
            // Turn by turn, out of the stores, the beds counted after every turn.
            int fewest = Integer.MAX_VALUE, turns = 0, laid = 0;
            while (turns < 100) {
                int n = Renovation.renovate(level, v, b, 12, false);
                turns++;
                fewest = Math.min(fewest, Homes.bedsForTests(level, id, at).size());
                if (n <= 0) break;
                laid += n;
            }
            java.util.Map<Renovation.Kind, Integer> left = Renovation.workForTests(level, v, b);
            Renovation.Stamp now = Renovation.stamp(id, at);
            Kit.log("st04 renovated in " + turns + " turns, " + laid + " blocks; left " + left + "; fewest beds " + fewest + "; stamp " + now
                + "; waits '" + Renovation.waits(at) + "'");
            helper.assertTrue(left.isEmpty(), "nothing left to do: " + left);
            helper.assertTrue(now != null && now.version() == newV && now.drawing().equals("house"), "stamped to today's design: " + now);
            // The design, block for block: its walls, floors and roof whole and fit, its beds where it lays them.
            int wrong = 0;
            for (BuildGoal.Placement p : BuildGoal.plan("house", at, Direction.NORTH, 13)) {
                BlockState st = level.getBlockState(p.pos());
                if (p.part() == BuildGoal.Part.BLOCK && (st.isAir() || Materials.unfit(id, p.style(), st))) wrong++;
                if (p.part() == BuildGoal.Part.BED && !(st.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) wrong++;
                if (p.part() == BuildGoal.Part.CHEST && !st.is(Blocks.CHEST)) wrong++;
            }
            helper.assertTrue(wrong == 0, "the house is today's design: " + wrong + " places wrong");
            // The chest, moved whole.
            BlockPos newChest = null;
            for (BuildGoal.Placement p : BuildGoal.plan("house", at, Direction.NORTH, 13)) if (p.part() == BuildGoal.Part.CHEST) newChest = p.pos();
            net.minecraft.world.Container moved = (net.minecraft.world.Container) level.getBlockEntity(newChest);
            int bread = 0, diamonds = 0;
            boolean book = false;
            for (int i = 0; moved != null && i < moved.getContainerSize(); i++) {
                ItemStack st = moved.getItem(i);
                if (st.is(Items.BREAD)) bread += st.getCount();
                if (st.is(Items.DIAMOND)) diamonds += st.getCount();
                if (st.is(Items.WRITABLE_BOOK) && st.getHoverName().getString().equals("Grandmother's recipes")) book = true;
            }
            helper.assertTrue(!newChest.equals(chestAt) && bread == 20 && diamonds == 3 && book, "the chest moved with all it held: " + bread + " bread, "
                + diamonds + " diamonds, the book " + book);
            // The household: the same folk, each with a bed, never a bed short.
            List<UUID> after = Homes.membersForTests(id, at);
            int beds = Homes.bedsForTests(level, id, at).size();
            helper.assertTrue(after.containsAll(members) && members.containsAll(after), "the same household: " + members + " / " + after);
            helper.assertTrue(beds >= members.size() && fewest >= members.size(), "a bed each, after and all through: " + beds + ", fewest " + fewest);
            for (VillageFolkEntity f : t.folk()) {
                if (members.contains(f.getUUID())) helper.assertTrue(Homes.bedFor(level, f) != null, "a bed for " + f.getName().getString());
            }
            // The earth and the gold out of the walls, back into the stores.
            int goldAfter = com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.GOLD_BLOCK));
            helper.assertTrue(!level.getBlockState(wallCells.get(0)).is(Blocks.DIRT) && !level.getBlockState(wallCells.get(1)).is(Blocks.GOLD_BLOCK),
                "no earth or gold in the walls");
            helper.assertTrue(goldAfter == goldBefore + 1, "the gold back into the stores: " + goldBefore + " -> " + goldAfter);
            helper.succeed();
        });
    }
}
