package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.VillageBoardBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Village Board: where it stands, and what it says.
 *
 * <p>Every village has one from the day it is founded, set into the line its wall will
 * one day follow, on the inside of the square, facing the heart — so the square in front
 * of it is where the village gathers to hear the day's business (Assemblies). What it
 * says is written from the village as it is, a few times a minute: what it is building
 * and what comes after, what it is short of, the elder's orders and what the council
 * last decided; how many it is and at what trades, its stores, its homes, its purse, how
 * content it is and whether the watch is quiet; and what it is working towards — the
 * next age, the next rank, the civic its leader has the town researching (CityTree) — with
 * the latest news at the foot.
 */
public final class VillageBoards {

    private VillageBoards() {}

    /** Where each village's board stands: its bottom-left panel. */
    private static final Map<UUID, BlockPos> BOARDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Direction> FACING = new ConcurrentHashMap<>();

    public static void known(ServerLevel level, BlockPos anchor, @Nullable UUID village) {
        if (village == null) return;
        BOARDS.put(village, anchor.immutable());
        BlockState st = level.getBlockState(anchor);
        if (st.getBlock() instanceof VillageBoardBlock) FACING.put(village, st.getValue(VillageBoardBlock.FACING));
    }

    public static void gone(ServerLevel level, BlockPos anchor) {
        BOARDS.entrySet().removeIf(e -> e.getValue().equals(anchor));
    }

    /** The bottom-left panel of the village's board, or null if it has none (that we know of). */
    @Nullable
    public static BlockPos boardOf(UUID village) {
        return BOARDS.get(village);
    }

    /** Which way the village's board faces (towards the square), or null. */
    @Nullable
    public static Direction facingOf(UUID village) {
        return FACING.get(village);
    }

    /** The middle of the foot of the board, where somebody stands to read it out. */
    @Nullable
    public static BlockPos lectern(UUID village) {
        BlockPos a = BOARDS.get(village);
        Direction f = FACING.get(village);
        if (a == null || f == null) return null;
        return a.relative(VillageBoardBlock.right(f), VillageBoardBlock.WIDE / 2).relative(f, 2);
    }

    public static void resetForTests() {
        BOARDS.clear();
        FACING.clear();
        PREFER.clear();
    }

    // ------------------------------------------------------------------ putting it up

    /**
     * The founders put their board up: on the line the wall will follow, on whichever side
     * of the square has the kindest ground, in one half of that side (the avenue to the gate
     * stays clear), facing the heart. Nothing anybody built is knocked down for it — only
     * grass, flowers, saplings and leaves are cleared — so where the line is taken (a village
     * the folk moved into) it goes a little further in or out. Returns its bottom-left panel,
     * or null if there was nowhere it could stand.
     */
    @Nullable
    public static BlockPos raiseFor(ServerLevel level, Villages.Village v) {
        // A village founded where its founding board stood (entity/Founding) puts its own up on
        // the same side, where the player who chose its founders last saw it.
        Direction prefer = PREFER.remove(column(v.centre()));
        Spot spot = findSpot(level, v.centre(), prefer, 2.0, false);
        if (spot == null) {
            LOG.info("[MCA-BOARD] {}: nowhere on the square for a board", Villages.name(v.id()));
            return null;
        }
        put(level, spot);
        VillageBoardBlock.raise(level, spot.anchor(), spot.facing(), v.id());
        if (level.getBlockEntity(spot.anchor()) instanceof com.jrpetty.mcassistant.block.VillageBoardBlockEntity be) be.markRaised();
        known(level, spot.anchor(), v.id());
        LOG.info("[MCA-BOARD] {}: the board is up at {}, facing {}", Villages.name(v.id()), spot.anchor().toShortString(), spot.facing());
        return spot.anchor();
    }

    /**
     * A board for a village that is not founded yet (a Village Folk Spawner set down, entity/Founding):
     * where the founders' board would go round a heart here, on the {@code side} of it the one who set
     * the spawner down was facing, so it faces them across the heart. On rough ground where no side
     * will do as the founders would want it, anywhere on the square's edge it can stand, on longer
     * posts. Returns its bottom-left panel, or null if it could not go up at all.
     */
    @Nullable
    public static BlockPos raisePending(ServerLevel level, BlockPos heart, Direction side) {
        Spot spot = findSpot(level, heart, side, 12.0, false);
        if (spot == null) spot = findSpot(level, heart, side, 12.0, true);
        if (spot == null) return null;
        put(level, spot);
        VillageBoardBlock.raise(level, spot.anchor(), spot.facing(), null);
        LOG.info("[MCA-BOARD] a founding board is up at {}, facing {}", spot.anchor().toShortString(), spot.facing());
        return spot.anchor();
    }

