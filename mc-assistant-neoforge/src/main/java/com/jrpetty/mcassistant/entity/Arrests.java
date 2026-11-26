package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.Justice.Charge;
import com.jrpetty.mcassistant.entity.Justice.Ev;
import com.jrpetty.mcassistant.entity.Justice.JCase;
import com.jrpetty.mcassistant.entity.Justice.Stage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpe] The watch's walk with a warrant, and with a prisoner (Justice). A warrant out, the nearest free guard of the town
 * (else a sworn special constable's notice, else the elder standing in) walks to the named player while it is in the
 * town and online: "Stop there. I have a warrant for your arrest." Within arm's reach the irons go on (Restraint), the
 * player is searched (stolen goods found are evidence, and their case joins this one), and it is walked on the lead to
 * the jail: into a cell, the door shut on it, the irons off, remanded till the court sits. It is walked from the cell to
 * the dock and back the same way, and a guard brings its meals. Nobody is ever moved out of sight: every step is walked.
 * Pulling away is resisting arrest; outrunning the watch is a pursuit and a wanted notice on the board; giving oneself
 * up is remembered in its favour.
 */
@EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
final class Arrests {

    private Arrests() {}

    enum Phase { GO, LEAD, CELL, FETCH, DOCK, MEAL }

    static final class Task {
        final int caseId;
        final UUID player;
        Phase phase;
        long since;
        boolean told;
        @Nullable Jail.Spot spot;
        @Nullable BlockPos to;

        Task(int caseId, UUID player, Phase phase, long since) {
            this.caseId = caseId;
            this.player = player;
            this.phase = phase;
            this.since = since;
        }
    }

    /** By guard: what it is about. */
    private static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();
    /** By player: when the watch last lost it (a pursuit that failed), so it is not sent for again at once. */
    private static final Map<UUID, Long> LOST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PATHED = new ConcurrentHashMap<>();

    static void resetForTests() {
        TASKS.clear();
        LOST.clear();
        PATHED.clear();
    }

    static boolean busy(VillageFolkEntity g) {
        return TASKS.containsKey(g.getUUID());
    }

    @Nullable
    static Task task(VillageFolkEntity g) {
        return TASKS.get(g.getUUID());
    }

    /** The guard on this player, or null. */
    @Nullable
    static UUID guardOn(UUID player) {
        for (Map.Entry<UUID, Task> e : TASKS.entrySet()) if (e.getValue().player.equals(player)) return e.getKey();
        return null;
    }

    /** Whoever serves a warrant here: the nearest free guard, else the elder. */
    @Nullable
    static VillageFolkEntity officerFor(ServerLevel level, Villages.Village v, BlockPos at) {
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, at, 160.0, null);
        if (g != null) return g;
        UUID elder = Villages.elder(v.id());
        VillageFolkEntity e = elder == null ? null : Civics.find(level, elder);
        return e != null && e.isAlive() && !TASKS.containsKey(e.getUUID()) ? e : null;
    }

    // ------------------------------------------------------------------ the warrant

    /** A warrant out (Justice.round): a guard sent for the named player while it is about. */
    static void warrant(ServerLevel level, Villages.Village v, JCase c) {
        if (guardOn(c.accused) != null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(c.accused);
        if (p == null || p.level() != level || !p.isAlive()) return;
        if (Jail.held(p.getUUID())) {                                          // already inside: the charge joins its custody
            Jail.join(level, p, c);
            return;
        }
        Long lost = LOST.get(p.getUUID());
        long now = level.getGameTime();
        if (lost != null && now - lost < Justice.pause(1200)) return;
        int reach = Villages.townReach(v.id()) + 32;
        if (Math.max(Math.abs(p.getX() - v.centre().getX()), Math.abs(p.getZ() - v.centre().getZ())) > reach) return;
        VillageFolkEntity g = officerFor(level, v, p.blockPosition());
        if (g == null) return;
        Task t = new Task(c.id, p.getUUID(), Phase.GO, now);
        TASKS.put(g.getUUID(), t);
        c.officer = g.getUUID();
        c.officerName = g.displayNameCap();
        g.getNavigation().stop();
        Police.log(v.id(), level.getDayTime(), "arrest", g.displayNameCap() + " went to arrest " + c.accusedName + " for " + c.title(), g, 0);
        Justice.changed();
    }

    // ------------------------------------------------------------------ the guard's part

    /** The guard's walk (VillageFolkEntity's round): true while it is about it. */
    static boolean hold(VillageFolkEntity g, ServerLevel level) {
        Task t = TASKS.get(g.getUUID());
        if (t == null) return false;
        if (!g.isAlive()) { TASKS.remove(g.getUUID()); return false; }
        JCase c = Justice.get(t.caseId);
        Villages.Village v = c == null ? null : Villages.get(c.village);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(t.player);
        if (c == null || v == null) { end(g, null); return false; }
        long now = level.getGameTime();
        g.lastLeisureTick = g.tickCount;
        if (p == null || p.level() != level) {
            // Logged off: held to it all the same (Jail puts it in its cell when it is back).
            if (t.phase == Phase.LEAD || t.phase == Phase.CELL) Jail.lockUp(level, v, t.player, c, t.spot, g);
            end(g, null);
            return false;
        }
        switch (t.phase) {
            case GO -> go(level, v, g, t, c, p, now);
            case LEAD -> lead(level, v, g, t, c, p, now);
            case CELL -> cell(level, v, g, t, c, p, now);
            case FETCH -> fetch(level, v, g, t, c, p, now);
            case DOCK -> dock(level, v, g, t, c, p, now);
            case MEAL -> meal(level, v, g, t, c, p, now);
        }
        return TASKS.containsKey(g.getUUID());
    }

    static String doing(VillageFolkEntity g) {
        Task t = TASKS.get(g.getUUID());
        if (t == null) return null;
        return switch (t.phase) {
            case GO -> "serving a warrant";
            case LEAD, CELL -> "taking a prisoner to the cells";
            case FETCH, DOCK -> "taking a prisoner to court";
            case MEAL -> "bringing a prisoner its dinner";
        };
    }

    private static void end(VillageFolkEntity g, @Nullable ServerPlayer p) {
        TASKS.remove(g.getUUID());
        PATHED.remove(g.getUUID());
        g.setSprinting(false);
        g.getNavigation().stop();
    }

    private static void walk(VillageFolkEntity g, BlockPos to, double speed) {
        Integer last = PATHED.get(g.getUUID());
        if (g.getNavigation().isDone() || last == null || g.tickCount - last > 40) {
            g.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed);
            PATHED.put(g.getUUID(), g.tickCount);
        }
    }

    /** To the player with the warrant; the irons on at arm's reach. */
    private static void go(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, JCase c, ServerPlayer p, long now) {
        double d = g.distanceTo(p);
        if (d > 2.3) {
            boolean run = d > 10;
            g.setSprinting(run);
            Integer last = PATHED.get(g.getUUID());
            if (g.getNavigation().isDone() || last == null || g.tickCount - last > 10) {
                g.getNavigation().moveTo(p, run ? 1.2 : 0.9);
                PATHED.put(g.getUUID(), g.tickCount);
            }
            if (!t.told && d < 9 && g.hasLineOfSight(p)) {
                t.told = true;
                t.since = now;
                String n = Belonging.called(p);
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Stop there, " + n + ". I have a warrant for your arrest.",
                    "In the name of the watch, " + n + ": stand still.", n + "! You're wanted for " + c.charge.word + ". Hands where I can see them.",
                    "Don't make this harder, " + n + ". You're under arrest.", "That's far enough, " + n + ". The watch wants a word.",
                    "Stay where you are, " + n + ". Warrant for " + c.charge.word + "."));
                p.displayClientMessage(Component.literal(g.displayNameCap() + " has a warrant for your arrest for " + c.title()
                    + ". Running away adds a charge of resisting arrest.").withStyle(ChatFormatting.RED), false);
            }
            // Outrun: the pursuit is lost; the warrant stands, and the board says so.
            if (t.told && (d > 40 || now - t.since > Justice.pause(2400))) fled(level, v, g, c, p);
            else if (!t.told && now - t.since > 12000) end(g, p);            // never caught up with it: another time
            return;
        }
        g.setSprinting(false);
        g.getNavigation().stop();
        g.getLookControl().setLookAt(p, 30.0F, 30.0F);
        arrest(level, v, g, t, c, p);
    }

    /** The irons on, the search, the notice; then the walk. */
    static void arrest(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, JCase c, ServerPlayer p) {
        Restraint.on(p, g, Jail.irons(level, v) ? Restraint.Kind.CUFFS : Restraint.Kind.ROPE);
        c.stage = Stage.ARREST;
        c.notes.add(g.displayNameCap() + " arrested " + c.accusedName);
        List<String> found = search(level, v, p, c);
        // Every other warrant or summons out for it rides along.
        for (JCase o : Justice.namedAgainst(p.getUUID())) {
            if (o == c || o.joinedTo != 0 || o.stage.ordinal() > Stage.WARRANT.ordinal()) continue;
            join(c, o);
        }
        t.spot = Jail.spot(level, v, p.getUUID());
        t.phase = Phase.LEAD;
        t.since = level.getGameTime();
        String n = Belonging.called(p);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Come along quietly, " + n + ".", "This way. Mind your step.", "Easy now. The cells aren't far.",
            "No trouble, and there'll be none from me.", "Walk with me. Slowly.", "Right. To the cells with you."));
        String where = t.spot == null ? "the watch's custody" : t.spot.words;
        Justice.notice(p, "You are under arrest",
            "You are under arrest for " + c.chargeWords() + " at " + c.place + " (witness: " + Justice.nonEmpty(c.firstWitness(), "the watch's evidence") + ")."
                + (found.isEmpty() ? "" : " Searched: " + String.join(", ", found) + " taken as evidence."),
            "The watch is walking you to " + where + ". Pulling away is resisting arrest. You'll be held there until the court sits; you can prepare your defence or hire a lawyer on this page.");
        Police.log(v.id(), level.getDayTime(), "arrest", c.accusedName + " (a player) was arrested for " + c.title(), g, 0);
        Villages.tell(v.id(), c.day, "the watch arrested " + c.accusedName + " for " + c.charge.word);
        Justice.changed();
    }

    /** Another case tried with this one. */
    static void join(JCase main, JCase o) {
        if (o == main || main.joined.contains(o.id)) return;
        main.joined.add(o.id);
        o.joinedTo = main.id;
        o.stage = Stage.ARREST;
        main.notes.add("tried with " + o.title());
        Justice.changed();
    }

    /** The search: stolen goods (marked) taken as evidence, and their cases brought in. Returns what was found. */
    static List<String> search(ServerLevel level, Villages.Village v, ServerPlayer p, JCase c) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            int id = Justice.stolenCase(s);
            if (id == 0) continue;
            JCase o = Justice.get(id);
            found.add(s.getCount() + " " + s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
            p.getInventory().setItem(i, ItemStack.EMPTY);
            Crafts.store(level, v, s.copy());                                  // back to the town, for its owner
            if (o == null || o.stage == Stage.CONVICTED || o.stage == Stage.ACQUITTED) continue;
            o.evidence.add(new Ev("GOODS", "the " + s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " taken in it, found on " + o.accusedName, 3.0, true));
            o.notes.add("the stolen goods were found on " + o.accusedName + " when the watch searched them");
            if (o != c) join(c, o);
        }
        p.getInventory().setChanged();
        return found;
    }

    /** Off at the run: resisting arrest, a pursuit lost, and the wanted notice. */
    static void fled(ServerLevel level, Villages.Village v, VillageFolkEntity g, JCase c, ServerPlayer p) {
        end(g, p);
        LOST.put(p.getUUID(), level.getGameTime());
        addCharge(c, Charge.RESISTING, "fled from " + g.displayNameCap());
        c.notes.add("wanted: fled the watch");
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Run, then. We'll see you again.", "Gone. The board will have their name by nightfall.",
            "They'll be back. They always are.", "Fine. Wanted it is.", "Let them run. The town's not that big.", "Next time, then."));
        Justice.notice(p, "Wanted", "You ran from the watch. A charge of resisting arrest has been added, and your name is on the board as wanted.",
            "The watch will come for you again whenever it sees you in town. Giving yourself up to any guard counts in your favour.");
        Police.log(v.id(), level.getDayTime(), "fled", c.accusedName + " (a player) fled the watch", g, 0);
        Villages.tell(v.id(), c.day, c.accusedName + " fled the watch and is wanted");
    }

    static void addCharge(JCase c, Charge ch, String why) {
        if (!c.extra.contains(ch)) c.extra.add(ch);
        c.notes.add(ch.word + ": " + why);
        Justice.changed();
    }

    /** Pulled free of the lead (Restraint): resisting arrest, and the guard after it at a run. */
    static void brokeAway(ServerLevel level, ServerPlayer p, @Nullable UUID leader) {
        Restraint.off(p);
        UUID gid = guardOn(p.getUUID());
        Task t = gid == null ? null : TASKS.get(gid);
        VillageFolkEntity g = gid == null ? null : Civics.find(level, gid);
        JCase c = t == null ? null : Justice.get(t.caseId);
        if (t == null || g == null || c == null) return;
        Villages.Village v = Villages.get(c.village);
        addCharge(c, Charge.RESISTING, "pulled free of " + g.displayNameCap());
        c.stage = Stage.WARRANT;
        t.phase = Phase.GO;
        t.told = true;
        t.since = level.getGameTime();
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Oi! Come back here!", "Stop! You're only making it worse!", "Grab them!",
            "That's resisting, that is!", "Don't you dare run!", "After them!"));
        if (v != null) Justice.notice(p, "Resisting arrest", "You pulled free of the watch. That is resisting arrest, and it's been added to the charges.",
            "Stop and let the guard take you, or give yourself up to any guard. If you outrun them, you'll be wanted.");
    }

    /** "I give myself up": to a guard, the arrest there and then; to anybody else, the watch sent for. Null if nothing is out. */
    @Nullable
    static String surrender(VillageFolkEntity f, ServerPlayer p) {
        JCase c = null;
        for (JCase o : Justice.namedAgainst(p.getUUID())) if (o.stage == Stage.WARRANT && o.joinedTo == 0) { c = o; break; }
        if (c == null) return null;
        ServerLevel level = (ServerLevel) f.level();
        Villages.Village v = Villages.get(c.village);
        if (v == null) return null;
        if (!c.notes.contains("surrendered")) c.notes.add("surrendered");
        LOST.remove(p.getUUID());
        UUID onIt = guardOn(p.getUUID());
        if (onIt != null) {
            VillageFolkEntity g = Civics.find(level, onIt);
            Task t = TASKS.get(onIt);
            if (g != null && t != null && t.phase == Phase.GO) { t.told = true; t.since = level.getGameTime(); }
            return "Wait here. The guard's coming for you.";
        }
        boolean guard = f.stationTask() == AssistantEntity.StationTask.GUARD && !TASKS.containsKey(f.getUUID());
        VillageFolkEntity g = guard ? f : officerFor(level, v, p.blockPosition());
        if (g == null) return "There's nobody of the watch about to take you. Go to the watch house.";
        Task t = new Task(c.id, p.getUUID(), Phase.GO, level.getGameTime());
        t.told = true;
        TASKS.put(g.getUUID(), t);
        Justice.changed();
        return guard ? FolkTalk.pick(f.getRandom(), "Sensible. Hold out your hands.", "Good. The court will hear you came quietly.", "That's the right thing. Come here.",
            "Wise. Hands, please.", "You've done yourself a favour.", "Quietly does it. Hands.")
            : "Stay where you are. I've sent for the watch; " + g.displayNameCap() + " is on the way.";
    }

    // ------------------------------------------------------------------ the walk to the cells

    private static void lead(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, JCase c, ServerPlayer p, long now) {
        if (!Restraint.restrained(p)) Restraint.on(p, g, Jail.irons(level, v) ? Restraint.Kind.CUFFS : Restraint.Kind.ROPE);
        BlockPos to = t.spot == null ? null : t.spot.front;
        if (to == null) {
            Jail.lockUp(level, v, p.getUUID(), c, null, g);
            end(g, p);
            return;
        }
        double d = Math.sqrt(g.distanceToSqr(Vec3.atBottomCenterOf(to)));
        if (d > 1.6) {
            walk(g, to, 0.55);
            g.getLookControl().setLookAt(p, 20.0F, 20.0F);
            if (now - t.since > Justice.pause(7200)) {                      // the way blocked: held where it stands
                Jail.lockUp(level, v, p.getUUID(), c, null, g);
                end(g, p);
            }
            return;
        }
        g.getNavigation().stop();
        t.phase = Phase.CELL;
        t.since = now;
        if (t.spot.door != null) WatchHouse.setDoor(level, t.spot.door, true);
        Restraint.pullTo(p, t.spot.inside);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "In you go.", "Your room for the night.", "Inside. Bed's in the corner.",
            "Step in. I'll have those irons off.", "In. Mind the bucket.", "Here we are."));
    }

    private static void cell(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, JCase c, ServerPlayer p, long now) {
        Jail.Spot s = t.spot;
        g.getLookControl().setLookAt(p, 30.0F, 30.0F);
        double dx = p.getX() - (s.inside.getX() + 0.5), dz = p.getZ() - (s.inside.getZ() + 0.5);
        boolean in = dx * dx + dz * dz < 0.8 * 0.8;
        if (!in && now - t.since < 200) return;
        if (!in) p.teleportTo(s.inside.getX() + 0.5, s.inside.getY(), s.inside.getZ() + 0.5);   // a last shuffle in, never out of the guard's sight
        Restraint.off(p);
        if (s.door != null) WatchHouse.setDoor(level, s.door, false);
        Jail.lockUp(level, v, p.getUUID(), c, s, g);
        end(g, p);
    }

    // ------------------------------------------------------------------ to court and back

    /** The court wants the prisoner: a guard to its cell, the irons on, and to the dock. False if nobody can. */
    static boolean toCourt(ServerLevel level, Villages.Village v, JCase c, ServerPlayer p, BlockPos dock) {
        if (guardOn(p.getUUID()) != null) return true;
        VillageFolkEntity g = officerFor(level, v, p.blockPosition());
        if (g == null) return false;
        Task t = new Task(c.id, p.getUUID(), Phase.FETCH, level.getGameTime());
        t.spot = Jail.spotOf(p.getUUID());
        t.to = dock.immutable();
        TASKS.put(g.getUUID(), t);
        return true;
    }

    private static void fetch(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, JCase c, ServerPlayer p, long now) {
        BlockPos front = t.spot == null || t.spot.front == null ? p.blockPosition() : t.spot.front;
        if (g.distanceToSqr(Vec3.atBottomCenterOf(front)) > 2.0 * 2.0 && g.distanceTo(p) > 2.5) {
            walk(g, front, 0.8);
            if (now - t.since > Justice.pause(7200)) end(g, p);
            return;
        }
        g.getNavigation().stop();
        if (t.spot != null && t.spot.door != null) WatchHouse.setDoor(level, t.spot.door, true);
        Restraint.on(p, g, Jail.irons(level, v) ? Restraint.Kind.CUFFS : Restraint.Kind.ROPE);
        Jail.outForCourt(p.getUUID(), true);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Up you get. The court's sitting.", "Time. The judge is waiting.", "Court. Look lively.",
            "On your feet. Your case is called.", "This way, to the court.", "Let's not keep the bench waiting."));
        t.phase = Phase.DOCK;
        t.since = now;
    }

    private static void dock(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, JCase c, ServerPlayer p, long now) {
        if (t.spot != null && t.spot.door != null && now - t.since == 60) WatchHouse.setDoor(level, t.spot.door, false);
        BlockPos dock = t.to;
        if (dock == null) { end(g, p); return; }
        double d = Math.sqrt(g.distanceToSqr(Vec3.atBottomCenterOf(dock)));
        if (d > 2.2) {
            walk(g, dock.relative(net.minecraft.core.Direction.SOUTH), 0.6);
            if (now - t.since > Justice.pause(9600)) {
                Restraint.pullTo(p, dock);
                end(g, p);
            }
            return;
        }
        g.getNavigation().stop();
        Restraint.pullTo(p, dock);
        Restraint.lead(p, null);
        Restraint.pullTo(p, dock);
        end(g, p);
    }

    /** Back to the cells from the court to serve a sentence (or wait). */
    static boolean toCells(ServerLevel level, Villages.Village v, JCase c, ServerPlayer p) {
        VillageFolkEntity g = officerFor(level, v, p.blockPosition());
        Jail.Spot s = Jail.spot(level, v, p.getUUID());
        if (g == null || s == null) {
            Jail.lockUp(level, v, p.getUUID(), c, s, null);
            Restraint.off(p);
            return false;
        }
        Restraint.on(p, g, Jail.irons(level, v) ? Restraint.Kind.CUFFS : Restraint.Kind.ROPE);
        Task t = new Task(c.id, p.getUUID(), Phase.LEAD, level.getGameTime());
        t.spot = s;
        TASKS.put(g.getUUID(), t);
        return true;
    }

    // ------------------------------------------------------------------ meals

    /** A guard sent with a meal to the prisoner's cell. */
    static boolean meal(ServerLevel level, Villages.Village v, ServerPlayer p, int caseId) {
        if (guardOn(p.getUUID()) != null) return false;
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, p.blockPosition(), 96.0, null);
        if (g == null) return false;
        Task t = new Task(caseId, p.getUUID(), Phase.MEAL, level.getGameTime());
        t.spot = Jail.spotOf(p.getUUID());
        TASKS.put(g.getUUID(), t);
        return true;
    }

    private static void meal(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, JCase c, ServerPlayer p, long now) {
        BlockPos front = t.spot == null || t.spot.front == null ? p.blockPosition() : t.spot.front;
        if (g.distanceTo(p) > 3.0 && g.distanceToSqr(Vec3.atBottomCenterOf(front)) > 1.8 * 1.8) {
            walk(g, front, 0.7);
            if (now - t.since > Justice.pause(4800)) end(g, p);
            return;
        }
        g.getNavigation().stop();
        g.getLookControl().setLookAt(p, 30.0F, 30.0F);
        Jail.fed(level, v, p, g);
        end(g, p);
    }

    // ------------------------------------------------------------------ fines unpaid

    /** A fine unpaid past its day: a charge of its own, by summons. */
    static void fines(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        for (JCase c : Justice.cases(v.id())) {
            if (c.stage != Stage.CONVICTED || c.fine <= 0 || c.finePaid || c.fineDue <= 0 || day <= c.fineDue) continue;
            c.fineDue = Long.MAX_VALUE;
            JCase u = Justice.open(level, v.id(), Charge.UNPAID_FINE, c.accused, c.accusedName, c.player, null, "the court", false, c.place, c.where);
            u.evidence.add(new Ev("RECORD", "the court's own book: the fine of " + c.fine + " for " + c.charge.word + " is unpaid", 5.0, true));
            u.stage = Stage.REPORTED;
            u.notes.add("the fine for " + c.title() + " went unpaid");
            Justice.changed();
        }
    }

    // ------------------------------------------------------------------ striking the watch

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof VillageFolkEntity g) || !(e.getSource().getEntity() instanceof ServerPlayer p)) return;
        Task t = TASKS.get(g.getUUID());
        if (t == null || !t.player.equals(p.getUUID())) return;
        JCase c = Justice.get(t.caseId);
        if (c == null || c.extra.contains(Charge.RESISTING) && c.notes.contains("struck the arresting guard")) return;
        addCharge(c, Charge.RESISTING, "struck the arresting guard");
        c.notes.add("struck the arresting guard");
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "That's resisting arrest!", "Hit the watch, would you?", "You'll pay for that in court.",
            "Assaulting an officer. Lovely.", "That's another charge.", "Steady! You're making it worse."));
        p.displayClientMessage(Component.literal("You struck the arresting guard: resisting arrest has been added to your charges.").withStyle(ChatFormatting.RED), false);
    }

    // ------------------------------------------------------------------ tests

    static String taskName(VillageFolkEntity g) {
        Task t = TASKS.get(g.getUUID());
        return t == null ? "" : t.phase.name();
    }
}
