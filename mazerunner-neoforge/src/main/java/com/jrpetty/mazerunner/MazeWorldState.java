package com.jrpetty.mazerunner;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
    private boolean timerRunning = false;
    private long timerStartMs = 0;
    private long lastRunMs = -1;
    private long bestRunMs = -1;
    private int chestCycle = 0;
    private final Map<Long, Integer> chestRolled = new HashMap<>(); // BlockPos.asLong → cycle

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
        state.timerRunning = tag.getBoolean("timerRunning");
        state.timerStartMs = tag.getLong("timerStartMs");
        state.lastRunMs = tag.contains("lastRunMs") ? tag.getLong("lastRunMs") : -1;
        state.bestRunMs = tag.contains("bestRunMs") ? tag.getLong("bestRunMs") : -1;
        state.chestCycle = tag.getInt("chestCycle");
        long[] keys = tag.getLongArray("chestKeys");
        int[] cycles = tag.getIntArray("chestCycles");
        for (int i = 0; i < keys.length && i < cycles.length; i++) {
            state.chestRolled.put(keys[i], cycles[i]);
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
        tag.putBoolean("timerRunning", timerRunning);
        tag.putLong("timerStartMs", timerStartMs);
        tag.putLong("lastRunMs", lastRunMs);
        tag.putLong("bestRunMs", bestRunMs);
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
        return tag;
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

    public boolean timerRunning() { return timerRunning; }

    public long timerStartMs() { return timerStartMs; }

    public long lastRunMs() { return lastRunMs; }

    public long bestRunMs() { return bestRunMs; }

    /** Records a finished run; returns true if it's a new world record. */
    public boolean recordRun(long elapsedMs) {
        boolean record = bestRunMs < 0 || elapsedMs < bestRunMs;
        if (record) bestRunMs = elapsedMs;
        setDirty();
        return record;
    }

    public void startTimer(long nowMs) {
        this.timerRunning = true;
        this.timerStartMs = nowMs;
        setDirty();
    }

    /** Stops the timer and returns the elapsed milliseconds. */
    public long stopTimer(long nowMs) {
        long elapsed = timerRunning ? nowMs - timerStartMs : -1;
        this.timerRunning = false;
        if (elapsed >= 0) this.lastRunMs = elapsed;
        setDirty();
        return elapsed;
    }

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
