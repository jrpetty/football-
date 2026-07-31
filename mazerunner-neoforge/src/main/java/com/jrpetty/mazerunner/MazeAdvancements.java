package com.jrpetty.mazerunner;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Glader's progression. Every advancement uses an {@code impossible}
 * criterion so the mod decides exactly when it is earned, rather than trying to
 * express "survived a night in the Maze" as a vanilla trigger.
 */
public final class MazeAdvancements {

    public static final String GREENIE = "greenie";
    public static final String RUNNER = "runner";
    public static final String NIGHT_SURVIVOR = "night_survivor";
    public static final String GRIEVER_SLAYER = "griever_slayer";
    public static final String CURED = "cured";
    public static final String ESCAPED = "escaped";

    /** The single criterion name shared by every advancement in this mod. */
    private static final String CRITERION = "earned";

    private MazeAdvancements() {}

    /**
     * Grants an advancement; silently does nothing if it can't be resolved.
     *
     * @return true only if this granted it for the first time — useful for
     *         doing something once per player, like the newcomer briefing.
     */
    public static boolean award(ServerPlayer player, String path) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        try {
            AdvancementHolder holder = server.getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath(MazeRunnerMod.MODID, path));
            if (holder != null) {
                return player.getAdvancements().award(holder, CRITERION);
            }
        } catch (Throwable t) {
            MazeRunnerMod.LOGGER.warn("Maze Runner: could not award advancement {}: {}", path, t.toString());
        }
        return false;
    }
}
