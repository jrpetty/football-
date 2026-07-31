package net.minecraft.world.level.biome;
import net.minecraft.core.BlockPos;
public class Biome {
    public Precipitation getPrecipitationAt(BlockPos pos) {
        return harness.World.noRainBiome(pos.getX(), pos.getZ()) ? Precipitation.NONE : Precipitation.RAIN;
    }
    public enum Precipitation { NONE, RAIN, SNOW }
}
