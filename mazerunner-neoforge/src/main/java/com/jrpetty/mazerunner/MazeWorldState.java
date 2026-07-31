package com.jrpetty.mazerunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.jrpetty.mazerunner.config.RunScoring;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world persistent state: the once-rolled day→layout schedule, the day
 * counter and custom clock, the maze's current physical layout, door state,
 * the escape timer, and chest reroll bookkeeping.
 */
public class MazeWorldState extends SavedData {

    public static final String ID = "mazerunner";
    public static final int LAYOUT_COUNT = 7;

    private int[] schedule = new int[0]; // empty = not rolled yet
    private long dayNumber = 1;          // 1-based in-game day counter
    private long virtualSixths = 0;      // custom clock; dayTime = virtualSixths / 6
    private int physicalLayout = 0;      // layout index currently built into the world
    private boolean doorsOpen = false;
    private int chestCycle = 0;
    private final Map<Long, Integer> chestRolled = new HashMap<>(); // BlockPos.asLong → cycle

    // ---- per-player runs -------------------------------------------------
    /** Runs in progress: player → wall-clock millis when they left the Glade. */
    private final Map<UUID, Long> runStart = new HashMap<>();
    /** Deaths accrued during the run in progress. */
    private final Map<UUID, Integer> runDeaths = new HashMap<>();
    /** Best finished run per player — the persistent leaderboard. */
    private final Map<UUID, RunScoring.Entry> best = new HashMap<>();

    /** Outcome of a finished run. */
    public record RunResult(long finalMillis, long elapsedMillis, int deaths,
                            boolean personalBest, boolean worldBest) {}

    // ---- server-wide race ------------------------------------------------
    /** A race is one synchronised sprint: everyone starts together, first out wins. */
    private boolean raceRunning = false;
    private long raceStartMs = 0;
    private long raceBestMs = -1;
    private String raceBestHolder = "";

