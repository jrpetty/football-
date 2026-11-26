package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [pa33] Every click, key and word from a player's assistant panel, wheel, talk box or voice, handled on the server.
 * No action depends on any command permission: the server bans chat and console commands, and none are used.
 */
public final class PaServer {

    private PaServer() {}

    public static void act(ServerPlayer p, String action, CompoundTag args) {
        try {
            act0(p, action, args);
        } catch (RuntimeException e) {
            // a slip in the assistant never costs a player their connection: it is logged, and they are told
            com.mojang.logging.LogUtils.getLogger().error("[pa33] the assistant's \"{}\" failed", action, e);
            PaLife.tell(p, "Your assistant got confused by that. Try again?");
        }
    }

    private static void act0(ServerPlayer p, String action, CompoundTag args) {
        PaData.Rec r = PaData.first(p.getUUID());
        if (args.contains("slot")) {
            PaData.Rec bySlot = PaData.find(p.getUUID(), args.getInt("slot"));
            if (bySlot != null) r = bySlot;
        }
        PlayerAssistantEntity a = r == null ? null : PaLife.entity(p.server, r);
        switch (action) {
            case "open" -> PaNet.open(p, a, args.getString("tab").isEmpty() ? "talk" : args.getString("tab"));
            case "state" -> PaNet.refresh(p, a);
            case "summon" -> {
                PaLife.summonButton(p);
                PaNet.refresh(p, PaLife.find(p));
            }
            case "dismiss" -> {
                if (r != null) PaLife.dismiss(p, r);
            }
            case "talk" -> talk(p, r, a, args);
            case "ping" -> ping(p, r, a, args);                     // [pa33c-2b] a ping wheel choice (a bare ping never reaches here)
            case "logi" -> PaLogiNet.act(p, r, a, args);            // [pa36] the Storage view, the chest wheel's pickers, the Craft view
            case "kit" -> PaKit.act(p, r, a, args);                 // [featpa] Blueprints, Errand to town, Combat stance
            case "mark" -> {                                        // [pa33c-2b] a bare ping shared with players near by (never an order)
                if (!MARKS.computeIfAbsent(p.getUUID(), k -> new PaPings.Rate()).allow(System.currentTimeMillis())) return;
                BlockPos at = new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z"));
                String label = args.getString("label");
                for (ServerPlayer o : p.serverLevel().players())
                    if (o != p && o.distanceToSqr(p) < 128 * 128) PaNet.ping(o, at, p.getGameProfile().getName() + ": " + (label.length() > 40 ? label.substring(0, 40) : label), "LOOK", "shared");
            }
            case "stranger" -> {                                    // [pa33b] someone else's assistant, from its little chat box
                if (p.level().getEntity(args.getInt("id")) instanceof PlayerAssistantEntity other && !other.isOwner(p) && other.distanceToSqr(p) < 16 * 16)
                    PaBanter.stranger(other, p, args.getString("text"), false);
            }
            case "settings" -> {
                if (r == null) return;
                if (args.contains("auto")) r.autoHelp = args.getBoolean("auto");
                if (args.contains("autoKind")) {                         // [pa35] one kind of auto-help on or off
                    String k = args.getString("autoKind");
                    if (args.getBoolean("on")) { r.autoOff.remove(k); if (a != null) a.mind.vetoed.remove(k); } else r.autoOff.add(k);
                }
                if (args.contains("chests")) r.useChests = args.getBoolean("chests");
                if (args.contains("break")) r.breakNearBuilds = args.getBoolean("break");
                if (args.contains("confirm")) r.confirm = args.getBoolean("confirm");
                if (args.contains("sleep")) {
                    r.sleepAtNight = args.getBoolean("sleep");
                    if (a != null) a.setNightHome(r.sleepAtNight);
                }
                if (args.contains("roam")) r.roam = Math.max(8, Math.min(PaConfig.roamMax(), args.getInt("roam")));
                if (args.contains("chatty")) r.chatty = Math.max(0, Math.min(4, args.getInt("chatty")));
                if (args.contains("funny")) {                       // [pa33b] the personality triangle
                    PaPersona.Tri t = PaPersona.Tri.of(args.getFloat("funny"), args.getFloat("gloomy"));
                    r.funny = (float) t.funny();
                    r.gloomy = (float) t.gloomy();
                }
                if (args.contains("mute") && a != null && args.getInt("mute") != r.mute) PaOrders.hush(a, args.getInt("mute"));   // [pa33b] About, Orders, wheel
                else if (args.contains("mute")) r.mute = Math.max(0, Math.min(2, args.getInt("mute")));
                if (args.contains("language")) r.language = Math.max(0, Math.min(2, args.getInt("language")));     // [pa33b] match my energy
                if (args.contains("humour")) r.humour = Math.max(0, Math.min(2, args.getInt("humour")));     // [pa35] Dry, Daft, Sincere
                if (args.contains("roastBack")) r.roastBack = Math.max(0, Math.min(2, args.getInt("roastBack")));
                if (args.contains("defend")) r.defend = args.getBoolean("defend");
                if (args.contains("pingLocked")) r.pingLocked = args.getBoolean("pingLocked");      // [pa35]
                if (args.contains("spend")) r.spend = Math.max(0, Math.min(PaConfig.spendMax(), args.getInt("spend")));
                if (args.getBoolean("reset")) {                     // [pa33c-2b] Settings, Advanced: its settings as they came
                    r.autoHelp = true; r.useChests = true; r.breakNearBuilds = false; r.confirm = true; r.sleepAtNight = false;
                    r.roam = 32; r.chatty = 2; r.spend = 64; r.funny = (float) PaPersona.DEFAULT_FUNNY; r.gloomy = (float) PaPersona.DEFAULT_GLOOMY;
                    r.mute = 0; r.language = 0; r.roastBack = 0; r.defend = true; r.keep.clear();
                    if (a != null) a.setNightHome(false);
                }
                if (args.getBoolean("clearBed")) { r.bed = null; r.lastBed = null; }   // [pa33c-2b] back at its spawner (or you) after a fall
                PaData.touch();
                PaNet.refresh(p, a);
            }
            case "look" -> {
                if (r == null) return;
                if (args.contains("look")) r.look = Math.floorMod(args.getInt("look"), PaLife.LOOKS);
                if (args.contains("voice")) r.voice = Math.max(0, args.getInt("voice"));
                if (args.contains("pers")) r.personality = Math.floorMod(args.getInt("pers"), PaPhrases.Personality.values().length);
                if (a != null) {
                    a.setLook(r.look);
                    a.setPersonality(r.personality);
                    if (args.contains("name")) PaLife.rename(a, args.getString("name"));
                } else if (args.contains("name")) {
                    String n = args.getString("name").trim().toLowerCase().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "");
                    if (!n.isEmpty()) r.name = n.length() > 16 ? n.substring(0, 16) : n;
                }
                PaData.touch();
                PaNet.refresh(p, a);
            }
            case "job" -> {
                if (a == null) return;
                job(a, args.getString("op"), args.getInt("i"));
                PaNet.refresh(p, a);
            }
            case "pack" -> {
                if (a != null && a.obeys(p)) a.openManagementScreen(p);
            }
            case "admin" -> {                                       // [pa33d] operators only: the server's levelling multiplier
                if (!p.hasPermissions(2)) { PaNet.note(p, "Only an operator can change that."); return; }
                if (args.contains("xpMult")) PaConfig.setXpMultiplier(args.getDouble("xpMult"));
                if (args.contains("memCap")) PaConfig.setMemoryCap(args.getInt("memCap"));          // [pa35] how much each may remember
                if (args.contains("ladderOn")) PaConfig.setLadder(args.getBoolean("ladderOn"), null, 0);   // [pa35] the mining and gear ladders
                if (args.contains("rung")) PaConfig.setLadder(null, args.getString("rung"), args.getInt("rungLevel"));
                PaConfig.setChunks(args.contains("chunksOn") ? args.getBoolean("chunksOn") : null,                 // [pa33g] its chunks
                    args.contains("chunkRadius") ? args.getInt("chunkRadius") : null,
                    args.contains("chunkCap") ? args.getInt("chunkCap") : null,
                    args.contains("chunksOffline") ? args.getBoolean("chunksOffline") : null);
                PaNet.refresh(p, a);
            }
            case "glance" -> {                                      // [pa33d] a bare ping: it looks over, nothing more (no order, no log)
                PaLogistics.seen(p, new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z")));   // [pa36] a pinged chest is "this chest" for a while
                if (a == null || !a.obeys(p) || a.getTarget() != null || a.isSleeping() || a.distanceToSqr(p) > 48 * 48) return;
                if (!MARKS.computeIfAbsent(p.getUUID(), k -> new PaPings.Rate()).allow(System.currentTimeMillis())) return;
                a.mind.glanceAt = new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z"));
                a.mind.glanceUntil = a.tickCount + 40;
            }
            case "packop" -> {                                      // [pa33c-2b] the Pack tab: a move or a button
                if (a == null || r == null || !a.obeys(p)) return;
                String note = PaPack.op(p, a, r, args);
                if (!note.isEmpty()) PaNet.note(p, note);
                PaNet.refresh(p, a);
            }
            case "log" -> {
                // a Guide answer the client gave as its assistant's words
                if (a != null) PaTalk.reply(a, args.getString("text"));
            }
            case "tour" -> {
                if (r != null) { r.toured = true; PaData.touch(); }
            }
            case "memop" -> {                                       // [pa35] the Memory tab: delete, edit, add, pause, forget all
                if (r == null || a == null || !a.isOwner(p)) return;
                PaMemoryAct.op(p, a, r, args);
                PaNet.refresh(p, a);
            }
            default -> { }
        }
    }

