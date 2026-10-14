package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;

/**
 * [roles-b] The brewer's stand and the enchanter's table, as service points (ServiceRole.BREWERY, ENCHANTER): every
 * potion the brewer brews, bought from the stores or brewed to order and collected (CraftServices.orderOrSell); every
 * thing in the player's pack the enchanter can better, with its price and its spell (CraftServices.enchantHeld); and the
 * stand or the table to use yourself. The prices and the reasons are the talk's own, worked out the one way.
 */
final class CraftDesk implements ServicePoints.Desk {

    static final CraftDesk INSTANCE = new CraftDesk();

    private CraftDesk() {}

    @Override
    public String kind(ServiceRole role) {
        return "desk";
    }

    @Override
    public int fingerprint(ServicePoints.Visit visit) {
        ServerPlayer p = visit.player();
        if (p == null) return 0;
        int h = Market.coinsHeld(p);
        for (ItemStack s : p.getInventory().items) h = h * 31 + (s.isEmpty() ? 0 : s.getItem().hashCode() + s.getCount());
        h = h * 7 + (CraftServices.hasPotionOrder(visit.village().id(), p.getUUID()) ? 1 : 0);
        h = h * 7 + Market.stock(visit.level(), visit.village().id(), s -> s.is(Items.POTION) || s.is(Items.LAPIS_LAZULI) || s.is(Items.BOOK));
        return h;
    }

    @Override
    public CompoundTag body(ServicePoints.Visit visit, String tab) {
        DeskRows d = new DeskRows(visit.level().registryAccess());
        ServerPlayer p = visit.player();
        if (p == null) return d.build();
        if (visit.role() == ServiceRole.BREWERY) brewery(visit, p, d);
        else enchanter(visit, p, d);
        if (visit.point() != null) {
            boolean stand = visit.role() == ServiceRole.BREWERY;
            d.section("At the bench");
            d.row("use", "Use it", stand ? "The brewing stand, yourself" : "The enchanting table, yourself",
                "Crouch and click it next time to go straight to it.", new ItemStack(stand ? Items.BREWING_STAND : Items.ENCHANTING_TABLE), "");
        }
        return d.build();
    }

    private static void brewery(ServicePoints.Visit visit, ServerPlayer p, DeskRows d) {
        ServerLevel level = visit.level();
        VillageFolkEntity k = visit.keeper();
        if (CraftServices.hasPotionOrder(visit.village().id(), p.getUUID())) {
            d.section("Your order");
            d.row("collect", "Collect", "Your potion", "Handed over once it's off the stand; your coin back after "
                + CraftServices.ORDER_DAYS + " days if it never comes right.", new ItemStack(Items.POTION), k == null ? "Nobody at the stand just now." : "");
            return;
        }
        d.section("Potions");
        d.line("From the stores if they have one to spare; else brewed to order from the stores' makings, paid now, the next off the stand.");
        for (Crafts.Brew b : Crafts.brews(visit.village())) {
            String quote = k == null ? "Nobody at the stand just now." : CraftServices.orderOrSell(level, visit.village(), k, p, b, true);
            boolean ok = quote.startsWith("QUOTE|");
            String[] q = ok ? quote.split("\\|", 3) : null;
            String why = ok ? (Market.coinsHeld(p) < Integer.parseInt(q[1]) ? "That's " + q[1] + " coins; you've " + Market.coinsHeld(p) + "." : "") : quote;
            CompoundTag r = d.row("potion", ok && q[2].contains("stores") ? "Buy" : "Order", CraftServices.nameOf(b.potion()), ok ? q[2] : "",
                PotionContents.createItemStack(Items.POTION, b.potion()), why);
            r.getCompound("args").putString("potion", CraftServices.path(b.potion()));
        }
    }

    private static void enchanter(ServicePoints.Visit visit, ServerPlayer p, DeskRows d) {
        ServerLevel level = visit.level();
        VillageFolkEntity k = visit.keeper();
        d.section("Enchant what you carry");
        d.line("Three lapis and a book from the stores at the market's price (else your own), and " + CraftServices.ENCHANT_FEE
            + " coins a rank of the work. The shelves round the table and the enchanter's hand set how strong.");
        int shown = 0;
        List<ItemStack> items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || Crafts.spellFor(s, 1, false).isEmpty()) continue;
            shown++;
            String quote = k == null ? "Nobody at the table just now." : CraftServices.enchantHeld(level, visit.village(), k, p, s, true);
            boolean ok = quote.startsWith("QUOTE|");
            String[] q = ok ? quote.split("\\|", 3) : null;
            String why = ok ? (Market.coinsHeld(p) < Integer.parseInt(q[1]) ? "That's " + q[1] + " coins; you've " + Market.coinsHeld(p) + "." : "") : quote;
            CompoundTag r = d.row("enchant", "Enchant", Goods.name(s), ok ? q[2] : "", s, why);
            r.getCompound("args").putInt("slot", i);
            r.getCompound("args").putString("id", Goods.lineId(s));
        }
        if (shown == 0) d.line("Nothing on you to enchant: iron, diamond or netherite tools, blades and armour, or a bow.");
    }

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerLevel level = visit.level();
        ServerPlayer p = visit.player();
        VillageFolkEntity k = visit.keeper();
        if (p == null) return ServicePoints.Result.no("");
        if (action.equals("use")) return ServicePoints.useItYourself(visit);
        if (k == null) return ServicePoints.Result.no("Nobody here just now.");
        int held = Market.coinsHeld(p);
        switch (action) {
            case "potion" -> {
                Crafts.Brew b = null;
                for (Crafts.Brew x : Crafts.brews(visit.village())) if (CraftServices.path(x.potion()).equals(args.getString("potion"))) b = x;
                if (b == null) return ServicePoints.Result.no("");
                String said = CraftServices.orderOrSell(level, visit.village(), k, p, b, false);
                FolkTalk.speak(k, said);
                return Market.coinsHeld(p) < held ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            case "collect" -> {
                String said = CraftServices.potion(k, p, "");
                boolean got = !CraftServices.hasPotionOrder(visit.village().id(), p.getUUID());
                return got ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            case "enchant" -> {
                int slot = args.getInt("slot");
                List<ItemStack> items = p.getInventory().items;
                if (slot < 0 || slot >= items.size()) return ServicePoints.Result.no("That's gone from your pack.");
                ItemStack s = items.get(slot);
                if (s.isEmpty() || !Goods.lineId(s).equals(args.getString("id"))) return ServicePoints.Result.no("That's gone from your pack.");
                String said = CraftServices.enchantHeld(level, visit.village(), k, p, s, false);
                FolkTalk.speak(k, said);
                return Market.coinsHeld(p) < held ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            default -> {
                return ServicePoints.Result.no("");
            }
        }
    }
}
