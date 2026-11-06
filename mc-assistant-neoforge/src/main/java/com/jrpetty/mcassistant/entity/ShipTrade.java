package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [trade-world] Trading with a merchant ship's captain at the game's own trading table: on the left what is in the hold, a
 * lot for so many village coins, and what the ship will buy (its land's wants) for coin out of the ship's purse. Real coin
 * and goods move: the coins you pay go into the ship's purse, the coins it pays come out of it, a lot bought comes out of
 * the hold and is gone from it, and what you sell goes aboard and away with the ship. Every price is the town's own
 * (PriceIndex), the far land's goods at their markup. The table shuts when the ship is called aboard, or you walk off.
 */
final class ShipTrade implements Merchant {

    /** What an offer on the table is: the ship selling a lot out of its hold, or buying so many of a thing for coin. */
    record Ref(boolean sell, int lot, String item, int n, int coins) {}

    private final UUID town;
    private final UUID captain;
    @Nullable private Player trading;
    private MerchantOffers offers = new MerchantOffers();
    private final List<Ref> refs = new ArrayList<>();

    /** The tables open now, by player. */
    private static final Map<UUID, ShipTrade> OPEN = new ConcurrentHashMap<>();

    private ShipTrade(UUID town, UUID captain) {
        this.town = town;
        this.captain = captain;
    }

    /** "Trade?" asked of the captain: the table opened. What the captain says. */
    static String open(ServerPlayer p, UUID town, VillageFolkEntity captain) {
        ShipCalls.Ship ship = ShipCalls.ship(town);
        if (ship == null || !ship.docked() || ship.called >= 0) return "We're making ready to sail, friend. Next time!";
        ShipTrade t = new ShipTrade(town, captain.getUUID());
        t.build(p.serverLevel(), ship);
        if (t.offers.isEmpty()) return "The hold's bare and the purse is empty. We sail soon.";
        t.setTradingPlayer(p);
        OPEN.put(p.getUUID(), t);
        t.openTradingScreen(p, Component.literal(ship.name + " of " + (ship.land() == null ? "over the sea" : ship.land().name())), 0);
        return FolkTalk.pick(captain.getRandom(), "Here's what's in the hold, and what we'll buy. Prices as fair as the town's own!",
            "Have a look. Coin for goods, goods for coin.");
    }

    /** The table laid: the hold's lots, then what the ship buys. */
    void build(ServerLevel level, ShipCalls.Ship ship) {
        offers = new MerchantOffers();
        refs.clear();
        ForeignShips.Land land = ship.land();
        for (int i = 0; i < ship.hold.size(); i++) {
            ShipCalls.Lot l = ship.hold.get(i);
            if (l.lotsLeft() <= 0) continue;
            int price = Math.max(1, Math.min(64, l.price));
            offers.add(new MerchantOffer(new ItemCost(McAssistantMod.VILLAGE_COIN.get(), price), Optional.empty(), l.one(land), 0,
                l.lotsLeft(), 0, 0.0F));
            refs.add(new Ref(true, i, l.item, l.lot, price));
        }
        if (land == null) return;
        for (Item want : land.wants()) {
            ItemStack one = new ItemStack(want);
            int n = Math.max(1, Math.min(16, one.getMaxStackSize() / 4));
            int coins = (int) Math.floor(PriceIndex.each(level, town, one) * n);
            if (coins < 1) {
                n = Math.min(one.getMaxStackSize(), n * 4);
                coins = (int) Math.floor(PriceIndex.each(level, town, one) * n);
            }
            coins = Math.min(64, coins);
            if (coins < 1) continue;
            int uses = Math.min(8, ship.purse / coins);
            if (uses <= 0) continue;
            offers.add(new MerchantOffer(new ItemCost(want, n), Optional.empty(), new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins), 0,
                uses, 0, 0.0F));
            refs.add(new Ref(false, -1, BuiltInRegistries.ITEM.getKey(want).toString(), n, coins));
        }
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        this.trading = player;
        if (player == null) OPEN.values().remove(this);
    }

    @Nullable
    @Override
    public Player getTradingPlayer() {
        return trading;
    }

    @Override
    public MerchantOffers getOffers() {
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers o) {
        // The server's table is the ship's own: nothing to take from the client.
    }

    /**
     * A trade made: the coins into the ship's purse and the lot out of its hold, or the goods aboard and the coins out of
     * its purse. Kept in the town's notes at once.
     */
    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
        int i = offers.indexOf(offer);
        ShipCalls.Ship ship = ShipCalls.ship(town);
        if (i < 0 || i >= refs.size() || ship == null) return;
        Ref r = refs.get(i);
        long day = trading == null ? 0 : trading.level().getDayTime() / 24000L;
        if (r.sell()) {
            if (r.lot() >= 0 && r.lot() < ship.hold.size()) {
                ShipCalls.Lot l = ship.hold.get(r.lot());
                l.count = Math.max(0, l.count - l.lot);
                ship.lotsSold++;
            }
            ship.purse += r.coins();
            ship.playerPaid += r.coins();
        } else {
            ship.purse = Math.max(0, ship.purse - r.coins());
            ship.playerGot += r.coins();
            ship.bought.merge(r.item(), r.n(), Integer::sum);
            // What else the ship would buy, as far as its purse goes now.
            for (int k = 0; k < refs.size(); k++) {
                Ref o = refs.get(k);
                if (!o.sell() && o.coins() > ship.purse) offers.get(k).setToOutOfStock();
            }
            if (trading instanceof ServerPlayer sp) {
                sp.sendMerchantOffers(sp.containerMenu.containerId, offers, 0, 0, false, false);
            }
        }
        ShipCalls.save(town, ship);
        if (trading instanceof ServerPlayer sp) Advancements.firstTrade(sp);
        if (trading != null && ship.playerPaid + ship.playerGot == r.coins()) {
            Villages.tell(town, day, trading.getName().getString() + " traded with " + ship.name + " on the quay");
        }
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    /** The ship is called aboard (or gone): every table at its harbour shut. */
    static void closeAll(UUID town) {
        for (ShipTrade t : new ArrayList<>(OPEN.values())) {
            if (!t.town.equals(town)) continue;
            Player p = t.trading;
            if (p != null && p.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu) p.closeContainer();
            t.setTradingPlayer(null);
        }
    }

    /** A player walked off from the captain (or the captain from the quay): the table shut. */
    static void closeFar(VillageFolkEntity captain) {
        for (ShipTrade t : new ArrayList<>(OPEN.values())) {
            if (!t.captain.equals(captain.getUUID())) continue;
            Player p = t.trading;
            if (p == null || p.distanceToSqr(captain) <= 12.0 * 12.0) continue;
            if (p.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu) p.closeContainer();
            t.setTradingPlayer(null);
        }
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the table a player would see at this town's ship: "sell glass 16 for 9", "buy wheat 16 for 3". */
    static List<String> tableForTests(ServerLevel level, UUID town) {
        List<String> out = new ArrayList<>();
        ShipCalls.Ship ship = ShipCalls.ship(town);
        if (ship == null) return out;
        ShipTrade t = new ShipTrade(town, new UUID(0, 0));
        t.build(level, ship);
        for (Ref r : t.refs) out.add((r.sell() ? "sell " : "buy ") + r.item() + " " + r.n() + " for " + r.coins());
        return out;
    }

    /** Tests: the table laid and its n-th offer taken, as the trading table takes it (the payment taken off, the goods given). */
    static boolean tradeForTests(ServerPlayer p, UUID town, int n) {
        ShipCalls.Ship ship = ShipCalls.ship(town);
        if (ship == null) return false;
        ShipTrade t = new ShipTrade(town, new UUID(0, 0));
        t.build(p.serverLevel(), ship);
        if (n < 0 || n >= t.offers.size()) return false;
        MerchantOffer o = t.offers.get(n);
        ItemStack cost = o.getCostA();
        int have = 0;
        for (ItemStack s : p.getInventory().items) if (ItemStack.isSameItem(s, cost)) have += s.getCount();
        if (have < cost.getCount() || o.isOutOfStock()) return false;
        int need = cost.getCount();
        for (ItemStack s : p.getInventory().items) {
            if (need <= 0) break;
            if (!ItemStack.isSameItem(s, cost)) continue;
            int k = Math.min(need, s.getCount());
            s.shrink(k);
            need -= k;
        }
        ItemStack got = o.assemble();
        t.trading = p;
        t.notifyTrade(o);
        t.trading = null;
        if (!p.getInventory().add(got)) p.drop(got, false);
        return true;
    }
}