    private static final java.util.Map<java.util.UUID, PaPings.Rate> PINGS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, PaPings.Rate> MARKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Game tests: forget a player's ping rate (their ticks run faster than the wall clock). */
    public static void pingRateForTests(java.util.UUID u) {
        PINGS.remove(u);
    }

    /**
     * [pa33c-2b] A ping wheel choice: the option's own words, with the ping as what "that" and "there" mean, through the
     * same reader as anything said; or one of the special ones (look, danger, leave it, say hi). Owner only, rate-limited.
     */
    private static void ping(ServerPlayer p, @Nullable PaData.Rec r, @Nullable PlayerAssistantEntity a, CompoundTag args) {
        if (r == null || a == null || !a.obeys(p)) return;
        if (!PINGS.computeIfAbsent(p.getUUID(), k -> new PaPings.Rate()).allow(System.currentTimeMillis())) return;
        PaPings.Target target = PaPings.of(args.getString("target"));
        PaPings.Option o = PaPings.option(target, args.getString("opt"));
        if (o == null) return;
        BlockPos at = args.contains("x") ? new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z")) : null;
        int ent = args.contains("ent") ? args.getInt("ent") : -1;
        String label = args.getString("label");
        if (at != null) PaLogistics.seen(p, at);                    // [pa36]
        if (at != null && args.contains("x2")) PaBuild.rememberArea(p, at, new BlockPos(args.getInt("x2"), args.getInt("y2"), args.getInt("z2")));   // [featpa]
        switch (o.id()) {
            case "look" -> {
                String key = "ping.look." + target.name().toLowerCase(java.util.Locale.ROOT);
                String raw = PaPersona.from(PaPersona.linesFor(key, a.tri(), a.getRandom()::nextInt, a.mind.recent), a.getRandom()::nextInt, a.mind.recent);
                if (raw == null) raw = PaPersona.from(PaLines.get(key), a.getRandom()::nextInt, a.mind.recent);
                PaTalk.reply(a, raw == null ? "Yes. I see it." : PaBanter.fill(a, raw, "what", label.isEmpty() ? "that" : label));
                return;
            }
            case "danger" -> {
                if (at != null) a.mind.danger = at;
                PaTalk.reply(a, PaTalk.line(a, "ping_danger", "what", label.isEmpty() ? "there" : label));
                return;
            }
            case "leave" -> { PaTalk.reply(a, PaTalk.line(a, "ping_leave")); return; }
            case "hi" -> {
                net.minecraft.world.entity.Entity e = ent >= 0 ? p.level().getEntity(ent) : null;
                String who = e == null ? "there" : e.getName().getString();
                PaTalk.reply(a, PaTalk.line(a, "ping_hi", "player", who));
                return;
            }
            case "followthem" -> { PaTalk.reply(a, PaTalk.line(a, "not_yours_to_follow")); return; }
            case "copy" -> {                                         // [featpa] the marked area taken in as a blueprint
                if (at != null && args.contains("x2")) PaBuild.rememberArea(p, at, new BlockPos(args.getInt("x2"), args.getInt("y2"), args.getInt("z2")));
                PaBuild.copyArea(p, a, "");
                return;
            }
            case "cancel", "area" -> { return; }
            default -> { }
        }
        if (o.says().isEmpty()) return;
        List<BlockPos> more = new java.util.ArrayList<>();
        if (args.contains("x2")) {                                   // an area: its two corners
            more.add(at);
            more.add(new BlockPos(args.getInt("x2"), args.getInt("y2"), args.getInt("z2")));
        }
        PaOrders.hear(p, a, o.says(), new PaOrders.Look(at, ent, more), false);
    }

