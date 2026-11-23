package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * [pa33c-2b] "Ping the nearest iron", "find me a tree", "where's the closest village": the nearest one in what's loaded
 * round its player (32 blocks out, 24 up and down; towns from the town list), or null. A single look, on demand.
 */
final class PaSeek {

    private PaSeek() {}

    /** What a word means to look for, said back nicely. */
    static String label(String what) {
        String w = what.toLowerCase(Locale.ROOT).replaceAll(" ores?$", "").replaceAll("s$", "");
        return switch (w) {
            case "log", "wood", "tree" -> "a tree";
            case "village", "town" -> "the village";
            case "iron", "gold", "coal", "copper", "diamond", "emerald", "redstone", "lapi", "lapis" -> (w.equals("lapi") ? "lapis" : w) + " ore";
            default -> w;
        };
    }

    @Nullable
    static Predicate<BlockState> matcher(String what) {
        String w = what.toLowerCase(Locale.ROOT).replaceAll(" ores?$", "").replaceAll("s$", "");
        return switch (w) {
            case "log", "wood", "tree" -> s -> s.is(BlockTags.LOGS);
            case "iron", "gold", "coal", "copper", "diamond", "emerald", "redstone", "lapi", "lapis" -> {
                String key = (w.equals("lapi") ? "lapis" : w) + "_ore";
                yield s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith(key);
            }
            case "ore" -> s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_ore");
            case "chest" -> s -> s.is(net.minecraft.world.level.block.Blocks.CHEST) || s.is(net.minecraft.world.level.block.Blocks.BARREL);
            case "bed" -> s -> s.is(BlockTags.BEDS);
            case "furnace" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock;
            case "crafting table" -> s -> s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
            case "water" -> s -> s.getFluidState().is(FluidTags.WATER);
            case "lava" -> s -> s.getFluidState().is(FluidTags.LAVA);
            default -> null;
        };
    }

    @Nullable
    static BlockPos nearest(ServerLevel level, BlockPos from, String what) {
        String w = what.toLowerCase(Locale.ROOT);
        if (w.startsWith("village") || w.startsWith("town")) {
            BlockPos best = null;
            double bd = 512 * 512;
            for (com.jrpetty.mcassistant.entity.Villages.Village v : com.jrpetty.mcassistant.entity.Villages.every()) {
                if (!v.dim().equals(level.dimension())) continue;
                double d = v.centre().distSqr(from);
                if (d < bd) { bd = d; best = v.centre(); }
            }
            return best;
        }
        Predicate<BlockState> m = matcher(what);
        if (m == null) return null;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -32; dx <= 32; dx++) for (int dz = -32; dz <= 32; dz++) {
            int x = from.getX() + dx, z = from.getZ() + dz;
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            for (int dy = -24; dy <= 24; dy++) {
                p.set(x, from.getY() + dy, z);
                double d = (double) dx * dx + dz * dz + dy * dy;
                if (d >= bd) continue;
                if (m.test(level.getBlockState(p))) { bd = d; best = p.immutable(); }
            }
        }
        return best;
    }
}
