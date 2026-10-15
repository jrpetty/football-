package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * [borders-map] The admin toolkit's actions for the towns' lands and the war room (AdminSpec: army dispute, army settle,
 * world borders, world maptable), bound here so the toolkit's own classes take only a line for them (AdminActions).
 */
public final class BordersAdmin {

    private BordersAdmin() {}

    /** [borders-map] The lands, by decree: a dispute begun or settled, the borders drawn, the markers set, the town's map table. */
    public static AdminTools.Done act(ServerLevel level, net.minecraft.commands.CommandSourceStack src, Villages.Village v, String id, String word,
                               @Nullable Villages.Village other) {
        String town = Villages.name(v.id());
        switch (id) {
            case "army.dispute" -> {
                if (other == null || other.id().equals(v.id())) return AdminTools.Done.no("No other town called " + word + ".");
                if (!com.jrpetty.mcassistant.village.Ledger.knowEachOther(v.id(), other.id())) com.jrpetty.mcassistant.village.Ledger.relate(v.id(), other.id(), 0);
                BlockPos mid = new BlockPos((v.centre().getX() + other.centre().getX()) / 2, v.centre().getY(), (v.centre().getZ() + other.centre().getZ()) / 2);
                BorderDisputes.Dispute d = BorderDisputes.raise(level, v.id(), other.id(), BorderDisputes.Cause.OVERLAP,
                    "the land between " + town + " and " + Villages.name(other.id()), mid);
                return AdminTools.Done.ok("A land dispute: " + town + " against " + Villages.name(other.id()) + " over " + d.subject() + ".",
                    town + " laid claim to the land between it and " + Villages.name(other.id()));
            }
            case "army.settle" -> {
                int n = BorderDisputes.settleAll(level, v.id());
                return n == 0 ? AdminTools.Done.said(town + " has no land dispute to settle.")
                    : AdminTools.Done.ok(n + " land dispute(s) of " + town + " settled by treaty.", "the elders settled " + town + "'s land disputes by treaty");
            }
            case "world.borders" -> {
                Borders.drawNow(level);
                return switch (word.isEmpty() ? "draw" : word) {
                    case "markers" -> AdminTools.Done.ok(BorderMarkers.plantAllFree(level, v) + " border markers set for " + town + ".", town + "'s border was marked");
                    case "release" -> {
                        Borders.release(v.id());
                        yield AdminTools.Done.ok("The lands of " + town + " let go.", "the lands of " + town + " were let go");
                    }
                    default -> AdminTools.Done.said("The lands drawn: " + town + " holds " + Borders.area(v.id()) + " chunks.");
                };
            }
            case "world.maptable" -> {
                net.minecraft.server.level.ServerPlayer p = src.getPlayer();
                switch (word.isEmpty() ? "place" : word) {
                    case "give" -> {
                        if (p == null) return AdminTools.Done.no("Only a player can be given a map table.");
                        ItemStack s = new ItemStack(com.jrpetty.mcassistant.item.BorderItems.MAP_TABLE_ITEM.get());
                        if (!p.getInventory().add(s)) p.drop(s, false);
                        return AdminTools.Done.said("A map table, for you.");
                    }
                    case "reveal" -> {
                        if (p == null) return AdminTools.Done.no("Only a player can look at a map table.");
                        WarRoom.reveal(p, !WarRoom.revealed(p));
                        return AdminTools.Done.said(WarRoom.revealed(p) ? "Every map table shows you the whole world for the next hour." : "The map tables show only what their towns know.");
                    }
                    default -> {
                        Borders.drawNow(level);
                        BlockPos at = WarRoom.placeNow(level, v);
                        return at == null ? AdminTools.Done.no("No room for a map table in " + town + ".")
                            : AdminTools.Done.ok("The map table of " + town + " stands at " + at.toShortString() + ".", "a map table was set in " + town + "'s hall");
                    }
                }
            }
            default -> {
                return AdminTools.Done.no("Not bound: " + id);
            }
        }
    }
}
