package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [paths] Whose house it is: the sign by every home's front door, with the name and the trade of whoever lives there.
 *
 * <p>"Gwen" over "Miner" for a folk on its own; for a household, the head's name "&amp; family" and the trades of its
 * grown folk ("Farmer &amp; Miner"); "To let" when it stands empty; a player's name on a player's house it lives in itself.
 * Under them the house's number and its street, as the sign by the door always gave them (and a number already up is kept
 * if the house is off the plan's streets). It is the town's own sign (TownLife.addressSign: a wall sign of the town's wood,
 * beside the door at head height, on the wall and never on a window), seen to on the town's rounds one building at a time,
 * so a move, a sale, a new trade or a death is on it by the next round, and a sign knocked down is put back.
 */
public final class HouseSigns {

    private HouseSigns() {}

    /** What fits on a line of a sign. */
    static final int WIDE = 15;

    /** The homes that carry their household's name (the leader's hall has its own name over its door). */
    public static boolean named(String structure) {
        String s = structure.endsWith("_tall") ? structure.substring(0, structure.length() - 5) : structure;
        return s.equals("house") || s.equals("house2") || s.equals("manor") || s.equals("villa");
    }

    /**
     * The four lines of a home's sign: who, their trade, the number and the street. {@code where} is the house's address
     * (TownLife.address), null off the streets; {@code was} what the sign says now, if there is one, so a number already
     * up is kept. Null if the household is on the books but none of it is about to be named (left as it is).
     */
    @Nullable
    public static String[] lines(UUID village, Ledger.Building b, @Nullable String[] where, @Nullable String[] was) {
        String number = where != null ? where[0] : kept(was);
        String street = where != null ? where[1] : "";
        Homes.Household h = Homes.householdAt(village, b.anchor());
        String who, trade;
        if (h == null || h.vacant()) {
            who = "To let";
            trade = "";
        } else if (h.folk().isEmpty()) {
            if (h.player() && h.members() == 0) {
                who = fit(h.landlord());
                trade = "";
            } else {
                return null;                        // its folk are away (their part of the world asleep): as it was
            }
        } else {
            List<VillageFolkEntity> grown = new ArrayList<>();
            for (VillageFolkEntity f : h.folk()) if (!f.isBaby()) grown.add(f);
            VillageFolkEntity head = grown.isEmpty() ? h.folk().get(0) : grown.get(0);
            String name = head.displayNameCap();
            who = h.members() > 1 && (name + " & family").length() <= WIDE ? name + " & family" : fit(name);
            trade = trade(head);
            for (VillageFolkEntity f : grown) {
                if (f == head || trade(f).isEmpty() || trade(f).equals(trade(head))) continue;
                String both = trade.isEmpty() ? trade(f) : trade + " & " + trade(f);
                if (both.length() <= WIDE) trade = both;
                break;
            }
            trade = fit(trade);
        }
        return new String[]{ who, trade, number == null ? "" : number, street };
    }

    /** A folk's trade, as a sign gives it: nothing for one with none yet. */
    private static String trade(VillageFolkEntity f) {
        String t = f.tradeTitle();
        return t == null || t.equals("Unassigned") ? "" : t;
    }

    /** A number already on the sign ("No. 12"), from any of its lines. */
    @Nullable
    private static String kept(@Nullable String[] was) {
        if (was == null) return null;
        for (String l : was) if (l != null && l.startsWith("No. ")) return l;
        return null;
    }

    /** A line cut to the sign's width, with an ellipsis where it was cut. */
    private static String fit(String s) {
        return s.length() <= WIDE ? s : s.substring(0, WIDE - 1) + "\u2026";
    }

    /** A wall sign of the town's own wood (its walls' planks), spruce if there is no such sign. */
    public static Block signOf(UUID village) {
        String wood = Palettes.of(village).walls();
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_wall_sign"));
        return b instanceof WallSignBlock ? b : Blocks.SPRUCE_WALL_SIGN;
    }

    /** A fence of the town's own wood, for a lamp's post. */
    public static Block fenceOf(UUID village) {
        String wood = Palettes.of(village).walls();
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_fence"));
        return b instanceof net.minecraft.world.level.block.FenceBlock ? b : Blocks.SPRUCE_FENCE;
    }

    /** Homes whose household changed (Homes.save), by village: their signs are seen to first on the next round. */
    private static final java.util.Map<UUID, java.util.Set<Long>> CHANGED = new java.util.concurrent.ConcurrentHashMap<>();
    /** How many changed homes' signs one round sees to (the rest wait for the next round, ten seconds on). */
    static final int PER_ROUND = 4;

    /** A household moved in or out of this home, bought it or let it: its sign wants seeing to. */
    public static void changed(UUID village, net.minecraft.core.BlockPos anchor) {
        CHANGED.computeIfAbsent(village, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(anchor.asLong());
    }

    /** One round (TownLife.tick): the signs of the homes that changed, a few at a time, out of the stores. */
    static void round(net.minecraft.server.level.ServerLevel level, Villages.Village v) {
        java.util.Set<Long> due = CHANGED.get(v.id());
        if (due == null || due.isEmpty()) return;
        java.util.Map<Long, Ledger.Building> homes = new java.util.HashMap<>();
        for (Ledger.Building b : Ledger.buildings(v.id())) if (named(b.structure())) homes.put(b.anchor().asLong(), b);
        int n = 0;
        for (Long key : List.copyOf(due)) {
            if (n >= PER_ROUND) break;
            Ledger.Building b = homes.get(key);
            if (b == null) { due.remove(key); continue; }      // a flat, or gone: not a home with a sign of its own
            if (!level.isLoaded(b.anchor())) continue;           // its ground asleep: when it wakes
            due.remove(key);
            TownLife.addressSign(level, v.id(), v.centre(), b, v, false);
            n++;
        }
    }

    public static void resetForTests() {
        CHANGED.clear();
    }

    /** A window, which a sign never goes on: glass, a pane. */
    public static boolean window(BlockState st) {
        return st.getBlock() instanceof IronBarsBlock || st.getBlock() instanceof TransparentBlock;
    }
}
