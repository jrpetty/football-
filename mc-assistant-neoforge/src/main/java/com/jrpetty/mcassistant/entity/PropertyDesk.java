package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * [player15] Selling, letting and repurposing a building of yours: the buttons are on your shop's screen and the Your
 * life in town page. [join21] The town's property office answers (PropertyOffice, PropertyMarket): the buttons open its
 * screen on the "My property" tab with your shop's building first, where it is priced, put up for sale or to let, or
 * quoted for a new use. A player's shop goes on the town's property register when it is set up (or the first time its
 * buttons are pressed): a building the town built for the player is on it already, as the builders handed it over; one the
 * player built itself is put on it by its walls, its building valued as what its walls, floor and roof would cost to put up
 * again at the market's prices.
 */
public final class PropertyDesk {

    private PropertyDesk() {}

    /** The property office's screen for this (sell, let, repurpose), on the player's shop's building. */
    public static String open(ServerPlayer p, String what, String from) {
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!The property office keeps a town's register: go to one.";
        if (what.equals("orders")) { PropertyOffice.openOn(p, "orders", null, "orders"); return "#open:"; }   // [biz25] the builders' orders
        PlayerShops.Shop s = PlayerShops.of(v.id(), p.getUUID());
        String key = s == null ? null : register(p.serverLevel(), s);
        PropertyOffice.openOn(p, "property", key, what);
        return "#open:";
    }

    /** The shop's building on the property register (found there, or put on it now): its key, or null if it can't be. */
    @Nullable
    public static String register(ServerLevel level, PlayerShops.Shop s) {
        String found = keyFor(s.village, s.owner, s.anchor);
        if (found != null) {
            tagBusiness(s, found);
            return found;
        }
        if (!level.isLoaded(s.anchor)) return null;
        ShopNeeds.Survey sv = PlayerShops.survey(level, s);
        if (sv.room.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE;
        for (BlockPos r : sv.room) {
            x0 = Math.min(x0, r.getX()); x1 = Math.max(x1, r.getX());
            z0 = Math.min(z0, r.getZ()); z1 = Math.max(z1, r.getZ());
            y0 = Math.min(y0, r.getY());
        }
        // Its walls stand a block outside the room.
        int hw = (x1 - x0) / 2 + 1, hd = (z1 - z0) / 2 + 1;
        BlockPos anchor = new BlockPos((x0 + x1) / 2, y0, (z0 + z1) / 2);
        BlockState counter = sv.counter() == null ? null : level.getBlockState(sv.counter());
        Direction facing = counter != null && counter.hasProperty(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING)
            ? counter.getValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING) : Direction.NORTH;
        // register() turns the footprint by the facing: give it across and deep as it faces.
        boolean turned = facing.getAxis() == Direction.Axis.X;
        String key = PropertyMarket.register(level, s.village, s.owner, s.ownerName, s.name, "shop", anchor, facing, "",
            turned ? hd : hw, turned ? hw : hd);
        CompoundTag t = PropertyMarket.get(s.village, key);
        if (t != null) {
            t.putLong("rebuild", rebuildPence(level, s.village, sv));
            t.putBoolean("selfBuilt", true);
            if (!t.contains("built")) t.putLong("built", level.getDayTime() / 24000L);
        }
        tagBusiness(s, key);
        Accounts.dirty();
        return key;
    }

    /** The register's entry of this owner's whose footprint holds this spot, or null. */
    @Nullable
    static String keyFor(UUID village, UUID owner, BlockPos at) {
        for (PropertyMarket.Property pr : PropertyMarket.ownedBy(village, owner)) {
            CompoundTag t = PropertyMarket.get(village, pr.key());
            if (t == null) continue;
            BlockPos a = pr.anchor();
            Direction f = Direction.from2DDataValue(t.getInt("facing"));
            boolean turned = f.getAxis() == Direction.Axis.X;
            int bx = turned ? t.getInt("hd") : t.getInt("hw"), bz = turned ? t.getInt("hw") : t.getInt("hd");
            if (Math.abs(at.getX() - a.getX()) <= bx && Math.abs(at.getZ() - a.getZ()) <= bz && Math.abs(at.getY() - a.getY()) <= 12) return pr.key();
        }
        return null;
    }

    /** A firm trading from the shop: its goodwill counts in the building's value. */
    static void tagBusiness(PlayerShops.Shop s, String key) {
        CompoundTag t = PropertyMarket.get(s.village, key);
        if (t == null) return;
        String biz = s.firm == null ? "" : s.firm;
        if (!biz.equals(t.getString("biz"))) {
            t.putString("biz", biz);
            Accounts.dirty();
        }
    }

    /** What the shell round a room (its walls, its floor, its roof) would cost to put up again, at the market's prices (pennies). */
    static long rebuildPence(ServerLevel level, UUID village, ShopNeeds.Survey sv) {
        Set<BlockPos> shell = new HashSet<>();
        for (BlockPos r : sv.room) {
            for (Direction d : Direction.values()) {
                BlockPos n = r.relative(d);
                if (!sv.room.contains(n)) shell.add(n.immutable());
            }
        }
        double coins = 0;
        for (BlockPos b : shell) {
            if (!level.isLoaded(b)) continue;
            BlockState st = level.getBlockState(b);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            ItemStack one = new ItemStack(st.getBlock().asItem());
            if (one.isEmpty()) continue;
            coins += Math.max(0.02, CostBook.atMarket(level, village, one));
        }
        // The builders' hours for it: a mason's day for every hundred blocks, at the town's rate.
        coins += shell.size() / 100.0 * Math.max(1, JobWorth.rate(AssistantEntity.StationTask.SMITH, village)) / 10.0 * 8;
        return Math.round(coins * 100);
    }
}