    /** The side the board of the village about to be founded here is to go on (entity/Founding). */
    public static void preferSide(BlockPos heart, Direction side) {
        PREFER.put(column(heart), side);
    }

    private static final Map<Long, Direction> PREFER = new ConcurrentHashMap<>();

    private static long column(BlockPos p) {
        return BlockPos.asLong(p.getX(), 0, p.getZ());
    }

    /** Where a board will go: its bottom-left panel, which way it faces, and the height of its foot. */
    private record Spot(BlockPos anchor, Direction facing, int base) {}

    /**
     * The best place on the square's edge round this heart for a board. {@code prefer}, if any, is the
     * side it would rather be on, by so much of the score; {@code rough} lets it stand over ground
     * up to twenty blocks out of true, on long posts.
     */
    @Nullable
    private static Spot findSpot(ServerLevel level, BlockPos heart, @Nullable Direction prefer, double bonus, boolean rough) {
        int line = com.jrpetty.mcassistant.village.TownPlan.PLAZA;
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        Direction[] sides = { Direction.WEST, Direction.SOUTH, Direction.EAST, Direction.NORTH };
        int[] lines = { line, line - 2, line + 2, line - 4, line + 4 };
        for (int d : lines) {
            for (Direction side : sides) {
                Direction facing = side.getOpposite();
                Direction right = VillageBoardBlock.right(facing);
                BlockPos mid = heart.relative(side, d);
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                boolean wet = false;
                for (int c = 0; c < VillageBoardBlock.WIDE; c++) {
                    BlockPos col = mid.relative(right, -12 + c);
                    int g = groundY(level, col.getX(), col.getZ());
                    lo = Math.min(lo, g);
                    hi = Math.max(hi, g);
                    if (!level.getFluidState(new BlockPos(col.getX(), g - 1, col.getZ())).isEmpty()
                            || !level.getFluidState(new BlockPos(col.getX(), g, col.getZ())).isEmpty()) wet = true;
                }
                if ((wet && !rough) || hi - lo > (rough ? 20 : 6)) continue;
                int base = hi + 1;
                boolean blocked = false;
                int clearing = 0;
                for (int c = 0; c < VillageBoardBlock.WIDE && !blocked; c++) {
                    for (int r = -1; r < VillageBoardBlock.HIGH; r++) {
                        BlockState st = level.getBlockState(mid.relative(right, -12 + c).atY(base + r));
                        if (st.isAir()) continue;
                        if (!clearable(st)) { blocked = true; break; }
                        clearing++;
                    }
                }
                if (blocked) continue;
                double score = (hi - lo) * 4.0 + clearing * 0.5 + Math.abs(base - 1 - heart.getY()) * 2.0
                    + Math.abs(d - line) * 3.0 - (side == prefer ? bonus : 0.0) + (wet ? 40.0 : 0.0);
                if (score < bestScore) {
                    bestScore = score;
                    best = new Spot(mid.relative(right, -12).atY(base), facing, base);
                }
            }
            if (best != null) break;                       // the nearest line that has room
        }
        return best;
    }

