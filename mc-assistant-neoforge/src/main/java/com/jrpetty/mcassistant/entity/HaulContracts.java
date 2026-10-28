package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [earning] Haul contracts between towns: a town short of something (its Wanted board) that a neighbour can spare pays
 * a player to fetch it, by hand.
 * <ul>
 * <li><b>The contract.</b> So many of a thing from the neighbour's stores to this town's, by a day, for a fee by the
 *     distance and the load. Accepted, the fee goes out of the buying town's treasury into escrow (the ledger holds it).</li>
 * <li><b>Loading.</b> At the neighbour's storehouse: the goods come out of its stores (Shipments.dispatch, carried by
 *     hand: neither the wagons nor the road take it), marked as the load; the player leaves a deposit of their worth.</li>
 * <li><b>Delivered.</b> At the buying town's storehouse: the load into its stores; the town pays the neighbour for the
 *     goods (Shipments.delivered, as any shipment); the player has the fee out of escrow and the deposit back.</li>
 * <li><b>Not delivered</b> by the day: the load is lost to the neighbour (Shipments.lost) and the deposit is its
 *     instead; the fee goes back to the town that offered it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class HaulContracts {

    private HaulContracts() {}

    /** The fee: a coin a hundred blocks, and a tenth of the load's worth; the least and the most. */
    static final double PER_HUNDRED = 1.0, OF_WORTH = 0.1;
    static final int FEE_LEAST = 3, FEE_MOST = 60;
    /** The farthest neighbour a contract reaches (blocks), the most of a load, the days allowed. */
    static final int FARTHEST = 2400, LOAD_MOST = 64, DAYS = 3;
    static final String MARK = "haul";

    /** A contract on offer or under way: its key, the towns, the thing, how many, the fee, the goods' price, its day. */
    public record Contract(String id, UUID from, UUID to, Item item, int count, int fee, int coinDue, long deadline) {}

    /** What this town offers today: its wanted orders a loaded neighbour can spare, nearest first (three at most). */
    public static List<Contract> offered(ServerLevel level, Villages.Village to) {
        List<Contract> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        for (WantedBoard.Order o : WantedBoard.orders(level, to)) {
            if (o.left() < 8 || out.size() >= 3) continue;
            ItemStack one = new ItemStack(o.item);
            Villages.Village best = null;
            double bestD = FARTHEST;
            for (Villages.Village from : Villages.every()) {
                if (from.id().equals(to.id()) || !from.dim().equals(to.dim()) || !level.isLoaded(from.centre())) continue;
                double d = Math.sqrt(from.centre().distSqr(to.centre()));
                if (d >= bestD) continue;
                if (Budget.spare(level, from.id(), one) < Math.min(o.left(), LOAD_MOST)) continue;
                best = from;
                bestD = d;
            }
            if (best == null) continue;
            int n = Math.min(o.left(), LOAD_MOST);
            int due = Math.max(1, (int) Math.round(PriceIndex.each(level, best.id(), one) * n));
            int fee = Math.max(FEE_LEAST, Math.min(FEE_MOST, (int) Math.round(bestD / 100.0 * PER_HUNDRED + due * OF_WORTH)));
            out.add(new Contract(to.id() + "|" + best.id() + "|" + BuiltInRegistries.ITEM.getKey(o.item) + "|" + day, best.id(), to.id(), o.item,
                n, fee, due, day + DAYS));
        }
        return out;
    }

    // ------------------------------------------------------------------ the player's contract

    @Nullable
    static CompoundTag held(ServerPlayer p) {
        CompoundTag root = Earnings.data(p);
        return root.contains("haul") ? root.getCompound("haul") : null;
    }

    /** Take a contract: its fee into escrow. What it says. */
    public static String accept(ServerLevel level, ServerPlayer p, String id) {
        if (held(p) != null) return "You've a contract under way already.";
        String[] f = id.split("\\|");
        if (f.length < 4) return "No such contract.";
        Villages.Village to = Villages.get(Earnings.uuid(f[0]));
        if (to == null) return "No such contract.";
        Contract c = null;
        for (Contract x : offered(level, to)) if (x.id().equals(id)) c = x;
        if (c == null) return "That contract has been taken down.";
        int fee = Ledger.takeCoins(to.id(), c.fee());
        if (fee < c.fee()) {
            if (fee > 0) Ledger.addCoins(to.id(), fee);
            return Villages.name(to.id()) + " can't put the fee up just now.";
        }
        Ledger.note(to.id(), "escrow/haul/" + p.getUUID(), Integer.toString(fee));
        CompoundTag t = new CompoundTag();
        t.putString("id", c.id());
        t.putString("from", c.from().toString());
        t.putString("to", c.to().toString());
        t.putString("item", BuiltInRegistries.ITEM.getKey(c.item()).toString());
        t.putInt("count", c.count());
        t.putInt("fee", c.fee());
        t.putInt("due", c.coinDue());
        t.putLong("deadline", c.deadline());
        t.putInt("leg", 0);
        Earnings.data(p).put("haul", t);
        return "Contract taken: fetch " + Goods.words(new ItemStack(c.item()), c.count()) + " from " + Villages.name(c.from()) + "'s storehouse to "
            + Villages.name(c.to()) + "'s by day " + c.deadline() + ". The fee, " + c.fee() + "c, is held in escrow till you deliver.";
    }

    /** The storehouses: loading at the one, unloading at the other. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(level.getBlockEntity(e.getPos()) instanceof StorehouseBlockEntity)) return;
        CompoundTag t = held(p);
        if (t == null) return;
        Villages.Village here = PlayerServices.townAt(level, e.getPos());
        if (here == null) return;
        String said = null;
        if (t.getInt("leg") == 0 && here.id().toString().equals(t.getString("from"))) said = load(level, p, t);
        else if (t.getInt("leg") == 1 && here.id().toString().equals(t.getString("to"))) said = deliver(level, p, t);
        if (said == null) return;
        Earnings.tell(p, said);
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
    }

    static String load(ServerLevel level, ServerPlayer p, CompoundTag t) {
        UUID from = Earnings.uuid(t.getString("from")), to = Earnings.uuid(t.getString("to"));
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(t.getString("item")));
        int n = t.getInt("count");
        if (from == null || to == null || item == Items.AIR) return "The contract has gone astray.";
        ItemStack one = new ItemStack(item);
        int deposit = Market.bidLot(level, from, one, n);
        if (Market.coinsHeld(p) < deposit) return "The storekeeper wants a deposit of " + deposit + "c for the load (back on delivery).";
        Shipments.Shipment s = Shipments.dispatch(level, from, to, Shipments.HAND + p.getUUID(), List.of(one.copyWithCount(n)), t.getInt("due"));
        if (s == null) return Villages.name(from) + "'s stores can't spare it all just now.";
        if (deposit > 0) Market.payOut(p, deposit);
        Ledger.note(from, "escrow/deposit/" + p.getUUID(), Integer.toString(deposit));
        int left = n;
        while (left > 0) {
            int k = Math.min(left, one.getMaxStackSize());
            ItemStack load = one.copyWithCount(k);
            CustomData.update(DataComponents.CUSTOM_DATA, load, tag -> tag.putString(Shifts.MARK, MARK));
            if (!p.getInventory().add(load)) p.drop(load, false);
            left -= k;
        }
        t.putInt("leg", 1);
        t.putString("shipment", s.id().toString());
        t.putInt("deposit", deposit);
        return "Loaded " + Goods.words(one, n) + ". Take it to " + Villages.name(to) + "'s storehouse.";
    }

    static String deliver(ServerLevel level, ServerPlayer p, CompoundTag t) {
        UUID to = Earnings.uuid(t.getString("to")), from = Earnings.uuid(t.getString("from"));
        UUID ship = Earnings.uuid(t.getString("shipment"));
        if (to == null || from == null || ship == null) return "The contract has gone astray.";
        int n = t.getInt("count"), got = 0;
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(t.getString("item")));
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.is(item) || !Shifts.isShiftItem(st)) continue;
            got += st.getCount();
            p.getInventory().setItem(i, ItemStack.EMPTY);
        }
        if (got <= 0) return "You haven't the load with you.";
        got = Math.min(got, n);
        Shipments.delivered(level, ship, List.of(new ItemStack(item, got)));
        List<WantedBoard.Order> orders = WantedBoard.read(to);                    // the order it answered, filled so far
        int toFill = got;
        for (WantedBoard.Order o : orders) {
            if (o.item != item || toFill <= 0) continue;
            int k = Math.min(o.left(), toFill);
            o.filled += k;
            toFill -= k;
        }
        WantedBoard.write(to, orders);
        int fee = escrow(to, "escrow/haul/" + p.getUUID());
        int deposit = escrow(from, "escrow/deposit/" + p.getUUID());
        int earned = (int) Math.round(fee * got / (double) n);
        if (fee - earned > 0) Ledger.addCoins(to, fee - earned);                 // a short load: the rest of the fee back
        int back = (int) Math.round(deposit * got / (double) n);
        if (deposit - back > 0) Ledger.addCoins(from, deposit - back);          // what never came: the neighbour keeps its worth
        Earnings.giveCoins(p, back);
        if (earned > 0) {
            Earnings.giveCoins(p, earned);
            Earnings.record(p, to, Earnings.Source.CONTRACT, earned, Villages.name(to), "for hauling " + Goods.words(new ItemStack(item), got)
                + " from " + Villages.name(from));
        }
        Earnings.data(p).remove("haul");
        return "Delivered. Your deposit, " + back + "c, is back.";
    }

    private static int escrow(UUID village, String key) {
        String s = Ledger.note(village, key);
        Ledger.forget(village, key);
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** What a town holds in escrow for contracts (fees and deposits): coin out of its treasury, still its own or owed. */
    public static int escrowed(UUID village) {
        int n = 0;
        for (var e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith("escrow/")) continue;
            try {
                n += Integer.parseInt(e.getValue().trim());
            } catch (NumberFormatException ignored) {
                // not a number: nothing held
            }
        }
        return n;
    }

    /** Every few seconds, for online players with a contract: past its day, it is settled as lost. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 13) return;
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            CompoundTag t = held(p);
            if (t == null || !(p.level() instanceof ServerLevel level)) continue;
            if (level.getDayTime() / 24000L <= t.getLong("deadline")) continue;
            lapse(level, p, t);
        }
    }

    static void lapse(ServerLevel level, ServerPlayer p, CompoundTag t) {
        UUID to = Earnings.uuid(t.getString("to")), from = Earnings.uuid(t.getString("from"));
        if (to != null) Ledger.addCoins(to, escrow(to, "escrow/haul/" + p.getUUID()));
        if (t.getInt("leg") == 1 && from != null) {
            List<ItemStack> saved = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (!Shifts.isShiftItem(st)) continue;
                saved.add(ShiftTasks.strip(st));
                p.getInventory().setItem(i, ItemStack.EMPTY);
            }
            UUID ship = Earnings.uuid(t.getString("shipment"));
            if (ship != null) Shipments.lost(level, ship, saved, "its carrier, " + p.getName().getString() + ", never delivered it");
            int deposit = escrow(from, "escrow/deposit/" + p.getUUID());
            if (saved.isEmpty()) Ledger.addCoins(from, deposit);
            else Earnings.giveCoins(p, deposit);
        }
        Earnings.data(p).remove("haul");
        p.sendSystemMessage(Component.literal("Your haul contract ran out: the fee went back to the town" + (t.getInt("leg") == 1 ? ", and the load to "
            + Villages.name(from) : "") + ".").withStyle(ChatFormatting.RED));
    }

    /** The contract in words for the page, or null. */
    @Nullable
    static String line(ServerPlayer p) {
        CompoundTag t = held(p);
        if (t == null) return null;
        UUID to = Earnings.uuid(t.getString("to")), from = Earnings.uuid(t.getString("from"));
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(t.getString("item")));
        return (t.getInt("leg") == 0 ? "Load " : "Deliver ") + Goods.words(new ItemStack(item), t.getInt("count")) + (t.getInt("leg") == 0
            ? " at " + Villages.name(from) + "'s storehouse" : " to " + Villages.name(to) + "'s storehouse") + " by day " + t.getLong("deadline")
            + " for " + t.getInt("fee") + "c.";
    }

    /** Where the contract's next leg is: the storehouse to load at or deliver to. */
    @Nullable
    static BlockPos nextStop(ServerLevel level, ServerPlayer p) {
        CompoundTag t = held(p);
        if (t == null) return null;
        UUID town = Earnings.uuid(t.getString(t.getInt("leg") == 0 ? "from" : "to"));
        return town == null ? null : ShiftTasks.storehouse(level, town);
    }

    /** Tests: run the deliver and load as the storehouse click would. */
    public static String loadForTests(ServerLevel level, ServerPlayer p) {
        CompoundTag t = held(p);
        return t == null ? "none" : load(level, p, t);
    }

    public static String deliverForTests(ServerLevel level, ServerPlayer p) {
        CompoundTag t = held(p);
        return t == null ? "none" : deliver(level, p, t);
    }
}
