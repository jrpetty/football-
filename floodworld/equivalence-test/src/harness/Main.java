package harness;

import java.util.ArrayList;
import java.util.List;

import com.jrp.floodworld.Config;
import com.jrp.floodworld.flow.FloodWorldFluids;
import com.jrp.floodworld.sim.FloodEngine;
import com.jrp.floodworld.sim.FloodSavedData;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class Main {

    public static void main(String[] args) {
        FloodWorldFluids.init("floodworld");
        if (!FloodWorldFluids.isModifyingWater()) throw new IllegalStateException("fake API not wired");

        scenario("A-defaults", 6, 4, 8192, 3, 1, 30, 20);
        scenario("B-no-threshold", 0, 0, 8192, 1, 3, 30, 20);
        scenario("C-cell-cap", 3, 6, 40, 4, 2, 5, 40);
        scenario("D-wide-scan", 10, 12, 8192, 6, 1, 60, 25);
    }

    static void scenario(String name, int threshold, int downhill, int maxCells,
                         int maxHeight, int landRate, int evap, int decay) {
        Config.SATURATION_THRESHOLD.set(threshold);
        Config.DOWNHILL_SEARCH_RADIUS.set(downhill);
        Config.MAX_ACTIVE_CELLS.set(maxCells);
        Config.MAX_FLOOD_HEIGHT.set(maxHeight);
        Config.BUILDUP_LAND.set(landRate);
        Config.EVAP_TICKS.set(evap);
        Config.SATURATION_DECAY_TICKS.set(decay);

        World.reset();
        C.reset();
        Rng rng = new Rng(0x5DEECE66DL ^ name.hashCode());

        ServerLevel overworld = new ServerLevel(name + ":overworld", true);
        ServerLevel nether = new ServerLevel(name + ":nether", false);
        overworld.random = rng;
        nether.random = rng;

        int[][] seats = {{0, 0}, {-18, 16}, {100, 100}, {-120, -120}};
        List<ServerPlayer> ps = new ArrayList<>();
        for (int[] s : seats) {
            ServerPlayer p = new ServerPlayer();
            p.pos = new BlockPos(s[0], 70, s[1]);
            overworld.players.add(p);
            ps.add(p);
        }

        MinecraftServer server = new MinecraftServer();
        server.levels = List.of(overworld, nether);
        ServerTickEvent.Post ev = new ServerTickEvent.Post();
        ev.server = server;

        System.out.println("### scenario " + name);
        StringBuilder dirtySig = new StringBuilder();
        int[] last = {0, 0};
        for (int t = 1; t <= 1600; t++) {
            World.raining = t <= 800;
            for (int i = 0; i < ps.size(); i++) {
                int[] s = seats[i];
                ps.get(i).pos = new BlockPos(s[0] + (t / 13 + i * 3) % 9 - 4, 70, s[1] + (t / 17 + i * 5) % 9 - 4);
            }
            FloodEngine.onServerTick(ev);
            // Per-tick boolean: did this tick mark the save data dirty at all?
            int i = 0;
            for (ServerLevel lv : List.of(overworld, nether)) {
                SavedData sd = lv.getDataStorage().cache.get("floodworld_flood");
                int now = sd == null ? 0 : sd.dirtyCount;
                dirtySig.append(now > last[i] ? '1' : '0');
                last[i] = now;
                i++;
            }
        }
        System.out.println("dirty-per-tick signature: " + dirtySig);

        dump(name, overworld);
        dump(name, nether);
        System.out.println("cells=" + FloodEngine.totalFloodCells(server)
                + " satcols=" + FloodEngine.totalSaturationColumns(server));
        System.out.println("clearAll removed=" + FloodEngine.clearAll(server));
        dump(name + "-after-clear", overworld);

        System.out.println("--- ops for " + name + " ---");
        System.out.println(C.report());
        System.out.print(World.LOG);
        World.LOG.setLength(0);
    }

    static void dump(String tag, ServerLevel level) {
        SavedData sd = level.getDataStorage().cache.get("floodworld_flood");
        if (sd == null) { System.out.println("[" + tag + "/" + level.dimension() + "] no data"); return; }
        FloodSavedData d = (FloodSavedData) sd;
        System.out.println("[" + tag + "/" + level.dimension() + "] flood=" + d.flood().size() + " sat=" + d.saturation().size() + " base=" + d.baseY().size());
        printPos("F", d.flood());
        printCol("T", d.saturation());
        printCol("B", d.baseY());
    }

    static void printPos(String p, Long2IntOpenHashMap m) {
        List<String> out = new ArrayList<>();
        for (Long2IntMap.Entry e : m.long2IntEntrySet()) {
            long k = e.getLongKey();
            out.add(p + " " + BlockPos.getX(k) + "," + BlockPos.getY(k) + "," + BlockPos.getZ(k) + "=" + e.getIntValue());
        }
        out.sort(null);
        out.forEach(System.out::println);
    }

    static void printCol(String p, Long2IntOpenHashMap m) {
        List<String> out = new ArrayList<>();
        for (Long2IntMap.Entry e : m.long2IntEntrySet()) {
            long k = e.getLongKey();
            out.add(p + " " + BlockPos.getX(k) + "," + BlockPos.getZ(k) + "=" + e.getIntValue());
        }
        out.sort(null);
        out.forEach(System.out::println);
    }
}
