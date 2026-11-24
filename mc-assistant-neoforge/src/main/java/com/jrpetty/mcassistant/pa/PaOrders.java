package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.CraftPlanner;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.pa.PaIntent.Kind;
import com.jrpetty.mcassistant.pa.PaUnderstand.Choice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [pa33] What the Player AI Assistant does with what it is told. A sentence is read (PaUnderstand), anything it
 * could not read is offered to the old chat parser's reader (ChatControl) before it asks back; then each step is
 * answered at once (stop, follow, a question) or added to its plan (PaPlan) as the engine's jobs, with whatever has
 * to come first worked out: a pickaxe before stone, the blocks and the furniture before a house. Long or
 * world-changing work is confirmed first ("Mining down to iron, about 3 minutes. OK?") unless its player turned that off.
 */
public final class PaOrders {

    private PaOrders() {}

    /** A plan waiting for its player's OK. */
    public record Pending(String question, List<Choice> choices, List<PaPlan.Step> steps, String said, long at) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    @Nullable
    public static Pending pending(UUID owner) {
        return PENDING.get(owner);
    }

    /** Where the player was looking when they spoke (sent with every order): a block, an entity, or nothing. */
    public record Look(@Nullable BlockPos block, int entity, List<BlockPos> more) {
        public static final Look NONE = new Look(null, -1);

        public Look(@Nullable BlockPos block, int entity) {
            this(block, entity, List.of());
        }

        /** [pa33c-2b] The k-th place pointed at while it was said ("that, and that": one look each), else this one. */
        public Look nth(int k) {
            if (more.isEmpty() || k == 0) return this;
            return new Look(more.get(Math.min(k, more.size() - 1)), -1, List.of());
        }
    }

    // ------------------------------------------------------------------ hearing

    /** The player said (typed, spoke, clicked a chip) something to their assistant. */
    public static void hear(ServerPlayer p, PlayerAssistantEntity a, String text, Look look, boolean voice) {
        if (text == null || text.isBlank()) return;
        text = text.trim();
        if (text.length() > 240) text = text.substring(0, 240);
        PaData.Rec r = a.rec();
        if (r != null) {
            // [pa35] a password, an address, a phone number: never kept, not even in the talk log
            String logged = PaMemoryParse.secret(text) ? "(something private: not kept)" : text;
            r.say(0, (voice ? "(voice) " : "") + logged, a.level().getGameTime());
            PaData.touch();
        }
        PlayerAssistantEntity before = PaChests.askAs(a);
        try {
            hear0(p, a, voice ? PaWords.voiceFix(text) : text, look);   // [pa33b] a spoken order: the recogniser's slips put right
        } finally {
            PaChests.askAs(before);
        }
        PaNet.refresh(p, a);
    }

    /**
     * [pa35] A standing rule's order, on its trigger (PaMemoryAct): the plain order words through the same reader as
     * anything said, with no question first (its player set the rule) and nothing logged as said by them.
     */
    public static void rule(ServerPlayer p, PlayerAssistantEntity a, String order) {
        PaData.Rec r = a.rec();
        boolean confirm = r != null && r.confirm;
        if (r != null) r.confirm = false;
        PlayerAssistantEntity before = PaChests.askAs(a);
        try {
            PaUnderstand.Reading reading = PaUnderstand.read(order, a.getAssistantName());
            if (reading.understood()) run(p, a, reading.steps(), Look.NONE, order);
        } finally {
            PaChests.askAs(before);
            if (r != null) r.confirm = confirm;
        }
        PaNet.refresh(p, a);
    }

    private static void hear0(ServerPlayer p, PlayerAssistantEntity a, String text, Look look) {
        // [pa33b] match my energy: the attitude comes off before the order is read ("go get my wheat you fucking loser" fetches wheat)
        PaTone.Read tone = PaTone.read(text);
        PaUnderstand.Reading reading = PaUnderstand.read(tone.tone() == PaTone.Tone.RUDE || tone.polite() ? tone.order() : text, a.getAssistantName());
        Pending pend = PENDING.get(p.getUUID());
        if (pend != null) {
            Kind first = reading.steps().isEmpty() ? null : reading.steps().get(0).kind();
            if (first == Kind.YES) {
                PENDING.remove(p.getUUID());
                start(p, a, pend.steps(), pend.said());
                return;
            }
            if (first == Kind.NO || first == Kind.STOP) {
                PENDING.remove(p.getUUID());
                PaTalk.reply(a, PaTalk.line(a, "cancelled"));
                return;
            }
            PENDING.remove(p.getUUID());                // anything else is a new order: the old question lapses
        }
        // [pa35] something to remember, a question about what it remembers, or "forget that": its memory answers first
        if (PaQuips.mute(text, a.rec() != null && a.rec().mute > 0) == null && PaMemoryAct.hear(p, a, tone.tone() == PaTone.Tone.RUDE ? tone.order() : text, look)) return;
        // [pa33b] "shut up" (quiet: answers and warnings only), "no more jokes" (plain words), "you can talk again" (itself)
        PaData.Rec mr = a.rec();
        PaQuips.Mute mu = mr == null ? null : PaQuips.mute(text, mr.mute > 0);
        if (mu != null) {
            boolean order = reading.clarify() == null && reading.steps().stream().anyMatch(st -> st.kind().queued()
                || st.kind() == Kind.FOLLOW || st.kind() == Kind.STAY || st.kind() == Kind.COME || st.kind() == Kind.GUARD_ME);
            hush(a, mu == PaQuips.Mute.ON ? 0 : mu == PaQuips.Mute.JOKES ? 1 : 2);
            if (order) run(p, a, reading.steps(), look, text);
            return;
        }
        // [pa33b] an order said rudely, politely or bluntly: done straight away, then a word back in kind (never while hushed)
        if (mr != null && tone.tone() != PaTone.Tone.NEUTRAL) {
            int before = a.mind.toneHeat.take(tone, a.level().getGameTime());
            boolean order = reading.clarify() == null && reading.steps().stream().anyMatch(st -> st.kind().queued()
                || st.kind() == Kind.FOLLOW || st.kind() == Kind.STAY || st.kind() == Kind.COME || st.kind() == Kind.GUARD_ME);
            if (order) {
                run(p, a, reading.steps(), look, text);
                String key = PaTone.key(tone, before, a.mind.toneHeat.heat(), PaMemoryAct.language(a, PaTone.level(mr.language, PaConfig.maxLanguage())), mr.mute);   // [pa35] clean near those it was told
                String back = key == null ? null : PaBanter.toneLine(a, key, thing(reading));
                if (back != null) PaTalk.reply(a, back);
                return;
            }
        }
        // [pa33b] lip, a kind word, or "tell me a joke": answered in kind. With a real order in it, the order first.
        PaQuips.Comeback cb = PaBanter.comeback(text);
        if (cb != null) {
            boolean order = reading.clarify() == null && reading.steps().stream().anyMatch(st -> st.kind().queued()
                || st.kind() == Kind.FOLLOW || st.kind() == Kind.STAY || st.kind() == Kind.COME || st.kind() == Kind.GUARD_ME);
            if (!order) {
                PaTalk.reply(a, PaBanter.answer(a, cb));
                return;
            }
            run(p, a, reading.steps(), look, text);
            PaTalk.comment(a, 0, PaBanter.answer(a, cb));
            return;
        }
        // [pa33b] the same order again so soon: done again, with a remark
        // [pa33c-2b] only a job asked for again counts ("stop", "stay", "follow me" twice just do it, with a plain reply)
        boolean twice = text.equalsIgnoreCase(a.mind.lastOrder) && a.tickCount - a.mind.lastOrderAt < 2400 && !reading.steps().isEmpty()
            && reading.steps().stream().allMatch(st -> st.kind().queued());
        a.mind.lastOrder = text;
        a.mind.lastOrderAt = a.tickCount;
        a.mind.askedTwice = twice;                       // remarked on after the reply (PaBrain)
        if (reading.clarify() != null) {
            // Before asking back: the old chat reader may know it ("tend the farm every 20 minutes", "patrol between...").
            if (reading.steps().isEmpty() && reading.clarify().question().startsWith("I'm not sure")
                    && com.jrpetty.mcassistant.ChatControl.understood(a, p, text)) {
                PaTalk.reply(a, PaTalk.line(a, "ok"));
                return;
            }
            // [pa33f] a remark it can't place but that reads as chat: a word back in kind, never a menu
            if (reading.steps().isEmpty() && reading.clarify().question().startsWith("I'm not sure") && PaRemarks.chat(text)) {
                String back = PaPersona.remark("comeback.chat", a.tri(), mr == null ? 0 : mr.look, null, a.getRandom()::nextInt, a.mind.recent);
                if (back == null) back = PaPersona.from(PaLines.get("comeback.chat@serious"), a.getRandom()::nextInt, a.mind.recent);
                if (back != null) { PaTalk.reply(a, PaBanter.fill(a, back)); return; }
            }
            // Steps read before the unclear part still run; then the question.
            if (!reading.steps().isEmpty()) run(p, a, reading.steps(), look, text);
            PaTalk.reply(a, reading.clarify().question(), reading.clarify().choices());
            return;
        }
        run(p, a, reading.steps(), look, text);
    }

