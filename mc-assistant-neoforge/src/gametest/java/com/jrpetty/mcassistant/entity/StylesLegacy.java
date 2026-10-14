package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [styles] The medieval look as it was written in code before it became data (themes/medieval.json): Palettes,
 * Architecture, Ages and Homeland as they stood, frozen, word for word. Only StylesGameTests st01 uses it, to hold the
 * medieval theme to exactly the blocks the code chose. Not to be changed: it is the record of what the look was.
 */
public final class StylesLegacy {

    private StylesLegacy() {}

    static final List<String> WOODS = List.of("oak", "spruce", "birch", "dark_oak", "jungle", "acacia", "mangrove", "cherry");

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(id));
    }

    // ------------------------------------------------------------------ Palettes

    public static Palettes.Look forLand(Homeland.Land land) {
        return switch (land) {
            case TAIGA, SNOW -> new Palettes.Look("spruce", "dark_oak", "spruce", "dark_oak",
                List.of("cobblestone", "stone", "andesite", "cobbled_deepslate", "diorite", "granite", "tuff"),
                List.of("stone_bricks", "polished_andesite", "mossy_stone_bricks", "deepslate_bricks", "polished_diorite", "polished_granite"));
            case FOREST -> new Palettes.Look("birch", "dark_oak", "oak", "dark_oak",
                List.of("cobblestone", "stone", "andesite", "mossy_cobblestone", "diorite", "granite", "cobbled_deepslate"),
                List.of("stone_bricks", "mossy_stone_bricks", "polished_andesite", "polished_diorite", "polished_granite", "deepslate_bricks"));
            case MOUNTAIN -> new Palettes.Look("spruce", "dark_oak", "spruce", "spruce",
                List.of("cobbled_deepslate", "cobblestone", "andesite", "tuff", "stone", "granite", "diorite"),
                List.of("deepslate_bricks", "stone_bricks", "polished_deepslate", "polished_andesite", "tuff_bricks", "polished_granite"));
            case DESERT -> new Palettes.Look("birch", "jungle", "acacia", "jungle",
                List.of("sandstone", "smooth_sandstone", "cobblestone", "stone", "andesite", "granite"),
                List.of("cut_sandstone", "smooth_sandstone", "chiseled_sandstone", "stone_bricks", "polished_granite", "polished_andesite"));
            case SAVANNA -> new Palettes.Look("acacia", "dark_oak", "acacia", "dark_oak",
                List.of("cobblestone", "stone", "granite", "andesite", "terracotta", "diorite"),
                List.of("stone_bricks", "polished_granite", "polished_andesite", "bricks", "polished_diorite", "mud_bricks"));
            case JUNGLE -> new Palettes.Look("jungle", "dark_oak", "jungle", "jungle",
                List.of("mossy_cobblestone", "cobblestone", "stone", "andesite", "granite", "diorite"),
                List.of("mossy_stone_bricks", "stone_bricks", "polished_andesite", "polished_granite", "polished_diorite", "mud_bricks"));
            case SWAMP -> new Palettes.Look("mangrove", "dark_oak", "spruce", "mangrove",
                List.of("mud_bricks", "cobblestone", "stone", "andesite", "mossy_cobblestone", "diorite"),
                List.of("mud_bricks", "stone_bricks", "mossy_stone_bricks", "polished_andesite", "polished_diorite", "packed_mud"));
            case BADLANDS -> new Palettes.Look("dark_oak", "spruce", "acacia", "spruce",
                List.of("terracotta", "red_sandstone", "cobblestone", "stone", "granite", "andesite"),
                List.of("cut_red_sandstone", "smooth_red_sandstone", "bricks", "stone_bricks", "polished_granite", "polished_andesite"));
            case MEADOW -> new Palettes.Look("cherry", "spruce", "oak", "spruce",
                List.of("cobblestone", "stone", "andesite", "diorite", "granite", "cobbled_deepslate"),
                List.of("stone_bricks", "polished_diorite", "polished_andesite", "mossy_stone_bricks", "polished_granite", "deepslate_bricks"));
            case COAST, RIVER -> new Palettes.Look("oak", "spruce", "spruce", "spruce",
                List.of("cobblestone", "stone", "andesite", "diorite", "granite", "cobbled_deepslate"),
                List.of("stone_bricks", "polished_andesite", "polished_diorite", "mossy_stone_bricks", "polished_granite", "deepslate_bricks"));
            default -> new Palettes.Look("oak", "dark_oak", "spruce", "spruce",
                List.of("cobblestone", "stone", "andesite", "diorite", "granite", "cobbled_deepslate", "tuff"),
                List.of("stone_bricks", "polished_andesite", "mossy_stone_bricks", "polished_diorite", "polished_granite", "deepslate_bricks"));
        };
    }

    private static List<String> woods(String first, boolean dark) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(first);
        if (dark) { out.add("dark_oak"); out.add("spruce"); out.add("mangrove"); }
        out.addAll(WOODS);
        return new ArrayList<>(out);
    }

    public static List<Item> ranked(Palettes.Look l, Blueprints.Style style) {
        List<String> ids = new ArrayList<>();
        switch (style) {
            case WALL -> { for (String w : woods(l.walls(), false)) ids.add(planks(w)); ids.add("bamboo_planks"); }
            case FLOOR -> { for (String w : woods(l.floor(), false)) ids.add(planks(w)); ids.add("bamboo_planks"); }
            case ROOF_BLOCK -> { for (String w : woods(l.roof(), true)) ids.add(planks(w)); }
            case ROOF_STAIR, ROOF_STAIR_TOP -> { for (String w : woods(l.roof(), true)) ids.add(w + "_stairs"); }
            case ROOF_SLAB, ROOF_SLAB_TOP -> { for (String w : woods(l.roof(), true)) ids.add(w + "_slab"); }
            case POST, BEAM_ACROSS, BEAM_ALONG -> { for (String w : woods(l.frame(), true)) ids.add(w.equals("bamboo") ? "bamboo_block" : w + "_log"); }
            case FOUNDATION, WALL_LOW -> ids.addAll(l.footing());
            case MASONRY -> ids.addAll(l.dressed());
            default -> { }
        }
        List<Item> out = new ArrayList<>();
        for (String id : ids) {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id));
            if (it != Items.AIR && !out.contains(it)) out.add(it);
        }
        return out;
    }

    private static String planks(String wood) {
        return wood + "_planks";
    }

    // ------------------------------------------------------------------ Architecture: the style's woods

    public static Palettes.Look look(@Nullable Architecture.Style st, Palettes.Look base) {
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

    // ------------------------------------------------------------------ Ages: the ages' looks

    @Nullable
    public static BlockState ageLook(Villages.Age age, String drawing, Blueprints.Style style, BlockState now, BlockPos pos,
                           @Nullable Block landStone) {
        // A building with a storey put on is still the same building.
        String structure = drawing.endsWith(Blueprints.TALL) ? drawing.substring(0, drawing.length() - Blueprints.TALL.length()) : drawing;
        boolean home = structure.equals("house") || structure.equals("house2");
        boolean stone = age.ordinal() >= Villages.Age.STONE.ordinal();
        boolean iron = age.ordinal() >= Villages.Age.IRON.ordinal();
        boolean diamond = age.ordinal() >= Villages.Age.DIAMOND.ordinal();
        boolean great = Ages.GREAT.contains(structure);
        BlockState thatch = Thatch.reroof(age, style, now);           // [workitems] a thatched roof re-roofed in tiles, then slate
        if (thatch != null) return thatch;
        switch (style) {
            case WALL -> {
                // A manor is brick from the Iron Age it is built in.
                if (structure.equals("manor") && iron && now.is(BlockTags.PLANKS)) return Blocks.BRICKS.defaultBlockState();
                // The houses are Grow's: stone, then brick. Timber, or the rough cobble a storey went up
                // in for want of dressed stone (Masonry.buildIn), is dressed.
                if (home || !stone || !(now.is(BlockTags.PLANKS) || now.is(Blocks.COBBLESTONE))) return null;
                return (landStone != null ? landStone : Blocks.STONE_BRICKS).defaultBlockState();
            }
            case FOUNDATION, WALL_LOW -> {
                // [nether] The Nether Age's great buildings stand on quartz brick, of the runners' quartz (affordable: it
                // waits till the stores have it).
                if (age == Villages.Age.NETHER && great && (now.is(Blocks.STONE_BRICKS) || now.is(Blocks.MOSSY_STONE_BRICKS)
                        || now.is(Blocks.COBBLESTONE) || now.is(Blocks.MOSSY_COBBLESTONE))) {
                    return Blocks.QUARTZ_BRICKS.defaultBlockState();
                }
                // Moss in the old footings: only where the stores have the vines for it (affordable).
                if (diamond && Math.floorMod(pos.hashCode(), 3) == 0) {
                    if (now.is(Blocks.STONE_BRICKS)) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                    if (now.is(Blocks.COBBLESTONE)) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
                }
                if (iron && now.is(Blocks.COBBLESTONE)) return Blocks.STONE_BRICKS.defaultBlockState();
                return null;
            }
            case ROOF_STAIR, ROOF_STAIR_TOP -> {
                if (diamond && great && (now.is(BlockTags.WOODEN_STAIRS) || now.is(Blocks.DEEPSLATE_TILE_STAIRS)
                        || now.is(Blocks.STONE_BRICK_STAIRS))) {
                    return Ages.like(now, Blocks.CUT_COPPER_STAIRS);
                }
                if (iron && now.is(BlockTags.WOODEN_STAIRS)) return Ages.like(now, Blocks.DEEPSLATE_TILE_STAIRS);
                return null;
            }
            case ROOF_SLAB, ROOF_SLAB_TOP -> {
                if (diamond && great && (now.is(BlockTags.WOODEN_SLABS) || now.is(Blocks.DEEPSLATE_TILE_SLAB)
                        || now.is(Blocks.STONE_BRICK_SLAB))) {
                    return Ages.like(now, Blocks.CUT_COPPER_SLAB);
                }
                if (iron && now.is(BlockTags.WOODEN_SLABS)) return Ages.like(now, Blocks.DEEPSLATE_TILE_SLAB);
                return null;
            }
            case ROOF_BLOCK -> {
                if (diamond && great && (now.is(BlockTags.PLANKS) || now.is(Blocks.DEEPSLATE_TILES)
                        || now.is(Blocks.STONE_BRICKS))) {
                    return Blocks.CUT_COPPER.defaultBlockState();
                }
                if (iron && now.is(BlockTags.PLANKS)) return Blocks.DEEPSLATE_TILES.defaultBlockState();
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ Homeland: the land's stone

    @Nullable
    public static Homeland.Stone landStone(Homeland.Land l) {
        return switch (l) {
            case DESERT -> new Homeland.Stone(Blocks.CUT_SANDSTONE, s -> s.is(Items.SANDSTONE) || s.is(Items.CUT_SANDSTONE), 1);
            case BADLANDS -> new Homeland.Stone(Blocks.TERRACOTTA, s -> s.is(Items.TERRACOTTA) || s.is(Items.ORANGE_TERRACOTTA)
                || s.is(Items.RED_TERRACOTTA) || s.is(Items.BROWN_TERRACOTTA) || s.is(Items.YELLOW_TERRACOTTA), 1);
            case MOUNTAIN -> new Homeland.Stone(Blocks.POLISHED_ANDESITE, s -> s.is(Items.ANDESITE) || s.is(Items.POLISHED_ANDESITE), 1);
            // Mossy stone bricks only of the masons' mossy bricks (stone bricks and the woodcutters' vines,
            // Masonry): moss is a nicety, and without it the walls go up in plain stone like anybody's.
            case JUNGLE, SWAMP -> new Homeland.Stone(Blocks.MOSSY_STONE_BRICKS, s -> s.is(Items.MOSSY_STONE_BRICKS), 1);
            case SNOW -> new Homeland.Stone(Blocks.STONE_BRICKS, s -> s.is(Items.STONE_BRICKS), 1);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ Architecture: the dressing

    public static List<Architecture.Change> plan(ServerLevel level, UUID village, Ledger.Building b, Architecture.Style st, int age) {
        String drawing = Ages.drawing(village, b);
        List<Architecture.Change> out = new ArrayList<>();
        if (!Blueprints.has(drawing)) return out;
        List<Architecture.Cell> cells = Architecture.cells(b, drawing);
        Map<Long, Architecture.Cell> byPos = new HashMap<>();
        for (Architecture.Cell c : cells) if (c.p().part() != BuildGoal.Part.CLEAR) byPos.put(c.p().pos().asLong(), c);
        Direction back = b.facing(), front = back.getOpposite(), right = back.getClockWise();
        boolean stone = age >= Villages.Age.STONE.ordinal(), iron = age >= Villages.Age.IRON.ordinal();
        int frontDz = Integer.MAX_VALUE, eavesH = Integer.MAX_VALUE, minDz = Integer.MAX_VALUE, maxDz = Integer.MIN_VALUE;
        int wallBack = Integer.MIN_VALUE;
        Architecture.Cell chimneyTop = null;
        for (Architecture.Cell c : cells) {
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
        for (Architecture.Cell c : cells) {
            BuildGoal.Placement p = c.p();
            BlockPos pos = p.pos();
            BlockState now = level.getBlockState(pos);
            BlockState want = null;
            String what = "";
            if (p.part() == BuildGoal.Part.BLOCK) {
                switch (p.style()) {
                    case WALL -> {
                        if (!Architecture.wallish(now)) break;
                        Architecture.Cell above = byPos.get(pos.above().asLong());
                        boolean band = above == null || !(above.p().part() == BuildGoal.Part.BLOCK && above.p().style() == Blueprints.Style.WALL
                            || above.p().part() == BuildGoal.Part.WINDOW);
                        Direction.Axis run = Architecture.runOf(c, byPos, back);
                        want = switch (st) {
                            case TIMBERED -> band ? log("dark_oak", false, run) : null;
                            case HILL_FORT -> !stone ? null : (iron ? Blocks.DEEPSLATE_BRICKS : Blocks.COBBLESTONE).defaultBlockState();
                            case COASTAL -> stone ? Blocks.POLISHED_DIORITE.defaultBlockState() : Blocks.BIRCH_PLANKS.defaultBlockState();
                            case DESERT_COURT -> stone ? Blocks.CUT_SANDSTONE.defaultBlockState() : null;
                            case FOREST_LODGE -> log(woodOf(village), false, run);
                            case GRAND_CIVIC -> stone ? Blocks.POLISHED_ANDESITE.defaultBlockState() : null;
                        };
                        what = st == Architecture.Style.TIMBERED ? "the timber band" : "the walls";
                    }
                    case POST -> {
                        if (!Architecture.wallish(now)) break;
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
                        if (!Architecture.roofish(now) || !(now.getBlock() instanceof StairBlock)) break;
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
                        if (!Architecture.roofish(now) || !now.is(BlockTags.SLABS)) break;
                        Block to = switch (st) {
                            case HILL_FORT -> iron ? Blocks.DEEPSLATE_TILE_SLAB : stone ? Blocks.COBBLESTONE_SLAB : null;
                            case DESERT_COURT -> stone ? Blocks.SANDSTONE_SLAB : null;
                            case FOREST_LODGE -> Blocks.MOSSY_COBBLESTONE_SLAB;
                            case TIMBERED -> now.is(BlockTags.WOODEN_SLABS) ? null : Blocks.SPRUCE_SLAB;
                            default -> null;
                        };
                        if (to != null) want = Ages.like(now, to);
                        what = st == Architecture.Style.FOREST_LODGE ? "the mossy ridge" : "the roof";
                    }
                    case MASONRY, BRICK -> {
                        if (!Architecture.wallish(now)) break;
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
            } else if (p.part() == BuildGoal.Part.WINDOW && Architecture.pane(now)) {
                boolean side = c.dz() != frontDz && c.dz() != wallBack && c.h() <= 2;
                if (st == Architecture.Style.COASTAL) {
                    Block tinted = block(Architecture.trim(village).getName() + "_stained_glass_pane");
                    if (!now.is(tinted)) want = tinted.withPropertiesOf(now);
                    what = "the windows' glass";
                } else if (st == Architecture.Style.HILL_FORT && side && stone) {
                    want = Blocks.COBBLESTONE.defaultBlockState();
                    what = "the side windows walled up small";
                }
            }
            if (want != null && !now.equals(want) && !(want.getBlock() == now.getBlock() && sameButAxis(now, want))) {
                out.add(new Architecture.Change(pos, want, false, what));
            }
        }
        // What the style adds, in front of the house and on its roof: only into the air.
        if (frontDz != Integer.MAX_VALUE) {
            for (Architecture.Cell c : cells) {
                BuildGoal.Placement p = c.p();
                // Shutters either side of each front window.
                if ((st == Architecture.Style.TIMBERED || st == Architecture.Style.FOREST_LODGE) && p.part() == BuildGoal.Part.WINDOW && c.dz() == frontDz) {
                    for (Direction side : new Direction[]{ right, right.getOpposite() }) {
                        BlockPos wall = p.pos().relative(side), at = wall.relative(front);
                        Architecture.Cell wc = byPos.get(wall.asLong());
                        if (wc == null || wc.p().part() != BuildGoal.Part.BLOCK) continue;
                        if (!level.getBlockState(at).isAir()) continue;
                        BlockState shutter = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, front)
                            .setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.HALF, Half.TOP);
                        out.add(new Architecture.Change(at, shutter, true, "shutters"));
                    }
                }
                // The town's colours on banners at the front corners.
                if ((st == Architecture.Style.COASTAL || st == Architecture.Style.GRAND_CIVIC || st == Architecture.Style.HILL_FORT) && p.part() == BuildGoal.Part.BLOCK
                        && p.style() == Blueprints.Style.POST && c.dz() == frontDz && c.h() == 2) {
                    BlockPos at = p.pos().relative(front);
                    if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isAir()) {
                        Block banner = Heraldry.wallBanner(Architecture.trim(village));
                        if (banner instanceof WallBannerBlock) out.add(new Architecture.Change(at, banner.defaultBlockState().setValue(WallBannerBlock.FACING, front),
                            true, "banners of the town's colours"));
                    }
                }
                // Battlements along the eaves.
                if (st == Architecture.Style.HILL_FORT && stone && p.part() == BuildGoal.Part.BLOCK && BuildGoal.isRoof(p.style())
                        && p.style() != Blueprints.Style.ROOF_BLOCK && c.h() == eavesH && (c.dz() == minDz || c.dz() == maxDz)
                        && Math.floorMod(c.dx(), 2) == 0) {
                    BlockPos at = p.pos().above();
                    if (level.getBlockState(at).isAir() && !byPos.containsKey(at.asLong())) {
                        out.add(new Architecture.Change(at, Blocks.COBBLESTONE_WALL.defaultBlockState(), true, "the battlements"));
                    }
                }
            }
            BlockPos door = null;
            for (Architecture.Cell c : cells) if (c.p().part() == BuildGoal.Part.DOOR && c.dz() == frontDz && c.h() == 0) door = c.p().pos();
            if (door != null) {
                BlockPos step = door.relative(front);
                BlockPos left = step.relative(right.getOpposite(), 2), rightOf = step.relative(right, 2);
                switch (st) {
                    case COASTAL -> {
                        if (placeable(level, rightOf)) out.add(new Architecture.Change(rightOf, Blocks.BARREL.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP), true, "a barrel by the door"));
                    }
                    case DESERT_COURT -> {
                        if (placeable(level, left)) out.add(new Architecture.Change(left, Blocks.DECORATED_POT.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, front), true,
                            "a fired pot by the door"));
                    }
                    case FOREST_LODGE -> {
                        // The household's names carved on a board over the door.
                        BlockPos board = door.above(2).relative(front);
                        if (level.getBlockState(board).isAir() && level.getBlockState(board.relative(back)).isSolid()) {
                            out.add(new Architecture.Change(board, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front),
                                true, "a carved board by the door"));
                        }
                    }
                    default -> { }
                }
            }
        }
        // Smoke from the chimney pot.
        if ((st == Architecture.Style.TIMBERED || st == Architecture.Style.FOREST_LODGE) && chimneyTop != null) {
            BlockPos at = chimneyTop.p().pos().above();
            if (level.getBlockState(at).isAir() && level.getBlockState(chimneyTop.p().pos()).isSolid()) {
                out.add(new Architecture.Change(at, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), true, "a fire in the chimney pot"));
            }
        }
        return out;
    }

    /** Somewhere on the ground to set a thing down: air, firm ground under it, and not a way in. */
    private static boolean placeable(ServerLevel level, BlockPos at) {
        return level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
            && level.getBlockState(at.above()).isAir();
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

    // ------------------------------------------------------------------ Architecture: the streets (as lampPost and benchStair chose)

    /** The lamp post's block and what paid for it, as Architecture.lampPost had them; null for the usual spruce. */
    @Nullable
    public static Architecture.Post post(Architecture.Style st) {
        return switch (st) {
            case TIMBERED -> new Architecture.Post(Blocks.DARK_OAK_FENCE, s -> s.is(Items.DARK_OAK_LOG) || s.is(Items.DARK_OAK_FENCE), 2);
            case HILL_FORT -> new Architecture.Post(Blocks.COBBLESTONE_WALL, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLESTONE_WALL), 2);
            case COASTAL -> new Architecture.Post(Blocks.BIRCH_FENCE, s -> s.is(Items.BIRCH_LOG) || s.is(Items.BIRCH_FENCE), 2);
            case DESERT_COURT -> new Architecture.Post(Blocks.SANDSTONE_WALL, s -> s.is(Items.SANDSTONE) || s.is(Items.SANDSTONE_WALL), 2);
            case FOREST_LODGE -> null;
            case GRAND_CIVIC -> new Architecture.Post(Blocks.STONE_BRICK_WALL, s -> s.is(Items.STONE_BRICKS) || s.is(Items.STONE_BRICK_WALL), 2);
        };
    }

    /** The benches in the order benchStair tried them, and what paid for each. */
    public static List<Architecture.Post> benches(@Nullable Architecture.Style st) {
        List<Architecture.Post> out = new ArrayList<>();
        if (st == Architecture.Style.DESERT_COURT) out.add(new Architecture.Post(Blocks.SANDSTONE_STAIRS, s -> s.is(Items.SANDSTONE) || s.is(Items.SANDSTONE_STAIRS), 1));
        if (st == Architecture.Style.HILL_FORT || st == Architecture.Style.GRAND_CIVIC) {
            out.add(new Architecture.Post(Blocks.STONE_BRICK_STAIRS, s -> s.is(Items.STONE_BRICKS) || s.is(Items.STONE_BRICK_STAIRS), 1));
            out.add(new Architecture.Post(Blocks.COBBLESTONE_STAIRS, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLESTONE_STAIRS), 1));
        }
        return out;
    }

    /** A style whose houses keep their shingle roofs through the ages (ageMayChange). */
    public static boolean keepsRoof(@Nullable Architecture.Style st) {
        return st == Architecture.Style.TIMBERED || st == Architecture.Style.FOREST_LODGE;
    }

    /** A style that boxes flowers under every house's windows. */
    public static boolean boxes(@Nullable Architecture.Style st) {
        return st == Architecture.Style.TIMBERED;
    }

    /** The theme's palettes against the code's own (Ages.CIVIC, Grow.STONE, Grow.BRICK): what differs, or "". */
    public static String palettes(com.jrpetty.mcassistant.village.ThemeData t) {
        StringBuilder out = new StringBuilder();
        if (!Themes.palette(t, "civic", Grow.STONE).equals(Ages.CIVIC)) out.append("civic ");
        if (!Themes.palette(t, "house_stone", Ages.CIVIC).equals(Grow.STONE)) out.append("house_stone ");
        if (!Themes.palette(t, "house_brick", Ages.CIVIC).equals(Grow.BRICK)) out.append("house_brick ");
        return out.toString();
    }

    /** The lamp post by a door and its light, as Ages.lamps laid them. */
    public static Block streetPost() {
        return Blocks.SPRUCE_FENCE;
    }

    public static String streetNow(@Nullable UUID village) {
        return Themes.streetPost(village) + " " + Themes.streetLight(village);
    }

    /** The new Architecture's lamp post and benches for a town (the test asks; Architecture keeps them to itself). */
    @Nullable
    public static Architecture.Post postNow(UUID village) {
        return Architecture.postFor(village);
    }

    public static List<Architecture.Post> benchesNow(UUID village) {
        return Architecture.benchesFor(village);
    }

    /** Dress a building with the old plan, for nothing, as dressAs did (the stage's dressing). How many changed. */
    public static int dressAs(ServerLevel level, UUID village, Ledger.Building b, Architecture.Style st, Villages.Age age) {
        int n = 0;
        for (Architecture.Change c : plan(level, village, b, st, age.ordinal())) {
            if (c.fresh() && !level.getBlockState(c.pos()).isAir()) continue;
            level.setBlock(c.pos(), c.want(), 3);
            if (c.want().getBlock() instanceof WallSignBlock && level.getBlockEntity(c.pos()) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                TownLife.write(sign, new String[]{ "~ Home ~", st.words, "", "" });
            }
            n++;
        }
        return n;
    }

    /** The new Architecture's words for each change (planForTests asks the town's own style). */
    public static List<String> planWords(ServerLevel level, UUID village, Ledger.Building b, Architecture.Style st, Villages.Age age, boolean legacy) {
        List<String> out = new ArrayList<>();
        for (Architecture.Change c : legacy ? plan(level, village, b, st, age.ordinal()) : Architecture.plan(level, village, b, st, age.ordinal())) {
            out.add(c.pos().toShortString() + "=" + c.want() + "/" + c.fresh() + "/" + c.what());
        }
        return out;
    }
}