    public static MazeWorldState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(MazeWorldState::new, MazeWorldState::load, null), ID);
    }

    public static MazeWorldState load(CompoundTag tag, HolderLookup.Provider provider) {
        MazeWorldState state = new MazeWorldState();
        state.schedule = tag.getIntArray("schedule");
        state.dayNumber = Math.max(1, tag.getLong("dayNumber"));
        state.virtualSixths = tag.getLong("virtualSixths");
        state.physicalLayout = tag.getInt("physicalLayout");
        state.doorsOpen = tag.getBoolean("doorsOpen");
        state.chestCycle = tag.getInt("chestCycle");
        long[] keys = tag.getLongArray("chestKeys");
        int[] cycles = tag.getIntArray("chestCycles");
        for (int i = 0; i < keys.length && i < cycles.length; i++) {
            state.chestRolled.put(keys[i], cycles[i]);
        }

        ListTag runs = tag.getList("runs", Tag.TAG_COMPOUND);
        for (int i = 0; i < runs.size(); i++) {
            CompoundTag t = runs.getCompound(i);
            UUID id = t.getUUID("id");
            state.runStart.put(id, t.getLong("startMs"));
            state.runDeaths.put(id, t.getInt("deaths"));
        }
        state.raceRunning = tag.getBoolean("raceRunning");
        state.raceStartMs = tag.getLong("raceStartMs");
        state.raceBestMs = tag.contains("raceBestMs") ? tag.getLong("raceBestMs") : -1;
        state.raceBestHolder = tag.getString("raceBestHolder");

        ListTag board = tag.getList("leaderboard", Tag.TAG_COMPOUND);
        for (int i = 0; i < board.size(); i++) {
            CompoundTag t = board.getCompound(i);
            UUID id = t.getUUID("id");
            state.best.put(id, new RunScoring.Entry(id.toString(), t.getString("name"),
                t.getLong("finalMs"), t.getInt("deaths")));
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putIntArray("schedule", schedule.clone());
        tag.putLong("dayNumber", dayNumber);
        tag.putLong("virtualSixths", virtualSixths);
        tag.putInt("physicalLayout", physicalLayout);
        tag.putBoolean("doorsOpen", doorsOpen);
        tag.putInt("chestCycle", chestCycle);
        long[] keys = new long[chestRolled.size()];
        int[] cycles = new int[chestRolled.size()];
        int i = 0;
        for (Map.Entry<Long, Integer> e : chestRolled.entrySet()) {
            keys[i] = e.getKey();
            cycles[i] = e.getValue();
            i++;
        }
        tag.putLongArray("chestKeys", keys);
        tag.putIntArray("chestCycles", cycles);

        ListTag runs = new ListTag();
        for (Map.Entry<UUID, Long> e : runStart.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", e.getKey());
            t.putLong("startMs", e.getValue());
            t.putInt("deaths", runDeaths.getOrDefault(e.getKey(), 0));
            runs.add(t);
        }
        tag.put("runs", runs);

        tag.putBoolean("raceRunning", raceRunning);
        tag.putLong("raceStartMs", raceStartMs);
        tag.putLong("raceBestMs", raceBestMs);
        tag.putString("raceBestHolder", raceBestHolder);

        ListTag board = new ListTag();
        for (Map.Entry<UUID, RunScoring.Entry> e : best.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", e.getKey());
            t.putString("name", e.getValue().name());
            t.putLong("finalMs", e.getValue().finalMillis());
            t.putInt("deaths", e.getValue().deaths());
            board.add(t);
        }
        tag.put("leaderboard", board);
        return tag;
    }

    // ------------------------------------------------------------- runs

    public boolean isRunning(UUID player) {
        return runStart.containsKey(player);
    }

    /** Begins a run; ignored if one is already in progress. */
    public void startRun(UUID player, long nowMs) {
        if (runStart.containsKey(player)) return;
        runStart.put(player, nowMs);
        runDeaths.put(player, 0);
        setDirty();
    }

    /** Abandons a run without recording it. */
    public void abandonRun(UUID player) {
        if (runStart.remove(player) != null) {
            runDeaths.remove(player);
            setDirty();
        }
    }

    /** Elapsed clock time so far, excluding death penalties; -1 if not running. */
    public long runElapsed(UUID player, long nowMs) {
        Long start = runStart.get(player);
        return start == null ? -1 : Math.max(0, nowMs - start);
    }

    public int runDeaths(UUID player) {
        return runDeaths.getOrDefault(player, 0);
    }

    /** Records a death against the run in progress; returns the new death count. */
    public int addDeath(UUID player) {
        if (!runStart.containsKey(player)) return 0;
        int deaths = runDeaths.merge(player, 1, Integer::sum);
        setDirty();
        return deaths;
    }

    /**
     * Completes a run: scores it, updates the leaderboard, and clears the
     * in-progress state. Returns null if the player had no run going.
     */
    public RunResult finishRun(UUID player, String name, long nowMs, long penaltyPerDeathMs) {
        Long start = runStart.remove(player);
        if (start == null) return null;
        Integer recorded = runDeaths.remove(player);
        int deaths = recorded == null ? 0 : recorded;
        long elapsed = Math.max(0, nowMs - start);
        long finalMs = RunScoring.finalMillis(elapsed, deaths, penaltyPerDeathMs);

        long previousWorldBest = best.values().stream()
            .mapToLong(RunScoring.Entry::finalMillis).min().orElse(-1);
        RunScoring.Entry previous = best.get(player);
        boolean personalBest = previous == null || RunScoring.isBetter(finalMs, previous.finalMillis());
        if (personalBest) {
            best.put(player, new RunScoring.Entry(player.toString(), name, finalMs, deaths));
        }
        boolean worldBest = RunScoring.isBetter(finalMs, previousWorldBest);
        setDirty();
        return new RunResult(finalMs, elapsed, deaths, personalBest, worldBest);
    }

    // ------------------------------------------------------------- race

    public boolean raceRunning() {
        return raceRunning;
    }

    public void startRace(long nowMs) {
        this.raceRunning = true;
        this.raceStartMs = nowMs;
        setDirty();
    }

    /** Ends the race without recording anything. */
    public void cancelRace() {
        this.raceRunning = false;
        setDirty();
    }

    /** Elapsed race time, or -1 if no race is running. */
    public long raceElapsed(long nowMs) {
        return raceRunning ? Math.max(0, nowMs - raceStartMs) : -1;
    }

    /**
     * Ends the race with a winner. Returns the winning time, or -1 if no race
     * was running; check {@link #raceBestHolder()} afterwards for the record.
     */
    public long finishRace(String winner, long nowMs) {
        if (!raceRunning) return -1;
        long elapsed = Math.max(0, nowMs - raceStartMs);
        raceRunning = false;
        if (RunScoring.isBetter(elapsed, raceBestMs)) {
            raceBestMs = elapsed;
            raceBestHolder = winner;
        }
        setDirty();
        return elapsed;
    }

    /** True if that time would beat the standing race record. */
    public boolean isRaceRecord(long millis) {
        return RunScoring.isBetter(millis, raceBestMs);
    }

    public long raceBestMillis() {
        return raceBestMs;
    }

    public String raceBestHolder() {
        return raceBestHolder;
    }

    /** Snapshot of the persistent leaderboard, best first. */
    public List<RunScoring.Entry> leaderboard(int limit) {
        return RunScoring.ranked(best.values(), limit);
    }

    /** Best final time recorded in this world, or -1. */
    public long worldBestMillis() {
        return best.values().stream().mapToLong(RunScoring.Entry::finalMillis).min().orElse(-1);
    }

    // ------------------------------------------------------------- schedule

    /**
     * Rolls the per-world day→layout permutation once, seeded from the world
     * seed. Every world gets the same 7 mazes in a different, fixed order.
     */
    public boolean ensureSchedule(long worldSeed) {
        if (schedule.length == LAYOUT_COUNT) return false;
        int[] order = new int[LAYOUT_COUNT];
        for (int i = 0; i < LAYOUT_COUNT; i++) order[i] = i;
        Random rng = new Random(worldSeed ^ 0x6D617A65L); // salted ("maze")
        for (int i = LAYOUT_COUNT - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        schedule = order;
        physicalLayout = order[0];
        setDirty();
        return true;
    }

    /** Layout index scheduled for a given 1-based day number. */
    public int layoutIndexForDay(long day) {
        return schedule[(int) Math.floorMod(day - 1, LAYOUT_COUNT)];
    }

    public int[] schedule() {
        return schedule.clone();
    }

    // ------------------------------------------------------------- accessors

    public long dayNumber() { return dayNumber; }

    public void setDayNumber(long day) { this.dayNumber = day; setDirty(); }

    public long virtualSixths() { return virtualSixths; }

    public void setVirtualSixths(long v) { this.virtualSixths = v; setDirty(); }

    public int physicalLayout() { return physicalLayout; }

    public void setPhysicalLayout(int layout) { this.physicalLayout = layout; setDirty(); }

    public boolean doorsOpen() { return doorsOpen; }

    public void setDoorsOpen(boolean open) { this.doorsOpen = open; setDirty(); }

    public int chestCycle() { return chestCycle; }

    public void setChestCycle(int cycle) { this.chestCycle = cycle; setDirty(); }

    public int chestRolledCycle(long posKey) {
        return chestRolled.getOrDefault(posKey, -1);
    }

    public void markChestRolled(long posKey, int cycle) {
        chestRolled.put(posKey, cycle);
        setDirty();
    }
}
