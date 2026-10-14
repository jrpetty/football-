package com.jrpetty.mcassistant.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [wagons] A wagon as a passer-by meets it: right-click for its manifest (whose it is, from where to where, what is
 * aboard and what it is worth at its town's prices, its errand, its driver, guards and animal); sneak and use it to
 * buy a lot of a caravan's own goods off it on the road (a shipment's or a deal's are spoken for). The coin goes into
 * the carrier's purse and home with the trip. Also the few words the client is sent for the load (summary).
 */
public final class WagonManifest {

    private WagonManifest() {}

    /** Right-click: the manifest (from, to, what is aboard, its worth, who drives); sneaking, buy a lot of the load. */
    static void interact(ServerLevel level, TradeWagon w, Player p) {
        if (p.isShiftKeyDown()) {
            p.displayClientMessage(buy(level, w, p), false);
            return;
        }
        for (Component line : manifest(level, w)) p.displayClientMessage(line, false);
    }

    /** The manifest, a line at a time. */
    public static List<Component> manifest(ServerLevel level, TradeWagon w) {
        List<Component> out = new ArrayList<>();
        String town = w.town == null ? "A town" : Villages.name(w.town);
        String where = switch (w.phase) {
            case PARKED -> "standing in its yard";
            case FETCH -> "being made ready";
            case LOADING -> "being loaded";
            case UNLOADING -> "being unloaded";
            default -> {
                UUID going = w.back ? w.from : w.to;
                UUID leaving = w.back ? w.to : w.from;
                yield going == null ? "on the road" : "from " + Villages.name(leaving) + " to " + Villages.name(going) + (w.back ? ", homeward" : "");
            }
        };
        out.add(Component.literal(town + "'s " + w.kind().label).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal("  " + where).withStyle(ChatFormatting.GRAY)));
        Map<Item, Integer> load = loadByItem(w);
        if (load.isEmpty()) {
            out.add(Component.literal("Aboard: nothing").withStyle(ChatFormatting.GRAY));
        } else {
            MutableComponent goods = Component.literal("Aboard: ").withStyle(ChatFormatting.GRAY);
            int i = 0, pieces = 0;
            for (Map.Entry<Item, Integer> e : load.entrySet()) {
                pieces += e.getValue();
                if (i++ > 0) goods.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                goods.append(Component.literal(e.getValue() + " " + new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(java.util.Locale.ROOT))
                    .withStyle(ChatFormatting.WHITE));
            }
            goods.append(Component.literal(" (" + pieces + " in all, " + w.realFill() + "% full)").withStyle(ChatFormatting.DARK_GRAY));
            out.add(goods);
            UUID priced = w.town;
            out.add(Component.literal("Worth about " + worth(level, priced, w) + " coins at " + (priced == null ? "market" : Villages.name(priced))
                + "'s prices").withStyle(ChatFormatting.GRAY));
        }
        String what = w.shipment != null ? "a load sent to order" : w.phase == TradeWagon.Phase.PARKED ? "" : errandWords(level, w);
        StringBuilder crew = new StringBuilder();
        VillageFolkEntity d = Wagons.driver(level, w);
        if (d != null) crew.append("Driver: ").append(d.displayNameCap());
        List<VillageFolkEntity> gs = Wagons.guards(level, w);
        if (!gs.isEmpty()) {
            crew.append(crew.length() > 0 ? "; " : "").append(gs.size() == 1 ? "guard: " : "guards: ");
            for (int i = 0; i < gs.size(); i++) crew.append(i > 0 ? ", " : "").append(gs.get(i).displayNameCap());
        }
        if (w.animal != null) crew.append(crew.length() > 0 ? "; " : "").append("pulled by ").append(Wagons.animalWord(level, w));
        if (!what.isEmpty() || crew.length() > 0) {
            out.add(Component.literal((what.isEmpty() ? "" : what.substring(0, 1).toUpperCase() + what.substring(1) + ". ") + crew)
                .withStyle(ChatFormatting.GRAY));
        }
        Component sale = forSale(level, w);
        if (sale != null) out.add(sale);
        return out;
    }

    private static String errandWords(ServerLevel level, TradeWagon w) {
        VillageFolkEntity d = Wagons.driver(level, w);
        Caravans.Trip t = d == null ? null : d.trip();
        if (t == null) return "";
        if (t.deal != null) return "a delivery by a trade deal";
        return t.trade ? "a trade caravan's goods" : "a caravan between a town and its colony";
    }

    /** What a passer-by may buy off it, or why not: only a caravan's own goods out on the road, never a deal's or an order's. */
    @Nullable
    private static Component forSale(ServerLevel level, TradeWagon w) {
        ItemStack lot = lotForSale(level, w);
        if (lot == null) {
            if (w.realFill() > 0 && w.phase == TradeWagon.Phase.ROAD) {
                return Component.literal("Spoken for: not for sale.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
            }
            return null;
        }
        int price = Market.askLot(level, w.town, lot, lot.getCount());
        return Component.literal("Sneak and use it to buy " + lot.getCount() + " " + lot.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
            + " for " + price + " coins.").withStyle(ChatFormatting.GREEN);
    }

    /** The lot a passer-by may buy: a bundle of the most plentiful good aboard a caravan's own wagon on the road. */
    @Nullable
    private static ItemStack lotForSale(ServerLevel level, TradeWagon w) {
        if (w.shipment != null || w.phase != TradeWagon.Phase.ROAD || w.town == null) return null;
        VillageFolkEntity d = Wagons.driver(level, w);
        Caravans.Trip t = d == null ? null : d.trip();
        if (t == null || t.deal != null || t.errand != null) return null;
        ItemStack best = null;
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.getItem(i);
            if (s.isEmpty() || Crates.isCrate(s) || Market.goodFor(s) == null) continue;
            if (best == null || s.getCount() > best.getCount()) best = s;
        }
        if (best == null) return null;
        Market.Good g = Market.goodFor(best);
        int n = Math.min(best.getCount(), Math.max(1, g == null ? 8 : g.bundle()));
        return best.copyWithCount(n);
    }

    /** Sneak-use: the lot bought, the coin into the carrier's purse (it goes home with the trip and is booked there). */
    static Component buy(ServerLevel level, TradeWagon w, Player p) {
        ItemStack lot = lotForSale(level, w);
        if (lot == null) return Component.literal("Nothing aboard is for sale.").withStyle(ChatFormatting.GRAY);
        int price = Market.askLot(level, w.town, lot, lot.getCount());
        if (!Civics.takeCoins(p, price)) {
            return Component.literal("That's " + price + " coins, and you haven't got them.").withStyle(ChatFormatting.RED);
        }
        int need = lot.getCount();
        for (int i = 0; i < w.cargo.getContainerSize() && need > 0; i++) {
            ItemStack s = w.cargo.getItem(i);
            if (!ItemStack.isSameItemSameComponents(s, lot)) continue;
            int k = Math.min(need, s.getCount());
            s.shrink(k);
            need -= k;
        }
        ItemStack bought = lot.copyWithCount(lot.getCount() - need);
        if (!p.getInventory().add(bought)) p.drop(bought, false);
        VillageFolkEntity d = Wagons.driver(level, w);
        if (d != null && d.trip() != null) {
            d.trip().purse += price;
            d.trip().earned += price;
            FolkTalk.speak(d, "A pleasure. Safe roads to you!");
        }
        w.cargoChanged();
        Villages.tell(w.town, level.getDayTime() / 24000L, "sold " + lot.getCount() + " " + lot.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
            + " off the " + w.kind().label + " on the road to " + p.getName().getString() + " for " + price + " coins");
        return Component.literal("Bought " + lot.getCount() + " " + lot.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " for " + price + " coins.")
            .withStyle(ChatFormatting.GREEN);
    }

    /** The load by item, most first. */
    static Map<Item, Integer> loadByItem(TradeWagon w) {
        Map<Item, Integer> m = new HashMap<>();
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.getItem(i);
            if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        List<Map.Entry<Item, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        Map<Item, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** What the load is worth at a town's asking prices (the market's own price desk). */
    static int worth(ServerLevel level, @Nullable UUID town, TradeWagon w) {
        if (town == null) return 0;
        int sum = 0;
        for (Map.Entry<Item, Integer> e : loadByItem(w).entrySet()) sum += Market.askLot(level, town, new ItemStack(e.getKey()), e.getValue());
        return sum;
    }

    /** The load for the client: "id*count;..." (most first, six at most). */
    static String summary(net.minecraft.world.Container cargo) {
        Map<Item, Integer> m = new HashMap<>();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack s = cargo.getItem(i);
            if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (m.isEmpty()) return "";
        List<Map.Entry<Item, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, es.size()); i++) {
            if (i > 0) sb.append(';');
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(es.get(i).getKey());
            sb.append(id).append('*').append(es.get(i).getValue());
        }
        return sb.toString();
    }

    /** The manifest as plain lines (tests, the command). */
    public static List<String> text(ServerLevel level, TradeWagon w) {
        List<String> out = new ArrayList<>();
        for (Component c : manifest(level, w)) out.add(c.getString());
        return out;
    }
}