    /** Clear what grows where the board goes, and set it on four posts. */
    private static void put(ServerLevel level, Spot spot) {
        Direction right = VillageBoardBlock.right(spot.facing());
        for (int c = 0; c < VillageBoardBlock.WIDE; c++) {
            for (int r = -1; r < VillageBoardBlock.HIGH; r++) {
                BlockPos p = VillageBoardBlock.cell(spot.anchor(), spot.facing(), c, r);
                if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
            if (c == 0 || c == 3 || c == 6 || c == VillageBoardBlock.WIDE - 1) {
                BlockPos foot = spot.anchor().relative(right, c);
                int g = groundY(level, foot.getX(), foot.getZ());
                for (int y = g; y < spot.base(); y++) {
                    level.setBlock(new BlockPos(foot.getX(), y, foot.getZ()), Blocks.DARK_OAK_LOG.defaultBlockState(), 3);
                }
            }
        }
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** What may be cleared for a board: what grows, never what was built. */
    private static boolean clearable(BlockState s) {
        // A tree's trunk too (Terraform.growth: what the world grew, never a log somebody laid): in a jungle every
        // place on the square's edge had a trunk in it, and the board, and so the founding, could go nowhere.
        return s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
            || s.getBlock() instanceof net.minecraft.world.level.block.BushBlock || Terraform.growth(s);
    }

    /** The first free block above the ground here: through trees, plants and snow to the earth. */
    private static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int floor = level.getMinBuildHeight() + 1;
        while (y > floor) {
            BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
            if (below.is(BlockTags.LOGS) || below.is(BlockTags.LEAVES) || below.canBeReplaced()) y--;
            else break;
        }
        return y;
    }

    // ------------------------------------------------------------------ what it says

    /** The board's lines for this village, as VillageBoardBlockEntity keeps them. */
    public static List<String> compose(ServerLevel level, @Nullable UUID id) {
        List<String> out = new ArrayList<>();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) {
            out.add("TH|The Village Board");
            out.add("SM|No village keeps this board. Put it up in a village and it will tell you all about it.");
            return out;
        }
        long day = level.getDayTime() / 24000L;
        int folk = Villages.headcount(id);
        Villages.Age age = Villages.ageOf(id);
        out.add("TH|" + Villages.name(id));
        String elder = Villages.elderName(id);
        out.add("SM|" + capital(age.label) + "  ·  " + capital(Villages.rank(id).label) + "  ·  " + folk
            + (folk == 1 ? " soul" : " folk") + (elder.isEmpty() ? "" : "  ·  Elder " + elder) + "  ·  Day " + (day + 1));

        // ---- what we're doing
        out.add("LH|What we're doing");
        String founding = Founding.boardLine(level.getServer(), id);
        if (founding != null) out.add("LG|" + founding);
        Map<String, List<String>> building = new LinkedHashMap<>();
        Map<AssistantEntity.StationTask, Integer> trades = new java.util.EnumMap<>(AssistantEntity.StationTask.class);
        int children = 0, idle = 0, working = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.isBaby()) { children++; continue; }
            if (a.stationTask() == AssistantEntity.StationTask.NONE) idle++;
            else trades.merge(a.stationTask(), 1, Integer::sum);
            Job j = a.peekJob();
            if (j != null && j.type() == Job.Type.BUILD && j.arg() != null) {
                String what = j.arg().split("\\|", 2)[0];
                building.computeIfAbsent(what, k -> new ArrayList<>()).add(a.displayNameCap());
            }
            if ("Working".equals(a.clientStatus())) working++;
        }
        if (building.isEmpty()) {
            String next = Villages.nextProject(id);
            out.add(next == null ? "LM|Nothing to build just now: every building we want stands."
                : "LN|Next to build: " + Villages.spoken(next) + ".");
        } else {
            for (Map.Entry<String, List<String>> b : building.entrySet()) {
                out.add("LG|Building " + Villages.spoken(b.getKey()) + " — " + String.join(", ", b.getValue()) + ".");
            }
        }
        List<String> wanted = Villages.projectsWanted(id);
        List<String> after = new ArrayList<>();
        for (String w : wanted) {
            if (building.containsKey(w) || after.contains(w)) continue;
            after.add(w);
            if (after.size() >= 4) break;
        }
        if (!after.isEmpty()) {
            List<String> words = new ArrayList<>();
            for (String w : after) words.add(Villages.spoken(w));
            out.add("LN|After that: " + String.join(", then ", words) + ".");
        }
        // The job market (JobMarket): our Wanted notices, who is on the road here, who came and went, word from other towns.
        out.addAll(JobMarket.board(level, id));
        Orders.Order order = Orders.current(id);
        out.add(order == null ? "LM|Elder's orders: none yet — the elder is watching how things go."
            : "LN|Elder's orders: " + order.title + ". " + order.words);
        String plan = Leader.board(id);
        if (plan != null) out.add("LN|" + plan);
        String decided = Council.lastDecision(id);
        if (decided != null) out.add("LM|The council: " + capital(decided) + ".");
        String chosen = Council.chosen(id);
        if (chosen != null) out.add("LM|Voted to build next of the extras: " + Villages.spoken(chosen) + ".");
        List<String> shorts = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, id)) {
            if (n.task() == Villages.Task.BUILD || n.task() == Villages.Task.NONE) continue;
            shorts.add(n.amount() > 1 ? n.amount() + " " + n.what() : n.what());
            if (shorts.size() >= 4) break;
        }
        out.add(shorts.isEmpty() ? "LG|We're short of nothing that matters." : "LW|Short of: " + String.join(", ", shorts) + ".");

        // ---- how we're doing
        out.add("RH|How we're doing");
        StringBuilder t = new StringBuilder();
        for (Map.Entry<AssistantEntity.StationTask, Integer> e : trades.entrySet()) {
            if (t.length() > 0) t.append(", ");
            t.append(e.getValue()).append(' ').append(plural(e.getKey().title.toLowerCase(Locale.ROOT), e.getValue()));
        }
        out.add("RN|Our trades: " + (t.length() == 0 ? "none yet" : t) + (idle > 0 ? "; " + idle + " still choosing" : "")
            + (children > 0 ? "; " + children + (children == 1 ? " child" : " children") : "") + ".");
        out.add("RN|At work right now: " + working + " of " + Math.max(0, folk - children) + ".");
        // Who is on duty: the watch, the town's works, the scouts and envoys out on the road.
        List<String> watch = new ArrayList<>(), works = new ArrayList<>(), away = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) watch.add(f.displayNameCap());
            String job = TownJobs.doing(f);
            if (job != null) works.add(f.displayNameCap() + " (" + job + ")");
            if (f.expedition() != null) away.add(f.displayNameCap() + " scouting " + f.expedition().heading());
            else if (f.trip() != null && f.trip().errand() != null) away.add(f.displayNameCap() + " envoy to " + Villages.name(f.trip().destination()));
            else if (f.trip() != null) away.add(f.displayNameCap() + " with a caravan");
        }
        List<String> rota = new ArrayList<>();
        if (!watch.isEmpty()) rota.add("the watch — " + String.join(", ", watch.subList(0, Math.min(4, watch.size()))));
        if (!works.isEmpty()) rota.add("the town's works — " + String.join(", ", works));
        if (!away.isEmpty()) rota.add("away — " + String.join(", ", away));
        if (!rota.isEmpty()) out.add("RM|On duty: " + String.join("; ", rota) + ".");
        int radius = Villages.storesRadius(id);
        int food = Villages.stock(level, v.centre(), Villages.Task.FOOD, radius);
        int logs = Villages.stock(level, v.centre(), Villages.Task.LOGS, radius);
        int stone = Villages.stock(level, v.centre(), Villages.Task.STONE, radius);
        int coal = Villages.stock(level, v.centre(), Villages.Task.COAL, radius);
        int iron = Villages.stock(level, v.centre(), Villages.Task.IRON, radius);
        out.add((food < folk * 2 ? "RW" : "RN") + "|In the stores: food " + food + ", timber " + logs + ", stone " + stone
            + ", coal " + coal + ", iron " + iron + "."
            + (Storehouses.stands(id) ? " (the storehouse)" : ""));
        int room = Villages.housing(id);
        String homes = Homes.brief(level, id);                  // let, owned, saving to buy, and yesterday's rent
        out.add((room < folk ? "RW" : "RN") + "|Homes: room for " + room + ", beds made up for " + Villages.bedsMadeUp(level, id)
            + (homes.isEmpty() ? "" : "; " + homes) + ".");
        int toMarket = Market.daysToMarket(id, day);
        if (Homeland.known(id) != null) out.add("RN|Land: " + Homeland.line(id) + ".");
        out.add("RN|Treasury: " + com.jrpetty.mcassistant.village.Ledger.coins(id) + " coins. Market "
            + (toMarket == 0 ? "today!" : toMarket == 1 ? "tomorrow." : "in " + toMarket + " days."));
        {
            int made = Economy.yesterday(id);
            Integer trend = Economy.trend(id);
            int worth = Economy.worth(id);
            if (made > 0 || worth >= 0) {
                out.add((trend != null && trend <= -10 ? "RW" : trend != null && trend >= 10 ? "RG" : "RN") + "|Output: " + made
                    + " coins' worth a day" + (trend == null ? "" : trend >= 3 ? ", up " + trend + "%" : trend <= -3 ? ", down " + (-trend) + "%" : ", steady")
                    + (worth >= 0 ? ". Worth " + worth + "." : "."));
            }
            String best = Wealth.bestPaid(id, 3);
            if (!best.isEmpty()) out.add("RN|Best paid: " + best + " a day.");
        }
        String open = Cafe.openLine(level, id);
        if (open != null) out.add("RN|Open: " + open + ".");
        int content = Contentment.score(id);
        out.add((content >= 60 ? "RG" : content >= 35 ? "RN" : "RW") + "|Contentment: " + Contentment.line(level, id) + ".");
        String alarm = Raids.why(id);
        out.add(alarm != null ? "RB|THE BELL IS RINGING: " + alarm + "!" : "RM|The watch: all quiet.");
        String bounty = PlayerServices.boardLine(level, id);     // [players] the night's bounty, when the watch is busy
        if (bounty != null) out.add("RW|" + bounty);
        out.addAll(TownCalendar.board(level, id));          // today's bells, Founding Day, the week's birthdays
        String gathering = Assemblies.now(id);
        if (gathering != null) out.add("RG|Now: " + gathering + " — come along!");
        Gatherings.Kind tonight = Gatherings.tonight(id, day);
        if (tonight != null) out.add("RG|Tonight: " + Gatherings.describe(tonight, id) + " — everybody welcome.");
        for (String p : Assemblies.planned(id)) out.add("RG|This evening: " + p + ".");
        if (day % 7 == 3) out.add("RM|The council sits this evening.");
        out.addAll(Elections.board(id, day));

        // ---- what we're working towards
        out.add("FH|What we're working towards");
        List<String> toAge = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, id)) {
            toAge.add(n.amount() > 1 && n.task() != Villages.Task.BUILD ? n.amount() + " " + n.what() : n.what());
            if (toAge.size() >= 5) break;
        }
        if (age != Villages.Age.NETHER) {
            out.add("FN|To come into " + nextAge(age).label + ": " + (toAge.isEmpty() ? "nearly there!" : String.join(", ", toAge)) + ".");
        } else {
            out.add("FN|Every age reached. Now: great works, colonies and renown.");
        }
        out.add("FN|To be " + Villages.nextRankNote(id) + ".");
        // The city's research (CityTree): what the leader has the town studying, how far on, why, and what is done.
        // In the foot, which the board always has room for, a line or two the width of the board.
        out.addAll(CityTree.board(id));
        out.add("FM|Growing: " + Villages.growthNote(level, id) + ".");
        out.add("FN|The purse: " + Budget.line(level, id) + ".");
        String neighbours = Envoys.boardLine(id);
        if (neighbours != null) out.add("FN|Neighbours: " + neighbours + ". Elder " + (Villages.elderName(id).isEmpty() ? "none yet" : "is " + Envoys.temper(id).words) + ".");
        String abroad = Envoys.latest(id);
        if (abroad != null) out.add("FM|Abroad: " + abroad + ".");
        String scouts = Scouts.boardLine(id);
        if (scouts != null) out.add("FN|" + scouts);
        String museum = Museum.boardLine(id, day);              // what is new in the museum (Museum)
        if (museum != null) out.add("FN|" + museum);
        List<Villages.News> news = Villages.news(id);
        if (!news.isEmpty()) out.add("FM|Latest: " + news.get(news.size() - 1).text() + ".");
        return out;
    }

    /** The board as a page of the village journal: its title, and the rest in sections. */
    public static String[] page(List<String> lines) {
        StringBuilder page = new StringBuilder();
        String title = "Village Board";
        for (String l : lines) {
            int bar = l.indexOf('|');
            if (bar < 2) continue;
            char where = l.charAt(0), how = l.charAt(1);
            String words = l.substring(bar + 1);
            if (where == 'T') { title = words; continue; }
            if (how == 'H') page.append(page.length() == 0 ? "" : "\n\n").append(words.toUpperCase(Locale.ROOT)).append('\n');
            else page.append(words).append('\n');
        }
        return new String[]{ title, page.toString().trim() };
    }

    private static Villages.Age nextAge(Villages.Age a) {
        Villages.Age[] all = Villages.Age.values();
        return all[Math.min(all.length - 1, a.ordinal() + 1)];
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String plural(String word, int n) {
        if (n == 1) return word;
        if (word.endsWith("smith")) return word + "s";
        if (word.endsWith("s")) return word;
        return word + "s";
    }
}
