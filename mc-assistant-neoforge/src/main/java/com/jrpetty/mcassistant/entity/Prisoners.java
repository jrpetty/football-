package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war-after] Prisoners of war: the beaten taken off the field of battle, and what becomes of them.
 *
 * <p><b>Taken.</b> The winner leads off the loser's wounded left on the field, and, when the loser broke and ran, those of
 * the routed it had surrounded (Battles: eight at most). Each is held as a captive is (Spies keeps the roll), and this
 * class keeps the rest of it in the captor's books (Ledger note "pow/&lt;folk&gt;"): its rank, what it is worth in ransom
 * (a Captain forty coins, the standard-bearer twenty, a sergeant sixteen, one of the line eight, a townsman five), where
 * it was taken, and its kin at home, who grieve for it.
 *
 * <p><b>Held.</b> In a cell of the captor's watch house when one is free (the door shut on it, its bed at night); else in
 * the stockade, the pen the town puts up of fences and a lean-to when it holds more prisoners than its cells will take
 * (stockade.txt); else, with neither, in the yard by the barracks or the hall, on its word. A prisoner far away where
 * nobody sees (its army's column home) is set down at its place.
 *
 * <p><b>Its day.</b> At noon its dinner out of the captor's stores (bread, else whatever food there is: a real cost to the
 * town, kept on its books). By day, one held in the stockade or the yard, under the eye of the watch, does a stint of light
 * work in the town's fields: the ripe crops cut and sown again, what they give into the captor's stores. At night, now and
 * then, a break for it: one in three hundred from a cell of iron, one in forty from one of timber, one in thirty from the
 * stockade, one in twelve from the yard, twice as likely hungry; a guard within eight blocks catches it at it.
 *
 * <p><b>The peace.</b> The treaty exchanges them head for head, the most valued first; the side holding more asks a ransom
 * for the rest from their town, carried by the envoy who made the peace as far as its purse runs, and the rest are held for
 * ransom: their town sends an envoy with the coin when it has it (Envoys, "with a gift": the ransom). Released, a prisoner
 * walks home. One never ransomed, held twelve days past the peace (or twenty-four in all, or its town gone to ruin), is
 * offered a place in the town that holds it, and most take it: a chronicle line at both ends.
 *
 * <p><b>Players.</b> A player can talk to a prisoner, buy its release from the watch (half as much again as its ransom,
 * into the captor's treasury: its own town thinks well of you for it), or break it out (a crime in the captor town, and
 * one that makes outlaws: Outlaws).
 */
public final class Prisoners {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Prisoners() {}

    static final String NOTE = "pow/", RANSOM = "pow.ransom/", HOME = "pow.home/";
    /** [war-after] The stockade: a pen of fences with a lean-to over two beds, for prisoners the cells will not take. */
    public static final String STOCKADE = "stockade";
    /** Held this long past the peace with nobody paying, a prisoner is offered a place; or this long in all. */
    static final int CITIZEN_DAYS = 12, HELD_LONG = 24;
    /** What a player pays the watch for a prisoner's release: its ransom, and half as much again. */
    static final int BUY_PERCENT = 150;
    /** The stockade holds this many. */
    static final int PEN_MOST = 8;
    /** A prisoner's stint of work: this many crops, or this long. */
    static final int STINT_CROPS = 6;
    static final long STINT_TICKS = 2400L;

    /** One prisoner of war, as its captor's books keep it. */
    public static final class Pow {
        final UUID who, holder;
        UUID home;
        String name;
        final long day;
        String rank;
        int worth;
        String where;
        /** The day the peace was made with it still held (held for ransom since), -1 while the war goes on. */
        long peace = -1;
        /** Fed, worked, tried for it, and offered a place: the day of each; its claim on a cell (-1 none). */
        long fed = -1, worked = -1, tried = -1, offered = -1;
        int cell = -1, hungry, crops;
        /** Its kin at home (ids), who grieve for it. */
        String kin = "";

        Pow(UUID who, UUID holder, UUID home, String name, long day, String rank, int worth, String where) {
            this.who = who;
            this.holder = holder;
            this.home = home;
            this.name = name;
            this.day = day;
            this.rank = rank;
            this.worth = worth;
            this.where = where;
        }

        public UUID who() { return who; }
        public UUID holder() { return holder; }
        public UUID home() { return home; }
        public String name() { return name; }
        public long day() { return day; }
        public String rank() { return rank; }
        public int worth() { return worth; }
        public long peace() { return peace; }
        public boolean forRansom() { return peace >= 0; }

        String encode() {
            return "home=" + home + ";name=" + name.replace(";", ",").replace("=", "-") + ";day=" + day + ";rank=" + rank + ";worth=" + worth
                + ";where=" + where.replace(";", ",").replace("=", "-") + ";peace=" + peace + ";fed=" + fed + ";worked=" + worked + ";tried=" + tried
                + ";offered=" + offered + ";cell=" + cell + ";hungry=" + hungry + ";crops=" + crops + ";kin=" + kin;
        }

