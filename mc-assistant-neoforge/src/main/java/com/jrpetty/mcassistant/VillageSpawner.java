package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settlements that were already there.
 *
 * <p>Villages are placed on a grid the same way the game places its own
 * structures: one candidate spot per cell, its exact position decided by the
 * world seed, so the same world always grows the same settlements and no two
 * end up on top of each other. When the chunk holding a candidate spot loads
 * for the first time and the ground there is worth living on, a village is
 * founded — a handful of folk, a chest of the things you cannot start without,
 * and nothing else. From that moment nobody helps them again.
 *
 * <p>They are given exactly enough to begin: food to live on while the first
 * field is dug, an axe and a pick so the first tree and the first stone are
 * possible, and seeds, saplings, torches and a crafting table in a chest. Every
 * single thing after that — the farm, the tools, the smeltery, the houses — is
 * theirs to earn.
 */
public final class VillageSpawner {

    private VillageSpawner() {}

    /** Chunks a village keeps awake around itself, so it grows whether or not
     *  anybody is watching. Four is a comfortable settlement's worth. */
    private static final int LOADED_RADIUS = 4;

    /** Cells we have already looked at this session, so a chunk that loads and
     *  unloads repeatedly is not re-examined every time. */
    private static final Set<Long> CONSIDERED = new HashSet<>();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!AssistantConfig.naturalVillages()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        ChunkPos chunk = event.getChunk().getPos();
        int spacing = Math.max(256, AssistantConfig.villageSpacing());
        int cellX = Math.floorDiv(chunk.getMinBlockX(), spacing);
        int cellZ = Math.floorDiv(chunk.getMinBlockZ(), spacing);
        long cellKey = (long) cellX * 4294967311L + cellZ;
        if (CONSIDERED.contains(cellKey)) return;

        BlockPos anchor = anchorFor(level, cellX, cellZ, spacing);
        // Only the chunk that actually contains the anchor does the work, so
        // this costs one comparison for every other chunk in the cell.
        if ((anchor.getX() >> 4) != chunk.x || (anchor.getZ() >> 4) != chunk.z) return;
        CONSIDERED.add(cellKey);

        if (Villages.nearest(level, anchor) != null) return;      // one already stands here
        if (folkNearby(level, anchor)) return;                    // ...or its people do
        BlockPos ground = groundAt(level, anchor.getX(), anchor.getZ());
        if (ground == null || !liveable(level, ground)) return;

