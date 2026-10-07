package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [culture2] Each town builds its own way. Beyond the land's stone (Homeland) and the look each age gives every town
 * (Grow, Ages), a town has a building style, chosen at its founding from its land, its founders and its temper, and
 * kept: it can change, slowly, as the town does (a town grown rich takes to the Grand Civic), and its houses are dressed
 * anew in the new style as the dressers come round to them. The style changes real blocks in the town's own houses, a
 * few at a time, by a hand at the town's works, out of the stores, and what comes off goes back into them:
 * <ul>
 * <li><b>Timbered Lowland</b>: a band of dark timber round each storey under the eaves, timber posts, wooden shutters by
 *     the front windows, shingle roofs kept wood through every age, a brick chimney with a fire in its pot, and a box
 *     of flowers under the windows of every house, not only the well-off ones.</li>
 * <li><b>Hill Fort</b>: walls of rough stone (of deep stone bricks from the Iron Age), corner stones, stone roofs (slate
 *     from the Iron Age), the side windows walled up small, and a parapet of battlements along the eaves.</li>
 * <li><b>Coastal</b>: white walls of polished diorite, pale birch posts, the windows glazed in the town's sea colour, a
 *     banner of its colours at each front corner, and a barrel by the door.</li>
 * <li><b>Desert Court</b>: cut sandstone walls, sandstone roofs, smooth sandstone columns, and a fired pot by the door.</li>
 * <li><b>Forest Lodge</b>: walls of laid logs, stripped posts, shutters, a mossy ridge, a carved board by the door with
 *     the household's names, and smoke from the chimney.</li>
 * <li><b>Grand Civic</b>: polished andesite walls, columns of chiselled stone, and the town's colours on banners at the
 *     corners: a hierarchical or a rich town's.</li>
 * </ul>
 * The streets follow: each style's lamp posts (dark oak, rough stone, birch, sandstone, spruce, stone brick) and its
 * benches (stone or sandstone ones in the stone towns). Its new buildings go up in its own woods first (Palettes).
 */
public final class Architecture {

    private Architecture() {}

    public enum Style {
        TIMBERED("Timbered Lowland", "Our houses are timber-framed, with shutters and flower boxes — the prettiest street you'll see."),
        HILL_FORT("Hill Fort", "We build in stone here, with battlements on the eaves. Let them come."),
        COASTAL("Coastal", "White walls and blue glass, like every good harbour town."),
        DESERT_COURT("Desert Court", "Sandstone, cool inside even at noon. That's how the well-towns build."),
        FOREST_LODGE("Forest Lodge", "Log houses, moss on the ridge and smoke from every chimney. Snug as anything."),
        GRAND_CIVIC("Grand Civic", "Polished stone and columns. We're a proper town now, and it shows.");

        public final String words, boast;

        Style(String words, String boast) {
            this.words = words;
            this.boast = boast;
        }

        @Nullable
        static Style byName(@Nullable String s) {
            if (s == null) return null;
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The buildings a town dresses in its style: its homes. */
    static final Set<String> HOMES = Set.of("house", "manor", "villa");
    /** How much better a new style must suit the town than its own before it turns to it. */
    static final int DRIFT_MARGIN = 3;

    /** Each building's dressing as last finished: anchor to "STYLE|age". */
    private static final Map<UUID, Map<Long, String>> DRESSED = new ConcurrentHashMap<>();

    static void resetForTests() {
        DRESSED.clear();
        WAIT.clear();
    }

    /** The town's style, once chosen; null before. */
    @Nullable
    public static Style of(@Nullable UUID village) {
        return village == null ? null : Style.byName(TownWays.note(village, "style"));
    }

    /** Tests: this town's style set so. */
    public static void setForTests(UUID village, Style s) {
        TownWays.note(village, "style", s.name());
        Palettes.forget(village);
    }

    // ------------------------------------------------------------------ choosing it

    /** How well each style suits the town: its land, a temper (its founders' at the founding, its own after), its means. */
    static Map<Style, Integer> scores(UUID village, Values.Value temper) {
        Map<Style, Integer> s = new EnumMap<>(Style.class);
        for (Style st : Style.values()) s.put(st, 0);
        Homeland.Land land = Homeland.of(village);
        switch (land) {
            case PLAINS, MEADOW -> s.merge(Style.TIMBERED, 6, Integer::sum);
            case RIVER -> { s.merge(Style.COASTAL, 4, Integer::sum); s.merge(Style.TIMBERED, 3, Integer::sum); }
            case COAST -> s.merge(Style.COASTAL, 6, Integer::sum);
            case MOUNTAIN -> s.merge(Style.HILL_FORT, 6, Integer::sum);
            case BADLANDS -> { s.merge(Style.HILL_FORT, 4, Integer::sum); s.merge(Style.DESERT_COURT, 4, Integer::sum); }
            case SNOW -> { s.merge(Style.HILL_FORT, 5, Integer::sum); s.merge(Style.FOREST_LODGE, 3, Integer::sum); }
            case DESERT -> s.merge(Style.DESERT_COURT, 6, Integer::sum);
            case SAVANNA -> s.merge(Style.DESERT_COURT, 5, Integer::sum);
            case FOREST, TAIGA -> s.merge(Style.FOREST_LODGE, 6, Integer::sum);
            case JUNGLE -> s.merge(Style.FOREST_LODGE, 5, Integer::sum);
            case SWAMP -> { s.merge(Style.FOREST_LODGE, 4, Integer::sum); s.merge(Style.COASTAL, 2, Integer::sum); }
        }
        switch (temper) {
            case SAFETY -> s.merge(Style.HILL_FORT, 3, Integer::sum);
            case WEALTH -> { s.merge(Style.GRAND_CIVIC, 3, Integer::sum); s.merge(Style.COASTAL, 1, Integer::sum); }
            case PROGRESS -> s.merge(Style.GRAND_CIVIC, 2, Integer::sum);
            case TRADITION -> { s.merge(Style.FOREST_LODGE, 2, Integer::sum); s.merge(Style.TIMBERED, 1, Integer::sum); }
            case HOMES, FOOD -> s.merge(Style.TIMBERED, 2, Integer::sum);
            case LEISURE -> { s.merge(Style.TIMBERED, 1, Integer::sum); s.merge(Style.COASTAL, 1, Integer::sum); }
        }
        // A town that has had the raiders at its gate thinks of walls (up to three raids).
        int raids = 0;
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains("raiders came")) raids++;
        s.merge(Style.HILL_FORT, Math.min(3, raids), Integer::sum);
        // A rich town, a big one, one far along: the Grand Civic.
        int coin = Ledger.coins(village);
        s.merge(Style.GRAND_CIVIC, (coin >= 600 ? 5 : coin >= 300 ? 3 : 0)
            + (Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal() ? 2 : 0) + (Villages.headcount(village) >= 40 ? 2 : 0), Integer::sum);
        return s;
    }

