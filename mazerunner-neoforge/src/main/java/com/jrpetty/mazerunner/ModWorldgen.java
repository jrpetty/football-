package com.jrpetty.mazerunner;

import com.jrpetty.mazerunner.gen.MazeChunkGenerator;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModWorldgen {

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, MazeRunnerMod.MODID);

    /** Featureless, structure-free biome used across the whole maze world (JSON-defined). */
    public static final ResourceKey<Biome> GLADE_BIOME = ResourceKey.create(Registries.BIOME,
        ResourceLocation.fromNamespaceAndPath(MazeRunnerMod.MODID, "glade"));

    static {
        CHUNK_GENERATORS.register("maze", () -> MazeChunkGenerator.CODEC);
    }

    private ModWorldgen() {}
}