        // Never build into a chunk that is still loading. Adding entities and
        // setting blocks from inside the load event is how you corrupt the
        // very chunk you are settling; the server runs this at the top of the
        // next tick instead, by which time the ground is really there.
        level.getServer().execute(() -> {
            if (Villages.nearest(level, ground) != null || folkNearby(level, ground)) return;
            found(level, ground);
        });
    }

    /** The candidate spot for a grid cell, fixed by the world seed. */
    private static BlockPos anchorFor(ServerLevel level, int cellX, int cellZ, int spacing) {
        RandomSource r = RandomSource.create(
            level.getSeed() ^ (cellX * 341873128712L + cellZ * 132897987541L));
        // Kept away from the cell edges so two neighbouring settlements can
        // never end up within shouting distance of each other.
        int inset = spacing / 4;
        int x = cellX * spacing + inset + r.nextInt(Math.max(1, spacing - inset * 2));
        int z = cellZ * spacing + inset + r.nextInt(Math.max(1, spacing - inset * 2));
        return new BlockPos(x, 0, z);
    }

    @Nullable
    private static BlockPos groundAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        if (y <= level.getMinBuildHeight() + 1) return null;
        return new BlockPos(x, y, z);
    }

    /**
     * Is this somewhere people could actually live? Dry land, above the tide,
     * and flat enough to build on — a village halfway up a cliff or standing
     * in a lake is not a village.
     */
    private static boolean liveable(ServerLevel level, BlockPos ground) {
        if (ground.getY() < level.getSeaLevel()) return false;
        if (level.getBlockState(ground.below()).is(Blocks.WATER)) return false;
        if (level.getBlockState(ground.below()).isAir()) return false;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int dx = -12; dx <= 12; dx += 6) {
            for (int dz = -12; dz <= 12; dz += 6) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                    ground.getX() + dx, ground.getZ() + dz);
                lowest = Math.min(lowest, y);
                highest = Math.max(highest, y);
            }
        }
        return highest - lowest <= 8;
    }

    private static boolean folkNearby(ServerLevel level, BlockPos anchor) {
        return !level.getEntitiesOfClass(VillageFolkEntity.class,
            new AABB(anchor).inflate(Villages.VILLAGE_RANGE), e -> true).isEmpty();
    }

    /** Put a settlement here: a supply chest, some folk, and nothing else. */
    private static void found(ServerLevel level, BlockPos ground) {
        int min = Math.max(1, AssistantConfig.villageMinFolk());
        int max = Math.max(min, AssistantConfig.villageMaxFolk());
        RandomSource r = RandomSource.create(level.getSeed() ^ ground.asLong());
        int size = min + r.nextInt(max - min + 1);

        Villages.Village village = Villages.found(ground);
        supplyChest(level, ground);

        Set<String> used = new HashSet<>();
        for (int i = 0; i < size; i++) {
            double angle = (Math.PI * 2 / size) * i;
            int x = ground.getX() + (int) Math.round(Math.cos(angle) * 4);
            int z = ground.getZ() + (int) Math.round(Math.sin(angle) * 4);
            BlockPos spot = groundAt(level, x, z);
            if (spot == null) spot = ground;

            VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (folk == null) continue;
            folk.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, r.nextFloat() * 360F, 0F);
            folk.rename(freshName(used));
            starterKit(folk);
            level.addFreshEntity(folk);
            // Settling, choosing a trade and finding ground all happen on the
            // folk's own agenda within a few seconds of standing up.
        }

        // The settlement keeps its own chunks awake, so it grows while the
        // player is a thousand blocks away — which is the entire point of a
        // village that lives on the map rather than in front of you.
        ChunkLoad.setLoaded(level, village.id(), ground, LOADED_RADIUS, true);
    }

    /** What a pair of hands needs to survive its first day and start work. */
    private static void starterKit(VillageFolkEntity folk) {
        folk.insertItem(new ItemStack(Items.BREAD, 16));
        folk.insertItem(new ItemStack(Items.STONE_AXE));
        folk.insertItem(new ItemStack(Items.STONE_PICKAXE));
    }

    /**
     * The village's founding stores. Everything here is a thing you cannot
     * bootstrap out of bare ground in reasonable time — seed for the first
     * field, saplings so the wood does not run out, light, and the bench that
     * every other tool comes off. Deliberately not generous: no iron, no
     * furnace, no food beyond what they carry.
     */
    private static void supplyChest(ServerLevel level, BlockPos ground) {
        BlockPos at = ground.above();
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(at) instanceof Container chest)) return;
        List<ItemStack> stores = List.of(
            new ItemStack(Items.WHEAT_SEEDS, 32),
            new ItemStack(Items.OAK_SAPLING, 16),
            new ItemStack(Items.TORCH, 32),
            new ItemStack(Items.CRAFTING_TABLE, 1),
            new ItemStack(Items.CHEST, 4),
            new ItemStack(Items.OAK_PLANKS, 32),
            new ItemStack(Items.BREAD, 32));
        for (int i = 0; i < stores.size() && i < chest.getContainerSize(); i++) {
            chest.setItem(i, stores.get(i).copy());
        }
        chest.setChanged();
    }

    private static String freshName(Set<String> used) {
        for (String candidate : Names.POOL) {
            if (used.add(candidate.toLowerCase())) return candidate;
        }
        return "folk_" + (used.size() + 1);
    }
}
