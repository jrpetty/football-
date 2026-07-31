package net.minecraft.world.level.material;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
public class FluidState {
    public static final FluidState EMPTY = new FluidState(0);
    private final int amount;
    public FluidState(int amount) { this.amount = amount; }
    public boolean is(TagKey<Fluid> tag) { return amount > 0 && tag.id.equals(FluidTags.WATER.id); }
    public int getAmount() { return amount; }
}
