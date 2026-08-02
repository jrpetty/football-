package net.minecraft.world.level.block.state;
import net.minecraft.tags.TagKey; import net.minecraft.world.level.block.Block; import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.FluidState;
public class BlockState {
    public final String m;
    public BlockState(String m){this.m=m;}
    public boolean isAir(){return m.equals("air");}
    public SoundType getSoundType(){return new SoundType();}
    public FluidState getFluidState(){return m.equals("water")?new FluidState(8):FluidState.EMPTY;}
    /** Tag membership by material name, mirroring the mod's real block tags. */
    public boolean is(TagKey<Block> t){
        switch(t.id){
            case "structint:structural_wood":       return m.equals("wood");
            case "structint:structural_stone":      return m.equals("stone")||m.equals("natural");
            case "structint:structural_reinforced": return m.equals("concrete");
            case "structint:structural_metal":      return m.equals("girder");
            case "structint:structural_dirt":       return m.equals("dirt");
            case "structint:pressure_sealing":      return m.equals("obsidian");
            case "structint:pressure_brittle":      return m.equals("glass");
            case "structint:pressure_exempt":       return m.equals("bedrock");
            default: return false;
        }
    }
}
