package com.jrpetty.mazerunner.config;

/**
 * Landmarks scattered through the corridors — pure placement logic, no
 * Minecraft classes.
 *
 * <p>At minigame scale every corridor is identical stone, which leaves a runner
 * nothing to recognise and nothing to map. These are small, deterministic
 * features — bones, webs, a lantern, rubble, a cairn — dropped into a fraction
 * of cells so the maze has memorable places in it.
 *
 * <p>Two invariants keep them safe. A landmark only ever occupies <b>one</b> of
 * the four columns at a cell's open centre, so at least three stay clear and no
 * corridor can be blocked; and the column chosen is never the cell's middle
 * square, which is where a dead-end supply chest would stand. Landmarks are
 * decoration on the floor only — they never touch walls, so they cannot change
 * whether a layout is solvable.
 */
public final class MazeLandmarks {

    public static final int NONE = -1;
    public static final int BONES = 0;
    public static final int WEBS = 1;
    public static final int LANTERN = 2;
    public static final int RUBBLE = 3;
    public static final int CAIRN = 4;
    public static final int TYPES = 5;

    /** One accent colour per compass section, so each section reads differently. */
    public static final int ACCENTS = 8;

    /** Roughly one cell in twelve carries a landmark. */
    private static final double DENSITY = 0.085;

    private MazeLandmarks() {}

    /** True if this cell carries a landmark at all. */
    public static boolean hasLandmark(MazeConfigData cfg, int cellX, int cellZ) {
        if (!cfg.inGrid(cellX, cellZ) || cfg.inGlade(cellX, cellZ)) return false;
        // Leave the ring of cells around the Glade clean — the doors read better bare.
        if (cellX >= cfg.gladeCellMin - 1 && cellX <= cfg.gladeCellMax + 1
            && cellZ >= cfg.gladeCellMin - 1 && cellZ <= cfg.gladeCellMax + 1) {
            return false;
        }
        MazeConfigData.ExitDef exit = cfg.fixedExit();
        if (cellX == exit.cellX() && cellZ == exit.cellZ()) return false;
        return Noise.hash2(cellX * 31 + 7, cellZ * 17 + 3, 0x1A2D) < DENSITY;
    }

    /** Which landmark this cell carries (only meaningful when it has one). */
    public static int typeAt(int cellX, int cellZ) {
        return (int) (Noise.hash2(cellX + 11, cellZ - 5, 0x5C0E) * TYPES) % TYPES;
    }

    /**
     * Which of three safe centre columns holds the feature: 0 = (lo,lo),
     * 1 = (lo,hi), 2 = (hi,lo). The fourth — (hi,hi) — is deliberately never
     * used because that is the chest column.
     */
    public static int featureCorner(int cellX, int cellZ) {
        return (int) (Noise.hash2(cellX - 3, cellZ + 9, 0x3B71) * 3) % 3;
    }

    /**
     * The landmark type occupying this exact block column, or {@link #NONE}.
     * Everything else in the corridor is left clear.
     */
    public static int typeFor(MazeConfigData cfg, int blockX, int blockZ) {
        int lo = MazeConfigData.WALL_BAND;
        int hi = cfg.cellSize - 1 - MazeConfigData.WALL_BAND;
        if (hi <= lo) return NONE; // corridor too narrow to decorate safely

        int cellX = Math.floorDiv(blockX, cfg.cellSize);
        int cellZ = Math.floorDiv(blockZ, cfg.cellSize);
        if (!hasLandmark(cfg, cellX, cellZ)) return NONE;

        int corner = featureCorner(cellX, cellZ);
        int featureX = corner == 2 ? hi : lo;
        int featureZ = corner == 1 ? hi : lo;
        int lx = Math.floorMod(blockX, cfg.cellSize);
        int lz = Math.floorMod(blockZ, cfg.cellSize);
        return lx == featureX && lz == featureZ ? typeAt(cellX, cellZ) : NONE;
    }

    /** Accent index 0..7 for a position, derived from its compass section. */
    public static int accentFor(MazeConfigData cfg, int blockX, int blockZ) {
        int section = cfg.sectionOf(blockX, blockZ); // 1..8
        return Math.floorMod(section - 1, ACCENTS);
    }
}
