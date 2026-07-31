package harness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import traben.flowing_fluids.api.FlowingFluidsAPI;
public final class FakeApi implements FlowingFluidsAPI {
    public static final FakeApi INSTANCE = new FakeApi();
    @Override public boolean isModEnabled() { return true; }
    @Override public boolean doesModifyThisFluid(Fluid fluid) { return true; }
    @Override public boolean modifyFluidAmountAtPos(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        return World.setWater(pos.getX(), pos.getY(), pos.getZ(), amount);
    }
}
