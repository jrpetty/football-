package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.BusinessOffers;
import com.jrpetty.mcassistant.entity.CoreSites;
import com.jrpetty.mcassistant.item.CoreItems;
import com.jrpetty.mcassistant.block.BusinessCoreBlockEntity;
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
 * [wpb] The Business Core (CoreSites, CorePage, CoreHub).
 * <ul>
 * <li><b>bz01</b>: a new business gets its Core at its premises (a player's firm and a folk's); a visitor opens the public
 *     face (read-only); its owner opens the full hub (the status line, the tour), and decisions through it take (hours, the
 *     deputy's switches); no commands on any page.</li>
 * <li><b>bz02</b>: protected: a stranger breaking it gets no block and the business notes it; its owner may take it up; a second
 *     in the same building is refused; set elsewhere at the premises, it moves.</li>
 * </ul>
 * Each on its own ground, x 8,870,000 to 8,890,000 at z 73,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BusinessCoreGameTests {

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

    /** Clear ground beside the heart for a firm's premises: its Core goes there. */
    private static BlockPos premises(ServerLevel level, Town t, int dx) {
        BlockPos at = Kit.surface(level, t.heart().getX() + dx, t.heart().getZ() - 10);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-5, 0, -5), at.offset(5, 3, 5))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-5, -1, -5), at.offset(5, -1, 5))) level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
        return at;
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bz01_core_views")
    public static void bz01_core_views(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8870000, 4);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            CoreSites.resetForTests();
            try {
                ServerPlayer owner = player(helper, t, t.heart(), 0);
                ServerPlayer visitor = player(helper, t, t.heart().east(2), 0);
                Business b = firm(level, t, "baker", owner.getUUID(), owner.getGameProfile().getName());
                b.at = premises(level, t, 0);
                staff(t, b, 1);
                VillageFolkEntity founder = t.folk().get(2);
                Business folkFirm = Enterprise.foundForTests(level, t.v(), "greengrocer", founder);
                folkFirm.at = premises(level, t, 16);
                owner.teleportTo(b.at.getX() + 0.5, b.at.getY(), b.at.getZ() + 2.5);
                BlockPos core = CoreSites.ensureForTests(level, owner, t.id(), "firm:" + b.id);
                BlockPos folkCore = CoreSites.ensureForTests(level, owner, t.id(), "firm:" + folkFirm.id);
                boolean isCore = core != null && level.getBlockState(core).is(CoreItems.CORE.get()) && level.getBlockEntity(core) instanceof BusinessCoreBlockEntity be && be.bound();
                String sign = core != null && level.getBlockEntity(core) instanceof BusinessCoreBlockEntity be2 ? be2.name() + " / " + be2.status() : "";
                // the visitor: the public face
                CoreSites.openForTests(visitor, core);
                String vpage = PlayerLife.ctx(visitor).page, vtext = PlayerLife.pageForTests(visitor).toString();
                CoreSites.openForTests(visitor, folkCore);
                String ftext = PlayerLife.pageForTests(visitor).toString();
                // the owner: the hub, with its tour the first time
                CoreSites.openForTests(owner, core);
                String opage = PlayerLife.ctx(owner).page, otext = PlayerLife.pageForTests(owner).toString();
                CompoundTag f = new CompoundTag();
                f.putString("open", "8");
                f.putString("close", "18");
                f.putString("days", "63");
                CompoundTag a = new CompoundTag();
                a.put("f", f);
                PlayerLife.act(owner, "firm", "hours", a);
                String hoursSaid = PlayerLife.messageForTests(owner);
                boolean hours = Enterprise.recordForTests(t.id(), b).contains("hours");
                PlayerLife.act(owner, "firm", "depnotify", new CompoundTag());
                boolean notify = Enterprise.recordForTests(t.id(), b).getBoolean("depNotify");
                StringBuilder all = new StringBuilder(vtext).append(ftext).append(otext);
                for (String tab : new String[]{ "hands", "stock", "prices", "away", "money", "offers", "history", "more" }) {
                    PlayerLife.open(owner, "firm", LifePage.args("firm", b.id, "tab", tab));
                    all.append(PlayerLife.pageForTests(owner));
                }
                Kit.log("[VT] bz01 core " + core + " " + isCore + " sign " + sign + " folk's core " + folkCore + " | visitor page " + vpage + " sells " + vtext.contains("What it sells")
                    + " offer " + vtext.contains("Make an offer") + " owner tools " + vtext.contains("Deputy in charge") + " | folk firm face " + ftext.contains(founder.displayNameCap())
                    + " | owner page " + opage + " till " + otext.contains("in the till") + " tour " + otext.contains("Your Business Core") + " | " + hoursSaid + " " + hours + " notify " + notify
                    + " | commands " + all.toString().contains("/village"));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(isCore && folkCore != null, "a Core set at each business's premises, the player's and the folk's");
                helper.assertTrue(vpage.equals("core") && vtext.contains("What it sells") && vtext.contains("Make an offer") && !vtext.contains("Deputy in charge"), "a visitor sees the public face, read-only");
                helper.assertTrue(ftext.contains(founder.displayNameCap()), "a folk firm's face too");
                helper.assertTrue(opage.equals("firm") && otext.contains("in the till") && otext.contains("Your Business Core"), "the owner gets the full hub, with its tour");
                helper.assertTrue(hours && hoursSaid.contains("08:00 to 18:00, Mon to Sat") && notify, "decisions through it take");
                helper.assertTrue(!all.toString().contains("/village"), "no commands");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bz02_core_protected")
    public static void bz02_core_protected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8880000, 3);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            CoreSites.resetForTests();
            try {
                ServerPlayer owner = player(helper, t, t.heart(), 0);
                ServerPlayer stranger = player(helper, t, t.heart().east(2), 20);
                Business b = firm(level, t, "baker", owner.getUUID(), owner.getGameProfile().getName());
                b.at = premises(level, t, 0);
                owner.teleportTo(b.at.getX() + 0.5, b.at.getY(), b.at.getZ() + 2.5);
                BlockPos core = CoreSites.ensureForTests(level, owner, t.id(), "firm:" + b.id);
                net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, core, level.getBlockState(core), stranger);
                CoreSites.onBreak(e);
                boolean gone = !level.getBlockState(core).is(CoreItems.CORE.get());
                boolean noted = b.notes.stream().anyMatch(n -> n.contains("Business Core broken"));
                BlockPos again = CoreSites.ensureForTests(level, owner, t.id(), "firm:" + b.id);
                boolean notToday = !level.getBlockState(core).is(CoreItems.CORE.get());
                // set again; a second beside it is refused; set elsewhere at the premises, it moves
                level.setBlock(core, CoreItems.CORE.get().defaultBlockState(), 3);
                CoreSites.placedForTests(level, owner, core);
                BlockPos second = core.east();
                level.setBlock(second, CoreItems.CORE.get().defaultBlockState(), 3);
                CoreSites.placedForTests(level, owner, second);
                boolean refused = !level.getBlockState(second).is(CoreItems.CORE.get()) && level.getBlockState(core).is(CoreItems.CORE.get());
                BlockPos moved = core.south(4);
                level.setBlock(moved, CoreItems.CORE.get().defaultBlockState(), 3);
                CoreSites.placedForTests(level, owner, moved);
                boolean moves = level.getBlockState(moved).is(CoreItems.CORE.get()) && !level.getBlockState(core).is(CoreItems.CORE.get());
                net.neoforged.neoforge.event.level.BlockEvent.BreakEvent own = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, moved, level.getBlockState(moved), owner);
                CoreSites.onBreak(own);
                Kit.log("[VT] bz02 stranger: cancelled " + e.isCanceled() + " gone " + gone + " noted " + noted + " not set again today " + notToday + " | second refused " + refused + " | moved " + moves
                    + " | owner's take-up cancelled " + own.isCanceled());
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(e.isCanceled() && gone && noted, "a stranger breaking it: no block for them, and the business notes it");
                helper.assertTrue(notToday, "set again the next morning, not at once");
                helper.assertTrue(refused, "one Core to a building");
                helper.assertTrue(moves, "set elsewhere at its premises, it moves");
                helper.assertTrue(!own.isCanceled(), "its owner may take it up");
            } finally {
                Enterprise.autoForTests(true);
            }
            helper.succeed();
        });
    }
}
