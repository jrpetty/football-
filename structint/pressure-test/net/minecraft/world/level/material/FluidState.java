package net.minecraft.world.level.material;
import net.minecraft.tags.FluidTags; import net.minecraft.tags.TagKey;
public class FluidState { public static final FluidState EMPTY=new FluidState(0); final int a;
  public FluidState(int a){this.a=a;}
  public boolean is(TagKey<Fluid> t){return a>0 && t.id.equals(FluidTags.WATER.id);} public int getAmount(){return a;} }
