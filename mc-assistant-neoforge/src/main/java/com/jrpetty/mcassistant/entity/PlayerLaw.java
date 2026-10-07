package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import com.jrpetty.mcassistant.item.PoliceItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [police] Players and the town's law, the watch's way: fair, and the player's to switch off (the config's
 * villageWatchPolicesPlayers; off, the council's old ladder in Laws stands).
 *
 * <p><b>What it is.</b> Taking from the town's stores, breaking what the town built, killing the town's beasts (only
 * its citizens have hunting rights in it), or striking one of its folk; and only what a folk or a guard sees (Laws).
 * Seen, it is reported, and the nearest guard comes to the player at a run with the town's answer:
 * <ol>
 * <li><b>a warning</b> the first time: "Put that back, stranger." Thirty seconds to put it back in the stores, or the
 *     guard takes it back out of the player's pack;</li>
 * <li><b>a fine</b> the second, out of the player's purse (what it cannot pay owed), and what it took taken back;</li>
 * <li><b>barred</b> the third: the shops will not serve the player, the gates are shut in its face, its name is on the
 *     board as wanted, till it pays its fine (what it owes and ten coins) at the watch house (or to a guard at the gate);
 *     and, only if the config allows it (villagePlayerJail, off by default), a short spell in the cells.</li>
 * </ol>
 * Striking a folk is real harm: a light blow, the first time, is a sharp warning; anything more and the watch draws its
 * weapons and drives the player out of the town, shoving, and is barred at once. Only a player who strikes back at the
 * watch is fought. The town remembers: every offence costs the player its standing.
 *
 * <p><b>The other side.</b> A player can report a crime at the watch house or to any guard ("I want to report a crime").
 * A citizen, or a friend of the town, with a clean record, can be sworn in as a <b>special constable</b>: a Constable's
 * Badge of iron and gold, made by the smith out of the stores (or the player's own). With it the player can arrest a
 * culprit it catches (a folk running from the watch, a wanted folk, one accused and loose, or one it saw do the deed:
 * right-click it with the badge, and it follows to the nearest guard), walk the beat with the watch (right-click the air
 * with the badge: its presence puts off a thief as the watch's does), and is paid three coins out of the treasury for
 * every case it helped close. <b>Bounties</b> on the wanted who fled are posted on the board and the quest board.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerLaw {

    private PlayerLaw() {}

    /** What a player's barred name costs to clear, on top of what it owes. */
    static final int CLEAR_FEE = 10;
    /** A special constable's wage for a case closed. */
    static final int WAGE = 3;
    /** How long a player has to put back what it took after a warning (ticks); how long the watch drives it out. */
    static final int PUT_BACK = 600, DRIVE = 2400;

    enum Offence { THEFT, DAMAGE, POACHING, HARM, OTHER }

    /** A warning to put back what was taken: the town, the things, the stores' count of each after, and until when. */
    private record PutBack(UUID village, Map<Item, Integer> taken, Map<Item, Integer> stockAfter, long until, @Nullable UUID guard) {}

    private static final Map<UUID, PutBack> PUT_BACKS = new ConcurrentHashMap<>();
    /** What a player was last seen taking from the stores (Laws.onClose, just before the offence). */
    private static final Map<UUID, Map<Item, Integer>> TAKEN = new ConcurrentHashMap<>();
    /** The town's beasts a player was seen to kill: their drops go back to the stores. */
    private static final Set<UUID> POACHED = ConcurrentHashMap.newKeySet();
    /** When each player last struck a folk (a flurry of blows is one offence). */
    private static final Map<UUID, Long> STRUCK = new ConcurrentHashMap<>();
    /** When a barred player was last told so at a gate or on the beat. */
    private static final Map<UUID, Long> TOLD = new ConcurrentHashMap<>();

    static void resetForTests() {
        PUT_BACKS.clear();
        TAKEN.clear();
        POACHED.clear();
        STRUCK.clear();
        TOLD.clear();
    }

    // ------------------------------------------------------------------ the records

    static CompoundTag record(UUID village, UUID player) {
        CompoundTag t = Police.town(village);
        CompoundTag all = t.getCompound("players");
        String key = player.toString();
        if (!all.contains(key)) all.put(key, new CompoundTag());
        t.put("players", all);
        return all.getCompound(key);
    }

    static boolean known(UUID village, UUID player) {
        return Police.town(village).getCompound("players").contains(player.toString());
    }

    /** Barred from the town by the watch (Laws.banished: the shops, the inn, the gates). */
    public static boolean barred(@Nullable UUID village, UUID player) {
        return village != null && known(village, player) && record(village, player).getBoolean("barred");
    }

    /** Struck back at the watch while it drove the player out: the watch fights it (Laws.outlaw). */
    public static boolean resisting(@Nullable UUID village, UUID player) {
        return village != null && known(village, player) && record(village, player).getBoolean("resisting");
    }

    static boolean enabled() {
        return Police.active() && AssistantConfig.villageWatchPolicesPlayers();
    }

    // ------------------------------------------------------------------ seeing it

    /** What a player took from the stores (Laws.onClose), for the watch to have back. */
    public static void taken(Player p, Map<Item, Integer> what) {
        TAKEN.put(p.getUUID(), new HashMap<>(what));
    }

    /**
     * A player seen breaking the town's law (Laws.offence): the watch's answer, when the town has a watch and the config
     * lets it. True if the watch had it (Laws' own ladder is not run then).
     */
    public static boolean offence(ServerLevel level, Villages.Village v, Player p, String what, int fine) {
        if (!enabled() || Patrols.watch(v.id()).isEmpty() || Laws.exempt(p)) return false;
        VillageFolkEntity seen = Laws.witness(level, v.id(), p);
        if (seen == null) return true;
        Offence kind = what.startsWith("taking") ? Offence.THEFT : what.startsWith("damaging") ? Offence.DAMAGE
            : what.startsWith("killing") ? Offence.POACHING : Offence.OTHER;
        handle(level, v, p, seen, kind, what, fine, 0.0F);
        return true;
    }

    /** The town's answer: a warning, a fine, the bar; for harm, the drive out. */
    static void handle(ServerLevel level, Villages.Village v, Player p, VillageFolkEntity seen, Offence kind, String what, int fine, float blow) {
        UUID id = v.id();
        long now = level.getDayTime(), day = now / 24000L, gt = level.getGameTime();
        String name = p.getName().getString();
        CompoundTag r = record(id, p.getUUID());
        r.putString("name", name);
        r.putLong("lastDay", day);
        boolean citizen = Citizens.is(id, p.getUUID());
        String you = citizen ? name : "stranger";
        String town = Villages.name(id);
        Map<Item, Integer> took = kind == Offence.THEFT ? TAKEN.remove(p.getUUID()) : null;
        String words, chat;
        int sting;
        ChatFormatting colour = ChatFormatting.YELLOW;
        boolean drive = false;
        if (kind == Offence.HARM) {
            int harm = r.getInt("harm");
            r.putInt("harm", harm + 1);
            if (harm == 0 && blow < 3.0F && !r.getBoolean("barred")) {
                words = "Lay a hand on one of ours again, " + you + ", and the watch will run you out of " + town + "!";
                chat = "A warning from the watch of " + town + ": you struck " + what.replace("striking ", "") + ". Do it again and you'll be driven out.";
                sting = 8;
                r.putInt("warnings", r.getInt("warnings") + 1);
            } else {
                int fee = 15;
                int paid = take(p, id, fee);
                bar(id, r, "harming one of the town's folk", fee - paid);
                r.putLong("outUntil", gt + DRIVE);
                drive = true;
                words = "Out! Out of " + town + ", now!";
                chat = "The watch of " + town + " is driving you out for " + what + ". You are barred from the town till you pay "
                    + (r.getInt("fee")) + " coins at the watch house." + (paid > 0 ? " (" + paid + " taken from your purse.)" : "");
                colour = ChatFormatting.RED;
                sting = 20;
                Villages.tell(id, day, "the watch drove " + name + " out of the town for " + what);
            }
        } else {
            int n = r.getInt("offences") + 1;
            r.putInt("offences", n);
            if (n == 1) {
                r.putInt("warnings", r.getInt("warnings") + 1);
                sting = 3;
                if (kind == Offence.THEFT && took != null && !took.isEmpty()) {
                    Map<Item, Integer> stock = new HashMap<>();
                    for (Item it : took.keySet()) stock.put(it, Market.stock(level, id, s -> s.is(it)));
                    PUT_BACKS.put(p.getUUID(), new PutBack(id, took, stock, gt + PUT_BACK, null));
                    words = FolkTalk.pick(seen.getRandom(), "Put that back, " + you + ".", "Oi! That's the town's. Put it back, " + you + ".");
                    chat = "A warning from the watch of " + town + ": put back what you took from the stores within thirty seconds, or it will be taken back.";
                } else {
                    words = kind == Offence.DAMAGE ? "Leave that be, " + you + " — that's the town's." : kind == Offence.POACHING
                        ? "That beast was the town's, " + you + ". Only citizens hunt here." : "That's against the town's law, " + you + ". Don't do it again.";
                    chat = "A warning from the watch of " + town + " for " + what + ". Next time it's a fine.";
                }
            } else if (n == 2) {
                int paid = take(p, id, fine);
                if (fine - paid > 0) owe(id, p.getUUID(), fine - paid);
                r.putInt("fines", r.getInt("fines") + 1);
                r.putInt("finedCoins", r.getInt("finedCoins") + paid);
                String back = takeBack(level, v, p, took);
                words = "That's twice, " + you + ". A fine of " + fine + (back.isEmpty() ? "." : ", and I'll have those back.");
                chat = "The watch of " + town + " fined you " + fine + " coins for " + what + (paid < fine ? " (" + (fine - paid) + " owed)" : "")
                    + (back.isEmpty() ? "." : "; " + back + " taken back.");
                sting = 6;
                Villages.tell(id, day, "the watch fined " + name + " for " + what);
                Police.log(id, now, "fine", "the watch fined " + name + " (a player) " + fine + " for " + what, null, paid);
            } else {
                int paid = take(p, id, fine);
                String back = takeBack(level, v, p, took);
                bar(id, r, what, fine - paid);
                words = "That's it, " + you + ". You're barred from " + town + " till you've paid at the watch house.";
                chat = "You are barred from " + town + " for " + what + ": its shops won't serve you and its gates are shut to you, till you pay "
                    + r.getInt("fee") + " coins at the watch house." + (back.isEmpty() ? "" : " " + Police.capital(back) + " taken back.");
                colour = ChatFormatting.RED;
                sting = 12;
                Villages.tell(id, day, name + " was barred from the town by the watch for " + what);
                if (AssistantConfig.villagePlayerJail() && p instanceof ServerPlayer sp) jail(level, v, sp);
            }
        }
        // The town remembers: every folk thinks the less of the player, the one who saw it most.
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f) f.persona().feelFor(p.getUUID(), name, -(sting + (f == seen ? 6 : 0)));
        }
        seen.persona().remember(day, "I saw " + name + " " + what, 5);
        Standing.stir(id, p.getUUID());
        p.sendSystemMessage(Component.literal(chat).withStyle(colour));
        Police.log(id, now, "player", name + " (a player) was seen " + what + (r.getBoolean("barred") ? "; barred" : ""), null, 0);
        // The watch comes to the player at a run with the town's answer; with no guard to hand, the witness says it.
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, p.blockPosition(), 64.0, null);
        if (g == null) {
            FolkTalk.speak(seen, words);
        } else {
            FolkTalk.speak(seen, FolkTalk.pick(seen.getRandom(), "Watch! Over here!", "Guard! Did you see that?"));
            Incidents.Task t = Incidents.newTask(drive ? Incidents.Kind.DRIVE : Incidents.Kind.PLAYER, id, gt);
            t.player = p.getUUID();
            t.words = words;
            Incidents.putTask(g, t);
            if (drive) g.equipBestWeapon();
        }
        if (drive) {
            for (VillageFolkEntity o : Patrols.watch(id)) {
                if (o == g || !WatchHouse.freeFor(o) || o.distanceTo(p) > 64) continue;
                Incidents.Task t = Incidents.newTask(Incidents.Kind.DRIVE, id, gt);
                t.player = p.getUUID();
                t.words = "Keep moving!";
                Incidents.putTask(o, t);
                o.equipBestWeapon();
                break;
            }
        }
        Police.changed();
    }

    /** Barred: the shops, the gates, the board, till the fee is paid at the watch house. */
    private static void bar(UUID village, CompoundTag r, String why, int owed) {
        r.putBoolean("barred", true);
        r.putBoolean("wanted", true);
        r.putString("why", why);
        r.putInt("fee", r.getInt("fee") + CLEAR_FEE + Math.max(0, owed));
        Police.changed();
    }

    /** Coins out of the player's purse into the treasury: as many as it has, up to so many. */
    static int take(Player p, UUID village, int coins) {
        int paid = Math.min(Market.coinsHeld(p), coins);
        if (paid > 0) {
            Market.payOut(p, paid);
            Ledger.addCoins(village, paid);
        }
        return paid;
    }

    /** Owed to the town (Laws' book: paid with "Pay a fine"). */
    private static void owe(UUID village, UUID player, int coins) {
        int[] r = Ledger.record(village, player);
        r[1] += coins;
        Ledger.record(village, player, r);
    }

    /** What it took out of its pack and back into the stores; in words, or "" if nothing. */
    static String takeBack(ServerLevel level, Villages.Village v, Player p, @Nullable Map<Item, Integer> took) {
        if (took == null || took.isEmpty()) return "";
        int n = 0;
        String what = null;
        for (Map.Entry<Item, Integer> e : took.entrySet()) {
            int want = e.getValue();
            for (int i = 0; i < p.getInventory().getContainerSize() && want > 0; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (!s.is(e.getKey())) continue;
                int k = Math.min(want, s.getCount());
                ItemStack back = s.split(k);
                Crafts.store(level, v, back);
                want -= k;
                n += k;
                if (what == null) what = e.getKey().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
            }
        }
        p.getInventory().setChanged();
        return n == 0 ? "" : n + " " + what + (n > 1 && !what.endsWith("s") ? "s" : "");
    }

    // ------------------------------------------------------------------ harm and the town's beasts

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || Laws.exempt(p) || !enabled()) return;
        com.jrpetty.mcassistant.Guard.run("the watch and a blow struck", () -> struck(level, p, f, e.getAmount()));
    }

    static void struck(ServerLevel level, ServerPlayer p, VillageFolkEntity f, float amount) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || f.isHired() || f.isShowcase() || Patrols.watch(village).isEmpty()) return;
        if (f.getTarget() == p) return;                                          // it went for the player: the player's to answer
        if (known(village, p.getUUID()) && record(village, p.getUUID()).getLong("outUntil") > level.getGameTime()
                && f.stationTask() == AssistantEntity.StationTask.GUARD) {
            // Struck back at the watch driving it out: now the watch fights.
            record(village, p.getUUID()).putBoolean("resisting", true);
            for (VillageFolkEntity g : Patrols.watch(village)) if (g.distanceTo(p) < 32) g.setTarget(p);
            Police.changed();
            return;
        }
        long gt = level.getGameTime();
        Long last = STRUCK.get(p.getUUID());
        if (last != null && gt - last < 100 && gt >= last) return;
        STRUCK.put(p.getUUID(), gt);
        VillageFolkEntity seen = f.isAlive() ? f : Laws.witness(level, village, p);
        if (seen == null) return;
        handle(level, v, p, seen, Offence.HARM, "striking " + f.displayNameCap(), 0, amount);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof Animal a) || !(a.level() instanceof ServerLevel level)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || Laws.exempt(p) || !enabled()) return;
        com.jrpetty.mcassistant.Guard.run("the town's beasts", () -> {
            Villages.Village v = Villages.nearest(level, a.blockPosition(), Villages.VILLAGE_RANGE);
            if (v == null || Citizens.is(v.id(), p.getUUID()) || a.hasCustomName()) return;
            if (!(a instanceof net.minecraft.world.entity.animal.Cow || a instanceof net.minecraft.world.entity.animal.Sheep
                || a instanceof net.minecraft.world.entity.animal.Pig || a instanceof net.minecraft.world.entity.animal.Chicken
                || a instanceof net.minecraft.world.entity.animal.Rabbit || a instanceof net.minecraft.world.entity.animal.goat.Goat)) return;
            int reach = Villages.townReach(v.id());
            if (Math.max(Math.abs(a.getX() - v.centre().getX()), Math.abs(a.getZ() - v.centre().getZ())) > reach) return;
            if (Laws.witness(level, v.id(), p) == null) return;
            POACHED.add(a.getUUID());
            String what = "killing the town's " + a.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
            Laws.offence(level, v, p, what, 4);
        });
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent e) {
        if (!POACHED.remove(e.getEntity().getUUID()) || !(e.getEntity().level() instanceof ServerLevel level)) return;
        Villages.Village v = Villages.nearest(level, e.getEntity().blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) return;
        // The carcass is the town's: into its stores, not the poacher's pack.
        for (ItemEntity drop : e.getDrops()) Crafts.store(level, v, drop.getItem().copy());
        e.setCanceled(true);
    }

    // ------------------------------------------------------------------ the watch at the player

    /** A guard's part (Incidents.hold): to the player at a run with the town's words; or, driving it out, at it with its blade. */
    static String guardStep(ServerLevel level, Villages.Village v, VillageFolkEntity g, Incidents.Task t, long gt) {
        ServerPlayer p = Incidents.serverPlayer(level, t.player);
        if (p == null || !p.isAlive() || p.level() != level) {
            Incidents.endTask(g);
            return null;
        }
        if (t.kind == Incidents.Kind.PLAYER) {
            if (gt - t.since > 600) {
                Incidents.endTask(g);
                return null;
            }
            if (g.distanceTo(p) > 3.0) {
                g.setSprinting(true);
                if (gt % 10 < 4 || g.getNavigation().isDone()) g.getNavigation().moveTo(p, 1.25D);
                return "going to have a word with " + p.getName().getString();
            }
            g.getNavigation().stop();
            g.setSprinting(false);
            g.getLookControl().setLookAt(p, 30.0F, 30.0F);
            FolkTalk.speak(g, t.words);
            Incidents.endTask(g);
            return null;
        }
        // Driving it out: weapons drawn, at its back, a shove now and then, till it is out past the town's edge.
        CompoundTag r = record(v.id(), p.getUUID());
        int reach = Villages.townReach(v.id());
        double out = Math.max(Math.abs(p.getX() - v.centre().getX()), Math.abs(p.getZ() - v.centre().getZ()));
        if (out > reach + 8 || r.getLong("outUntil") <= gt) {
            if (out > reach + 8) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "And stay out!", "Don't come back till you've paid your fine!"));
            Incidents.endTask(g);
            return null;
        }
        if (g.getMainHandItem().isEmpty()) g.equipBestWeapon();
        double d = g.distanceTo(p);
        if (d > 2.4) {
            if (gt % 10 < 4 || g.getNavigation().isDone()) g.getNavigation().moveTo(p, 1.15D);
        } else {
            g.getNavigation().stop();
            if ((gt - t.since) % 40 < 4) {
                double ax = p.getX() - v.centre().getX(), az = p.getZ() - v.centre().getZ();
                p.knockback(0.6, -ax, -az);
                p.hurtMarked = true;
                g.swing(net.minecraft.world.InteractionHand.OFF_HAND);
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), t.words, "Keep moving!", "Out. Go on."));
            }
        }
        g.getLookControl().setLookAt(p, 30.0F, 30.0F);
        return "driving " + p.getName().getString() + " out of the town";
    }

    // ------------------------------------------------------------------ the round

    /** Every second for a town (Police.tick): put-backs watched, the driven-out followed. */
    static void tick(ServerLevel level, Villages.Village v) {
        long gt = level.getGameTime();
        for (Map.Entry<UUID, PutBack> e : PUT_BACKS.entrySet()) {
            PutBack pb = e.getValue();
            if (!pb.village().equals(v.id())) continue;
            Player p = level.getPlayerByUUID(e.getKey());
            boolean back = true;
            for (Map.Entry<Item, Integer> t : pb.taken().entrySet()) {
                Item it = t.getKey();
                if (Market.stock(level, v.id(), s -> s.is(it)) < pb.stockAfter().getOrDefault(it, 0) + t.getValue()) back = false;
            }
            if (back) {
                PUT_BACKS.remove(e.getKey());
                if (p != null) {
                    p.sendSystemMessage(Component.literal("The watch saw you put it back. Nothing more will be said.").withStyle(ChatFormatting.GREEN));
                    VillageFolkEntity g = WatchHouse.nearestGuard(level, v, p.blockPosition(), 24.0, null);
                    if (g != null) FolkTalk.speak(g, "Good. We'll say no more about it.");
                }
                continue;
            }
            if (gt < pb.until()) continue;
            PUT_BACKS.remove(e.getKey());
            if (p == null) continue;
            String took = takeBack(level, v, p, pb.taken());
            if (took.isEmpty()) continue;
            p.sendSystemMessage(Component.literal("The watch took back " + took + " for the stores.").withStyle(ChatFormatting.YELLOW));
            VillageFolkEntity g = WatchHouse.nearestGuard(level, v, p.blockPosition(), 32.0, null);
            if (g != null) FolkTalk.speak(g, "Then I'll have it back, thank you.");
            Police.log(v.id(), level.getDayTime(), "player", "the watch took back " + took + " from " + p.getName().getString() + " (a player)", g, 0);
        }
        // A wanted folk loaded and not lying low (a restart, a quest let go): back out of the way.
        CompoundTag bounties = Police.town(v.id()).getCompound("bounties");
        for (String key : bounties.getAllKeys()) {
            VillageFolkEntity f = Civics.find(level, uuid(key));
            if (f == null || Incidents.task(f) != null || WatchHouse.custodyOf(f.getUUID()) != null || QuestStories.cast(f.getUUID())) continue;
            if (castInBounty(f.getUUID())) continue;
            Incidents.hideOut(level, v, f, v.centre());
        }
    }

    /** Every second for a level: the players in the cells, and those driven out. */
    static void tickPlayers(ServerLevel level) {
        long gt = level.getGameTime();
        for (ServerPlayer p : level.players()) {
            for (Villages.Village v : Villages.every()) {
                if (!v.dim().equals(level.dimension()) || !known(v.id(), p.getUUID())) continue;
                CompoundTag r = record(v.id(), p.getUUID());
                long until = r.getLong("jailUntil");
                if (until <= 0) continue;
                WatchHouse.Cell cell = cellOf(v.id(), r.getInt("jailCell"));
                if (cell == null) {
                    r.putLong("jailUntil", 0);
                    continue;
                }
                double dx = p.getX() - (cell.inside().getX() + 0.5), dz = p.getZ() - (cell.inside().getZ() + 0.5);
                if (gt >= until) {
                    r.putLong("jailUntil", 0);
                    WatchHouse.setDoor(level, cell.door(), true);
                    Ledger.Building b = WatchHouse.of(v.id());
                    if (b != null) {
                        BlockPos out = WatchHouse.at(b, WatchHouse.OUTSIDE);
                        p.teleportTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
                    }
                    p.sendSystemMessage(Component.literal("Your time in the cells is served. Your name stays on the board till you pay at the watch house.")
                        .withStyle(ChatFormatting.YELLOW));
                    Police.changed();
                } else if (dx * dx + dz * dz > 4.0 * 4.0) {
                    r.putLong("jailUntil", 0);
                    r.putInt("fee", r.getInt("fee") + CLEAR_FEE);
                    r.putString("why", "breaking out of the cells");
                    p.sendSystemMessage(Component.literal("You broke out of the cells. The watch has added " + CLEAR_FEE + " coins to your fine.").withStyle(ChatFormatting.RED));
                    Police.log(v.id(), level.getDayTime(), "break", p.getName().getString() + " (a player) broke out of the cells", null, 0);
                    Police.changed();
                }
            }
        }
    }

    @Nullable
    static WatchHouse.Cell cellOf(UUID village, int index) {
        Ledger.Building b = WatchHouse.of(village);
        return b == null ? null : WatchHouse.cell(b, index);
    }

    /** A player in a cell (villagePlayerJail): locked in for a short spell, the door shut on it. */
    static boolean jail(ServerLevel level, Villages.Village v, ServerPlayer p) {
        WatchHouse.Cell cell = WatchHouse.freeCell(level, v.id());
        if (cell == null) return false;
        CompoundTag r = record(v.id(), p.getUUID());
        r.putLong("jailUntil", level.getGameTime() + AssistantConfig.villagePlayerJailSeconds() * 20L);
        r.putInt("jailCell", cell.index());
        WatchHouse.setDoor(level, cell.door(), false);
        p.teleportTo(cell.inside().getX() + 0.5, cell.inside().getY(), cell.inside().getZ() + 0.5);
        p.sendSystemMessage(Component.literal("The watch has locked you in the cells at the watch house for " + AssistantConfig.villagePlayerJailSeconds()
            + " seconds.").withStyle(ChatFormatting.RED));
        Police.log(v.id(), level.getDayTime(), "jail", p.getName().getString() + " (a player) was locked in the cells", null, 0);
        Police.changed();
        return true;
    }

    /** Is a cell held by a player just now? */
    static boolean cellTaken(UUID village, int index) {
        CompoundTag all = Police.town(village).getCompound("players");
        for (String key : all.getAllKeys()) {
            CompoundTag r = all.getCompound(key);
            if (r.getLong("jailUntil") > 0 && r.getInt("jailCell") == index) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the bar at the gate, on the beat

    /** A barred player outside the gate: shut in its face, and told why. Opened again when it has gone. */
    static void atTheGate(ServerLevel level, Villages.Village v, VillageFolkEntity g, Watch.Gate gate) {
        Player near = null;
        for (Player p : level.players()) {
            if (!barred(v.id(), p.getUUID()) || Laws.exempt(p)) continue;
            if (p.blockPosition().distSqr(gate.inside()) > 14 * 14 || Watch.inside(v.id(), v.centre(), p.blockPosition())) continue;
            near = p;
            break;
        }
        Beats.gate(level, v, gate, near != null);
        if (near == null) return;
        long gt = level.getGameTime();
        Long told = TOLD.get(near.getUUID());
        if (told != null && gt - told < 600 && gt >= told) return;
        TOLD.put(near.getUUID(), gt);
        g.getLookControl().setLookAt(near, 30.0F, 30.0F);
        FolkTalk.speak(g, "The gate's shut to you, " + near.getName().getString() + ". You're barred till your fine's paid — at the watch house, or to me here.");
    }

    /** The beat meets a barred player (a word), or a special constable on patrol (a greeting). */
    static void onTheBeat(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        long gt = level.getGameTime();
        for (Player p : level.players()) {
            if (p.distanceTo(g) > 10 || Laws.exempt(p)) continue;
            Long told = TOLD.get(p.getUUID());
            if (told != null && gt - told < 1200 && gt >= told) continue;
            if (barred(v.id(), p.getUUID())) {
                TOLD.put(p.getUUID(), gt);
                FolkTalk.speak(g, "You're barred here, " + p.getName().getString() + ". Pay your fine at the watch house and we'll say no more.");
                return;
            }
            if (onPatrol(v.id(), p.getUUID())) {
                TOLD.put(p.getUUID(), gt);
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Evening, constable. Anything on your side of town?", "Walk with me a while, constable.",
                    "Good to have you on the beat, constable."));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ paying to clear the name

    /**
     * "Pay a fine" (Laws.pay), barred: what it owes and the fee, paid at the watch house (to a guard there or at its
     * desk), or to a guard at a gate, or anywhere to the watch in a town with no watch house. Null if the player is not
     * barred (Laws takes what it owes as before).
     */
    @Nullable
    public static String pay(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !barred(village, p.getUUID()) || !(f.level() instanceof ServerLevel level)) return null;
        if (f.stationTask() != AssistantEntity.StationTask.GUARD) return "That's for the watch, not me. Pay it at the watch house.";
        Ledger.Building house = WatchHouse.of(village);
        Roster.Duty d = Roster.dutyOf(level, f);
        boolean there = house == null || f.blockPosition().distSqr(house.anchor()) < 16 * 16 || d == Roster.Duty.WALLS || d == Roster.Duty.DESK;
        if (!there) return "Not here. Your fine's paid at the watch house — the desk will take it.";
        CompoundTag r = record(village, p.getUUID());
        int[] book = Ledger.record(village, p.getUUID());
        int fee = r.getInt("fee") + Math.max(0, book[1]);
        int have = Market.coinsHeld(p);
        if (have < fee) return "Your fine is " + fee + " coins, and you've " + have + " on you. Come back when you have it.";
        Market.payOut(p, fee);
        Ledger.addCoins(village, fee);
        book[1] = 0;
        Ledger.record(village, p.getUUID(), book);
        r.putBoolean("barred", false);
        r.putBoolean("wanted", false);
        r.putBoolean("resisting", false);
        r.putInt("fee", 0);
        r.putLong("outUntil", 0);
        r.putInt("offences", 1);                                               // the town remembers: next time, a fine
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity o) o.persona().feelFor(p.getUUID(), p.getName().getString(), 3);
        }
        Standing.stir(village, p.getUUID());
        Police.log(village, level.getDayTime(), "fine", p.getName().getString() + " (a player) paid " + fee + " at the watch house and cleared their name", f, fee);
        Villages.tell(village, level.getDayTime() / 24000L, p.getName().getString() + " paid their fine at the watch house and is welcome in the town again");
        Police.changed();
        return "That's " + fee + " coins. Your name's clear in " + Villages.name(village) + ". Mind it stays that way.";
    }

    // ------------------------------------------------------------------ reporting a crime

    /** "I want to report a crime": what the player saw, put on the books and answered. */
    static String report(VillageFolkEntity f, ServerPlayer p) {
        UUID village = f.ownerId();
        if (village == null || !(p.level() instanceof ServerLevel level)) return "Report it to the watch where it happened.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "Report it to the watch where it happened.";
        boolean watch = f.stationTask() == AssistantEntity.StationTask.GUARD;
        if (!watch) {
            VillageFolkEntity g = WatchHouse.nearestGuard(level, v, f.blockPosition(), 64.0, null);
            return "Not to me — the watch! " + (g != null ? g.displayNameCap() + " is about, or try" : "Try") + " the desk at the watch house.";
        }
        long day = level.getDayTime() / 24000L;
        for (Crime.Case c : Crime.cases(village)) {
            Crime.Witness w = c.witness(p.getUUID());
            if (w == null || !c.stage.open()) continue;
            c.helped(p);
            if (c.stage == Crime.Stage.UNNOTICED) {
                Crime.report(level, v, c, p.getName().getString());
                c.note(day, p.getName().getString() + " reported it to " + f.displayNameCap() + ".");
                Incidents.respondTo(level, v, f, c, null);
                Crime.listeners(l -> l.helped(p, village, c.id, "reported a crime to the watch"));
                Crime.changed();
                return "You saw " + w.saw + " at " + c.place + "? Right — I'm on my way. " + (w.thinks != null ? "If you know who it was, say \"I saw "
                    + w.thinksName + " do it\" and it'll go in the case." : "Anything you can tell me about who it was will help.");
            }
            return "The " + c.title().toLowerCase(java.util.Locale.ROOT) + "? It's on the books. " + (w.thinks != null ? "Tell me who — say \"I saw "
                + w.thinksName + " do it\" — and it goes in the case." : "What did you see?");
        }
        List<String> wanted = wantedNames(village);
        if (!wanted.isEmpty()) return "Nothing of yours on the books. We're after " + wanted.get(0) + " — if you see them, tell me where. "
            + "Else, tell me what you saw: \"I saw Fen take the bread\".";
        return "Nothing on the books that you saw. Tell me who and what — \"I saw Fen take the bread\" — and I'll look into it.";
    }

    // ------------------------------------------------------------------ special constables

    static CompoundTag constable(UUID village, UUID player) {
        CompoundTag t = Police.town(village);
        CompoundTag all = t.getCompound("constables");
        String key = player.toString();
        if (!all.contains(key)) all.put(key, new CompoundTag());
        t.put("constables", all);
        return all.getCompound(key);
    }

    static boolean sworn(@Nullable UUID village, UUID player) {
        return village != null && Police.town(village).getCompound("constables").contains(player.toString())
            && constable(village, player).getLong("sworn") > 0;
    }

    static boolean onPatrol(UUID village, UUID player) {
        if (!sworn(village, player)) return false;
        return constable(village, player).getBoolean("patrol");
    }

    /** The town a badge is sworn to, or null. */
    @Nullable
    public static UUID badgeTown(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return null;
        String t = d.copyTag().getString("mca_badge_town");
        return t.isEmpty() ? null : uuid(t);
    }

    /** A badge sworn to a town, in a player's name. */
    static void bind(ItemStack badge, UUID village, String player) {
        CompoundTag t = badge.has(DataComponents.CUSTOM_DATA) ? badge.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        t.putString("mca_badge_town", village.toString());
        t.putString("mca_badge_townName", Villages.name(village));
        t.putString("mca_badge_holder", player);
        badge.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
    }

    /** "Swear me in": a trusted citizen or friend of the town with a clean record, sworn as a special constable. */
    static String swear(VillageFolkEntity f, ServerPlayer p) {
        UUID village = f.ownerId();
        if (village == null || !(p.level() instanceof ServerLevel level)) return "Sworn in? Here?";
        Villages.Village v = Villages.get(village);
        if (v == null) return "Sworn in? Here?";
        if (f.stationTask() != AssistantEntity.StationTask.GUARD) return "Only the watch can swear you in. Ask a guard, or the constable at the watch house.";
        if (sworn(village, p.getUUID())) return "You're sworn already, constable. Badge on, and walk the beat with us when you will.";
        boolean citizen = Citizens.is(village, p.getUUID());
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (!citizen && !title.atLeast(Standing.Title.FRIEND)) {
            return "We swear in folk the town trusts: a citizen, or a friend of the town. You're " + title.words + " here yet.";
        }
        if (barred(village, p.getUUID()) || Laws.owes(village, p.getUUID()) > 0 || known(village, p.getUUID()) && record(village, p.getUUID()).getInt("fines") > 0) {
            return "Not with your record. Keep your nose clean a while, and settle what you owe.";
        }
        ItemStack badge = carriedBadge(p);
        boolean own = !badge.isEmpty();
        if (!own) {
            badge = Crafts.takeOne(level, v, s -> s.is(PoliceItems.CONSTABLE_BADGE.get()) && badgeTown(s) == null);
            if (badge.isEmpty()) {
                Police.town(village).putUUID("badgeFor", p.getUUID());
                Police.changed();
                return "Gladly — but you'll want a badge, and there's none in the stores. The smith will make one of an iron ingot and four gold "
                    + "nuggets from the stores; come back when it's made. Or bring me one of your own.";
            }
        }
        bind(badge, village, p.getName().getString());
        if (!own && !p.getInventory().add(badge)) p.drop(badge, false);
        Police.town(village).remove("badgeFor");
        CompoundTag c = constable(village, p.getUUID());
        c.putString("name", p.getName().getString());
        c.putLong("sworn", Math.max(1, level.getDayTime() / 24000L));
        c.putBoolean("patrol", false);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity o) o.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        }
        Standing.stir(village, p.getUUID());
        Villages.tell(village, level.getDayTime() / 24000L, p.getName().getString() + " was sworn in as a special constable of the watch");
        Police.log(village, level.getDayTime(), "constable", p.getName().getString() + " was sworn in as a special constable by " + f.displayNameCap(), f, 0);
        Police.changed();
        FolkTalk.speak(f, "Raise your hand. Do you swear to keep the peace of " + Villages.name(village) + ", without fear or favour?");
        return "Then you're sworn: a special constable of the watch of " + Villages.name(village) + ". That badge is your warrant: arrest a culprit "
            + "you catch (show it the badge) and bring them to any of us; right-click the air with it to walk the beat; three coins from the "
            + "treasury for every case you help close.";
    }

    /** The operators' swearing-in (/village police swear): a badge out of the stores, or one made for the purpose. */
    static String swearNow(ServerLevel level, Villages.Village v, ServerPlayer p) {
        ItemStack badge = carriedBadge(p);
        if (badge.isEmpty()) {
            // Out of the stores, or made now of the stores' iron and gold: never out of nothing.
            if (Market.stock(level, v.id(), s -> s.is(PoliceItems.CONSTABLE_BADGE.get()) && badgeTown(s) == null) == 0) Police.makeBadge(level, v);
            badge = Crafts.takeOne(level, v, s -> s.is(PoliceItems.CONSTABLE_BADGE.get()) && badgeTown(s) == null);
            if (badge.isEmpty()) return "No badge to be had: the stores want an iron ingot and four gold nuggets for one (or the player can bring its own).";
            if (!p.getInventory().add(badge)) p.drop(badge, false);
            badge = carriedBadge(p);
        }
        if (!badge.isEmpty()) bind(badge, v.id(), p.getName().getString());
        CompoundTag c = constable(v.id(), p.getUUID());
        c.putString("name", p.getName().getString());
        c.putLong("sworn", Math.max(1, level.getDayTime() / 24000L));
        Police.changed();
        return p.getName().getString() + " is sworn in as a special constable of " + Villages.name(v.id()) + ".";
    }

    private static ItemStack carriedBadge(Player p) {
        ItemStack held = p.getMainHandItem();
        if (held.is(PoliceItems.CONSTABLE_BADGE.get())) return held;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(PoliceItems.CONSTABLE_BADGE.get())) return s;
        }
        return ItemStack.EMPTY;
    }

    /** "Can I join your patrol?" (or the badge, used): a sworn constable walks the beat with the watch, and is felt there. */
    static String patrol(VillageFolkEntity f, ServerPlayer p) {
        UUID village = f.ownerId();
        if (village == null || !sworn(village, p.getUUID())) return "Only a sworn special constable walks the beat with the watch. Ask a guard to swear you in.";
        CompoundTag c = constable(village, p.getUUID());
        boolean on = !c.getBoolean("patrol");
        c.putBoolean("patrol", on);
        Police.changed();
        return on ? "Walk with me, then, constable. Keep your eyes open: a thief thinks twice where the watch walks."
            : "Off the beat, then. Thank you, constable.";
    }

    /** The badge used in the air: on the beat, or off it, in the town it is sworn to. */
    public static void togglePatrol(ServerPlayer p, ItemStack badge) {
        UUID village = badgeTown(badge);
        if (village == null) {
            p.displayClientMessage(Component.literal("This badge isn't sworn to any town. Ask a guard to swear you in."), true);
            return;
        }
        if (!sworn(village, p.getUUID())) {
            p.displayClientMessage(Component.literal("You're not sworn to " + Villages.name(village) + "'s watch."), true);
            return;
        }
        CompoundTag c = constable(village, p.getUUID());
        boolean on = !c.getBoolean("patrol");
        c.putBoolean("patrol", on);
        Police.changed();
        p.displayClientMessage(Component.literal(on ? "On the beat in " + Villages.name(village) + ": thieves think twice where you walk."
            : "Off the beat.").withStyle(on ? ChatFormatting.GOLD : ChatFormatting.GRAY), true);
    }

    /** The badge shown to a folk: arrested, if there is a charge to hold it on; it follows the constable to the watch. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getTarget() instanceof VillageFolkEntity f) || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!Police.active()) return;
        ItemStack held = e.getItemStack();
        if (!held.is(PoliceItems.CONSTABLE_BADGE.get())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        com.jrpetty.mcassistant.Guard.run("a constable's arrest", () -> {
            String said = badgeArrest(level, p, f, held);
            if (said != null) p.displayClientMessage(Component.literal(said), false);
        });
    }

    /** A special constable's arrest: on what charge, and if one holds, the folk follows it to the nearest guard. */
    @Nullable
    static String badgeArrest(ServerLevel level, ServerPlayer p, VillageFolkEntity f, ItemStack badge) {
        UUID town = badgeTown(badge);
        if (town == null) return "This badge isn't sworn to any town. Ask a guard to swear you in.";
        if (!town.equals(f.ownerId())) return "Your badge is " + Villages.name(town) + "'s. You've no warrant here.";
        if (!sworn(town, p.getUUID())) return "You're not sworn to " + Villages.name(town) + "'s watch.";
        Villages.Village v = Villages.get(town);
        if (v == null) return null;
        if (WatchHouse.custodyOf(f.getUUID()) != null) return f.displayNameCap() + " is in the watch's keeping already.";
        Incidents.Task t = Incidents.task(f);
        if (t != null && t.kind == Incidents.Kind.LED) return f.displayNameCap() + " is coming with you already.";
        int caseId = 0;
        String why = null;
        long day = level.getDayTime() / 24000L;
        if (t != null && t.kind == Incidents.Kind.FLEE) {
            caseId = t.caseId;
            why = "running from the watch";
            VillageFolkEntity g = t.other == null ? null : Civics.find(level, t.other);
            if (g != null) {
                Incidents.endTask(g);
                FolkTalk.speak(g, "Well held, constable!");
            }
        } else if (isWanted(town, f.getUUID())) {
            caseId = bountyCase(town, f.getUUID());
            why = "wanted by the watch";
        } else {
            for (Crime.Case c : Crime.open(town)) {
                if (c.stage == Crime.Stage.ACCUSED && f.getUUID().equals(c.accused)) {
                    caseId = c.id;
                    why = "accused of " + c.kind.word;
                    break;
                }
                Crime.Witness w = c.witness(p.getUUID());
                if (w != null && w.player && w.certainty >= 50 && f.getUUID().equals(w.thinks) && c.stage != Crime.Stage.TRIAL) {
                    caseId = c.id;
                    why = c.kind.word + " at " + c.place + ", seen by the constable";
                    if (c.stage == Crime.Stage.UNNOTICED) Crime.report(level, v, c, p.getName().getString());
                    c.clues.add(new Crime.Clue("arrest", "Arrested by " + p.getName().getString() + ", special constable, who saw it done.", 5.0, day)
                        .at(f.getUUID(), f.displayNameCap()));
                    c.accused = f.getUUID();
                    c.accusedName = f.displayNameCap();
                    c.stage = Crime.Stage.ACCUSED;
                    c.progressDay = day;
                    c.helped(p);
                    c.note(day, p.getName().getString() + ", a special constable, arrested " + f.displayNameCap() + ", having seen it done.");
                    Crime.changed();
                    break;
                }
            }
        }
        if (why == null) {
            CompoundTag c = constable(town, p.getUUID());
            c.putInt("refused", c.getInt("refused") + 1);
            Police.changed();
            f.persona().feelFor(p.getUUID(), p.getName().getString(), -2);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "What for? You've nothing on me!", "On what charge, constable?"));
            return "On what charge? The watch holds a folk who is running from it, wanted, accused, or one you saw do the deed — not on a badge alone.";
        }
        Crime.Case c = Crime.get(caseId);
        if (c != null) c.helped(p);
        Incidents.Task led = Incidents.newTask(Incidents.Kind.LED, town, level.getGameTime());
        led.player = p.getUUID();
        led.caseId = caseId;
        led.why = why;
        if (p.getInventory().countItem(Items.LEAD) > 0) {
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (!s.is(Items.LEAD)) continue;
                s.shrink(1);
                f.setLeashedTo(p, true);
                led.stage = 1;                                                // on the player's lead: given back at the hand-over
                break;
            }
        }
        Incidents.putTask(f, led);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "All right, constable. I'll come quietly.", "...Fair enough. Lead on."));
        return "You've arrested " + f.displayNameCap() + " (" + why + "). Bring them to any guard of the watch.";
    }

    /** A folk under a special constable's arrest (Incidents.hold): at its heels, till it is handed to a guard. */
    static String ledStep(ServerLevel level, Villages.Village v, VillageFolkEntity f, Incidents.Task t, long gt) {
        ServerPlayer p = Incidents.serverPlayer(level, t.player);
        if (p == null || p.level() != level || gt - t.since > 3600) {
            if (f.isLeashed()) f.dropLeash(true, t.stage == 1);
            Incidents.endTask(f);
            return null;
        }
        double d = f.distanceTo(p);
        if (d > 2.5) Incidents.walk(f, p.blockPosition(), d > 8 ? 1.1D : 0.9D);
        else f.getNavigation().stop();
        f.getLookControl().setLookAt(p, 20.0F, 20.0F);
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, f.blockPosition(), 5.0, null);
        if (g == null) return "under arrest, following " + p.getName().getString() + " to the watch";
        // Handed over: the guard takes it in, the constable has the credit.
        if (f.isLeashed()) f.dropLeash(true, false);
        if (t.stage == 1 && !p.getInventory().add(new ItemStack(Items.LEAD))) p.drop(new ItemStack(Items.LEAD), false);
        Incidents.endTask(f);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Good work, constable. I'll take it from here.", "Well done. In you come, " + f.displayNameCap() + "."));
        WatchHouse.arrest(level, v, g, f, t.caseId, t.why, -1);
        caught(v.id(), f.getUUID());
        CompoundTag c = constable(v.id(), p.getUUID());
        c.putInt("arrests", c.getInt("arrests") + 1);
        Police.log(v.id(), level.getDayTime(), "arrest", p.getName().getString() + ", special constable, brought " + f.displayNameCap() + " in to " + g.displayNameCap(), g, 0);
        p.displayClientMessage(Component.literal(g.displayNameCap() + " has taken " + f.displayNameCap() + " into the watch's keeping.").withStyle(ChatFormatting.GOLD), false);
        Police.changed();
        return null;
    }

    /** A case closed (Police's seam on Crime): the special constables who helped close it are paid their wage. */
    static void caseClosed(ServerLevel level, Villages.Village v, int caseId, List<UUID> helpers) {
        for (UUID u : helpers) {
            if (!sworn(v.id(), u)) continue;
            int paid = Ledger.takeCoins(v.id(), WAGE);
            CompoundTag c = constable(v.id(), u);
            c.putInt("cases", c.getInt("cases") + 1);
            c.putInt("paid", c.getInt("paid") + paid);
            Player p = level.getPlayerByUUID(u);
            if (p != null && paid > 0) {
                giveCoins(p, paid);
                p.sendSystemMessage(Component.literal("The watch of " + Villages.name(v.id()) + " pays you " + paid + " coins for the case closed, constable.")
                    .withStyle(ChatFormatting.GOLD));
            } else if (paid > 0) {
                c.putInt("owed", c.getInt("owed") + paid);
            }
            Police.log(v.id(), level.getDayTime(), "constable", c.getString("name") + ", special constable, was paid " + paid + " for a case closed", null, 0);
        }
        Police.changed();
    }

    /** Coins into a player's pack (or at its feet). */
    static void giveCoins(Player p, int n) {
        ItemStack coins = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), n);
        if (!p.getInventory().add(coins)) p.drop(coins, false);
    }

    // ------------------------------------------------------------------ the wanted, and bounties

    /** A folk that fled the watch, posted as wanted with a bounty on it (the board, the crier, the quest board). */
    static void postBounty(ServerLevel level, Villages.Village v, VillageFolkEntity f, int caseId, String why, String how) {
        CompoundTag t = Police.town(v.id());
        CompoundTag all = t.getCompound("bounties");
        CompoundTag b = new CompoundTag();
        b.putString("name", f.displayNameCap());
        b.putString("why", why);
        b.putString("how", how);
        b.putInt("reward", how.contains("cells") ? 8 : 5);
        b.putLong("day", level.getDayTime() / 24000L);
        b.putInt("case", caseId);
        all.put(f.getStringUUID(), b);
        t.put("bounties", all);
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " is wanted by the watch (" + how + "): a bounty of " + b.getInt("reward") + " coins");
        Police.log(v.id(), level.getDayTime(), "bounty", f.displayNameCap() + " is wanted: " + how + "; a bounty of " + b.getInt("reward") + " coins posted", null, 0);
        Police.changed();
    }

    static boolean isWanted(UUID village, UUID folk) {
        return Police.town(village).getCompound("bounties").contains(folk.toString());
    }

    static int bountyCase(UUID village, UUID folk) {
        return Police.town(village).getCompound("bounties").getCompound(folk.toString()).getInt("case");
    }

    static void hideoutAt(UUID village, UUID folk, BlockPos at) {
        CompoundTag all = Police.town(village).getCompound("bounties");
        if (!all.contains(folk.toString())) return;
        all.getCompound(folk.toString()).putLong("hideout", at.asLong());
        Police.changed();
    }

    /** Two days lying low, and it comes home to give itself up. */
    static boolean givesUp(ServerLevel level, UUID village, UUID folk) {
        CompoundTag b = Police.town(village).getCompound("bounties").getCompound(folk.toString());
        return b.contains("day") && level.getDayTime() / 24000L - b.getLong("day") >= 2 && !castInBounty(folk);
    }

    /** Taken in: the bounty comes down. */
    static void caught(UUID village, UUID folk) {
        CompoundTag t = Police.town(village);
        CompoundTag all = t.getCompound("bounties");
        if (all.contains(folk.toString())) {
            all.remove(folk.toString());
            t.put("bounties", all);
            Police.changed();
        }
    }

    static int wantedAtLarge(UUID village) {
        return Police.town(village).getCompound("bounties").size();
    }

    /** Its card: wanted, with a bounty. */
    @Nullable
    static String bountyOn(UUID village, UUID folk) {
        CompoundTag all = Police.town(village).getCompound("bounties");
        if (!all.contains(folk.toString())) return null;
        CompoundTag b = all.getCompound(folk.toString());
        return "wanted by the watch (" + b.getString("how") + "), a bounty of " + b.getInt("reward") + " coins";
    }

    /** The wanted's names (the notice board), folk and players. */
    static List<String> wantedNames(UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag all = Police.town(village).getCompound("bounties");
        for (String key : all.getAllKeys()) out.add(all.getCompound(key).getString("name"));
        CompoundTag players = Police.town(village).getCompound("players");
        for (String key : players.getAllKeys()) {
            CompoundTag r = players.getCompound(key);
            if (r.getBoolean("wanted")) out.add(r.getString("name"));
        }
        return out;
    }

    static List<String> wantedLines(UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag all = Police.town(village).getCompound("bounties");
        for (String key : all.getAllKeys()) {
            CompoundTag b = all.getCompound(key);
            out.add("Wanted: " + b.getString("name") + ", for " + b.getString("why") + " (" + b.getString("how") + "): " + b.getInt("reward") + " coins to bring them in.");
        }
        CompoundTag players = Police.town(village).getCompound("players");
        for (String key : players.getAllKeys()) {
            CompoundTag r = players.getCompound(key);
            if (r.getBoolean("barred")) out.add("Barred: " + r.getString("name") + " (a player), for " + r.getString("why") + ", till " + r.getInt("fee")
                + " coins are paid at the watch house.");
        }
        return out;
    }

    static List<String> boardLines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag all = Police.town(village).getCompound("bounties");
        for (String key : all.getAllKeys()) {
            CompoundTag b = all.getCompound(key);
            out.add("RB|WANTED: " + b.getString("name") + ", for " + b.getString("why") + " (" + b.getString("how") + "). " + b.getInt("reward")
                + " coins to whoever brings them in.");
        }
        CompoundTag players = Police.town(village).getCompound("players");
        for (String key : players.getAllKeys()) {
            CompoundTag r = players.getCompound(key);
            if (r.getBoolean("barred")) out.add("RW|Barred: " + r.getString("name") + " (a player), for " + r.getString("why") + ". Till " + r.getInt("fee")
                + " coins are paid at the watch house.");
        }
        List<String> constables = constableLines(village);
        if (!constables.isEmpty()) out.add("RG|Special constables of the watch: " + String.join(" ", constables));
        return out;
    }

    static List<String> constableLines(UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag all = Police.town(village).getCompound("constables");
        for (String key : all.getAllKeys()) {
            CompoundTag c = all.getCompound(key);
            if (c.getLong("sworn") <= 0) continue;
            out.add(c.getString("name") + " (sworn day " + c.getLong("sworn") + "; " + c.getInt("arrests") + " arrests, " + c.getInt("cases") + " cases, "
                + c.getInt("paid") + " coins paid" + (c.getBoolean("patrol") ? "; on the beat" : "") + ").");
        }
        return out;
    }

    static List<String> crierLines(UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag all = Police.town(village).getCompound("bounties");
        for (String key : all.getAllKeys()) {
            CompoundTag b = all.getCompound(key);
            out.add("Wanted by the watch: " + b.getString("name") + ", for " + b.getString("why") + "! " + b.getInt("reward") + " coins to whoever brings them in!");
            break;
        }
        return out;
    }

    /** "Any bounties?": the wanted, and how to bring them in. */
    static String bounties(VillageFolkEntity f, ServerPlayer p) {
        UUID village = f.ownerId();
        if (village == null) return "Bounties? Not here.";
        List<String> lines = wantedLines(village);
        if (lines.isEmpty()) return "Nobody's wanted just now. A quiet town, touch wood.";
        return String.join(" ", lines) + " Find them, and they'll come quietly to the watch with you: the quest board has it.";
    }

    /** Asked about the quests (FolkTalk): the watch's bounties on the wanted, after the rest; "" with none up. */
    public static String bountyTalk(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !Police.active()) return "";
        List<String> names = new ArrayList<>();
        CompoundTag all = Police.town(village).getCompound("bounties");
        for (String key : all.getAllKeys()) names.add(all.getCompound(key).getString("name"));
        if (names.isEmpty()) return "";
        return " And the watch has a bounty up on " + String.join(" and ", names) + ": find them and bring them in.";
    }

    static void daily(ServerLevel level, Villages.Village v, long day) {
        // A bounty a week old with nobody brought in comes down; the folk is still remembered on its record.
        CompoundTag t = Police.town(v.id());
        CompoundTag all = t.getCompound("bounties");
        for (String key : new ArrayList<>(all.getAllKeys())) {
            if (day - all.getCompound(key).getLong("day") > 7) all.remove(key);
        }
        t.put("bounties", all);
        Police.changed();
    }

    // ------------------------------------------------------------------ the bounty on the quest board

    /** The quest board's bounty: find the wanted folk, and bring it to the watch (QuestMaker.register: "bounty"). */
    @Nullable
    static Quest bountyQuest(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag all = Police.town(id).getCompound("bounties");
        for (String key : all.getAllKeys()) {
            VillageFolkEntity f = Civics.find(level, uuid(key));
            if (f == null || QuestMaker.already(id, "watch.bounty", "wanted", key)) continue;
            UUID cap = Police.captainId(id);
            VillageFolkEntity giver = cap == null ? null : Civics.find(level, cap);
            if (giver == null) giver = WatchHouse.nearestGuard(level, v, v.centre(), 256.0, null);
            if (!QuestMaker.free(giver)) continue;
            CompoundTag b = all.getCompound(key);
            String name = f.displayNameCap();
            Quest q = QuestMaker.newQuest("watch.bounty", Kind.TOWN, v, giver, "Wanted: " + name,
                QuestTalk.voice(giver, name + " " + b.getString("how") + " and is lying low out past the fields. Find them and bring them to the watch, and the "
                    + "treasury pays the bounty.", "Wanted: " + name + ". Bring them in.", name + " ran from us. Help us bring them in?",
                    "There's a bounty on " + name + "! Find them and bring them in!"));
            q.coins = QuestRewards.affordTreasury(id, b.getInt("reward"));
            q.payer = "treasury";
            q.warmth = 10;
            q.days = 3;
            q.flags.put("wanted", key);
            q.flags.put("chronicle", "{who} brought in " + name + ", wanted by the watch");
            BlockPos hideout = b.contains("hideout") ? BlockPos.of(b.getLong("hideout")) : f.blockPosition();
            q.steps.add(new Step(StepType.FIND, "find", "Find " + name + ", lying low out past the fields").who(f).at(hideout, 4));
            Ledger.Building house = WatchHouse.of(id);
            BlockPos to = house != null ? WatchHouse.at(house, WatchHouse.OUTSIDE) : giver.blockPosition();
            q.steps.add(new Step(StepType.WAIT, "bring", "Bring " + name + " to the watch" + (house != null ? " house" : "") + " (they'll follow you)").at(to, 6));
            return q;
        }
        return null;
    }

    static boolean castInBounty(UUID folk) {
        for (Quest q : QuestBook.all()) if (q.open() && "watch.bounty".equals(q.script) && folk.toString().equals(q.flag("cast.wanted"))) return true;
        return false;
    }

    static final QuestRun.Script BOUNTY = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public void tick(ServerLevel level, Quest q, Player p) {
            Step s = q.current();
            if (s == null || !s.key.equals("bring") || s.at == null) return;
            VillageFolkEntity f = Civics.find(level, q.flagId("wanted"));
            if (f == null) return;
            double dx = f.getX() - s.at.getX(), dz = f.getZ() - s.at.getZ();
            if (dx * dx + dz * dz <= (double) s.radius * s.radius) {
                q.flags.remove("cast.wanted");
                QuestRun.reached(level, q, s, null, p);
            }
        }

        /** Found, it comes quietly at the finder's heels to the watch. */
        @Override
        public boolean hold(VillageFolkEntity f, ServerLevel level, Quest q) {
            Step s = q.current();
            return s != null && s.key.equals("bring") && QuestStories.follow(f, level, q.player);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            if (s.key.equals("find") && f != null) {
                q.flags.put("cast.wanted", f.getStringUUID());
                s.note = "Found, and coming quietly.";
                return "...All right. I'm sick of sleeping in hedges. I'll come with you.";
            }
            if (s.key.equals("bring")) {
                VillageFolkEntity w = Civics.find(level, q.flagId("wanted"));
                Villages.Village v = Villages.get(q.village);
                if (w != null && v != null) {
                    VillageFolkEntity g = WatchHouse.nearestGuard(level, v, w.blockPosition(), 48.0, null);
                    Incidents.Task t = Incidents.task(w);
                    if (t != null) Incidents.endTask(w);
                    int caseId = bountyCase(v.id(), w.getUUID());
                    caught(v.id(), w.getUUID());
                    if (g != null) WatchHouse.arrest(level, v, g, w, caseId, "running from the watch", -1);
                    Police.log(v.id(), level.getDayTime(), "arrest", p.getName().getString() + " brought " + w.displayNameCap() + " in for the bounty", g, 0);
                }
                q.flags.put("chronicle", q.flag("chronicle").replace("{who}", p.getName().getString()));
                s.note = "Brought in.";
                return "";
            }
            return "";
        }

        @Override
        public void ended(ServerLevel level, Quest q) {
            q.flags.remove("cast.wanted");
        }
    };

    // ------------------------------------------------------------------ helpers

    @Nullable
    static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the tests

    public static CompoundTag recordForTests(UUID village, UUID player) {
        return record(village, player).copy();
    }

    /** The watch's answer to a player seen at this offence, as if Laws had seen it (the witness, given). */
    public static void offenceForTests(ServerLevel level, Villages.Village v, Player p, VillageFolkEntity seen, String what, int fine,
                                       @Nullable Map<Item, Integer> took) {
        if (took != null) TAKEN.put(p.getUUID(), new HashMap<>(took));
        Offence kind = what.startsWith("taking") ? Offence.THEFT : what.startsWith("damaging") ? Offence.DAMAGE : Offence.OTHER;
        handle(level, v, p, seen, kind, what, fine, 0.0F);
    }

    /** The put-back window run out now. */
    public static void putBackTimeForTests(UUID player) {
        PutBack pb = PUT_BACKS.get(player);
        if (pb != null) PUT_BACKS.put(player, new PutBack(pb.village(), pb.taken(), pb.stockAfter(), 0L, pb.guard()));
    }

    public static boolean barredForTests(UUID village, UUID player) {
        return barred(village, player);
    }

    public static String payForTests(VillageFolkEntity f, Player p) {
        return pay(f, p);
    }

    public static String swearForTests(VillageFolkEntity f, ServerPlayer p) {
        return swear(f, p);
    }

    public static boolean swornForTests(UUID village, UUID player) {
        return sworn(village, player);
    }

    @Nullable
    public static String badgeArrestForTests(ServerLevel level, ServerPlayer p, VillageFolkEntity f, ItemStack badge) {
        return badgeArrest(level, p, f, badge);
    }

    public static void constableForTests(UUID village, UUID player, String name, boolean sworn) {
        CompoundTag c = constable(village, player);
        c.putString("name", name);
        c.putLong("sworn", sworn ? 1 : 0);
        Police.changed();
    }

    public static UUID badgeTownForTests(ItemStack s) {
        return badgeTown(s);
    }

    /** A badge sworn to a town in a player's name, as a guard swears one. */
    public static void bindForTests(ItemStack badge, UUID village, String player) {
        bind(badge, village, player);
    }

    /** The town's round for the players now (the put-backs watched, the wanted kept out of the way). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    /** A special constable's record: its arrests, its cases closed, its wages paid. */
    public static CompoundTag constableRecordForTests(UUID village, UUID player) {
        return constable(village, player).copy();
    }
}
