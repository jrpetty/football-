package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * [redstone] The redstone engineer's machines, as drawings: the cane farm, the melon and pumpkin farm, the street
 * lamp, the sorter, the piston gate and the auto-smelter, each in {@code data/mc_assistant/blueprints/machines/}.
 *
 * <p>The same text the buildings are drawn in (a layer to a block of rows, the back of the machine the top row, the
 * front the bottom one, the middle row and column its centre line), but every drawing carries its own legend, since a
 * machine is made of exact blocks in exact states: "key H hopper facing=right" is a hopper whose spout points to the
 * machine's right, whichever way the machine is turned. A mark's block is a block of the game, or one of the
 * engineer's own materials: "casing" (its stone: stone bricks, or what the stores have), "casing_slab", "casing_wall",
 * "door" (the wall's own block, for the gate) and "stem" (a melon or a pumpkin stem). "." is cleared to air.
 *
 * <p>A mark is built in its stage: the frame (stone, glass, soil, water), the mechanism (hoppers, chests, pistons,
 * furnaces), then the redstone and the observers, and the crop last of all, so a machine never fires half built, and
 * within a stage from the bottom up.
 */
public final class Machines {

    private Machines() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The order a machine goes up in. */
    public enum Stage { FRAME, MECHANISM, REDSTONE, CROP }

    /** One mark of a drawing's legend: the block, its state as drawn (ways relative to the machine), its stage, and
     *  a stand-in for a block only there for its looks ("or=casing"). */
    public record Key(char ch, String block, Map<String, String> props, Stage stage, @Nullable String or) {
        public boolean air() { return block.equals("air"); }
    }

    /** One block of a drawing: across (right is +), up, and depth (toward the back is +). */
    public record Cell(int dx, int h, int dz, Key key) {}

    /** A whole drawing, read. */
    public record Drawing(String name, List<Cell> cells, Map<Character, Key> legend) {
        public int count(char ch) {
            int n = 0;
            for (Cell c : cells) if (c.key().ch() == ch) n++;
            return n;
        }

        /** The cells of one mark. */
        public List<Cell> of(char ch) {
            List<Cell> out = new ArrayList<>();
            for (Cell c : cells) if (c.key().ch() == ch) out.add(c);
            return out;
        }

        /** The half-width and half-depth it takes on the ground, and its lowest and highest layer. */
        public int[] bounds() {
            int x = 0, z = 0, lo = 0, hi = 0;
            for (Cell c : cells) {
                x = Math.max(x, Math.abs(c.dx()));
                z = Math.max(z, Math.abs(c.dz()));
                lo = Math.min(lo, c.h());
                hi = Math.max(hi, c.h());
            }
            return new int[]{ x, z, lo, hi };
        }
    }

    /** A block of a machine where it goes in the world, in the state it is built in. */
    public record Placement(BlockPos pos, BlockState state, Cell cell) {
        public Stage stage() { return cell.key().stage(); }
    }

    private static final Map<String, Drawing> DRAWINGS = new ConcurrentHashMap<>();

    /** The machine drawn under this name, read once (empty if there is no such drawing). */
    public static Drawing drawing(String name) {
        return DRAWINGS.computeIfAbsent(name, Machines::read);
    }

    private static Drawing read(String name) {
        String path = "/data/mc_assistant/blueprints/machines/" + name + ".txt";
        try (InputStream in = Machines.class.getResourceAsStream(path)) {
            if (in == null) {
                LOG.error("[MCA-REDSTONE] no drawing at {}", path);
                return new Drawing(name, List.of(), Map.of());
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(name, r.lines().toList());
        } catch (Exception e) {
            LOG.error("[MCA-REDSTONE] could not read {}: {}", path, e.toString());
            return new Drawing(name, List.of(), Map.of());
        }
    }

    /** Read a drawing's lines. A comment is a line that starts "# " (a row of stone starts "##" or "#."). */
    public static Drawing parse(String name, List<String> lines) {
        Map<Character, Key> legend = new LinkedHashMap<>();
        legend.put('.', new Key('.', "air", Map.of(), Stage.FRAME, null));
        List<Cell> cells = new ArrayList<>();
        Integer layer = null;
        List<String> rows = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.replace("\r", "");
            // A lone "#" is a blank line of the notes above the drawing, but a row of one stone in a layer (the lamp post's).
            if (line.isBlank() || line.startsWith("# ") || layer == null && line.equals("#")) continue;
            if (line.startsWith("name ")) continue;
            if (line.startsWith("key ")) {
                Key k = key(line.substring(4));
                if (k != null) legend.put(k.ch(), k);
                continue;
            }
            if (line.startsWith("layer ")) {
                if (layer != null) flush(name, layer, rows, legend, cells);
                layer = Integer.parseInt(line.substring(6).trim());
                rows = new ArrayList<>();
                continue;
            }
            rows.add(line);
        }
        if (layer != null) flush(name, layer, rows, legend, cells);
        return new Drawing(name, List.copyOf(cells), Map.copyOf(legend));
    }

    @Nullable
    private static Key key(String spec) {
        String[] parts = spec.trim().split("\\s+");
        if (parts.length < 2 || parts[0].length() != 1) return null;
        char ch = parts[0].charAt(0);
        String block = parts[1];
        Map<String, String> props = new LinkedHashMap<>();
        Stage stage = null;
        String or = null;
        for (int i = 2; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            String k = parts[i].substring(0, eq), v = parts[i].substring(eq + 1);
            if (k.equals("stage")) stage = Stage.valueOf(v.toUpperCase(Locale.ROOT));
            else if (k.equals("or")) or = v;
            else props.put(k, v);
        }
        return new Key(ch, block, Map.copyOf(props), stage != null ? stage : stageOf(block), or);
    }

    private static final Set<String> REDSTONE = Set.of("redstone_wire", "comparator", "repeater", "redstone_torch",
        "redstone_wall_torch", "observer", "daylight_detector", "lever");
    private static final Set<String> MECHANISM = Set.of("hopper", "chest", "piston", "sticky_piston", "furnace",
        "dropper", "dispenser", "redstone_lamp");
    private static final Set<String> CROP = Set.of("sugar_cane", "stem", "melon_stem", "pumpkin_stem");

    private static Stage stageOf(String block) {
        if (REDSTONE.contains(block)) return Stage.REDSTONE;
        if (MECHANISM.contains(block)) return Stage.MECHANISM;
        if (CROP.contains(block)) return Stage.CROP;
        return Stage.FRAME;
    }

    private static void flush(String name, int layer, List<String> rows, Map<Character, Key> legend, List<Cell> out) {
        int depth = rows.size(), width = 0;
        for (String r : rows) width = Math.max(width, r.length());
        for (int i = 0; i < depth; i++) {
            String row = rows.get(i);
            int dz = (depth - 1) / 2 - i;
            for (int j = 0; j < row.length(); j++) {
                char c = row.charAt(j);
                if (c == ' ') continue;
                Key k = legend.get(c);
                if (k == null) {
                    LOG.warn("[MCA-REDSTONE] {} layer {}: no such mark '{}'", name, layer, c);
                    continue;
                }
                out.add(new Cell(j - (width - 1) / 2, layer, dz, k));
            }
        }
    }

    // ------------------------------------------------------------------ in the world

    /** The machine's right, for a machine whose back is toward {@code back}: a viewer at its front faces its back. */
    public static Direction right(Direction back) {
        return back.getClockWise();
    }

    /** Where a cell of a drawing goes, for a machine at {@code origin} whose back is toward {@code back}. */
    public static BlockPos at(BlockPos origin, Direction back, int dx, int h, int dz) {
        return origin.relative(right(back), dx).relative(back, dz).above(h);
    }

    public static BlockPos at(BlockPos origin, Direction back, Cell c) {
        return at(origin, back, c.dx(), c.h(), c.dz());
    }

    /** A way named in a drawing, in the world: front, back, left, right, up, down, or a compass point as it is. */
    @Nullable
    public static Direction way(String name, Direction back) {
        return switch (name) {
            case "back" -> back;
            case "front" -> back.getOpposite();
            case "right" -> right(back);
            case "left" -> right(back).getOpposite();
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            default -> Direction.byName(name);
        };
    }

    /** The block a mark stands for, in its drawn state, turned to the machine. The engineer's own materials come
     *  from {@code material} (which may give a stand-in when the stores have none of a block that is only for looks). */
    public static BlockState state(Key k, Direction back, Function<String, BlockState> material) {
        // A block drawn with a stand-in is asked about first as "or:<block>": the builder answers with the stand-in
        // when it would rather not spend (or cannot find) the drawn block, and with nothing when the drawn block is fine.
        BlockState s = k.or() != null ? material.apply("or:" + k.block()) : null;
        if (s == null) s = base(k.block(), material);
        if (s == null && k.or() != null) s = base(k.or(), material);
        if (s == null) s = Blocks.AIR.defaultBlockState();
        for (Map.Entry<String, String> e : k.props().entrySet()) s = with(s, e.getKey(), e.getValue(), back);
        return s;
    }

    @Nullable
    private static BlockState base(String block, Function<String, BlockState> material) {
        if (block.equals("air")) return Blocks.AIR.defaultBlockState();
        BlockState own = material.apply(block);
        if (own != null) return own;
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.withDefaultNamespace(block))
            .map(Block::defaultBlockState).orElse(null);
    }

    /** A state with one property set from the drawing's text; a way is turned to the machine. */
    public static BlockState with(BlockState s, String name, String value, Direction back) {
        Property<?> p = s.getBlock().getStateDefinition().getProperty(name);
        if (p == null) return s;
        if (p instanceof DirectionProperty dp) {
            Direction d = way(value, back);
            return d != null && dp.getPossibleValues().contains(d) ? s.setValue(dp, d) : s;
        }
        return set(s, p, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState s, Property<T> p, String value) {
        return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
    }

    /**
     * The machine laid out where it goes, in the order it is built: stage by stage, each from the bottom up (and, in
     * a layer, its water after the walls that hold it).
     */
    public static List<Placement> plan(Drawing d, BlockPos origin, Direction back, Function<String, BlockState> material) {
        List<Placement> out = new ArrayList<>();
        for (Cell c : d.cells()) out.add(new Placement(at(origin, back, c), state(c.key(), back, material), c));
        out.sort(Comparator.<Placement>comparingInt(p -> p.stage().ordinal())
            .thenComparingInt(p -> p.cell().h())
            .thenComparingInt(p -> p.state().is(Blocks.WATER) ? 1 : 0)
            .thenComparingInt(p -> -p.cell().dz())
            .thenComparingInt(p -> p.cell().dx()));
        return out;
    }

    /** Put one block of a machine in place, the way the game places it (its neighbours told). Redstone dust takes
     *  the shape its neighbours give it, as dust placed by a hand does. */
    public static void place(ServerLevel level, Placement p) {
        BlockState s = p.state();
        if (s.is(Blocks.REDSTONE_WIRE)) s = Block.updateFromNeighbourShapes(s, level, p.pos());
        level.setBlock(p.pos(), s, Block.UPDATE_ALL);
        // A repeater, a comparator, a torch or a hopper set down looks at its own input only when something next to it
        // changes (a hand placing one has the game look at once). Set down by the engineer, it is told as a hand's would
        // be: the sorter's repeaters, set after the torches that feed them, never lit and never locked a thing.
        if (p.stage() == Stage.REDSTONE || p.stage() == Stage.MECHANISM) level.neighborChanged(p.pos(), s.getBlock(), p.pos());
    }

    /** Every block of a machine, at once (the stage command, and the tests of a machine alone). */
    public static void build(ServerLevel level, List<Placement> plan) {
        for (Placement p : plan) place(level, p);
    }

    /**
     * Is this block as the drawing has it? The block itself and the ways it faces; not what changes as it works (a
     * piston out or in, power, a lamp lit, a crop's age, an attached stem). An open cell is not looked at: a piston's
     * head comes and goes there, and the cane and the fruit grow into it.
     */
    public static boolean asDrawn(BlockState have, Placement p) {
        if (p.cell().key().air()) return true;
        BlockState want = p.state();
        if (want.is(Blocks.WATER)) return have.getFluidState().isSource() && have.is(Blocks.WATER);
        if (want.is(Blocks.MELON_STEM) || want.is(Blocks.PUMPKIN_STEM)) {
            return have.is(Blocks.MELON_STEM) || have.is(Blocks.PUMPKIN_STEM)
                || have.is(Blocks.ATTACHED_MELON_STEM) || have.is(Blocks.ATTACHED_PUMPKIN_STEM);
        }
        if (want.is(Blocks.FARMLAND)) return have.is(Blocks.FARMLAND);
        if (!have.is(want.getBlock())) return false;
        for (Property<?> prop : want.getProperties()) {
            if (prop instanceof DirectionProperty && !have.getValue(prop).equals(want.getValue(prop))) return false;
        }
        for (String name : List.of("type", "mode", "inverted", "face")) {
            Property<?> prop = want.getBlock().getStateDefinition().getProperty(name);
            if (prop != null && p.cell().key().props().containsKey(name) && !have.getValue(prop).equals(want.getValue(prop))) return false;
        }
        return true;
    }

    /** The blocks of a placed machine that are not as drawn (missing, broken, turned), in the order they go back. */
    public static List<Placement> faults(ServerLevel level, List<Placement> plan) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : plan) {
            if (p.cell().key().air()) continue;
            if (!level.isLoaded(p.pos())) continue;
            if (!asDrawn(level.getBlockState(p.pos()), p)) out.add(p);
        }
        return out;
    }
}
