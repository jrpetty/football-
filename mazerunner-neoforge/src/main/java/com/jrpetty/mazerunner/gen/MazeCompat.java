package com.jrpetty.mazerunner.gen;

import java.util.List;
import java.util.Optional;

import com.jrpetty.mazerunner.MazeRunnerMod;
import com.jrpetty.mazerunner.config.GladeTerrain;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Best-effort Dynamic Trees soft-compat. When the {@code dynamictrees} mod is
 * present, forest trunk sites are planted with DT saplings (looked up by
 * registry name, guarded — a miss simply falls back to the built-in tree, and
 * nothing here can crash a world without the mod). DT then grows its own
 * apple-oak / oak / dark-oak / birch trees over the first minutes.
 *
 * <p>If DT's block names differ on your version, the lookups return empty and
 * the built-in forest is used; report the version and I'll pin the exact ids.
 */
public final class MazeCompat {

    private static volatile Boolean dtLoaded;
    private static BlockState[] dtSaplingBySpecies; // indexed by GladeTerrain species

    private MazeCompat() {}

    public static boolean dynamicTrees() {
        Boolean local = dtLoaded;
        if (local == null) {
            synchronized (MazeCompat.class) {
                if (dtLoaded == null) {
                    dtLoaded = ModList.get() != null && ModList.get().isLoaded("dynamictrees");
                    if (dtLoaded) resolveSaplings();
                    MazeRunnerMod.LOGGER.info("Maze Runner: Dynamic Trees {}",
                        dtLoaded ? "detected — planting DT saplings in the Glade forest"
                            : "not present — using built-in trees");
                }
                local = dtLoaded;
            }
        }
        return local;
    }

    /** DT sapling state for a species, or null to fall back to a built-in tree. */
    static BlockState sapling(int species) {
        if (!dynamicTrees() || dtSaplingBySpecies == null) return null;
        return dtSaplingBySpecies[species];
    }

    private static void resolveSaplings() {
        dtSaplingBySpecies = new BlockState[4];
        dtSaplingBySpecies[GladeTerrain.OAK] = firstExisting("oak");
        dtSaplingBySpecies[GladeTerrain.BIRCH] = firstExisting("birch");
        dtSaplingBySpecies[GladeTerrain.DARK_OAK] = firstExisting("dark_oak", "darkoak");
        dtSaplingBySpecies[GladeTerrain.APPLE_OAK] = firstExisting("apple_oak", "apple", "appleoak", "oak");
    }

    /** Tries common DT sapling id patterns for a species; returns the first that exists. */
    private static BlockState firstExisting(String... species) {
        for (String s : species) {
            for (String pattern : List.of(s + "_sapling", "sapling_" + s, s)) {
                for (String ns : List.of("dynamictrees", "dynamictreesplus")) {
                    Optional<Block> block = BuiltInRegistries.BLOCK
                        .getOptional(ResourceLocation.fromNamespaceAndPath(ns, pattern));
                    if (block.isPresent() && !block.get().defaultBlockState().isAir()) {
                        return block.get().defaultBlockState();
                    }
                }
            }
        }
        return null;
    }
}
