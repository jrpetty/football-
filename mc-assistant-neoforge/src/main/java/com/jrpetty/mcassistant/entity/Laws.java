package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A village's laws, and what happens to a player who breaks them.
 * <ul>
 * <li><b>Theft.</b> Taking from the village's stores, unless you are one of its citizens.</li>
 * <li><b>Damage.</b> Breaking what the village built: its buildings, its wall and gates,
 *     its stores.</li>
 * </ul>
 * Only what somebody sees: a village folk near enough to see it done. Then:
 * <ol>
 * <li>the first time, <b>a fine</b>: twice the worth of what was taken (five coins for
 *     damage), out of your purse there and then, or owed;</li>
 * <li>the second time, <b>a trial</b>: the council hears it, and unless you are well liked
 *     finds you guilty and fines you three times over;</li>
 * <li>the third time, <b>banishment</b>: seven days an outcast, the watch turning you out of
 *     the village on sight.</li>
 * </ol>
 * Every offence costs a player the village's good opinion, and the witness's most of all.
 * Ask any folk to let you pay what you owe.
 */
public final class Laws {

    private Laws() {}

    public static final int DAMAGE_FINE = 5;
    public static final int BANISHED_DAYS = 7;

    /** What a player had in a store chest when it opened it: village, where, and how many of each. */
    private record Look(UUID village, List<BlockPos> chests, Map<Item, Integer> had, Map<Item, Integer> carried) {}

    private static final Map<UUID, Look> LOOKING = new ConcurrentHashMap<>();

    public static void resetForTests() { LOOKING.clear(); }

    /** Coin the player owes the village. */
    public static int owes(UUID village, UUID player) {
        return Ledger.record(village, player)[1];
    }

    public static int offences(UUID village, UUID player) {
        return Ledger.record(village, player)[0];
    }

    /** Is the player banished from the village today? */
    public static boolean banished(@Nullable UUID village, UUID player, long day) {
        return village != null && Ledger.record(village, player)[2] > day;
    }

    // ------------------------------------------------------------------ seeing it done