        @Nullable
        static Pow decode(UUID who, UUID holder, String s) {
            Map<String, String> m = new java.util.HashMap<>();
            for (String part : s.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) m.put(part.substring(0, eq), part.substring(eq + 1));
            }
            try {
                Pow p = new Pow(who, holder, UUID.fromString(m.get("home")), m.getOrDefault("name", "?"), Long.parseLong(m.getOrDefault("day", "0")),
                    m.getOrDefault("rank", "NONE"), Integer.parseInt(m.getOrDefault("worth", "5")), m.getOrDefault("where", "the war"));
                p.peace = Long.parseLong(m.getOrDefault("peace", "-1"));
                p.fed = Long.parseLong(m.getOrDefault("fed", "-1"));
                p.worked = Long.parseLong(m.getOrDefault("worked", "-1"));
                p.tried = Long.parseLong(m.getOrDefault("tried", "-1"));
                p.offered = Long.parseLong(m.getOrDefault("offered", "-1"));
                p.cell = Integer.parseInt(m.getOrDefault("cell", "-1"));
                p.hungry = Integer.parseInt(m.getOrDefault("hungry", "0"));
                p.crops = Integer.parseInt(m.getOrDefault("crops", "0"));
                p.kin = m.getOrDefault("kin", "");
                return p;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** By prisoner. */
    private static final Map<UUID, Pow> POWS = new ConcurrentHashMap<>();
    /** A prisoner out at its stint of work: the crop it is going to, and when the stint began. */
    private static final Map<UUID, long[]> STINT = new ConcurrentHashMap<>();
    private static volatile int scanned = -1;

    static void resetForTests() {
        POWS.clear();
        STINT.clear();
        scanned = -1;
    }

    /** The prisoners as the world's books keep them: read again whenever the towns known have changed in number. */
    static void load() {
        List<Villages.Village> all = Villages.every();
        if (all.size() == scanned) return;
        scanned = all.size();
        for (Villages.Village v : all) {
            for (Map.Entry<String, String> n : Ledger.notes(v.id()).entrySet()) {
                if (!n.getKey().startsWith(NOTE) || n.getValue() == null || n.getValue().isEmpty()) continue;
                try {
                    UUID who = UUID.fromString(n.getKey().substring(NOTE.length()));
                    Pow p = Pow.decode(who, v.id(), n.getValue());
                    if (p != null) POWS.putIfAbsent(who, p);
                } catch (IllegalArgumentException e) {
                    LOG.debug("[MCA-POW] a prisoner's note would not read: {}", n.getValue());
                }
            }
        }
    }

    static void save(Pow p) {
        Ledger.note(p.holder, NOTE + p.who, p.encode());
    }

    // ------------------------------------------------------------------ who is held

    @Nullable
    public static Pow of(@Nullable UUID who) {
        if (who == null) return null;
        load();
        return POWS.get(who);
    }

    public static boolean isPow(@Nullable UUID who) {
        return of(who) != null;
    }

    /** Held for ransom after the peace (not to be let go at the peace with the spies). */
    static boolean heldForRansom(UUID who) {
        Pow p = of(who);
        return p != null;
    }

    /** The prisoners this town holds, the most valued first. */
    public static List<Pow> heldBy(UUID holder) {
        load();
        List<Pow> out = new ArrayList<>();
        for (Pow p : POWS.values()) if (p.holder.equals(holder)) out.add(p);
        out.sort(Comparator.comparingInt((Pow p) -> -p.worth).thenComparingLong(p -> p.day));
        return out;
    }

    /** This town's folk held prisoner elsewhere. */
    public static List<Pow> ofTown(UUID home) {
        load();
        List<Pow> out = new ArrayList<>();
        for (Pow p : POWS.values()) if (p.home.equals(home)) out.add(p);
        out.sort(Comparator.comparingInt((Pow p) -> -p.worth));
        return out;
    }

    // ------------------------------------------------------------------ taken

    /** What a prisoner of this rank is worth in ransom. */
    static int worth(String rank, boolean fighter) {
        return switch (rank) {
            case "CAPTAIN" -> 40;
            case "BEARER" -> 20;
            case "SERGEANT" -> 16;
            default -> fighter ? 8 : 5;
        };
    }

    static String rankWords(String rank) {
        return switch (rank) {
            case "CAPTAIN" -> "its Captain";
            case "BEARER" -> "its standard-bearer";
            case "SERGEANT" -> "a sergeant";
            case "FIGHTER" -> "one of the line";
            default -> "a townsman";
        };
    }

    /** [Battles.capture] One taken on the field: its rank and worth, the field's name, its kin at home grieving. */
    static void takenInBattle(ServerLevel level, Armies.Fighter f, UUID holder, @Nullable String battle, long day) {
        String rank = f.rank == Armies.Rank.NONE ? "FIGHTER" : f.rank.name();
        taken(level, f.id, f.name, f.town, holder, rank, worth(rank, true), battle == null || battle.isEmpty() ? "the field" : battle, day);
    }

    /** On the books as a prisoner of war, and told at both ends. */
    static Pow taken(ServerLevel level, UUID who, String name, UUID home, UUID holder, String rank, int worth, String where, long day) {
        load();
        Pow p = new Pow(who, holder, home, name, day, rank, worth, where);
        VillageFolkEntity f = Civics.find(level, who);
        List<String> kin = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(home)) {
            if (!(a instanceof VillageFolkEntity k) || k.getUUID().equals(who) || !k.life().rolled()) continue;
            boolean close = who.equals(k.life().partner()) || who.equals(k.life().bestFriend()) || k.life().parents().contains(name)
                || f != null && f.life().parents().contains(k.displayNameCap());
            if (!close) continue;
            kin.add(k.getUUID().toString());
            k.persona().remember(day, name + " was taken prisoner by " + Villages.name(holder) + " at " + where, 8);
        }
        p.kin = String.join(",", kin);
        POWS.put(who, p);
        save(p);
        if (f != null) {
            f.persona().remember(day, "I was taken prisoner by " + Villages.name(holder) + " at " + where, 9);
            f.clearQueue();
        }
        String us = Villages.name(holder), them = Villages.name(home);
        Villages.tell(home, day, name + " (" + rankWords(rank) + ") was taken prisoner by " + us + " at " + where);
        WarBooks.course(holder, home, day, name + " of " + them + " taken prisoner at " + where + ", worth " + worth + " coins in ransom");
        LOG.info("[MCA-POW] {} of {} ({}) taken prisoner by {} at {}", name, them, rank, us, where);
        return p;
    }

    /**
     * [Battles.takeCaptives] A rout: those of the routed the winner has surrounded (a standing fighter of its within six
     * blocks, none of their own) taken too, up to the field's most. Returns how many.
     */
    static int caughtInRout(ServerLevel level, Armies.Army w, Armies.Army l, int n, int most, long day) {
        int took = 0;
        for (Armies.Fighter f : l.fighters) {
            if (n + took >= most) break;
            if (f.dead || f.order != Armies.Order.ROUT || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
            Armies.Fighter by = null;
            int friends = 0;
            for (Armies.Fighter o : w.fighters) {
                if (o.down() || !(level.getEntity(o.id) instanceof VillageFolkEntity oe)) continue;
                if (oe.distanceToSqr(e) < 36) { by = o; break; }
            }
            for (Armies.Fighter o : l.fighters) {
                if (o != f && !o.down() && level.getEntity(o.id) instanceof VillageFolkEntity oe && oe.distanceToSqr(e) < 36) friends++;
            }
            if (by == null || friends > 0) continue;
            Battles.capture(level, f, by, w, day);
            took++;
        }
        return took;
    }

    /**
     * [admin] Prisoners taken now: so many grown folk of that town (its Captain first, then its guards), set down
     * where this town holds its prisoners. Returns how many.
     */
    public static int takeNow(ServerLevel level, Villages.Village captor, Villages.Village from, int n) {
        long day = level.getDayTime() / 24000L;
        List<VillageFolkEntity> pick = new ArrayList<>();
        UUID captain = Police.captainId(from.id()), elder = Villages.elder(from.id());
        for (AssistantEntity a : Villages.folkOf(from.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.getUUID().equals(elder) || Spies.held(f) || f.isShowcase()) continue;
            pick.add(f);
        }
        pick.sort(Comparator.comparingInt((VillageFolkEntity f) -> f.getUUID().equals(captain) ? 0 : f.stationTask() == AssistantEntity.StationTask.GUARD ? 1 : 2));
        int took = 0;
        BlockPos at = Spies.holdAt(captor);
        for (VillageFolkEntity f : pick) {
            if (took >= n) break;
            String rank = f.getUUID().equals(captain) ? "CAPTAIN" : f.stationTask() == AssistantEntity.StationTask.GUARD ? "FIGHTER" : "NONE";
            Spies.takeInBattle(captor.id(), f.getUUID(), f.displayNameCap(), from.id(), day);
            taken(level, f.getUUID(), f.displayNameCap(), from.id(), captor.id(), rank, worth(rank, !rank.equals("NONE")), "a raid by decree", day);
            if (f.isPassenger()) f.stopRiding();
            f.getNavigation().stop();
            f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, f.getYRot(), 0.0F);
            took++;
        }
        if (took > 0) Villages.tell(captor.id(), day, took + " of " + Villages.name(from.id()) + "'s folk were brought in as prisoners");
        return took;
    }

    // ------------------------------------------------------------------ where it is held

    /** Where a prisoner is kept: a cell (its index), the stockade, or the yard; and the spot it keeps to. */
    record Place(String kind, BlockPos stand, @Nullable WatchHouse.Cell cell, @Nullable Ledger.Building pen) {
        String words(UUID holder) {
            String town = Villages.name(holder);
            return switch (kind) {
                case "CELL" -> "in the cells at " + town + "'s watch house";
                case "PEN" -> "in " + town + "'s stockade";
                default -> "in the yard at " + town + ", on its word";
            };
        }
    }

    /** Is this cell held for a prisoner of war (WatchHouse.freeCell passes it over)? */
    static boolean cellTaken(UUID village, int index) {
        load();
        for (Pow p : POWS.values()) if (p.holder.equals(village) && p.cell == index) return true;
        return false;
    }

    /** The stockade, standing (not still going up), or null. */
    @Nullable
    static Ledger.Building pen(UUID village) {
        Ledger.Building b = Villages.builtStructure(village, STOCKADE);
        return b == null || Ledger.raising(village, b.anchor()) ? null : b;
    }

    static BlockPos penSpot(Ledger.Building b, int across, int back) {
        return WatchHouse.at(b, across, 0, back);
    }

