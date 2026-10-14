package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [shops-homes] The pictures' stage (/village service stage, ops; the client smoke): the shop stood up by the spot with
 * its service counter, its stock set out on its counters and three folk queuing at the counter (held still for the
 * camera till /village service stage done), and two houses side by side, a poor household's and a well-off one's, their
 * boxes and rooms as a few weeks of shopping leave them. A showcase: what it sets out is the stage's own, as every
 * showcase's is. And the screen opened on a player (/village service open): the trade's screen as at its counter.
 */
public final class ServiceStage {

    private ServiceStage() {}

    private static final List<UUID> HELD = new ArrayList<>();

    /** The stage, and where each camera stands ("SHOT name ex ey ez ax ay az"). */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos near) {
        List<String> out = new ArrayList<>(Workshop.stage(level, v, near));
        UUID id = v.id();
        Ledger.Building shop = Villages.builtStructure(id, "shop");
        if (shop == null) return out;
        // The counter: fitted as the town fits it (the stores' planks), else set in for the stage.
        ServicePoints.fitOutForTests(level, v);
        ServicePoints.Point p = ServicePoints.nearest(level, id, ServiceRole.SHOP, shop.anchor());
        if (p == null) {
            BlockPos at = ServicePoints.counterSpot(level, shop);
            if (at != null) {
                level.setBlock(at, McAssistantMod.SERVICE_COUNTER.get().defaultBlockState()
                    .setValue(com.jrpetty.mcassistant.block.ServiceCounterBlock.FACING, StoreFloor.front(at, TownLife.fittings(shop).door())), 3);
                ServicePoints.resetPointsForStage();
                p = ServicePoints.nearest(level, id, ServiceRole.SHOP, shop.anchor());
            }
        }
        // Its wares, on the counters.
        for (ItemStack s : List.of(new ItemStack(Items.BREAD, 16), new ItemStack(Items.CANDLE, 8), new ItemStack(Items.LANTERN, 4),
                new ItemStack(Items.FLOWER_POT, 4), new ItemStack(Items.PAINTING, 2), new ItemStack(Items.BOOK, 6),
                new ItemStack(Items.LEATHER_BOOTS, 2), new ItemStack(Items.IRON_PICKAXE))) {
            if (Store.count(level, id, x -> x.is(s.getItem())) < s.getCount()) Store.put(level, id, s.copy());
        }
        StoreFloor.dress(level, v);
        if (p != null) {
            VillageFolkEntity keeper = Workshop.keeper(id);
            if (keeper != null && p.stand() != null) hold(keeper, p.stand(), p.pos());
            int i = 0;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (i >= 3 || !(a instanceof VillageFolkEntity f) || f == keeper || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.SHOP) continue;
                hold(f, p.queueSpot(level, i), p.pos());
                i++;
            }
            BlockPos q = p.queueSpot(level, 3);
            Direction front = p.front();
            BlockPos eye = q.relative(front.getClockWise(), 2);
            out.add(shot("shop-queue", eye, p.pos().above()));
            BlockPos door = TownLife.fittings(shop).door();
            if (door != null) out.add(shot("shop-interior", door.relative(front.getOpposite()), p.pos().relative(front.getOpposite(), 2).above()));
            out.add("COUNTER " + p.pos().getX() + " " + p.pos().getY() + " " + p.pos().getZ());
        }
        // Two houses side by side: a poor household's and a well-off one's.
        BlockPos poor = Store.flatGround(level, shop.anchor().offset(-26, 0, 18));
        BlockPos rich = poor.offset(12, 0, 0);
        for (BlockPos h : List.of(poor, rich)) {
            Store.clearSite(level, h);
            BuildGoal.stamp(level, "house", h, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        }
        furnish(level, poor, false);
        furnish(level, rich, true);
        out.add(shot("homes-side-by-side", poor.offset(6, 4, -14), poor.offset(6, 2, 0)));
        out.add(shot("home-rich-inside", rich.offset(0, 1, 2), rich.offset(0, 1, -3)));
        out.add(shot("home-poor-inside", poor.offset(0, 1, 2), poor.offset(0, 1, -3)));
        return out;
    }

    private static String shot(String name, BlockPos eye, BlockPos at) {
        return "SHOT " + name + " " + (eye.getX() + 0.5) + " " + eye.getY() + " " + (eye.getZ() + 0.5) + " "
            + (at.getX() + 0.5) + " " + (at.getY() + 0.5) + " " + (at.getZ() + 0.5);
    }

    private static void hold(VillageFolkEntity f, BlockPos at, BlockPos look) {
        f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        f.setNoAi(true);
        f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 1.0, look.getZ() + 0.5);
        HELD.add(f.getUUID());
    }

    /** Everybody held for the camera let go. */
    public static int release(ServerLevel level) {
        int n = 0;
        for (UUID u : HELD) if (level.getEntity(u) instanceof VillageFolkEntity f) { f.setNoAi(false); n++; }
        HELD.clear();
        return n;
    }

    /** A house's rooms and boxes as its household's means leave them: the poor a larder of bread and a chest of little;
     *  the well-off a full larder, a wardrobe, a shelf of books, a rug, a lantern, flowers on the sill and candles. */
    private static void furnish(ServerLevel level, BlockPos house, boolean rich) {
        BlockPos chest = null;
        for (BuildGoal.Placement p : BuildGoal.plan("house", house, Direction.NORTH, 13)) {
            if (p.part() == BuildGoal.Part.CHEST) chest = p.pos();
        }
        if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
            c.setItem(0, new ItemStack(rich ? Items.BOOK : Items.TORCH, rich ? 4 : 3));
            c.setItem(1, new ItemStack(rich ? Items.CANDLE : Items.STICK, rich ? 6 : 2));
            if (rich) c.setItem(2, new ItemStack(Items.PAINTING));
            c.setChanged();
        }
        List<BlockPos> floor = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(house.offset(-2, 1, -2), house.offset(2, 1, 2))) {
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolidRender(level, p.below())) floor.add(p.immutable());
        }
        if (floor.isEmpty()) return;
        BlockPos larder = floor.get(0);
        level.setBlock(larder, Blocks.BARREL.defaultBlockState(), 3);
        if (level.getBlockEntity(larder) instanceof Container c) {
            c.setItem(0, new ItemStack(Items.BREAD, rich ? 12 : 3));
            if (rich) {
                c.setItem(1, new ItemStack(Items.COOKED_BEEF, 6));
                c.setItem(2, new ItemStack(Items.CAKE));
            }
            c.setChanged();
        }
        if (!rich || floor.size() < 5) return;
        BlockPos wardrobe = floor.get(floor.size() - 1);
        level.setBlock(wardrobe, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(wardrobe) instanceof Container c) {
            c.setItem(0, new ItemStack(Items.LEATHER_BOOTS));
            c.setItem(1, new ItemStack(Items.LEATHER_CHESTPLATE));
        }
        level.setBlock(floor.get(1), Blocks.BOOKSHELF.defaultBlockState(), 3);
        level.setBlock(floor.get(2), Blocks.RED_CARPET.defaultBlockState(), 3);
        if (level.getBlockState(larder.above()).isAir()) level.setBlock(larder.above(), Blocks.CANDLE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.CandleBlock.LIT, true), 3);
        if (level.getBlockState(floor.get(3)).isAir()) level.setBlock(floor.get(3), Blocks.LANTERN.defaultBlockState(), 3);
        if (floor.size() > 5 && level.getBlockState(floor.get(4)).isAir()) level.setBlock(floor.get(4), Blocks.POTTED_POPPY.defaultBlockState(), 3);
    }

    /** /village service open: the player's screen at the nearest point of a trade, on a tab; "full" fills its basket. */
    public static String open(ServerPlayer p, ServiceRole role, String tab) {
        if (!(p.level() instanceof ServerLevel level)) return "Not here.";
        Villages.Village v = Villages.nearest(level, p.blockPosition(), 128);
        if (v == null) return "No village within reach.";
        ServicePoints.Point point = ServicePoints.nearest(level, v.id(), role, p.blockPosition());
        if (point == null) return "The town has no " + role.title.toLowerCase(java.util.Locale.ROOT) + " to serve at.";
        ServicePoints.openAt(p, point.pos(), tab.equals("sell") ? "sell" : "");
        int nonce = ServicePoints.nextNonceForTests(p);
        if (tab.equals("full") && role == ServiceRole.SHOP) {
            CompoundTag basket = new CompoundTag();
            int n = 0;
            for (String line : ShopDesk.linesForTests(level, v, role)) {
                String[] kv = line.split("=");
                int stock = Integer.parseInt(kv[1].replace("+order", ""));
                if (stock <= 0 || n >= 3) continue;
                basket.putInt(kv[0], Math.min(stock, 2 + n));
                n++;
            }
            CompoundTag args = new CompoundTag();
            args.put("basket", basket);
            ServicePoints.act(p, role.id, "basket", args, nonce);
        }
        return "SERVICE " + role.id + " " + tab + " at " + point.pos().toShortString();
    }

}
