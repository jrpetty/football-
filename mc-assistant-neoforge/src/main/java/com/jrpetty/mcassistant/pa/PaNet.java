package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa33] The Player AI Assistant's wire: its panel, its words and its questions to the client; the player's clicks,
 * typed words and spoken words back. Nothing goes through the chat: a typed or spoken order arrives here as the same
 * "talk" action and is read by the same reader.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PaNet {

    private PaNet() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(PaPayload.TYPE, PaPayload.STREAM_CODEC, PaNet::handleClient);
        registrar.playToServer(PaActionPayload.TYPE, PaActionPayload.STREAM_CODEC, PaNet::handleAction);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleClient(PaPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.pa.PaClient.heard(payload.kind(), payload.data()));
    }

    private static void handleAction(PaActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) PaServer.act(player, payload.action(), payload.args());
        });
    }

    // ------------------------------------------------------------------ to the client

    public static void send(ServerPlayer p, int kind, CompoundTag data) {
        if (p.connection == null) return;
        try {
            PacketDistributor.sendToPlayer(p, new PaPayload(kind, data));
        } catch (Exception ignored) {
            // a test's mock player has no real connection
        }
    }

    /** Open the panel at a tab ("talk", "orders", "jobs", "pack", "about"), with its state. */
    public static void open(ServerPlayer p, @Nullable PlayerAssistantEntity a, String tab) {
        CompoundTag t = state(p, a);
        t.putString("tab", tab);
        send(p, PaPayload.OPEN, t);
    }

    /** The panel's state, sent when something changed while it is open (and on open). */
    public static void refresh(ServerPlayer p, @Nullable PlayerAssistantEntity a) {
        send(p, PaPayload.STATE, state(p, a));
    }

    /** A line said: to its owner (logged, spoken, with choices when it asks), and as a bubble to everyone in sight. */
    public static void say(PlayerAssistantEntity a, String text, boolean speak, @Nullable List<PaUnderstand.Choice> choices, int ticks) {
        say(a, text, speak, choices, ticks, false);
    }

    public static void say(PlayerAssistantEntity a, String text, boolean speak, @Nullable List<PaUnderstand.Choice> choices, int ticks, boolean greet) {
        CompoundTag t = new CompoundTag();
        if (greet) t.putBoolean("greet", true);
        t.putInt("id", a.getId());
        t.putString("name", a.displayNameCap());
        t.putString("text", text);
        t.putInt("ticks", ticks);
        t.putInt("voice", a.rec() == null ? 0 : a.rec().voice);
        t.putFloat("gloom", (float) a.tri().gloomy());           // [pa33b] slower and lower when gloomy
        t.putFloat("fun", (float) a.tri().funny());
        if (choices != null && !choices.isEmpty()) t.put("choices", choices(choices));
        if (a.level() instanceof net.minecraft.server.level.ServerLevel) {
            for (ServerPlayer viewer : ((net.minecraft.server.level.ServerLevel) a.level()).players()) {
                boolean owner = a.isOwner(viewer);
                if (!owner && viewer.distanceToSqr(a) > 48 * 48) continue;
                CompoundTag mine = t.copy();
                mine.putBoolean("owner", owner);
                mine.putBoolean("speak", owner && speak);
                if (!owner) mine.remove("choices");
                send(viewer, PaPayload.SAY, mine);
            }
        }
    }

    static ListTag choices(List<PaUnderstand.Choice> choices) {
        ListTag l = new ListTag();
        for (PaUnderstand.Choice c : choices) {
            CompoundTag ct = new CompoundTag();
            ct.putString("l", c.label());
            ct.putString("s", c.say());
            l.add(ct);
        }
        return l;
    }

    /** The panel's whole state for this player: their assistant (if any), its jobs, its talk, its settings. */
    public static CompoundTag state(ServerPlayer p, @Nullable PlayerAssistantEntity a) {
        CompoundTag t = new CompoundTag();
        long now = p.serverLevel().getGameTime();
        List<PaData.Rec> recs = PaData.of(p.getUUID());
        PaData.Rec r = a != null ? a.rec() : (recs.isEmpty() ? null : recs.get(0));
        t.putInt("count", recs.size());
        t.putInt("limit", PaConfig.perPlayer());
        t.putBoolean("free", PaConfig.freeFirst() || p.isCreative());
        t.putBoolean("has", r != null);
        if (r == null) return t;
        t.putInt("slot", r.slot);
        t.putString("name", r.name.isEmpty() ? "Assistant" : cap(r.name));
        t.putString("owner", r.ownerName);
        t.putInt("look", r.look);
        t.putInt("voice", r.voice);
        t.putInt("pers", r.personality);
        t.putBoolean("dead", r.dead(now));
        t.putInt("back", r.dead(now) ? (int) ((r.deadUntil - now) / 20) : 0);
        t.putString("death", r.lastDeath);
        t.putInt("summonIn", (int) Math.max(0, (r.summonReadyAt - now) / 20));
        t.putBoolean("away", r.away);
        t.putBoolean("toured", r.toured);
        t.putBoolean("introduced", r.introduced);
        CompoundTag set = new CompoundTag();
        set.putBoolean("auto", r.autoHelp);
        set.putBoolean("chests", r.useChests);
        set.putBoolean("break", r.breakNearBuilds);
        set.putBoolean("confirm", r.confirm);
        set.putBoolean("sleep", r.sleepAtNight);
        set.putInt("roam", r.roam);
        set.putInt("chatty", r.chatty);
        set.putFloat("funny", r.funny);                          // [pa33b]
        set.putFloat("gloomy", r.gloomy);
        set.putInt("mute", r.mute);                               // [pa33b] 0 talking, 1 no jokes, 2 quiet
        set.putInt("spend", r.spend);
        set.putInt("roamMax", PaConfig.roamMax());
        set.putInt("spendMax", PaConfig.spendMax());
        t.put("set", set);
        ListTag log = new ListTag();
        int skip = Math.max(0, r.log.size() - 50);
        for (PaData.Line l : r.log) {
            if (skip-- > 0) continue;
            CompoundTag c = new CompoundTag();
            c.putInt("w", l.who());
            c.putString("t", l.text());
            if (l.times() > 1) c.putInt("n", l.times());       // [pa33b] said again: "×2"
            if (l.greet()) c.putBoolean("g", true);
            log.add(c);
        }
        t.put("log", log);
        t.putInt("done", r.jobsDone);
        if (a != null) {
            t.putInt("id", a.getId());
            t.putFloat("hp", a.getHealth());
            t.putFloat("maxHp", a.getMaxHealth());
            t.putInt("level", a.veteranLevel());
            t.putInt("xp", a.lifetimeXp());
            t.putInt("food", a.countFood());
            t.putString("mode", a.getMode().name().toLowerCase());
            t.putString("state", a.state() == null ? "" : a.state());
            t.putBoolean("paused", a.plan.paused);
            ListTag steps = new ListTag();
            for (PaPlan.Step s : a.plan.all()) {
                CompoundTag c = new CompoundTag();
                c.putString("l", s.label);
                c.putString("k", s.kind);
                c.putInt("pct", s.percent(a));
                c.putString("tally", s.tally(a));
                int left = s.eta;
                if (s.started >= 0) left = Math.max(5, s.eta - (int) ((now - s.started) / 20));
                c.putInt("eta", left);
                c.putBoolean("now", s == a.plan.current);
                c.putBoolean("auto", s.auto);
                steps.add(c);
            }
            t.put("steps", steps);
            ListTag deeds = new ListTag();
            for (String line : a.workRecord(8)) if (line != null) deeds.add(StringTag.valueOf(line));
            t.put("deeds", deeds);
            String today = a.productionSummary(5);
            t.putString("today", today == null ? "" : today);
            Map<String, Integer> pack = new LinkedHashMap<>();
            for (ItemStack s : a.getInventoryItems()) {
                if (s.isEmpty()) continue;
                pack.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
            }
            ListTag pl = new ListTag();
            pack.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12)
                .forEach(e -> pl.add(StringTag.valueOf(e.getValue() + " " + e.getKey())));
            t.put("pack", pl);
            t.putInt("packUsed", (int) a.getInventoryItems().stream().filter(s -> !s.isEmpty()).count());
            t.putString("held", a.getMainHandItem().isEmpty() ? "nothing" : a.getMainHandItem().getHoverName().getString());
            t.putString("dim", a.level().dimension().location().getPath());
            t.putInt("dist", (int) Math.sqrt(a.distanceToSqr(p)));
            PaOrders.Pending pend = PaOrders.pending(p.getUUID());
            if (pend != null) {
                t.putString("ask", pend.question());
                t.put("askChoices", choices(pend.choices()));
            }
        }
        return t;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
    }

    @SuppressWarnings("unused")
    private static boolean isAssistant(AssistantEntity a) {
        return a instanceof PlayerAssistantEntity;
    }
}
