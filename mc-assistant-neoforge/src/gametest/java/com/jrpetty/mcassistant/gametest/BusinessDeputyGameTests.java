package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
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
 * [wpb] A player's business while they are away, and its deputy manager (Deputy, DeputyPage).
 * <ul>
 * <li><b>bd01</b>: folk staff keep working while the owner is offline: the firm runs (nobody in charge, the staff carry on),
 *     and a farm with folk hands is not paused.</li>
 * <li><b>bd02</b>: it shuts only when every member of staff is a player and none is about; a partner about keeps it open.</li>
 * <li><b>bd03</b>: the deputy restocks within the day's spending limit and the till's floor and banks the takings; a task
 *     switched off is left alone; the owner's markup is never overridden; authority off means no decisions; "only while I'm
 *     away" stands back with the owner there, and "always help" does only the routine work.</li>
 * <li><b>bd04</b>: a hardworking deputy against an easygoing one over a week, the same firm, stock and demand: fewer late
 *     openings, fuller shelves, fewer sales missed, a better rating, measured.</li>
 * <li><b>bd05</b>: a dishonest deputy's skim shows in the books ("till short") and a hand sees it; reported to the watch.</li>
 * <li><b>bd06</b>: the "While you were away" report at login, with the deputy's word, and on the business's page; the pages
 *     carry the deputy's buttons and no commands.</li>
 * </ul>
 * Each on its own ground, x 8,810,000 to 8,840,000 at z 73,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BusinessDeputyGameTests {

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

    // ============================================================ the business while you're away

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bd01_staff_keep_working")
    public static void bd01_staff_keep_working(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8810000, 4);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                UUID absent = UUID.randomUUID();
                Business b = firm(level, t, "baker", absent, "Absent Owner");
                staff(t, b, 1);
                MyBusinesses.awayForTests(t.id(), b, absent, day(level) - 3);
                boolean paused = MyBusinesses.pausedTodayForTests(t.id(), b, day(level));
                Deputy.Charge ch = Deputy.chargeForTests(level, t.id(), b);
                boolean routine = Deputy.morningForTests(level, t.v(), b, day(level));
                int staffDays = rec(t, b).getList("staffLog", Tag.TAG_COMPOUND).size();
                // a farm with a folk hand, its owner offline
                BlockPos at = Kit.surface(level, t.heart().getX() + 30, t.heart().getZ() + 30);
                for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) level.setBlock(at.offset(dx, -1, dz), Blocks.FARMLAND.defaultBlockState(), 3);
                ServerPlayer owner = player(helper, t, at.offset(1, 0, 1), 30);
                PlayerFarms.registerForTests(level, owner, "farm", "Owner's Farm");
                PlayerFarms.Farm f = PlayerFarms.allForTests().get(0);
                Businesses.of(t.id(), "pfarm:" + f.id).till += 5000;
                for (int i = 2; i < t.folk().size(); i++) t.folk().get(i).setJob(StationTask.NONE);
                String hired = PlayerFarms.hireForTests(level, f.id);
                Kit.noLeftoverPlayers(level);                               // the owner logs off
                PlayerFarms.morningForTests(level, f.id, day(level));
                Kit.log("[VT] bd01 paused " + paused + " charge " + ch + " routine " + routine + " staff days " + staffDays + " | farm " + hired + " hands " + f.hands.size() + " paused " + f.paused);
                helper.assertTrue(!paused && ch.mode() == Deputy.Mode.STAFF, "folk staff keep it open with the owner away");
                helper.assertTrue(!routine && staffDays == 1, "the staff carry on (nobody reorders)");
                helper.assertTrue(!f.hands.isEmpty() && !f.paused, "the farm's folk hands keep working");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bd02_pause_all_players_away")
    public static void bd02_pause_all_players_away(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8814000, 3);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                UUID a = UUID.randomUUID(), b2 = UUID.randomUUID();
                Business b = firm(level, t, "baker", a, "Owner A");
                MyBusinesses.awayForTests(t.id(), b, a, day(level) - 2);
                boolean alone = MyBusinesses.pausedTodayForTests(t.id(), b, day(level));
                b.owners.clear();
                b.owners.add(new Business.Stake(Business.Holder.PLAYER, a, "Owner A", 6000));
                b.owners.add(new Business.Stake(Business.Holder.PLAYER, b2, "Partner B", 4000));
                boolean partnerAbout = MyBusinesses.pausedTodayForTests(t.id(), b, day(level));
                MyBusinesses.awayForTests(t.id(), b, b2, day(level) - 1);
                boolean bothAway = MyBusinesses.pausedTodayForTests(t.id(), b, day(level));
                staff(t, b, 1);
                boolean withFolk = MyBusinesses.pausedTodayForTests(t.id(), b, day(level));
                Kit.log("[VT] bd02 all player staff away " + alone + ", a partner about " + partnerAbout + ", both away " + bothAway + ", a folk hand on " + withFolk
                    + " | notes " + b.notes.size());
                helper.assertTrue(alone && bothAway, "shut when every member of staff is a player and none is about");
                helper.assertTrue(!partnerAbout && !withFolk, "a partner about, or a folk hand, keeps it open");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    // ============================================================ the deputy

    /** A greengrocer's with a deputy (hand 1) and another hand (2), carrots in the stores at half a coin. */
    private static Business shop(ServerLevel level, Town t, UUID owner, String name, int deputyAt, int handAt) {
        Business b = firm(level, t, "greengrocer", owner, name);
        VillageFolkEntity d = staff(t, b, deputyAt);
        if (handAt > 0) staff(t, b, handAt);
        Crime.honestyForTests(d, 70);
        Deputy.marksForTests(d, EnumSet.noneOf(Temperament.Mark.class));
        Deputy.appointForTests(level, t.id(), b, d);
        b.till = 10000;
        return b;
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bd03_deputy_within_limits")
    public static void bd03_deputy_within_limits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8818000, 5);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            Businesses.spareAllForTests(true);
            ItemStack carrot = new ItemStack(Items.CARROT);
            Businesses.storesPriceForTests(carrot, 0.5);
            try {
                fill(t.id(), t.store(), new ItemStack(Items.CARROT, 640));
                UUID absent = UUID.randomUUID();
                Business b = shop(level, t, absent, "Absent Owner", 1, 2);
                Deputy.ordersForTests(t.id(), b, 10, 5);
                Map<String, Integer> wants = Map.of(Stockroom.key(carrot), 70);
                long day0 = day(level);
                Deputy.Charge ch = Deputy.chargeForTests(level, t.id(), b);
                CompoundTag d1 = Deputy.dayForTests(level, t.v(), b, day0, true, wants);
                long held = b.stock.containsKey(Stockroom.key(carrot)) ? b.stock.get(Stockroom.key(carrot)).qty : 0;
                long due = b.ownersDue.getOrDefault(absent, 0L);
                double m1 = b.markup;
                // a task off: no restock
                Deputy.setForTests(t.id(), b, "task:restock", false);
                b.stock.clear();
                CompoundTag d2 = Deputy.dayForTests(level, t.v(), b, day0 + 1, true, wants);
                Deputy.setForTests(t.id(), b, "task:restock", true);
                // the owner's markup stays
                rec(t, b).putLong("ownerPrice", day0);
                b.markup = 0.33;
                Deputy.dayForTests(level, t.v(), b, day0 + 2, true, wants);
                double kept = b.markup;
                // authority off: no decisions at all
                Deputy.setForTests(t.id(), b, "on", false);
                int logged = rec(t, b).getList("depLog", Tag.TAG_COMPOUND).size();
                Deputy.Charge off = Deputy.chargeForTests(level, t.id(), b);
                Deputy.morningForTests(level, t.v(), b, day0 + 3);
                int loggedOff = rec(t, b).getList("depLog", Tag.TAG_COMPOUND).size();
                Deputy.setForTests(t.id(), b, "on", true);
                // only while I'm away: the owner at the business, it stands back; always help: routine only
                ServerPlayer p = player(helper, t, t.heart(), 0);
                b.owners.clear();
                b.owners.add(new Business.Stake(Business.Holder.PLAYER, p.getUUID(), p.getGameProfile().getName(), Business.WHOLE));
                Deputy.nearForTests(level, t.id(), b, p.getUUID());
                Deputy.Charge present = Deputy.chargeForTests(level, t.id(), b);
                boolean routine = Deputy.morningForTests(level, t.v(), b, day0 + 4);
                int loggedPresent = rec(t, b).getList("depLog", Tag.TAG_COMPOUND).size();
                Deputy.setForTests(t.id(), b, "always", true);
                rec(t, b).remove("ownerPrice");
                b.markup = 0.33;
                b.till = 10000;
                b.stock.clear();
                Deputy.morningForTests(level, t.v(), b, day0 + 5);
                ListTag log = rec(t, b).getList("depLog", Tag.TAG_COMPOUND);
                CompoundTag helping = log.getCompound(log.size() - 1);
                Kit.log("[VT] bd03 charge " + ch + " | day1 spend " + d1.getLong("spend") + " held " + held + " banked " + d1.getLong("banked") + " due " + due + " till " + b.till
                    + " markup " + m1 + " | task off spend " + d2.getLong("spend") + " | owner's markup kept " + kept + " | off " + off + " logged " + logged + "->" + loggedOff
                    + " | present " + present + " routine " + routine + " logged " + loggedPresent + " | always: full " + helping.getBoolean("full") + " spend " + helping.getLong("spend")
                    + " banked " + helping.getLong("banked") + " markup " + b.markup);
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(ch.mode() == Deputy.Mode.DEPUTY, "the deputy in charge with the owner away");
                helper.assertTrue(d1.getLong("spend") > 0 && d1.getLong("spend") <= 1000 && held > 0, "restocked, within the day's limit");
                helper.assertTrue(d1.getLong("banked") > 0 && due == d1.getLong("banked"), "the takings banked for the owner");
                helper.assertTrue(m1 >= 0.15 && m1 <= 0.60, "the day's markup within the owner's range");
                helper.assertTrue(d2.getLong("spend") == 0, "a task switched off is left alone");
                helper.assertTrue(kept == 0.33, "the owner's markup never overridden");
                helper.assertTrue(off.mode() != Deputy.Mode.DEPUTY && logged == loggedOff, "authority off: no decisions");
                helper.assertTrue(present.mode() == Deputy.Mode.OWNER && routine && loggedPresent == loggedOff, "only while I'm away: it stands back");
                helper.assertTrue(!helping.getBoolean("full") && helping.getLong("spend") == 0 && b.markup == 0.33, "always help: routine work, no prices or spending");
            } finally {
                Enterprise.autoForTests(true);
                Businesses.spareAllForTests(false);
                Businesses.storesPriceForTests(carrot, null);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bd04_hardworking_vs_easygoing")
    public static void bd04_hardworking_vs_easygoing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8822000, 6);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            Businesses.spareAllForTests(true);
            ItemStack carrot = new ItemStack(Items.CARROT);
            Businesses.storesPriceForTests(carrot, 0.5);
            try {
                fill(t.id(), t.store(), new ItemStack(Items.CARROT, 2000));
                Business hard = shop(level, t, UUID.randomUUID(), "Owner H", 1, 0);
                Business easy = shop(level, t, UUID.randomUUID(), "Owner E", 2, 0);
                Deputy.marksForTests(t.folk().get(1), EnumSet.of(Temperament.Mark.HARDWORKING));
                Deputy.marksForTests(t.folk().get(2), EnumSet.of(Temperament.Mark.EASYGOING));
                String key = Stockroom.key(carrot);
                Map<String, Integer> wants = Map.of(key, 70);
                long day0 = day(level);
                int[] late = new int[2];
                long[] missed = new long[2], sold = new long[2];
                double[] fill = new double[2];
                Business[] both = { hard, easy };
                for (int i = 0; i < 7; i++) {
                    for (int k = 0; k < 2; k++) {
                        Business b = both[k];
                        Deputy.ordersForTests(t.id(), b, 40, 3);
                        CompoundTag d = Deputy.dayForTests(level, t.v(), b, day0 + i, true, wants);
                        if (d.getBoolean("late")) late[k]++;
                        fill[k] += d.getDouble("fill") / 7;
                        // the same day's trade for each: thirty wanted, fewer while its doors are still shut
                        long demand = d.getBoolean("late") ? 21 : 30;
                        Business.Lot lot = b.stock.get(key);
                        long have = lot == null ? 0 : lot.qty;
                        long n = Math.min(have, demand);
                        if (n > 0) { lot.cost -= Math.round(lot.each() * n); lot.qty -= n; }
                        b.till += n * 80;
                        sold[k] += n;
                        missed[k] += 30 - n;
                    }
                }
                int rh = Deputy.ratingForTests(t.id(), hard), re = Deputy.ratingForTests(t.id(), easy);
                List<String> whyE = Deputy.reasonsForTests(t.id(), easy);
                Kit.log(String.format(java.util.Locale.ROOT, "[VT] bd04 hardworking: late %d, shelves %.0f%%, sold %d, missed %d, rating %d | easygoing: late %d, shelves %.0f%%, sold %d, missed %d, rating %d | %s",
                    late[0], fill[0] * 100, sold[0], missed[0], rh, late[1], fill[1] * 100, sold[1], missed[1], re, whyE));
                helper.assertTrue(late[0] < late[1] && late[1] >= 2, "the easygoing one opens late more often");
                helper.assertTrue(fill[0] > fill[1] && missed[0] < missed[1] && sold[0] > sold[1], "the hardworking one keeps the shelves fuller and misses fewer sales");
                helper.assertTrue(rh > re, "and rates better");
                helper.assertTrue(whyE.stream().anyMatch(s -> s.startsWith("Opened late on") && s.endsWith("easygoing")), "the reason in plain words");
            } finally {
                Enterprise.autoForTests(true);
                Businesses.spareAllForTests(false);
                Businesses.storesPriceForTests(carrot, null);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bd05_dishonest_skim")
    public static void bd05_dishonest_skim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8826000, 5);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                ServerPlayer p = player(helper, t, t.heart(), 0);
                Business b = shop(level, t, p.getUUID(), p.getGameProfile().getName(), 1, 2);
                Crime.honestyForTests(t.folk().get(1), 20);
                t.folk().get(1).persona().feelFor(p.getUUID(), p.getGameProfile().getName(), -60);   // not fond of the owner: nothing holds it back
                Deputy.setForTests(t.id(), b, "task:bank", false);
                long day0 = day(level);
                int skims = 0;
                String saw = "";
                for (int i = 0; i < 12; i++) {
                    b.till = 10000;
                    CompoundTag d = Deputy.dayForTests(level, t.v(), b, day0 + i, true, Map.of());
                    if (d.getInt("skim") > 0) skims++;
                    if (d.contains("saw")) saw = d.getString("saw");
                }
                boolean inBooks = b.notes.stream().anyMatch(n -> n.toString().contains("till short"));
                List<String> why = Deputy.reasonsForTests(t.id(), b);
                PlayerLife.open(p, "firm", LifePage.args("firm", b.id, "tab", "away"));
                String page = PlayerLife.pageForTests(p).toString();
                PlayerLife.act(p, "firm", "report", LifePage.args("sure", false));
                PlayerLife.act(p, "firm", "report", LifePage.args("sure", true));
                String said = PlayerLife.messageForTests(p);
                boolean gone = Deputy.chargeForTests(level, t.id(), b).mode() != Deputy.Mode.DEPUTY && !rec(t, b).hasUUID("manager");
                Kit.log("[VT] bd05 skims " + skims + " saw by " + saw + " in books " + inBooks + " | " + why + " | page shows it " + page.contains("Money missing") + " | " + said + " | deputy gone " + gone);
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(skims >= 2 && inBooks, "the skim shows in the books");
                helper.assertTrue(saw.contains(t.folk().get(2).displayNameCap()), "a hand saw it taken");
                helper.assertTrue(why.stream().anyMatch(s -> s.contains("dishonest")), "and the reason in plain words");
                helper.assertTrue(page.contains("Money missing") && page.contains("Report to the watch"), "the owner sees it, with the way to deal with it");
                helper.assertTrue(gone, "reported and let go");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bd06_login_report")
    public static void bd06_login_report(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8830000, 5);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                ServerPlayer p = player(helper, t, t.heart(), 0);
                Business b = shop(level, t, p.getUUID(), p.getGameProfile().getName(), 1, 2);
                long day0 = day(level);
                for (int i = 0; i < 3; i++) Deputy.dayForTests(level, t.v(), b, day0 + i, true, Map.of());
                int n = Deputy.loginReportForTests(level, p, day0);
                ListTag rep = rec(t, b).getList("report/" + p.getUUID(), Tag.TAG_STRING);
                String last = rep.isEmpty() ? "" : rep.getString(rep.size() - 1);
                StringBuilder pages = new StringBuilder();
                for (String tab : new String[]{ "summary", "hands", "away" }) {
                    PlayerLife.open(p, "firm", LifePage.args("firm", b.id, "tab", tab));
                    pages.append(PlayerLife.pageForTests(p));
                }
                String all = pages.toString();
                Kit.log("[VT] bd06 reports " + n + " | " + rep + " | page has report " + all.contains("While you were away") + " deputy button " + all.contains("Remove as deputy")
                    + " authority " + all.contains("Deputy in charge: On") + " commands " + all.contains("/village"));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(n == 1 && rep.size() >= 3, "a report at login");
                helper.assertTrue(last.contains(": \"") && rep.getString(0).contains("ran " + b.name), "what was done, and its word");
                helper.assertTrue(all.contains("While you were away") && all.contains("Deputy in charge: On"), "on the business's page, with the deputy's settings");
                helper.assertTrue(!all.contains("/village"), "no commands");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }
}
