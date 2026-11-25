package com.jrpetty.mcassistant.pa;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * [pa38] Its sense for a villager's trades, growing with its level. The prices are always the game's own: what a
 * villager asks (the base price, demand, and the discounts the game gives), never one it made up. What grows is its
 * judgement: which offer is worth having ("trade with that villager"), which of the villagers nearby sells it for less
 * (one at first, ten by level 10), which offers are bargains (a discount; from level 6 an enchanted book worth more than
 * its price), why a price is up (demand, which eases when the villager restocks), and, from level 7, the cure: a zombie
 * villager given weakness and a golden apple, which the game repays with lasting discounts. It trades with its
 * player's emeralds, for its player, so the villager prices it as it would for its player (its player's reputation
 * there and the Hero of the Village, the game's own sums), and it says when that has saved them something.
 */
public final class PaTrade {

    private PaTrade() {}

    // ------------------------------------------------------------------ in game: what an offer is worth

    /** What a result is worth to an expert, in emeralds: its usual price, more for a book or gear with the good enchantments. */
    static double worth(MerchantOffer o) {
        ItemStack r = o.getResult();
        double fair = Math.max(1, o.getBaseCostA().getCount() * (o.getBaseCostA().is(Items.EMERALD) ? 1.0 : 0.15));
        ItemEnchantments e = r.is(Items.ENCHANTED_BOOK) ? r.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY)
            : r.getEnchantments();
        double extra = 0;
        for (var en : e.entrySet()) {
            String id = en.getKey().unwrapKey().map(k -> k.location().getPath()).orElse("");
            int lvl = en.getIntValue();
            extra += switch (id) {
                case "mending" -> 30;
                case "protection", "sharpness", "efficiency", "power" -> lvl >= 4 ? 18 : 6;
                case "unbreaking", "fortune", "looting" -> lvl >= 3 ? 16 : 5;
                case "silk_touch", "infinity", "feather_falling" -> 12;
                default -> 3;
            };
        }
        return fair + extra;
    }

    static String name(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace('_', ' ');
    }

    // ------------------------------------------------------------------ in game: its player's prices

    /**
     * The villager's prices as its player would be offered them (the game's own: its player's reputation in that village,
     * and the Hero of the Village if they have it), for the trades it makes for them. Undone by {@link #restore}.
     */
    static int ownerPrices(PlayerAssistantEntity a, Villager v) {
        UUID owner = a.getOwnerId();
        if (owner == null) return 0;
        int rep = v.getGossips().getReputation(owner, t -> true);
        int saved = 0;
        for (MerchantOffer o : v.getOffers()) {
            int before = o.getCostA().getCount();
            if (rep != 0) o.addToSpecialPriceDiff(-Mth.floor(rep * o.getPriceMultiplier()));
            Player p = a.getOwnerPlayer();
            MobEffectInstance hero = p == null ? null : p.getEffect(MobEffects.HERO_OF_THE_VILLAGE);
            if (hero != null) {
                double d = 0.3 + 0.0625 * hero.getAmplifier();
                o.addToSpecialPriceDiff(-Math.max((int) Math.floor(d * o.getBaseCostA().getCount()), 1));
            }
            saved += Math.max(0, before - o.getCostA().getCount());
        }
        return saved;
    }

    static void restore(Villager v) {
        for (MerchantOffer o : v.getOffers()) o.resetSpecialPriceDiff();
    }

    // ------------------------------------------------------------------ in game: comparing villagers

    /** The villagers near this one it would check (as many as its level lets it), this one first. */
    static List<Villager> compared(PlayerAssistantEntity a, Villager first) {
        List<Villager> out = new ArrayList<>();
        out.add(first);
        int n = PaKnack.villagersCompared(a.veteranLevel());
        if (n <= 1) return out;
        List<Villager> near = a.level().getEntitiesOfClass(Villager.class, first.getBoundingBox().inflate(16), v -> v != first && v.isAlive() && !v.isBaby() && !v.isSleeping());
        near.sort((x, y) -> Double.compare(x.distanceToSqr(first), y.distanceToSqr(first)));
        for (Villager v : near) { if (out.size() >= n) break; if (v.getTradingPlayer() == null) out.add(v); }
        return out;
    }

    /** The offer at this villager that sells (or buys) the thing: the first not sold out, or null. */
    @Nullable
    static MerchantOffer offerFor(Villager v, boolean sell, net.minecraft.world.item.Item want) {
        for (MerchantOffer o : v.getOffers()) {
            if (o.isOutOfStock()) continue;
            boolean match = sell ? o.getResult().is(Items.EMERALD) && (want == Items.AIR || o.getCostA().is(want))
                : !o.getResult().is(Items.EMERALD) && (want == Items.AIR || o.getResult().is(want));
            if (match) return o;
        }
        return null;
    }

    /**
     * Before trading: which villager and offer it settles on, and what it notices (said now, once): a cheaper villager
     * nearby, a bargain, a price up from demand, what its player's standing saved. Returns the chosen villager.
     */
    static Villager settle(PlayerAssistantEntity a, Villager first, boolean sell, net.minecraft.world.item.Item want, Random r) {
        int lv = a.veteranLevel();
        List<Villager> vs = compared(a, first);
        int[] prices = new int[vs.size()];
        for (int i = 0; i < vs.size(); i++) {
            MerchantOffer o = offerFor(vs.get(i), sell, want);
            prices[i] = o == null ? 0 : o.getCostA().getCount();            // buying: emeralds asked; selling: items asked for an emerald
        }
        int k = PaTradeSense.cheapest(lv, prices, r);
        Villager pick = k < 0 ? first : vs.get(k);
        if (k > 0 && prices[0] > 0 && !sell && prices[0] - prices[k] >= 2) {
            PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.trade_cheaper", "n", String.valueOf(prices[0] - prices[k]), "item", want == Items.AIR ? "it" : name(new ItemStack(want))), true);
        } else if (k > 0 && prices[0] > 0 && prices[0] - prices[k] < 2) pick = first;           // not worth the walk
        return pick;
    }

    /** What it notices about the offers here once its player's prices are on (said once): a bargain, or demand. */
    static void remark(PlayerAssistantEntity a, Villager v, @Nullable MerchantOffer chosen, int saved) {
        int lv = a.veteranLevel();
        PaField f = a.mind.field;
        if (saved > 0 && lv >= PaKnack.CURE_LEVEL - 2) {
            PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.trade_standing", "n", String.valueOf(saved)), true);
            return;
        }
        for (MerchantOffer o : v.getOffers()) {
            if (o.isOutOfStock() || o.getResult().is(Items.EMERALD)) continue;
            PaTradeSense.Seen s = new PaTradeSense.Seen(o.getCostA().getCount(), o.getBaseCostA().getCount(), worth(o));
            if (PaTradeSense.bargain(lv, s) && s.price() <= s.base()) {
                f.bargains++;
                PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.trade_bargain", "item", name(o.getResult()), "n", String.valueOf(s.price())), true);
                return;
            }
        }
        if (chosen != null && PaKnack.knowsDemand(lv) && chosen.getDemand() > 0 && chosen.getCostA().getCount() > chosen.getBaseCostA().getCount())
            PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.trade_demand", "item", name(chosen.getResult()), "n", String.valueOf(chosen.getCostA().getCount() - chosen.getBaseCostA().getCount())), true);
    }

    /** "Trade with that villager": the offer it judges best value that it can pay for, or null. */
    @Nullable
    static MerchantOffer bestBuy(PlayerAssistantEntity a, Villager v, Random r) {
        List<MerchantOffer> can = new ArrayList<>();
        List<PaTradeSense.Seen> seen = new ArrayList<>();
        for (MerchantOffer o : v.getOffers()) {
            if (o.isOutOfStock() || o.getResult().is(Items.EMERALD)) continue;
            ItemStack ca = o.getCostA();
            if (a.countMatching(s -> s.is(ca.getItem())) < ca.getCount()) continue;
            can.add(o);
            seen.add(new PaTradeSense.Seen(ca.getCount(), o.getBaseCostA().getCount(), worth(o)));
        }
        int k = PaTradeSense.choose(a.veteranLevel(), seen, r);
        return k < 0 ? null : can.get(k);
    }

    // ------------------------------------------------------------------ the cure (level 7)

    /** A zombie villager it is curing for its player: never a target while the cure is under way. */
    static boolean curing(PlayerAssistantEntity a, @Nullable LivingEntity t) {
        if (!(t instanceof ZombieVillager zv)) return false;
        return zv.isConverting() || zv.getUUID().equals(a.mind.field.cureOrder);
    }

    private static java.lang.reflect.Method START;

    /**
     * The golden apple given (it has weakness): the game's cure begins (three to five minutes), started in its player's
     * name, so the game's thanks (the discount it gives whoever cured the villager) is theirs, as if they had fed it.
     */
    static boolean startCure(PlayerAssistantEntity a, ZombieVillager zv) {
        UUID owner = a.getOwnerId();
        if (owner == null || !zv.hasEffect(MobEffects.WEAKNESS) || zv.isConverting()) return false;
        try {
            if (START == null) {
                java.lang.reflect.Method m = ZombieVillager.class.getDeclaredMethod("startConverting", UUID.class, int.class);
                m.setAccessible(true);
                START = m;
            }
            START.invoke(zv, owner, zv.getRandom().nextInt(2401) + 3600);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** A splash potion of weakness in its pack (the cure's first half), or EMPTY. */
    static ItemStack weakness(PlayerAssistantEntity a) {
        for (ItemStack s : a.getInventoryItems()) {
            if (!s.is(Items.SPLASH_POTION)) continue;
            var pc = s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (pc != null && pc.potion().isPresent() && (pc.potion().get().is(net.minecraft.world.item.alchemy.Potions.WEAKNESS)
                || pc.potion().get().is(net.minecraft.world.item.alchemy.Potions.LONG_WEAKNESS))) return s;
        }
        return ItemStack.EMPTY;
    }

    static boolean ownerOnline(PlayerAssistantEntity a) {
        return a.getOwnerId() != null && a.level() instanceof ServerLevel sl && sl.getServer().getPlayerList().getPlayer(a.getOwnerId()) != null;
    }
}