    /** [pa33c-2b] Where a GOTO goes: the look/ping, where its player fell, its bed, or a named place. */
    @Nullable
    static BlockPos placeFor(ServerPlayer p, PlayerAssistantEntity a, PaIntent i, Look look) {
        PaData.Rec r = a.rec();
        String it = i.item();
        if (it.isEmpty()) return look.block() != null ? look.block().above() : null;
        if (it.equals("death")) return a.mind.deathPos;
        if (it.equals("bed")) return r == null ? null : r.lastBed != null ? r.lastBed : r.bed;
        if (it.startsWith("@") && r != null) {
            String name = it.substring(1);
            BlockPos b = r.waypoints.get(name);
            if (b == null) for (var e : r.waypoints.entrySet()) if (e.getKey().contains(name) || name.contains(e.getKey())) b = e.getValue();
            return b == null ? null : b.above();
        }
        return null;
    }

    /** [pa33c-2b] The head block of the bed at (or beside) this spot, or null. */
    @Nullable
    static BlockPos bedHead(net.minecraft.world.level.Level level, @Nullable BlockPos at) {
        if (at == null) return null;
        for (BlockPos p : new BlockPos[]{at, at.below(), at.above()}) {
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) continue;
            if (s.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) return p.immutable();
            return p.relative(s.getValue(net.minecraft.world.level.block.BedBlock.FACING)).immutable();
        }
        return null;
    }

    /** [pa33c-2b] Is someone else sleeping in it, or is it a player's own spawn bed near them? It never takes a used bed. */
    static boolean bedBusy(PlayerAssistantEntity a, BlockPos head) {
        net.minecraft.world.level.block.state.BlockState s = a.level().getBlockState(head);
        if (s.getBlock() instanceof net.minecraft.world.level.block.BedBlock && s.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)
            && !(a.isSleeping() && a.getSleepingPos().map(head::equals).orElse(false))) return true;
        if (a.level() instanceof net.minecraft.server.level.ServerLevel sl) for (ServerPlayer pl : sl.players()) {
            if (a.isOwner(pl)) continue;                      // its own player may share theirs
            BlockPos sp = pl.getRespawnPosition();
            if (sp != null && pl.getRespawnDimension() == sl.dimension() && (sp.equals(head) || sp.closerThan(head, 1.5))) return true;
        }
        return false;
    }

    /** [pa33c-2b] This bed is its bed, and where it comes back after a fall. */
    static void setBed(PlayerAssistantEntity a, BlockPos head) {
        PaData.Rec r = a.rec();
        if (r != null) {
            r.bed = head;
            r.bedDim = a.level().dimension().location().toString();
            r.lastBed = head;
            r.lastBedDim = r.bedDim;
            PaData.touch();
        }
        a.useBed(head);
    }

    static boolean hasBed(PlayerAssistantEntity a) {
        for (net.minecraft.world.item.ItemStack s : a.getInventoryItems()) if (s.is(net.minecraft.tags.ItemTags.BEDS)) return true;
        return false;
    }

    /** [pa33b] What it was sent for, for "your {thing}": the first order's thing, else "that". */
    static String thing(PaUnderstand.Reading reading) {
        for (PaIntent i : reading.steps()) if (i.item() != null && !i.item().isBlank()) return i.item();
        return "that";
    }

    /** [pa33b] Hush it (1 no jokes, 2 quiet) or lift it (0), with one short line in its own voice; never a sulk. */
    public static void hush(PlayerAssistantEntity a, int level) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        PaPersona.Tri own = r.tri();                       // its own corner, not the muted one, for the acknowledgement
        String key = level == 0 ? "mute_on" : level == 1 ? "mute_jokes" : "mute_all";
        a.setMute(level);
        PaTalk.reply(a, PaPhrases.line(key, own, a.getRandom()::nextInt, a.mind.recent, "owner", a.ownerName().isEmpty() ? "you" : a.ownerName(),
            "name", a.displayNameCap()));
    }

    /** Carry out what was read: answers now, work into the plan. */
    static void run(ServerPlayer p, PlayerAssistantEntity a, List<PaIntent> steps, Look look, String said) {
        List<PaPlan.Step> work = new ArrayList<>();
        List<String> replies = new ArrayList<>();
        int pointed = 0;
        Look whole = look;
        for (PaIntent i : steps) {
            look = i.place() == PaIntent.Place.LOOKED ? whole.nth(pointed++) : whole;   // [pa33c-2b] each "that" its own look
            if (i.kind().queued()) {
                List<PaPlan.Step> made = new ArrayList<>();
                String why = plan(p, a, i, look, made, said);
                if (why != null) {
                    // could not be planned: say why (with the fixes), and stop here so later steps don't run out of order
                    PaTalk.reply(a, why, fixesFor(why, said));
                    if (!work.isEmpty()) break;
                    return;
                }
                work.addAll(made);
            } else {
                String now = now(p, a, i, look);
                if (now != null && !now.isEmpty()) replies.add(now);
            }
        }
        for (String r : replies) PaTalk.reply(a, r);
        if (work.isEmpty()) return;
        PaData.Rec r = a.rec();
        boolean ask = r == null || r.confirm;
        boolean big = work.size() >= 3 || work.stream().anyMatch(s -> confirmable(s));
        if (ask && big) {
            String q = summary(work) + ". About " + time(work.stream().mapToInt(s -> s.eta).sum());
            List<Choice> choices = List.of(new Choice("OK", "yes"), new Choice("Change", "#change " + said), new Choice("Cancel", "no"));
            PENDING.put(p.getUUID(), new Pending(q, choices, work, said, a.level().getGameTime()));
            PaTalk.reply(a, PaTalk.line(a, "confirm", "what", q), choices);
            return;
        }
        start(p, a, work, said);
    }

    private static boolean confirmable(PaPlan.Step s) {
        try {
            Kind k = Kind.valueOf(s.kind);
            // long or far-reaching jobs ask first; a minute's dig at the vein you point at just starts
            boolean big = k == Kind.BUILD || k == Kind.CLEAR_AREA || k == Kind.MINE_DOWN || k == Kind.TRADE_BUY || k == Kind.TRADE_SELL || s.eta >= 150;
            return (k.confirmable() && big) || (k == Kind.GATHER && s.target >= 64);
        } catch (Exception e) {
            return false;
        }
    }

    /** Start the work: anything it was doing on its own gives way; the old list is kept for "undo". */
    static void start(ServerPlayer p, PlayerAssistantEntity a, List<PaPlan.Step> work, String said) {
        a.plan.remember();
        if (a.plan.onlyAuto()) {
            if (a.plan.current != null && a.plan.current.auto) { a.clearQueue(); a.task = null; a.plan.current = null; }
            a.plan.dropAuto();
        }
        for (PaPlan.Step s : work) s.said = said;
        PaCome.end(a);                                                    // [pa33g] work ends a come-here
        a.plan.steps.addAll(work);
        a.plan.paused = false;
        if (a.getMode() == AssistantEntity.Mode.STAY) a.setMode(AssistantEntity.Mode.FOLLOW);   // it has work: it is not standing still
        String what = work.size() == 1 ? cap(work.get(0).label) : work.size() + " steps, starting with: " + work.get(0).label;
        PaTalk.reply(a, PaTalk.line(a, "ok") + " " + what + ".");
    }

    // ------------------------------------------------------------------ answers at once

    @Nullable
    static String now(ServerPlayer p, PlayerAssistantEntity a, PaIntent i, Look look) {
        PaPhrases.Personality pers = a.personality();
        PaPhrases.Dice rnd = a.getRandom()::nextInt;
        switch (i.kind()) {
            case FOLLOW -> {
                PaCome.end(a);                                            // [pa33g] another order ends a come-here
                a.mind.post = null;
                a.setMode(AssistantEntity.Mode.FOLLOW);
                return PaTalk.line(a, "follow");
            }
            case STAY -> {
                PaCome.end(a);                                            // [pa33g] another order ends a come-here
                a.mind.post = null;
                a.getNavigation().stop();
                a.setMode(AssistantEntity.Mode.STAY);
                return PaTalk.line(a, "stay");
            }
            case COME -> {
                return PaCome.start(a, p);                                // [pa33g] on foot the whole way, running while it's far
            }
            case GUARD_ME -> {
                PaCome.end(a);                                            // [pa33g] another order ends a come-here
                a.mind.post = null;
                a.setMode(AssistantEntity.Mode.GUARD);
                a.equipBestWeapon();
                return PaTalk.line(a, "guard");
            }
            case GUARD_HERE -> {
                PaCome.end(a);                                            // [pa33g] another order ends a come-here
                BlockPos at = where(p, a, i.place(), look);
                a.mind.post = at;
                a.setMode(AssistantEntity.Mode.GUARD);
                a.equipBestWeapon();
                return "I'll guard " + (i.item().equals("here") ? "this spot" : "the " + i.item()) + ". Nothing gets past me.";
            }
            case FIGHT -> {
                PaCome.end(a);                                            // [pa33g] another order ends a come-here
                LivingEntity target = null;
                if (look.entity() >= 0 && a.level().getEntity(look.entity()) instanceof LivingEntity le && le != a && !(le instanceof ServerPlayer)) target = le;
                if (target == null) {
                    double best = 24 * 24;
                    for (Monster m : a.level().getEntitiesOfClass(Monster.class, a.getBoundingBox().inflate(24))) {
                        if (!m.isAlive()) continue;
                        if (!i.item().equals("monsters") && !m.getType().toShortString().contains(i.item())) continue;
                        double d = m.distanceToSqr(p);
                        if (d < best) { best = d; target = m; }
                    }
                }
                if (target == null) return "I don't see anything to fight. I'll keep watch.";
                a.setMode(AssistantEntity.Mode.GUARD);
                a.equipBestWeapon();
                a.setTarget(target);
                return "Going after the " + target.getName().getString().toLowerCase() + "!";
            }
            case WAYPOINT -> {                                     // [pa33c-2b] "remember this spot as the mine"
                BlockPos at = look.block() != null ? look.block() : p.blockPosition();
                PaData.Rec r = a.rec();
                if (r == null) return null;
                if (r.waypoints.size() >= 32 && !r.waypoints.containsKey(i.item())) return "I can only remember 32 places. Forget one first.";
                r.waypoints.put(i.item(), at.immutable());
                r.waypointDims.put(i.item(), p.level().dimension().location().toString());
                PaData.touch();
                PaNet.ping(p, at, i.item(), "MOVE", "waypoint");
                return PaTalk.line(a, "waypoint_saved", "name", i.item());
            }
            case MARK -> {                                         // "ping that": a marker, no order
                BlockPos at = look.block();
                if (at == null) return "Look at something and say it again.";
                PaNet.ping(p, at, "Look here", "LOOK", "mark");
                return null;
            }
            case FIND -> {                                         // "ping the nearest iron", "find me a tree"
                BlockPos found = PaSeek.nearest(p.serverLevel(), p.blockPosition(), i.item());
                if (found == null) return PaTalk.line(a, "find_none", "what", PaSeek.label(i.item()));
                double dx = found.getX() + 0.5 - p.getX(), dz = found.getZ() + 0.5 - p.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                PaNet.ping(p, found, PaNet.cap(PaSeek.label(i.item())), "GATHER", "found");
                return PaTalk.line(a, "find_found", "what", PaSeek.label(i.item()), "dist", com.jrpetty.mcassistant.pa.voice.PaSpeech.words(dist),
                    "dir", com.jrpetty.mcassistant.pa.voice.PaSpeech.bearing(dx, dz));
            }
            case PING_EDIT -> {                                    // cancel, clear, skip, do one first
                String op = i.item();
                if (op.equals("clear")) { PaNet.ping(p, null, "", "", "clear"); return PaTalk.line(a, "pings_cleared"); }
                if (op.equals("cancel")) {                         // the last ping's marker and the job it made, if still waiting
                    PaNet.ping(p, null, "", "", "unlast");
                    if (!a.plan.steps.isEmpty()) a.plan.steps.remove(a.plan.steps.size() - 1);
                    else if (a.plan.current != null && !a.plan.current.auto) { a.plan.current = null; a.clearQueue(); a.task = null; }
                    return PaTalk.line(a, "ping_cancelled");
                }
                if (op.equals("skip")) {                           // the job under way (or the next) is dropped
                    if (a.plan.current != null && !a.plan.current.auto) { a.plan.current = null; a.clearQueue(); a.task = null; }
                    else if (!a.plan.steps.isEmpty()) a.plan.steps.remove(0);
                    else return "There's nothing to skip.";
                    return PaTalk.line(a, "ping_skipped");
                }
                if (op.startsWith("first:")) {                     // that one next; the one under way waits behind it
                    String w = op.substring(6);
                    String also = w.matches("trees?|wood|logs?") ? "log" : w.matches("ores?") ? "mine" : w;
                    for (int k = 0; k < a.plan.steps.size(); k++) {
                        PaPlan.Step st = a.plan.steps.get(k);
                        String l = st.label.toLowerCase(java.util.Locale.ROOT);
                        if (l.contains(w) || l.contains(also) || (also.equals("log") && (l.contains("chop") || l.contains("tree")))) {
                            if (k == 0 && a.plan.current == null) return PaTalk.line(a, "ping_reordered", "job", st.label);
                            a.plan.steps.remove(k);
                            PaPlan.Step cur = a.plan.current;
                            if (cur != null && !cur.auto) { a.plan.current = null; a.clearQueue(); a.task = null; cur.fed = false; cur.started = -1; a.plan.steps.add(0, cur); }
                            a.plan.steps.add(0, st);
                            return PaTalk.line(a, "ping_reordered", "job", st.label);
                        }
                    }
                    return "I haven't got a job like that in my list.";
                }
                return null;
            }
            case BED_USE -> {                                      // [pa33c-2b] "use this bed": its spawn from now on
                BlockPos head = bedHead(a.level(), look.block());
                if (head == null) return PaTalk.line(a, "bed_point");
                if (bedBusy(a, head)) return PaTalk.line(a, "bed_busy");
                setBed(a, head);
                return PaTalk.line(a, "bed_set");
            }
            case BED_WHERE -> {
                PaData.Rec r = a.rec();
                BlockPos b = r == null ? null : r.lastBed != null ? r.lastBed : r.bed;
                if (b == null) return PaTalk.line(a, "bed_none");
                double dx = b.getX() + 0.5 - p.getX(), dz = b.getZ() + 0.5 - p.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                return PaTalk.line(a, "bed_where", "x", "" + b.getX(), "y", "" + b.getY(), "z", "" + b.getZ(), "dist", "" + dist,
                    "dir", com.jrpetty.mcassistant.pa.voice.PaSpeech.bearing(dx, dz));
            }
            case SET_HOME -> {
                BlockPos at = where(p, a, Kind.SET_HOME == i.kind() ? PaIntent.Place.HERE : i.place(), look);
                a.setHome(at);
                return "Home is here now.";
            }
            case STOP -> {
                PaCome.end(a);                                            // [pa33g] another order ends a come-here
                stopAll(a);
                return PaTalk.line(a, "stop");
            }
            case UNDO -> {
                a.clearQueue();
                a.task = null;
                if (a.plan.undo()) return PaTalk.line(a, "undo");
                return "There's nothing to undo.";
            }
            case PAUSE -> {
                a.plan.paused = true;
                a.clearQueue();
                if (a.plan.current != null) { a.plan.current.fed = false; a.plan.steps.add(0, a.plan.current); a.plan.current = null; }
                a.getNavigation().stop();
                return PaTalk.line(a, "pause");
            }
            case RESUME -> {
                if (!a.plan.paused && a.plan.idle()) return "I've nothing to get back to. What next?";
                a.plan.paused = false;
                return PaTalk.line(a, "resume");
            }
            case AUTO_ON, AUTO_OFF -> {
                PaData.Rec r = a.rec();
                if (r != null) { r.autoHelp = i.kind() == Kind.AUTO_ON; PaData.touch(); }
                if (i.kind() == Kind.AUTO_OFF) { a.plan.dropAuto(); if (a.plan.current != null && a.plan.current.auto) { a.clearQueue(); a.plan.current = null; } }
                return i.kind() == Kind.AUTO_ON ? "Auto-help is on: when you've nothing for me I'll keep the base tidy, the furnace going and the farm picked, and tell you what I'm up to."
                    : "Auto-help is off. I'll only do what you ask.";
            }
            case SLEEP -> {                                       // [pa33d] to bed now (at night), or at dark
                a.setNightHome(true);
                return PaSleep.goToBed(a);
            }
            case WAKE -> { return PaSleep.wakeUp(a); }
            case RENAME -> {
                PaLife.rename(a, i.item());
                return "Call me " + a.displayNameCap() + " from now on.";
            }
            case STATUS -> {
                int hp = (int) Math.ceil(a.getHealth());
                String feel = hp >= a.getMaxHealth() * 0.8 ? "I'm feeling good" : hp >= a.getMaxHealth() * 0.4 ? "I'm a bit bruised" : "I'm badly hurt";
                return feel + ": " + hp + "/" + (int) a.getMaxHealth() + " health, " + a.countFood() + " food in my pack, level " + a.veteranLevel() + ".";
            }
            case DOING -> {
                return doing(a);
            }
            case INVENTORY -> {
                List<String> parts = new ArrayList<>();
                java.util.Map<String, Integer> pack = new java.util.LinkedHashMap<>();
                for (var s : a.getInventoryItems()) if (!s.isEmpty()) pack.merge(s.getHoverName().getString().toLowerCase(), s.getCount(), Integer::sum);
                pack.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6).forEach(e -> parts.add(e.getValue() + " " + e.getKey()));
                return parts.isEmpty() ? "My pack's empty. Open the Pack tab to give me things." : "I'm carrying " + String.join(", ", parts) + ".";
            }
            case NEEDS -> {
                List<String> needs = new ArrayList<>();
                if (!PaItems.hasPickaxe(a)) needs.add("a pickaxe");
                if (!PaItems.hasAxe(a)) needs.add("an axe");
                if (a.countFood() < 4) needs.add("some food");
                if (a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()) needs.add("armour");
                return needs.isEmpty() ? "I've got everything I need, thanks." : "I could use " + String.join(", ", needs) + ". I can make most of it myself if you ask.";
            }
            case WHERE -> {
                if (a.level() != p.level()) return "I'm in the " + a.level().dimension().location().getPath() + ".";
                int d = (int) Math.sqrt(a.distanceToSqr(p));
                return d < 6 ? "Right here beside you." : "About " + d + " blocks " + bearing(p.position(), a.position()) + " of you.";
            }
            case HELP -> {
                return "Talk to me like a person: \"follow me\", \"get me 16 logs\", \"mine iron until you have 20\", \"build a small house here\", "
                    + "\"farm this field\", \"guard me\", \"put everything in that chest\", \"bring me food\". Chain them with \"then\". "
                    + "The Orders tab has buttons for all of it, and you can hold the talk key and just say it.";
            }
            case QUESTION -> {
                // Answered from the Guide, which lives on the player's client: ask it to answer as this assistant.
                net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
                t.putInt("id", a.getId());
                t.putString("q", i.item());
                PaNet.send(p, PaPayload.GUIDE, t);
                return "";
            }
            case CHALLENGE -> { return PaChallenge.start(p, a, i.item(), look); }     // [pa33d] race me, mining, shooting
            case GREETING -> {
                return PaTalk.line(a, "hello", "owner", p.getGameProfile().getName());
            }
            case THANKS -> {
                return PaTalk.line(a, "thanks", "owner", p.getGameProfile().getName());
            }
            case BYE -> {
                return PaTalk.line(a, "bye", "owner", p.getGameProfile().getName());
            }
            case YES -> {
                return "Yes? Tell me what you'd like.";
            }
            case NO -> {
                return "Alright.";
            }
            default -> {
                return null;
            }
        }
    }

    /** [pa33e] The creature an order names ("that pig", "the cart") nearest the player's line of sight, within 32 blocks and 16 degrees. */
    @Nullable
    static net.minecraft.world.entity.Entity named(ServerPlayer p, String raw) {
        String t = raw.toLowerCase(java.util.Locale.ROOT);
        java.util.function.Predicate<net.minecraft.world.entity.Entity> kind =
            t.matches(".*\\bpig\\b.*") ? e -> e instanceof net.minecraft.world.entity.animal.Pig
            : t.matches(".*\\bcow\\b.*") ? e -> e instanceof net.minecraft.world.entity.animal.Cow
            : t.matches(".*\\bsheep\\b.*") ? e -> e instanceof net.minecraft.world.entity.animal.Sheep
            : t.matches(".*\\bchicken\\b.*") ? e -> e instanceof net.minecraft.world.entity.animal.Chicken
            : t.matches(".*\\bhorse\\b.*") ? e -> e instanceof net.minecraft.world.entity.animal.horse.AbstractHorse
            : t.matches(".*\\b(minecart|cart)\\b.*") ? e -> e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart : null;
        if (kind == null) return null;
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition(), dir = p.getViewVector(1.0F);
        net.minecraft.world.entity.Entity best = null;
        double bestAngle = Math.cos(Math.toRadians(16));
        for (net.minecraft.world.entity.Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(32), e -> e.isAlive() && kind.test(e))) {
            net.minecraft.world.phys.Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double c = to.normalize().dot(dir);
            if (c > bestAngle) { bestAngle = c; best = e; }
        }
        return best;
    }

    /** Stop everything at once: no more work, no target, standing still. */
    public static void stopAll(PlayerAssistantEntity a) {
        a.plan.clear();
        a.task = null;
        a.mind.post = null;
        a.clearQueue();
        a.setTarget(null);
        a.getNavigation().stop();
        a.setMode(AssistantEntity.Mode.STAY);
    }

    static String doing(PlayerAssistantEntity a) {
        PaPlan.Step s = a.plan.current;
        if (a.plan.paused) return "I'm paused with " + a.plan.total() + " job" + (a.plan.total() == 1 ? "" : "s") + " waiting. Say resume when you're ready.";
        if (s != null) {
            String tally = s.tally(a);
            String more = a.plan.steps.isEmpty() ? "" : ", then " + a.plan.steps.size() + " more";
            return "I'm " + PaBrain.ing(s.label) + (tally.isEmpty() ? "" : " (" + tally + ")") + more + (s.auto ? ", since you'd nothing for me" : "") + ".";
        }
        if (a.getTarget() != null) return "Fighting a " + a.getTarget().getName().getString().toLowerCase() + "!";
        if (a.mind.post != null) return "Guarding my post.";
        return switch (a.getMode()) {
            case FOLLOW -> "Following you. Give me something to do!";
            case GUARD -> "Guarding you.";
            case STAY -> "Waiting here for orders.";
        };
    }

    // ------------------------------------------------------------------ work into steps

    /**
     * Turn one queued order into steps (more than one when something must come first). Returns null when planned,
     * or why it cannot be (an honest reason the player is told, with fixes offered).
     */
    @Nullable
    static String plan(ServerPlayer p, PlayerAssistantEntity a, PaIntent i, Look look, List<PaPlan.Step> out, String said) {
        int n = i.amount();
        switch (i.kind()) {
            case GATHER -> {
                String what = i.item();
                String noOre = PaMineLock.refusal(a, what);                // [pa35] the mining ladder
                if (noOre != null) return noOre;
                GatherGoal.Kind k = PaItems.kind(what);
                if (k == null) {
                    if (what.equals("clay") || what.equals("wool") || what.equals("flowers") || what.equals("saplings")
                        || what.equals("leather") || what.equals("string") || what.equals("seeds")) {
                        return "I can't gather " + what + " on my own yet. Logs, stone, dirt, sand, gravel, iron, coal and sugar cane I can.";
                    }
                    return "I don't know how to gather " + what + ".";
                }
                if (k.needsProperTool() && !PaItems.hasPickaxe(a)) needPickaxe(a, out);
                if (k == GatherGoal.Kind.OBSIDIAN && !hasDiamondPick(a)) return "Obsidian needs a diamond pickaxe, and I haven't one.";
                int amount = n > 0 ? n : 16;
                // [pa33c-2b] "chop that" at a tree you point at or pinged: it walks there and fells that one
                BlockPos lookedLog = look.block() == null ? null : treeLog(a, look.block());   // [pa33d] the leaves mean their tree
                if (i.place() == PaIntent.Place.LOOKED && lookedLog != null && k == GatherGoal.Kind.LOGS) {
                    int logs = 0;
                    BlockPos b = lookedLog;
                    while (logs < 32 && a.level().getBlockState(b.below()).is(net.minecraft.tags.BlockTags.LOGS)) b = b.below();
                    for (BlockPos c = b; logs < 32 && a.level().getBlockState(c).is(net.minecraft.tags.BlockTags.LOGS); c = c.above()) logs++;
                    amount = n > 0 ? n : Math.max(3, logs);
                    PaPlan.Step s = step("chop that tree", Kind.GATHER, eta(k, amount) + 10);
                    BlockPos stand = b;                                         // beside the trunk, not in it
                    double sd = Double.MAX_VALUE;
                    for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos q = b.relative(dir);
                        if (!a.level().getBlockState(q).getCollisionShape(a.level(), q).isEmpty()) continue;
                        double d = q.distSqr(a.blockPosition());
                        if (d < sd) { sd = d; stand = q; }
                    }
                    s.jobs.add(Job.goTo(stand.getX() + " " + stand.getY() + " " + stand.getZ()));
                    s.jobs.add(Job.gather(k, amount));
                    s.count = what;
                    s.target = amount;
                    out.add(s);
                    return null;
                }
                PaPlan.Step s = step("gather " + amount + " " + k.label, Kind.GATHER, eta(k, amount));
                s.jobs.add(Job.gather(k, amount));
                s.count = what;
                s.target = amount;
                out.add(s);
                // "get me 16 logs": they are for you, so it brings them over when it has them
                if (i.raw() != null && i.raw().toLowerCase().matches(".*\\b(get|bring|fetch|grab|collect|chop|mine|dig) me\\b.*")) {
                    PaPlan.Step give = step("bring you " + amount + " " + what, Kind.GIVE, 15);
                    give.jobs.add(Job.give(what, amount));
                    out.add(give);
                }
                return null;
            }
            case MINE_ORE -> {
                String ore = i.item();
                // [pa35] the mining ladder: an ore above its level it won't touch, and says how far off it is
                if (look.block() != null && (ore.equals("vein") || i.place() == PaIntent.Place.LOOKED)) {
                    net.minecraft.world.level.block.state.BlockState ls = a.level().getBlockState(look.block());
                    if (!PaLevels.canMine(a, ls)) { String no = PaMineLock.refusal(a, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ls.getBlock()).getPath().replace("deepslate_", "").replace("_ore", "")); if (no != null) return no; }
                }
                String noOre = PaMineLock.refusal(a, ore);
                if (noOre != null) return noOre;
                if (!PaItems.hasPickaxe(a)) needPickaxe(a, out);
                GatherGoal.Kind seen = look.block() == null ? null : kindAt(a, look.block());
                boolean looking = seen != null && !ore.equals("vein") && seen.label.toLowerCase().contains(ore);   // "mine iron", eyes on iron ore
                if (ore.equals("vein") || i.place() == PaIntent.Place.LOOKED || looking) {
                    // the vein looked at: gather that ore round it
                    BlockPos b = look.block();
                    GatherGoal.Kind k = b == null ? null : kindAt(a, b);
                    if (k == null) return "Look at the ore you want me to mine, then ask again.";
                    PaPlan.Step s = step("mine the " + k.label + " vein", Kind.MINE_ORE, 60);
                    s.jobs.add(Job.gather(k, n > 0 ? n : 16));
                    s.count = k == GatherGoal.Kind.IRON ? "iron" : k == GatherGoal.Kind.COAL ? "coal" : "stone";
                    s.target = n > 0 ? n : 8;
                    out.add(s);
                    return null;
                }
                int y = PaItems.oreY(ore);
                if ((ore.equals("diamond") || ore.equals("redstone") || ore.equals("gold") || ore.equals("lapis")) && !hasIronPick(a)) {
                    PaPlan.Step pick = step("make an iron pickaxe", Kind.CRAFT, 120);
                    CraftPlanner.Result cr = CraftPlanner.plan(a, "iron_pickaxe", 1);
                    if (!cr.blockers().isEmpty()) return ore + " needs an iron pickaxe, and I'm missing " + String.join(", ", cr.blockers()) + " to make one.";
                    pick.jobs.addAll(cr.jobs());
                    if (!pick.jobs.isEmpty()) out.add(pick);
                }
                int amount = n > 0 ? n : (ore.equals("diamond") ? 3 : 8);
                PaPlan.Step s = step("mine for " + ore + (n > 0 ? " until I have " + amount : ""), Kind.MINE_ORE, 180 + Math.max(0, 64 - y));
                s.jobs.add(Job.mine(y));
                s.count = ore;
                s.target = amount;
                s.stopAtTarget = true;
                out.add(s);
                return null;
            }
            case MINE_DOWN -> {
                if (!PaItems.hasPickaxe(a)) needPickaxe(a, out);
                int y = n == 0 ? 12 : n;
                PaPlan.Step s = step("dig a mine down to Y " + y, Kind.MINE_DOWN, 120 + Math.max(0, (int) a.getY() - y) * 2);
                s.jobs.add(Job.mine(y));
                out.add(s);
                return null;
            }
            case FARM -> {
                BlockPos at = i.place() == PaIntent.Place.NONE ? null : where(p, a, i.place(), look);
                if (at != null && PaSafety.offLimits(a, at)) return "That's " + PaSafety.whose(a, at) + "'s ground. I won't farm there without leave.";
                if ("field".equals(i.item()) || (at != null && !farmlandNear(a, at))) {
                    if (at == null) at = p.blockPosition();
                    if (!hasHoe(a)) {
                        PaPlan.Step hoe = step("make a hoe", Kind.CRAFT, 30);
                        CraftPlanner.Result cr = CraftPlanner.plan(a, "wooden_hoe", 1);
                        if (!cr.blockers().isEmpty()) return "I need a hoe for a field, and I'm missing " + String.join(", ", cr.blockers()) + ".";
                        hoe.jobs.addAll(cr.jobs());
                        if (!hoe.jobs.isEmpty()) out.add(hoe);
                    }
                    PaPlan.Step s = step("lay out a field", Kind.FARM, 90);
                    s.task = new PaTask(PaTask.Kind.FIELD, at, "wheat", 0);
                    out.add(s);
                    return null;
                }
                if (!hasHoe(a)) {
                    // a player's hoe makes short work of a field: one first, if it can be had
                    CraftPlanner.Result cr = CraftPlanner.plan(a, "wooden_hoe", 1);
                    if (cr.blockers().isEmpty() && !cr.jobs().isEmpty()) {
                        PaPlan.Step hoe = step("make a hoe", Kind.CRAFT, 30);
                        hoe.jobs.addAll(cr.jobs());
                        out.add(hoe);
                    }
                }
                PaPlan.Step s = step("tend the farm", Kind.FARM, 60);
                s.jobs.add(Job.farm());
                out.add(s);
                return null;
            }
            case BUILD -> {
                return planBuild(p, a, i, look, out);
            }
            case CRAFT -> {
                String what = i.item();
                if (what.equals("charcoal")) {
                    PaPlan.Step s = step("make " + Math.max(1, n) + " charcoal", Kind.SMELT, 10 * Math.max(4, n));
                    s.jobs.add(Job.smelt("logs", n > 0 ? n : 8));
                    out.add(s);
                    return null;
                }
                String target = CraftPlanner.matchTarget(bestOf(a, what));
                if (target == null) return "I don't know how to make " + what + ".";
                int amount = n > 0 ? n : 1;
                CraftPlanner.Result cr = CraftPlanner.plan(a, target, amount);
                if (!cr.blockers().isEmpty()) {
                    return "I can't make " + CraftPlanner.pretty(target) + ": I'm missing " + String.join(", ", cr.blockers())
                        + ", and I can't gather it. Put some in a chest I'm allowed to use, or hand it to me.";
                }
                PaPlan.Step s = step("make " + (amount > 1 ? amount + " " : "") + CraftPlanner.pretty(target), Kind.CRAFT,
                    Math.max(10, cr.jobs().stream().mapToInt(PaOrders::jobEta).sum()));
                s.jobs.addAll(cr.jobs());
                s.count = PaItems.itemKey(target);
                s.target = s.count.isEmpty() ? 0 : amount;
                if (s.jobs.isEmpty()) {
                    // already has it: nothing to make
                    if (i.place() == PaIntent.Place.NONE) return null;
                }
                if (!s.jobs.isEmpty()) out.add(s);
                if (i.place() != PaIntent.Place.NONE) {
                    BlockPos at = where(p, a, i.place(), look);
                    if (PaSafety.offLimits(a, at)) return "That's " + PaSafety.whose(a, at) + "'s ground. I won't build there without leave.";
                    PaPlan.Step put = step("put the " + CraftPlanner.pretty(target) + " down", Kind.CRAFT, 15);
                    put.task = new PaTask(PaTask.Kind.PLACE, at, what, 1);
                    out.add(put);
                }
                return null;
            }
            case SMELT -> {
                String what = i.item();
                if (what.equals("food") || what.equals("meat")) what = rawMeatCarried(a);
                if (what.equals("ore")) what = PaItems.count(a, "iron") > 0 ? "iron" : "gold";
                if (what.equals("it") || what.isEmpty()) what = "iron";
                if (what.equals("cobblestone")) what = "stone";
                int amount = n > 0 ? n : 8;
                PaPlan.Step s = step("smelt " + amount + " " + what, Kind.SMELT, 10 * amount + 15);
                s.jobs.add(Job.smelt(what, amount));
                out.add(s);
                return null;
            }
            case DEPOSIT -> {
                PaPlan.Step s = step("put my things away", Kind.DEPOSIT, 20);
                if (i.place() == PaIntent.Place.LOOKED && look.block() != null && isContainer(a, look.block())) {
                    point(a, look.block());
                    s.jobs.add(Job.depositAt(look.block()));
                    s.label = "put my things in that chest";
                } else {
                    s.jobs.add(Job.deposit());
                }
                out.add(s);
                return null;
            }
            case FETCH -> {
                int amount = n > 0 ? n : 16;
                PaPlan.Step s = step("fetch " + amount + " " + i.item() + " from the chest", Kind.FETCH, 25);
                if (i.place() == PaIntent.Place.LOOKED && look.block() != null && isContainer(a, look.block())) {
                    point(a, look.block());
                    s.jobs.add(Job.withdrawAt(i.item(), amount, look.block(), 8));
                } else s.jobs.add(Job.withdraw(i.item(), amount));
                s.jobs.add(Job.give(i.item(), amount));
                out.add(s);
                return null;
            }
            case GOTO -> {                                         // [pa33c-2b] a ping, a waypoint, where it died, its bed
                BlockPos to = placeFor(p, a, i, look);
                if (to == null) return i.item().startsWith("@") ? "I don't know a place called \"" + i.item().substring(1) + "\". Say \"remember this spot as "
                    + i.item().substring(1) + "\" there first." : i.item().equals("death") ? "I don't know where you fell." : i.item().equals("bed") ? PaTalk.line(a, "bed_none")
                    : "Where? Look at the spot, or ping it, and say it again.";
                String name = i.item().isEmpty() ? "there" : i.item().equals("death") ? "where you fell" : i.item().equals("bed") ? "my bed" : i.item().substring(1);
                PaPlan.Step s = step("go to " + name, Kind.GOTO, 30);
                s.jobs.add(Job.goTo(to.getX() + " " + to.getY() + " " + to.getZ()));
                out.add(s);
                return null;
            }
            // ---------------------------------------------------------------- [pa33d] the rest of the game, by level
            case ENCHANT -> {
                String lock = locked(a, "enchant");
                if (lock != null) return lock;
                if (a.countMatching(st -> st.is(net.minecraft.world.item.Items.LAPIS_LAZULI)) == 0) return PaTalk.line(a, "need_lapis");
                PaPlan.Step s = step("enchant my " + i.item(), Kind.ENCHANT, 40);
                s.jobs.add(Job.enchant(i.item().equals("gear") || i.item().equals("items") || i.item().equals("stuff") || i.item().equals("things") ? "gear" : i.item()));
                out.add(s);
                return null;
            }
            case NETHER -> {
                String lock = locked(a, "nether");
                if (lock != null) return lock;
                if (i.raw() != null && i.raw().toLowerCase().matches(".*\\b(come|follow|go|head|let'?s go)\\b.*nether.*") && !i.raw().toLowerCase().matches(".*\\b(get|gather|collect|mine|fetch|bring)\\b.*")) {
                    a.setMode(AssistantEntity.Mode.FOLLOW);           // through the portal at your heels
                    a.mind.post = null;                                // no step: nothing to "finish", it just comes along
                    PaTalk.reply(a, PaTalk.line(a, "nether_with"));
                    return null;
                }
                int amount = n > 0 ? n : 16;
                PaPlan.Step s = step("fetch " + amount + " " + i.item() + " from the Nether", Kind.NETHER, 240);
                s.jobs.add(Job.nether(i.item(), amount));
                out.add(s);
                return null;
            }
            case TAME -> {
                String lock = locked(a, "tame");
                if (lock != null) return lock;
                BlockPos at = look.entity() >= 0 && p.level().getEntity(look.entity()) != null ? p.level().getEntity(look.entity()).blockPosition() : where(p, a, i.place(), look);
                PaPlan.Step s = step("tame the " + (i.item().equals("any") ? "animal" : i.item()), Kind.TAME, 30);
                s.task = new PaTask(PaTask.Kind.TAME, at, i.item(), look.entity());
                out.add(s);
                return null;
            }
            case VTRADE -> {
                String lock = locked(a, "trade");
                if (lock != null) return lock;
                BlockPos at = look.entity() >= 0 && p.level().getEntity(look.entity()) != null ? p.level().getEntity(look.entity()).blockPosition() : where(p, a, i.place(), look);
                String[] bs = i.item().split(":", 2);
                PaPlan.Step s = step((bs[0].equals("sell") ? "sell " : "buy ") + bs[1].replace("anything", "something") + (bs[0].equals("sell") ? " to" : " from") + " the villager", Kind.VTRADE, 25);
                s.task = new PaTask(PaTask.Kind.VTRADE, at, i.item(), n);
                out.add(s);
                return null;
            }
            case BREW -> {
                String lock = locked(a, "brew");
                if (lock != null) return lock;
                PaPlan.Step s = step("brew " + i.item() + " potions", Kind.BREW, 60);
                s.task = new PaTask(PaTask.Kind.BREW, look.block() != null ? look.block() : a.blockPosition(), i.item(), 3);
                out.add(s);
                return null;
            }
            case UPGRADE -> {
                String lock = locked(a, "upgrade");
                if (lock != null) return lock;
                List<String> want = PaGear.upgrades(a);
                if (want.isEmpty()) return PaTalk.line(a, "upgrade_none");
                for (String w : want) {
                    String why = plan(p, a, new PaIntent(Kind.CRAFT, w, 1, PaIntent.Place.NONE, said), look, out, said);
                    if (why != null && out.isEmpty()) return why;
                }
                PaPlan.Step eq = step("put on my new gear", Kind.UPGRADE, 5);
                eq.task = new PaTask(PaTask.Kind.EQUIP, null, "", 0);
                out.add(eq);
                return null;
            }
            case PLACE_THING -> {
                String lock = locked(a, "redstone");
                if (lock != null) return lock;
                return plan(p, a, new PaIntent(Kind.CRAFT, i.item(), 1, i.place() == PaIntent.Place.NONE ? PaIntent.Place.LOOKED : i.place(), said), look, out, said);
            }
            case MAP -> {
                String lock = locked(a, "explore");
                if (lock != null) return lock;
                PaPlan.Step s = step("map the area", Kind.MAP, 90);
                s.task = new PaTask(PaTask.Kind.MAP, p.blockPosition(), "", 0);
                out.add(s);
                return null;
            }
            case PILLAR -> {
                String lock = locked(a, "bridge");
                if (lock != null) return lock;
                PaPlan.Step s = step("pillar up " + Math.max(2, Math.min(24, n)) + " blocks", Kind.PILLAR, 10 + n * 2);
                s.task = new PaTask(PaTask.Kind.PILLAR, null, "", Math.max(2, Math.min(24, n)));
                out.add(s);
                return null;
            }
            case SHOOT_AT -> {
                String lock = locked(a, "trick");
                if (lock != null) return lock;
                net.minecraft.world.entity.Entity mover = look.entity() >= 0 ? p.level().getEntity(look.entity()) : null;
                if (mover == null && i.raw() != null) mover = named(p, i.raw());       // "shoot that pig" with the crosshair just off it
                if (mover != null && !mover.getPassengers().isEmpty()) mover = mover.getPassengers().get(0);   // the pig in the cart, not the cart
                if (mover != null) {                                       // [pa33e] something on the move: led, as a player leads it
                    PaPlan.Step s = step("a trick shot at the " + mover.getName().getString().toLowerCase(java.util.Locale.ROOT), Kind.SHOOT_AT, 10);
                    s.task = new PaTask(PaTask.Kind.SHOOT, mover.blockPosition(), "e:" + mover.getId(), 3);
                    out.add(s);
                    return null;
                }
                if (look.block() == null) return "Look at the thing to hit, or ping it, and say it again.";
                PaPlan.Step s = step("a trick shot", Kind.SHOOT_AT, 10);
                s.task = new PaTask(PaTask.Kind.SHOOT, look.block(), "", 3);
                out.add(s);
                return null;
            }
            case BRIDGE -> {
                String lock = locked(a, "bridge");
                if (lock != null) return lock;
                PaPlan.Step s = step("build a bridge", Kind.BRIDGE, 90);
                BlockPos at = where(p, a, i.place(), look);
                if (at != null) s.jobs.add(Job.goTo(at.getX() + " " + at.getY() + " " + at.getZ()));
                s.jobs.add(Job.bridge());
                out.add(s);
                return null;
            }
            case BED_PLACE -> {                                    // [pa33c-2b] "place a bed here": one from its pack, or made first
                BlockPos at = where(p, a, i.place() == PaIntent.Place.NONE ? PaIntent.Place.HERE : i.place(), look);
                if (!hasBed(a)) {                                   // made from scratch, as "craft a white bed" would be
                    String why = plan(p, a, new PaIntent(Kind.CRAFT, "white bed", 1, PaIntent.Place.NONE, said), look, out, said);
                    if (why != null) return "I've no bed and can't make one: " + why;
                }
                PaPlan.Step put = step("put my bed down", Kind.BED_PLACE, 20);
                put.task = new PaTask(PaTask.Kind.PLACE, at, "bed", 1);
                out.add(put);
                return null;
            }
            case GIVE -> {
                String what = i.item().equals("it") ? lastThing(a, out) : i.item();
                int amount = n > 0 ? n : (a.countMatching(PaItems.of(what)) > 0 ? Math.min(64, a.countMatching(PaItems.of(what))) : 16);
                if (a.countMatching(PaItems.of(what)) < amount && out.isEmpty()) {
                    // it hasn't got it: get it first, the way it would for itself
                    GatherGoal.Kind k = PaItems.kind(what);
                    if (k != null) {
                        if (k.needsProperTool() && !PaItems.hasPickaxe(a)) needPickaxe(a, out);
                        PaPlan.Step g = step("gather " + amount + " " + k.label, Kind.GATHER, eta(k, amount));
                        g.jobs.add(Job.gather(k, amount));
                        g.count = what;
                        g.target = amount;
                        out.add(g);
                    } else {
                        String target = CraftPlanner.matchTarget(what);
                        if (target != null) {
                            CraftPlanner.Result cr = CraftPlanner.plan(a, target, amount);
                            if (cr.blockers().isEmpty() && !cr.jobs().isEmpty()) {
                                PaPlan.Step m = step("make " + CraftPlanner.pretty(target), Kind.CRAFT, 30);
                                m.jobs.addAll(cr.jobs());
                                out.add(m);
                            }
                        }
                    }
                }
                PaPlan.Step s = step("bring you " + amount + " " + what, Kind.GIVE, 15);
                s.jobs.add(Job.give(what, amount));
                out.add(s);
                return null;
            }
            case BRING_FOOD -> {
                if (a.countFood() == 0) {
                    PaPlan.Step get = step("find some food", Kind.HUNT, 60);
                    get.jobs.add(Job.withdraw("food", 8));
                    get.jobs.add(Job.hunt(null, 2));
                    out.add(get);
                    PaPlan.Step cook = step("cook it", Kind.SMELT, 40);
                    cook.jobs.add(Job.smelt("beef", 4));
                    out.add(cook);
                }
                PaPlan.Step s = step("bring you food", Kind.BRING_FOOD, 15);
                s.jobs.add(Job.give(PaMemoryAct.foodWord(a), n > 0 ? n : 4));   // [pa35] its player's favourite, when it has some
                PaMemoryAct.gaveFood(a);
                out.add(s);
                return null;
            }
            case HUNT -> {
                PaPlan.Step s = step("hunt " + (n > 0 ? n : 3) + " " + i.item(), Kind.HUNT, 60);
                s.jobs.add(Job.hunt(i.item().equals("animals") ? null : animal(i.item()), n > 0 ? n : 3));
                out.add(s);
                return null;
            }
            case FISH -> {
                PaPlan.Step s = step("catch " + (n > 0 ? n : 5) + " fish", Kind.FISH, 30 * Math.max(1, n > 0 ? n : 5));
                s.jobs.add(Job.fish(n > 0 ? n : 5));
                s.count = "fish";
                s.target = n > 0 ? n : 5;
                out.add(s);
                return null;
            }
            case BREED -> {
                String lockB = locked(a, "tame");                    // [pa33d] animals at level 2
                if (lockB != null) return lockB;
                PaPlan.Step s = step("breed the " + i.item(), Kind.BREED, 40);
                s.jobs.add(Job.breed(i.item().equals("animals") ? null : animal(i.item()), n > 0 ? n : 2));
                out.add(s);
                return null;
            }
            case SHEAR -> {
                PaPlan.Step s = step("shear the sheep", Kind.SHEAR, 40);
                s.jobs.add(Job.shear(n > 0 ? n : 8));
                out.add(s);
                return null;
            }
            case LIGHT_AREA -> {
                if (a.countMatching(PaItems.of("torches")) < 4) {
                    CraftPlanner.Result cr = CraftPlanner.plan(a, "torch", 16);
                    if (cr.blockers().isEmpty() && !cr.jobs().isEmpty()) {
                        PaPlan.Step m = step("make torches", Kind.CRAFT, 40);
                        m.jobs.addAll(cr.jobs());
                        out.add(m);
                    } else if (!cr.blockers().isEmpty()) return "I need torches, and coal or charcoal to make them.";
                }
                PaPlan.Step s = step("light up the area", Kind.LIGHT_AREA, 40);
                s.jobs.add(Job.torchArea(n > 0 ? Math.min(32, n) : 12));
                out.add(s);
                return null;
            }
            case SORT -> {
                PaPlan.Step s = step("sort the chests", Kind.SORT, 45);
                s.jobs.add(Job.sort());
                out.add(s);
                return null;
            }
            case CLEAR_AREA -> {
                if (look.more().size() >= 2) {                     // [pa33c-2b] two corners pinged or looked at ("from here... to here")
                    BlockPos c1 = look.more().get(0), c2 = look.more().get(look.more().size() - 1);
                    if (PaSafety.offLimits(a, c1) || PaSafety.offLimits(a, c2)) return "That's someone's ground. I won't clear it without leave.";
                    int w = Math.max(2, Math.min(32, Math.abs(c1.getX() - c2.getX()) + 1)), d = Math.max(2, Math.min(32, Math.abs(c1.getZ() - c2.getZ()) + 1));
                    BlockPos mid = new BlockPos((c1.getX() + c2.getX()) / 2, Math.min(c1.getY(), c2.getY()), (c1.getZ() + c2.getZ()) / 2);
                    PaPlan.Step s = step("clear the " + w + "x" + d + " area", Kind.CLEAR_AREA, 120);
                    s.jobs.add(Job.goTo(mid.getX() + " " + mid.getY() + " " + mid.getZ()));
                    s.jobs.add(Job.clear(w + "x" + d));
                    out.add(s);
                    return null;
                }
                BlockPos at = where(p, a, i.place(), look);
                if (PaSafety.offLimits(a, at)) return "That's " + PaSafety.whose(a, at) + "'s ground. I won't clear it without leave.";
                PaPlan.Step s = step("clear a " + i.item() + " area", Kind.CLEAR_AREA, 120);
                s.jobs.add(Job.clear(i.item()));
                out.add(s);
                return null;
            }
            case FUEL -> {
                PaPlan.Step s = step("fuel the furnace", Kind.FUEL, 20);
                s.task = new PaTask(PaTask.Kind.FUEL, look.block() != null ? look.block() : a.blockPosition(), "", 0);
                out.add(s);
                return null;
            }
            case REPAIR -> {
                return "I can't mend tools myself yet. An anvil and the material will do it, or I'll make a new one: say \"craft a stone pickaxe\".";
            }
            case TRADE_BUY, TRADE_SELL -> {
                com.jrpetty.mcassistant.entity.Villages.Village v = nearestTown(a);
                if (v == null) return "There's no town's market near enough to trade at.";
                boolean buy = i.kind() == Kind.TRADE_BUY;
                PaPlan.Step s = step((buy ? "buy " : "sell ") + (n > 0 ? n + " " : "") + i.item() + " at " + com.jrpetty.mcassistant.entity.Villages.name(v.id()),
                    i.kind(), 90);
                s.task = new PaTask(buy ? PaTask.Kind.BUY : PaTask.Kind.SELL, v.centre(), i.item(), n);
                out.add(s);
                return null;
            }
            case GO_HOME -> {
                BlockPos home = homeOf(a);
                if (home == null) return "I don't have a home yet. Say \"set home here\" where you want it, or place my spawner.";
                PaPlan.Step s = step("head home", Kind.GO_HOME, 30);
                s.jobs.add(Job.goHome());
                out.add(s);
                return null;
            }
            case EXPLORE -> {
                PaPlan.Step s = step("scout around", Kind.EXPLORE, 90);
                Vec3 v = p.getLookAngle();
                s.jobs.add(Job.explore(BlockPos.containing(p.getX() + v.x * 48, p.getY(), p.getZ() + v.z * 48)));
                out.add(s);
                return null;
            }
            default -> {
                return "I'm not sure how to do that.";
            }
        }
    }

    /** A house (or any of the mod's drawings) at the spot: the blocks and furniture it needs first, then the building. */
    private static String planBuild(ServerPlayer p, PlayerAssistantEntity a, PaIntent i, Look look, List<PaPlan.Step> out) {
        String structure = i.item();
        if (structure.equals("bridge")) {
            PaPlan.Step s = step("bridge the gap", Kind.BUILD, 60);
            s.jobs.add(Job.bridge());
            out.add(s);
            return null;
        }
        if (!BuildGoal.STRUCTURES.contains(structure)) {
            return "I don't have a drawing for that. I can build a house, a shelter, a farm, a storage shed, a workshop, a smeltery or a watchtower.";
        }
        int half = BuildGoal.halfOf(structure);
        BlockPos anchor;
        Direction facing;
        if (i.place() == PaIntent.Place.LOOKED && look.block() != null && look.block().distSqr(p.blockPosition()) <= 48 * 48) {
            anchor = look.block();
            facing = Direction.getNearest(anchor.getX() - p.getX(), 0, anchor.getZ() - p.getZ());
        } else if (i.place() == PaIntent.Place.HOME && homeOf(a) != null) {
            BlockPos h = homeOf(a);
            anchor = h.offset(half + 4, 0, 0);
            facing = Direction.WEST;
        } else {
            // in front of the player, far enough that they are not standing inside it
            facing = p.getDirection();
            anchor = p.blockPosition().relative(facing, half + 3);
        }
        if (!facing.getAxis().isHorizontal()) facing = Direction.NORTH;
        anchor = new BlockPos(anchor.getX(), BuildGoal.groundTop(a.level(), anchor.getX(), anchor.getZ()), anchor.getZ());
        for (BlockPos c : new BlockPos[]{anchor, anchor.offset(half, 0, half), anchor.offset(-half, 0, -half), anchor.offset(half, 0, -half), anchor.offset(-half, 0, half)}) {
            if (PaSafety.offLimits(a, c)) return "That's " + PaSafety.whose(a, c) + "'s ground. I won't build there without leave.";
        }
        // what it needs, counted from the drawing itself
        Map<BuildGoal.Part, Integer> parts = BuildGoal.partCounts(structure, 5);
        int blocks = parts.getOrDefault(BuildGoal.Part.BLOCK, 0) + 12;              // a little for the ground under it
        int have = a.countMatching(BuildGoal::isBuildingBlock);
        int short_ = Math.max(0, blocks - have);
        if (short_ > 0) {
            int planks = (short_ * 3) / 5;                                         // timber walls and roof
            int stone = short_ - planks;                                           // a stone footing
            if (!PaItems.hasPickaxe(a)) needPickaxe(a, out);
            if (stone > 0) {
                PaPlan.Step g = step("gather " + stone + " stone for the " + structure, Kind.GATHER, eta(GatherGoal.Kind.STONE, stone));
                g.jobs.add(Job.gather(GatherGoal.Kind.STONE, stone));
                g.count = "stone";
                g.target = stone;
                out.add(g);
            }
            if (planks > 0) {
                CraftPlanner.Result cr = CraftPlanner.plan(a, "planks", planks);
                PaPlan.Step w = step("get " + planks + " planks for the " + structure, Kind.CRAFT, Math.max(20, planks * 2));
                w.jobs.addAll(cr.jobs());
                w.count = "planks";
                w.target = planks;
                w.make = "planks";
                if (!w.jobs.isEmpty()) out.add(w);
            }
        }
        for (Map.Entry<BuildGoal.Part, Integer> e : parts.entrySet()) {
            String key = switch (e.getKey()) {
                case CHEST -> "chest";
                case CRAFTING_TABLE -> "crafting_table";
                case FURNACE -> "furnace";
                case LADDER -> "ladder";
                case DOOR -> "oak_door";
                case FENCE -> "oak_fence";
                case GATE -> "oak_fence_gate";
                case TORCH -> "torch";
                default -> null;
            };
            if (key == null) continue;
            int want = e.getValue();
            int got = a.countMatching(BuildGoal.itemForPart(e.getKey()));
            if (got >= want) continue;
            CraftPlanner.Result cr = CraftPlanner.plan(a, key, want - got);
            if (!cr.blockers().isEmpty() || cr.jobs().isEmpty()) continue;           // the finishing it can't make is left out, as a town's builder does
            PaPlan.Step m = step("make " + (want - got) + " " + CraftPlanner.pretty(key) + " for the " + structure, Kind.CRAFT,
                Math.max(15, cr.jobs().stream().mapToInt(PaOrders::jobEta).sum()));
            m.jobs.addAll(cr.jobs());
            m.count = PaItems.itemKey(key);
            m.target = m.count.isEmpty() ? 0 : want - got;
            m.make = key;
            m.optional = true;                                                     // the house goes up without it if need be
            out.add(m);
        }
        PaPlan.Step s = step("build a " + (structure.equals("house") ? "small house" : structure), Kind.BUILD, Math.max(60, blocks * 2));
        s.jobs.add(Job.buildAt(structure, anchor, facing, 0));
        out.add(s);
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** [pa33d] A skill it hasn't the level for yet: an honest line saying when (null when it has it). */
    @Nullable
    static String locked(PlayerAssistantEntity a, String skill) {
        int need = PaLevels.levelOf(skill);
        if (need == 0 || a.veteranLevel() >= need) return null;
        return PaTalk.line(a, "skill_locked", "need", String.valueOf(need), "level", String.valueOf(a.veteranLevel()));
    }

    static PaPlan.Step step(String label, Kind kind, int eta) {
        PaPlan.Step s = new PaPlan.Step();
        s.label = label;
        s.kind = kind.name();
        s.eta = eta;
        return s;
    }

    private static void needPickaxe(PlayerAssistantEntity a, List<PaPlan.Step> out) {
        for (PaPlan.Step s : out) if (s.label.contains("pickaxe")) return;
        for (PaPlan.Step s : a.plan.all()) if (s.label.contains("pickaxe")) return;
        PaPlan.Step s = pickaxeStep(a);
        if (s != null) out.add(s);
    }

    /** A step to make the best pickaxe it can from what it has (stone if it has cobblestone), or null if it can't. */
    @Nullable
    static PaPlan.Step pickaxeStep(PlayerAssistantEntity a) {
        String target = a.countMatching(PaItems.of("stone")) >= 3 ? "stone_pickaxe" : "wooden_pickaxe";
        CraftPlanner.Result cr = CraftPlanner.plan(a, target, 1);
        if (!cr.blockers().isEmpty() || cr.jobs().isEmpty()) return null;
        PaPlan.Step s = step("make a " + CraftPlanner.pretty(target) + " first", Kind.CRAFT, Math.max(20, cr.jobs().stream().mapToInt(PaOrders::jobEta).sum()));
        s.jobs.addAll(cr.jobs());
        s.count = PaItems.itemKey(target);
        s.target = 1;
        s.make = target;
        return s;
    }

    private static boolean hasIronPick(AssistantEntity a) {
        return a.countMatching(s -> s.is(net.minecraft.world.item.Items.IRON_PICKAXE) || s.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE)
            || s.is(net.minecraft.world.item.Items.NETHERITE_PICKAXE)) > 0
            || a.getMainHandItem().is(net.minecraft.world.item.Items.IRON_PICKAXE) || a.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
    }

    private static boolean hasDiamondPick(AssistantEntity a) {
        return a.countMatching(s -> s.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE) || s.is(net.minecraft.world.item.Items.NETHERITE_PICKAXE)) > 0
            || a.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
    }

    private static boolean hasHoe(AssistantEntity a) {
        return a.countMatching(s -> s.getItem() instanceof net.minecraft.world.item.HoeItem) > 0
            || a.getMainHandItem().getItem() instanceof net.minecraft.world.item.HoeItem;
    }

    /** "pickaxe" with no material: the best it can make from what it has. */
    private static String bestOf(AssistantEntity a, String what) {
        for (String tool : PaUnderstand.TOOLS) {
            if (what.equals(tool)) {
                if (a.countMatching(PaItems.of("iron")) >= 3 && a.countMatching(s -> s.is(net.minecraft.world.item.Items.IRON_INGOT)) >= 3) return "iron " + tool;
                if (a.countMatching(PaItems.of("stone")) >= 3 || PaItems.hasPickaxe(a)) return "stone " + tool;
                return "wooden " + tool;
            }
        }
        return what;
    }

    private static String rawMeatCarried(AssistantEntity a) {
        for (String m : new String[]{"beef", "porkchop", "mutton", "chicken", "fish", "potato"}) {
            if (a.countMatching(com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(m)) > 0) return m;
        }
        return "beef";
    }

    private static String lastThing(PlayerAssistantEntity a, List<PaPlan.Step> out) {
        for (int k = out.size() - 1; k >= 0; k--) if (!out.get(k).count.isEmpty()) return out.get(k).count;
        if (a.plan.current != null && !a.plan.current.count.isEmpty()) return a.plan.current.count;
        return "everything";
    }

    @Nullable
    private static String animal(String word) {
        String w = word.endsWith("s") && !word.equals("sheep") ? word.substring(0, word.length() - 1) : word;
        return w.equals("animal") ? null : w;
    }

    private static int eta(GatherGoal.Kind k, int n) {
        return switch (k) {
            case LOGS -> 10 + n * 3;
            case STONE, DIRT, SAND, GRAVEL -> 10 + n * 2;
            case IRON, COAL -> 60 + n * 6;
            default -> 30 + n * 4;
        };
    }

    static int jobEta(Job j) {
        return switch (j.type()) {
            case GATHER -> j.kind() == null ? 30 : eta(j.kind(), j.amount());
            case CRAFT -> 6;
            case SMELT -> 10 + j.amount() * 10;
            case MINE -> 180;
            case HUNT -> 60;
            case WITHDRAW, DEPOSIT, GIVE -> 15;
            case BUILD -> 180;
            default -> 30;
        };
    }

    /** The log a look at a tree means: the block itself, or (looking at its leaves) the nearest log below them. */
    @Nullable
    static BlockPos treeLog(AssistantEntity a, BlockPos b) {
        if (a.level().getBlockState(b).is(net.minecraft.tags.BlockTags.LOGS)) return b;
        if (!a.level().getBlockState(b).is(net.minecraft.tags.BlockTags.LEAVES)) return null;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(b.offset(-3, -5, -3), b.offset(3, 1, 3))) {
            if (!a.level().getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS)) continue;
            double d = q.distSqr(b);
            if (d < bd) { bd = d; best = q.immutable(); }
        }
        return best;
    }

    @Nullable
    private static GatherGoal.Kind kindAt(AssistantEntity a, BlockPos b) {
        var st = a.level().getBlockState(b);
        for (GatherGoal.Kind k : GatherGoal.Kind.values()) if (k.matches(st)) return k;
        return null;
    }

    private static boolean isContainer(AssistantEntity a, BlockPos b) {
        return a.level().getBlockEntity(b) instanceof net.minecraft.world.Container;
    }

    /** The player pointed at this chest: from now on it may always use it. */
    static void point(PlayerAssistantEntity a, BlockPos chest) {
        PaData.Rec r = a.rec();
        if (r != null && r.pointed.add(chest.asLong())) PaData.touch();
    }

    private static boolean farmlandNear(AssistantEntity a, BlockPos at) {
        for (BlockPos b : BlockPos.betweenClosed(at.offset(-6, -2, -6), at.offset(6, 2, 6))) {
            if (a.level().getBlockState(b).is(net.minecraft.world.level.block.Blocks.FARMLAND)) return true;
        }
        return false;
    }

    @Nullable
    static BlockPos homeOf(PlayerAssistantEntity a) {
        if (a.getHome() != null) return a.getHome();
        PaData.Rec r = a.rec();
        if (r != null && r.spawner != null && a.level().dimension().location().toString().equals(r.spawnerDim)) return r.spawner;
        return null;
    }

    /** The spot an order means. */
    static BlockPos where(ServerPlayer p, PlayerAssistantEntity a, PaIntent.Place place, Look look) {
        return switch (place) {
            case LOOKED -> look.block() != null ? look.block() : p.blockPosition();
            case HOME -> homeOf(a) != null ? homeOf(a) : p.blockPosition();
            case HERE -> look.block() != null && look.block().distSqr(p.blockPosition()) <= 12 * 12 ? look.block() : p.blockPosition();
            default -> p.blockPosition();
        };
    }

    @Nullable
    private static com.jrpetty.mcassistant.entity.Villages.Village nearestTown(AssistantEntity a) {
        com.jrpetty.mcassistant.entity.Villages.Village best = null;
        double bd = 200.0 * 200.0;
        for (com.jrpetty.mcassistant.entity.Villages.Village v : com.jrpetty.mcassistant.entity.Villages.every()) {
            if (!v.dim().equals(a.level().dimension())) continue;
            double d = v.centre().distSqr(a.blockPosition());
            if (d < bd) { bd = d; best = v; }
        }
        return best;
    }

    static String summary(List<PaPlan.Step> work) {
        List<String> l = new ArrayList<>();
        for (PaPlan.Step s : work) l.add(s.label.replaceAll(" first$", ""));
        if (l.size() == 1) return cap(l.get(0));
        String last = l.get(l.size() - 1);
        if (l.size() > 4) {
            return "First " + l.get(0) + ", " + l.get(1) + " and " + l.get(2) + ", then " + (l.size() - 4) + " more step"
                + (l.size() - 4 == 1 ? "" : "s") + ", and " + last;
        }
        return "First " + String.join(", then ", l.subList(0, l.size() - 1)) + ", and then " + last;
    }

    static String time(int seconds) {
        if (seconds < 60) return "under a minute";
        int m = Math.round(seconds / 60.0F);
        return m <= 1 ? "a minute" : m + " minutes";
    }

    static String bearing(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double ang = Math.toDegrees(Math.atan2(-dx, dz));
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        int i = (int) Math.round(((ang % 360) + 360) % 360 / 45.0) % 8;
        return names[i];
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The fixes to offer when something can't be done, read from the reason. */
    static List<Choice> fixesFor(String why, String said) {
        String w = why.toLowerCase();
        List<Choice> c = new ArrayList<>();
        if (w.contains("pickaxe")) c.add(new Choice("Make a pickaxe", "craft a stone pickaxe"));
        if (w.contains("full")) c.add(new Choice("Use the next chest", "deposit"));
        if (w.contains("torch") || w.contains("coal")) c.add(new Choice("Mine some coal", "mine coal"));
        if (w.contains("hoe")) c.add(new Choice("Make a hoe", "craft a wooden hoe"));
        if (w.contains("water")) c.add(new Choice("Make a bucket", "craft a bucket"));
        if (w.contains("still need") || w.contains("building blocks")) c.add(new Choice("Gather blocks", "gather 32 stone then get 32 logs"));
        if (w.contains("home")) c.add(new Choice("Set home here", "set home here"));
        if (w.contains("chest") && c.isEmpty()) c.add(new Choice("Use my chests", "#settings chests on"));
        if ("#retry".equals(said)) c.add(new Choice("Try again", "#retry"));
        else if (said != null && !said.isEmpty() && !said.startsWith("#")) c.add(new Choice("Try again", said));
        c.add(new Choice("Never mind", "stop"));
        return c;
    }
}