    private static void talk(ServerPlayer p, @Nullable PaData.Rec r, @Nullable PlayerAssistantEntity a, CompoundTag args) {
        String text = args.getString("text").trim();
        if (text.isEmpty()) return;
        if (r == null) {
            PaLife.tell(p, "You haven't an assistant yet: click \"Get my assistant\".");
            return;
        }
        long now = p.serverLevel().getGameTime();
        if (r.dead(now)) {
            PaLife.tell(p, PaNet.cap(r.name) + " is knocked out: back in " + (r.deadUntil - now) / 20 + "s.");
            return;
        }
        if (a == null) {
            // not loaded (its chunk is far off): it comes to you to hear you out
            PaLife.call(p, r, true);
            a = PaLife.entity(p.server, r);
            if (a == null) return;
        }
        if (!a.obeys(p)) return;
        if (text.startsWith("#")) {
            hash(p, r, a, text);
            return;
        }
        BlockPos look = args.contains("bx") ? new BlockPos(args.getInt("bx"), args.getInt("by"), args.getInt("bz")) : null;
        int ent = args.contains("ent") ? args.getInt("ent") : -1;
        if (PaKit.heard(p, a, text)) {                              // [featpa] "copy this building", "run an errand", "be a medic"
            r.say(0, text, now);
            PaData.touch();
            PaNet.refresh(p, a);
            return;
        }
        // [pa33c-2b] every place pointed at while it was said (a ping first, then each look at "that"), in order
        List<BlockPos> more = new java.util.ArrayList<>();
        int[] ls = args.getIntArray("looks");
        for (int i = 0; i + 2 < ls.length && more.size() < 8; i += 3) more.add(new BlockPos(ls[i], ls[i + 1], ls[i + 2]));
        PaOrders.hear(p, a, text, new PaOrders.Look(look, ent, more), args.getBoolean("voice"));
    }

