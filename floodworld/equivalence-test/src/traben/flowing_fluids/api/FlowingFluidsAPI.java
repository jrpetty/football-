package traben.flowing_fluids.api;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
public interface FlowingFluidsAPI {
    static FlowingFluidsAPI getInstance(String modid) { return harness.FakeApi.INSTANCE; }
    boolean isModEnabled();
    boolean doesModifyThisFluid(Fluid fluid);
    boolean modifyFluidAmountAtPos(LevelAccessor level, BlockPos pos, Fluid fluid, int amount);
}
