package dev.structint.world;
import net.minecraft.core.registries.Registries; import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey; import net.minecraft.world.level.block.Block;
public final class StructuralTags {
    public static final TagKey<Block> STRUCTURAL_DIRT=t("structural_dirt"), STRUCTURAL_WOOD=t("structural_wood"),
        STRUCTURAL_STONE=t("structural_stone"), STRUCTURAL_REINFORCED=t("structural_reinforced"), STRUCTURAL_METAL=t("structural_metal");
    @SuppressWarnings("unchecked")
    private static TagKey<Block> t(String p){ return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("structint",p)); }
}