    /** Inside the stockade's fence (the drawing's own nine by nine). */
    static boolean inPen(Ledger.Building b, BlockPos at) {
        BlockPos c = b.anchor();
        return Math.abs(at.getX() - c.getX()) <= 3 && Math.abs(at.getZ() - c.getZ()) <= 3 && Math.abs(at.getY() - c.getY()) <= 2;
    }

    static Place placeFor(ServerLevel level, Villages.Village v, Pow p) {
        Ledger.Building house = WatchHouse.of(v.id());
        if (house != null) {
            WatchHouse.Cell mine = p.cell >= 0 ? WatchHouse.cell(house, p.cell) : null;
            if (mine != null && !WatchHouse.occupied(v.id(), mine.index()) && !PlayerLaw.cellTaken(v.id(), mine.index())) {
                return new Place("CELL", mine.inside(), mine, null);
            }
            // A cell is the Captain's first: the others take what is left.
            for (WatchHouse.Cell c : WatchHouse.cells(house)) {
                if (WatchHouse.occupied(v.id(), c.index()) || PlayerLaw.cellTaken(v.id(), c.index()) || cellTaken(v.id(), c.index())) continue;
                p.cell = c.index();
                save(p);
                return new Place("CELL", c.inside(), c, null);
            }
        }
        if (p.cell >= 0) {
            p.cell = -1;
            save(p);
        }
        Ledger.Building pen = pen(v.id());
        if (pen != null) {
            int n = 0;
            for (Pow o : heldBy(v.id())) if (o.cell < 0 && o != p && o.day <= p.day) n++;
            if (n < PEN_MOST) {
                int k = Math.floorMod(p.who.hashCode(), 9);
                return new Place("PEN", penSpot(pen, (k % 3) - 1, -1 - k / 3), null, pen);
            }
        }
        return new Place("YARD", Spies.holdAt(v), null, null);
    }

    // ------------------------------------------------------------------ its own step (Spies.hold)

