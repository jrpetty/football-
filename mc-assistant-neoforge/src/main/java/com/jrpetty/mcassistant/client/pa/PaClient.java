package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.pa.PaActionPayload;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.PaPayload;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa33] The Player AI Assistant on the player's side: its keys (the panel, hold-to-talk, the action wheel), what the
 * server last said about it (for the panel), its bubbles, the HUD line ("Chopping logs 12/16"), its voice, and
 * listening. Every order goes to the server as the same "talk" action, typed, clicked or spoken, with what the player
 * is looking at (so "that chest" and "build here" mean something).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PaClient {

    private PaClient() {}

    public static final KeyMapping PANEL = new KeyMapping("key.mc_assistant.pa_panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y,
        "key.categories.mc_assistant");
    public static final KeyMapping TALK = new KeyMapping("key.mc_assistant.pa_talk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z,
        "key.categories.mc_assistant");
    public static final KeyMapping WHEEL = new KeyMapping("key.mc_assistant.pa_wheel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X,
        "key.categories.mc_assistant");

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        private Keys() {}

        @SubscribeEvent
        public static void onKeys(RegisterKeyMappingsEvent event) {
            event.register(PANEL);
            event.register(TALK);
            event.register(WHEEL);
        }
    }

    /** What the server last told us about our assistant (the panel draws it). */
    static CompoundTag state = new CompoundTag();
    /** Bumped whenever it changes, so an open panel knows to re-read it. */
    static int stamp;
    /** The choices it last offered (clickable chips under the talk box). */
    static List<String[]> choices = new ArrayList<>();
    private static long vetoAt;
    /** Chips to put up with the next line it says (a Guide answer's "show me"). */
    static List<String[]> pendingChips;
    /** A line from the system with no assistant to say it (the panel's footer). */
    static String note = "";
    static long noteAt;

    /** Bubbles over assistants' heads: entity id -> what, and till when (game time). */
    record Bubble(String text, long until) {}

    static final Map<Integer, Bubble> BUBBLES = new HashMap<>();

    /** Subtitles of what it said, for when it is out of sight (the HUD shows the latest). */
    static String subtitle = "";
    static long subtitleUntil;

    // ------------------------------------------------------------------ from the server

    public static void heard(int kind, CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case PaPayload.STATE -> {
                state = data;
                stamp++;
                PaToast.maybeShow(data.getBoolean("has"));
            }
            case PaPayload.OPEN -> {
                state = data;
                stamp++;
                PaScreen s = new PaScreen(data.getString("tab"));
                mc.setScreen(s);
                if (data.getBoolean("tour")) s.startTour();
            }
            case PaPayload.SAY -> said(mc, data);
            case PaPayload.GUIDE -> PaGuideAnswer.answer(data.getInt("id"), data.getString("q"));
            default -> { }
        }
    }

    private static void said(Minecraft mc, CompoundTag d) {
        String text = d.getString("text");
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        if (d.getBoolean("note")) {
            note = text;
            noteAt = System.currentTimeMillis();
            return;
        }
        int id = d.getInt("id");
        BUBBLES.put(id, new Bubble(text, now + Math.max(60, d.getInt("ticks"))));
        if (!d.getBoolean("owner")) return;
        // its owner: the talk log (until the server's own copy arrives), the chips, the subtitle, the voice
        ListTag log = state.getList("log", Tag.TAG_COMPOUND);
        CompoundTag line = new CompoundTag();
        line.putInt("w", 1);
        line.putString("t", text);
        log.add(line);
        state.put("log", log);
        List<String[]> before = choices;
        choices = new ArrayList<>();
        for (Tag t : d.getList("choices", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            choices.add(new String[]{c.getString("l"), c.getString("s")});
        }
        if (choices.isEmpty() && pendingChips != null) choices = pendingChips;
        // something it chose to do by itself keeps its "Don't do that" chip a while, even as it reports back
        boolean veto = before.stream().anyMatch(c -> c[1].startsWith("#veto"));
        if (choices.isEmpty() && veto && System.currentTimeMillis() - vetoAt < 60_000L) choices = before;
        if (choices.stream().anyMatch(c -> c[1].startsWith("#veto")) && choices != before) vetoAt = System.currentTimeMillis();
        pendingChips = null;
        stamp++;
        subtitle = d.getString("name") + ": " + text;
        subtitleUntil = System.currentTimeMillis() + 2500L + text.length() * 60L;
        if (d.getBoolean("speak")) PaVoice.speak(text);
    }

    /** The bubble over this assistant just now, or null. */
    public static String bubble(int id, long gameTime) {
        Bubble b = BUBBLES.get(id);
        if (b == null) return null;
        if (b.until() < gameTime) {
            BUBBLES.remove(id);
            return null;
        }
        return b.text();
    }

    // ------------------------------------------------------------------ to the server

    public static void send(String action, CompoundTag args) {
        if (Minecraft.getInstance().getConnection() == null) return;
        PacketDistributor.sendToServer(new PaActionPayload(action, args));
    }

    /** Say something to the assistant (typed, clicked or spoken), with what the player is looking at. */
    public static void talk(String text, boolean voice) {
        if (text == null || text.isBlank()) return;
        CompoundTag a = new CompoundTag();
        a.putString("text", text.trim());
        a.putBoolean("voice", voice);
        look(a);
        // shown at once in the log; the server's copy replaces it
        if (!text.startsWith("#")) {
            ListTag log = state.getList("log", Tag.TAG_COMPOUND);
            CompoundTag line = new CompoundTag();
            line.putInt("w", 0);
            line.putString("t", (voice ? "(voice) " : "") + text.trim());
            log.add(line);
            state.put("log", log);
        }
        choices = new ArrayList<>();                            // answered: the chips go (a veto's too)
        vetoAt = 0;
        stamp++;
        send("talk", a);
    }

    /** What the player looks at, far as an order means it (48 blocks for a block, 32 for a creature). */
    static void look(CompoundTag a) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 dir = mc.player.getViewVector(1.0F);
        HitResult block = mc.player.pick(48.0, 1.0F, false);
        double reach = block.getType() == HitResult.Type.BLOCK ? block.getLocation().distanceTo(eye) : 32.0;
        Vec3 end = eye.add(dir.scale(Math.min(32.0, reach)));
        AABB sweep = mc.player.getBoundingBox().expandTowards(dir.scale(32.0)).inflate(1.0);
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(mc.player, eye, end, sweep,
            e -> e.isAlive() && e.isPickable() && !(e instanceof PlayerAssistantEntity), 32.0 * 32.0);
        if (ent != null) a.putInt("ent", ent.getEntity().getId());
        if (block instanceof BlockHitResult bh && block.getType() == HitResult.Type.BLOCK) {
            a.putInt("bx", bh.getBlockPos().getX());
            a.putInt("by", bh.getBlockPos().getY());
            a.putInt("bz", bh.getBlockPos().getZ());
        }
    }

    /** Our assistant as the client sees it (by the id the server gave), or null. */
    public static PlayerAssistantEntity mine() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !state.contains("id")) return null;
        Entity e = mc.level.getEntity(state.getInt("id"));
        return e instanceof PlayerAssistantEntity a ? a : null;
    }

    public static boolean has() {
        return state.getBoolean("has");
    }

    public static String name() {
        return state.getString("name").isEmpty() ? "your assistant" : state.getString("name");
    }

    // ------------------------------------------------------------------ keys

    private static boolean talkDown;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (PANEL.consumeClick()) {
            if (mc.screen == null) {
                CompoundTag a = new CompoundTag();
                a.putString("tab", "talk");
                send("open", a);
            }
        }
        while (WHEEL.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new PaWheelScreen());
        }
        // hold to talk: only while no screen is open (the panel's own mic button does the same inside it)
        boolean down = mc.screen == null && TALK.isDown();
        if (down && !talkDown) PaVoice.startListening();
        if (!down && talkDown) PaVoice.stopListening();
        talkDown = down;
        PaVoice.tick();
    }

    // ------------------------------------------------------------------ the HUD line and the subtitle

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen instanceof PaScreen) return;
        GuiGraphics g = event.getGuiGraphics();
        PaHud.draw(g, mc);
    }
}
