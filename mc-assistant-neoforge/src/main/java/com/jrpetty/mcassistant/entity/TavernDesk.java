package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * [shops-homes] The tavern's bar: a drink of the café's or a hot meal out of the stores at the town's price, a round
 * for everybody in (Tavern.round: a coin a head, raised glasses), and a room for the night at the inn (Inn.rent).
 * [roles-c] A drink is poured the barkeep's way (Tavern.pint, its till and books); empty bottles are paid back here.
 */
final class TavernDesk implements ServicePoints.Desk {

    static final TavernDesk INSTANCE = new TavernDesk();

    private TavernDesk() {}

    @Override
    public String kind(ServiceRole role) {
        return "desk";
    }

    @Override
    public int fingerprint(ServicePoints.Visit visit) {
        return visit.player() == null ? 0 : Market.coinsHeld(visit.player()) * 31 + inTonight(visit)
            + visit.player().getInventory().countItem(Items.GLASS_BOTTLE) * 1009;                 // [roles-c] the bottles row
    }

    /** How many of the town are in the tavern now. */
    private static int inTonight(ServicePoints.Visit visit) {
        Ledger.Building tav = Tavern.of(visit.village().id());
        if (tav == null) return 0;
        return visit.level().getEntitiesOfClass(VillageFolkEntity.class, new AABB(tav.anchor()).inflate(5, 3, 5),
            f -> f.isAlive() && !f.isSleeping() && !f.isBaby() && visit.village().id().equals(f.ownerId())).size();
    }

    @Override
    public CompoundTag body(ServicePoints.Visit visit, String tab) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        DeskRows d = new DeskRows(level.registryAccess());
        int held = visit.player() == null ? 0 : Market.coinsHeld(visit.player());
        d.section("At the bar");
        List<ItemStack> drinks = Cafe.drinksInStores(level, id);
        if (drinks.isEmpty()) d.line("Nothing from the café in the stores tonight: water, or ask the cook.");
        for (ItemStack one : drinks) {
            int price = ShopStock.lotPrice(level, id, one, 1);
            CompoundTag r = d.row("drink", "Buy", Goods.name(one), price + (price == 1 ? " coin" : " coins"), one,
                held < price ? "You've " + held + " on you." : "");
            r.getCompound("args").putString("id", Goods.lineId(one));
        }
        ItemStack meal = meal(level, id);
        if (!meal.isEmpty()) {
            int price = ShopStock.lotPrice(level, id, meal, 1);
            CompoundTag r = d.row("meal", "Buy", "A hot meal: " + Goods.name(meal), price + (price == 1 ? " coin" : " coins"), meal,
                held < price ? "You've " + held + " on you." : "");
            r.getCompound("args").putString("id", Goods.lineId(meal));
        }
        int in = inTonight(visit);
        d.section("For the room");
        d.row("round", "Buy a round", "A round for everybody in", in == 0 ? "Nobody's in yet." : in + " in: a coin a head, " + in + (in == 1 ? " coin." : " coins."),
            new ItemStack(Items.HONEY_BOTTLE), in == 0 ? "Nobody's in to drink it." : held < in ? "A round is " + in + " coins; you've " + held + "." : "");
        int bottles = visit.player() == null ? 0 : visit.player().getInventory().countItem(Items.GLASS_BOTTLE);     // [roles-c]
        d.row("bottles", "Bring back", "Your empty bottles", "A coin for every " + Tavern.BOTTLE_DEPOSIT + "; they go back in the stores.",
            new ItemStack(Items.GLASS_BOTTLE), bottles < Tavern.BOTTLE_DEPOSIT ? "You've " + bottles + " empty bottles on you." : "");
        if (Inn.inn(id) != null) {
            d.section("The inn");
            d.row("room", "Take a room", "A room for the night", Inn.ROOM + " coins a night; your bed till morning.", new ItemStack(Items.RED_BED), "");
        }
        return d.build();
    }

    /** The cheapest hot meal the stores can spare, or nothing. */
    private static ItemStack meal(ServerLevel level, UUID id) {
        ItemStack best = ItemStack.EMPTY;
        double bestPrice = Double.MAX_VALUE;
        for (ItemStack s : Cafe.menuGoods(level, id)) {
            if (Cafe.isDrink(s) || s.get(DataComponents.FOOD) == null) continue;
            double p = PriceIndex.each(level, id, s);
            if (p < bestPrice) { bestPrice = p; best = s.copyWithCount(1); }
        }
        return best;
    }

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerLevel level = visit.level();
        ServerPlayer p = visit.player();
        if (p == null) return ServicePoints.Result.no("");
        return switch (action) {
            case "drink" -> result(Tavern.pint(level, visit.village(), p, args.getString("id"), visit.keeper()));   // [roles-c]
            case "meal" -> pour(visit, args.getString("id"), meal(level, visit.village().id()));
            case "bottles" -> result(Tavern.bottlesBack(level, visit.village(), p, visit.keeper()));              // [roles-c]
            case "round" -> {
                String said = Tavern.round(level, visit.village(), p);
                yield said.startsWith("You bought") ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            case "room" -> {
                String said = Inn.rent(level, visit.village(), p);
                yield said.contains("(Your bed until morning") ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
            }
            default -> ServicePoints.Result.no("");
        };
    }

    /** [roles-c] What the bar said, as the desk's answer. */
    private static ServicePoints.Result result(Inn.Let l) {
        return l.ok() ? ServicePoints.Result.done(l.said()) : ServicePoints.Result.no(l.said());
    }

    /** The meal out of the stores, paid for at the town's price into the treasury. (A drink: Tavern.pint.) */
    private static ServicePoints.Result pour(ServicePoints.Visit visit, String id, ItemStack meal) {
        ServerLevel level = visit.level();
        ServerPlayer p = visit.player();
        UUID village = visit.village().id();
        ItemStack one = meal;
        if (one.isEmpty()) return ServicePoints.Result.no("That's all gone just now.");
        ShopDesk.Quote q = ShopDesk.quote(level, village, p, List.of(one), List.of(1));
        if (Market.coinsHeld(p) < q.total()) return ServicePoints.Result.no(Goods.name(one) + " is " + q.total() + ". You've " + Market.coinsHeld(p) + ".");
        ItemStack want = one;
        if (!TownWork.take(level, visit.village(), s -> ItemStack.isSameItemSameComponents(s, want), 1)) return ServicePoints.Result.no("That's all gone just now.");
        Market.payOut(p, q.total());
        Ledger.addCoins(village, q.total());
        Economy.sold(village, q.total());
        Stockroom.sold(level, village, Stockroom.Seller.TAVERN, one, 1, q.total());
        if (!p.getInventory().add(one.copy())) p.drop(one.copy(), false);
        return ServicePoints.Result.done(Goods.name(one) + ", " + q.total() + (q.total() == 1 ? " coin" : " coins") + ". Good health!");
    }
}