    /** [Spies.hold] A prisoner of war, kept where its captor holds it: true while it is held. */
    static boolean hold(VillageFolkEntity f, ServerLevel level, boolean think) {
        Pow p = of(f.getUUID());
        if (p == null) return false;
        if (!think) return true;
        Villages.Village v = Villages.get(p.holder);
        if (v == null) return true;                                 // (its keepers' town not known just now: it waits)
        if (f.isPassenger()) f.stopRiding();
        if (f.isLeashed()) f.dropLeash(true, false);                // home with its captors: off the lead and to its place
        long[] stint = STINT.get(f.getUUID());
        if (stint != null && workStep(level, v, f, p, stint)) return true;
        Place at = placeFor(level, v, p);
        f.hobbyNow = "a prisoner of war, held " + at.words(p.holder);
        double d = f.distanceToSqr(at.stand().getX() + 0.5, at.stand().getY(), at.stand().getZ() + 0.5);
        // Far off where nobody sees it (the column home, a restart): set down in its place.
        if (d > 64 * 64 && !seen(level, f.blockPosition()) && !seen(level, at.stand())) {
            f.getNavigation().stop();
            f.moveTo(at.stand().getX() + 0.5, at.stand().getY(), at.stand().getZ() + 0.5, f.getYRot(), 0.0F);
            return true;
        }
        long tod = Math.floorMod(level.getDayTime(), 24000L);
        switch (at.kind()) {
            case "CELL" -> {
                WatchHouse.Cell c = at.cell();
                if (d > 2.4 * 2.4) {
                    if (d < 7 * 7 || !seen(level, f.blockPosition())) {
                        f.getNavigation().stop();
                        f.moveTo(c.inside().getX() + 0.5, c.inside().getY(), c.inside().getZ() + 0.5, f.getYRot(), 0.0F);
                        WatchHouse.setDoor(level, c.door(), false);
                        level.playSound(null, c.door(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 0.8F);
                    } else if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(c.front(), 0.8D);
                    return true;
                }
                f.getNavigation().stop();
                if (night(tod) && !f.isSleeping() && level.getBlockState(c.bedHead()).getBlock() instanceof BedBlock) f.startSleeping(c.bedHead());
                else if (!night(tod) && f.isSleeping()) f.stopSleeping();
            }
            case "PEN" -> {
                Ledger.Building pen = at.pen();
                if (!inPen(pen, f.blockPosition())) {
                    BlockPos gate = penSpot(pen, 0, -5);
                    if (f.blockPosition().distSqr(gate) < 6 * 6 || !seen(level, f.blockPosition())) {
                        f.getNavigation().stop();
                        f.moveTo(at.stand().getX() + 0.5, at.stand().getY(), at.stand().getZ() + 0.5, f.getYRot(), 0.0F);
                    } else if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(gate, 0.8D);
                    return true;
                }
                if (night(tod)) {
                    for (int across : new int[]{ -1, 1 }) {
                        BlockPos head = WatchHouse.at(pen, across, 0, 3);
                        if (!f.isSleeping() && level.getBlockState(head).getBlock() instanceof BedBlock && !level.getBlockState(head).getValue(BedBlock.OCCUPIED)) {
                            f.startSleeping(head);
                            break;
                        }
                    }
                } else if (f.isSleeping()) f.stopSleeping();
                if (d > 4 && (f.getNavigation().isDone() || f.tickCount % 60 == 0) && !f.isSleeping()) f.walkTo(at.stand(), 0.6D);
            }
            default -> {
                if (d > 9) {
                    if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(at.stand(), 0.9D);
                } else f.getNavigation().stop();
            }
        }
        return true;
    }

    static boolean night(long tod) {
        return tod >= 13000 && tod < 23500;
    }

    /** Is a player near enough to see it happen? */
    static boolean seen(ServerLevel level, BlockPos at) {
        for (ServerPlayer pl : level.players()) if (!pl.isSpectator() && pl.blockPosition().distSqr(at) < 48 * 48) return true;
        return false;
    }

    // ------------------------------------------------------------------ the prisoners' day (AfterWar, every second)

    /** The captor's round: dinner at noon, a stint of work by day, a break for it at night, a place offered in the morning. */
    static void round(ServerLevel level, Villages.Village v, Random rng) {
        List<Pow> held = heldBy(v.id());
        if (held.isEmpty()) return;
        long now = level.getDayTime(), day = now / 24000L, tod = Math.floorMod(now, 24000L);
        for (Pow p : held) {
            VillageFolkEntity f = Civics.find(level, p.who);
            if (Villages.get(p.home) == null) {                   // its town is no more: a place is all there is to offer
                if (p.offered != day) offer(level, v, p, f, day, true);
                continue;
            }
            // At peace with its town with no treaty to say what becomes of it (the war ended some other way): held for ransom.
            if (p.peace < 0 && !Wars.atWar(p.holder, p.home)) {
                p.peace = day;
                save(p);
                Villages.tell(p.home, day, Villages.name(p.holder) + " holds " + p.name + " for ransom now there is peace: " + p.worth + " coins");
            }
            if (f == null) continue;
            if (tod >= 6000 && tod < 7000 && p.fed != day) feed(level, v, p, f, day);
            if (tod >= 2500 && tod < 9000 && p.worked != day && !STINT.containsKey(p.who)) startWork(level, v, p, f, day);
            if (tod >= 17000 && tod < 22000 && p.tried != day) tryEscape(level, v, p, f, day, rng);
            if (tod >= 1000 && tod < 2500 && p.offered != day && (p.forRansom() && day - p.peace >= CITIZEN_DAYS || day - p.day >= HELD_LONG)) {
                offer(level, v, p, f, day, false);
            }
        }
    }

    /** Dinner, at the captor's cost: bread, else whatever food the stores have. None to be had, it goes hungry. */
    static void feed(ServerLevel level, Villages.Village v, Pow p, VillageFolkEntity f, long day) {
        p.fed = day;
        ItemStack food = Crafts.takeOne(level, v, s -> s.is(Items.BREAD));
        if (food.isEmpty()) food = Crafts.takeOne(level, v, s -> s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE)
            && !s.is(Items.POISONOUS_POTATO));
        if (food.isEmpty()) {
            p.hungry++;
            save(p);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nothing again today? You'll starve us.", "Is this how " + Villages.name(p.holder) + " keeps its prisoners? Hungry?"));
            return;
        }
        p.hungry = 0;
        save(p);
        String what = Crafts.named(food.copy());
        ItemStack left = f.insertItem(food);
        if (!left.isEmpty()) Crafts.store(level, v, left);
        Ledger.note(v.id(), "pow.fed", Integer.toString(num(Ledger.note(v.id(), "pow.fed")) + 1));
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, f.blockPosition(), 16.0, null);
        if (g != null) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Your dinner, " + f.displayNameCap() + ". " + Villages.name(v.id()) + " feeds its prisoners.",
            "Here. Eat up."));
        Police.log(v.id(), level.getDayTime(), "fed", f.displayNameCap() + ", a prisoner of war, was given " + what + " out of the stores", g, 0);
    }

    /** A stint of light work in the town's fields: one held in the stockade or the yard, with the watch about. */
    static void startWork(ServerLevel level, Villages.Village v, Pow p, VillageFolkEntity f, long day) {
        if (p.rank.equals("CAPTAIN")) return;                              // a Captain is not set to cutting corn
        Place at = placeFor(level, v, p);
        if (at.kind().equals("CELL") || Patrols.watch(v.id()).isEmpty()) return;
        BlockPos crop = ripeCrop(level, v, f.blockPosition());
        p.worked = day;
        save(p);
        if (crop == null) return;
        STINT.put(p.who, new long[]{ crop.asLong(), level.getGameTime(), 0 });
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Out to the fields, is it? Better than sitting.", "I'll cut your corn. Not that I've a choice."));
    }

    /** At its stint: to the crop, cut it and sow it again, the next; back when the stint is done. Whether it is at it. */
    static boolean workStep(ServerLevel level, Villages.Village v, VillageFolkEntity f, Pow p, long[] stint) {
        long gt = level.getGameTime();
        if (stint[2] >= STINT_CROPS || gt - stint[1] > STINT_TICKS || night(Math.floorMod(level.getDayTime(), 24000L))) {
            STINT.remove(p.who);
            if (stint[2] > 0) {
                p.crops += (int) stint[2];
                save(p);
                Ledger.note(v.id(), "pow.crops", Integer.toString(num(Ledger.note(v.id(), "pow.crops")) + (int) stint[2]));
                Police.log(v.id(), level.getDayTime(), "work", f.displayNameCap() + ", a prisoner of war, brought in " + stint[2] + " crops from the fields", null, 0);
            }
            return false;
        }
        BlockPos crop = BlockPos.of(stint[0]);
        f.hobbyNow = "a prisoner of war, at work in " + Villages.name(p.holder) + "'s fields";
        if (f.blockPosition().distSqr(crop) > 2.5 * 2.5) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(crop, 0.7D);
            return true;
        }
        f.getNavigation().stop();
        if (harvest(level, v, crop)) {
            stint[2]++;
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        BlockPos next = ripeCrop(level, v, crop);
        if (next == null) stint[1] = gt - STINT_TICKS - 1;           // nothing more ripe: the stint is over
        else stint[0] = next.asLong();
        return true;
    }

    /** A ripe crop of the town's fields, nearest this spot first (its farmers' plots), or null. */
    @Nullable
    static BlockPos ripeCrop(ServerLevel level, Villages.Village v, BlockPos near) {
        List<BlockPos> fields = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() == AssistantEntity.StationTask.FARM && a.workZone() != null) fields.add(a.workZone().center());
            if (fields.size() >= 6) break;
        }
        fields.add(near);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos c : fields) {
            if (c.distSqr(v.centre()) > (double) Villages.townReach(v.id()) * Villages.townReach(v.id()) * 2) continue;
            for (int dx = -7; dx <= 7; dx++) {
                for (int dz = -7; dz <= 7; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = c.offset(dx, dy, dz);
                        if (!level.isLoaded(p)) continue;
                        BlockState st = level.getBlockState(p);
                        if (!(st.getBlock() instanceof CropBlock cb) || !cb.isMaxAge(st)) continue;
                        double d = p.distSqr(near);
                        if (d < bd) { bd = d; best = p.immutable(); }
                    }
                }
            }
            if (best != null) return best;
        }
        return best;
    }

    /** One ripe crop cut, sown again from its own seed, and the rest of what it gave into the stores. */
    static boolean harvest(ServerLevel level, Villages.Village v, BlockPos at) {
        BlockState st = level.getBlockState(at);
        if (!(st.getBlock() instanceof CropBlock cb) || !cb.isMaxAge(st)) return false;
        List<ItemStack> drops = Block.getDrops(st, level, at, null);
        Item seed = st.getBlock().asItem();
        boolean sown = false;
        for (ItemStack d : drops) {
            if (!sown && d.is(seed)) {
                d.shrink(1);
                sown = true;
            }
        }
        level.setBlock(at, sown ? cb.getStateForAge(0) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.playSound(null, at, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
        for (ItemStack d : drops) if (!d.isEmpty()) Crafts.store(level, v, d);
        return true;
    }

    /** A break for it, once a night at most: the odds by where it is held, and hunger; a guard close by catches it at it. */
    static void tryEscape(ServerLevel level, Villages.Village v, Pow p, VillageFolkEntity f, long day, Random rng) {
        p.tried = day;
        save(p);
        Place at = placeFor(level, v, p);
        int odds = switch (at.kind()) {
            case "CELL" -> WatchHouse.ironClad(level, at.cell()) ? 300 : 40;
            case "PEN" -> 30;
            default -> 12;
        };
        if (p.hungry > 0) odds = Math.max(4, odds / 2);
        if (rng.nextInt(odds) != 0) return;
        escape(level, v, p, f, at, day);
    }

    /** Out and away: the door or the gate forced, a run for home; a guard close by catches it at it. */
    static boolean escape(ServerLevel level, Villages.Village v, Pow p, VillageFolkEntity f, Place at, long day) {
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, f.blockPosition(), 8.0, null);
        if (at.cell() != null) {
            boolean iron = level.getBlockState(at.cell().door()).is(net.minecraft.world.level.block.Blocks.IRON_DOOR);
            level.playSound(null, at.cell().door(), iron ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.6F);
        } else {
            level.playSound(null, f.blockPosition(), SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 0.7F);
        }
        if (g != null) {
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Oh no you don't. Back you go.", "Going somewhere, " + f.displayNameCap() + "?"));
            FolkTalk.speak(f, "Worth a try.");
            Villages.tell(v.id(), day, f.displayNameCap() + ", a prisoner of " + Villages.name(p.home) + ", tried to slip away in the night; " + g.displayNameCap() + " caught it at it");
            Police.log(v.id(), level.getDayTime(), "break", f.displayNameCap() + ", a prisoner of war, tried to escape and was caught", g, 0);
            return false;
        }
        free(level, p.who, "escaped", f.displayNameCap() + ", a prisoner of " + Villages.name(p.home) + ", escaped in the night; the watch is after it",
            f.displayNameCap() + " escaped from " + Villages.name(p.holder) + " and is on the way home", true);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Now's my chance...", "Home. I'm going home."));
        Police.log(v.id(), level.getDayTime(), "break", f.displayNameCap() + ", a prisoner of war, escaped", null, 0);
        Raids.tellNear(level, v.centre(), 96, Component.literal("A prisoner of war has escaped from " + Villages.name(v.id()) + ".").withStyle(ChatFormatting.GOLD), false);
        return true;
    }

    /**
     * A place offered: held long with nobody paying, or its town gone. Most take it (one whose town is gone always does),
     * and join the town that held them: a chronicle line at both ends.
     */
    static void offer(ServerLevel level, Villages.Village v, Pow p, @Nullable VillageFolkEntity f, long day, boolean homeGone) {
        p.offered = day;
        save(p);
        if (f == null) return;
        boolean takes = homeGone || f.getRandom().nextInt(3) != 0;
        if (!takes) {
            f.persona().remember(day, Villages.name(v.id()) + " offered me a place, and I said no: I'll wait for " + Villages.name(p.home), 5);
            FolkTalk.speak(f, "Join you? No. " + Villages.name(p.home) + " will pay for me yet.");
            return;
        }
        UUID home = p.home;
        String was = Villages.name(home);
        drop(p.who);
        Villages.Village from = Villages.get(home);
        if (from != null) {
            Homes.left(home, f.getUUID());
            if (f.getUUID().equals(Villages.elder(home))) Villages.elderGone(home, f.getUUID());
            Villages.recordDeath(home);
            Annals.moved(home, v.id());
        }
        f.forgetBed();
        f.setStation(null, AssistantEntity.StationTask.NONE);
        f.joinVillage(v.id(), v.centre());
        Villages.recordBirth(v.id());
        f.persona().remember(day, homeGone ? was + " is no more; " + Villages.name(v.id()) + ", which held me, took me in"
            : "held in " + Villages.name(v.id()) + " since day " + p.day + " and never ransomed, I took a place there", 9);
        FolkTalk.speak(f, homeGone ? "There's nothing to go back to. I'll make my life here." : "Nobody came for me. All right: I'll be one of you.");
        Villages.tell(v.id(), day, f.displayNameCap() + " of " + was + ", held since day " + p.day + (homeGone ? ", whose town is no more" : " and never ransomed")
            + ", took the town's oath and is one of us now");
        if (from != null) {
            Villages.tell(home, day, f.displayNameCap() + ", held in " + Villages.name(v.id()) + " since day " + p.day + " and never ransomed, has made a new life there");
            WarBooks.course(home, v.id(), day, f.displayNameCap() + ", never ransomed, took a place in " + Villages.name(v.id()));
        }
        LOG.info("[MCA-POW] {} of {} took a place in {}", f.displayNameCap(), was, Villages.name(v.id()));
    }

    // ------------------------------------------------------------------ let go

    /** Off both books (Spies' roll, the captor's notes), its cell left. */
    @Nullable
    static Pow drop(UUID who) {
        load();
        Pow p = POWS.remove(who);
        STINT.remove(who);
        if (p != null) Ledger.forget(p.holder, NOTE + who);
        Spies.forget(who);
        return p;
    }

    /**
     * Let one go home: off the books, its kin told, on the road home (now if it is about, else as soon as it is: a note on
     * its town). Lines for the captor's chronicle and its own town's. Whether there was such a prisoner.
     */
    static boolean free(ServerLevel level, UUID who, String how, String captorLine, String homeLine, boolean walk) {
        Pow p = drop(who);
        if (p == null) return false;
        long day = level.getDayTime() / 24000L;
        Villages.Village back = Villages.get(p.home);
        VillageFolkEntity f = Civics.find(level, who);
        if (f != null && f.isSleeping()) f.stopSleeping();
        if (back != null) {
            if (f != null) sendHome(level, f, p, back);
            else Ledger.note(p.home, HOME + who, Long.toString(day));
            for (String k : p.kin.split(",")) {
                if (k.isEmpty()) continue;
                try {
                    VillageFolkEntity kin = Civics.find(level, UUID.fromString(k));
                    if (kin != null) kin.persona().remember(day, p.name + " is coming home from " + Villages.name(p.holder) + " (" + how + ")", 6);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (!captorLine.isEmpty()) Villages.tell(p.holder, day, captorLine);
        if (!homeLine.isEmpty() && back != null) Villages.tell(p.home, day, homeLine);
        LOG.info("[MCA-POW] {} of {} let go by {}: {}", p.name, Villages.name(p.home), Villages.name(p.holder), how);
        return true;
    }

    /** On the road home, as a captive let go walks. */
    static void sendHome(ServerLevel level, VillageFolkEntity f, Pow p, Villages.Village back) {
        Villages.Village h = Villages.get(p.holder);
        Spying.Mission m = new Spying.Mission(p.holder, Villages.name(p.holder), h != null ? h.centre() : f.blockPosition(), "its prison");
        m.freed = true;
        m.phase = Spying.Phase.HOME;
        f.getNavigation().stop();
        Scouts.walkHome(f, back, m);
    }

    /** Freed while it was not about (its ground asleep): on the road home the moment it is. */
    static void homeward(ServerLevel level, Villages.Village v) {
        for (Map.Entry<String, String> n : Ledger.notes(v.id()).entrySet()) {
            if (!n.getKey().startsWith(HOME)) continue;
            UUID who;
            try {
                who = UUID.fromString(n.getKey().substring(HOME.length()));
            } catch (IllegalArgumentException e) {
                Ledger.forget(v.id(), n.getKey());
                continue;
            }
            VillageFolkEntity f = Civics.find(level, who);
            if (f == null) continue;
            Ledger.forget(v.id(), n.getKey());
            if (f.blockPosition().distSqr(v.centre()) < 48 * 48 || f.expedition() != null) continue;
            Spying.Mission m = new Spying.Mission(v.id(), Villages.name(v.id()), f.blockPosition(), "its prison");
            m.freed = true;
            m.phase = Spying.Phase.HOME;
            Scouts.walkHome(f, v, m);
        }
    }

    /** [admin] Every prisoner this town holds let go home. How many. */
    public static int releaseAll(ServerLevel level, UUID holder, String why) {
        int n = 0;
        for (Pow p : heldBy(holder)) {
            if (free(level, p.who, why, p.name + " of " + Villages.name(p.home) + ", our prisoner, was let go home " + why,
                p.name + ", held in " + Villages.name(holder) + ", was let go " + why + " and is on the way home", true)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the peace

    /**
     * [WarAndPeace.makePeace] The prisoners each holds of the other's: exchanged head for head, the most valued first;
     * the rest ransomed by their town, out of the envoy's purse (its own side's) or into it (to carry home), as far as it
     * runs; whoever is left held for ransom. The treaty's words for it ("" if nobody was held).
     */
    public static String atPeace(ServerLevel level, UUID a, UUID d, @Nullable Caravans.Trip t, long day) {
        List<Pow> byA = new ArrayList<>(), byD = new ArrayList<>();
        for (Pow p : heldBy(a)) if (p.home.equals(d)) byA.add(p);
        for (Pow p : heldBy(d)) if (p.home.equals(a)) byD.add(p);
        if (byA.isEmpty() && byD.isEmpty()) return "";
        int swap = Math.min(byA.size(), byD.size());
        for (int i = 0; i < swap; i++) {
            Pow x = byA.get(i), y = byD.get(i);
            free(level, x.who, "exchanged", x.name + " of " + Villages.name(d) + " was exchanged for " + y.name + " at the peace", "", true);
            free(level, y.who, "exchanged", y.name + " of " + Villages.name(a) + " was exchanged for " + x.name + " at the peace", "", true);
            Villages.tell(d, day, x.name + " came home in an exchange of prisoners at the peace");
            Villages.tell(a, day, y.name + " came home in an exchange of prisoners at the peace");
        }
        List<Pow> rest = new ArrayList<>((byA.size() > swap ? byA : byD).subList(swap, Math.max(byA.size(), byD.size())));
        String words = swap > 0 ? swap * 2 + " prisoners exchanged" : "";
        if (rest.isEmpty()) return words;
        UUID holder = rest.get(0).holder, payer = rest.get(0).home;
        int budget = t == null ? 0 : t.from.equals(payer) ? t.purse : Math.max(0, Ledger.coins(payer) - 5);
        int asked = 0, paidFor = 0, owed = 0;
        List<Pow> ransomed = new ArrayList<>();
        for (Pow p : rest) {
            asked += p.worth;
            if (t != null && paidFor + p.worth <= budget) {
                paidFor += p.worth;
                ransomed.add(p);
            }
        }
        int paid = paidFor > 0 ? WarAndPeace.pay(payer, holder, paidFor, t) : 0;
        if (paid < paidFor) {                                  // (the purse ran short after all: as many as it covers)
            int k = 0;
            List<Pow> covered = new ArrayList<>();
            for (Pow p : ransomed) if (k + p.worth <= paid) { k += p.worth; covered.add(p); }
            ransomed = covered;
        }
        List<String> names = new ArrayList<>();
        for (Pow p : ransomed) {
            names.add(p.name);
            free(level, p.who, "ransomed", p.name + " of " + Villages.name(payer) + " was ransomed for " + p.worth + " coins at the peace",
                p.name + " was ransomed for " + p.worth + " coins at the peace and is on the way home", true);
        }
        for (Pow p : rest) {
            if (ransomed.contains(p)) continue;
            p.peace = day;
            save(p);
            owed += p.worth;
        }
        if (owed > 0) {
            Villages.tell(holder, day, "we hold " + (rest.size() - ransomed.size()) + " of " + Villages.name(payer) + "'s folk for ransom: " + owed + " coins asked");
            Villages.tell(payer, day, Villages.name(holder) + " holds " + (rest.size() - ransomed.size()) + " of ours for ransom, " + owed + " coins, till we can pay");
        }
        String r = (ransomed.isEmpty() ? "" : ransomed.size() + " ransomed for " + paid + " coins")
            + (owed > 0 ? (ransomed.isEmpty() ? "" : ", ") + (rest.size() - ransomed.size()) + " held for ransom (" + owed + " coins asked of " + Villages.name(payer) + ")" : "");
        LOG.info("[MCA-POW] peace between {} and {}: {} exchanged, {} ransomed for {}, {} held for {} ({} asked)", Villages.name(a), Villages.name(d),
            swap * 2, ransomed.size(), paid, rest.size() - ransomed.size(), owed, asked);
        return words.isEmpty() ? r : words + (r.isEmpty() ? "" : "; " + r);
    }

    /** [WarAndPeace.peacePurse] What a white flag carries for the ransom its side will owe: its folk held beyond theirs, if it can. */
    static int peacePurse(UUID from, UUID to) {
        List<Pow> ours = new ArrayList<>(), theirs = new ArrayList<>();
        for (Pow p : heldBy(to)) if (p.home.equals(from)) ours.add(p);
        for (Pow p : heldBy(from)) if (p.home.equals(to)) theirs.add(p);
        if (ours.size() <= theirs.size()) return 0;
        int want = 0;
        for (Pow p : ours.subList(theirs.size(), ours.size())) want += p.worth;
        int can = Math.max(0, Ledger.coins(from) - 5);
        return want <= can ? Ledger.takeCoins(from, want) : 0;
    }

    /** [Envoys.send] A town's envoy "with a gift" to a town holding its folk for ransom: the ransom of as many as it can pay. */
    static int ransomPurse(UUID from, UUID to, Envoys.Errand errand, long day) {
        if (errand != Envoys.Errand.GIFT || to.equals(Vassals.overlord(from)) || Wars.atWar(from, to)) return 0;
        List<Pow> owed = new ArrayList<>();
        for (Pow p : heldBy(to)) if (p.home.equals(from) && p.forRansom()) owed.add(p);
        if (owed.isEmpty()) return 0;
        int budget = Math.max(0, Ledger.coins(from) - 5), sum = 0;
        List<String> who = new ArrayList<>();
        for (Pow p : owed) {
            if (sum + p.worth > budget) continue;
            sum += p.worth;
            who.add(p.who.toString());
        }
        if (who.isEmpty()) return 0;
        int took = Ledger.takeCoins(from, sum);
        Ledger.note(from, RANSOM + to, day + "|" + String.join(",", who) + "|" + took);
        return took;
    }

    /** [WarAndPeace.asks] What the ransom's carrier says before the board: null if it is not carrying one. */
    @Nullable
    static String asks(UUID from, UUID host, Envoys.Errand errand, Caravans.Trip t) {
        String n = errand == Envoys.Errand.GIFT ? Ledger.note(from, RANSOM + host) : null;
        if (n == null || n.isEmpty()) return null;
        String elder = Villages.elderName(from);
        String sender = elder.isEmpty() ? "The folk of " + Villages.name(from) : "Elder " + elder + " of " + Villages.name(from);
        return sender + " sends " + t.purse + " coins: the ransom of our folk you hold. Let them come home.";
    }

    /** [WarAndPeace.answer] The ransom received at the captor's board: the coin into its treasury, the prisoners let go. */
    @Nullable
    static Envoys.Answer received(ServerLevel level, UUID host, UUID from, Envoys.Errand errand, VillageFolkEntity envoy, Caravans.Trip t) {
        String n = errand == Envoys.Errand.GIFT ? Ledger.note(from, RANSOM + host) : null;
        if (n == null || n.isEmpty()) return null;
        String[] parts = n.split("\\|");
        int coins = parts.length > 2 ? (int) WarBooks.num(parts[2], 0) : 0;
        long day = level.getDayTime() / 24000L;
        return new Envoys.Answer(true, "The ransom of " + Villages.name(from) + ", received. They're free to go home with you.", () -> {
            Ledger.forget(from, RANSOM + host);
            int paid = Math.min(t.purse, coins);
            t.purse -= paid;
            Ledger.addCoins(host, paid);
            List<String> names = new ArrayList<>();
            if (parts.length > 1) for (String s : parts[1].split(",")) {
                try {
                    UUID who = UUID.fromString(s);
                    Pow p = of(who);
                    if (p == null || !p.holder.equals(host)) continue;
                    names.add(p.name);
                    free(level, who, "ransomed", p.name + " of " + Villages.name(from) + " was ransomed for " + p.worth + " coins, brought by " + envoy.displayNameCap(),
                        p.name + " was ransomed from " + Villages.name(host) + " and is on the way home", true);
                } catch (IllegalArgumentException ignored) { }
            }
            t.outcome = "we paid " + paid + " coins' ransom" + (names.isEmpty() ? "" : " for " + String.join(" and ", names)) + ", and they are coming home";
            WarBooks.course(from, host, day, "we paid " + paid + " coins' ransom for " + String.join(", ", names));
        });
    }

    /** A town owing ransoms sends the coin when it has it: an envoy "with a gift", the ransom in its purse (ransomPurse). */
    static void ransomDay(ServerLevel level, Villages.Village home, long day) {
        java.util.Set<UUID> holders = new java.util.LinkedHashSet<>();
        for (Pow p : ofTown(home.id())) if (p.forRansom()) holders.add(p.holder);
        for (UUID h : holders) {
            Villages.Village to = Villages.get(h);
            if (to == null || Wars.atWar(home.id(), h) || Envoys.travelling(home.id(), h)) continue;
            String carried = Ledger.note(home.id(), RANSOM + h);
            if (carried != null && !carried.isEmpty()) {
                if (day - WarBooks.num(carried.split("\\|")[0], day) < 4) continue;
                Ledger.forget(home.id(), RANSOM + h);                     // (an envoy that never got there: the coin came home with it)
            }
            int cheapest = Integer.MAX_VALUE;
            for (Pow p : ofTown(home.id())) if (p.holder.equals(h) && p.forRansom()) cheapest = Math.min(cheapest, p.worth);
            if (Ledger.coins(home.id()) - 5 < cheapest) continue;
            if (Envoys.send(level, home, to, Envoys.Errand.GIFT, day)) {
                Villages.tell(home.id(), day, "the elder sent the ransom for our folk held in " + Villages.name(h));
            }
        }
    }

    // ------------------------------------------------------------------ players

    /** What a player pays the watch for this prisoner's release. */
    static int price(Pow p) {
        return p.worth * BUY_PERCENT / 100;
    }

    /**
     * Talk (AfterWar.talk): to a prisoner, its plight, or "I'm getting you out" (a break-out); to a guard of a town holding
     * prisoners, "who are your prisoners?" and "I'll pay for the release of Fen". Null when it is none of that.
     */
    @Nullable
    static String talk(VillageFolkEntity f, ServerPlayer p, String text) {
        if (!(f.level() instanceof ServerLevel level)) return null;
        String t = text.toLowerCase(Locale.ROOT);
        Pow me = of(f.getUUID());
        if (me != null) {
            if (t.contains("getting you out") || t.contains("break you out") || t.contains("break out") || t.contains("set you free") || t.contains("free you")) {
                return breakOut(level, f, me, p);
            }
            if (t.isEmpty() || t.contains("how") || t.contains("prisoner") || t.contains("ransom") || t.contains("who are you")) {
                return plight(me, level.getDayTime() / 24000L);
            }
            return null;
        }
        UUID village = f.ownerId();
        if (village == null || f.stationTask() != AssistantEntity.StationTask.GUARD) return null;
        if (t.contains("release") || t.contains("buy") && t.contains("prisoner") || t.contains("ransom")) return buy(level, f, p, t);
        if (t.contains("prisoner")) return list(village, level.getDayTime() / 24000L);
        return null;
    }

    static String plight(Pow me, long day) {
        String home = Villages.name(me.home), holder = Villages.name(me.holder);
        if (Villages.get(me.home) == null) return "Taken at " + me.where + ", day " + me.day + ". And " + home + " is gone now, they tell me. Where would I go?";
        return "Taken at " + me.where + ", day " + me.day + ", and held here ever since. "
            + (me.forRansom() ? "There's peace now, and " + holder + " wants " + me.worth + " coins for me. " + home + " will pay when it can."
                : home + " and " + holder + " are still at war. When there's peace there'll be an exchange, or a ransom.")
            + " The watch would let me go for " + price(me) + ", if somebody paid it.";
    }

    static String list(UUID village, long day) {
        List<Pow> held = heldBy(village);
        if (held.isEmpty()) return "We hold no prisoners of war.";
        List<String> s = new ArrayList<>();
        for (Pow q : held) s.add(q.name + " of " + Villages.name(q.home) + " (" + rankWords(q.rank) + ", " + price(q) + " coins to buy out)");
        return "We hold " + held.size() + (held.size() == 1 ? " prisoner" : " prisoners") + " of war: " + String.join("; ", s)
            + ". Say \"I'll pay for the release of\" and the name, and it's done.";
    }

    /** A prisoner's release bought from the watch: the price into the treasury, it goes home; its own town is grateful. */
    static String buy(ServerLevel level, VillageFolkEntity g, ServerPlayer p, String t) {
        UUID village = g.ownerId();
        List<Pow> held = heldBy(village);
        if (held.isEmpty()) return "We hold no prisoners of war to buy out.";
        Pow pick = null;
        for (Pow q : held) if (t.contains(q.name.toLowerCase(Locale.ROOT).split(" ")[0])) { pick = q; break; }
        if (pick == null) return list(village, level.getDayTime() / 24000L);
        if (Wars.atWar(village, pick.home)) return "Not while we're at war with " + Villages.name(pick.home) + ". Ask again at the peace.";
        if (Outlaws.isOutlaw(village, p.getUUID())) return "From you? Not a chance.";
        int price = price(pick);
        int have = Market.coinsHeld(p);
        if (have < price) return pick.name + "'s release is " + price + " coins, and you've " + have + ".";
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        UUID home = pick.home;
        free(level, pick.who, "bought out by " + name, pick.name + " of " + Villages.name(home) + ", our prisoner, was bought out by " + name + " for " + price + " coins",
            name + " bought " + pick.name + "'s release from " + Villages.name(village) + " for " + price + " coins; " + pick.name + " is on the way home", true);
        // Its own town thinks the world of the player for it; it, most of all.
        for (AssistantEntity a : Villages.folkOf(home)) {
            if (a instanceof VillageFolkEntity o) o.persona().feelFor(p.getUUID(), name, o.getUUID().equals(pick.who) ? 25 : 6);
        }
        Standing.stir(home, p.getUUID());
        Police.log(village, level.getDayTime(), "ransom", name + " (a player) bought out " + pick.name + ", a prisoner of war, for " + price, g, price);
        p.sendSystemMessage(Component.literal("You paid " + price + " coins for " + pick.name + "'s release. " + Villages.name(home) + " will remember it.").withStyle(ChatFormatting.GREEN));
        level.playSound(null, g.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
        return "Done: " + price + " coins, and " + pick.name + " goes free. It'll be home in " + Villages.name(home) + " in a day or two.";
    }

    /** "I'm getting you out": seen by anybody of the town, it is a crime; unseen, it slips away all the same. */
    static String breakOut(ServerLevel level, VillageFolkEntity f, Pow me, ServerPlayer p) {
        Villages.Village v = Villages.get(me.holder);
        if (v == null) return "Out? There's nobody left to hold me.";
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        VillageFolkEntity seen = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity w) || w == f || w.isSleeping() || w.isBaby()) continue;
            if (w.distanceToSqr(p) < 16 * 16 && w.hasLineOfSight(p)) { seen = w; break; }
        }
        Place at = placeFor(level, v, me);
        if (at.cell() != null) WatchHouse.setDoor(level, at.cell().door(), true);
        level.playSound(null, f.blockPosition(), at.cell() != null ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 0.6F);
        free(level, me.who, "broken out by " + name, me.name + " of " + Villages.name(me.home) + ", our prisoner, was broken out by " + name + (seen == null ? ", nobody saw how" : ""),
            name + " broke " + me.name + " out of " + Villages.name(v.id()) + "; " + me.name + " is on the way home", true);
        for (AssistantEntity a : Villages.folkOf(me.home)) {
            if (a instanceof VillageFolkEntity o) o.persona().feelFor(p.getUUID(), name, o.getUUID().equals(me.who) ? 20 : 4);
        }
        if (seen != null) {
            FolkTalk.speak(seen, FolkTalk.pick(seen.getRandom(), "Guards! The prisoner! " + name + " let them out!", "Stop! That's our prisoner!"));
            Outlaws.crime(level, v, p, Outlaws.Deed.BREAKOUT, "breaking " + me.name + " out of the town's keeping");
        }
        return seen == null ? "Quick, then — before anybody sees. I'll not forget this." : "They've seen you! Run — I'm away home!";
    }

    // ------------------------------------------------------------------ a town gone

    /** [Ruins.fall] A town is no more: the prisoners it held let go home; its folk held elsewhere offered a place. */
    static void townGone(ServerLevel level, UUID dead) {
        long day = level.getDayTime() / 24000L;
        for (Pow p : heldBy(dead)) {
            free(level, p.who, "its keepers gone", "", p.name + ", held in " + Villages.name(dead) + ", walked free when that town fell, and is on the way home", true);
        }
        for (Pow p : ofTown(dead)) {
            Villages.Village holder = Villages.get(p.holder);
            if (holder == null) continue;
            offer(level, holder, p, Civics.find(level, p.who), day, true);
        }
    }

    // ------------------------------------------------------------------ the books, the card, the board, the mood

    /** The War page: the prisoners we hold. */
    static List<String> report(UUID id, long day) {
        List<String> out = new ArrayList<>();
        for (Pow p : heldBy(id)) {
            out.add(p.name + " of " + Villages.name(p.home) + ", " + rankWords(p.rank) + ", taken at " + p.where + " on day " + p.day
                + (p.forRansom() ? "; held for ransom since the peace of day " + p.peace + ", " + p.worth + " coins asked" : "; worth " + p.worth + " coins in ransom")
                + (p.crops > 0 ? "; has brought in " + p.crops + " crops" : "") + (p.hungry > 0 ? "; went hungry " + p.hungry + " day(s)" : ""));
        }
        int fed = num(Ledger.note(id, "pow.fed")), crops = num(Ledger.note(id, "pow.crops"));
        if (fed + crops > 0) out.add("Their keep so far: " + fed + " meals out of the stores; their work, " + crops + " crops brought in");
        return out;
    }

    /** The War page: ours held by others. */
    static List<String> oursHeld(UUID id) {
        List<String> out = new ArrayList<>();
        for (Pow p : ofTown(id)) {
            out.add(p.name + ", " + rankWords(p.rank) + ", held in " + Villages.name(p.holder) + " since day " + p.day
                + (p.forRansom() ? " (ransom " + p.worth + " coins, owed)" : ""));
        }
        return out;
    }

    /** The board's lines for a town holding prisoners, or whose folk are held. */
    static List<String> board(UUID id) {
        List<String> out = new ArrayList<>();
        List<Pow> held = heldBy(id), ours = ofTown(id);
        if (!held.isEmpty()) {
            int owed = 0;
            for (Pow p : held) if (p.forRansom()) owed += p.worth;
            out.add("RW|Prisoners of war: " + held.size() + " held" + (owed > 0 ? ", " + owed + " coins' ransom asked" : "")
                + ". Ask the watch to buy one out.");
        }
        if (!ours.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Pow p : ours) if (names.size() < 3) names.add(p.name + " (" + Villages.name(p.holder) + ")");
            out.add("RW|Ours held prisoner: " + String.join(", ", names) + (ours.size() > 3 ? " and " + (ours.size() - 3) + " more" : "") + ".");
        }
        return out;
    }

    /** Its card: "prisoner of Bramley since day 40 (a sergeant; ransom 16 coins); held in the cells at the watch house". */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        Pow p = of(f.getUUID());
        if (p == null) return null;
        Villages.Village v = Villages.get(p.holder);
        String held = v != null && f.level() instanceof ServerLevel level ? "held " + placeFor(level, v, p).words(p.holder) : "";
        return "prisoner of " + Villages.name(p.holder) + " since day " + p.day + " (" + rankWords(p.rank) + ", taken at " + p.where + "; ransom " + p.worth
            + " coins" + (p.forRansom() ? ", owed since the peace" : "") + ")" + (held.isEmpty() ? "" : "; " + held);
    }

    /** Its spirits: held, or one of its kin held. */
    static int mood(VillageFolkEntity f, int m, List<Object[]> why) {
        Pow me = of(f.getUUID());
        if (me != null) {
            why.add(new Object[]{ "captive", 8 });
            return m - 8;
        }
        UUID home = f.ownerId();
        if (home == null) return m;
        String id = f.getUUID().toString();
        for (Pow p : ofTown(home)) {
            if (p.kin.contains(id)) {
                why.add(new Object[]{ "kinheld", 5 });
                return m - 5;
            }
        }
        return m;
    }

    static String moodWords(VillageFolkEntity f, String key) {
        if (key.equals("captive")) {
            Pow me = of(f.getUUID());
            String home = me == null ? "home" : Villages.name(me.home);
            return FolkTalk.pick(f.getRandom(), "I want to go home to " + home + ".", "Every day here is a day I'm not at home.");
        }
        UUID home = f.ownerId();
        String id = f.getUUID().toString();
        if (home != null) for (Pow p : ofTown(home)) {
            if (p.kin.contains(id)) return FolkTalk.pick(f.getRandom(), p.name + " is still held in " + Villages.name(p.holder) + ". I can't stop thinking about it.",
                "When will they let " + p.name + " come home?");
        }
        return "";
    }

    // ------------------------------------------------------------------ the stockade

    /** More prisoners than its cells will take: the town wants a stockade. */
    public static boolean stockadeWanted(UUID village) {
        List<Pow> held = heldBy(village);
        if (held.isEmpty() || Villages.builtStructure(village, STOCKADE) != null) return false;
        Ledger.Building house = WatchHouse.of(village);
        int cells = house == null ? 0 : WatchHouse.cells(house).size();
        return held.size() > cells;
    }

    public static String stockadeWhy(UUID village) {
        int n = heldBy(village).size();
        return "We hold " + n + " prisoners of war and the cells will not take them all: a stockade, a pen of stout fences with a lean-to over "
            + "two beds, to keep them under the watch's eye.";
    }

    static int num(@Nullable String s) {
        return (int) WarBooks.num(s, 0);
    }

    // ------------------------------------------------------------------ tests

    public static void roundForTests(ServerLevel level, Villages.Village v, long seed) {
        round(level, v, new Random(seed));
    }

    public static boolean feedForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Pow p = of(f.getUUID());
        if (p == null) return false;
        feed(level, v, p, f, level.getDayTime() / 24000L);
        return p.hungry == 0;
    }

    public static String placeForTests(ServerLevel level, VillageFolkEntity f) {
        Pow p = of(f.getUUID());
        Villages.Village v = p == null ? null : Villages.get(p.holder);
        return v == null ? "" : placeFor(level, v, p).kind();
    }

    public static boolean escapeForTests(ServerLevel level, VillageFolkEntity f) {
        Pow p = of(f.getUUID());
        Villages.Village v = p == null ? null : Villages.get(p.holder);
        return v != null && escape(level, v, p, f, placeFor(level, v, p), level.getDayTime() / 24000L);
    }

    public static void offerForTests(ServerLevel level, VillageFolkEntity f, boolean homeGone) {
        Pow p = of(f.getUUID());
        Villages.Village v = p == null ? null : Villages.get(p.holder);
        if (v != null) offer(level, v, p, f, level.getDayTime() / 24000L, homeGone);
    }

    public static String cardForTests(VillageFolkEntity f) {
        String s = cardLine(f);
        return s == null ? "" : s;
    }

    public static void ransomDayForTests(ServerLevel level, Villages.Village home) {
        ransomDay(level, home, level.getDayTime() / 24000L);
    }

    /** The ransom's carrier from {@code from} heard at {@code host}'s board now: the outcome, or "" if none is on the road. */
    public static String ransomArrivesForTests(ServerLevel level, UUID from, UUID host) {
        for (AssistantEntity a : Villages.folkOf(from)) {
            if (!(a instanceof VillageFolkEntity f) || f.trip() == null || !f.trip().to.equals(host) || f.trip().errand != Envoys.Errand.GIFT) continue;
            Envoys.Answer ans = received(level, host, from, Envoys.Errand.GIFT, f, f.trip());
            if (ans == null) return "";
            if (ans.effect() != null) ans.effect().run();
            return f.trip().outcome == null ? "" : f.trip().outcome;
        }
        return "";
    }

    public static int carriedForTests(UUID from, UUID host) {
        for (AssistantEntity a : Villages.folkOf(from)) {
            if (a instanceof VillageFolkEntity f && f.trip() != null && f.trip().to.equals(host)) return f.trip().purse;
        }
        return -1;
    }

    /** The stockade stamped here facing north and put on the town's books, as its builders would leave it. */
    public static void stockadeForTests(ServerLevel level, Villages.Village v, BlockPos site) {
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, STOCKADE, site, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(v.id(), STOCKADE, site, Direction.NORTH);
        Villages.builtAtForTests(v.id(), STOCKADE, site);
    }

    public static int workForTests(ServerLevel level, VillageFolkEntity f, int steps) {
        Pow p = of(f.getUUID());
        Villages.Village v = p == null ? null : Villages.get(p.holder);
        if (v == null) return -1;
        BlockPos crop = ripeCrop(level, v, f.blockPosition());
        if (crop == null) return 0;
        long[] stint = { crop.asLong(), level.getGameTime(), 0 };
        int cut = 0;
        for (int i = 0; i < steps; i++) {
            BlockPos c = BlockPos.of(stint[0]);
            f.moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
            cut = (int) stint[2];
            if (!workStep(level, v, f, p, stint)) break;
        }
        return Math.max(cut, (int) stint[2]);
    }
}
