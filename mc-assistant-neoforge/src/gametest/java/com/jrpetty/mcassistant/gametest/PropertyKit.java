package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Bailiff;
import com.jrpetty.mcassistant.entity.Bank;
import com.jrpetty.mcassistant.entity.Belonging;
import com.jrpetty.mcassistant.entity.Debts;
import com.jrpetty.mcassistant.entity.LandPlots;
import com.jrpetty.mcassistant.entity.Planner;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.Tenancy;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** [wpc] The property tests' common ground: a flat town, a player with coin and standing, a house on the books, a page's buttons. */
final class PropertyKit {

    private PropertyKit() {}

    record T(UUID id, Villages.Village v, BlockPos heart, List<VillageFolkEntity> folk) {}

    /** Flat grass this far round, a town at its heart, so many folk with a little coin. */
    static T town(GameTestHelper helper, ServerLevel level, int x, int z, int n, int flat) {
        Kit.reset(level);
        Bank.resetForTests();
        Bailiff.resetForTests();
        Tenancy.resetForTests();
        LandPlots.resetForTests();
        LandPlots.PRESSURE_FOR_TESTS = false;
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, x, z, flat + 16);
        Kit.prepare(level, x, z, flat + 16);
        BlockPos ground = Kit.surface(level, x, z);
        int y = ground.getY();
        for (int dx = -flat; dx <= flat; dx++) {
            for (int dz = -flat; dz <= flat; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int h = 0; h <= 12; h++) level.setBlock(new BlockPos(x + dx, y + h, z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 3 + (i % 4) * 2, y, z - 4 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.earn(20);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Debts.resetForTests(id);
        return new T(id, Villages.get(id), new BlockPos(x, y, z), folk);
    }

    static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        if (coins > 0) for (int left = coins, i = 0; left > 0; left -= 64, i++) p.getInventory().setItem(i, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), Math.min(64, left)));
        Belonging.resetForTests(p.getUUID());
        Planner.resetForTests(p.getUUID());
        return p;
    }

    static int coins(ServerPlayer p) {
        return p.getInventory().countItem(McAssistantMod.VILLAGE_COIN.get());
    }

    static void friend(T t, ServerPlayer p) {
        for (VillageFolkEntity f : t.folk()) f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 60);
        Standing.stir(t.id(), p.getUUID());
    }

    static void citizen(T t, ServerPlayer p) {
        friend(t, p);
        Ledger.addCitizen(t.id(), p.getUUID(), p.getGameProfile().getName());
    }

    /** A cottage stamped and on the town's books, its door to the north; returns its anchor. */
    static BlockPos house(ServerLevel level, UUID id, BlockPos at) {
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "house", at, Direction.NORTH);
        return at;
    }

    /** A button on the page by its label (null if none). */
    static CompoundTag button(CompoundTag page, String label) {
        for (Tag t : page.getList("blocks", Tag.TAG_COMPOUND)) {
            ListTag b = ((CompoundTag) t).getList("b", Tag.TAG_COMPOUND);
            for (int i = 0; i < b.size(); i++) if (b.getCompound(i).getString("l").startsWith(label)) return b.getCompound(i);
        }
        return null;
    }

    /** Blocks of a kind on the page. */
    static int blocks(CompoundTag page, String kind) {
        int n = 0;
        for (Tag t : page.getList("blocks", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getString("t").equals(kind)) n++;
        return n;
    }

    static void finish(GameTestHelper helper, Kit.Expect ok) {
        if (ok.clean()) helper.succeed();
        else helper.fail(ok.summary());
    }
}
