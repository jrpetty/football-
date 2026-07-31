package harness;
import net.minecraft.world.level.block.state.BlockState;
public final class Blocks {
    public static BlockState at(int x, int y, int z) {
        int w = World.water(x, y, z);
        if (w > 0) return new BlockState(w, null);
        int top = World.solidTop(x, z);
        if (y > top) return BlockState.AIR;
        if (y == top && World.leafTop(x, z)) return new BlockState(0, "minecraft:leaves");
        if (y == top && Math.floorMod(x * 7 + z * 3, 131) == 0) return new BlockState(0, "floodworld:rain_blocked");
        return new BlockState(0, "minecraft:stone");
    }
}
