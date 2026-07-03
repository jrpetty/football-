package com.jrpetty.mazerunner.config;

/**
 * Permanent maze-wall geometry with surface relief — pure logic, no Minecraft.
 *
 * <p>The flat 8-wide-corridor floor plan from {@link MazeStructures#isOpen}
 * gets a movie-panel look: along every wall run, each 8-block panel is pushed
 * either 1 out (into the corridor) or 1 in (a recessed niche), alternating.
 * The relief only ever touches the single corridor-edge layer, so corridor
 * centres — and therefore every path the maze depends on — stay clear.
 *
 * <p>Relief is disabled on movable segments (toggle points, Glade doors, exit
 * gates) and inside plaza clearings, so those stay flush for the animator and
 * the structure builder. Each Glade door also gets bulked entry posts.
 */
public final class MazeWalls {

    private static final int PANEL = 8;

    private MazeWalls() {}

    /** +1 = panel pushed out into the corridor, -1 = recessed niche. Alternates per 8. */
    private static int panelOffset(int runCoord) {
        return (Math.floorDiv(runCoord, PANEL) & 1) == 0 ? 1 : -1;
    }

    private static int cell(int block, int size) {
        return Math.floorDiv(block, size);
    }

    private static boolean inMaze(MazeConfigData cfg, int x, int z) {
        int cx = cell(x, cfg.cellSize);
        int cz = cell(z, cfg.cellSize);
        return cfg.inGrid(cx, cz) && !cfg.inGlade(cx, cz);
    }

    private static boolean inPlaza(MazeConfigData cfg, int x, int z) {
        return MazeStructures.plazaAtCell(cfg, cell(x, cfg.cellSize), cell(z, cfg.cellSize)) != null;
    }

    /** Whether (x,z) lies on or within 1 block of any movable segment footprint. */
    private static boolean nearMovable(MazeConfigData cfg, int x, int z) {
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                for (MazeConfigData.StateBox box : cfg.boxesIn((x >> 4) + dcx, (z >> 4) + dcz)) {
                    if (x >= box.box().x0() - 1 && x <= box.box().x1() + 1
                        && z >= box.box().z0() - 1 && z <= box.box().z1() + 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Flush base solidity (no relief) for the maze region. */
    private static boolean baseSolid(MazeConfigData cfg, int x, int z) {
        return inMaze(cfg, x, z) && !MazeStructures.isOpen(cfg, x, z);
    }

    /**
     * Final solidity of a permanent maze wall block, with relief and door
     * posts. Vertical range is the wall band; outside it, false.
     */
    public static boolean solid(MazeConfigData cfg, int x, int y, int z) {
        if (y < cfg.wallBaseY || y > cfg.wallTopY) return false;
        if (!inMaze(cfg, x, z)) return false;

        if (doorPost(cfg, x, z)) return true;
        if (inPlaza(cfg, x, z)) return !MazeStructures.isOpen(cfg, x, z);

        boolean base = baseSolid(cfg, x, z);
        if (nearMovable(cfg, x, z)) return base;

        if (base) {
            // A flat outer face (exactly one open orthogonal neighbour) can recess.
            int open = 0;
            int run = 0;
            if (isOpenForRelief(cfg, x - 1, z)) { open++; run = z; }
            if (isOpenForRelief(cfg, x + 1, z)) { open++; run = z; }
            if (isOpenForRelief(cfg, x, z - 1)) { open++; run = x; }
            if (isOpenForRelief(cfg, x, z + 1)) { open++; run = x; }
            if (open == 1 && panelOffset(run) == -1) return false; // 1-deep niche
            return true;
        }

        // An open corridor-edge block can be pushed into by a neighbouring panel.
        if (!MazeStructures.isOpen(cfg, x, z)) return false; // plaza/void handled above
        if (nearMovable(cfg, x, z)) return false;
        if (protrudesFrom(cfg, x - 1, z, z)) return true;
        if (protrudesFrom(cfg, x + 1, z, z)) return true;
        if (protrudesFrom(cfg, x, z - 1, x)) return true;
        if (protrudesFrom(cfg, x, z + 1, x)) return true;
        return false;
    }

    private static boolean openMaze(MazeConfigData cfg, int x, int z) {
        return inMaze(cfg, x, z) && MazeStructures.isOpen(cfg, x, z);
    }

    /** Open for relief purposes — maze corridors AND the open Glade both count,
     *  so the walls facing the Glade recess/protrude just like the maze. */
    private static boolean isOpenForRelief(MazeConfigData cfg, int x, int z) {
        int cx = cell(x, cfg.cellSize);
        int cz = cell(z, cfg.cellSize);
        if (!cfg.inGrid(cx, cz)) return false;
        if (cfg.inGlade(cx, cz)) return true;
        return MazeStructures.isOpen(cfg, x, z);
    }

    /** A Glade-edge block that a neighbouring wall panel pushes out into. */
    public static boolean gladeProtrusion(MazeConfigData cfg, int gx, int gz) {
        int cx = cell(gx, cfg.cellSize);
        int cz = cell(gz, cfg.cellSize);
        if (!cfg.inGrid(cx, cz) || !cfg.inGlade(cx, cz)) return false;
        return protrudeGladeFrom(cfg, gx - 1, gz, gz) || protrudeGladeFrom(cfg, gx + 1, gz, gz)
            || protrudeGladeFrom(cfg, gx, gz - 1, gx) || protrudeGladeFrom(cfg, gx, gz + 1, gx);
    }

    private static boolean protrudeGladeFrom(MazeConfigData cfg, int nx, int nz, int run) {
        return baseSolid(cfg, nx, nz) && !nearMovable(cfg, nx, nz) && !inPlaza(cfg, nx, nz)
            && panelOffset(run) == 1;
    }

    /** Solidity including Glade-edge protrusions; use for generation & greenery. */
    public static boolean solidWithGlade(MazeConfigData cfg, int x, int y, int z) {
        if (y < cfg.wallBaseY || y > cfg.wallTopY) return false;
        int cx = cell(x, cfg.cellSize);
        int cz = cell(z, cfg.cellSize);
        if (cfg.inGrid(cx, cz) && cfg.inGlade(cx, cz)) return gladeProtrusion(cfg, x, z);
        return solid(cfg, x, y, z);
    }

    private static boolean protrudesFrom(MazeConfigData cfg, int nx, int nz, int run) {
        return baseSolid(cfg, nx, nz) && !nearMovable(cfg, nx, nz) && !inPlaza(cfg, nx, nz)
            && panelOffset(run) == 1;
    }

    // ------------------------------------------------------------- door posts

    /**
     * Bulked entry posts flanking each Glade door: two full-height columns at
     * the opening edges, one step into the corridor on each side. They leave a
     * clear central gap so the door is always passable.
     */
    private static boolean doorPost(MazeConfigData cfg, int x, int z) {
        for (MazeConfigData.DoorDef door : cfg.doors) {
            MazeConfigData.Box b = door.box();
            boolean approachZ = door.name().equals("north") || door.name().equals("south");
            if (approachZ) {
                boolean atEdge = x == b.x0() || x == b.x1();          // opening edge columns
                boolean beyond = z == b.z0() - 1 || z == b.z1() + 1;  // one step into each corridor
                if (atEdge && beyond) return true;
            } else {
                boolean atEdge = z == b.z0() || z == b.z1();
                boolean beyond = x == b.x0() - 1 || x == b.x1() + 1;
                if (atEdge && beyond) return true;
            }
        }
        return false;
    }
}
