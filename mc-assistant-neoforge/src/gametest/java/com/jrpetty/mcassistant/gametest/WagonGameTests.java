package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Caravans;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Shipments;
import com.jrpetty.mcassistant.entity.TradeWagon;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WagonKind;
import com.jrpetty.mcassistant.entity.WagonManifest;
import com.jrpetty.mcassistant.entity.Wagons;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [wagons] The trade wagons (entity/Wagons, TradeWagon): a shipment loaded, driven down the road between two towns and
 * unloaded, every piece of it accounted for at every moment (goods no folk uses, so none is eaten or built with); a road walled across, driven round or nudged past; a
 * wagon lost with its driver, its load spilt and not delivered; the manifest; a wagon out of sight moved on by number
 * and delivering all the same; and a caravan's goods carried by the town's handcart.
 *
 * <p>Game-test band: x 1800000 to 1819999, z 66000. Each test its own batch (Kit.reset clears the level's folk).
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WagonGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    /** Two towns this far apart: well clear of each other (Villages.VILLAGE_RANGE), a short road between. */
    private static final int APART = 160;

    private record Pair(Villages.Village a, Villages.Village b) {}

    private static Pair towns(GameTestHelper helper, ServerLevel level, int x) {
        Kit.reset(level);
        Wagons.resetForTests();
        Shipments.resetForTests();
        Wagons.ambushForTests(false);
        level.setDayTime(24000L * 6 + 2000);
        for (TradeWagon w : level.getEntitiesOfClass(TradeWagon.class, new AABB(x - 64, -64, Z - 64, x + APART + 64, 320, Z + 64))) w.discard();
        Villages.Village[] out = new Villages.Village[2];
        for (int i = 0; i < 2; i++) {
            int cx = x + i * APART;
            Kit.hold(level, cx, Z, 40);
            Kit.prepare(level, cx, Z, 40);
            BlockPos heart = Kit.surface(level, cx, Z);
            int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 5);
            out[i] = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
            helper.assertTrue(stood >= 4 && out[i] != null, "a town of five at " + cx + ": " + stood);
            for (AssistantEntity a : Villages.folkOf(out[i].id())) if (a instanceof VillageFolkEntity f) f.ensurePersona();
            for (BlockPos p : Villages.storeChests(level, out[i].id())) {
                if (level.getBlockEntity(p) instanceof Container c) { c.clearContent(); c.setChanged(); }
            }
            Ledger.addCoins(out[i].id(), 200);
        }
        helper.assertTrue(!out[0].id().equals(out[1].id()), "two towns, not one");
        return new Pair(out[0], out[1]);
    }

    /** A chest of the town's stores at its heart, holding these goods (an empty one if none). */
    private static void chest(ServerLevel level, Villages.Village v, int k, ItemStack... goods) {
        BlockPos at = Kit.surface(level, v.centre().getX() + 4 + 2 * k, v.centre().getZ() + 6);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i].copy());
        box.setChanged();
        Villages.forgetStock();
    }

    /** A grown, tamed horse of the town's, standing by its wagon. */
    private static void horse(ServerLevel level, Villages.Village v, TradeWagon w) {
        Horse h = EntityType.HORSE.create(level);
        h.setTamed(true);
        h.setOwnerUUID(v.id());
        h.moveTo(w.getX() + 3, w.getY(), w.getZ() + 3, 0, 0);
        level.addFreshEntity(h);
    }

    private static int stores(ServerLevel level, UUID town, Item it) {
        Villages.forgetStock();
        return Market.stock(level, town, s -> s.is(it));
    }

    private static int loose(ServerLevel level, double x, double z, double r, Item it) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(x - r, -64, z - r, x + r, 320, z + r), e -> e.getItem().is(it))) {
            n += e.getItem().getCount();
        }
        return n;
    }

    /** Every piece of this good anywhere it can be: both towns' stores, the wagon, the carrier's back, the ground. */
    private static int everywhere(ServerLevel level, Pair p, Item it, int x) {
        TradeWagon w = Wagons.wagonForTests(level, p.a().id());
        int n = stores(level, p.a().id(), it) + stores(level, p.b().id(), it) + (w == null ? 0 : Wagons.aboardForTests(w, it));
        for (int i = 0; i < 2; i++) {
            for (AssistantEntity a : Villages.folkOf((i == 0 ? p.a() : p.b()).id())) n += a.countCarried(s -> s.is(it));
        }
        return n + loose(level, x + APART / 2.0, Z, APART, it);
    }

    private static Shipments.Shipment ship(GameTestHelper helper, ServerLevel level, Pair p, String tag, ItemStack... goods) {
        Shipments.Shipment s = Shipments.dispatch(level, p.a().id(), p.b().id(), "test/" + tag, List.of(goods), 0);
        helper.assertTrue(s != null, tag + ": the shipment is dispatched (its goods in the stores)");
        TradeWagon w = Wagons.wagonForTests(level, p.a().id());
        helper.assertTrue(w != null && s.id().equals(Wagons.shipmentForTests(w)), tag + ": the town's wagon takes it, not the road: "
            + (w == null ? "no wagon" : Wagons.stateForTests(level, p.a().id())));
        return s;
    }

    private static TradeWagon wagon(GameTestHelper helper, ServerLevel level, Villages.Village v, WagonKind k, boolean animal) {
        TradeWagon w = Wagons.giveForTests(level, v.id(), k);
        helper.assertTrue(w != null, "a " + k.label + " in " + Villages.name(v.id()) + "'s yard");
        if (animal) horse(level, v, w);
        return w;
    }

    // ------------------------------------------------------------------ wg01: there and back, nothing gained or lost

    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "wg01_shipment_round_trip")
    public static void wg01_shipment_round_trip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1800000;
        Pair p = towns(helper, level, x);
        Wagons.nearForTests(true);
        chest(level, p.a(), 0, new ItemStack(Items.AMETHYST_SHARD, 64), new ItemStack(Items.PRISMARINE_SHARD, 32));
        chest(level, p.b(), 0);
        wagon(helper, level, p.a(), WagonKind.WAGON, true);
        Shipments.Shipment s = ship(helper, level, p, "wg01", new ItemStack(Items.AMETHYST_SHARD, 48), new ItemStack(Items.PRISMARINE_SHARD, 20));
        helper.assertTrue(stores(level, p.a().id(), Items.AMETHYST_SHARD) == 16 && stores(level, p.a().id(), Items.PRISMARINE_SHARD) == 12,
            "the load came out of the sending town's stores, exactly");
        Set<String> seen = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(level.getDayTime() + 24000 - level.getDayTime() % 24000 + 1000);
            if (t % 20 != 0) return;
            TradeWagon w = Wagons.wagonForTests(level, p.a().id());
            if (w == null) { helper.fail("the wagon is gone: " + Wagons.stateForTests(level, p.a().id())); return; }
            seen.add(Wagons.phaseForTests(w));
            int amethyst = everywhere(level, p, Items.AMETHYST_SHARD, x), prismarine = everywhere(level, p, Items.PRISMARINE_SHARD, x);
            if (amethyst != 64 || prismarine != 32) {
                helper.fail("goods made or lost on the way at " + t + ": amethyst " + amethyst + " of 64, prismarine " + prismarine + " of 32 — "
                    + Wagons.stateForTests(level, p.a().id()));
                return;
            }
            if (t % 400 == 0) Kit.log("wg01 @" + t + ": " + Wagons.stateForTests(level, p.a().id()) + "; B amethyst "
                + stores(level, p.b().id(), Items.AMETHYST_SHARD));
            if (Shipments.get(s.id()) == null && "PARKED".equals(Wagons.phaseForTests(w))) {
                int bw = stores(level, p.b().id(), Items.AMETHYST_SHARD), bi = stores(level, p.b().id(), Items.PRISMARINE_SHARD);
                Kit.log("wg01 home at " + t + ": phases " + seen + "; the other town's stores: " + bw + " amethyst, " + bi + " prismarine");
                helper.assertTrue(bw == 48 && bi == 20, "the other town's stores took exactly the load: " + bw + " amethyst, " + bi + " prismarine");
                helper.assertTrue(seen.containsAll(Set.of("FETCH", "LOADING", "ROAD", "UNLOADING")),
                    "made ready, loaded, driven and unloaded: " + seen);
                helper.assertTrue(w.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p.a().centre())) < 40 * 40, "and parked at home");
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ wg02: a wall across the road

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "wg02_stall_recovered")
    public static void wg02_stall_recovered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1803000;
        Pair p = towns(helper, level, x);
        Wagons.nearForTests(true);
        chest(level, p.a(), 0, new ItemStack(Items.WHEAT, 32));
        chest(level, p.b(), 0);
        wagon(helper, level, p.a(), WagonKind.WAGON, true);
        ship(helper, level, p, "wg02", new ItemStack(Items.WHEAT, 16));
        double[] wall = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 10 != 0) return;
            TradeWagon w = Wagons.wagonForTests(level, p.a().id());
            if (w == null) { helper.fail("the wagon is gone"); return; }
            double s = Wagons.alongForTests(w);
            if (wall[0] < 0) {
                if (!"ROAD".equals(Wagons.phaseForTests(w)) || s < 30) return;
                // A stone wall five high and thirteen wide across the road, ten blocks ahead of it.
                wall[0] = s + 10;
                double[] at = Wagons.pointForTests(w, wall[0]);
                double h = at[2];
                for (int k = -6; k <= 6; k++) {
                    int bx = (int) Math.floor(at[0] - Math.sin(h) * k), bz = (int) Math.floor(at[1] + Math.cos(h) * k);
                    BlockPos g = Kit.surface(level, bx, bz);
                    for (int up = 0; up < 5; up++) level.setBlock(g.above(up), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                }
                Kit.log("wg02 a wall across the road at " + (int) wall[0] + " (the wagon at " + (int) s + ")");
                return;
            }
            if (s > wall[0] + 4) {
                Kit.log("wg02 past the wall at " + t + ": " + Wagons.stateForTests(level, p.a().id()));
                helper.succeed();
            } else if (t % 200 == 0) {
                Kit.log("wg02 @" + t + ": " + Wagons.stateForTests(level, p.a().id()));
            }
        });
    }

    // ------------------------------------------------------------------ wg03: lost with its driver

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "wg03_lost_wagon")
    public static void wg03_lost_wagon(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1806000;
        Pair p = towns(helper, level, x);
        Wagons.nearForTests(true);
        chest(level, p.a(), 0, new ItemStack(Items.AMETHYST_SHARD, 64), new ItemStack(Items.PRISMARINE_SHARD, 32));
        chest(level, p.b(), 0);
        wagon(helper, level, p.a(), WagonKind.WAGON, true);
        Shipments.Shipment s = ship(helper, level, p, "wg03", new ItemStack(Items.AMETHYST_SHARD, 32), new ItemStack(Items.PRISMARINE_SHARD, 16));
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0] || helper.getTick() % 10 != 0) return;
            TradeWagon w = Wagons.wagonForTests(level, p.a().id());
            if (w == null || !"ROAD".equals(Wagons.phaseForTests(w)) || Wagons.alongForTests(w) < 20) return;
            done[0] = true;
            VillageFolkEntity d = Wagons.driverForTests(level, w);
            double wx = w.getX(), wz = w.getZ();
            helper.assertTrue(d != null, "a driver on the bench");
            Caravans.abandon(level, d);                                // lost on the road
            int amethyst = loose(level, wx, wz, 8, Items.AMETHYST_SHARD), prismarine = loose(level, wx, wz, 8, Items.PRISMARINE_SHARD);
            Kit.log("wg03 lost at " + (int) wx + "," + (int) wz + ": spilt " + amethyst + " amethyst, " + prismarine + " prismarine; the shipment "
                + (Shipments.get(s.id()) == null ? "settled" : "still open") + "; the town's wagon " + Wagons.owned(p.a().id()));
            helper.assertTrue(w.isRemoved() && Wagons.owned(p.a().id()) == null, "the wagon is lost to the town");
            helper.assertTrue(Shipments.get(s.id()) == null, "the shipment is reported lost (settled), not left open");
            helper.assertTrue(amethyst == 32 && prismarine == 16, "its load spilt on the road where it was lost: " + amethyst + " amethyst, " + prismarine + " prismarine");
            helper.assertTrue(stores(level, p.a().id(), Items.AMETHYST_SHARD) == 32 && stores(level, p.b().id(), Items.AMETHYST_SHARD) == 0,
                "nothing back in the sender's stores, nothing delivered");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wg04: the manifest

    @GameTest(template = EMPTY, timeoutTicks = 2000, batch = "wg04_manifest")
    public static void wg04_manifest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1809000;
        Pair p = towns(helper, level, x);
        Wagons.nearForTests(true);
        chest(level, p.a(), 0, new ItemStack(Items.WHEAT, 64), new ItemStack(Items.IRON_INGOT, 32));
        chest(level, p.b(), 0);
        wagon(helper, level, p.a(), WagonKind.WAGON, true);
        ship(helper, level, p, "wg04", new ItemStack(Items.WHEAT, 48), new ItemStack(Items.IRON_INGOT, 20));
        helper.onEachTick(() -> {
            if (helper.getTick() % 10 != 0) return;
            TradeWagon w = Wagons.wagonForTests(level, p.a().id());
            if (w == null || !"ROAD".equals(Wagons.phaseForTests(w))) return;
            List<String> m = WagonManifest.text(level, w);
            String all = String.join(" | ", m);
            Kit.log("wg04 the manifest: " + all);
            String from = Villages.name(p.a().id()), to = Villages.name(p.b().id());
            helper.assertTrue(m.get(0).contains(from + "'s wagon") && m.get(0).contains("from " + from + " to " + to),
                "whose, and from where to where: " + m.get(0));
            helper.assertTrue(all.contains("48 wheat") && all.contains("20 iron ingot") && all.contains("68 in all"), "what is aboard: " + all);
            helper.assertTrue(all.contains("Worth about ") && !all.contains("Worth about 0 "), "what it is worth: " + all);
            helper.assertTrue(all.contains("load sent to order") && all.contains("Driver: ") && all.contains("pulled by"), "its errand and crew: " + all);
            helper.assertTrue(all.contains("Spoken for"), "a shipment is not for sale off the wagon: " + all);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wg05: out of sight

    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "wg05_quiet_road")
    public static void wg05_quiet_road(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1812000;
        Pair p = towns(helper, level, x);
        Wagons.nearForTests(false);                                    // no player anywhere: it goes quiet on the road
        chest(level, p.a(), 0, new ItemStack(Items.AMETHYST_SHARD, 64));
        chest(level, p.b(), 0);
        wagon(helper, level, p.a(), WagonKind.WAGON, true);
        Shipments.Shipment s = ship(helper, level, p, "wg05", new ItemStack(Items.AMETHYST_SHARD, 40));
        boolean[] quiet = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 20 != 0) return;
            String st = Wagons.stateForTests(level, p.a().id());
            if (st.startsWith("FAR") && !quiet[0]) {
                quiet[0] = true;
                Kit.log("wg05 quiet at " + t + ": " + st);
            }
            if (t % 400 == 0) Kit.log("wg05 @" + t + ": " + st);
            if (Shipments.get(s.id()) == null) {
                int bw = stores(level, p.b().id(), Items.AMETHYST_SHARD);
                Kit.log("wg05 delivered at " + t + ": the other town has " + bw + " amethyst; " + st);
                helper.assertTrue(quiet[0], "it went quiet on the road with nobody near");
                helper.assertTrue(bw == 40, "and delivered all of it, coming back into the world at the other end: " + bw);
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ wg06: a caravan's goods by handcart

    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "wg06_caravan_by_wagon")
    public static void wg06_caravan_by_wagon(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1815000;
        Pair p = towns(helper, level, x);
        Wagons.nearForTests(true);
        ItemStack[] stone = new ItemStack[10];
        for (int i = 0; i < stone.length; i++) stone[i] = new ItemStack(Items.COBBLESTONE, 64);
        chest(level, p.a(), 0, stone);
        chest(level, p.b(), 0);
        wagon(helper, level, p.a(), WagonKind.HANDCART, false);
        boolean out = Caravans.setOut(level, p.a(), p.b());
        TradeWagon w = Wagons.wagonForTests(level, p.a().id());
        helper.assertTrue(out && w != null && !"PARKED".equals(Wagons.phaseForTests(w)),
            "a caravan sets out with the town's handcart: " + out + ", " + Wagons.stateForTests(level, p.a().id()));
        int[] loaded = { 0 };
        EnumSet<TradeWagon.Phase> seen = EnumSet.noneOf(TradeWagon.Phase.class);
        int bBefore = stores(level, p.b().id(), Items.COBBLESTONE);
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(level.getDayTime() + 24000 - level.getDayTime() % 24000 + 1000);
            if (t % 20 != 0) return;
            TradeWagon now = Wagons.wagonForTests(level, p.a().id());
            if (now == null) { helper.fail("the handcart is gone"); return; }
            seen.add(TradeWagon.Phase.valueOf(Wagons.phaseForTests(now)));
            if ("ROAD".equals(Wagons.phaseForTests(now)) && loaded[0] == 0) {
                loaded[0] = Wagons.aboardForTests(now, Items.COBBLESTONE);
                Kit.log("wg06 on the road with " + loaded[0] + " stone in the handcart");
            }
            if (t % 400 == 0) Kit.log("wg06 @" + t + ": " + Wagons.stateForTests(level, p.a().id()));
            boolean home = "PARKED".equals(Wagons.phaseForTests(now)) && seen.contains(TradeWagon.Phase.UNLOADING);
            if (home) {
                int got = stores(level, p.b().id(), Items.COBBLESTONE) - bBefore;
                Kit.log("wg06 home at " + t + ": phases " + seen + "; the other town took " + got + " stone");
                helper.assertTrue(loaded[0] > 0, "the caravan's stone rode in the handcart, not on the carrier's back");
                helper.assertTrue(got > 0, "the other town's stores took the caravan's stone: " + got);
                helper.succeed();
            }
        });
    }
}
