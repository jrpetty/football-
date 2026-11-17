package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PlayerCoinPaths;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [health] A player's coin and the census: every way a player pays coin into a town or its folk, or has coin out of them,
 * books the census flow that explains it (players paying in, paid out to players), so the town's coin moves by exactly
 * what is booked: the morning count shows no drift for a player's dealings (CoinCensus, PlayerCoinPaths).
 * <ul>
 * <li><b>pc01</b>: the treasury's dealings by talk: dice, a feast, a made-to-order piece, bulk orders (bought, a deposit,
 *     the deposit back), a supply contract, the treasury's bank (deposit, withdrawal, a loan repaid, interest), an
 *     investment and its dividend, the auction (a bid, outbid, settled, the outbid's coin drawn), a caravan escorted.</li>
 * <li><b>pc02</b>: the law: an outlaw's fine and pardon, a bounty claimed, a barred player's fine, a fine taken, a
 *     constable's wage, bail and the bail back.</li>
 * <li><b>pc03</b>: the counters: the bank, the money changer both ways, rent collected (at the counter and by word), a
 *     stall's till, the mint at the market stall, pay out of the treasury, a hire, a map, a retinue's wage, the statue
 *     fund.</li>
 * </ul>
 * Each checks, path by path, that the town's coin (every holder the census counts) moved by just what was booked, and
 * that the paths meant to move coin did; the probe logs any coin moved by a way not booked, with where.
 *
 * <p>Each on its own ground, x 3,200,000 to 3,299,999 at z 90,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerCoinGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    private record Town(UUID id, Villages.Village v, List<VillageFolkEntity> folk) {}

    /** A Stone Age town of seven, a storehouse with planks, cobblestone and bread, a full treasury. */
    private static Town town(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(2000L);
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        List<VillageFolkEntity> folk = new ArrayList<>();
        folk.add(first);
        for (int i = 0; i < 6; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + i).east(1), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            f.setJob(StationTask.NONE);
            folk.add(f);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart);
        Villages.ageForTests(id, Villages.Age.STONE);
        for (ItemStack g : List.of(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.BREAD, 32))) {
            for (int i = 0; i < store.getContainerSize(); i++) if (store.getItem(i).isEmpty()) { store.setItem(i, g.copy()); break; }
        }
        store.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        com.jrpetty.mcassistant.entity.Budget.forget(id);
        Ledger.addCoins(id, 1500);
        for (VillageFolkEntity f : folk) f.earn(30);
        return new Town(id, Villages.get(id), folk);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart) {
        for (BlockPos p : Villages.storeChests(level, Villages.nearest(level, heart, Villages.VILLAGE_RANGE).id())) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        BlockPos origin = Kit.surface(level, heart.getX() + 8, heart.getZ() + 8);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) level.setBlock(origin.offset(x, y, z), unit, 3);
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands");
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    private static ServerPlayer player(GameTestHelper helper, VillageFolkEntity near, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.teleportTo(near.getX() + 1.5, near.getY(), near.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    /** Each path in turn: the town's coin against the flows booked (both in pennies), and the players' coin. */
    private static void drive(GameTestHelper helper, String test, Town t, List<PlayerCoinPaths.Path> paths, ServerPlayer... players) {
        UUID id = t.id();
        CoinCensus.probeForTests(id);
        List<String> wrong = new ArrayList<>(), idle = new ArrayList<>();
        try {
            for (PlayerCoinPaths.Path path : paths) {
                long c0 = CoinCensus.count(id), n0 = CoinCensus.netFlowPence(id);
                int held0 = 0;
                for (ServerPlayer p : players) held0 += Market.coinsHeld(p);
                CoinCensus.probe("before " + path.name());
                String said;
                try {
                    said = path.run().get();
                } catch (RuntimeException e) {
                    said = "threw " + e;
                    wrong.add(path.name() + " threw " + e);
                }
                CoinCensus.probe("after " + path.name());
                long c1 = CoinCensus.count(id), n1 = CoinCensus.netFlowPence(id);
                int held1 = 0;
                for (ServerPlayer p : players) held1 += Market.coinsHeld(p);
                long town = c1 - c0, booked = n1 - n0;
                Kit.log("[VT] " + test + " " + path.name() + ": the town's coin " + (town >= 0 ? "+" : "") + town + "p, booked " + (booked >= 0 ? "+" : "")
                    + booked + "p, the players' coin " + (held1 - held0 >= 0 ? "+" : "") + (held1 - held0) + "; said: " + (said == null ? "-" : said.length() > 160 ? said.substring(0, 160) : said));
                if (town != booked) wrong.add(path.name() + " (the town's coin " + town + "p, booked " + booked + "p)");
                if (path.moves() && held1 == held0) idle.add(path.name() + " (said: " + said + ")");
            }
        } finally {
            CoinCensus.probeForTests(null);
            Kit.noLeftoverPlayers(helper.getLevel());
        }
        helper.assertTrue(wrong.isEmpty(), "every player's coin booked: unbooked or wrong " + wrong);
        helper.assertTrue(idle.isEmpty(), "every path moved the player's coin: did nothing " + idle);
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pc01_treasury_dealings")
    public static void pc01_treasury_dealings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 3200100);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = t.folk().get(1);
            ServerPlayer p = player(helper, f, 400), q = player(helper, f, 0);
            drive(helper, "pc01", t, PlayerCoinPaths.talk(level, t.v(), f, p, q), p, q);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pc02_the_law")
    public static void pc02_the_law(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 3202300);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity guard = t.folk().get(1);
            guard.setJob(StationTask.GUARD);
            UUID elderId = Villages.elder(t.id());
            VillageFolkEntity elder = t.folk().get(0);
            for (VillageFolkEntity f : t.folk()) if (f.getUUID().equals(elderId)) elder = f;
            ServerPlayer p = player(helper, guard, 400), q = player(helper, guard, 0);
            drive(helper, "pc02", t, PlayerCoinPaths.law(level, t.v(), guard, elder, p, q), p, q);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pc03_counters")
    public static void pc03_counters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 3204500);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = t.folk().get(2);
            ServerPlayer p = player(helper, f, 400);
            drive(helper, "pc03", t, PlayerCoinPaths.counters(level, t.v(), f, p), p);
            helper.succeed();
        });
    }
}
