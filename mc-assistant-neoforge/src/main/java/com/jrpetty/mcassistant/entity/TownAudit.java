package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [integrity] A look over a whole town, for the tests (and the operator's command): is every building whole and true
 * to its drawing, is anything in a doorway, across a way in or on a road, is anything left half built?
 *
 * <p>{@code TownAudit.check(level, village)} runs every check registered and hands back a {@link Report}: named
 * measures (numbers) and findings (a place and what is wrong there, under the check that found it). The checks
 * here are the buildings' ({@value #FIDELITY}, {@value #FOREIGN}, {@value #ROADS}, {@value #HALF_BUILT}); other
 * parts of the town add theirs with {@link #register} (the doors reachable, the debris, the looks), each under
 * its own name, so an acceptance test asks the one report for what it cares about.
 */
public final class TownAudit {

    private TownAudit() {}

    /** The checks' names. */
    public static final String FIDELITY = "fidelity", FOREIGN = "foreign", ROADS = "roads", HALF_BUILT = "half_built";

    /** One thing found wrong: the check that found it, where, and what (with what put it there, where it is known). */
    public record Finding(String check, BlockPos pos, String what) {
        @Override
        public String toString() {
            return check + " @" + pos.toShortString() + ": " + what;
        }
    }

    /** One building as the audit found it. */
    public record BuildingReport(Ledger.Building building, String design, int cells, int serving, List<Restore.Fix> fixes,
                                 String status) {
        public double fidelity() {
            return cells == 0 ? 1.0 : serving / (double) cells;
        }
    }

    /** What the audit found in a town. */
    public static final class Report {
        public final UUID village;
        public final long tick;
        private final Map<String, Double> measures = new LinkedHashMap<>();
        private final List<Finding> findings = new ArrayList<>();
        public final List<BuildingReport> buildings = new ArrayList<>();

        Report(UUID village, long tick) {
            this.village = village;
            this.tick = tick;
        }

        public void measure(String name, double value) {
            measures.put(name, value);
        }

        /** A measure, or NaN if no check made it. */
        public double measure(String name) {
            return measures.getOrDefault(name, Double.NaN);
        }

        public Map<String, Double> measures() {
            return new LinkedHashMap<>(measures);
        }

        public void find(String check, BlockPos pos, String what) {
            findings.add(new Finding(check, pos.immutable(), what));
        }

        public List<Finding> findings() {
            return new ArrayList<>(findings);
        }

        public List<Finding> findings(String check) {
            List<Finding> out = new ArrayList<>();
            for (Finding f : findings) if (f.check().equals(check)) out.add(f);
            return out;
        }

        public int count(String check) {
            int n = 0;
            for (Finding f : findings) if (f.check().equals(check)) n++;
            return n;
        }

        /** One line: every measure, and how many findings under each check. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Double> e : measures.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                double d = e.getValue();
                sb.append(e.getKey()).append('=').append(d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.3f", d));
            }
            Map<String, Integer> per = new LinkedHashMap<>();
            for (Finding f : findings) per.merge(f.check(), 1, Integer::sum);
            sb.append("; findings ").append(per);
            return sb.toString();
        }

        /** Up to so many findings, a line each. */
        public List<String> lines(int most) {
            List<String> out = new ArrayList<>();
            for (Finding f : findings) {
                if (out.size() >= most) break;
                out.add(f.toString());
            }
            return out;
        }
    }

    /** A check: its name, and what it adds to the report. */
    public interface Check {
        String name();

        void run(ServerLevel level, Villages.Village v, Report r);
    }

    private static final List<Check> CHECKS = new CopyOnWriteArrayList<>();

    /** Add a check (a check of the same name is replaced). */
    public static void register(Check c) {
        CHECKS.removeIf(x -> x.name().equals(c.name()));
        CHECKS.add(c);
    }

    /** A check from a name and a function. */
    public static void register(String name, TriConsumer run) {
        register(new Check() {
            @Override public String name() { return name; }
            @Override public void run(ServerLevel level, Villages.Village v, Report r) { run.accept(level, v, r); }
        });
    }

    /** The shape of a check's body. */
    @FunctionalInterface
    public interface TriConsumer {
        void accept(ServerLevel level, Villages.Village v, Report r);
    }

    static {
        register(new Fidelity());
        register(new Roads());
        register(new HalfBuilt());
        WayAudit.register();                 // [paths] the doors reached, the lots apart, the square clear, nobody stuck
    }

    /** Run every check on the town. */
    public static Report check(ServerLevel level, Villages.Village v) {
        Report r = new Report(v.id(), level.getGameTime());
        for (Check c : CHECKS) {
            try {
                c.run(level, v, r);
            } catch (RuntimeException ex) {
                r.find(c.name(), v.centre(), "the check failed: " + ex);
            }
        }
        return r;
    }

    /** Only the checks named. */
    public static Report check(ServerLevel level, Villages.Village v, String... names) {
        Report r = new Report(v.id(), level.getGameTime());
        java.util.Set<String> want = java.util.Set.of(names);
        for (Check c : CHECKS) if (want.contains(c.name())) c.run(level, v, r);
        return r;
    }

    // ------------------------------------------------------------------ the buildings

    /** Every building against its drawing: fidelity, the broken and missing blocks, and what is in its doorways. */
    static final class Fidelity implements Check {
        @Override public String name() { return FIDELITY; }

        @Override
        public void run(ServerLevel level, Villages.Village v, Report r) {
            int cellsAll = 0, servingAll = 0, broken = 0, missing = 0, shape = 0, foreign = 0, whole = 0, counted = 0;
            double worst = 1.0;
            long day = level.getDayTime() / 24000L;
            for (Ledger.Building b : Ledger.buildings(v.id())) {
                if (b.structure().equals("fortify")) continue;                  // the wall follows the ground: no drawing to hold it to
                if (!Land.areaLoaded(level, b.anchor(), 8)) continue;
                String design = TownBlocks.designOf(v.id(), b);
                List<BuildGoal.Placement> plan = BuildGoal.plan(design, b.anchor(), b.facing(), 13);
                int cells = 0;
                for (BuildGoal.Placement p : plan) {
                    if (p.part() != BuildGoal.Part.CLEAR && p.part() != BuildGoal.Part.STOREHOUSE) cells++;
                }
                if (cells == 0) continue;
                List<Restore.Fix> fixes = Restore.diff(level, b, design);
                int bad = 0;
                for (Restore.Fix f : fixes) {
                    String cause = causeOf(level, f.pos(), f.found());
                    String part = f.want() == null ? "nothing" : f.want().part() + (f.want().part() == BuildGoal.Part.BLOCK ? "/" + f.want().style() : "");
                    switch (f.kind()) {
                        case MISSING -> { missing++; bad++; }
                        case WRONG -> { broken++; bad++; }
                        case SHAPE -> { shape++; bad++; }
                        case FOREIGN -> foreign++;
                    }
                    String what = design + " at " + b.anchor().toShortString() + ": " + f.kind() + " want " + part + " found "
                        + TownAudit.name(f.found()) + " [" + cause + "]";
                    r.find(f.kind() == Restore.Kind.FOREIGN ? FOREIGN : FIDELITY, f.pos(), what);
                }
                int serving = cells - bad;
                cellsAll += cells;
                servingAll += serving;
                counted++;
                double fid = serving / (double) cells;
                worst = Math.min(worst, fid);
                if (bad == 0) whole++;
                r.buildings.add(new BuildingReport(b, design, cells, serving, fixes, Restore.status(v.id(), b.anchor(), day)));
            }
            r.measure("buildings", counted);
            r.measure("buildings_whole", whole);
            r.measure("fidelity", cellsAll == 0 ? 1.0 : servingAll / (double) cellsAll);
            r.measure("fidelity_worst", worst);
            r.measure("blocks_missing", missing);
            r.measure("blocks_wrong", broken);
            r.measure("blocks_shape", shape);
            r.measure("blocks_in_doorways", foreign);
            int queued = 0;
            for (Restore.Job j : Restore.queue(v.id())) if (j.reason == Restore.Reason.REPAIR) queued++;
            r.measure("repair_queue", queued);
        }
    }

    /** What is standing on the town's streets (a lamp at the edge aside), at or above the ground. */
    static final class Roads implements Check {
        @Override public String name() { return ROADS; }

        @Override
        public void run(ServerLevel level, Villages.Village v, Report r) {
            int reach = Math.min(Villages.townReach(v.id()), TownPlan.reach());
            int n = 0;
            BlockPos c = v.centre();
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (!TownPlan.isStreet(dx, dz)) continue;
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (Math.abs(top.getY() - c.getY()) > 12) continue;
                    // The ground's own top, under whatever stands on it.
                    BlockPos g = top.below();
                    int steps = 0;
                    while (steps < 6 && !ground(level.getBlockState(g))) { g = g.below(); steps++; }
                    if (!ground(level.getBlockState(g))) continue;
                    BlockPos on = g.above();
                    BlockState st = level.getBlockState(on);
                    if (Restore.clearEnough(st)) continue;
                    if (TownBlocks.buildingAt(level, v, on) != null) continue;   // a building's own (a porch on the street is [paths]')
                    boolean edge = edge(dx, dz);
                    if (edge && lampish(st)) continue;                            // a lamp post or a sign at the kerb
                    n++;
                    r.find(ROADS, on, TownAudit.name(st) + (edge ? " at the road's edge" : " in the road") + " [" + causeOf(level, on, st) + "]");
                }
            }
            r.measure("blocks_on_roads", n);
        }

        private static boolean edge(int dx, int dz) {
            for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                if (!TownPlan.isStreet(dx + d[0], dz + d[1]) && !TownPlan.isSquare(dx + d[0], dz + d[1])) return true;
            }
            return false;
        }
    }

    private static boolean ground(BlockState st) {
        return Restore.natural(st) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.STONE_BRICKS) || st.is(Blocks.DIRT_PATH)
            || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.SMOOTH_STONE) || st.is(Blocks.POLISHED_ANDESITE)
            || st.is(BlockTags.STONE_BRICKS);
    }

    private static boolean lampish(BlockState st) {
        return st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock || st.getBlock() instanceof net.minecraft.world.level.block.WallBlock
            || st.getBlock() instanceof net.minecraft.world.level.block.LanternBlock || st.is(BlockTags.SIGNS)
            || st.is(Blocks.TORCH) || st.is(BlockTags.LOGS) || st.is(Blocks.REDSTONE_LAMP) || st.is(Blocks.LIGHTNING_ROD)
            || st.getBlock() instanceof net.minecraft.world.level.block.ChainBlock;
    }

    /** Each site going up: {blocks laid when last counted, the tick it last changed}. */
    private static final Map<String, long[]> GOING = new java.util.concurrent.ConcurrentHashMap<>();

    /** Buildings left half built: a site given up with blocks on it, or a building of the register far short of
     *  its drawing. */
    static final class HalfBuilt implements Check {
        @Override public String name() { return HALF_BUILT; }

        @Override
        public void run(ServerLevel level, Villages.Village v, Report r) {
            int n = 0;
            for (Restore.Scaffold s : Restore.scaffolds(v.id())) {
                n++;
                r.find(HALF_BUILT, s.building().anchor(), "the " + s.building().structure() + " given up with " + s.placed()
                    + " blocks laid (since tick " + s.since() + ")");
            }
            for (BuildingReport b : r.buildings) {
                if (b.fidelity() >= 0.6) continue;
                n++;
                r.find(HALF_BUILT, b.building().anchor(), "the " + b.design() + " stands at " + Math.round(b.fidelity() * 100)
                    + "% of its drawing");
            }
            for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(v.id()).entrySet()) {
                r.measure("going_up/" + e.getKey(), 1);
                if (e.getKey().equals("fortify") || !BuildGoal.STRUCTURES.contains(e.getKey())) continue;
                // A building going up that has not gained a block in two days has stalled: half built, and left so.
                Ledger.Building b = new Ledger.Building(e.getKey(), e.getValue().anchor(), e.getValue().facing());
                int laid = Restore.laid(level, v, b).size();
                String key = v.id() + "/" + e.getKey() + "/" + b.anchor().asLong();
                long[] was = GOING.get(key);
                if (was == null || was[0] != laid) {
                    GOING.put(key, new long[]{ laid, level.getGameTime() });
                } else if (laid > 0 && level.getGameTime() - was[1] > 48000L) {
                    n++;
                    r.find(HALF_BUILT, b.anchor(), "the " + b.structure() + " going up has stood at " + laid + " blocks since tick " + was[1]);
                }
            }
            r.measure("half_built", n);
        }
    }

    // ------------------------------------------------------------------ the command

    /** /village integrity: the nearest town's audit in words; "views": where to look at its worst buildings from
     *  ("VIEW name x y z at-x at-y at-z"); "survey": every building looked over now and listed. Operators only. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
        return net.minecraft.commands.Commands.literal("integrity").requires(src -> src.hasPermission(2))
            .executes(ctx -> tell(ctx, false))
            .then(net.minecraft.commands.Commands.literal("views").executes(ctx -> tell(ctx, true)))
            .then(net.minecraft.commands.Commands.literal("survey").executes(ctx -> {
                Villages.Village v = near(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                int n = Restore.surveyAll(level, v);
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("SURVEY " + n + " buildings looked over; "
                    + Restore.queue(v.id()).size() + " on the repair list"), false);
                return n;
            }));
    }

    @Nullable
    private static Villages.Village near(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), here, Villages.VILLAGE_RANGE * 4);
        if (v == null) ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("No village near."));
        return v;
    }

    private static int tell(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx, boolean views) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        Report r = check(level, v);
        StringBuilder sb = new StringBuilder("INTEGRITY " + Villages.name(v.id()) + ": " + r.summary());
        if (views) {
            List<BuildingReport> worst = new ArrayList<>(r.buildings);
            worst.sort(java.util.Comparator.comparingDouble(BuildingReport::fidelity));
            int k = 0;
            for (BuildingReport b : worst) {
                if (k >= 4) break;
                BlockPos a = b.building().anchor();
                net.minecraft.core.Direction front = b.building().facing().getOpposite();
                BlockPos eye = a.relative(front, 12).relative(front.getClockWise(), 5).above(6);
                sb.append("\nVIEW ").append(b.design()).append('-').append(k).append(' ').append(eye.getX()).append(' ').append(eye.getY())
                    .append(' ').append(eye.getZ()).append(' ').append(a.getX()).append(' ').append(a.getY() + 2).append(' ').append(a.getZ())
                    .append(" (").append(Math.round(b.fidelity() * 1000) / 10.0).append("% ").append(b.status()).append(')');
                k++;
            }
        } else {
            for (String line : r.lines(12)) sb.append("\n").append(line);
        }
        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
        return 1;
    }

    // ------------------------------------------------------------------ what put it there

    /** What put this block here, as far as can be told: whoever set it through the gate last, else a guess from the
     *  block itself. */
    public static String causeOf(ServerLevel level, BlockPos pos, BlockState st) {
        TownBlocks.Why by = TownBlocks.setBy(pos);
        if (by != null) return "set by " + by;
        if (st.isAir() || st.is(Blocks.LIGHT)) return level.getBlockState(pos.below()).is(BlockTags.FIRE) || st.is(BlockTags.FIRE) ? "burnt" : "never laid, or taken down";
        if (st.is(BlockTags.FIRE)) return "fire";
        if (st.getBlock() instanceof net.minecraft.world.level.block.LanternBlock) return "a lamp";
        if (st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock) return "a fence (a lamp post, a garden)";
        if (st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) return "a garden gate";
        if (st.is(BlockTags.SIGNS) || st.is(BlockTags.WALL_SIGNS)) return "a sign";
        if (st.is(BlockTags.FLOWER_POTS) || st.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) return "a window box";
        if (st.getBlock() instanceof com.jrpetty.mcassistant.block.WindowBoxBlock) return "a window box";
        if (st.is(Blocks.FARMLAND) || st.is(BlockTags.CROPS)) return "a field";
        if (st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT)) return "earth (ground work, or never cleared)";
        if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) return "a tree";
        if (st.is(BlockTags.BANNERS)) return "a banner";
        if (st.is(BlockTags.CANDLES)) return "a candle";
        if (st.getFluidState().isSource()) return "water";
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        if (path.contains("rope")) return "a rope";
        if (path.contains("pit_prop")) return "a pit prop";
        return "unknown";
    }

    static String name(@Nullable BlockState st) {
        if (st == null) return "?";
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }
}
