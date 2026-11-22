package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * [vision27] The film camera: the town shot from the air, a folk followed through its day, a flight along points the
 * player set, the camera taken to wherever something is happening. Opened from the camera screen (CameraScreen: the
 * camera key, or the button on the U page); nothing is typed.
 *
 * <p>How it moves the picture: the game draws the world from its "camera entity", which is the player; while a shot
 * runs, that is a marker of our own (never added to the world, so nothing else knows of it), placed and turned each
 * frame before the frame is drawn. Its place is worked out from the clock afresh each frame, not each tick, and it is
 * stood still between frames (its last place the same as its place), so the picture moves as smoothly as the frames
 * come, sixty a second or more, whatever the game's tick. The followed folk and the orbit's middle are read where the
 * game draws them (between their last two ticks), and everything the camera aims at is eased towards, never jumped to.
 *
 * <ul>
 * <li><b>Orbit</b>: round a point (the town's heart, the spot something is happening) or a folk, at a radius, height
 *     and speed; lifted clear of hills and roofs in its way.</li>
 * <li><b>Fly-through</b>: two to six points (where the camera stood and which way it looked when each was set), flown
 *     along a smooth curve at an even pace, easing in at the start and out at the end, banking a little into turns.</li>
 * <li><b>Follow</b>: over the shoulder of one folk, a few blocks behind, swinging round slowly as it turns, pulled in
 *     short of a wall rather than through it.</li>
 * </ul>
 *
 * Escape always ends the shot and gives the player back its own eyes (and anything else that opens a screen does too,
 * save the camera screen itself, which can be opened over a running shot to change it as it plays). While a shot runs:
 * Space pauses, the arrow keys change speed and height, the mouse wheel the distance, H hides the keys' reminder. The
 * player's own body, hand and crosshair are left out of the picture; with "clean shot" (the default) the whole HUD,
 * the folk's name tags and every label of the mod's are too, as F1 does.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class CinemaCam {

    private CinemaCam() {}

    public enum Mode { OFF, ORBIT, PATH, FOLLOW }

    // ------------------------------------------------------------------ the settings (the screen's), kept for the session

    public static float radius = 26.0F, height = 14.0F, speed = 5.0F, lens = 70.0F;
    public static float followDist = 5.5F, followHeight = 2.2F;
    public static float pathSeconds = 20.0F;
    public static boolean loop, clean = true;
    /** Follow from beside the folk (its right), not behind it: a walk seen side on (CineDev "side"). */
    public static boolean followSide;
    /** [dev] Time the shot by the world's clock, not the wall's: for frame-by-frame recording (CinemaRecorder). */
    public static boolean worldClock;

    public static final int MOST_POINTS = 6;

    /** A fly-through's point: where the camera's eyes were and which way they looked. */
    public record Point(Vec3 pos, float yaw, float pitch) {}

    public static final List<Point> POINTS = new ArrayList<>();

    // ------------------------------------------------------------------ the shot running

    private static Mode mode = Mode.OFF;
    private static Marker cam;
    private static Vec3 centreGoal = Vec3.ZERO, centre = Vec3.ZERO;
    private static int target = -1;
    private static double angle, travelled, lift, followYaw, pull = 1.0;
    private static boolean paused, started;
    private static long lastNs, lostAt;
    private static double lastWorld = -1.0;
    private static Vec3 eye = Vec3.ZERO, look = Vec3.ZERO;
    private static float roll, lastPathYaw;
    private static CameraType typeBefore = CameraType.FIRST_PERSON;
    private static boolean hideBefore;
    private static String caption = "";
    private static long captionAt, hintAt;
    private static boolean hint = true;
    private static long hintFor = 4500L;
    private static int shots;
    private static Spline spline;

    public static boolean active() {
        return mode != Mode.OFF;
    }

    public static Mode mode() {
        return mode;
    }

    /** The folk (or ball) the shot keeps in the picture, or -1. */
    public static int target() {
        return target;
    }

    /** [dev] No keys' reminder at all (CineDev, for recording). */
    public static void hints(boolean on) {
        hint = on;
    }

    public static boolean paused() {
        return paused;
    }

    /** A clean shot running: no HUD, no labels (CameraScreen's tick box; F1 while it runs changes it too). */
    public static boolean cleanShot() {
        return active() && Minecraft.getInstance().options.hideGui;
    }

    // ------------------------------------------------------------------ starting and stopping

    /** Orbit round this point, or round this entity while it can be seen (entity -1: the point). */
    public static void orbit(Vec3 at, int entity, String label) {
        centreGoal = at;
        centre = at;
        target = entity;
        if (!begin(Mode.ORBIT, label)) return;
        // Start round the point from where the camera is now, so it glides in rather than jumping half way round.
        Vec3 from = eye.subtract(at);
        angle = Math.toDegrees(Math.atan2(from.x, from.z));
        lift = 0.0;
        pull = 1.0;
    }

    /** Follow this folk over its shoulder. */
    public static void follow(int entity, String label) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(entity);
        if (e == null) {
            say("That folk is out of sight.");
            return;
        }
        target = entity;
        centreGoal = e.position();
        centre = centreGoal;
        followYaw = yawOf(e);
        lostAt = 0L;
        pull = 1.0;
        begin(Mode.FOLLOW, label);
    }

    /** Fly along the points set (at least two). */
    public static boolean flyThrough() {
        if (POINTS.size() < 2) {
            say("Set at least two points first.");
            return false;
        }
        List<Point> route = new ArrayList<>(POINTS);
        if (loop && route.size() >= 3) route.add(route.get(0));    // round and back to the first, to go again
        spline = new Spline(route);
        travelled = 0.0;
        target = -1;
        if (!begin(Mode.PATH, "Fly-through: " + POINTS.size() + " points, " + Math.round(pathSeconds) + " s")) return false;
        Point p0 = POINTS.get(0);
        eye = p0.pos();
        lastPathYaw = p0.yaw();
        return true;
    }

    private static boolean begin(Mode m, String label) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        boolean was = active();
        if (!was) {
            Camera c = mc.gameRenderer.getMainCamera();
            eye = c.getPosition();
            look = eye.add(Vec3.directionFromRotation(c.getXRot(), c.getYRot()).scale(8.0));
            typeBefore = mc.options.getCameraType();
            hideBefore = mc.options.hideGui;
        }
        if (cam == null || cam.level() != mc.level) cam = new Marker(EntityType.MARKER, mc.level);
        place(eye, look, 0.0F);
        mode = m;
        paused = false;
        started = false;
        lastNs = 0L;
        lastWorld = -1.0;
        roll = 0.0F;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.hideGui = clean;
        mc.setCameraEntity(cam);
        eyeHeight(mc.gameRenderer.getMainCamera(), 0.0F);
        caption = label == null ? "" : label;
        captionAt = System.currentTimeMillis();
        hintAt = captionAt;
        hintFor = shots++ == 0 ? 4500L : 2000L;                 // the keys told at length the first time, briefly after
        return true;
    }

    /** End the shot: the player's own eyes back, and its HUD as it had it. */
    public static void stop() {
        if (mode == Mode.OFF) return;
        mode = Mode.OFF;
        target = -1;
        Minecraft mc = Minecraft.getInstance();
        mc.options.setCameraType(typeBefore == null ? CameraType.FIRST_PERSON : typeBefore);
        mc.options.hideGui = hideBefore;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            eyeHeight(mc.gameRenderer.getMainCamera(), mc.player.getEyeHeight());
        }
        cam = null;
    }

    /** A word on the screen for a moment (also while no shot runs: the camera screen's answers). */
    public static void say(String words) {
        caption = words;
        captionAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ the frame

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (mode == Mode.OFF) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || cam == null || cam.level() != mc.level || mc.getCameraEntity() != cam) {
            stop();
            return;
        }
        DeltaTracker dtk = event.getPartialTick();
        float partial = dtk.getGameTimeDeltaPartialTick(false);
        double dt = clock(mc.level, partial);
        if (mc.screen == null) keys(mc, dt);
        if (paused) dt = 0.0;
        switch (mode) {
            case ORBIT -> orbitFrame(mc.level, partial, dt);
            case FOLLOW -> followFrame(mc.level, partial, dt);
            case PATH -> pathFrame(dt);
            default -> { }
        }
    }

    /** Seconds since the last frame, by the wall's clock (or the world's, for recording); none on the first. */
    private static double clock(Level level, float partial) {
        double dt;
        if (worldClock) {
            double w = level.getGameTime() + partial;
            dt = lastWorld < 0.0 ? 0.0 : Mth.clamp((w - lastWorld) / 20.0, 0.0, 0.25);
            lastWorld = w;
        } else {
            long now = System.nanoTime();
            dt = lastNs == 0L ? 0.0 : Mth.clamp((now - lastNs) / 1.0E9, 0.0, 0.1);
            lastNs = now;
        }
        return dt;
    }

    private static void orbitFrame(Level level, float partial, double dt) {
        Entity t = target >= 0 ? level.getEntity(target) : null;
        if (t != null && t.isAlive()) centreGoal = t.getPosition(partial);
        centre = ease(centre, centreGoal, dt, 1.6);
        angle += speed * dt;
        double a = Math.toRadians(angle);
        Vec3 base = centre.add(Math.sin(a) * radius, height, Math.cos(a) * radius);
        Vec3 aim = centre.add(0.0, t instanceof LivingEntity ? 1.1 : 0.8, 0.0);
        // Close round a folk (or tight in), it comes in short of a wall; round a building or a spot farther out it rises over
        // what is in the way instead (a house going up is watched from outside it, not from its floor).
        boolean close = radius <= 14.0F || t instanceof LivingEntity && radius <= 24.0F;
        lift = lift + (clearance(level, base, aim, !close) - lift) * (1.0 - Math.exp(-dt * 0.9));
        Vec3 want = base.add(0.0, lift, 0.0);
        // Close in: a wall, a roof or a tree between the camera and what it watches pulls it in short of it (quickly in,
        // slowly back out), rather than lifting it up over the roofs and away from the folk.
        double reach = 1.0;
        if (close) {
            Vec3 out = want.subtract(aim);
            double len = out.length();
            HitResult hit = level.clip(new ClipContext(aim, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            // Leaves are looked through from above, not come in under (a woodcutter under the canopy).
            if (hit.getType() == HitResult.Type.BLOCK && level.getBlockState(((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos()).is(net.minecraft.tags.BlockTags.LEAVES)) {
                reach = 1.0;
            } else if (hit.getType() != HitResult.Type.MISS && len > 0.01) {
                reach = Mth.clamp((hit.getLocation().distanceTo(aim) - 0.5) / len, 2.5 / len, 1.0);
            }
        }
        pull = pull + (reach - pull) * (1.0 - Math.exp(-dt * (reach < pull ? 5.0 : 1.2)));
        want = aim.add(want.subtract(aim).scale(pull));
        double k = started ? 4.0 : 1.4;                         // a glide into the orbit, then held close to it
        eye = ease(eye, want, dt, k);
        look = ease(look, aim, dt, 5.0);
        if (eye.distanceToSqr(want) < 0.25) started = true;
        place(eye, look, 0.0F);
    }

    private static void followFrame(Level level, float partial, double dt) {
        Entity t = level.getEntity(target);
        if (t == null || !t.isAlive()) {
            if (lostAt == 0L) lostAt = System.currentTimeMillis();
            if (System.currentTimeMillis() - lostAt > 1500L) {
                // Gone indoors out of reach, or away: keep circling where it was last seen.
                mode = Mode.ORBIT;
                target = -1;
                radius = Math.max(10.0F, followDist * 2.0F);
                height = Math.max(5.0F, followHeight * 2.0F);
                say("Lost sight of them: circling where they were.");
            }
            place(eye, look, 0.0F);
            return;
        }
        lostAt = 0L;
        Vec3 feet = t.getPosition(partial);
        Vec3 head = feet.add(0.0, t.getEyeHeight(), 0.0);
        // Asleep, sat down or at its work in one spot, its body turns about: hold the view rather than swing with it.
        boolean still = t instanceof LivingEntity le && (le.isSleeping() || t.getPose() == net.minecraft.world.entity.Pose.SITTING)
            || (t instanceof VillageFolkEntity vf ? vf.animSeen().gaitSpeed < 0.5F : t.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4);
        if (!still) followYaw = easeAngle(followYaw, yawOf(t), dt, 1.1);
        double y = Math.toRadians(followYaw);
        Vec3 forward = new Vec3(-Math.sin(y), 0.0, Math.cos(y));
        Vec3 right = new Vec3(-Math.cos(y), 0.0, -Math.sin(y));
        Vec3 want = followSide ? head.add(right.scale(-followDist)).add(forward.scale(0.4)).add(0.0, followHeight - 1.0, 0.0)
            : head.add(forward.scale(-followDist)).add(right.scale(0.7)).add(0.0, followHeight - 1.0, 0.0);
        // A wall behind it pulls the camera in (quickly), and lets it back out slowly: never a jump to and fro as the
        // folk passes a door post.
        HitResult hit = level.clip(new ClipContext(head, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        double len = want.distanceTo(head), reach = 1.0;
        if (hit.getType() != HitResult.Type.MISS && len > 0.01) reach = Mth.clamp((hit.getLocation().distanceTo(head) - 0.35) / len, 0.12, 1.0);
        pull = pull + (reach - pull) * (1.0 - Math.exp(-dt * (reach < pull ? 7.0 : 0.8)));
        want = head.add(want.subtract(head).scale(Math.min(pull, reach + 0.08)));
        Vec3 aim = followSide ? head.add(0.0, -0.7, 0.0) : head.add(forward.scale(1.2)).add(0.0, -0.25, 0.0);
        eye = ease(eye, want, dt, started ? 3.2 : 1.6);
        look = ease(look, aim, dt, 6.0);
        if (eye.distanceToSqr(want) < 0.5) started = true;
        place(eye, look, 0.0F);
    }

    private static void pathFrame(double dt) {
        if (spline == null) return;
        travelled += dt;
        double u = travelled / Math.max(1.0, pathSeconds);
        if (u >= 1.0) {
            if (loop) {
                travelled -= pathSeconds;
                u -= 1.0;
            } else {
                u = 1.0;
                travelled = pathSeconds;
            }
        }
        double s = loop ? u : u * u * u * (u * (u * 6.0 - 15.0) + 10.0);     // eased in and out
        Spline.Pose p = spline.at(s);
        eye = p.pos();
        float turn = dt > 0.0 ? (float) (Mth.wrapDegrees(p.yaw() - lastPathYaw) / dt) : 0.0F;
        lastPathYaw = p.yaw();
        float bank = Mth.clamp(-turn * 0.12F, -7.0F, 7.0F);
        roll = roll + (bank - roll) * (float) (1.0 - Math.exp(-dt * 2.5));
        Vec3 dir = Vec3.directionFromRotation(p.pitch(), p.yaw());
        look = eye.add(dir.scale(8.0));
        cam.setPos(eye.x, eye.y, eye.z);
        sync(p.yaw(), p.pitch());
    }

    /** The camera stood at {@code at}, looking at {@code to}. */
    private static void place(Vec3 at, Vec3 to, float r) {
        if (cam == null) return;
        Vec3 d = to.subtract(at);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.max(1.0E-4, flat)));
        cam.setPos(at.x, at.y, at.z);
        sync(yaw, Mth.clamp(pitch, -89.0F, 89.0F));
    }

    /** Stood still between frames: its last place and turn the same as its present ones. */
    private static void sync(float yaw, float pitch) {
        cam.xo = cam.xOld = cam.getX();
        cam.yo = cam.yOld = cam.getY();
        cam.zo = cam.zOld = cam.getZ();
        cam.setYRot(yaw);
        cam.setXRot(pitch);
        cam.yRotO = yaw;
        cam.xRotO = pitch;
    }

    /** How far up from {@code at} the camera must go to stand in the open and see {@code aim} (blocks, up to 40). */
    private static double clearance(Level level, Vec3 at, Vec3 aim, boolean sight) {
        for (int up = 0; up <= 40; up += 2) {
            Vec3 p = at.add(0.0, up, 0.0);
            BlockPos b = BlockPos.containing(p);
            if (!level.isLoaded(b)) return up;
            if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) continue;
            if (!sight) return up;
            HitResult hit = level.clip(new ClipContext(p, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(aim) < 9.0) return up;
        }
        return 40.0;
    }

    private static double yawOf(Entity e) {
        return e instanceof LivingEntity l ? l.yBodyRot : e.getYRot();
    }

    /** Eased towards: the further off, the faster, never past it; {@code k} is how quickly (per second). */
    private static Vec3 ease(Vec3 from, Vec3 to, double dt, double k) {
        double f = 1.0 - Math.exp(-dt * k);
        return from.add(to.subtract(from).scale(f));
    }

    private static double easeAngle(double from, double to, double dt, double k) {
        return from + Mth.wrapDegrees(to - from) * (1.0 - Math.exp(-dt * k));
    }

    /** The game's camera keeps its own eye height, eased each tick towards its entity's: set it outright (no dip). */
    private static void eyeHeight(Camera c, float h) {
        try {
            if (EYE == null) {
                EYE = Camera.class.getDeclaredField("eyeHeight");
                EYE_OLD = Camera.class.getDeclaredField("eyeHeightOld");
                EYE.setAccessible(true);
                EYE_OLD.setAccessible(true);
            }
            EYE.setFloat(c, h);
            EYE_OLD.setFloat(c, h);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Only a dip for a moment at the start: nothing to do.
        }
    }

    private static Field EYE, EYE_OLD;

    // ------------------------------------------------------------------ keys while it runs

    private static void keys(Minecraft mc, double dt) {
        long w = mc.getWindow().getWindow();
        float f = (float) dt;
        if (InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT)) speed = Mth.clamp(speed - 10.0F * f, -40.0F, 40.0F);
        if (InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT)) speed = Mth.clamp(speed + 10.0F * f, -40.0F, 40.0F);
        boolean up = InputConstants.isKeyDown(w, GLFW.GLFW_KEY_UP), down = InputConstants.isKeyDown(w, GLFW.GLFW_KEY_DOWN);
        if (mode == Mode.FOLLOW) {
            if (up) followHeight = Mth.clamp(followHeight + 2.0F * f, 0.0F, 12.0F);
            if (down) followHeight = Mth.clamp(followHeight - 2.0F * f, 0.0F, 12.0F);
        } else {
            if (up) height = Mth.clamp(height + 8.0F * f, 1.0F, 80.0F);
            if (down) height = Mth.clamp(height - 8.0F * f, 1.0F, 80.0F);
        }
        if (mode == Mode.PATH) {
            if (InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT)) pathSeconds = Mth.clamp(pathSeconds + 6.0F * f, 4.0F, 120.0F);
            if (InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT)) pathSeconds = Mth.clamp(pathSeconds - 6.0F * f, 4.0F, 120.0F);
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.OFF || mc.screen != null || event.getAction() != GLFW.GLFW_PRESS) return;
        switch (event.getKey()) {
            case GLFW.GLFW_KEY_SPACE -> {
                if (mode == Mode.PATH && !loop && travelled >= pathSeconds) {
                    travelled = 0.0;
                    paused = false;
                } else {
                    paused = !paused;
                }
                say(paused ? "Paused" : "");
            }
            case GLFW.GLFW_KEY_H -> {
                hint = !hint;
                hintAt = System.currentTimeMillis();
                hintFor = 4500L;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> travelled = 0.0;
            default -> { }
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.OFF || mc.screen != null) return;
        event.setCanceled(true);
        float k = (float) Math.pow(0.9, event.getScrollDeltaY());
        switch (mode) {
            case ORBIT -> radius = Mth.clamp(radius * k, 4.0F, 160.0F);
            case FOLLOW -> followDist = Mth.clamp(followDist * k, 1.5F, 24.0F);
            default -> lens = Mth.clamp(lens * k, 20.0F, 100.0F);
        }
    }

    /** The player's own keys that would act in the world from where it stands (drop, use, swap): not while it films. */
    @SubscribeEvent
    public static void onTick(ClientTickEvent.Pre event) {
        if (mode == Mode.OFF) return;
        var o = Minecraft.getInstance().options;
        while (o.keyDrop.consumeClick()) { }
        while (o.keySwapOffhand.consumeClick()) { }
        while (o.keyAttack.consumeClick()) { }
        while (o.keyUse.consumeClick()) { }
        while (o.keyPickItem.consumeClick()) { }
        while (o.keyTogglePerspective.consumeClick()) { }
    }

    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (mode != Mode.OFF) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    // ------------------------------------------------------------------ the picture

    @SubscribeEvent
    public static void onAngles(ViewportEvent.ComputeCameraAngles event) {
        if (mode == Mode.PATH) event.setRoll(roll);
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (mode != Mode.OFF && event.usedConfiguredFov()) event.setFOV(lens);
    }

    /** The player's own body, standing where it left it: not in the shot. */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (mode != Mode.OFF && event.getEntity() == Minecraft.getInstance().player) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        if (mode != Mode.OFF) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (mode != Mode.OFF && event.getName().equals(VanillaGuiLayers.CROSSHAIR)) event.setCanceled(true);
    }

    /** Escape ends the shot; any other screen opening (bar the camera's own) ends it too, as the picture is covered. */
    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        if (mode == Mode.OFF || event.getNewScreen() instanceof CameraScreen) return;
        if (event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
        stop();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        mode = Mode.OFF;
        cam = null;
        target = -1;
    }

    /** The keys' reminder (a few seconds, then gone: H brings it back) and a word on what is shown. */
    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        if (!caption.isEmpty() && now - captionAt < 3500L && mc.screen == null && (hint || mode == Mode.OFF)) {
            float a = Mth.clamp((3500L - (now - captionAt)) / 600.0F, 0.0F, 1.0F) * Mth.clamp((now - captionAt) / 250.0F, 0.0F, 1.0F);
            int w = font.width(caption);
            int x = (g.guiWidth() - w) / 2, y = g.guiHeight() - 46;
            g.fill(x - 8, y - 5, x + w + 8, y + 13, UiStyle.alpha(0xC0101018, a));
            g.drawString(font, caption, x, y, UiStyle.alpha(0xFFF0F0F0, a), false);
        }
        if (mode == Mode.OFF || mc.screen != null || !hint) return;
        long since = now - hintAt;
        if (since > hintFor) return;
        float a = Mth.clamp((hintFor - since) / 700.0F, 0.0F, 1.0F);
        String keys = switch (mode) {
            case PATH -> "Esc stop   Space pause / again   ←→ slower/faster   wheel lens   H hide";
            case FOLLOW -> "Esc stop   Space pause   ↑↓ height   wheel distance   H hide";
            default -> "Esc stop   Space pause   ←→ speed   ↑↓ height   wheel distance   H hide";
        };
        int w = font.width(keys);
        int x = (g.guiWidth() - w) / 2, y = 10;
        g.fill(x - 8, y - 5, x + w + 8, y + 13, UiStyle.alpha(0xA0101018, a));
        g.drawString(font, keys, x, y, UiStyle.alpha(0xFFE0E0E0, a), false);
    }

    // ------------------------------------------------------------------ the fly-through's curve

    /**
     * A smooth curve through the points (Catmull-Rom, the curve passing through every point), measured along its length
     * so that it is flown at an even pace however far apart the points stand; the camera's turn (yaw unwound, so it
     * never spins the long way round) and its tilt follow the same curve.
     */
    static final class Spline {
        record Pose(Vec3 pos, float yaw, float pitch) {}

        private static final int STEPS = 48;
        private final Vec3[] p;
        private final double[] yaw, pitch;
        private final double[] len;                     // the length to each sample, (n-1)*STEPS+1 of them
        private final double total;

        Spline(List<Point> points) {
            int n = points.size();
            p = new Vec3[n];
            yaw = new double[n];
            pitch = new double[n];
            for (int i = 0; i < n; i++) {
                Point q = points.get(i);
                p[i] = q.pos();
                yaw[i] = i == 0 ? q.yaw() : yaw[i - 1] + Mth.wrapDegrees(q.yaw() - yaw[i - 1]);
                pitch[i] = q.pitch();
            }
            len = new double[(n - 1) * STEPS + 1];
            Vec3 prev = p[0];
            for (int i = 1; i < len.length; i++) {
                Vec3 at = point(i / (double) STEPS);
                len[i] = len[i - 1] + at.distanceTo(prev);
                prev = at;
            }
            total = len[len.length - 1];
        }

        /** The pose at this fraction of the way along (0 to 1), by distance travelled. */
        Pose at(double frac) {
            double want = Mth.clamp(frac, 0.0, 1.0) * total;
            int lo = 0, hi = len.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (len[mid] < want) lo = mid; else hi = mid;
            }
            double span = len[hi] - len[lo];
            double f = span <= 1.0E-6 ? 0.0 : (want - len[lo]) / span;
            double param = (lo + f) / STEPS;
            if (total <= 1.0E-6) param = frac * (p.length - 1);   // all the points in one spot: turn on the spot
            return new Pose(point(param), (float) curve(yaw, param), (float) Mth.clamp(curve(pitch, param), -89.0, 89.0));
        }

        private Vec3 point(double param) {
            int i = Mth.clamp((int) Math.floor(param), 0, p.length - 2);
            double t = param - i;
            Vec3 a = p[Math.max(0, i - 1)], b = p[i], c = p[i + 1], d = p[Math.min(p.length - 1, i + 2)];
            return new Vec3(cr(a.x, b.x, c.x, d.x, t), cr(a.y, b.y, c.y, d.y, t), cr(a.z, b.z, c.z, d.z, t));
        }

        private double curve(double[] v, double param) {
            int i = Mth.clamp((int) Math.floor(param), 0, v.length - 2);
            double t = param - i;
            return cr(v[Math.max(0, i - 1)], v[i], v[i + 1], v[Math.min(v.length - 1, i + 2)], t);
        }

        private static double cr(double a, double b, double c, double d, double t) {
            double t2 = t * t, t3 = t2 * t;
            return 0.5 * (2.0 * b + (-a + c) * t + (2.0 * a - 5.0 * b + 4.0 * c - d) * t2 + (-a + 3.0 * b - 3.0 * c + d) * t3);
        }
    }

    /** Where the camera's eyes are now and which way they look: the fly-through's next point. */
    public static Point here() {
        Camera c = Minecraft.getInstance().gameRenderer.getMainCamera();
        return new Point(c.getPosition(), c.getYRot(), c.getXRot());
    }
}