    /** The choices' own words: "#veto fight", "#settings chests on", "#tour". */
    private static void hash(ServerPlayer p, PaData.Rec r, PlayerAssistantEntity a, String text) {
        String[] w = text.substring(1).split(" ");
        switch (w[0]) {
            case "fetchdeath" -> PaBanter.fetchDeath(a);           // [pa33b]
            case "logi" -> {                                        // [pa36] "Hand over", "Look further", a craft's choices
                if (w.length > 1 && w[1].startsWith("craft")) PaCraft.choice(p, a, w[1]);
                else PaLogiNet.hash(p, a, w);
            }
            case "kit" -> PaKit.hash(p, a, w);                      // [featpa] a build's "Get what's short", "Buy it", "Carry on"
            case "memforgetall" -> { PaMemoryAct.forgetAll(a); return; }     // [pa35] "forget everything", confirmed
            case "memkeep" -> PaTalk.reply(a, "Right. I'll hang on to all of it.", null, true);
            case "memlike", "memdislike" -> {                        // [pa35] "I like Sam": a thing after all, not a person
                String it = text.substring(w[0].length() + 1).trim();
                PaMemoryAct.hear(p, a, (w[0].equals("memlike") ? "i like " : "i hate ") + it.toLowerCase(java.util.Locale.ROOT), PaOrders.Look.NONE);
            }
            case "nofetch" -> PaTalk.reply(a, "Right you are. Mind, they'll vanish in about five minutes.");
            case "veto" -> {
                if (w.length > 1) {
                    a.mind.vetoed.add(w[1]);
                    r.autoOff.add(w[1]);                                 // [pa35] kept, and shown in Settings > Behaviour
                    PaData.touch();
                    a.plan.dropAuto();
                    if (a.plan.current != null && a.plan.current.auto) { a.clearQueue(); a.task = null; a.plan.current = null; }
                    if (w[1].equals("fight")) a.setTarget(null);
                    PaTalk.reply(a, "Alright, I won't do that on my own again.");
                }
            }
            case "settings" -> {
                if (w.length > 2 && w[1].equals("chests")) { r.useChests = w[2].equals("on"); PaData.touch(); PaTalk.reply(a, r.useChests ? "Thanks, I'll use your chests." : "I'll leave your chests alone."); }
            }
            case "retry" -> {
                PaPlan.Step f = a.plan.failed;
                a.plan.failed = null;
                if (f == null) { PaTalk.reply(a, "There's nothing to try again just now."); break; }
                PaBrain.rearm(a, f);
                a.plan.steps.add(0, f);
                a.plan.paused = false;
                PaTalk.reply(a, "Trying again: " + f.label + ".");
            }
            case "pack" -> {                                          // [pa33b] "I'll give you some": its pack, to put food in
                a.openManagementScreen(p);
                return;
            }
            case "tour" -> {
                CompoundTag t = PaNet.state(p, a);
                t.putString("tab", "talk");
                t.putBoolean("tour", true);
                PaNet.send(p, PaPayload.OPEN, t);
            }
            default -> { }
        }
        PaNet.refresh(p, a);
    }

