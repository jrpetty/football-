package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.BusinessOffers;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.Crime;
import com.jrpetty.mcassistant.entity.Deputy;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.MyBusinesses;
import com.jrpetty.mcassistant.entity.PlayerFarms;
import com.jrpetty.mcassistant.entity.PlayerHiring;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.Temperament;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkShifts;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [wpb] Buying a business, or a share, by offer (BusinessOffers, OfferPage). Kept apart: bx01 to bx06.
 * <ul>
 * <li><b>bx01</b>: no instant buy is left: the old Buy and Invest lead to the offer page, nothing changes hands.</li>
 * <li><b>bx02</b>: a player's offer for a player's business: the price in escrow, accepted, paid to the owner, the firm passes.</li>
 * <li><b>bx03</b>: a counter-offer, taken by the buyer: the difference into escrow, done.</li>
 * <li><b>bx04</b>: a folk owner thinks it over (half a day to a day); a fair-value offer refused by a healthy firm's owner,
 *     a strong one accepted; a struggling owner's price is far lower.</li>
 * <li><b>bx05</b>: a lowball is refused with a reason, costs regard, and a second gets the buyer shown the door for a week;
 *     the escrow back each time.</li>
 * <li><b>bx06</b>: competing offers: one accepted, the other refunded; an offer lapses and is refunded; one withdrawn.</li>
 * </ul>
 * Each on its own ground, x 8,840,000 to 8,870,000 at z 73,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BusinessOfferGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 73000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, int x, int n) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Shifts.resetForTests();
        WorkShifts.resetForTests();
        PlayerFarms.resetForTests();
        PlayerHiring.resetForTests();
        level.setDayTime(2000L);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        first.ensurePersona();
        folk.add(first);
        UUID id = first.ownerId();
        for (int i = 1; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + (i % 5)).east(i / 5), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            f.ensurePersona();
            f.setJob(StationTask.NONE);
            folk.add(f);
        }
        BlockPos origin = Kit.surface(level, heart.getX() + 8, heart.getZ() + 8);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int xx = 0; xx < 3; xx++) for (int z = 0; z < 3; z++) level.setBlock(origin.offset(xx, y, z), unit, 3);
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands");
        StorehouseBlockEntity store = (StorehouseBlockEntity) level.getBlockEntity(door);
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c && c != (Object) store) { c.clearContent(); level.removeBlock(p, false); }
        }
        Villages.forgetStores(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        Ledger.addCoins(id, 400);
        return new Town(id, Villages.get(id), heart, store, folk);
    }

    private static void fill(UUID id, StorehouseBlockEntity store, ItemStack g) {
        ItemStack left = g.copy();
        while (!left.isEmpty()) {
            int slot = -1;
            for (int i = 0; i < store.getContainerSize(); i++) if (store.getItem(i).isEmpty()) { slot = i; break; }
            if (slot < 0) break;
            store.setItem(slot, left.split(left.getMaxStackSize()));
        }
        store.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        com.jrpetty.mcassistant.entity.Budget.forget(id);
    }

    private static ServerPlayer player(GameTestHelper helper, Town t, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        for (VillageFolkEntity f : t.folk()) f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 60);
        Standing.stir(t.id(), p.getUUID());
        return p;
    }

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** A firm of this kind owned whole by this owner (a player, or an absent one by id alone). */
    private static Business firm(ServerLevel level, Town t, String kind, UUID owner, String name) {
        Business b = Enterprise.foundForTests(level, t.v(), kind, null);
        b.owners.clear();
        b.owners.add(new Business.Stake(Business.Holder.PLAYER, owner, name, Business.WHOLE));
        return b;
    }

    private static VillageFolkEntity staff(Town t, Business b, int i) {
        VillageFolkEntity hand = t.folk().get(i);
        hand.setJob(Enterprise.kindOf(t.id(), b).work());
        Enterprise.staffForTests(t.id(), b, hand);
        return hand;
    }

    private static CompoundTag rec(Town t, Business b) {
        return Enterprise.recordForTests(t.id(), b);
    }

    private static int coins(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(McAssistantMod.VILLAGE_COIN.get())) n += s.getCount();
        return n;
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bx01_no_instant_buy")
    public static void bx01_no_instant_buy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8840000, 4);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            BusinessOffers.resetForTests();
            try {
                VillageFolkEntity founder = t.folk().get(1);
                Business b = Enterprise.foundForTests(level, t.v(), "baker", founder);
                ServerPlayer p = player(helper, t, t.heart(), 200);
                String was = b.ownersWords();
                String buy = Businesses.buy(level, t.id(), b, p);
                String inv = Businesses.invest(level, t.id(), b, p, 20);
                String page = PlayerLife.ctx(p).page;
                String shown = PlayerLife.pageForTests(p).toString();
                Kit.log("[VT] bx01 " + buy + " | " + inv + " | owners " + was + " -> " + b.ownersWords() + " coins " + coins(p) + " page " + page + " guide " + shown.contains("Fair value"));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(b.ownersWords().equals(was) && coins(p) == 200, "nothing changes hands at a click");
                helper.assertTrue(page.equals("offer") && shown.contains("Fair value") && shown.contains("Make the offer"), "the offer page instead, with the valuation guide");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bx02_player_offer_accepted")
    public static void bx02_player_offer_accepted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8844000, 3);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            BusinessOffers.resetForTests();
            try {
                ServerPlayer owner = player(helper, t, t.heart(), 0);
                ServerPlayer buyer = player(helper, t, t.heart().east(2), 50);
                Business b = firm(level, t, "baker", owner.getUUID(), owner.getGameProfile().getName());
                BusinessOffers.Offer o = BusinessOffers.makeForTests(level, buyer, t.id(), b, Business.WHOLE, 30);
                int held = coins(buyer);
                long escrow = BusinessOffers.escrowed(t.id());
                String acc = o == null ? "no offer" : BusinessOffers.acceptForTests(level, owner, o);
                Kit.log("[VT] bx02 escrow " + escrow + " buyer " + held + " | " + acc + " | owners " + b.ownersWords() + " seller " + coins(owner) + " escrow " + BusinessOffers.escrowed(t.id()));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(o != null && held == 20 && escrow == 30, "the price in escrow");
                helper.assertTrue(b.ownedBy(buyer.getUUID()) && !b.ownedBy(owner.getUUID()) && coins(owner) == 30, "accepted: paid, and the firm passes");
                helper.assertTrue(BusinessOffers.escrowed(t.id()) == 0, "escrow empty");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bx03_counter_taken")
    public static void bx03_counter_taken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8848000, 3);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            BusinessOffers.resetForTests();
            try {
                ServerPlayer owner = player(helper, t, t.heart(), 0);
                ServerPlayer buyer = player(helper, t, t.heart().east(2), 50);
                Business b = firm(level, t, "baker", owner.getUUID(), owner.getGameProfile().getName());
                BusinessOffers.Offer o = BusinessOffers.makeForTests(level, buyer, t.id(), b, Business.WHOLE, 20);
                String c = BusinessOffers.counterForTests(level, owner, o, 28);
                String took = BusinessOffers.takeCounterForTests(level, buyer, o);
                Kit.log("[VT] bx03 " + c + " | " + took + " | buyer " + coins(buyer) + " seller " + coins(owner) + " owners " + b.ownersWords() + " | " + o.log);
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(b.ownedBy(buyer.getUUID()) && coins(buyer) == 22 && coins(owner) == 28, "the counter taken: the difference paid, the firm passes");
                helper.assertTrue(o.state == BusinessOffers.State.ACCEPTED && BusinessOffers.escrowed(t.id()) == 0, "done, escrow empty");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bx04_folk_owner_decides")
    public static void bx04_folk_owner_decides(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8852000, 4);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            BusinessOffers.resetForTests();
            try {
                VillageFolkEntity founder = t.folk().get(1);
                Business b = Enterprise.foundForTests(level, t.v(), "baker", founder);
                ServerPlayer buyer = player(helper, t, t.heart(), 300);
                b.till += 5000;                                             // a healthy firm, with coin behind it
                long fair = BusinessOffers.value(level, t.id(), b).fair();
                int fairCoins = (int) Math.max(1, Math.round(fair / 100.0));
                long res = BusinessOffers.reservationForTests(level, t.id(), b, founder, buyer.getUUID());
                BusinessOffers.Offer o = BusinessOffers.makeForTests(level, buyer, t.id(), b, Business.WHOLE, fairCoins);
                boolean thinking = o != null && o.decideAt > level.getGameTime() + 11000;
                if (o != null) BusinessOffers.decideForTests(level, o);
                String saidFair = o == null ? "" : o.reason;
                boolean refused = o != null && !b.ownedBy(buyer.getUUID()) && o.state != BusinessOffers.State.ACCEPTED;
                if (o != null && o.live()) BusinessOffers.withdrawForTests(level, buyer, o);
                int afterFair = coins(buyer);
                // a struggling owner's price
                b.lossDays = 10;
                long resLow = BusinessOffers.reservationForTests(level, t.id(), b, founder, buyer.getUUID());
                b.lossDays = 0;
                BusinessOffers.Offer strong = BusinessOffers.makeForTests(level, buyer, t.id(), b, Business.WHOLE, (int) Math.min(250, fairCoins * 4L));
                if (strong != null) BusinessOffers.decideForTests(level, strong);
                Kit.log("[VT] bx04 fair " + fairCoins + "c, its price " + res + "c (struggling " + resLow + "c) | thinking " + thinking + " | fair offer: " + saidFair + " -> " + (o == null ? "-" : o.state)
                    + " coins back " + afterFair + " | strong " + (strong == null ? "-" : strong.price + "c " + strong.state + " " + strong.reason) + " | owners " + b.ownersWords());
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(res * 10 >= fairCoins * 14 && resLow * 10 < fairCoins * 13, "a healthy owner asks well over fair value; a struggling one far less");
                helper.assertTrue(thinking, "the folk owner takes half a day or more to answer");
                helper.assertTrue(refused && !saidFair.isEmpty() && afterFair == 300, "a fair-value offer refused, in its own words, the coin back");
                helper.assertTrue(strong != null && strong.state == BusinessOffers.State.ACCEPTED && b.ownedBy(buyer.getUUID()), "a strong offer accepted");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bx05_lowball")
    public static void bx05_lowball(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8856000, 4);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            BusinessOffers.resetForTests();
            try {
                VillageFolkEntity founder = t.folk().get(1);
                Business b = Enterprise.foundForTests(level, t.v(), "baker", founder);
                ServerPlayer buyer = player(helper, t, t.heart(), 100);
                long fair = BusinessOffers.value(level, t.id(), b).fair();
                int low = (int) Math.max(1, fair / 400);
                int regard = founder.persona().affinity(buyer.getUUID());
                BusinessOffers.Offer o1 = BusinessOffers.makeForTests(level, buyer, t.id(), b, Business.WHOLE, low);
                if (o1 != null) BusinessOffers.decideForTests(level, o1);
                int back1 = coins(buyer);
                BusinessOffers.Offer o2 = BusinessOffers.makeForTests(level, buyer, t.id(), b, Business.WHOLE, low);
                if (o2 != null) BusinessOffers.decideForTests(level, o2);
                BusinessOffers.Offer o3 = BusinessOffers.makeForTests(level, buyer, t.id(), b, Business.WHOLE, low * 3);
                Kit.log("[VT] bx05 lowball " + low + "c of fair " + fair / 100 + "c: " + (o1 == null ? "-" : o1.state + " " + o1.reason) + " | regard " + regard + " -> " + founder.persona().affinity(buyer.getUUID())
                    + " coins " + back1 + " | second " + (o2 == null ? "-" : o2.reason) + " | third allowed " + (o3 != null) + " coins " + coins(buyer));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(o1 != null && o1.state == BusinessOffers.State.REFUSED && o1.reason.contains("\""), "a lowball refused, with its reason");
                helper.assertTrue(back1 == 100, "the escrow back");
                helper.assertTrue(founder.persona().affinity(buyer.getUUID()) < regard, "it took offence");
                helper.assertTrue(o3 == null && coins(buyer) == 100, "a second lowball: no more offers from them this week");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bx06_competing_lapsed")
    public static void bx06_competing_lapsed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8860000, 3);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            BusinessOffers.resetForTests();
            try {
                ServerPlayer owner = player(helper, t, t.heart(), 0);
                ServerPlayer a = player(helper, t, t.heart().east(2), 40);
                ServerPlayer c = player(helper, t, t.heart().east(4), 40);
                Business b = firm(level, t, "baker", owner.getUUID(), owner.getGameProfile().getName());
                Business b2 = firm(level, t, "baker", owner.getUUID(), owner.getGameProfile().getName());
                BusinessOffers.Offer oa = BusinessOffers.makeForTests(level, a, t.id(), b, Business.WHOLE, 25);
                BusinessOffers.Offer oc = BusinessOffers.makeForTests(level, c, t.id(), b, Business.WHOLE, 30);
                BusinessOffers.acceptForTests(level, owner, oc);
                int aBack = coins(a);
                BusinessOffers.Offer lapse = BusinessOffers.makeForTests(level, a, t.id(), b2, 2500, 10);
                int aHeld = coins(a);
                BusinessOffers.morningForTests(level, t.id(), level.getDayTime() / 24000L + 5);
                int aAfter = coins(a);
                BusinessOffers.Offer w = BusinessOffers.makeForTests(level, a, t.id(), b2, 1000, 5);
                String wd = w == null ? "-" : BusinessOffers.withdrawForTests(level, a, w);
                Kit.log("[VT] bx06 " + (oa == null ? "-" : oa.state + " " + oa.reason) + " a " + aBack + " | sold to " + b.ownersWords() + " | lapse " + (lapse == null ? "-" : lapse.state) + " a " + aHeld + " -> " + aAfter
                    + " | " + wd + " a " + coins(a) + " escrow " + BusinessOffers.escrowed(t.id()));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(b.ownedBy(c.getUUID()) && oa != null && oa.state == BusinessOffers.State.REFUSED && aBack == 40, "the rival refused and refunded");
                helper.assertTrue(lapse != null && lapse.state == BusinessOffers.State.LAPSED && aHeld == 30 && aAfter == 40, "a lapsed offer refunded");
                helper.assertTrue(coins(a) == 40 && BusinessOffers.escrowed(t.id()) == 0, "withdrawn: refunded, escrow empty");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }
}
