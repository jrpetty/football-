package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * [health] Every way a player's coin goes into a town or comes out of it, driven one at a time for the census test
 * (PlayerCoinGameTests): each path is a name and what it does, returning what was said. Here in the mod's own package
 * so a test reaches what a player reaches by talk, a counter or a block, without a screen.
 */
public final class PlayerCoinPaths {

    private PlayerCoinPaths() {}

    /** A path: its name, whether it must move the player's coin (a path that only says no proves nothing), and the deed. */
    public record Path(String name, boolean moves, Supplier<String> run) {}

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    private static void coins(ServerPlayer p, int n) {
        p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), n));
    }

    /** The treasury's dealings by talk (Commerce, Dealings): orders, contracts, the treasury's bank, investing, the auction,
     *  an escort, a feast, dice, a made-to-order piece. {@code f} a grown folk of the town (made a smith here), {@code q} a
     *  second player to bid against. */
    public static List<Path> talk(ServerLevel level, Villages.Village v, VillageFolkEntity f, ServerPlayer p, ServerPlayer q) {
        UUID id = v.id();
        String me = p.getUUID().toString();
        List<Path> out = new ArrayList<>();
        out.add(new Path("dice (six throws)", true, () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(Dealings.dice(f, p, "a game of dice for 2")).append(" / ");
            return sb.toString();
        }));
        out.add(new Path("a feast on the player", true, () -> Dealings.sponsor(f, p)));
        out.add(new Path("a made-to-order pickaxe", true, () -> {
            f.setJob(AssistantEntity.StationTask.SMITH);
            p.getInventory().add(new ItemStack(Items.COBBLESTONE, 3));
            p.getInventory().add(new ItemStack(Items.STICK, 2));
            return Dealings.order(f, p, "make me a stone pickaxe");
        }));
        out.add(new Path("a bulk order out of the stores", true, () -> Commerce.bulk(f, p, "I'd like to order 16 oak planks")));
        Ledger.note(id, "bulk/" + me, "");
        out.add(new Path("a bulk order's deposit", true, () -> {
            Ledger.note(id, "bulk/" + me, "");
            return Commerce.bulk(f, p, "I'd like to order 900 cobblestone");
        }));
        out.add(new Path("a bulk order's deposit back (not made in time)", true, () -> {
            String[] o = Commerce.fields(id, "bulk/" + me);
            if (o == null || o.length < 4) return "no order to lapse";
            Ledger.note(id, "bulk/" + me, o[0] + "|" + o[1] + "|" + o[2] + "|" + (day(level) - 1));
            return Commerce.bulk(f, p, "is my order ready");
        }));
        out.add(new Path("a supply contract's week delivered", true, () -> {
            Ledger.note(id, "contract/" + me, "FOOD|64|9|" + day(level) + "|-100|0|0");
            p.getInventory().add(new ItemStack(Items.BREAD, 64));
            return Commerce.contract(f, p, "my contract");
        }));
        out.add(new Path("the treasury's bank: a deposit", true, () -> Commerce.bank(f, p, "deposit 20")));
        out.add(new Path("the treasury's bank: a withdrawal", true, () -> Commerce.bank(f, p, "withdraw 6")));
        out.add(new Path("the treasury's bank: a loan repaid", true, () -> {
            String[] b = Commerce.fields(id, "bank/" + me);
            int dep = b == null ? 0 : (int) Commerce.num(b[0]);
            Ledger.note(id, "bank/" + me, dep + "|12|" + day(level) + "|10");
            return Commerce.bank(f, p, "repay 5");
        }));
        out.add(new Path("the treasury's bank: a week's interest (a claim, no coin moves)", false, () -> {
            Ledger.note(id, Commerce.POOL, "40");
            Commerce.bankWeek(level, v, day(level));
            return "account " + Ledger.note(id, "bank/" + me);
        }));
        out.add(new Path("an investment", true, () -> Commerce.invest(f, p, "I'd like to invest 50 coins")));
        out.add(new Path("the investment's dividend and its stake home (claims)", false, () -> {
            Commerce.dividends(v, day(level));
            Ledger.note(id, "invest/" + me, "50|" + (day(level) - 1) + "|0");
            Commerce.dividends(v, day(level) + 1);
            return "account " + Ledger.note(id, "bank/" + me);
        }));
        out.add(new Path("the treasury's auction: a bid", true, () -> {
            Ledger.note(id, "auction", "minecraft:diamond|5|0|||" + day(level));
            return Commerce.auction(f, p, "I bid 8");
        }));
        out.add(new Path("the treasury's auction: outbid", true, () -> {
            coins(q, 40);
            return Commerce.auction(f, q, "I bid 11");
        }));
        out.add(new Path("the treasury's auction: settled", false, () -> {
            Commerce.auctionDay(level, v, day(level) + 2);
            return "won " + Ledger.note(id, "auction.won/" + q.getUUID());
        }));
        out.add(new Path("the outbid draws its coin from its account", true, () -> Commerce.bank(f, p, "withdraw 8")));
        out.add(new Path("a caravan escorted", true, () -> {
            Ledger.note(id, "escort/" + me, Long.toString(day(level)));
            p.teleportTo(f.getX() + 2, f.getY(), f.getZ());
            Commerce.caravanArrived(level, f, id, id, false, 0);
            return "escort paid";
        }));
        return out;
    }

    /** The law: an outlaw's fine and pardon, a bounty claimed, a barred player's fine, bail, a constable's wage, the
     *  fine taken by the hunters. {@code guard} a guard of the town, {@code elder} its leader. */
    public static List<Path> law(ServerLevel level, Villages.Village v, VillageFolkEntity guard, VillageFolkEntity elder, ServerPlayer p, ServerPlayer q) {
        UUID id = v.id();
        List<Path> out = new ArrayList<>();
        out.add(new Path("declared an outlaw (the bounty set aside, still the town's)", false, () -> {
            CompoundTag r = Outlaws.record(id, p.getUUID(), true);
            r.putInt("wanted", 40);
            r.putString("name", p.getName().getString());
            Outlaws.declare(level, v, p, r, day(level));
            return "bounty " + r.getInt("bounty");
        }));
        out.add(new Path("an outlaw pays its fine", true, () -> Outlaws.payFine(level, guard, p, Outlaws.record(id, p.getUUID(), true), day(level))));
        out.add(new Path("an outlaw buys its pardon", true, () -> {
            CompoundTag r = Outlaws.record(id, p.getUUID(), true);
            r.putInt("wanted", 40);
            Outlaws.declare(level, v, p, r, day(level));
            return Outlaws.pardon(level, elder, p, r, "i'll pay for a pardon", day(level));
        }));
        out.add(new Path("a bounty claimed", true, () -> {
            CompoundTag r = Outlaws.record(id, q.getUUID(), true);
            r.putInt("wanted", 40);
            r.putString("name", "the other");
            Outlaws.declare(level, v, q, r, day(level));
            int bounty = r.getInt("bounty");
            CompoundTag claims = r.getCompound("claims");
            claims.putInt(p.getUUID().toString(), bounty);
            r.put("claims", claims);
            r.putInt("bounty", 0);
            Outlaws.settle(id, q.getUUID(), r, day(level), "brought down", 0);
            return Outlaws.claim(level, guard, p);
        }));
        out.add(new Path("a barred player's fine at the watch", true, () -> {
            CompoundTag r = PlayerLaw.record(id, p.getUUID());
            r.putBoolean("barred", true);
            r.putInt("fee", 7);
            return PlayerLaw.pay(guard, p);
        }));
        out.add(new Path("a fine taken out of the pack (the hunters, the watch)", true, () -> "took " + PlayerLaw.take(p, id, 4)));
        out.add(new Path("a constable's wage for a case", true, () -> {
            String sworn = PlayerLaw.swearNow(level, v, p);
            PlayerLaw.caseClosed(level, v, 1, List.of(p.getUUID()));
            return sworn;
        }));
        out.add(new Path("bail stood by a player", true, () -> {
            CompoundTag t = new CompoundTag();
            t.putInt("case", 424242);
            t.putInt("cell", 0);
            return "bailed " + WatchHouse.bail(level, v, elder, t, p);
        }));
        out.add(new Path("the bail back at the hearing", true, () -> {
            WatchHouse.bailBack(level, v, 424242);
            return "back";
        }));
        return out;
    }

    /** The rest: the bank and its counter, rent, a stall's till, the mint at the market stall, pay out of the treasury,
     *  a hire, a map's price, a retinue's wage, a gift to the statue fund. */
    public static List<Path> counters(ServerLevel level, Villages.Village v, VillageFolkEntity f, ServerPlayer p) {
        UUID id = v.id();
        List<Path> out = new ArrayList<>();
        out.add(new Path("the bank: a deposit", true, () -> Bank.handle(level, id, p, "deposit 15")));
        out.add(new Path("the bank: a withdrawal", true, () -> Bank.handle(level, id, p, "withdraw 5")));
        out.add(new Path("the bank's counter: the changer sells gold", true, () -> {
            CompoundTag a = new CompoundTag();
            a.putString("item", "minecraft:gold_nugget");
            a.putInt("amount", 3);
            ServicePoints.Visit visit = new ServicePoints.Visit(level, v, ServiceRole.BANK, null, p, null, new CompoundTag());
            Market.intoStoresForTests(level, id, new ItemStack(Items.GOLD_NUGGET, 8));
            return BankCounter.change(visit, a).toString();
        }));
        out.add(new Path("the bank's counter: the changer buys gold", true, () -> {
            CompoundTag a = new CompoundTag();
            a.putString("item", "minecraft:gold_nugget");
            a.putInt("amount", 2);
            a.putBoolean("toCoin", true);
            ServicePoints.Visit visit = new ServicePoints.Visit(level, v, ServiceRole.BANK, null, p, null, new CompoundTag());
            return BankCounter.change(visit, a).toString();
        }));
        out.add(new Path("rent waiting collected at the counter", true, () -> {
            Ledger.note(id, "rentdue/" + p.getUUID(), "6");
            ServicePoints.Visit visit = new ServicePoints.Visit(level, v, ServiceRole.BANK, null, p, null, new CompoundTag());
            return BankCounter.collect(visit).toString();
        }));
        out.add(new Path("rent waiting collected by word", true, () -> {
            Ledger.note(id, "rentdue/" + p.getUUID(), "4");
            p.teleportTo(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5);
            return Homes.playerCollects(level, p);
        }));
        out.add(new Path("a stall's till emptied", true, () -> {
            PlayerStalls.Stall st = PlayerStalls.load(id, p.getUUID());
            if (st == null) st = new PlayerStalls.Stall(p.getUUID());
            st.till = 9;
            PlayerStalls.save(id, st);
            return "paid " + PlayerStalls.payTill(level, id, p);
        }));
        out.add(new Path("the mint at the market stall", true, () -> {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_INGOT, 2));
            return MarketMaker.mintForPlayer(level, v, p);
        }));
        out.add(new Path("paid out of the treasury (a sale, a shift, a bounty)", true, () -> "paid "
            + Earnings.fromTreasury(p, id, 5, Earnings.Source.SELL, Villages.name(id), "for the test")));
        out.add(new Path("a folk hired for a day", true, () -> {
            f.setJob(AssistantEntity.StationTask.NONE);
            f.persona().feelFor(p.getUUID(), p.getName().getString(), 40);
            return Hire.ask(f, p);
        }));
        out.add(new Path("a map paid for at the cartographer's", true, () -> {
            Cartographers.paid(v, f, p, 6, 2.0);
            return "paid";
        }));
        out.add(new Path("a retinue's man paid by the knight", true, () -> "paid " + Retinue.pay(level, id, p, false, f)));
        out.add(new Path("a retinue's man paid by the town", false, () -> "paid " + Retinue.pay(level, id, p, true, f)));
        out.add(new Path("a gift to the statue fund", true, () -> PublicFund.donate(level, v, p, 3)));
        return out;
    }
}
