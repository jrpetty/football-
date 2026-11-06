package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * [war-after] Outlaws, and the bounty hunters sent after them: a player who will not keep a town's law.
 *
 * <p><b>Wanted.</b> Every crime a player is seen to do in a town raises how much it is wanted there (its record with the
 * town, kept with the world: AfterWarBooks): taking from the stores, a shop or a house (twelve, and more for what it was
 * worth), breaking what the town built (ten), killing its beasts (eight), striking one of its folk (fifteen), killing one
 * (sixty), breaking a prisoner out (forty-five), striking back at the watch or its hunters (twenty), and coming back into
 * the town while barred or banished (four, each minute it is seen). It eases by three a day.
 *
 * <p><b>Outlawed.</b> At fifty the town declares the player an outlaw: a wanted poster on its board, and on its allies'
 * boards; its guards go for the player on sight (Laws.outlaw), its shops and inn turn it away (Laws.banished), its folk
 * will not talk to it (they are afraid, and a guard is called); and a bounty is set on it out of the treasury (a fifth of
 * what is in it, ten coins and half the wanted level at most), held by the watch till it is claimed.
 *
 * <p><b>The hunters.</b> While an outlaw is within a hundred and sixty blocks of the town, the watch sends a party of up to
 * three guards after it (and one of an ally's, if the ally is close), on the stable's saddled horses where it has them. They
 * ride it down; the blow that would take it below two hearts takes it instead: caught. The hunters claim the bounty (into
 * their purses), and the outlaw pays its fine out of its purse (fifteen and half the wanted level), or, short of it, is
 * locked in the watch house's cells for its time (or owes the rest). Either way the account is settled. The party gives up
 * after two minutes, or when the outlaw is out of reach; an outlaw who logs off is let be till it comes back.
 *
 * <p><b>The way back.</b> Pay the fine and the bounty at the watch house (to any guard of the town, or its leader);
 * give yourself up to a guard and serve your time; ask the leader for a pardon (given freely to a friend of the town,
 * else for a gift of coin); or keep away and wait for it to ease. And another player may claim the bounty: an outlaw
 * killed by a player the town has nothing against owes its bounty to the one who brought it down, paid by any guard of
 * that town ("I've come to claim a bounty"), as earnings.
 *
 * <p>Public: {@link #isOutlaw(UUID, UUID)} and {@link #wanted(UUID, UUID)}, and {@link #listen} for whoever must know of a
 * player outlawed (a knight losing its title).
 */
public final class Outlaws {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Outlaws() {}

    /** The crimes that make an outlaw, and how much each makes a player wanted. */
    public enum Deed {
        THEFT(12, "theft"), DAMAGE(10, "damage"), POACHING(8, "poaching"), HARM(15, "striking our folk"), KILL(60, "murder"),
        BREAKOUT(45, "breaking a prisoner out"), RESIST(20, "striking the watch"), TRESPASS(4, "trespass");

        public final int weight;
        public final String words;

        Deed(int weight, String words) {
            this.weight = weight;
            this.words = words;
        }
    }

    /** Wanted this much, an outlaw; eased below this, no longer (on its own: the bounty comes back to the treasury). */
    public static final int OUTLAW = 50, CLEARS = 20;
    /** How much the wanted level eases a day. */
    static final int EASES = 3;
    /** How near the town an outlaw must come for the hunters to ride out. */
    static final int HUNT_RANGE = 160;
    /** A hunt's longest (ticks), and the rest between one and the next. */
    static final long HUNT_MOST = 2400L, HUNT_REST = 1200L;
    /** The hunters' party at most, and the blow that catches an outlaw: below this much health. */
    static final int PARTY = 3;
    static final float CAUGHT = 4.0F;

    /** Hunts, on in a game; stood down between the tests till one stands them up. */
    private static volatile boolean live = true;
    /** Tests: a creative player (the test's stand-in) counted as any other. */
    private static volatile boolean creativeToo;

    /** A hunt: the town, its quarry, the hunters, when it began, the hunters' horses. */
    static final class Hunt {
        final UUID town, player;
        final List<UUID> hunters = new ArrayList<>();
        final Map<UUID, UUID> horses = new ConcurrentHashMap<>();
        /** Where each hunter goes home to (an ally's guard to its own town). */
        final Map<UUID, BlockPos> homes = new ConcurrentHashMap<>();
        final long since;
        long lastSeen;

        Hunt(UUID town, UUID player, long since) {
            this.town = town;
            this.player = player;
            this.since = since;
            this.lastSeen = since;
        }
    }

    /** By "town/player". */
    private static final Map<String, Hunt> HUNTS = new ConcurrentHashMap<>();
    /** By hunter. */
    private static final Map<UUID, Hunt> HUNTERS = new ConcurrentHashMap<>();
    /** Hunters riding home, by hunter: where to. */
    private static final Map<UUID, BlockPos> HOMEWARD = new ConcurrentHashMap<>();
    /** When each town last hunted each player (ticks), by "town/player". */
    private static final Map<String, Long> RESTED = new ConcurrentHashMap<>();
    /** When a player was last counted trespassing, by "town/player". */
    private static final Map<String, Long> TRESPASSED = new ConcurrentHashMap<>();
    private static final List<BiConsumer<UUID, UUID>> LISTENERS = new CopyOnWriteArrayList<>();

    static void resetForTests() {
        live = false;
        creativeToo = false;
        HUNTS.clear();
        HUNTERS.clear();
        HOMEWARD.clear();
        RESTED.clear();
        TRESPASSED.clear();
        for (String k : new ArrayList<>(AfterWarBooks.outlaws().getAllKeys())) AfterWarBooks.outlaws().remove(k);
    }

    public static void liveForTests(boolean on) {
        live = on;
    }

    static boolean liveNow() {
        return live;
    }

    /** Somebody to tell when a player is declared an outlaw of a town (town, player). */
    public static void listen(BiConsumer<UUID, UUID> onOutlawed) {
        LISTENERS.add(onOutlawed);
    }

    // ------------------------------------------------------------------ the records

    @Nullable
    static CompoundTag record(@Nullable UUID town, UUID player, boolean make) {
        if (town == null) return null;
        CompoundTag book = make ? AfterWarBooks.outlawsOf(town.toString()) : AfterWarBooks.outlaws().getCompound(town.toString());
        String key = player.toString();
        if (!book.contains(key, Tag.TAG_COMPOUND)) {
            if (!make) return null;
            book.put(key, new CompoundTag());
        }
        return book.getCompound(key);
    }

    /** Is this player an outlaw of this town? */
    public static boolean isOutlaw(@Nullable UUID town, @Nullable UUID player) {
        if (town == null || player == null) return false;
        CompoundTag r = record(town, player, false);
        return r != null && r.getBoolean("outlaw");
    }

    /** How much this town wants this player: 0 (not at all), fifty an outlaw, a hundred at most. */
    public static int wanted(@Nullable UUID town, @Nullable UUID player) {
        if (town == null || player == null) return 0;
        CompoundTag r = record(town, player, false);
        return r == null ? 0 : r.getInt("wanted");
    }

    /** The bounty held on a player by a town, in coin (0 if none). */
    public static int bounty(@Nullable UUID town, @Nullable UUID player) {
        if (town == null || player == null) return 0;
        CompoundTag r = record(town, player, false);
        return r == null || !r.getBoolean("outlaw") ? 0 : r.getInt("bounty");
    }

    /** Every town this player is an outlaw of. */
    public static List<UUID> outlawedBy(UUID player) {
        List<UUID> out = new ArrayList<>();
        CompoundTag all = AfterWarBooks.outlaws();
        for (String town : all.getAllKeys()) {
            CompoundTag r = all.getCompound(town).getCompound(player.toString());
            if (r.getBoolean("outlaw")) {
                try { out.add(UUID.fromString(town)); } catch (IllegalArgumentException ignored) { }
            }
        }
        return out;
    }

    /** The outlaws of a town, as "player id -> record". */
    static Map<UUID, CompoundTag> outlawsOf(UUID town) {
        Map<UUID, CompoundTag> out = new java.util.LinkedHashMap<>();
        CompoundTag book = AfterWarBooks.outlaws().getCompound(town.toString());
        for (String k : book.getAllKeys()) {
            CompoundTag r = book.getCompound(k);
            if (!r.getBoolean("outlaw")) continue;
            try { out.put(UUID.fromString(k), r); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ crimes

    /** [PlayerLaw.handle] The watch's offence, as a deed. */
    static Deed deedOf(PlayerLaw.Offence o) {
        return switch (o) {
            case THEFT -> Deed.THEFT;
            case DAMAGE -> Deed.DAMAGE;
            case POACHING -> Deed.POACHING;
            case HARM -> Deed.HARM;
            default -> Deed.DAMAGE;
        };
    }

    /** [Laws.offence] The old ladder's offence, by its words. */
    static Deed deedOf(String what) {
        return what.startsWith("taking") ? Deed.THEFT : what.startsWith("killing") || what.startsWith("poaching") ? Deed.POACHING
            : what.startsWith("striking") ? Deed.HARM : Deed.DAMAGE;
    }

    /**
     * A crime seen in a town: the player the more wanted there; past the line, an outlaw. Returns the wanted level now.
     * (A creative or spectating player, or the town's own leader, is passed over.)
     */
    public static int crime(ServerLevel level, Villages.Village v, Player p, Deed deed, String what) {
        if (!live || Laws.exempt(p) && !creativeToo || PlayerLeader.leads(v.id(), p.getUUID())) return 0;
        long day = level.getDayTime() / 24000L;
        CompoundTag r = record(v.id(), p.getUUID(), true);
        r.putString("name", p.getName().getString());
        int was = r.getInt("wanted");
        int now = Math.min(100, was + deed.weight);
        r.putInt("wanted", now);
        r.putLong("lastCrime", day);
        r.putString("why", what);
        r.putInt("crimes", r.getInt("crimes") + 1);
        ListTag deeds = r.getList("deeds", Tag.TAG_STRING);
        deeds.add(StringTag.valueOf("day " + day + ": " + what));
        while (deeds.size() > 8) deeds.remove(0);
        r.put("deeds", deeds);
        AfterWarBooks.changed();
        if (!r.getBoolean("outlaw") && now >= OUTLAW) declare(level, v, p, r, day);
        else if (r.getBoolean("outlaw") && deed != Deed.TRESPASS) {
            Villages.tell(v.id(), day, p.getName().getString() + ", our outlaw, was seen " + what);
            raise(level, v, r, Math.min(15, deed.weight / 3));
        }
        LOG.info("[MCA-OUTLAW] {} in {}: {} (+{} = {})", p.getName().getString(), Villages.name(v.id()), what, deed.weight, now);
        return now;
    }

    /** The bounty raised (out of the treasury), as far as the treasury runs. */
    static void raise(ServerLevel level, Villages.Village v, CompoundTag r, int by) {
        int took = Ledger.takeCoins(v.id(), Math.max(0, Math.min(by, Ledger.coins(v.id()) / 5)));
        if (took <= 0) return;
        r.putInt("bounty", r.getInt("bounty") + took);
        AfterWarBooks.changed();
    }

    /** Declared an outlaw: the poster up, the bounty set out of the treasury, the watch told to take it on sight. */
    static void declare(ServerLevel level, Villages.Village v, Player p, CompoundTag r, long day) {
        String name = p.getName().getString(), town = Villages.name(v.id());
        r.putBoolean("outlaw", true);
        r.putLong("since", day);
        if (Citizens.is(v.id(), p.getUUID())) Ledger.removeCitizen(v.id(), p.getUUID());   // cast out: a citizen no more
        int want = 10 + r.getInt("wanted") / 2;
        int bounty = Ledger.takeCoins(v.id(), Math.min(want, Ledger.coins(v.id()) / 5));
        r.putInt("bounty", bounty);
        AfterWarBooks.changed();
        Villages.tell(v.id(), day, name + " was declared an outlaw of the town for " + r.getString("why") + (bounty > 0 ? "; a bounty of " + bounty + " coins is on its head" : ""));
        for (UUID ally : WarAndPeace.alliesOf(v.id())) {
            Villages.tell(ally, day, town + " declared " + name + " an outlaw; the poster is on our board too");
        }
        Police.log(v.id(), level.getDayTime(), "outlaw", name + " (a player) was declared an outlaw" + (bounty > 0 ? ", a bounty of " + bounty + " coins" : ""), null, bounty);
        p.sendSystemMessage(Component.literal("You are declared an OUTLAW of " + town + " for " + r.getString("why") + ". Its watch will take you on sight, its shops "
            + "will not serve you" + (bounty > 0 ? ", and there is a bounty of " + bounty + " coins on your head" : "") + ". Pay your fine at the watch house, give "
            + "yourself up, ask the leader for a pardon, or keep away till it eases.").withStyle(ChatFormatting.DARK_RED));
        level.playSound(null, v.centre(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 0.6F);
        Raids.tellNear(level, v.centre(), 160, Component.literal(town + " has declared " + name + " an outlaw" + (bounty > 0 ? ": " + bounty + " coins on its head" : "") + ".")
            .withStyle(ChatFormatting.RED), false);
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f) f.persona().remember(day, name + " was declared an outlaw of the town", 4);
        }
        for (BiConsumer<UUID, UUID> l : LISTENERS) com.jrpetty.mcassistant.Guard.run("outlawed", () -> l.accept(v.id(), p.getUUID()));
        LOG.info("[MCA-OUTLAW] {} declared an outlaw of {} (bounty {})", name, town, bounty);
    }

    /** The account settled (paid, served, pardoned, caught, eased): no longer an outlaw; the bounty's coin as given. */
    static void settle(UUID town, UUID player, CompoundTag r, long day, String how, int wantedAfter) {
        r.putBoolean("outlaw", false);
        r.putInt("wanted", Math.min(r.getInt("wanted"), wantedAfter));
        r.putInt("bounty", 0);
        r.putLong("settled", day);
        r.putString("settledHow", how);
        AfterWarBooks.changed();
        Hunt h = HUNTS.get(town + "/" + player);
        if (h != null) end(h, "the account settled");
        Villages.tell(town, day, r.getString("name") + " is an outlaw no more: " + how);
    }

    /** A day: every town's wanted ease; an outlaw eased far enough is let be, its bounty back to the treasury. */
    static void eases(ServerLevel level, long day) {
        CompoundTag all = AfterWarBooks.outlaws();
        for (String town : all.getAllKeys()) {
            CompoundTag book = all.getCompound(town);
            UUID t;
            try { t = UUID.fromString(town); } catch (IllegalArgumentException e) { continue; }
            for (String k : book.getAllKeys()) {
                CompoundTag r = book.getCompound(k);
                if (r.getLong("eased") >= day) continue;
                r.putLong("eased", day);
                int w = Math.max(0, r.getInt("wanted") - EASES);
                r.putInt("wanted", w);
                if (r.getBoolean("outlaw") && w < CLEARS) {
                    int back = r.getInt("bounty");
                    if (back > 0 && Villages.get(t) != null) Ledger.addCoins(t, back);
                    try { settle(t, UUID.fromString(k), r, day, "the matter let lie, long enough", w); } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        AfterWarBooks.changed();
    }

    // ------------------------------------------------------------------ the hunt (AfterWar, every second)

    /** A town's look for its outlaws near: a party sent after one in reach; trespassers counted. */
    static void watch(ServerLevel level, Villages.Village v) {
        if (!live) return;
        long gt = level.getGameTime(), day = level.getDayTime() / 24000L;
        UUID id = v.id();
        int reach = Villages.townReach(id);
        for (ServerPlayer p : level.players()) {
            if (Laws.exempt(p) && !creativeToo || !p.isAlive()) continue;
            double flat = Math.max(Math.abs(p.getX() - v.centre().getX()), Math.abs(p.getZ() - v.centre().getZ()));
            // Back in the town while barred or banished, and seen: trespass, a minute at a time.
            if (flat <= reach && !isOutlaw(id, p.getUUID()) && Laws.banished(id, p.getUUID(), day)) {
                String key = id + "/" + p.getUUID();
                Long last = TRESPASSED.get(key);
                if ((last == null || gt - last >= 1200 || gt < last) && Laws.witness(level, id, p) != null) {
                    TRESPASSED.put(key, gt);
                    crime(level, v, p, Deed.TRESPASS, "coming back into the town while barred");
                }
            }
            if (!isOutlaw(id, p.getUUID()) || flat > HUNT_RANGE) continue;
            String key = id + "/" + p.getUUID();
            Hunt h = HUNTS.get(key);
            if (h != null) continue;
            Long rested = RESTED.get(key);
            if (rested != null && gt - rested < HUNT_REST && gt >= rested) continue;
            if (PlayerLaw.record(id, p.getUUID()).getLong("jailUntil") > gt) continue;      // (in the cells already)
            ride(level, v, p, gt);
        }
    }

    /** The party sent out: up to three of the town's free guards (one of an ally's, close), on the stable's horses. */
    static boolean ride(ServerLevel level, Villages.Village v, ServerPlayer p, long gt) {
        List<VillageFolkEntity> party = new ArrayList<>();
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (party.size() >= PARTY) break;
            if (!g.isAlive() || g.isBaby() || g.isSleeping() || HUNTERS.containsKey(g.getUUID()) || !WatchHouse.freeFor(g)) continue;
            if (Armies.fighter(g.getUUID()) != null || Spies.held(g) || g.trip() != null) continue;
            party.add(g);
        }
        for (UUID ally : WarAndPeace.alliesOf(v.id())) {
            Villages.Village a = Villages.get(ally);
            if (a == null || !a.dim().equals(level.dimension()) || a.centre().distSqr(p.blockPosition()) > 200 * 200) continue;
            for (VillageFolkEntity g : Patrols.watch(ally)) {
                if (!g.isAlive() || HUNTERS.containsKey(g.getUUID()) || !WatchHouse.freeFor(g) || g.trip() != null) continue;
                party.add(g);
                break;
            }
        }
        if (party.isEmpty()) return false;
        Hunt h = new Hunt(v.id(), p.getUUID(), gt);
        long day = level.getDayTime() / 24000L;
        Stables.Stable st = Stables.stable(v.id());
        for (VillageFolkEntity g : party) {
            h.hunters.add(g.getUUID());
            Villages.Village own = Villages.get(g.ownerId());
            h.homes.put(g.getUUID(), own == null ? v.centre() : own.centre());
            HUNTERS.put(g.getUUID(), h);
            HOMEWARD.remove(g.getUUID());
            g.clearQueue();
            g.equipBestWeapon();
            if (st != null && g.blockPosition().distSqr(st.anchor()) < 24 * 24 && !g.isPassenger()) {
                AbstractHorse horse = Riding.freeHorse(level, v.id(), st);
                if (horse != null && horse.distanceToSqr(g) < 8 * 8 && Riding.mount(g, horse)) h.horses.put(g.getUUID(), horse.getUUID());
            }
        }
        HUNTS.put(v.id() + "/" + p.getUUID(), h);
        String names = party.get(0).displayNameCap() + (party.size() > 1 ? " and " + (party.size() - 1) + " more" : "");
        FolkTalk.speak(party.get(0), FolkTalk.pick(party.get(0).getRandom(), "There's the outlaw! After " + p.getName().getString() + "!",
            "Bounty on that one's head. Come on!", "Saddle up. We've an outlaw to bring in."));
        Villages.tell(v.id(), day, names + " of the watch " + (h.horses.isEmpty() ? "set out" : "rode out") + " after " + p.getName().getString() + ", the outlaw");
        Police.log(v.id(), level.getDayTime(), "hunt", names + (h.horses.isEmpty() ? " set out" : " rode out") + " after " + p.getName().getString() + " (an outlaw)", party.get(0), 0);
        p.sendSystemMessage(Component.literal("Bounty hunters of " + Villages.name(v.id()) + " are after you!").withStyle(ChatFormatting.RED));
        level.playSound(null, party.get(0).blockPosition(), SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0).value(), SoundSource.NEUTRAL, 2.0F, 1.0F);
        LOG.info("[MCA-OUTLAW] {} sends {} after {}", Villages.name(v.id()), party.size(), p.getName().getString());
        return true;
    }

    /** [VillageFolkEntity.aiStep] A hunter at its hunt, or riding home after it: true while it is. */
    public static boolean hunting(VillageFolkEntity f, ServerLevel level) {
        if (HUNTERS.isEmpty() && HOMEWARD.isEmpty()) return false;
        Hunt h = HUNTERS.get(f.getUUID());
        if (h == null) {
            BlockPos home = HOMEWARD.get(f.getUUID());
            if (home == null) return false;
            if (f.blockPosition().distSqr(home) < 12 * 12 || f.tickCount % 400 == 0 && !Prisoners.seen(level, f.blockPosition()) && f.blockPosition().distSqr(home) > 96 * 96) {
                // (Far off where nobody sees, it is simply home: as an army's straggler is.)
                if (f.blockPosition().distSqr(home) > 96 * 96) {
                    if (f.getVehicle() != null) f.stopRiding();
                    f.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, f.getYRot(), 0.0F);
                }
                HOMEWARD.remove(f.getUUID());
                if (f.getVehicle() instanceof AbstractHorse horse) {
                    f.stopRiding();
                    horse.clearRestriction();
                }
                return false;
            }
            if (f.tickCount % 20 == 0) f.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0D);   // (riding, its horse with it, as Riding has it)
            f.hobbyNow = "riding home from the hunt";
            return true;
        }
        if (f.tickCount % 5 != 0) return true;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.player);
        if (p == null || p.level() != level || !p.isAlive()) return true;          // (the round settles it)
        f.hobbyNow = "hunting " + p.getName().getString() + ", the outlaw";
        double d = f.distanceToSqr(p);
        if (f.getVehicle() instanceof AbstractHorse horse) {
            if (d < 6 * 6) {
                f.stopRiding();
                horse.restrictTo(horse.blockPosition(), 3);
            } else if (f.tickCount % 10 == 0) {
                f.getNavigation().moveTo(p, 1.2D);                           // the rider's way, its horse under it (Riding)
            }
            return true;
        }
        if (d > 2.6 * 2.6) {
            f.setSprinting(true);
            if (f.tickCount % 10 == 0 || f.getNavigation().isDone()) f.getNavigation().moveTo(p, 1.3D);
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        if (f.getMainHandItem().isEmpty()) f.equipBestWeapon();
        if (f.tickCount % 20 == 0) {
            f.swing(InteractionHand.MAIN_HAND);
            f.doHurtTarget(p);
            if (f.getRandom().nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Give it up, " + p.getName().getString() + "!", "In the name of " + Villages.name(h.town) + "!",
                "You'll hang for this... or pay, at least."));
        }
        return true;
    }

    /** The hunts gone over (every second): given up when it is out of reach, too long at it, or gone. */
    static void hunts(ServerLevel level) {
        long gt = level.getGameTime();
        for (Hunt h : new ArrayList<>(HUNTS.values())) {
            Villages.Village v = Villages.get(h.town);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.player);
            if (v == null) { end(h, "the town gone"); continue; }
            if (!v.dim().equals(level.dimension())) continue;
            if (p == null) { end(h, "the outlaw gone from the world"); continue; }
            if (!isOutlaw(h.town, h.player)) { end(h, "the account settled"); continue; }
            h.hunters.removeIf(id -> !(level.getEntity(id) instanceof VillageFolkEntity g) || !g.isAlive());
            if (h.hunters.isEmpty()) { end(h, "no hunters left standing"); continue; }
            double flat = Math.max(Math.abs(p.getX() - v.centre().getX()), Math.abs(p.getZ() - v.centre().getZ()));
            if (flat > HUNT_RANGE + 64 || p.level() != level) {
                end(h, p.getName().getString() + " got clean away");
                Villages.tell(h.town, level.getDayTime() / 24000L, "the hunters lost " + p.getName().getString() + "'s trail and came home");
                continue;
            }
            if (gt - h.since > HUNT_MOST) end(h, "the hunters gave it up for today");
        }
    }

    /** A hunt over: the hunters home (riding, if they rode out), the next hunt after a rest. */
    static void end(Hunt h, String why) {
        HUNTS.remove(h.town + "/" + h.player);
        Villages.Village v = Villages.get(h.town);
        for (UUID g : h.hunters) {
            HUNTERS.remove(g);
            BlockPos home = h.homes.get(g);
            if (home == null && v != null) home = v.centre();
            if (home != null) HOMEWARD.put(g, home);
        }
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) RESTED.put(h.town + "/" + h.player, server.overworld().getGameTime());
        LOG.info("[MCA-OUTLAW] the hunt of {} by {} is over: {}", h.player, Villages.name(h.town), why);
    }

    /** [AfterWar, a player's incoming blow] A hunter's blow that would take its quarry low takes it instead: caught. */
    static boolean blow(ServerPlayer p, VillageFolkEntity by, float amount) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        Hunt h = HUNTERS.get(by.getUUID());
        if (h == null || !h.player.equals(p.getUUID())) {
            // Any of the watch of a town it is an outlaw of, out on its own (taken on sight): the catch is the town's all the same.
            UUID town = by.ownerId();
            if (town == null || by.stationTask() != AssistantEntity.StationTask.GUARD || !isOutlaw(town, p.getUUID())) return false;
            if (p.getHealth() - amount > CAUGHT) return false;
            h = HUNTS.get(town + "/" + p.getUUID());
            if (h == null) h = new Hunt(town, p.getUUID(), level.getGameTime());
            if (!h.hunters.contains(by.getUUID())) h.hunters.add(by.getUUID());
        }
        if (p.getHealth() - amount > CAUGHT) return false;
        caught(level, h, by, p);
        return true;
    }

    /** Caught: the hunters claim the bounty; the outlaw pays its fine, or serves its time, or owes the rest. */
    static void caught(ServerLevel level, Hunt h, @Nullable VillageFolkEntity by, ServerPlayer p) {
        Villages.Village v = Villages.get(h.town);
        CompoundTag r = record(h.town, p.getUUID(), true);
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString(), town = Villages.name(h.town);
        int bounty = r.getInt("bounty");
        // The bounty, shared among the hunters' purses.
        List<VillageFolkEntity> hunters = new ArrayList<>();
        for (UUID g : h.hunters) if (level.getEntity(g) instanceof VillageFolkEntity e) hunters.add(e);
        for (int i = 0; i < hunters.size() && bounty > 0; i++) {
            int share = bounty / (hunters.size() - i);
            hunters.get(i).earn(share);
            bounty -= share;
            hunters.get(i).persona().remember(day, "we brought in " + name + ", the outlaw, and shared the bounty", 6);
        }
        int fine = fine(r);
        int paid = PlayerLaw.take(p, h.town, fine);
        String how;
        if (paid >= fine) {
            how = "caught by the hunters, and paid a fine of " + fine + " coins";
        } else if (v != null && PlayerLaw.jail(level, v, p)) {
            how = "caught by the hunters and locked in the cells" + (paid > 0 ? " (" + paid + " coins of the fine paid)" : "");
        } else {
            int[] book = Ledger.record(h.town, p.getUUID());
            book[1] += fine - paid;
            Ledger.record(h.town, p.getUUID(), book);
            how = "caught by the hunters; " + (fine - paid) + " coins of the fine still owed";
        }
        r.putInt("caught", r.getInt("caught") + 1);
        settle(h.town, p.getUUID(), r, day, how, CLEARS);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 2));
        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 400, 1));
        p.sendSystemMessage(Component.literal("Caught! The hunters of " + town + " have you: " + how + ". You are an outlaw there no more.").withStyle(ChatFormatting.GOLD));
        if (by != null) FolkTalk.speak(by, FolkTalk.pick(by.getRandom(), "Got you. That's the end of it.", "Down you go. " + town + " will have its due."));
        level.playSound(null, p.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);
        Police.log(h.town, level.getDayTime(), "arrest", "the hunters caught " + name + " (an outlaw): " + how, by, paid);
        LOG.info("[MCA-OUTLAW] {} caught by the hunters of {}: {}", name, town, how);
    }

    /** What an outlaw's account comes to: fifteen, and half its wanted level. */
    static int fine(CompoundTag r) {
        return 15 + r.getInt("wanted") / 2;
    }

    /** [AfterWar, a player's death] An outlaw killed: by a hunter, caught; by another player, that player's bounty to claim. */
    static void died(ServerPlayer dead, @Nullable net.minecraft.world.entity.Entity killer) {
        if (!(dead.level() instanceof ServerLevel level)) return;
        long day = level.getDayTime() / 24000L;
        for (UUID town : outlawedBy(dead.getUUID())) {
            CompoundTag r = record(town, dead.getUUID(), true);
            if (killer instanceof VillageFolkEntity g && HUNTERS.get(g.getUUID()) != null && HUNTERS.get(g.getUUID()).town.equals(town)) {
                caught(level, HUNTERS.get(g.getUUID()), g, dead);
                continue;
            }
            if (!(killer instanceof ServerPlayer claimant) || claimant == dead || isOutlaw(town, claimant.getUUID())) continue;
            int bounty = r.getInt("bounty");
            CompoundTag claims = r.getCompound("claims");
            claims.putInt(claimant.getUUID().toString(), claims.getInt(claimant.getUUID().toString()) + bounty);
            r.put("claims", claims);
            r.putInt("bounty", 0);
            settle(town, dead.getUUID(), r, day, "brought down by " + claimant.getName().getString() + ", who has the bounty to claim", CLEARS);
            claimant.sendSystemMessage(Component.literal("You brought down " + dead.getName().getString() + ", an outlaw of " + Villages.name(town)
                + (bounty > 0 ? ". Claim the bounty of " + bounty + " coins from any guard of " + Villages.name(town) + "." : ".")).withStyle(ChatFormatting.GOLD));
        }
    }

    /** Logged off while hunted: the hunters let it be for now. */
    static void loggedOff(UUID player) {
        for (Hunt h : new ArrayList<>(HUNTS.values())) if (h.player.equals(player)) end(h, "the outlaw slipped away (logged off)");
        TRESPASSED.keySet().removeIf(k -> k.endsWith("/" + player));
    }

    // ------------------------------------------------------------------ talk

    /**
     * Talk with a player who is wanted here: the folk afraid of an outlaw (a guard called), a guard's terms, paying the
     * fine, giving itself up, a pardon of the leader; and a bounty claimed. Null when none of it applies.
     */
    @Nullable
    static String talk(VillageFolkEntity f, ServerPlayer p, TalkTopic topic, String text) {
        UUID town = f.ownerId();
        if (town == null || !(f.level() instanceof ServerLevel level)) return null;
        String t = text.toLowerCase(Locale.ROOT);
        long day = level.getDayTime() / 24000L;
        if (t.contains("claim") && t.contains("bount")) return claim(level, f, p);
        if (t.contains("wanted poster") || t.contains("a poster")) return poster(level, f, p);
        if (!isOutlaw(town, p.getUUID())) {
            if (topic == TalkTopic.SAY && t.contains("outlaw")) return wantedList(town);    // (the watch's own bounties on folk are PlayerLaw's)
            return null;
        }
        CompoundTag r = record(town, p.getUUID(), true);
        if (t.contains("pardon")) return pardon(level, f, p, r, t, day);
        boolean guard = f.stationTask() == AssistantEntity.StationTask.GUARD || f.getUUID().equals(Villages.elder(town));
        if (guard && (topic == TalkTopic.FINE || t.contains("pay"))) return payFine(level, f, p, r, day);
        if (guard && (t.contains("give myself up") || t.contains("surrender") || t.contains("turn myself in"))) return giveUp(level, f, p, r, day);
        if (topic == TalkTopic.GIFT) return null;                     // a gift is a gift, outlaw or not
        if (guard) {
            return "You're an outlaw of " + Villages.name(town) + ", " + p.getName().getString() + ". Pay your fine of " + (fine(r) + r.getInt("bounty"))
                + " coins, or give yourself up and serve your time" + (f.getUUID().equals(Villages.elder(town)) ? ", or ask me for a pardon" : "") + ".";
        }
        // Afraid: it backs off, and calls the watch.
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        if (f.getRandom().nextInt(3) == 0) {
            Villages.Village v = Villages.get(town);
            VillageFolkEntity g = v == null ? null : WatchHouse.nearestGuard(level, v, f.blockPosition(), 48.0, null);
            if (g != null) FolkTalk.speak(g, "The outlaw! Over there!");
        }
        f.persona().remember(day, "the outlaw " + p.getName().getString() + " spoke to me", 3);
        return FolkTalk.pick(f.getRandom(), "Get away from me! Guards! Guards!", "You're the outlaw — I've seen your poster. Leave me be!",
            "I'll not say a word to you. Go, before the watch comes.");
    }

    static String wantedList(UUID town) {
        Map<UUID, CompoundTag> all = outlawsOf(town);
        if (all.isEmpty()) return "No outlaws posted. Keep the law and it'll stay that way.";
        List<String> s = new ArrayList<>();
        for (CompoundTag r : all.values()) s.add(r.getString("name") + " (" + r.getString("why") + (r.getInt("bounty") > 0 ? ", " + r.getInt("bounty") + " coins on its head" : "") + ")");
        return "The outlaws on our poster: " + String.join("; ", s) + ". Bring one down and the bounty's yours: claim it of any guard.";
    }

    /** "I'll pay my fine": the fine and the bounty, into the treasury; the account settled. */
    static String payFine(ServerLevel level, VillageFolkEntity f, ServerPlayer p, CompoundTag r, long day) {
        UUID town = f.ownerId();
        int owe = fine(r) + r.getInt("bounty");
        int have = Market.coinsHeld(p);
        if (have < owe) return "Your account is " + owe + " coins — the fine, and the bounty we put up for you — and you've " + have + ". Come back with it, or give yourself up.";
        Market.payOut(p, owe);
        Ledger.addCoins(town, owe + r.getInt("bounty"));                  // the bounty the watch held back into the treasury too
        Police.log(town, level.getDayTime(), "fine", p.getName().getString() + " (an outlaw) paid " + owe + " and settled its account", f, owe);
        settle(town, p.getUUID(), r, day, "it paid " + owe + " coins at the watch house", CLEARS);
        Standing.stir(town, p.getUUID());
        level.playSound(null, f.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8F, 1.0F);
        return "That's " + owe + " coins, and your account is settled. You're no outlaw here now — mind it stays that way.";
    }

    /** "I give myself up": the cells for its time, the account settled (or, with no cell, the fine owed). */
    static String giveUp(ServerLevel level, VillageFolkEntity f, ServerPlayer p, CompoundTag r, long day) {
        UUID town = f.ownerId();
        Villages.Village v = Villages.get(town);
        Ledger.addCoins(town, r.getInt("bounty"));                         // nobody to pay it to: back in the treasury
        if (v != null && PlayerLaw.jail(level, v, p)) {
            settle(town, p.getUUID(), r, day, "it gave itself up and served its time in the cells", CLEARS);
            return "Sensible. In you go — you'll be out when your time's served, and that's the end of it.";
        }
        int[] book = Ledger.record(town, p.getUUID());
        book[1] += fine(r);
        Ledger.record(town, p.getUUID(), book);
        settle(town, p.getUUID(), r, day, "it gave itself up; with no cell free, its fine is owed", CLEARS);
        return "No cell free to put you in. Your fine of " + fine(r) + " is on the book: pay it when you can. Go on.";
    }

    /** "I ask for a pardon": the leader's to give; freely to a friend of the town, else for a gift of coin. */
    static String pardon(ServerLevel level, VillageFolkEntity f, ServerPlayer p, CompoundTag r, String t, long day) {
        UUID town = f.ownerId();
        if (!f.getUUID().equals(Villages.elder(town))) return "A pardon? That's for the leader to give, not me.";
        int warmth = f.persona().affinity(p.getUUID());
        int price = 2 * (fine(r) + r.getInt("bounty"));
        String name = p.getName().getString();
        if (warmth >= 40) {
            Ledger.addCoins(town, r.getInt("bounty"));
            settle(town, p.getUUID(), r, day, "the leader pardoned it, for all it has done for the town", 10);
            Villages.tell(town, day, "the leader pardoned " + name + ", for what it has done for the town");
            return "You've done this town more good than harm, " + name + ". You're pardoned. Don't make me regret it.";
        }
        if (!t.contains("pay")) return "A pardon is not given lightly. A gift of " + price + " coins to the town, and I'll think it settled. Say you'll pay for a pardon.";
        int have = Market.coinsHeld(p);
        if (have < price) return "The gift is " + price + " coins, and you've " + have + ". Come back when you have it.";
        Market.payOut(p, price);
        Ledger.addCoins(town, price + r.getInt("bounty"));
        settle(town, p.getUUID(), r, day, "the leader pardoned it for a gift of " + price + " coins to the town", 10);
        Villages.tell(town, day, "the leader pardoned " + name + " for a gift of " + price + " coins to the town");
        return "Then you're pardoned, " + name + ". The town is the richer for it, and you the wiser, I hope.";
    }

    /** "I've come to claim a bounty": what a player is owed for an outlaw it brought down, paid by a guard. */
    static String claim(ServerLevel level, VillageFolkEntity f, ServerPlayer p) {
        UUID town = f.ownerId();
        if (f.stationTask() != AssistantEntity.StationTask.GUARD) return "Bounties are the watch's business. Ask a guard.";
        CompoundTag book = AfterWarBooks.outlaws().getCompound(town.toString());
        int owed = 0;
        List<String> who = new ArrayList<>();
        for (String k : book.getAllKeys()) {
            CompoundTag r = book.getCompound(k);
            CompoundTag claims = r.getCompound("claims");
            int n = claims.getInt(p.getUUID().toString());
            if (n <= 0 && !claims.contains(p.getUUID().toString())) continue;
            owed += n;
            who.add(r.getString("name"));
            claims.remove(p.getUUID().toString());
            r.put("claims", claims);
        }
        if (who.isEmpty()) return "We owe you nothing that I know of. Bring down one of the outlaws on our poster, then come and see me.";
        AfterWarBooks.changed();
        if (owed > 0) {
            Earnings.giveCoins(p, owed);
            Earnings.record(p, town, Earnings.Source.BOUNTY, owed, Villages.name(town) + "'s watch", "for bringing down " + String.join(" and ", who));
        }
        Police.log(town, level.getDayTime(), "bounty", p.getName().getString() + " (a player) claimed the bounty on " + String.join(", ", who), f, owed);
        for (AssistantEntity a : Villages.folkOf(town)) if (a instanceof VillageFolkEntity o) o.persona().feelFor(p.getUUID(), p.getName().getString(), 4);
        return owed > 0 ? "Here's your " + owed + " coins, bounty hunter. " + Villages.name(town) + " thanks you." : "There was no coin on that one's head, but the town thanks you all the same.";
    }

    /** "Have you a wanted poster?": one written up out of the stores' paper (the town's outlaws), into the player's hands. */
    static String poster(ServerLevel level, VillageFolkEntity f, ServerPlayer p) {
        UUID town = f.ownerId();
        Map<UUID, CompoundTag> all = outlawsOf(town);
        if (all.isEmpty()) return "No outlaws, no posters. Good.";
        Villages.Village v = Villages.get(town);
        // Written up as the poster's recipe has it: a sheet of the stores' paper, ink, and red for the WANTED across its head.
        java.util.function.Predicate<ItemStack> paper = s -> s.is(net.minecraft.world.item.Items.PAPER),
            ink = s -> s.is(net.minecraft.world.item.Items.INK_SAC) || s.is(net.minecraft.world.item.Items.BLACK_DYE),
            red = s -> s.is(net.minecraft.world.item.Items.RED_DYE);
        if (v == null || Market.stock(level, town, paper) < 1 || Market.stock(level, town, ink) < 1 || Market.stock(level, town, red) < 1) {
            return "No paper, ink and red dye in the stores to write you one out. Read the board.";
        }
        Crafts.takeOne(level, v, paper);
        Crafts.takeOne(level, v, ink);
        Crafts.takeOne(level, v, red);
        Map.Entry<UUID, CompoundTag> top = all.entrySet().iterator().next();
        ItemStack poster = posterFor(town, top.getKey(), top.getValue());
        if (!p.getInventory().add(poster)) p.drop(poster, false);
        return "Here: " + top.getValue().getString("name") + ", wanted by " + Villages.name(town) + ". Bring them down and claim the bounty of any guard.";
    }

    /** A Wanted Poster written up for this outlaw of this town. */
    public static ItemStack posterFor(UUID town, UUID player, CompoundTag r) {
        ItemStack s = new ItemStack(com.jrpetty.mcassistant.item.AfterWarItems.WANTED_POSTER.get());
        CompoundTag t = new CompoundTag();
        t.putString("mca_wanted_town", town.toString());
        t.putString("mca_wanted_townName", Villages.name(town));
        t.putString("mca_wanted_player", player.toString());
        t.putString("mca_wanted_name", r.getString("name"));
        t.putString("mca_wanted_why", r.getString("why"));
        t.putInt("mca_wanted_bounty", r.getInt("bounty"));
        t.putLong("mca_wanted_since", r.getLong("since"));
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Wanted: " + r.getString("name")).withStyle(ChatFormatting.RED));
        return s;
    }

    /** [WantedPosterItem] A blank poster used in a town: written up from its board with its most wanted (or false, none). */
    public static boolean fillPoster(ServerLevel level, ServerPlayer p, ItemStack blank) {
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) {
            p.displayClientMessage(Component.literal("Copy a poster at a town's board.").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        Map<UUID, CompoundTag> all = outlawsOf(v.id());
        for (UUID ally : WarAndPeace.alliesOf(v.id())) if (all.isEmpty()) all = outlawsOf(ally);
        if (all.isEmpty()) {
            p.displayClientMessage(Component.literal(Villages.name(v.id()) + " has no outlaws posted.").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        Map.Entry<UUID, CompoundTag> top = all.entrySet().iterator().next();
        UUID town = isOutlaw(v.id(), top.getKey()) ? v.id() : outlawedBy(top.getKey()).isEmpty() ? v.id() : outlawedBy(top.getKey()).get(0);
        ItemStack filled = posterFor(town, top.getKey(), top.getValue());
        blank.shrink(1);
        if (!p.getInventory().add(filled)) p.drop(filled, false);
        p.displayClientMessage(Component.literal("You copy the poster of " + top.getValue().getString("name") + " off the board.").withStyle(ChatFormatting.GOLD), true);
        return true;
    }

    // ------------------------------------------------------------------ a town gone

    /** [Ruins.cleanUp] Its records of players closed: the posters come down, with nobody to pay out. */
    static void townGone(UUID dead) {
        for (Hunt h : new ArrayList<>(HUNTS.values())) if (h.town.equals(dead)) end(h, "the town is no more");
        AfterWarBooks.outlaws().remove(dead.toString());
        AfterWarBooks.changed();
    }

    // ------------------------------------------------------------------ seen: the board, the books, the talk card

    /** The board's poster: our outlaws, and our allies'. */
    static List<String> board(UUID id) {
        List<String> out = new ArrayList<>();
        for (CompoundTag r : outlawsOf(id).values()) {
            out.add("RB|WANTED, OUTLAW: " + r.getString("name") + " — " + r.getString("why") + (r.getInt("bounty") > 0 ? "; " + r.getInt("bounty") + " coins bounty" : "") + ".");
        }
        for (UUID ally : WarAndPeace.alliesOf(id)) {
            for (CompoundTag r : outlawsOf(ally).values()) {
                if (out.size() >= 4) break;
                out.add("RW|Wanted in " + Villages.name(ally) + ": " + r.getString("name") + (r.getInt("bounty") > 0 ? ", " + r.getInt("bounty") + " coins" : "") + ".");
            }
        }
        return out;
    }

    /** The Watch page: every player on the town's books, by how wanted. */
    static List<String> report(UUID id) {
        List<String> out = new ArrayList<>();
        CompoundTag book = AfterWarBooks.outlaws().getCompound(id.toString());
        List<CompoundTag> rs = new ArrayList<>();
        for (String k : book.getAllKeys()) rs.add(book.getCompound(k));
        rs.sort(java.util.Comparator.comparingInt((CompoundTag r) -> -r.getInt("wanted")));
        for (CompoundTag r : rs) {
            if (r.getInt("wanted") <= 0 && !r.getBoolean("outlaw") && r.getInt("caught") == 0) continue;
            out.add(r.getString("name") + ": " + (r.getBoolean("outlaw") ? "OUTLAW since day " + r.getLong("since") + ", bounty " + r.getInt("bounty") + " coins; "
                : "") + "wanted " + r.getInt("wanted") + "/100 (" + r.getInt("crimes") + (r.getInt("crimes") == 1 ? " crime" : " crimes") + ", last: " + r.getString("why") + ")"
                + (r.getInt("caught") > 0 ? "; caught " + r.getInt("caught") + "x" : "") + (!r.getBoolean("outlaw") && !r.getString("settledHow").isEmpty() ? "; " + r.getString("settledHow") : ""));
        }
        return out;
    }

    /** The talk card's word on the player here: ", WANTED (OUTLAW)" or ", wanted 30/100", or "". */
    static String talkWord(@Nullable UUID town, UUID player) {
        if (town == null) return "";
        if (isOutlaw(town, player)) return ", OUTLAW (bounty " + bounty(town, player) + ")";
        int w = wanted(town, player);
        return w >= 10 ? ", wanted " + w + "/100" : "";
    }

    /** What folk say of the outlaws (gossip). */
    static List<String> gossip(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        UUID town = f.ownerId();
        if (town == null) return out;
        for (CompoundTag r : outlawsOf(town).values()) {
            out.add(FolkTalk.pick(f.getRandom(), "Did you see the poster? " + r.getString("name") + " — an outlaw! For " + r.getString("why") + ".",
                "There's " + r.getInt("bounty") + " coins on " + r.getString("name") + "'s head. I'd not go after them myself."));
            if (out.size() >= 1) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    public static void creativeForTests(boolean on) {
        creativeToo = on;
    }

    public static CompoundTag recordForTests(UUID town, UUID player) {
        CompoundTag r = record(town, player, false);
        return r == null ? new CompoundTag() : r.copy();
    }

    public static void wantedForTests(UUID town, UUID player, int wanted) {
        record(town, player, true).putInt("wanted", wanted);
    }

    public static boolean huntForTests(ServerLevel level, Villages.Village v, ServerPlayer p) {
        return ride(level, v, p, level.getGameTime());
    }

    public static boolean huntedForTests(UUID town, UUID player) {
        return HUNTS.containsKey(town + "/" + player);
    }

    public static List<UUID> huntersForTests(UUID town, UUID player) {
        Hunt h = HUNTS.get(town + "/" + player);
        return h == null ? List.of() : new ArrayList<>(h.hunters);
    }

    public static boolean blowForTests(ServerPlayer p, VillageFolkEntity by, float amount) {
        return blow(p, by, amount);
    }

    public static void easeForTests(ServerLevel level, long day) {
        eases(level, day);
    }

    public static String talkForTests(VillageFolkEntity f, ServerPlayer p, String text) {
        String s = talk(f, p, TalkTopic.SAY, text);
        return s == null ? "" : s;
    }

    public static void diedForTests(ServerPlayer dead, net.minecraft.world.entity.Entity killer) {
        died(dead, killer);
    }

    public static void loggedOffForTests(UUID player) {
        loggedOff(player);
    }
}
