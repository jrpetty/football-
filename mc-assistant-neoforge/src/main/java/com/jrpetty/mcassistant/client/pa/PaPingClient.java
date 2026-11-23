package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.PaPings;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [pa33c-2b] Pings, on the player's side. Tap the ping key (middle mouse) to drop a plain "look here" marker where you're
 * looking (128 blocks); it is only a marker, never an order. Hold it for the wheel of what to do with what you pointed at;
 * or, within five seconds of a tap, say or type an order (its "that" is the ping) or tap again to send its first option.
 * Shift+tap queues "go there" stops (a route). Markers are drawn over the world, kept on screen with an arrow when the
 * spot is behind you, coloured and shaped by kind, with a ring counting down the order window. Sneak, or no assistant,
 * and the middle button is vanilla pick block as ever (or always pick block: Settings).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PaPingClient {

    private PaPingClient() {}

    /** What the crosshair points at (block or creature), classified, with a name to show. */
    public record Aim(PaPings.Target target, @Nullable BlockPos block, int entity, Vec3 at, String label) {}

    /** A marker: where, what it says, its kind, when it went up, how long it stays, whether it is still waiting for an order. */
    static final class Marker {
        final Vec3 at;
        final String label;
        final PaPings.Sort sort;
        final long born;
        final long life;
        final Aim aim;
        boolean bare, held;
        int number;
        @Nullable Vec3 corner2;

        Marker(Vec3 at, String label, PaPings.Sort sort, long life, @Nullable Aim aim, boolean bare) {
            this.at = at;
            this.label = label;
            this.sort = sort;
            this.born = System.currentTimeMillis();
            this.life = life;
            this.aim = aim;
            this.bare = bare;
        }
    }

    static final List<Marker> MARKERS = new ArrayList<>();
    private static final int MAX_MARKERS = 12;
    private static long downAt = -1;
    private static boolean wheelOpened;
    @Nullable static Aim areaCorner;
    private static int routeNo;
    /** Places looked at while speaking (voice auto-ping), in order. */
    static final List<BlockPos> VOICE_LOOKS = new ArrayList<>();

    // ------------------------------------------------------------------ the key

    /** Is the middle button ours just now (not vanilla pick block)? */
    static boolean pingOwnsMiddle(Minecraft mc) {
        String mode = PaClientConfig.get(PaClientConfig.PINGMODE, "both");
        if (mode.equals("pick")) return false;
        if (mc.player == null || mc.player.isShiftKeyDown() && mode.equals("both")) return false;
        return PaClient.has();
    }

    /** Vanilla's pick block on the same button gives way to a ping (unless sneaking, no assistant, or pick-block mode). */
    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isPickBlock()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!PaClient.PING.getKey().equals(mc.options.keyPickItem.getKey())) return;   // rebound elsewhere: no clash
        if (pingOwnsMiddle(mc)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** Each client tick: the ping key's tap and hold, and voice looks while listening. */
    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean onMiddle = PaClient.PING.getKey().equals(mc.options.keyPickItem.getKey());
        boolean usable = mc.screen == null && (!onMiddle || pingOwnsMiddle(mc));
        boolean down = usable && PaClient.PING.isDown();
        long now = System.currentTimeMillis();
        if (down && downAt < 0) { downAt = now; wheelOpened = false; }
        if (down && !wheelOpened && downAt > 0 && now - downAt > 260) {         // held: the wheel
            wheelOpened = true;
            Aim aim = aim(mc);
            if (aim != null) mc.setScreen(new PaPingWheel(aim, areaCorner, true));
        }
        if (!down && downAt > 0) {
            if (!wheelOpened && now - downAt <= 260 && usable) tap(mc);
            downAt = -1;
        }
        while (PaClient.CANCEL_PING.consumeClick()) cancelLast();
        if (mc.screen == null) releaseTyping();
        MARKERS.removeIf(m -> now - m.born > m.life);
        if (PaVoice.listening && PaClientConfig.get(PaClientConfig.VOICEPING, true) && mc.player.tickCount % 5 == 0) {
            Aim a = aim(mc);
            if (a != null && a.block() != null) {
                BlockPos last = VOICE_LOOKS.isEmpty() ? null : VOICE_LOOKS.get(VOICE_LOOKS.size() - 1);
                if (last == null || !last.closerThan(a.block(), 3.0)) {
                    if (VOICE_LOOKS.size() < 6) VOICE_LOOKS.add(a.block());
                    if (VOICE_LOOKS.size() > 1) add(new Marker(Vec3.atCenterOf(a.block()), a.label(), PaPings.bare(), 8000, a, true));
                }
            }
        }
    }

    /** Speech starts: the place looked at now is what "that" means (the marker drops at once). */
    static void speechStarted(Minecraft mc) {
        VOICE_LOOKS.clear();
        if (!PaClientConfig.get(PaClientConfig.VOICEPING, true)) return;
        Aim a = aim(mc);
        if (a == null || a.block() == null) return;
        VOICE_LOOKS.add(a.block());
        add(new Marker(Vec3.atCenterOf(a.block()), a.label(), PaPings.bare(), 8000, a, true));
    }

    private static void tap(Minecraft mc) {
        Aim aim = aim(mc);
        if (aim == null) return;
        long now = System.currentTimeMillis();
        Marker fresh = freshBare();
        if (areaCorner != null) {                                         // the second corner of an area: its wheel
            mc.setScreen(new PaPingWheel(aim, areaCorner, false));
            return;
        }
        if (routeTap(mc)) {
            // shift: a stop on a route, sent at once ("go there", queued after the others)
            Marker m = new Marker(aim.at(), "Stop " + (++routeNo), PaPings.Sort.MOVE, 60000, aim, false);
            m.number = routeNo;
            add(m);
            send(aim, PaPings.option(PaPings.Target.GROUND, "go"), null);
            chime(mc, false);
            return;
        }
        if (fresh != null && fresh.aim != null && fresh.aim.block() != null && aim.block() != null && fresh.aim.block().closerThan(aim.block(), 2.5)
                && now - fresh.born < PaPings.WINDOW_MS) {
            fresh.bare = false;                                             // tapped again on it: its first option, sent
            send(fresh.aim, PaPings.quick(fresh.aim.target()), null);
            chime(mc, false);
            return;
        }
        bareMarker(mc, aim);
    }

    /** The wheel was let go in the middle: nothing chosen, just the marker. */
    static void tapFromWheel(Aim aim) {
        bareMarker(Minecraft.getInstance(), aim);
    }

    /** A plain marker ("look here"), shared with players near by if that is on; an order only with "Act on quick pings". */
    private static void bareMarker(Minecraft mc, Aim aim) {
        boolean act = PaClientConfig.get(PaClientConfig.ACTQUICK, false);
        Marker m = new Marker(aim.at(), aim.label(), act ? PaPings.quick(aim.target()).sort() : PaPings.bare(), PaPings.MARK_MS, aim, !act);
        add(m);
        chime(mc, aim.target() == PaPings.Target.MOB || aim.target() == PaPings.Target.LAVA);
        if (act) send(aim, PaPings.quick(aim.target()), null);
        else if (PaClientConfig.get(PaClientConfig.SHAREPINGS, false)) {
            CompoundTag t = new CompoundTag();
            if (aim.block() != null) { t.putInt("x", aim.block().getX()); t.putInt("y", aim.block().getY()); t.putInt("z", aim.block().getZ()); }
            t.putString("label", aim.label());
            PaClient.send("mark", t);
        }
    }

    /**
     * Shift held while the ping key is tapped: a route stop. On the middle button in "both" mode shift means vanilla pick
     * block, so there a route needs the ping key on another button or the setting "Ping only".
     */
    private static boolean routeTap(Minecraft mc) {
        if (!mc.player.isShiftKeyDown()) return false;
        boolean onMiddle = PaClient.PING.getKey().equals(mc.options.keyPickItem.getKey());
        return !onMiddle || !PaClientConfig.get(PaClientConfig.PINGMODE, "both").equals("both");
    }

    /** A wheel choice: sent to the server as the option's words with the ping as "that". */
    static void choose(Aim aim, PaPings.Option o, @Nullable Aim corner) {
        Minecraft mc = Minecraft.getInstance();
        if (o.id().equals("cancel")) { cancelLast(); return; }
        if (o.id().equals("area")) {
            areaCorner = aim;
            add(new Marker(aim.at(), "Corner 1: ping the other corner", PaPings.Sort.BUILD, 20000, aim, false));
            return;
        }
        Marker bare = freshBare();
        if (bare != null && bare.aim == aim) bare.bare = false;
        Marker m = new Marker(aim.at(), o.label(), o.sort(), 15000, aim, false);
        if (corner != null) {
            m.corner2 = corner.at();
            add(new Marker(corner.at(), "Corner", o.sort(), 15000, corner, false));
        }
        add(m);
        areaCorner = null;
        send(aim, o, corner);
        chime(mc, o.sort() == PaPings.Sort.ATTACK || o.sort() == PaPings.Sort.DANGER);
    }

    private static void send(Aim aim, PaPings.Option o, @Nullable Aim corner) {
        if (o == null) return;
        CompoundTag t = new CompoundTag();
        t.putString("target", (corner != null ? PaPings.Target.AREA : aim.target()).name());
        t.putString("opt", o.id());
        t.putString("label", aim.label());
        BlockPos b = aim.block() != null ? aim.block() : BlockPos.containing(aim.at());
        t.putInt("x", b.getX());
        t.putInt("y", b.getY());
        t.putInt("z", b.getZ());
        if (aim.entity() >= 0) t.putInt("ent", aim.entity());
        if (corner != null) {
            BlockPos c = corner.block() != null ? corner.block() : BlockPos.containing(corner.at());
            t.putInt("x2", c.getX());
            t.putInt("y2", c.getY());
            t.putInt("z2", c.getZ());
        }
        PaClient.send("ping", t);
    }

    static void cancelLast() {
        if (!MARKERS.isEmpty()) MARKERS.remove(MARKERS.size() - 1);
        areaCorner = null;
    }

    /** The panel opened on a fresh ping: it waits while the order is typed (the window would run out mid-sentence). */
    static void holdForTyping() {
        Marker m = freshBare();
        if (m != null) m.held = true;
    }

    /** Back in the world: a held ping that got no order is a plain marker again. */
    static void releaseTyping() {
        for (Marker m : MARKERS) if (m.held) { m.held = false; m.bare = false; }
    }

    /** The last bare ping, still in its window: what an order said or typed now points at. */
    @Nullable
    static Marker freshBare() {
        long now = System.currentTimeMillis();
        for (int i = MARKERS.size() - 1; i >= 0; i--) {
            Marker m = MARKERS.get(i);
            if (m.bare && (m.held || now - m.born <= PaPings.WINDOW_MS)) return m;
        }
        return null;
    }

    /** An order is being sent: bind it to a fresh ping or to what was looked at while speaking. True if bound. */
    static boolean bind(CompoundTag talk, boolean voice) {
        Marker m = freshBare();
        List<BlockPos> looks = voice ? new ArrayList<>(VOICE_LOOKS) : new ArrayList<>();
        long now = System.currentTimeMillis();
        if (m != null && m.aim != null && PaPings.binds(m.held ? now : m.born, now, talk.getString("text"))) {
            m.bare = false;
            m.held = false;
            if (looks.isEmpty() && m.aim.block() != null) looks.add(m.aim.block());
            if (m.aim.block() != null) { talk.putInt("bx", m.aim.block().getX()); talk.putInt("by", m.aim.block().getY()); talk.putInt("bz", m.aim.block().getZ()); }
            if (m.aim.entity() >= 0) talk.putInt("ent", m.aim.entity());
        } else if (!looks.isEmpty()) {
            BlockPos b = looks.get(0);
            talk.putInt("bx", b.getX());
            talk.putInt("by", b.getY());
            talk.putInt("bz", b.getZ());
        }
        if (looks.size() > 1) {
            int[] arr = new int[looks.size() * 3];
            for (int i = 0; i < looks.size(); i++) { arr[3 * i] = looks.get(i).getX(); arr[3 * i + 1] = looks.get(i).getY(); arr[3 * i + 2] = looks.get(i).getZ(); }
            talk.putIntArray("looks", arr);
        }
        if (voice) VOICE_LOOKS.clear();
        return m != null || !looks.isEmpty();
    }

    /** A marker from the server (found, saved, shared) or "clear"/"unlast". */
    static void fromServer(CompoundTag t) {
        String op = t.getString("op");
        if (op.equals("clear")) { MARKERS.clear(); areaCorner = null; return; }
        if (op.equals("unlast")) { cancelLast(); return; }
        if (!t.contains("x")) return;
        PaPings.Sort sort;
        try { sort = PaPings.Sort.valueOf(t.getString("sort")); } catch (Exception e) { sort = PaPings.Sort.LOOK; }
        add(new Marker(Vec3.atCenterOf(new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"))), t.getString("label"), sort,
            op.equals("waypoint") ? 12000 : op.equals("shared") ? PaPings.MARK_MS : 45000, null, false));
    }

    private static void add(Marker m) {
        MARKERS.add(m);
        while (MARKERS.size() > MAX_MARKERS) MARKERS.remove(0);
    }

    private static void chime(Minecraft mc, boolean alarm) {
        if (mc.player != null) mc.player.playSound(alarm ? SoundEvents.NOTE_BLOCK_BASS.value() : SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6F, alarm ? 0.7F : 1.6F);
    }

    // ------------------------------------------------------------------ what the crosshair points at

    /** What the player points at, 128 blocks out: the nearer of a block (fluids count) and a creature. */
    @Nullable
    public static Aim aim(Minecraft mc) {
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.level == null) return null;
        Vec3 eye = cam.getEyePosition(1.0F), dir = cam.getViewVector(1.0F), end = eye.add(dir.scale(128));
        BlockHitResult bh = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, cam));
        double reach = bh.getType() == HitResult.Type.MISS ? 128 * 128 : bh.getLocation().distanceToSqr(eye);
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(cam, eye, eye.add(dir.scale(Math.sqrt(reach))),
            cam.getBoundingBox().expandTowards(dir.scale(128)).inflate(1.0), e -> !e.isSpectator() && e.isPickable() && e != mc.player, reach);
        if (eh != null) {
            Entity e = eh.getEntity();
            PaPings.Target t = e instanceof PlayerAssistantEntity ? PaPings.Target.ASSISTANT : e instanceof Player ? PaPings.Target.PLAYER
                : e instanceof net.minecraft.world.entity.npc.AbstractVillager ? PaPings.Target.VILLAGER
                : e instanceof Enemy ? PaPings.Target.MOB : e instanceof Animal ? PaPings.Target.ANIMAL : PaPings.Target.MOB;
            return new Aim(t, e.blockPosition(), e.getId(), e.position().add(0, e.getBbHeight() / 2, 0), e.getName().getString());
        }
        if (bh.getType() == HitResult.Type.MISS) return null;
        BlockPos p = bh.getBlockPos();
        BlockState s = mc.level.getBlockState(p);
        if (s.is(BlockTags.LEAVES)) {                                // the crown: the tree it belongs to (its nearest log)
            BlockPos log = nearestLog(mc, p);
            if (log != null) { p = log; s = mc.level.getBlockState(log); }
        }
        PaPings.Target t = classify(s);
        return new Aim(t, p, -1, bh.getLocation(), label(mc, p, s, t));
    }

    @Nullable
    private static BlockPos nearestLog(Minecraft mc, BlockPos leaf) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(leaf.offset(-3, -4, -3), leaf.offset(3, 1, 3))) {
            if (!mc.level.getBlockState(q).is(BlockTags.LOGS)) continue;
            double d = q.distSqr(leaf);
            if (d < bd) { bd = d; best = q.immutable(); }
        }
        return best;
    }

    static PaPings.Target classify(BlockState s) {
        if (s.getFluidState().is(FluidTags.LAVA)) return PaPings.Target.LAVA;
        if (s.getFluidState().is(FluidTags.WATER) && s.isAir() || s.getFluidState().is(FluidTags.WATER) && s.canBeReplaced()) return PaPings.Target.WATER;
        if (s.is(BlockTags.LOGS)) return PaPings.Target.TREE;
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.endsWith("_ore") || path.equals("ancient_debris")) return PaPings.Target.ORE;
        if (s.getBlock() instanceof net.minecraft.world.level.block.BedBlock) return PaPings.Target.BED;
        if (s.getBlock() instanceof net.minecraft.world.level.block.EnchantingTableBlock) return PaPings.Target.TABLE;   // [pa33d]
        if (s.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock) return PaPings.Target.FURNACE;
        if (s.getBlock() instanceof net.minecraft.world.level.block.ChestBlock || s.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)
            return PaPings.Target.CHEST;
        if (s.getBlock() instanceof net.minecraft.world.level.block.CropBlock || s.is(net.minecraft.world.level.block.Blocks.FARMLAND)) return PaPings.Target.CROP;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) return PaPings.Target.STONE;
        return PaPings.Target.GROUND;
    }

    /** "Iron ore ×7 vein", "Oak tree", "Chest", "Ground". */
    static String label(Minecraft mc, BlockPos p, BlockState s, PaPings.Target t) {
        String name = s.getBlock().getName().getString();
        return switch (t) {
            case ORE -> name + " ×" + vein(mc, p, s) + " vein";
            case TREE -> name.replace(" Log", "").replace(" Stem", "") + " tree";
            case GROUND -> "Ground";
            case WATER -> "Water";
            case LAVA -> "Lava";
            default -> name;
        };
    }

    private static int vein(Minecraft mc, BlockPos start, BlockState s) {
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && seen.size() < 64) {
            BlockPos c = q.poll();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockPos n = c.relative(d);
                if (!seen.contains(n) && mc.level.getBlockState(n).is(s.getBlock())) { seen.add(n); q.add(n); }
            }
        }
        return seen.size();
    }

    // ------------------------------------------------------------------ drawing the markers

    record Spot(Marker m, float x, float y, boolean on, double dist) {}

    private static final List<Spot> SPOTS = new ArrayList<>();

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        SPOTS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || MARKERS.isEmpty()) return;
        Vec3 cam = event.getCamera().getPosition();
        Matrix4f mvp = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        if (event.getPoseStack() != null) mvp.mul(event.getPoseStack().last().pose());
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        for (Marker m : MARKERS) {
            Vec3 at = m.at.add(0, 0.6 + 0.15 * Math.sin((System.currentTimeMillis() - m.born) / 300.0), 0);   // bobbing
            Vector4f v = new Vector4f((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z), 1.0F);
            mvp.transform(v);
            boolean front = v.w > 0.05F;
            float sx = front ? (v.x / v.w + 1) / 2 * gw : (v.x < 0 ? gw : 0), sy = front ? (1 - v.y / v.w) / 2 * gh : gh / 2F;
            boolean on = front && sx >= 0 && sx <= gw && sy >= 0 && sy <= gh;
            SPOTS.add(new Spot(m, sx, sy, on, at.distanceTo(cam)));
        }
    }

    /** Draw the markers with the HUD: a shape in the ping's colour, its words and distance, and an arrow at the edge if off screen. */
    static void draw(GuiGraphics g, Minecraft mc) {
        if (SPOTS.isEmpty()) return;
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        long now = System.currentTimeMillis();
        for (Spot s : SPOTS) {
            Marker m = s.m();
            float age = (now - m.born) / (float) m.life;
            int alpha = (int) (255 * Math.min(1, Math.min((now - m.born) / 150F, (1 - age) * 4)));      // fade in, and out at the end
            if (alpha <= 8) continue;
            int col = (m.bare ? 0xFFB8C4C2 : m.sort.colour) & 0x00FFFFFF | (alpha << 24);
            int x = Math.round(Math.max(10, Math.min(w - 10, s.x()))), y = Math.round(Math.max(10, Math.min(h - 22, s.y())));
            shape(g, m.bare ? "ring" : m.sort.shape, x, y, col);
            if (m.number > 0) g.drawCenteredString(font, "" + m.number, x, y - 4, 0xFFFFFFFF & (0x00FFFFFF | (alpha << 24)));
            if (m.bare && now - m.born < PaPings.WINDOW_MS) {               // the order window, counting down
                float left = 1 - (now - m.born) / (float) PaPings.WINDOW_MS;
                ring(g, x, y, 9, left, 0xFFFFFFFF & (0x00FFFFFF | (alpha << 24)));
            }
            String text = m.label + "  " + Math.round(s.dist()) + "m";
            if (!s.on()) text = (s.x() <= 10 ? "◀ " : s.x() >= w - 10 ? "" : "") + text + (s.x() >= w - 10 ? " ▶" : "");
            int tw = font.width(text);
            int tx = Math.max(4, Math.min(w - 4 - tw, x - tw / 2));
            g.fill(tx - 2, y + 8, tx + tw + 2, y + 19, (alpha * 3 / 4) << 24);
            g.drawString(font, text, tx, y + 10, 0x00FFFFFF | (alpha << 24), false);
            if (m.bare && now - m.born < PaPings.WINDOW_MS && m == freshBare()) {
                String hint = "Tap again to send " + PaClient.name() + ", or say it";
                int hw = font.width(hint);
                g.drawString(font, hint, Math.max(4, Math.min(w - 4 - hw, x - hw / 2)), y + 21, 0x00DDE8E6 | (alpha << 24), false);
            }
        }
    }

    /** One shape per kind, so colour is never the only clue. */
    static void shape(GuiGraphics g, String shape, int x, int y, int col) {
        int dark = (col & 0xFF000000) | 0x101010;
        switch (shape) {
            case "diamond" -> { for (int i = -6; i <= 6; i++) { int r = 6 - Math.abs(i); g.fill(x - r - 1, y + i, x + r + 2, y + i + 1, dark); g.fill(x - r, y + i, x + r + 1, y + i + 1, col); } }
            case "square" -> { g.fill(x - 6, y - 6, x + 7, y + 7, dark); g.fill(x - 5, y - 5, x + 6, y + 6, col); }
            case "triangle" -> { for (int i = 0; i <= 10; i++) { int r = i / 2; g.fill(x - r - 1, y - 6 + i, x + r + 2, y - 5 + i, dark); g.fill(x - r, y - 6 + i, x + r + 1, y - 5 + i, col); } }
            case "hexagon" -> { g.fill(x - 4, y - 6, x + 5, y + 7, col); g.fill(x - 6, y - 4, x + 7, y + 5, col); }
            case "ring" -> { ring(g, x, y, 6, 1, col); ring(g, x, y, 5, 1, col); }
            default -> { for (int i = -5; i <= 5; i++) { int r = (int) Math.round(Math.sqrt(25 - i * i)); g.fill(x - r, y + i, x + r + 1, y + i + 1, col); } }
        }
    }

    /** A ring (or part of one, clockwise from the top), drawn with small dots. */
    static void ring(GuiGraphics g, int x, int y, int r, float part, int col) {
        int n = Math.max(8, r * 6);
        for (int i = 0; i < n * part; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / n;
            int px = x + (int) Math.round(Math.cos(a) * r), py = y + (int) Math.round(Math.sin(a) * r);
            g.fill(px, py, px + 1, py + 1, col);
        }
    }

    /** A label for the talk box: "Ping: Iron ore ×7 vein, 40 blocks NE" while a bare ping waits. */
    @Nullable
    static String chip(Minecraft mc) {
        Marker m = freshBare();
        if (m == null || mc.player == null) return null;
        double dx = m.at.x - mc.player.getX(), dz = m.at.z - mc.player.getZ();
        return "Ping: " + m.label + ", " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks " + compass(dx, dz) + " (say \"that\")";
    }

    static String compass(double dx, double dz) {
        String[] n = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        double ang = Math.toDegrees(Math.atan2(-dx, dz));
        return n[(int) Math.floorMod(Math.round(ang / 45.0), 8L)];
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
