package net.minecraft.world.level.block.state;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FluidState;
public class BlockState {
    public static final BlockState AIR = new BlockState(0, null);
    private final int water;
    private final String tag;
    public BlockState(int water, String tag) { this.water = water; this.tag = tag; }
    public boolean is(TagKey<Block> t) { return tag != null && tag.equals(t.id); }
    public FluidState getFluidState() { return water > 0 ? new FluidState(water) : FluidState.EMPTY; }
}