    /** A village folk near enough to see the player, and awake. */
    @Nullable
    static VillageFolkEntity witness(ServerLevel level, UUID village, Player p) {
        VillageFolkEntity best = null;
        double bd = 16.0 * 16.0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isSleeping() || f.isBaby() || f.isShowcase()) continue;
            double d = f.distanceToSqr(p);
            if (d < bd && f.hasLineOfSight(p)) { bd = d; best = f; }
        }
        return best;
    }

    static boolean exempt(Player p) {
        return p.isCreative() || p.isSpectator();
    }

    // ------------------------------------------------------------------ theft

    @SubscribeEvent
    public static void onOpen(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || exempt(e.getEntity())) return;
        BlockPos pos = e.getPos();
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof ChestBlock) && !s.is(net.minecraft.world.level.block.Blocks.BARREL)) return;
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        if (v == null || Citizens.is(v.id(), e.getEntity().getUUID())) return;
        List<BlockPos> stores = Villages.storeChests(level, v.id());
        if (!stores.contains(pos)) return;
        List<BlockPos> chests = new ArrayList<>();
        chests.add(pos.immutable());
        // A double chest: the other half too.
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            if (stores.contains(n) && level.getBlockState(n).getBlock() instanceof ChestBlock) chests.add(n.immutable());
        }
        LOOKING.put(e.getEntity().getUUID(), new Look(v.id(), chests, count(level, chests), carried(e.getEntity())));
    }

    @SubscribeEvent
    public static void onClose(PlayerContainerEvent.Close e) {
        Player p = e.getEntity();
        Look look = LOOKING.remove(p.getUUID());
        if (look == null || !(p.level() instanceof ServerLevel level)) return;
        Map<Item, Integer> now = count(level, look.chests());
        Map<Item, Integer> pack = carried(p);
        int taken = 0;
        double worth = 0;
        String what = null;
        for (Map.Entry<Item, Integer> had : look.had().entrySet()) {
            // What left the chest AND turned up in the player's pack: a courier emptying the chest
            // at the same moment is not the player's doing.
            int gained = pack.getOrDefault(had.getKey(), 0) - look.carried().getOrDefault(had.getKey(), 0);
            int gone = Math.min(gained, had.getValue() - now.getOrDefault(had.getKey(), 0));
            if (gone <= 0) continue;
            taken += gone;
            ItemStack one = new ItemStack(had.getKey());
            Market.Good g = Market.goodFor(one);
            worth += gone * (g == null ? 0.2 : g.value());
            if (what == null) what = one.getHoverName().getString().toLowerCase();
        }
        if (taken == 0) return;
        Villages.Village v = Villages.get(look.village());
        if (v == null) return;
        offence(level, v, p, "taking " + taken + " " + what + (taken > 1 && !what.endsWith("s") ? "s" : "") + " from the stores",
            Math.max(2, (int) Math.round(worth * 2)));
    }

    /** What the player carries (and holds on the cursor), by item. */
    private static Map<Item, Integer> carried(Player p) {
        Map<Item, Integer> out = new HashMap<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack held = p.containerMenu == null ? ItemStack.EMPTY : p.containerMenu.getCarried();
        if (!held.isEmpty()) out.merge(held.getItem(), held.getCount(), Integer::sum);
        return out;
    }

    private static Map<Item, Integer> count(ServerLevel level, List<BlockPos> chests) {
        Map<Item, Integer> out = new HashMap<>();
        for (BlockPos p : chests) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ damage

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || exempt(e.getPlayer())) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getState();
        if (natural(s)) return;
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        if (v == null || Citizens.is(v.id(), e.getPlayer().getUUID())) return;
        String what = builtBy(level, v, pos);
        if (what == null) return;
        offence(level, v, e.getPlayer(), "damaging " + what, DAMAGE_FINE);
    }

    /** Ground, plants and trees: anybody's to dig, cut and pick. */
    public static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.SAND)
            || s.is(BlockTags.CROPS) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.BASE_STONE_OVERWORLD)
            || s.is(BlockTags.SNOW) || s.canBeReplaced() || s.is(net.minecraft.world.level.block.Blocks.GRAVEL)
            || s.is(net.minecraft.world.level.block.Blocks.FARMLAND) || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
    }

    /** What of the village's this spot is part of ("the storehouse", "the wall"), or null. */
    @Nullable
    static String builtBy(ServerLevel level, Villages.Village v, BlockPos pos) {
        if (Villages.storeChests(level, v.id()).contains(pos)) return "the village's stores";
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (b.structure().equals("guesthouse")) continue;           // a guest's house is the guest's own
            int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(b.structure());
            int r = b.structure().equals("fortify") ? Watch.R : Math.max(half[0], half[1]);
            int dx = Math.abs(pos.getX() - b.anchor().getX()), dz = Math.abs(pos.getZ() - b.anchor().getZ());
            int dy = pos.getY() - b.anchor().getY();
            if (dy < -2 || dy > 16) continue;
            boolean in = b.structure().equals("fortify") ? Math.max(dx, dz) >= Watch.R - 1 && Math.max(dx, dz) <= Watch.R + 1
                : dx <= r && dz <= r;
            if (in) return b.structure().equals("fortify") ? "the wall" : TownLife.title(b.structure()).replaceFirst("^The ", "the ");
        }
        return null;
    }

    // ------------------------------------------------------------------ what follows

    /** A player was seen breaking the village's law: the fine, the trial or banishment. */
    public static void offence(ServerLevel level, Villages.Village v, Player p, String what, int fine) {
        VillageFolkEntity seen = witness(level, v.id(), p);
        if (seen == null) return;                                    // nobody saw
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        int[] r = Ledger.record(id, p.getUUID());
        r[0]++;
        String verdict;
        int sting;
        if (r[0] == 1) {
            verdict = fine(p, id, r, fine) + " in " + Villages.name(id) + " for " + what + ".";
            sting = 6;
            FolkTalk.speak(seen, FolkTalk.pick(seen.getRandom(), "Oi! That's the village's!", "Thief! Stop!", "I saw that!"));
            Villages.tell(id, day, name + " was fined for " + what);
        } else if (r[0] == 2 || r[2] > day) {
            boolean guilty = Council.tries(level, id, p);
            if (guilty) {
                verdict = "The council tried you for " + what + " and found you guilty. " + fine(p, id, r, fine * 3) + ".";
                sting = 10;
                Villages.tell(id, day, "the council tried " + name + " for " + what + " and found them guilty");
            } else {
                verdict = "The council tried you for " + what + ", and let you off with a warning. Don't let it happen again.";
                sting = 3;
                Villages.tell(id, day, "the council tried " + name + " for " + what + " and let them off with a warning");
            }
            FolkTalk.speak(seen, FolkTalk.pick(seen.getRandom(), "Again?! The council will hear of this.", "That's twice now."));
        } else {
            r[2] = (int) day + BANISHED_DAYS;
            verdict = "You are banished from " + Villages.name(id) + " for " + BANISHED_DAYS + " days, for " + what + ". The watch will turn you out.";
            sting = 40;
            FolkTalk.speak(seen, FolkTalk.pick(seen.getRandom(), "That's it — out! And don't come back!", "Guards! Guards!"));
            Villages.tell(id, day, name + " was banished from the village for " + what);
            Ledger.removeCitizen(id, p.getUUID());
        }
        Ledger.record(id, p.getUUID(), r);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            f.persona().feelFor(p.getUUID(), name, -(sting + (f == seen ? 6 : 0)));
        }
        seen.persona().remember(day, "I caught " + name + " " + what, 6);
        Standing.stir(id, p.getUUID());
        p.sendSystemMessage(Component.literal(verdict).withStyle(ChatFormatting.RED));
    }

    /** Take a fine from the player's purse, or put it down as owed. Returns the words for it. */
    private static String fine(Player p, UUID village, int[] r, int fine) {
        int coins = Market.coinsHeld(p);
        int paid = Math.min(coins, fine);
        if (paid > 0) {
            Market.payOut(p, paid);
            Ledger.addCoins(village, paid);
        }
        r[1] += fine - paid;
        return paid == fine ? "You've been fined " + fine + " coins" : "You've been fined " + fine + " coins (" + (fine - paid) + " still owed)";
    }

    /** "I'd like to pay what I owe." Returns the folk's answer. */
    public static String pay(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "Pay what? To whom?";
        int[] r = Ledger.record(village, p.getUUID());
        if (r[1] <= 0) return r[0] > 0 ? "You owe us nothing now. Mind how you go." : "You don't owe the village a thing.";
        int coins = Market.coinsHeld(p);
        if (coins <= 0) return "You owe the village " + r[1] + " coins, and you've none on you.";
        int paid = Math.min(coins, r[1]);
        Market.payOut(p, paid);
        Ledger.addCoins(village, paid);
        r[1] -= paid;
        Ledger.record(village, p.getUUID(), r);
        if (r[1] == 0) {
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a instanceof VillageFolkEntity o) o.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
            }
            Standing.stir(village, p.getUUID());
            return "That's your debt paid, every coin. We'll say no more about it.";
        }
        return "That's " + paid + " coins paid. You still owe " + r[1] + ".";
    }

    /** The watch turns out a banished player found inside the village. */
    public static boolean outlaw(@Nullable UUID village, net.minecraft.world.entity.LivingEntity e) {
        return e instanceof Player p && !exempt(p) && banished(village, p.getUUID(), p.level().getDayTime() / 24000L);
    }
}
