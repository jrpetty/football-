package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [vision27] A block a builder lays is heard and seen going in: the block's own sound of being placed (the thunk of
 * stone, the knock of planks, glass's clink), a little quieter than a player's and a shade lower, and a few crumbs of
 * the block knocked off as it is set. Before, the walls rose in silence, block after block simply there. Sent only to
 * players near enough to see it (the game's own sound range and particle range), a few dozen bytes a block.
 */
public final class BuildFx {

    private BuildFx() {}

    /** A block set by the town's works (a household's house laid a few at a time): its crumbs, and its sound if asked. */
    public static void laid(ServerLevel level, BlockPos pos, boolean sound) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return;
        if (sound) {
            SoundType s = st.getSoundType(level, pos, null);
            level.playSound(null, pos, s.getPlaceSound(), SoundSource.BLOCKS, (s.getVolume() + 1.0F) / 2.0F * 0.8F, s.getPitch() * 0.82F);
        }
        if (st.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) return;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            5, 0.3, 0.3, 0.3, 0.05);
    }

    /** The block just set at this spot by this folk. */
    public static void placed(AssistantEntity builder, BlockPos pos) {
        if (!(builder.level() instanceof ServerLevel level)) return;
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return;
        SoundType sound = st.getSoundType(level, pos, builder);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F * 0.8F,
            sound.getPitch() * (0.78F + builder.getRandom().nextFloat() * 0.1F));
        if (st.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) return;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            6, 0.3, 0.3, 0.3, 0.05);
    }
}
