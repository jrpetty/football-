package dev.structint.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Block tags specific to water pressure, alongside the structural ones. */
public final class HydroTags {

    /** Never fails under water pressure, whatever the depth. */
    public static final TagKey<Block> PRESSURE_EXEMPT = tag("pressure_exempt");
    /** Brittle against water even though it may be structurally fine (glass, panes, bars). */
    public static final TagKey<Block> PRESSURE_BRITTLE = tag("pressure_brittle");
    /** Purpose-built to hold water back - the top rating regardless of material family. */
    public static final TagKey<Block> PRESSURE_SEALING = tag("pressure_sealing");

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("structint", path));
    }

    private HydroTags() {
    }
}
