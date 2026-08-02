package net.minecraft.world.level.block;
import net.minecraft.world.level.block.state.BlockState;
public class Block { final String m; public Block(String m){this.m=m;} public BlockState defaultBlockState(){return new BlockState(m);} }
