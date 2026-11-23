package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

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
            case "settings" -> {
                if (r == null) return;
                if (args.contains("auto")) r.autoHelp = args.getBoolean("auto");
                if (args.contains("chests")) r.useChests = args.getBoolean("chests");
                if (args.contains("break")) r.breakNearBuilds = args.getBoolean("break");
                if (args.contains("confirm")) r.confirm = args.getBoolean("confirm");
                if (args.contains("sleep")) {
                    r.sleepAtNight = args.getBoolean("sleep");
                    if (a != null) a.setNightHome(r.sleepAtNight);
                }
                if (args.contains("roam")) r.roam = Math.max(8, Math.min(PaConfig.roamMax(), args.getInt("roam")));
                if (args.contains("chatty")) r.chatty = Math.max(0, Math.min(3, args.getInt("chatty")));
                if (args.contains("spend")) r.spend = Math.max(0, Math.min(PaConfig.spendMax(), args.getInt("spend")));
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
            case "log" -> {
                // a Guide answer the client gave as its assistant's words
                if (a != null) PaTalk.reply(a, args.getString("text"));
            }
            case "tour" -> {
                if (r != null) { r.toured = true; PaData.touch(); }
            }
            default -> { }
        }
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
        PaOrders.hear(p, a, text, new PaOrders.Look(look, ent), args.getBoolean("voice"));
    }

    /** The choices' own words: "#veto fight", "#settings chests on", "#tour". */
    private static void hash(ServerPlayer p, PaData.Rec r, PlayerAssistantEntity a, String text) {
        String[] w = text.substring(1).split(" ");
        switch (w[0]) {
            case "veto" -> {
                if (w.length > 1) {
                    a.mind.vetoed.add(w[1]);
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