    static Style best(Map<Style, Integer> s) {
        Style best = Style.TIMBERED;
        for (Style st : Style.values()) if (s.get(st) > s.get(best)) best = st;
        return best;
    }

    /**
     * The style chosen at the founding (TownWays.daily, the first day the town's land is known), from its land and its
     * founders' temper; and, once a week after, whether the town has changed enough to build another way: a style that
     * suits it better by three, worked out with its temper now and its means, takes over. The chronicle says so, and
     * the houses are dressed anew as the dressers come round to them.
     */
    static void choose(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Style now = of(id);
        if (now == null) {
            Style st = best(scores(id, TownWays.founders(id)));
            TownWays.note(id, "style", st.name());
            TownWays.note(id, "style.since", Long.toString(day));
            Palettes.forget(id);
            Villages.tell(id, day, "the founders of " + Villages.name(id) + " chose to build in the " + st.words + " way");
            return;
        }
        long since = Culture.num(String.valueOf(TownWays.note(id, "style.since")), day);
        if (day - since < 7) return;
        Map<Style, Integer> s = scores(id, TownWays.heart(id));
        Style better = best(s);
        if (better == now || s.get(better) < s.get(now) + DRIFT_MARGIN) return;
        TownWays.note(id, "style", better.name());
        TownWays.note(id, "style.since", Long.toString(day));
        Palettes.forget(id);
        Villages.tell(id, day, "the town turned from its " + now.words + " ways to the " + better.words
            + ": its houses will be dressed anew as the builders come round to them");
    }

    /** Tests: the choice made now (and a change of style, if the town has changed enough), whatever the week. */
    public static Style chooseForTests(ServerLevel level, Villages.Village v, boolean drift) {
        if (drift) TownWays.note(v.id(), "style.since", Long.toString(level.getDayTime() / 24000L - 7));
        choose(level, v, level.getDayTime() / 24000L);
        return of(v.id());
    }

    // ------------------------------------------------------------------ its woods (Palettes)

    /** The town's look in its style's woods: the builders reach for these first for a new building (Palettes.of). */
    public static Palettes.Look look(UUID village, Palettes.Look base) {
        Style st = of(village);
        if (st == null) return base;
        return switch (st) {
            case TIMBERED -> new Palettes.Look("birch", "spruce", base.floor(), "dark_oak", base.footing(), base.dressed());
            case HILL_FORT -> new Palettes.Look(base.walls(), "dark_oak", base.floor(), "spruce", base.footing(), base.dressed());
            case COASTAL -> new Palettes.Look("birch", "spruce", base.floor(), "birch", base.footing(), base.dressed());
            case DESERT_COURT -> new Palettes.Look("birch", base.roof(), base.floor(), "acacia", base.footing(), base.dressed());
            case FOREST_LODGE -> new Palettes.Look("spruce", "dark_oak", base.floor(), "spruce", base.footing(), base.dressed());
            case GRAND_CIVIC -> new Palettes.Look("oak", "dark_oak", base.floor(), "dark_oak", base.footing(), base.dressed());
        };
    }

    // ------------------------------------------------------------------ the dressing

    /** A cell of a building's drawing, with where it is in the drawing's own terms. */
    record Cell(BuildGoal.Placement p, int dx, int h, int dz) {}

    /** One block to change: where, to what, and whether it is laid into the air (a new thing) or in place of the old. */
    record Change(BlockPos pos, BlockState want, boolean fresh, String what) {}

