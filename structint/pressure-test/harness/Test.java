package harness;

import java.util.function.IntUnaryOperator;

import dev.structint.Config;
import dev.structint.world.WaterPressure;
import dev.structint.world.WaterPressureScanner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Functional tests for the water-pressure engine against a deterministic fake world. */
public final class Test {

    static int pass = 0, fail = 0;
    static final int BOTTOM = 40, TOP = 69;   // 30 blocks of water, y 40..69

    public static void main(String[] args) {
        woodenDamOneThick();
        stoneGravityDam();
        archDam();
        buttressedWall();
        waterBothSides();
        anchoredIntoTerrain();
        underwaterBase();
        pondVsOcean();
        cascade();
        System.out.println("\n=== " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    // --------------------------------------------------------------- builders

    /** Reservoir to the west, wall of thickness t whose wet face sits at faceX(z), air to the east. */
    static ServerLevel dam(String material, int thickness, IntUnaryOperator faceX, int halfWidth, int reservoirLength) {
        W.reset();
        for (int z = -halfWidth; z <= halfWidth; z++) {
            int fx = faceX.applyAsInt(z);
            for (int y = BOTTOM; y <= TOP; y++) {
                for (int x = fx - reservoirLength; x < fx; x++) W.set(x, y, z, "water", false);
                for (int i = 0; i < thickness; i++) W.set(fx + i, y, z, material, true);
            }
            W.box(fx - reservoirLength, BOTTOM - 1, z, fx + thickness + 8, BOTTOM - 1, z, "natural", false);
        }
        return level();
    }

    static ServerLevel level() {
        ServerLevel l = new ServerLevel();
        ServerPlayer p = new ServerPlayer();
        p.pos = new BlockPos(0, 70, 0);
        l.players.add(p);
        return l;
    }

    static WaterPressure.Reading read(ServerLevel l, int x, int y, int z) {
        return WaterPressureScanner.inspect(l, new BlockPos(x, y, z));
    }

    // ------------------------------------------------------------------ cases

    static void woodenDamOneThick() {
        ServerLevel l = dam("wood", 1, z -> 1, 50, 60);
        WaterPressure.Reading base = read(l, 1, BOTTOM, 0);
        WaterPressure.Reading crest = read(l, 1, TOP, 0);
        check("wooden dam: 30 deep, big body -> load 60", near(base.load(), 60.0));
        check("wooden dam: one wood holds 4", near(base.run(), 4.0));
        check("wooden dam: base stress 15x", near(base.stress(), 15.0));
        check("wooden dam: base bursts", base.stress() >= 1.0);
        check("wooden dam: crest (1 deep) survives", crest.stress() < 1.0);
        info("  base " + fmt(base) + " | crest " + fmt(crest));
    }

    static void stoneGravityDam() {
        for (int t : new int[]{1, 4, 8}) {
            ServerLevel l = dam("stone", t, z -> 1, 50, 60);
            WaterPressure.Reading r = read(l, 1, BOTTOM, 0);
            info("  stone thickness " + t + ": " + fmt(r));
            if (t == 8) check("stone 8 thick holds 30 deep ocean", r.stress() < 1.0);
            if (t == 1) check("stone 1 thick fails 30 deep ocean", r.stress() >= 1.0);
        }
    }

    static void archDam() {
        // A real arch: the crown bows into the water, flanks sweep back parabolically.
        IntUnaryOperator curved = z -> 1 + Math.min(8, (z * z) / 8);
        ServerLevel straight = dam("concrete", 2, z -> 1, 40, 60);
        WaterPressure.Reading s = read(straight, 1, BOTTOM, 0);
        ServerLevel arch = dam("concrete", 2, curved, 40, 60);
        WaterPressure.Reading a = read(arch, 1, BOTTOM, 0);
        info("  concrete 2 thick straight: " + fmt(s));
        info("  concrete 2 thick arched:   " + fmt(a));
        check("arch is detected as curvature", a.curve() > 0);
        check("arch raises capacity over straight", a.capacity() > s.capacity());
        check("arched concrete 2 thick holds 30 deep ocean", a.stress() < 1.0);
    }

    static void buttressedWall() {
        for (int spacing : new int[]{2, 4, 10}) {
            W.reset();
            int t = 2;
            for (int z = -30; z <= 30; z++) {
                for (int y = BOTTOM; y <= TOP; y++) {
                    for (int x = -60; x < 1; x++) W.set(x, y, z, "water", false);
                    for (int i = 0; i < t; i++) W.set(1 + i, y, z, "stone", true);
                    if (Math.floorMod(z, spacing) == 0) {
                        for (int i = t; i < t + 8; i++) W.set(1 + i, y, z, "girder", true);
                    }
                }
            }
            ServerLevel l = level();
            WaterPressure.Reading onPier = read(l, 1, BOTTOM, 0);
            WaterPressure.Reading between = read(l, 1, BOTTOM, spacing / 2);
            info("  buttress every " + spacing + ": pier " + fmt(onPier) + " | between " + fmt(between));
            check("buttress spacing " + spacing + ": pier column holds", onPier.stress() < 1.0);
            if (spacing <= 4) check("buttress spacing " + spacing + ": panel borrows capacity", between.bracing() > 0.0);
            if (spacing == 2) check("buttress every 2 holds the mid panel", between.stress() < 1.0);
            if (spacing == 4) check("buttress every 4 still holds the mid panel", between.stress() < 1.0);
            if (spacing == 10) check("piers out of reach lend nothing", between.bracing() == 0.0);
            if (spacing == 10) check("unbraced 2-thick panel fails", between.stress() >= 1.0);
        }
    }

    static void waterBothSides() {
        W.reset();
        for (int z = -5; z <= 5; z++) for (int y = BOTTOM; y <= TOP; y++) {
            for (int x = -30; x < 1; x++) W.set(x, y, z, "water", false);
            W.set(1, y, z, "wood", true);
            for (int x = 2; x <= 30; x++) W.set(x, y, z, "water", false);
        }
        WaterPressure.Reading r = read(level(), 1, BOTTOM, 0);
        info("  submerged wall: " + fmt(r));
        check("water on both sides cancels", r.dry() || r.stress() < 0.01);
    }

    static void anchoredIntoTerrain() {
        W.reset();
        for (int z = -5; z <= 5; z++) for (int y = BOTTOM; y <= TOP; y++) {
            for (int x = -30; x < 1; x++) W.set(x, y, z, "water", false);
            W.set(1, y, z, "wood", true);
            for (int x = 2; x <= 12; x++) W.set(x, y, z, "natural", false);
        }
        WaterPressure.Reading r = read(level(), 1, BOTTOM, 0);
        info("  wall keyed into terrain: " + fmt(r));
        check("run reaching natural ground is anchored", r.dry());
    }

    static void underwaterBase() {
        // Sea from y=40 to 69; an air room behind a wall at x=1..t, room from x=t+1
        for (boolean flooded : new boolean[]{false, true}) {
            W.reset();
            int t = 2;
            for (int z = -6; z <= 6; z++) for (int y = BOTTOM; y <= TOP; y++) {
                for (int x = -40; x < 1; x++) W.set(x, y, z, "water", false);
                for (int i = 0; i < t; i++) W.set(1 + i, y, z, "stone", true);
                for (int x = 1 + t; x <= 10; x++) W.set(x, y, z, flooded ? "water" : "air", false);
            }
            WaterPressure.Reading r = read(level(), 1, BOTTOM, 0);
            info("  base wall, room " + (flooded ? "flooded" : "air") + ": " + fmt(r));
            if (flooded) check("flooding the room relieves the wall", r.dry() || r.stress() < 0.01);
            else check("air-filled room at depth loads the wall", r.stress() > 0.0);
        }
    }

    static void pondVsOcean() {
        ServerLevel ocean = dam("stone", 4, z -> 1, 50, 60);
        WaterPressure.Reading o = read(ocean, 1, BOTTOM, 0);
        ServerLevel pond = dam("stone", 4, z -> 1, 2, 2);
        WaterPressure.Reading p = read(pond, 1, BOTTOM, 0);
        info("  ocean: " + fmt(o) + " | pond: " + fmt(p));
        check("ocean body factor is larger than pond", o.body() > p.body());
        check("same depth, same head", o.head() == p.head());
        check("ocean pushes harder than pond at equal depth", o.load() > p.load());
    }

    static void cascade() {
        ServerLevel l = dam("wood", 3, z -> 1, 6, 60);
        WaterPressure.Reading before = read(l, 1, BOTTOM, 0);
        check("3-thick wood still fails 30 deep", before.stress() >= 1.0);
        WaterPressureScanner.tick(l);
        int firstRound = W.BROKEN.size();
        info("  cascade: first sweep burst " + firstRound + " blocks, e.g. " + (W.BROKEN.isEmpty() ? "-" : W.BROKEN.get(0)));
        check("sweep bursts overloaded blocks", firstRound > 0);
        check("deepest blocks go first", W.BROKEN.stream().anyMatch(s -> s.startsWith("1," + BOTTOM + ",")));
        check("burst blocks re-enter the span system", !W.REQUEUED.isEmpty());
        WaterPressureScanner.tick(l);
        info("  cascade: after second sweep total " + W.BROKEN.size());
        check("breach keeps walking back", W.BROKEN.size() > firstRound);
    }

    // ------------------------------------------------------------------ utils

    static boolean near(double a, double b) { return Math.abs(a - b) < 0.05; }
    static String fmt(WaterPressure.Reading r) {
        return String.format("head=%d body=%.2f load=%.1f run=%d brace=%.1f arch=%.2f curve=%d cap=%.1f stress=%.2f",
                r.head(), r.body(), r.load(), r.run(), r.bracing(), r.arch(), r.curve(), r.capacity(), r.stress());
    }
    static void info(String s) { System.out.println(s); }
    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (ok) pass++; else fail++;
    }
}