    /** The Jobs tab: cancel, move up or down, pause, resume, clear. Index 0 is the step under way. */
    static void job(PlayerAssistantEntity a, String op, int i) {
        PaPlan plan = a.plan;
        java.util.List<PaPlan.Step> all = plan.all();
        switch (op) {
            case "pause" -> PaOrders.now(null, a, PaIntent.of(PaIntent.Kind.PAUSE, ""), PaOrders.Look.NONE);
            case "resume" -> plan.paused = false;
            case "clear" -> PaOrders.stopAll(a);
            case "cancel" -> {
                if (i < 0 || i >= all.size()) return;
                PaPlan.Step s = all.get(i);
                if (s == plan.current) { plan.current = null; a.clearQueue(); a.task = null; }
                else plan.steps.remove(s);
            }
            case "up", "down" -> {
                if (i < 0 || i >= all.size()) return;
                PaPlan.Step s = all.get(i);
                int at = plan.steps.indexOf(s);
                if (at < 0) {
                    // the one under way moved down: it goes back in the list behind the next
                    if (op.equals("down") && !plan.steps.isEmpty()) {
                        plan.current = null;
                        a.clearQueue();
                        a.task = null;
                        s.fed = false;
                        s.started = -1;
                        plan.steps.add(1, s);
                    }
                    return;
                }
                int to = op.equals("up") ? at - 1 : at + 1;
                if (to < 0) {
                    // to the front: it takes over from the one under way
                    PaPlan.Step cur = plan.current;
                    plan.steps.remove(s);
                    plan.steps.add(0, s);
                    if (cur != null) { plan.current = null; a.clearQueue(); a.task = null; cur.fed = false; cur.started = -1; plan.steps.add(1, cur); }
                    return;
                }
                if (to >= plan.steps.size()) return;
                plan.steps.remove(at);
                plan.steps.add(to, s);
            }
            default -> { }
        }
    }
}