    /** The building's cells, in the drawing's terms (across, up, back). */
    static List<Cell> cells(Ledger.Building b, String drawing) {
        Direction back = b.facing(), right = back.getClockWise();
        BlockPos a = b.anchor();
        List<Cell> out = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, a, back, 13)) {
            int x = p.pos().getX() - a.getX(), z = p.pos().getZ() - a.getZ();
            out.add(new Cell(p, x * right.getStepX() + z * right.getStepZ(), p.pos().getY() - a.getY(), x * back.getStepX() + z * back.getStepZ()));
        }
        return out;
    }

    /** What the age is in a town (Villages.Age), as a number. */
    private static int age(UUID village) {
        return Villages.ageOf(village).ordinal();
    }

    /** The town's colour for its trim: the field of its arms, or its land's own before it has arms. */
    static DyeColor trim(UUID village) {
        Heraldry.Design d = Heraldry.design(village);
        if (d != null) return d.field();
        return switch (Homeland.of(village)) {
            case COAST -> DyeColor.BLUE;
            case RIVER -> DyeColor.LIGHT_BLUE;
            case FOREST, JUNGLE -> DyeColor.GREEN;
            case TAIGA -> DyeColor.CYAN;
            case SNOW -> DyeColor.WHITE;
            case MOUNTAIN -> DyeColor.GRAY;
            case DESERT -> DyeColor.YELLOW;
            case SAVANNA -> DyeColor.ORANGE;
            case SWAMP -> DyeColor.BROWN;
            case BADLANDS -> DyeColor.RED;
            case MEADOW -> DyeColor.PINK;
            case PLAINS -> DyeColor.LIME;
        };
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(id));
    }

    /** A wall of a house, as the dresser may take it down: timber, logs, any stone, brick, the land's own. */
    static boolean wallish(BlockState s) {
        if (s.isAir() || s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS)) return !s.isAir();
        Item it = s.getBlock().asItem();
        if (it == Items.AIR) return false;
        ItemStack st = new ItemStack(it);
        return BuildGoal.isStoneLike(st) || s.is(Blocks.BRICKS) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.CALCITE)
            || s.is(Blocks.POLISHED_DIORITE) || s.is(Blocks.DIORITE) || s.is(Blocks.CHISELED_STONE_BRICKS);
    }

    /** A roof as the dresser may take it down: wooden or stone stairs, slabs and blocks. */
    static boolean roofish(BlockState s) {
        return s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.PLANKS) || wallish(s);
    }

    /** The wall's run at a cell: along the front and back walls (across the building), or down the sides. */
    static Direction.Axis runOf(Cell c, Map<Long, Cell> byPos, Direction back) {
        Direction right = back.getClockWise();
        Cell l = byPos.get(c.p().pos().relative(right).asLong()), r = byPos.get(c.p().pos().relative(right.getOpposite()).asLong());
        boolean across = l != null && isWallCell(l) || r != null && isWallCell(r);
        return across ? right.getAxis() : back.getAxis();
    }

    private static boolean isWallCell(Cell c) {
        BuildGoal.Part part = c.p().part();
        Blueprints.Style st = c.p().style();
        return part == BuildGoal.Part.WINDOW || part == BuildGoal.Part.DOOR
            || part == BuildGoal.Part.BLOCK && (st == Blueprints.Style.WALL || st == Blueprints.Style.POST || st == Blueprints.Style.WALL_LOW);
    }

    /**
     * What the style would change on this building as it stands: every block of its walls, roof, posts, windows and
     * chimney that is not yet the style's, and the things the style adds (shutters, banners, battlements, a pot, a
     * barrel, a fire in the chimney pot, a carved board), in the order they are done. Nothing is listed that the
     * building does not have, or where something else already stands.
     */
    static List<Change> plan(ServerLevel level, UUID village, Ledger.Building b, Style st) {
        String drawing = Ages.drawing(village, b);
        List<Change> out = new ArrayList<>();
        if (!Blueprints.has(drawing)) return out;
        List<Cell> cells = cells(b, drawing);
        Map<Long, Cell> byPos = new HashMap<>();
        for (Cell c : cells) if (c.p().part() != BuildGoal.Part.CLEAR) byPos.put(c.p().pos().asLong(), c);
        Direction back = b.facing(), front = back.getOpposite(), right = back.getClockWise();
        int age = age(village);
        boolean stone = age >= Villages.Age.STONE.ordinal(), iron = age >= Villages.Age.IRON.ordinal();
        int frontDz = Integer.MAX_VALUE, eavesH = Integer.MAX_VALUE, minDz = Integer.MAX_VALUE, maxDz = Integer.MIN_VALUE;
        int wallBack = Integer.MIN_VALUE;
        Cell chimneyTop = null;
        for (Cell c : cells) {
            if (c.p().part() == BuildGoal.Part.DOOR && c.dz() < frontDz) frontDz = c.dz();
            if (c.p().part() == BuildGoal.Part.BLOCK && c.p().style() == Blueprints.Style.POST) wallBack = Math.max(wallBack, c.dz());
            if (c.p().part() == BuildGoal.Part.BLOCK && BuildGoal.isRoof(c.p().style()) && c.p().style() != Blueprints.Style.ROOF_BLOCK) {
                eavesH = Math.min(eavesH, c.h());
                minDz = Math.min(minDz, c.dz());
                maxDz = Math.max(maxDz, c.dz());
            }
            if (c.p().part() == BuildGoal.Part.BLOCK && (c.p().style() == Blueprints.Style.MASONRY || c.p().style() == Blueprints.Style.BRICK)
                    && (chimneyTop == null || c.h() > chimneyTop.h())) chimneyTop = c;
        }
        // The walls, the posts, the roof, the chimney and the windows, cell by cell.
        for (Cell c : cells) {
            BuildGoal.Placement p = c.p();
            BlockPos pos = p.pos();
            BlockState now = level.getBlockState(pos);
            BlockState want = null;
            String what = "";
            if (p.part() == BuildGoal.Part.BLOCK) {
                switch (p.style()) {
                    case WALL -> {
                        if (!wallish(now)) break;
                        Cell above = byPos.get(pos.above().asLong());
                        boolean band = above == null || !(above.p().part() == BuildGoal.Part.BLOCK && above.p().style() == Blueprints.Style.WALL
                            || above.p().part() == BuildGoal.Part.WINDOW);
                        Direction.Axis run = runOf(c, byPos, back);
                        want = switch (st) {
                            case TIMBERED -> band ? log("dark_oak", false, run) : null;
                            case HILL_FORT -> !stone ? null : (iron ? Blocks.DEEPSLATE_BRICKS : Blocks.COBBLESTONE).defaultBlockState();
                            case COASTAL -> stone ? Blocks.POLISHED_DIORITE.defaultBlockState() : Blocks.BIRCH_PLANKS.defaultBlockState();
                            case DESERT_COURT -> stone ? Blocks.CUT_SANDSTONE.defaultBlockState() : null;
                            case FOREST_LODGE -> log(woodOf(village), false, run);
                            case GRAND_CIVIC -> stone ? Blocks.POLISHED_ANDESITE.defaultBlockState() : null;
                        };
                        what = st == Style.TIMBERED ? "the timber band" : "the walls";
                    }
                    case POST -> {
                        if (!wallish(now)) break;
                        want = switch (st) {
                            case TIMBERED -> log("dark_oak", false, Direction.Axis.Y);
                            case HILL_FORT -> stone ? Blocks.STONE_BRICKS.defaultBlockState() : null;
                            case COASTAL -> log("birch", true, Direction.Axis.Y);
                            case DESERT_COURT -> stone ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : null;
                            case FOREST_LODGE -> log(woodOf(village), true, Direction.Axis.Y);
                            case GRAND_CIVIC -> stone ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : null;
                        };
                        what = "the corner posts";
                    }
                    case ROOF_STAIR, ROOF_STAIR_TOP -> {
                        if (!roofish(now) || !(now.getBlock() instanceof StairBlock)) break;
                        Block to = switch (st) {
                            case HILL_FORT -> iron ? Blocks.DEEPSLATE_TILE_STAIRS : stone ? Blocks.COBBLESTONE_STAIRS : null;
                            case DESERT_COURT -> stone ? Blocks.SANDSTONE_STAIRS : null;
                            case TIMBERED -> now.is(BlockTags.WOODEN_STAIRS) ? null : Blocks.SPRUCE_STAIRS;
                            case FOREST_LODGE -> now.is(BlockTags.WOODEN_STAIRS) ? null : Blocks.DARK_OAK_STAIRS;
                            default -> null;
                        };
                        if (to != null) want = Ages.like(now, to);
                        what = "the roof";
                    }
                    case ROOF_SLAB, ROOF_SLAB_TOP -> {
                        if (!roofish(now) || !now.is(BlockTags.SLABS)) break;
                        Block to = switch (st) {
                            case HILL_FORT -> iron ? Blocks.DEEPSLATE_TILE_SLAB : stone ? Blocks.COBBLESTONE_SLAB : null;
                            case DESERT_COURT -> stone ? Blocks.SANDSTONE_SLAB : null;
                            case FOREST_LODGE -> Blocks.MOSSY_COBBLESTONE_SLAB;
                            case TIMBERED -> now.is(BlockTags.WOODEN_SLABS) ? null : Blocks.SPRUCE_SLAB;
                            default -> null;
                        };
                        if (to != null) want = Ages.like(now, to);
                        what = st == Style.FOREST_LODGE ? "the mossy ridge" : "the roof";
                    }
                    case MASONRY, BRICK -> {
                        if (!wallish(now)) break;
                        Block to = switch (st) {
                            case TIMBERED -> stone ? Blocks.BRICKS : null;
                            case HILL_FORT -> Blocks.COBBLESTONE;
                            case COASTAL -> stone ? Blocks.POLISHED_DIORITE : null;
                            case DESERT_COURT -> Blocks.SANDSTONE;
                            case FOREST_LODGE -> Blocks.MOSSY_COBBLESTONE;
                            case GRAND_CIVIC -> stone ? Blocks.STONE_BRICKS : null;
                        };
                        if (to != null) want = to.defaultBlockState();
                        what = "the chimney";
                    }
                    default -> { }
                }
            } else if (p.part() == BuildGoal.Part.WINDOW && pane(now)) {
                boolean side = c.dz() != frontDz && c.dz() != wallBack && c.h() <= 2;
                if (st == Style.COASTAL) {
                    Block tinted = block(trim(village).getName() + "_stained_glass_pane");
                    if (!now.is(tinted)) want = tinted.withPropertiesOf(now);
                    what = "the windows' glass";
                } else if (st == Style.HILL_FORT && side && stone) {
                    want = Blocks.COBBLESTONE.defaultBlockState();
                    what = "the side windows walled up small";
                }
            }
            if (want != null && !now.equals(want) && !(want.getBlock() == now.getBlock() && sameButAxis(now, want))) {
                out.add(new Change(pos, want, false, what));
            }
        }
        // What the style adds, in front of the house and on its roof: only into the air.
        if (frontDz != Integer.MAX_VALUE) {
            for (Cell c : cells) {
                BuildGoal.Placement p = c.p();
                // Shutters either side of each front window.
                if ((st == Style.TIMBERED || st == Style.FOREST_LODGE) && p.part() == BuildGoal.Part.WINDOW && c.dz() == frontDz) {
                    for (Direction side : new Direction[]{ right, right.getOpposite() }) {
                        BlockPos wall = p.pos().relative(side), at = wall.relative(front);
                        Cell wc = byPos.get(wall.asLong());
                        if (wc == null || wc.p().part() != BuildGoal.Part.BLOCK) continue;
                        if (!level.getBlockState(at).isAir()) continue;
                        BlockState shutter = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, front)
                            .setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.HALF, Half.TOP);
                        out.add(new Change(at, shutter, true, "shutters"));
                    }
                }
                // The town's colours on banners at the front corners.
                if ((st == Style.COASTAL || st == Style.GRAND_CIVIC || st == Style.HILL_FORT) && p.part() == BuildGoal.Part.BLOCK
                        && p.style() == Blueprints.Style.POST && c.dz() == frontDz && c.h() == 2) {
                    BlockPos at = p.pos().relative(front);
                    if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isAir()) {
                        Block banner = Heraldry.wallBanner(trim(village));
                        if (banner instanceof WallBannerBlock) out.add(new Change(at, banner.defaultBlockState().setValue(WallBannerBlock.FACING, front),
                            true, "banners of the town's colours"));
                    }
                }
                // Battlements along the eaves.
                if (st == Style.HILL_FORT && stone && p.part() == BuildGoal.Part.BLOCK && BuildGoal.isRoof(p.style())
                        && p.style() != Blueprints.Style.ROOF_BLOCK && c.h() == eavesH && (c.dz() == minDz || c.dz() == maxDz)
                        && Math.floorMod(c.dx(), 2) == 0) {
                    BlockPos at = p.pos().above();
                    if (level.getBlockState(at).isAir() && !byPos.containsKey(at.asLong())) {
                        out.add(new Change(at, Blocks.COBBLESTONE_WALL.defaultBlockState(), true, "the battlements"));
                    }
                }
            }
            BlockPos door = null;
            for (Cell c : cells) if (c.p().part() == BuildGoal.Part.DOOR && c.dz() == frontDz && c.h() == 0) door = c.p().pos();
            if (door != null) {
                BlockPos step = door.relative(front);
                BlockPos left = step.relative(right.getOpposite(), 2), rightOf = step.relative(right, 2);
                switch (st) {
                    case COASTAL -> {
                        if (placeable(level, rightOf)) out.add(new Change(rightOf, Blocks.BARREL.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP), true, "a barrel by the door"));
                    }
                    case DESERT_COURT -> {
                        if (placeable(level, left)) out.add(new Change(left, Blocks.DECORATED_POT.defaultBlockState()
                            .setValue(DecoratedPotBlock.HORIZONTAL_FACING, front), true, "a fired pot by the door"));
                    }
                    case FOREST_LODGE -> {
                        // The household's names carved on a board over the door.
                        BlockPos board = door.above(2).relative(front);
                        if (level.getBlockState(board).isAir() && level.getBlockState(board.relative(back)).isSolid()) {
                            out.add(new Change(board, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front),
                                true, "a carved board by the door"));
                        }
                    }
                    default -> { }
                }
            }
        }
        // Smoke from the chimney pot.
        if ((st == Style.TIMBERED || st == Style.FOREST_LODGE) && chimneyTop != null) {
            BlockPos at = chimneyTop.p().pos().above();
            if (level.getBlockState(at).isAir() && level.getBlockState(chimneyTop.p().pos()).isSolid()) {
                out.add(new Change(at, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), true, "a fire in the chimney pot"));
            }
        }
        return out;
    }

    /** Somewhere on the ground to set a thing down: air, firm ground under it, and not a way in. */
    private static boolean placeable(ServerLevel level, BlockPos at) {
        return level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
            && level.getBlockState(at.above()).isAir();
    }

    /** A pane of glass, plain or stained (the game's panes share their shape with iron bars). */
    static boolean pane(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock && !s.is(Blocks.IRON_BARS);
    }

    /** The same block already, only lying another way (a log the builders laid upright): left as it is. */
    private static boolean sameButAxis(BlockState now, BlockState want) {
        return now.hasProperty(RotatedPillarBlock.AXIS);
    }

    /** A log of this wood (stripped, or not), lying this way. */
    private static BlockState log(String wood, boolean stripped, Direction.Axis axis) {
        Block b = block((stripped ? "stripped_" : "") + wood + "_log");
        if (b == Blocks.AIR) b = stripped ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.SPRUCE_LOG;
        BlockState s = b.defaultBlockState();
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
    }

    /** The wood a lodge town's logs are of: its look's frame. */
    private static String woodOf(UUID village) {
        String w = Palettes.of(village).frame();
        return w.equals("bamboo") ? "spruce" : w;
    }

    // ------------------------------------------------------------------ paying for it

    /** Is this log, stripped or not, of a wood with logs (what a log of the wanted wood may be paid in, or any log)? */
    private static boolean isLog(ItemStack s) {
        return s.is(ItemTags.LOGS);
    }

    /**
     * A block of the dressing paid out of the stores, at what it really costs: the masons' stone (Masonry), the land's
     * own stone in itself or what it is cut from (the stonecutter's one for one), a log for a log, a pane and a share of
     * dye for a coloured one, six wool and a stick for a banner, a trapdoor (or planks), four fired bricks for a pot, a
     * barrel (or seven planks), a campfire (or three logs and a coal), a sign. Returns the block as laid (a log of the
     * wood the stores had, if not the one wanted), or null if the stores cannot pay.
     */
    @Nullable
    static BlockState pay(ServerLevel level, Villages.Village v, BlockState want) {
        Block b = want.getBlock();
        if (b instanceof RotatedPillarBlock && want.is(BlockTags.LOGS)) {
            Item exact = b.asItem();
            String id = BuiltInRegistries.ITEM.getKey(exact).getPath();
            boolean stripped = id.startsWith("stripped_");
            Item plain = stripped ? BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id.substring(9))) : exact;
            if (Crafts.take(level, v, s -> s.is(exact) || s.is(plain), 1)) return want;
            ItemStack any = Crafts.takeOne(level, v, Architecture::isLog);
            if (any.isEmpty()) return null;
            String anyId = BuiltInRegistries.ITEM.getKey(any.getItem()).getPath().replace("stripped_", "");
            Block laid = block((stripped ? "stripped_" : "") + anyId);
            if (laid == Blocks.AIR || !(laid instanceof RotatedPillarBlock)) laid = Block.byItem(any.getItem());
            BlockState s = laid.defaultBlockState();
            return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, want.getValue(RotatedPillarBlock.AXIS)) : s;
        }
        if (b == Blocks.POLISHED_DIORITE) return oneOf(level, v, want, Items.POLISHED_DIORITE, Items.DIORITE, Items.CALCITE);
        if (b == Blocks.COBBLESTONE_WALL) return oneOf(level, v, want, Items.COBBLESTONE_WALL, Items.COBBLESTONE);
        if (b == Blocks.SANDSTONE || b == Blocks.SANDSTONE_STAIRS || b == Blocks.SANDSTONE_SLAB || b == Blocks.SMOOTH_SANDSTONE) {
            return oneOf(level, v, want, b.asItem(), Items.SANDSTONE, Items.CUT_SANDSTONE);
        }
        if (b == Blocks.BIRCH_PLANKS) return oneOf(level, v, want, Items.BIRCH_PLANKS);
        if (b == Blocks.SPRUCE_STAIRS || b == Blocks.DARK_OAK_STAIRS || b == Blocks.SPRUCE_SLAB) {
            Item planks = b == Blocks.DARK_OAK_STAIRS ? Items.DARK_OAK_PLANKS : Items.SPRUCE_PLANKS;
            return oneOf(level, v, want, b.asItem(), planks);
        }
        if (b instanceof TrapDoorBlock) {
            Block made = TownLook.wooden(level, v, ItemTags.WOODEN_TRAPDOORS, "trapdoor", 6, 2);
            if (!(made instanceof TrapDoorBlock)) return null;
            return made.withPropertiesOf(want);
        }
        if (b instanceof WallBannerBlock) {
            DyeColor c = trim(v.id());
            Item banner = Heraldry.bannerItem(c);
            if (Crafts.take(level, v, s -> s.is(banner), 1)) return want;
            Item wool = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(c.getName() + "_wool"));
            if (Crafts.stock(level, v, s -> s.is(wool)) < 6) return null;
            boolean stick = Crafts.take(level, v, s -> s.is(Items.STICK), 1);
            if (!stick && !Crafts.planks(level, v, 1)) return null;
            if (!stick) Crafts.store(level, v, new ItemStack(Items.STICK, 3));
            if (!Crafts.take(level, v, s -> s.is(wool), 6)) return null;
            return want;
        }
        if (b instanceof net.minecraft.world.level.block.StainedGlassPaneBlock) {
            Item pane = b.asItem();
            if (Crafts.take(level, v, s -> s.is(pane), 1)) return want;
            if (Crafts.stock(level, v, s -> s.is(Items.GLASS_PANE)) < 1) return null;
            // A dye stains eight panes: paid for with the first of each eight.
            DyeColor c = trim(v.id());
            String key = "dye." + c.getName();
            int left = (int) Culture.num(String.valueOf(TownWays.note(v.id(), key)), 0);
            if (left <= 0) {
                Item dye = Heraldry.dyeItem(c);
                Predicate<ItemStack> makes = s -> s.is(dye) || c == DyeColor.BLUE && (s.is(Items.LAPIS_LAZULI) || s.is(Items.CORNFLOWER));
                if (!Crafts.take(level, v, makes, 1)) return null;
                left = 8;
            }
            if (!Crafts.take(level, v, s -> s.is(Items.GLASS_PANE), 1)) return null;
            TownWays.note(v.id(), key, Integer.toString(left - 1));
            return want;
        }
        if (b == Blocks.DECORATED_POT) {
            if (Crafts.take(level, v, s -> s.is(Items.DECORATED_POT), 1)) return want;
            return Crafts.take(level, v, s -> s.is(Items.BRICK), 4) ? want : null;
        }
        if (b == Blocks.BARREL) {
            if (Crafts.take(level, v, s -> s.is(Items.BARREL), 1)) return want;
            return Crafts.planks(level, v, 7) ? want : null;
        }
        if (b == Blocks.CAMPFIRE) {
            if (Crafts.take(level, v, s -> s.is(Items.CAMPFIRE), 1)) return want;
            if (Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) < 3 || Crafts.stock(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) < 1) return null;
            if (!Crafts.take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)) return null;
            if (!Crafts.take(level, v, s -> s.is(ItemTags.LOGS), 3)) {
                Crafts.store(level, v, new ItemStack(Items.COAL));
                return null;
            }
            return want;
        }
        if (b instanceof WallSignBlock) return Crafts.sign(level, v) ? want : null;
        // The masons' own (stone bricks, cobble, slate, mossy stone, brick, polished andesite, cut sandstone...).
        return Masonry.take(level, v, b) ? want : null;
    }

    /** One of these out of the stores, laid as the block wanted (the stonecutter's one for one). */
    @Nullable
    private static BlockState oneOf(ServerLevel level, Villages.Village v, BlockState want, Item... from) {
        for (Item it : from) if (Crafts.take(level, v, s -> s.is(it), 1)) return want;
        return null;
    }

    // ------------------------------------------------------------------ doing it

    /**
     * A turn of the town's dressers (TownWays, every half a minute): the first of its homes not yet dressed in its style
     * for its age, a dozen blocks of it, by a hand at the town's works. A settled town only (a day old at least, its land
     * known). Returns the blocks changed.
     */
    static int dressTurn(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Style st = of(id);
        if (st == null || Homeland.known(id) == null) return 0;
        long founded = FoundingDay.founded(id), day = level.getDayTime() / 24000L;
        if (founded < 0 || day - founded < 1) return 0;
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (!HOMES.contains(b.structure()) || !Land.areaLoaded(level, b.anchor(), 9)) continue;
            if (dressedFor(id, b, st) || waiting(level, b)) continue;
            if (HousingMarket.isPrivate(id, b.anchor()) || Beliefs.untouchable(id, b) || Grow.raisingNow(id, b.anchor())) continue;
            int n = dress(level, v, b, 12, false);
            if (n > 0) return n;
        }
        return 0;
    }

    /** Has this building been dressed in this style for the town's age? */
    static boolean dressedFor(UUID village, Ledger.Building b, Style st) {
        String d = dressed(village).get(b.anchor().asLong());
        return d != null && d.equals(st.name() + "|" + age(village));
    }

    private static Map<Long, String> dressed(UUID village) {
        return DRESSED.computeIfAbsent(village, k -> {
            Map<Long, String> m = new ConcurrentHashMap<>();
            for (String[] r : Culture.rows(k, TownWays.PREFIX + "dressed")) if (r.length >= 2) m.put(Culture.num(r[0], 0), r[1]);
            return m;
        });
    }

    private static void markDressed(UUID village, Ledger.Building b, Style st) {
        Map<Long, String> m = dressed(village);
        m.put(b.anchor().asLong(), st.name() + "|" + age(village));
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<Long, String> e : m.entrySet()) rows.add(new String[]{ Long.toString(e.getKey()), e.getValue() });
        Culture.rows(village, TownWays.PREFIX + "dressed", rows);
    }

    /**
     * Dress a building in the town's style, up to so many blocks: out of the stores (unless {@code free}: the showcase,
     * the tests), what comes off back into them. A building with nothing left that the stores could pay for is marked
     * dressed for the style and the age, and not looked at again till either changes. Returns the blocks changed.
     */
    public static int dress(ServerLevel level, Villages.Village v, Ledger.Building b, int budget, boolean free) {
        UUID id = v.id();
        Style st = of(id);
        if (st == null) return 0;
        List<Change> todo = plan(level, id, b, st);
        if (todo.isEmpty()) {
            markDressed(id, b, st);
            return 0;
        }
        if (!free && !TownJobs.atWork(level, v, "dressing", b.anchor(), "dressing a house in the " + st.words + " style")) return 0;
        int n = 0;
        boolean shortOf = false;
        Set<String> did = new HashSet<>();
        for (Change c : todo) {
            if (n >= budget) break;
            BlockState now = level.getBlockState(c.pos());
            if (c.fresh() && !now.isAir()) continue;
            BlockState laid = free ? c.want() : pay(level, v, c.want());
            if (laid == null) { shortOf = true; continue; }
            if (!c.fresh() && !free) {
                Item back = now.getBlock().asItem();
                if (back != Items.AIR) Crafts.giveBack(level, v, back, 1);
            }
            level.setBlock(c.pos(), laid, 3);
            if (laid.getBlock() instanceof WallSignBlock && level.getBlockEntity(c.pos()) instanceof SignBlockEntity sign) {
                TownLife.write(sign, household(id, b));
            }
            did.add(c.what());
            n++;
        }
        if (n > 0) level.playSound(null, b.anchor(), SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.6F, 1.0F);
        if (n < budget && !shortOf) markDressed(id, b, st);
        else if (n == 0 && shortOf) WAIT.put(b.anchor().asLong(), level.getGameTime());   // waiting on the stores: the next house first
        return n;
    }

    /** A building whose dressing waits on the stores, since when (game time): looked at again five minutes on. */
    private static final Map<Long, Long> WAIT = new ConcurrentHashMap<>();

    static boolean waiting(ServerLevel level, Ledger.Building b) {
        Long since = WAIT.get(b.anchor().asLong());
        return since != null && level.getGameTime() - since < 6000L && level.getGameTime() >= since;
    }

    /** Tests and the stage: every one of this building's changes made now, for nothing. How many blocks changed. */
    public static int dressForTests(ServerLevel level, Villages.Village v, Ledger.Building b) {
        return dress(level, v, b, 10000, true);
    }

    /** Tests: what the style would change on this building as it stands (each change's words), nothing done. */
    public static List<String> planForTests(ServerLevel level, UUID village, Ledger.Building b) {
        List<String> out = new ArrayList<>();
        Style st = of(village);
        if (st != null) for (Change c : plan(level, village, b, st)) out.add(c.what());
        return out;
    }

    /** The carved board by a lodge's door: the household's names. */
    private static String[] household(UUID village, Ledger.Building b) {
        List<String> names = new ArrayList<>();
        Homes.Home h = Homes.homes(village).get(b.anchor().asLong());
        if (h != null) {
            for (UUID u : h.members) {
                for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(u)) names.add(a.displayNameCap());
            }
        }
        String[] out = { "~ Home ~", "", "", "" };
        for (int i = 0; i < names.size() && i < 3; i++) out[i + 1] = names.get(i);
        if (names.isEmpty()) out[1] = Villages.name(village);
        return out;
    }

    // ------------------------------------------------------------------ the age's make-over, and the streets

    /**
     * May the age's make-over (Ages) change this part of one of the town's homes? Not the roof of a Timbered Lowland or
     * a Forest Lodge town: shingle and moss are its look, through every age (no slate).
     */
    public static boolean ageMayChange(@Nullable UUID village, Ledger.Building b, Blueprints.Style part) {
        Style st = of(village);
        if (st != Style.TIMBERED && st != Style.FOREST_LODGE || !HOMES.contains(b.structure())) return true;
        return !BuildGoal.isRoof(part);
    }

    /** A lamp post of the town's style: what it is made of (the post's two blocks), and what pays for it. */
    public record Post(Block block, Predicate<ItemStack> pay, int cost) {}

    /**
     * The lamp post's post, in the town's style, if the stores can pay for it (TownWork): dark oak in a timbered town,
     * rough stone in a hill fort, birch on the coast, sandstone in the desert, stone brick in a grand one. Null for the
     * usual spruce posts of two logs.
     */
    @Nullable
    public static Post lampPost(ServerLevel level, Villages.Village v) {
        Style st = of(v.id());
        if (st == null) return null;
        Post p = switch (st) {
            case TIMBERED -> new Post(Blocks.DARK_OAK_FENCE, s -> s.is(Items.DARK_OAK_LOG) || s.is(Items.DARK_OAK_FENCE), 2);
            case HILL_FORT -> new Post(Blocks.COBBLESTONE_WALL, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLESTONE_WALL), 2);
            case COASTAL -> new Post(Blocks.BIRCH_FENCE, s -> s.is(Items.BIRCH_LOG) || s.is(Items.BIRCH_FENCE), 2);
            case DESERT_COURT -> new Post(Blocks.SANDSTONE_WALL, s -> s.is(Items.SANDSTONE) || s.is(Items.SANDSTONE_WALL), 2);
            case FOREST_LODGE -> null;
            case GRAND_CIVIC -> new Post(Blocks.STONE_BRICK_WALL, s -> s.is(Items.STONE_BRICKS) || s.is(Items.STONE_BRICK_WALL), 2);
        };
        return p != null && Crafts.stock(level, v, p.pay()) >= p.cost() ? p : null;
    }

    /**
     * A bench of the town's style for a street corner (StreetFurniture), paid out of the stores now: stone brick (or
     * cobble) in a hill fort or a grand town, sandstone in a desert court. Null for the usual wooden one.
     */
    @Nullable
    public static Block benchStair(ServerLevel level, Villages.Village v) {
        Style st = of(v.id());
        if (st == Style.DESERT_COURT && Crafts.take(level, v, s -> s.is(Items.SANDSTONE) || s.is(Items.SANDSTONE_STAIRS), 1)) return Blocks.SANDSTONE_STAIRS;
        if (st == Style.HILL_FORT || st == Style.GRAND_CIVIC) {
            if (Crafts.take(level, v, s -> s.is(Items.STONE_BRICKS) || s.is(Items.STONE_BRICK_STAIRS), 1)) return Blocks.STONE_BRICK_STAIRS;
            if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLESTONE_STAIRS), 1)) return Blocks.COBBLESTONE_STAIRS;
        }
        return null;
    }

    /** A Timbered Lowland town boxes flowers under every house's windows, not only the well-off ones' (StreetFurniture). */
    public static boolean boxesEverywhere(@Nullable UUID village) {
        return of(village) == Style.TIMBERED;
    }

    // ------------------------------------------------------------------ words

    /** The town's building, for its page. */
    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        Style st = of(village);
        if (st == null) {
            out.add("No style of its own yet: chosen once its land is known.");
            return out;
        }
        long since = Culture.num(String.valueOf(TownWays.note(village, "style.since")), -1);
        out.add("It builds in the " + st.words + " style" + (since >= 0 ? " (since day " + (since + 1) + ")" : "") + ": " + what(st) + ".");
        int homes = 0, done = 0;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!HOMES.contains(b.structure())) continue;
            homes++;
            if (dressedFor(village, b, st)) done++;
        }
        if (homes > 0) out.add(done + " of its " + homes + (homes == 1 ? " home" : " homes") + " dressed in it so far; its lamp posts and benches follow suit.");
        return out;
    }

    static String what(Style st) {
        return switch (st) {
            case TIMBERED -> "a dark timber band under the eaves, timber posts, shutters, shingle roofs, a brick chimney with a fire, and window boxes on every house";
            case HILL_FORT -> "rough stone walls, stone corners, stone and later slate roofs, small side windows and battlements on the eaves";
            case COASTAL -> "white diorite walls, birch posts, windows glazed in the town's colour, its banners at the corners and a barrel by the door";
            case DESERT_COURT -> "cut sandstone walls, sandstone roofs, smooth sandstone columns and a fired pot by the door";
            case FOREST_LODGE -> "log walls, stripped posts, shutters, a mossy ridge, smoke from the chimney and a carved board by the door";
            case GRAND_CIVIC -> "polished andesite walls, chiselled stone columns and the town's banners at the corners";
        };
    }

    /** "How do you build?" */
    static String talk(VillageFolkEntity f) {
        Style st = of(f.ownerId());
        return st == null ? "However the builders can, for now." : st.boast;
    }
}
