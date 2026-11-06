package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [shops-homes] The smith's anvil and the tailor's table: mending what you carry (PlayerServices.mend: the metal or
 * the leather out of the stores at the market's price, a unit a quarter of the wear, and the fee), a piece made to
 * order (Dealings.commission: paid now, ready tomorrow, as good as the hand that makes it), collecting it, and the
 * anvil or loom to use yourself.
 */
final class MakerDesk implements ServicePoints.Desk {

    static final MakerDesk INSTANCE = new MakerDesk();

    private MakerDesk() {}

    /** What each will make to order, the best the age and the hand allow shown, the rest left off. */
    private static final List<Item> SMITH_MAKES = List.of(Items.IRON_SWORD, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL,
        Items.IRON_HOE, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.SHIELD, Items.SHEARS,
        Items.BUCKET, Items.LANTERN, Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_CHESTPLATE);
    private static final List<Item> TAILOR_MAKES = List.of(Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS,
        Items.LEATHER_BOOTS, Items.WHITE_BED, Items.WHITE_BANNER, Items.WHITE_CARPET, Items.LEAD, Items.BOOK);

    @Override
    public String kind(ServiceRole role) {
        return "desk";
    }

    @Override
    public int fingerprint(ServicePoints.Visit visit) {
        ServerPlayer p = visit.player();
        if (p == null) return 0;
        int h = Market.coinsHeld(p);
        for (ItemStack s : p.getInventory().items) if (s.isDamaged()) h = h * 31 + s.getDamageValue();
        Dealings.Order o = Dealings.orderOf(p.getUUID(), visit.village().id());
        return h * 7 + (o == null ? 0 : o.item().hashCode());
    }

    /** Is this the tailor's to mend (leather, cloth), rather than the smith's? */
    private static boolean tailors(ItemStack s) {
        return s.getItem().isValidRepairItem(s, new ItemStack(Items.LEATHER)) || s.getItem().isValidRepairItem(s, new ItemStack(Items.STRING))
            || s.getItem().isValidRepairItem(s, new ItemStack(Items.PHANTOM_MEMBRANE));
    }

    @Override
    public CompoundTag body(ServicePoints.Visit visit, String tab) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        VillageFolkEntity k = visit.keeper();
        boolean tailor = visit.role() == ServiceRole.TAILOR;
        DeskRows d = new DeskRows(level.registryAccess());
        if (p == null) return d.build();
        d.section("Mend what you carry");
        int worn = 0;
        List<ItemStack> items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || !s.isDamaged() || tailors(s) != tailor) continue;
            worn++;
            String quote = k == null ? "Nobody at the " + (tailor ? "table" : "anvil") + " just now." : PlayerServices.mendQuote(level, visit.village(), k, p, s);
            boolean ok = quote.startsWith("QUOTE|");
            String[] q = ok ? quote.split("\\|", 3) : null;
            int wear = Math.round(100F * s.getDamageValue() / Math.max(1, s.getMaxDamage()));
            CompoundTag r = d.row("mend", "Mend", Goods.name(s), (ok ? q[2] : "") + " · worn " + wear + "%, done while you wait", s,
                ok ? (Market.coinsHeld(p) < Integer.parseInt(q[1]) ? "That's " + q[1] + " coins; you've " + Market.coinsHeld(p) + "." : "") : quote);
            r.getCompound("args").putInt("slot", i);
            r.getCompound("args").putString("id", Goods.lineId(s));
        }
        if (worn == 0) d.line(tailor ? "Nothing of leather or cloth on you wants mending." : "Nothing of metal on you wants mending.");
        Dealings.Order mine = Dealings.orderOf(p.getUUID(), id);
        d.section("Made to order");
        if (mine != null) {
            long day = level.getDayTime() / 24000L;
            boolean ready = day >= mine.ready();
            d.row("collect", "Collect", "Your " + Goods.name(new ItemStack(mine.item())).toLowerCase(Locale.ROOT), (ready ? "Ready now" : "Ready tomorrow")
                + ", by " + mine.maker() + ".", new ItemStack(mine.item()), ready ? "" : mine.maker() + " is still at it: come back tomorrow.");
        } else {
            d.line("Paid now, ready tomorrow, as good as the hand that makes it. The makings come out of your pack first, then the stores.");
            int skill = k == null ? 0 : k.veteranLevel();
            for (Item it : tailor ? TAILOR_MAKES : SMITH_MAKES) {
                String no = Workshop.refuse(level, visit.village(), it);
                if (!no.isEmpty()) continue;
                boolean able = Craftsmanship.canMake(skill, it);
                CompoundTag r = d.row("commission", "Order", Goods.name(new ItemStack(it)), "Ready tomorrow", new ItemStack(it),
                    k == null ? "Nobody here to make it just now." : able ? "" : "Beyond " + k.displayNameCap() + "'s hand yet.");
                r.getCompound("args").putString("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString());
            }
        }
        if (visit.point() != null) {
            d.section("At the bench");
            d.row("use", "Use it", tailor ? "The loom, yourself" : "The anvil, yourself", "Crouch and click it next time to go straight to it.",
                new ItemStack(tailor ? Items.LOOM : Items.ANVIL), "");
        }
        return d.build();
    }

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerLevel level = visit.level();
        ServerPlayer p = visit.player();
        VillageFolkEntity k = visit.keeper();
        if (p == null) return ServicePoints.Result.no("");
        if (action.equals("use")) return ServicePoints.useItYourself(visit);
        if (k == null) return ServicePoints.Result.no("Nobody at the " + (visit.role() == ServiceRole.TAILOR ? "table" : "anvil") + " just now.");
        switch (action) {
            case "mend" -> {
                int slot = args.getInt("slot");
                List<ItemStack> items = p.getInventory().items;
                if (slot < 0 || slot >= items.size()) return ServicePoints.Result.no("That's gone from your pack.");
                ItemStack s = items.get(slot);
                if (s.isEmpty() || !Goods.lineId(s).equals(args.getString("id")) || !s.isDamaged()) return ServicePoints.Result.no("That's gone from your pack.");
                int before = s.getDamageValue();
                String said = PlayerServices.mend(level, visit.village(), k, p, s, false);
                FolkTalk.speak(k, said);
                return s.getDamageValue() < before ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            case "commission" -> {
                Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(args.getString("item")));
                if (it == Items.AIR) return ServicePoints.Result.no("");
                int held = Market.coinsHeld(p);
                String said = Dealings.commission(level, k, p, it);
                FolkTalk.speak(k, said);
                return Market.coinsHeld(p) < held ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            case "collect" -> {
                boolean had = Dealings.orderOf(p.getUUID(), visit.village().id()) != null;
                String said = Dealings.order(k, p, "");
                boolean got = had && Dealings.orderOf(p.getUUID(), visit.village().id()) == null;
                return got ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            default -> {
                return ServicePoints.Result.no("");
            }
        }
    }
}
