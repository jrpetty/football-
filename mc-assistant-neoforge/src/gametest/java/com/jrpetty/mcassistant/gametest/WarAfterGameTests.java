package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AfterWar;
import com.jrpetty.mcassistant.entity.AfterWarStage;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Laws;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Outlaws;
import com.jrpetty.mcassistant.entity.Police;
import com.jrpetty.mcassistant.entity.Prisoners;
import com.jrpetty.mcassistant.entity.Ruins;
import com.jrpetty.mcassistant.entity.Scouts;
import com.jrpetty.mcassistant.entity.Spies;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [war-after] After the war (entity/Prisoners, Ruins, Outlaws): prisoners of war held in the cells, the stockade or the
 * yard, fed out of the captor's stores and set to work in its fields; exchanged and ransomed at the peace, the ransom
 * carried by an envoy afterwards; offered a place when never ransomed; a break for it at night; bought out by a player,
 * or broken out. A town gone to ruin, nothing left pointing at it, decaying, its stores spoiling, taken up again under its
 * old name; a town sacked, its stores carried off and its folk carried away. A player made an outlaw by its crimes: the
 * poster, the shops shut, the bounty out of the treasury, the hunters and the catch, the ways back, a bounty claimed.
 *
 * <p>Each on its own ground in x 2200000-2219999, z 70000, two towns 260 blocks apart where it needs two.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WarAfterGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;
    private static final int APART = 260;

    private static Villages.Village town(ServerLevel level, int x, int n) {
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        return Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
    }

    private static List<VillageFolkEntity> folk(UUID v) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v)) {
            if (a instanceof VillageFolkEntity f) {
                f.ensurePersona();
                out.add(f);
            }
        }
        return out;
    }

    private static VillageFolkEntity guard(UUID v) {
        UUID elder = Villages.elder(v);
        for (VillageFolkEntity f : folk(v)) {
            if (f.isBaby() || f.getUUID().equals(elder) || Spies.held(f)) continue;
            f.setJob(AssistantEntity.StationTask.GUARD);
            f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            return f;
        }
        return null;
    }

    private static void stock(ServerLevel level, UUID v, ItemStack... goods) {
        List<BlockPos> stores = Villages.storeChests(level, v);
        if (stores.isEmpty()) return;
        Container c = (Container) level.getBlockEntity(stores.get(0));
        int slot = 0;
        for (ItemStack s : goods) {
            while (slot < c.getContainerSize() && !c.getItem(slot).isEmpty()) slot++;
            if (slot >= c.getContainerSize()) break;
            c.setItem(slot, s);
        }
        c.setChanged();
    }

    private static int count(ServerLevel level, UUID v, net.minecraft.world.item.Item item) {
        return Market.stock(level, v, s -> s.is(item));
    }

    private static boolean chronicled(UUID v, String words) {
        for (Chronicle.Entry e : Chronicle.of(v)) if (e.text().contains(words)) return true;
        return false;
    }

    private static void start(ServerLevel level) {
        Kit.reset(level);
        level.setDayTime(40L * 24000L + 2500L);
    }

    private static void war(ServerLevel level, Villages.Village a, Villages.Village b) {
        long day = level.getDayTime() / 24000L;
        Ledger.relate(a.id(), b.id(), -70 - Ledger.relation(a.id(), b.id()));
        WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.TRIBUTE, 20, "twenty coins in tribute");
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    // ------------------------------------------------------------------ wa01

    /**
     * Prisoners taken: on the captor's books with rank and ransom, told at home; two in the watch house's cells (the doors
     * shut on them), the third in the yard; the town wants a stockade; dinner out of the captor's stores; the card and the
     * board say so; their kin at home grieve.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "wa01_held")
    public static void wa01_held(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2200000;
        Villages.Village a = town(level, x, 7), b = town(level, x + APART, 7);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        war(level, a, b);
        BlockPos site = Kit.surface(level, x + 14, Z + 14);
        Police.watchHouseForTests(level, a, site);
        int took = Prisoners.takeNow(level, a, b, 3);
        List<Prisoners.Pow> held = Prisoners.heldBy(a.id());
        Kit.log("wa01 took " + took + ": " + held.size() + " held; ranks " + held.stream().map(Prisoners.Pow::rank).toList());
        helper.assertTrue(took == 3 && held.size() == 3, "three prisoners on the captor's books: " + held.size());
        helper.assertTrue(chronicled(b.id(), "taken prisoner by " + Villages.name(a.id())), "told at home");
        helper.assertTrue(Prisoners.stockadeWanted(a.id()) && Villages.projectsWanted(a.id()).contains(Prisoners.STOCKADE),
            "more prisoners than two cells: a stockade wanted: " + Villages.projectsWanted(a.id()));
        stock(level, a.id(), new ItemStack(Items.BREAD, 10));
        helper.runAtTickTime(200, () -> {
            int cells = 0, yard = 0, inside = 0;
            List<String> where = new ArrayList<>();
            for (Prisoners.Pow p : Prisoners.heldBy(a.id())) {
                if (!(level.getEntity(p.who()) instanceof VillageFolkEntity f)) continue;
                String kind = Prisoners.placeForTests(level, f);
                where.add(f.displayNameCap() + " " + kind + " @" + f.blockPosition().toShortString());
                if (kind.equals("CELL")) cells++;
                if (kind.equals("YARD")) yard++;
                if (kind.equals("CELL") && f.blockPosition().distSqr(site) < 6 * 6) inside++;
            }
            Kit.log("wa01 at tick 200: " + where);
            helper.assertTrue(cells == 2 && yard == 1 && inside == 2, "two in the cells, one in the yard: " + where);
            VillageFolkEntity one = (VillageFolkEntity) level.getEntity(Prisoners.heldBy(a.id()).get(0).who());
            int bread0 = count(level, a.id(), Items.BREAD);
            boolean fed = Prisoners.feedForTests(level, a, one);
            int bread1 = count(level, a.id(), Items.BREAD);
            String card = Prisoners.cardForTests(one);
            String board = String.join(" / ", VillageBoards.compose(level, a.id()));
            String home = String.join(" / ", VillageBoards.compose(level, b.id()));
            Kit.log("wa01 fed " + fed + " bread " + bread0 + "->" + bread1 + "; card: " + card);
            helper.assertTrue(fed && bread1 == bread0 - 1, "dinner out of the captor's stores: " + bread0 + " -> " + bread1);
            helper.assertTrue(card.startsWith("prisoner of " + Villages.name(a.id()) + " since day"), "its card: " + card);
            helper.assertTrue(board.contains("Prisoners of war: 3 held") && home.contains("Ours held prisoner"), "both boards say so");
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wa02

    /**
     * The peace: one of each exchanged, the other two held for ransom; their town, with the coin, sends an envoy with the
     * ransom; heard at the captor's board, the coin goes into its treasury and the two walk home.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wa02_ransom")
    public static void wa02_ransom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2202000;
        Villages.Village a = town(level, x, 7), b = town(level, x + APART, 7);
        war(level, a, b);
        Prisoners.takeNow(level, a, b, 3);
        Prisoners.takeNow(level, b, a, 1);
        long day = level.getDayTime() / 24000L;
        String text = WarAndPeace.makePeace(level, a.id(), b.id(), day, WarAndPeace.terms(level, a.id(), b.id()), "by decree", null);
        List<Prisoners.Pow> left = Prisoners.heldBy(a.id());
        Kit.log("wa02 the treaty: " + text + "; left held by A: " + left.size() + ", by B: " + Prisoners.heldBy(b.id()).size());
        helper.assertTrue(!Wars.atWar(a.id(), b.id()), "peace");
        helper.assertTrue(left.size() == 2 && Prisoners.heldBy(b.id()).isEmpty() && left.stream().allMatch(Prisoners.Pow::forRansom),
            "one of each exchanged, two held for ransom: " + text);
        helper.assertTrue(text.contains("exchanged") && text.contains("held for ransom"), "the treaty says so: " + text);
        int owed = left.stream().mapToInt(Prisoners.Pow::worth).sum();
        Ledger.addCoins(b.id(), 200);
        int treasury0 = Ledger.coins(a.id());
        Prisoners.ransomDayForTests(level, b);
        int carried = Prisoners.carriedForTests(b.id(), a.id());
        Kit.log("wa02 the ransom: " + owed + " owed, " + carried + " carried");
        helper.assertTrue(carried >= owed, "the envoy carries the ransom: " + carried + " of " + owed);
        String outcome = Prisoners.ransomArrivesForTests(level, b.id(), a.id());
        int treasury1 = Ledger.coins(a.id());
        Kit.log("wa02 heard: " + outcome + "; treasury " + treasury0 + " -> " + treasury1 + "; held " + Prisoners.heldBy(a.id()).size());
        helper.assertTrue(Prisoners.heldBy(a.id()).isEmpty() && treasury1 == treasury0 + owed, "ransomed: " + outcome);
        boolean walking = left.stream().allMatch(p -> level.getEntity(p.who()) instanceof VillageFolkEntity f && f.expedition() != null && !Spies.held(f));
        helper.assertTrue(walking, "both on the road home");
        helper.assertTrue(chronicled(a.id(), "was ransomed for"), "the captor's chronicle");
        Kit.noLeftoverPlayers(level);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wa03

    /** Never ransomed: a place offered and taken (its town's roll for the captor's); a break for it, caught by a guard close by, and not. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wa03_fates")
    public static void wa03_fates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2204000;
        Villages.Village a = town(level, x, 7), b = town(level, x + APART, 7);
        war(level, a, b);
        Prisoners.takeNow(level, a, b, 3);
        List<Prisoners.Pow> held = Prisoners.heldBy(a.id());
        VillageFolkEntity stays = (VillageFolkEntity) level.getEntity(held.get(0).who());
        VillageFolkEntity runs = (VillageFolkEntity) level.getEntity(held.get(1).who());
        VillageFolkEntity tries = (VillageFolkEntity) level.getEntity(held.get(2).who());
        Prisoners.offerForTests(level, stays, true);
        Kit.log("wa03 offered: " + stays.displayNameCap() + " now of " + Villages.name(stays.ownerId()));
        helper.assertTrue(a.id().equals(stays.ownerId()) && !Prisoners.isPow(stays.getUUID()) && chronicled(a.id(), "took the town's oath"),
            "took a place in the town that held it");
        // Away from every guard: it gets clean away. With a guard at its elbow: caught at it.
        for (VillageFolkEntity g : folk(a.id())) if (g.stationTask() == AssistantEntity.StationTask.GUARD) g.setJob(AssistantEntity.StationTask.FARM);
        boolean away = Prisoners.escapeForTests(level, runs);
        VillageFolkEntity g = guard(a.id());
        g.moveTo(tries.getX() + 1, tries.getY(), tries.getZ());
        boolean caught = !Prisoners.escapeForTests(level, tries);
        Kit.log("wa03 escape: away " + away + " (walking " + (runs.expedition() != null) + "), caught " + caught);
        helper.assertTrue(away && !Prisoners.isPow(runs.getUUID()) && runs.expedition() != null, "escaped, and on its way home");
        helper.assertTrue(caught && Prisoners.isPow(tries.getUUID()), "caught at it by the guard beside it");
        helper.assertTrue(chronicled(b.id(), "escaped from"), "its town hears it is coming");
        helper.succeed();
    }

    // ------------------------------------------------------------------ wa04

    /**
     * A town dies out: a ruin, with its name, its buildings and its stores; its war over, its board a ruin's; it decays
     * (only its own blocks), its meat rots; a scout's atlas has it; a Village Folk Spawner set down in it, it rises again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "wa04_ruin")
    public static void wa04_ruin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2206000;
        Villages.Village t = town(level, x, 6), w = town(level, x + APART, 6);
        Ledger.relate(t.id(), w.id(), 0);
        war(level, w, t);
        BlockPos site = Kit.surface(level, x + 14, Z + 14);
        Police.watchHouseForTests(level, t, site);
        stock(level, t.id(), new ItemStack(Items.BEEF, 20), new ItemStack(Items.IRON_INGOT, 7));
        String name = Villages.name(t.id());
        UUID id = t.id();
        BlockPos heart = t.centre();
        for (VillageFolkEntity f : folk(id)) f.discard();
        if (Villages.get(id) != null) Ruins.fallForTests(level, t, "died out");
        Ruins.Ruin r = Ruins.of(id);
        Kit.log("wa04 ruin: " + (r == null ? "none" : r.title() + ", " + r.buildings().size() + " buildings, " + r.chests().size() + " chests, " + r.how()));
        helper.assertTrue(Villages.get(id) == null && r != null && r.name().equals(name) && r.chests().size() > 0, "the ruins of " + name);
        helper.assertTrue(!Wars.atWar(w.id(), id) && chronicled(w.id(), name + " is no more"), "the war with it over");
        boolean atlas = Scouts.atlas(w.id()).stream().anyMatch(f -> f.label().equals("the ruins of " + name));
        helper.assertTrue(atlas, "into the neighbour's atlas by word of its fall");
        List<String> board = Ruins.board(level, id);
        helper.assertTrue(board != null && board.get(0).equals("TH|The ruins of " + name), "its board a ruin's: " + board);
        // Decay: five days' worth, only the town's own blocks; the chests kept.
        Ruins.liveForTests(true);
        BlockPos mine = site.offset(9, 0, 9);
        level.setBlock(mine, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);         // a player's block beside it: never touched
        int gone = Ruins.decayNow(level, id, 5);
        int chests = 0;
        for (BlockPos c : r.chests()) if (level.getBlockEntity(c) instanceof Container) chests++;
        Kit.log("wa04 decay: " + gone + " blocks; chests still " + chests + "/" + r.chests().size());
        helper.assertTrue(gone > 0 && chests == r.chests().size() && level.getBlockState(mine).is(Blocks.DIAMOND_BLOCK), "decayed, the chests and the player's block kept");
        Ruins.spoilForTests(level);
        int flesh = count0(level, r.chests(), Items.ROTTEN_FLESH), iron = count0(level, r.chests(), Items.IRON_INGOT);
        Kit.log("wa04 spoiled: rotten flesh " + flesh + ", iron " + iron);
        helper.assertTrue(flesh > 0 && iron == 7, "the meat going off, the iron as it was");
        // Taken up again: the spawner set down at its heart.
        VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        Villages.Village again = Villages.nearest(level, heart, 40);
        Ruins.Ruin after = Ruins.of(id);
        Kit.log("wa04 again: " + (again == null ? "none" : Villages.name(again.id()) + ", buildings " + Ledger.buildings(again.id()).size()));
        helper.assertTrue(again != null && Villages.name(again.id()).equals(name) && after != null && again.id().equals(after.resettled())
            && Ledger.buildings(again.id()).stream().anyMatch(bd -> bd.structure().equals("watchhouse")), name + " rises again, with its old buildings");
        helper.assertTrue(chronicled(again.id(), "rises again"), "its chronicle says so");
        helper.succeed();
    }

    /** Everything in a town's stores, all told. */
    private static int stores(ServerLevel level, UUID v) {
        int n = 0;
        for (BlockPos c : Villages.storeChests(level, v)) {
            if (!(level.getBlockEntity(c) instanceof Container box)) continue;
            for (int i = 0; i < box.getContainerSize(); i++) n += box.getItem(i).getCount();
        }
        return n;
    }

    private static int count0(ServerLevel level, List<BlockPos> chests, net.minecraft.world.item.Item item) {
        int n = 0;
        for (BlockPos c : chests) {
            if (!(level.getBlockEntity(c) instanceof Container box)) continue;
            for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) n += box.getItem(i).getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ wa05

    /** The sack: a third of the stores carried off in the victors' packs, and home into their stores; too few left, the folk carried off, a ruin. */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wa05_sack")
    public static void wa05_sack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2208000;
        Villages.Village w = town(level, x, 8), v = town(level, x + APART, 4);
        war(level, w, v);
        Police.watchHouseForTests(level, v, Kit.surface(level, x + APART + 14, Z + 14));   // a building of its own, to leave a ruin
        stock(level, v.id(), new ItemStack(Items.WHEAT, 30), new ItemStack(Items.COBBLESTONE, 30), new ItemStack(Items.OAK_LOG, 30),
            new ItemStack(Items.IRON_INGOT, 12));
        int before = stores(level, v.id());
        List<VillageFolkEntity> carriers = new ArrayList<>(folk(w.id()).subList(0, 3));
        UUID vid = v.id();
        String vn = Villages.name(vid);
        List<VillageFolkEntity> theirs = folk(vid);
        int packs0 = 0;
        for (VillageFolkEntity c : carriers) packs0 += c.countMatching(s -> !s.isEmpty());
        String said = Ruins.sackForTests(level, v, w, carriers);
        int carried = -packs0;
        for (VillageFolkEntity c : carriers) carried += c.countMatching(s -> !s.isEmpty());
        Kit.log("wa05 sacked: " + said + "; carried " + carried + " of " + before);
        helper.assertTrue(carried > 0 && carried < before, "a share carried off: " + carried + " of " + before);
        int home0 = stores(level, w.id());
        Ruins.unloadForTests(level, w, carriers);
        int home1 = stores(level, w.id());
        Kit.log("wa05 home: the victors' stores " + home0 + " -> " + home1);
        helper.assertTrue(home1 - home0 == carried, "all of it into the victors' stores: " + (home1 - home0) + " of " + carried);
        Ruins.Ruin r = Ruins.of(vid);
        long joined = theirs.stream().filter(f -> w.id().equals(f.ownerId())).count();
        Kit.log("wa05 the ruin: " + (r == null ? "none" : r.how()) + "; " + joined + " of " + theirs.size() + " now of " + Villages.name(w.id()));
        helper.assertTrue(Villages.get(vid) == null && r != null && r.how().contains("sacked by " + Villages.name(w.id())), vn + " left in ruins");
        helper.assertTrue(joined == theirs.size(), "its folk carried off, and given a place: " + joined);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wa06

    /**
     * A murder makes an outlaw: the poster, the shops shut to it, the guards' on sight, a bounty out of the treasury; the
     * hunters ride out; their blow that would take it low catches it: the bounty in their purses, its fine out of its purse.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wa06_outlaw")
    public static void wa06_outlaw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2210000;
        Villages.Village v = town(level, x, 8);
        Outlaws.liveForTests(true);
        Outlaws.creativeForTests(true);
        for (int i = 0; i < 3; i++) guard(v.id());
        Ledger.addCoins(v.id(), 200 - Ledger.coins(v.id()));
        ServerPlayer p = player(helper, v.centre().offset(3, 0, 3), 100);
        int w = Outlaws.crime(level, v, p, Outlaws.Deed.KILL, "killing Ada");
        int bounty = Outlaws.bounty(v.id(), p.getUUID());
        String board = String.join(" / ", VillageBoards.compose(level, v.id()));
        Kit.log("wa06 wanted " + w + ", outlaw " + Outlaws.isOutlaw(v.id(), p.getUUID()) + ", bounty " + bounty + ", treasury " + Ledger.coins(v.id()));
        helper.assertTrue(w >= Outlaws.OUTLAW && Outlaws.isOutlaw(v.id(), p.getUUID()), "an outlaw");
        helper.assertTrue(bounty > 0 && Ledger.coins(v.id()) == 200 - bounty, "a bounty out of the treasury: " + bounty);
        helper.assertTrue(Laws.banished(v.id(), p.getUUID(), level.getDayTime() / 24000L), "the shops shut to it");
        helper.assertTrue(board.contains("WANTED, OUTLAW: " + p.getName().getString()), "the poster on the board");
        helper.assertTrue(chronicled(v.id(), "declared an outlaw"), "the chronicle");
        boolean rode = Outlaws.huntForTests(level, v, p);
        List<UUID> hunters = Outlaws.huntersForTests(v.id(), p.getUUID());
        Kit.log("wa06 hunt: " + rode + ", " + hunters.size() + " hunters");
        helper.assertTrue(rode && !hunters.isEmpty() && hunters.size() <= 3, "a party of the watch after it");
        VillageFolkEntity h = (VillageFolkEntity) level.getEntity(hunters.get(0));
        int purse0 = 0;
        for (UUID id : hunters) purse0 += ((VillageFolkEntity) level.getEntity(id)).purse();
        int coins0 = Market.coinsHeld(p);
        boolean caught = Outlaws.blowForTests(p, h, 100.0F);
        int purse1 = 0;
        for (UUID id : hunters) purse1 += ((VillageFolkEntity) level.getEntity(id)).purse();
        int coins1 = Market.coinsHeld(p);
        Kit.log("wa06 caught " + caught + ": hunters' purses " + purse0 + " -> " + purse1 + ", the outlaw's coin " + coins0 + " -> " + coins1);
        helper.assertTrue(caught && !Outlaws.isOutlaw(v.id(), p.getUUID()) && !Outlaws.huntedForTests(v.id(), p.getUUID()), "caught, and the account settled");
        helper.assertTrue(purse1 - purse0 == bounty, "the bounty shared among the hunters");
        helper.assertTrue(coins0 - coins1 == 15 + w / 2, "its fine out of its purse: " + (coins0 - coins1));
        Kit.noLeftoverPlayers(level);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wa07

    /** The ways back: the fine paid at the watch; a gift for a pardon; time and distance (the wanted level eases away). */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wa07_ways_back")
    public static void wa07_ways_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2212000;
        Villages.Village v = town(level, x, 7);
        Outlaws.liveForTests(true);
        Outlaws.creativeForTests(true);
        VillageFolkEntity g = guard(v.id());
        Ledger.addCoins(v.id(), 100);
        ServerPlayer p = player(helper, v.centre().offset(2, 0, 2), 600);
        Outlaws.crime(level, v, p, Outlaws.Deed.KILL, "killing Fen");
        int bounty = Outlaws.bounty(v.id(), p.getUUID());
        int treasury0 = Ledger.coins(v.id());
        String said = FolkTalk.answer(g, p, TalkTopic.SAY, "I'll pay my fine");
        int treasury1 = Ledger.coins(v.id());
        Kit.log("wa07 paid: " + said + " (treasury " + treasury0 + " -> " + treasury1 + ")");
        helper.assertTrue(!Outlaws.isOutlaw(v.id(), p.getUUID()) && treasury1 > treasury0 + bounty, "the fine paid, the account settled: " + said);
        // Again, and a pardon of the leader for a gift.
        Outlaws.crime(level, v, p, Outlaws.Deed.KILL, "killing Bree");
        VillageFolkEntity elder = null;
        for (VillageFolkEntity f : folk(v.id())) if (f != g && !f.isBaby() && elder == null) elder = f;
        helper.assertTrue(elder != null, "somebody to lead the town");
        Villages.electElder(v.id(), elder, level.getDayTime() / 24000L);
        String asked = FolkTalk.answer(elder, p, TalkTopic.SAY, "I ask for a pardon");
        String paid = FolkTalk.answer(elder, p, TalkTopic.SAY, "I'll pay for a pardon");
        Kit.log("wa07 pardon: " + asked + " / " + paid);
        helper.assertTrue(asked.contains("gift") && !Outlaws.isOutlaw(v.id(), p.getUUID()) && chronicled(v.id(), "pardoned"), "pardoned for a gift: " + paid);
        // Once more, and left to ease: below the line, the bounty back in the treasury.
        Outlaws.crime(level, v, p, Outlaws.Deed.KILL, "killing Moss");
        int held = Outlaws.bounty(v.id(), p.getUUID());
        Outlaws.wantedForTests(v.id(), p.getUUID(), Outlaws.CLEARS + 1);
        int t0 = Ledger.coins(v.id());
        long day = level.getDayTime() / 24000L;
        Outlaws.easeForTests(level, day + 1);
        Kit.log("wa07 eased: outlaw " + Outlaws.isOutlaw(v.id(), p.getUUID()) + ", wanted " + Outlaws.wanted(v.id(), p.getUUID()) + ", treasury " + t0 + " -> " + Ledger.coins(v.id()));
        helper.assertTrue(!Outlaws.isOutlaw(v.id(), p.getUUID()) && Ledger.coins(v.id()) == t0 + held, "let lie: no longer an outlaw, the bounty back");
        Kit.noLeftoverPlayers(level);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wa08

    /** Another player brings an outlaw down: the bounty is its to claim of any guard, booked as earnings; an outlaw who logs off is let be. */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wa08_claim")
    public static void wa08_claim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2214000;
        Villages.Village v = town(level, x, 8);
        Outlaws.liveForTests(true);
        Outlaws.creativeForTests(true);
        for (int i = 0; i < 2; i++) guard(v.id());
        VillageFolkEntity g = guard(v.id());
        Ledger.addCoins(v.id(), 300);
        ServerPlayer outlaw = player(helper, v.centre().offset(4, 0, 4), 0), hunter = player(helper, v.centre().offset(5, 0, 4), 0);
        Outlaws.crime(level, v, outlaw, Outlaws.Deed.KILL, "killing Ash");
        int bounty = Outlaws.bounty(v.id(), outlaw.getUUID());
        Outlaws.huntForTests(level, v, outlaw);
        Outlaws.loggedOffForTests(outlaw.getUUID());
        helper.assertTrue(!Outlaws.huntedForTests(v.id(), outlaw.getUUID()) && Outlaws.isOutlaw(v.id(), outlaw.getUUID()), "logged off: the hunt off, still an outlaw");
        Outlaws.diedForTests(outlaw, hunter);
        int coins0 = Market.coinsHeld(hunter);
        String said = FolkTalk.answer(g, hunter, TalkTopic.SAY, "I've come to claim a bounty");
        int coins1 = Market.coinsHeld(hunter);
        Kit.log("wa08 claim: " + said + "; coins " + coins0 + " -> " + coins1 + " (bounty " + bounty + ")");
        helper.assertTrue(!Outlaws.isOutlaw(v.id(), outlaw.getUUID()) && coins1 - coins0 == bounty && bounty > 0, "the bounty paid to the one who brought it down");
        String again = FolkTalk.answer(g, hunter, TalkTopic.SAY, "I've come to claim a bounty");
        helper.assertTrue(Market.coinsHeld(hunter) == coins1, "once: " + again);
        // The pictures' stage (/village afterwar stage) sets its scene out of the same pieces.
        Kit.hold(level, x + 400, Z + 280, 24);
        Kit.prepare(level, x + 400, Z + 280, 24);
        Kit.hold(level, x + 620, Z + 60, 24);
        Kit.prepare(level, x + 620, Z + 60, 24);
        Kit.hold(level, x + 400, Z + 60, 32);
        Kit.prepare(level, x + 400, Z + 60, 32);
        List<String> staged = AfterWarStage.stage(level, v, new BlockPos(x + 400, 0, Z + 60), null);
        Kit.log("wa08 the stage: " + staged);
        helper.assertTrue(staged.stream().anyMatch(s -> s.startsWith("VIEW cells")) && staged.stream().anyMatch(s -> s.startsWith("VIEW ruin")),
            "the stage's scenes: " + staged);
        Kit.noLeftoverPlayers(level);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wa09

    /** A player buys a prisoner's release of the watch (its town grateful), and breaks another out under a witness's eye (wanted for it). */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wa09_release")
    public static void wa09_release(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2216000;
        Villages.Village a = town(level, x, 8), b = town(level, x + APART, 7);
        Outlaws.liveForTests(true);
        Outlaws.creativeForTests(true);
        VillageFolkEntity g = guard(a.id());
        Prisoners.takeNow(level, a, b, 2);
        List<Prisoners.Pow> held = Prisoners.heldBy(a.id());
        ServerPlayer p = player(helper, a.centre().offset(2, 0, 2), 200);
        Prisoners.Pow first = held.get(0), second = held.get(1);
        int t0 = Ledger.coins(a.id()), c0 = Market.coinsHeld(p);
        String said = FolkTalk.answer(g, p, TalkTopic.SAY, "I'll pay for the release of " + first.name());
        int t1 = Ledger.coins(a.id()), c1 = Market.coinsHeld(p);
        Kit.log("wa09 bought: " + said + "; treasury " + t0 + " -> " + t1 + ", purse " + c0 + " -> " + c1);
        helper.assertTrue(!Prisoners.isPow(first.who()) && t1 - t0 == c0 - c1 && c0 - c1 == first.worth() * 3 / 2, "bought out: " + said);
        VillageFolkEntity freed = (VillageFolkEntity) level.getEntity(first.who());
        helper.assertTrue(freed.persona().affinity(p.getUUID()) >= 20, "it thinks the world of you");
        VillageFolkEntity jailed = (VillageFolkEntity) level.getEntity(second.who());
        p.teleportTo(jailed.getX() + 1, jailed.getY(), jailed.getZ());
        g.moveTo(jailed.getX() + 3, jailed.getY(), jailed.getZ());
        String out = FolkTalk.answer(jailed, p, TalkTopic.SAY, "I'm getting you out of here");
        int w = Outlaws.wanted(a.id(), p.getUUID());
        Kit.log("wa09 broke out: " + out + "; wanted " + w);
        helper.assertTrue(!Prisoners.isPow(second.who()) && jailed.expedition() != null && w >= Outlaws.Deed.BREAKOUT.weight, "broken out, seen: wanted " + w);
        Kit.noLeftoverPlayers(level);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wa10

    /** The stockade: prisoners the cells cannot take kept in its pen; a stint of light work brings the captor's ripe wheat in, sown again. */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wa10_stockade")
    public static void wa10_stockade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 2218000;
        Villages.Village a = town(level, x, 7), b = town(level, x + APART, 7);
        war(level, a, b);
        guard(a.id());
        Prisoners.takeNow(level, a, b, 2);
        helper.assertTrue(Prisoners.stockadeWanted(a.id()), "no cells at all: a stockade wanted");
        BlockPos site = Kit.surface(level, x - 14, Z + 14);
        Prisoners.stockadeForTests(level, a, site);
        helper.assertTrue(!Prisoners.stockadeWanted(a.id()), "the stockade stands");
        // Ripe wheat by the pen, on tilled ground.
        BlockPos field = Kit.surface(level, x - 14, Z + 26);
        for (int dx = 0; dx < 4; dx++) {
            BlockPos p = field.offset(dx, 0, 0);
            level.setBlock(p.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(p, ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
        }
        helper.runAtTickTime(160, () -> {
            List<String> where = new ArrayList<>();
            int pen = 0;
            for (Prisoners.Pow p : Prisoners.heldBy(a.id())) {
                VillageFolkEntity f = (VillageFolkEntity) level.getEntity(p.who());
                String kind = Prisoners.placeForTests(level, f);
                where.add(kind + " @" + f.blockPosition().toShortString());
                if (kind.equals("PEN") && Math.abs(f.getX() - site.getX()) <= 4 && Math.abs(f.getZ() - site.getZ()) <= 4) pen++;
            }
            Kit.log("wa10 held: " + where + " (the pen at " + site.toShortString() + ")");
            helper.assertTrue(pen == 2, "both kept in the stockade's pen: " + where);
            VillageFolkEntity worker = (VillageFolkEntity) level.getEntity(Prisoners.heldBy(a.id()).get(0).who());
            worker.moveTo(field.getX() + 0.5, field.getY(), field.getZ() + 2.5);
            int wheat0 = count(level, a.id(), Items.WHEAT);
            int cut = Prisoners.workForTests(level, worker, 20);
            int wheat1 = count(level, a.id(), Items.WHEAT);
            BlockState sown = level.getBlockState(field);
            Kit.log("wa10 work: " + cut + " crops, wheat in the stores " + wheat0 + " -> " + wheat1 + "; the field " + sown);
            helper.assertTrue(cut >= 4 && wheat1 - wheat0 >= 4 && sown.is(Blocks.WHEAT) && !((CropBlock) Blocks.WHEAT).isMaxAge(sown),
                "the ripe wheat cut, into the stores, and sown again");
            helper.succeed();
        });
    }
}
