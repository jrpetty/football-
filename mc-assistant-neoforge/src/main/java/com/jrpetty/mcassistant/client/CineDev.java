package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [vision27] For filming the mod in a developer's own client, and nowhere else: none of it runs unless the game was
 * started with {@code -Dmcassistant.cinedev=FILE}. Each time the file changes, its lines are carried out once, in order:
 *
 * <pre>
 *   orbit X Y Z RADIUS HEIGHT SPEED [ENTITY]    an orbit (CinemaCam), round a point or an entity id
 *   follow ID | nearest                          follow a folk
 *   point X Y Z YAW PITCH                        a fly-through point (clear: points cleared)
 *   path SECONDS [loop]                          fly the points
 *   happening | heart R H SPEED                  ask the server (as the screen's button) / orbit the town's heart
 *   stop                                         end the shot
 *   lens DEG / clean on|off / plates FULL|NAME|LOOK|OFF / clock world|wall
 *   record DIR                                   every frame drawn written to DIR as a numbered PNG, with frames.txt (the
 *                                                world's time of each, in ticks, to make a steady clip of it); record off
 *   screen camera | none                         the camera screen opened (for its pictures), or closed
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class CineDev {

    private CineDev() {}

    @Nullable
    private static final String FILE = System.getProperty("mcassistant.cinedev");
    private static long stamp = Long.MIN_VALUE;
    private static int every;

    @Nullable private static Path recordDir;
    @Nullable private static Writer times;
    private static int frame;
    private static ExecutorService writer;
    private static final AtomicInteger PENDING = new AtomicInteger();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (FILE == null || ++every % 5 != 0) return;
        try {
            Path p = Path.of(FILE);
            long t = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
            if (t == stamp) return;
            boolean first = stamp == Long.MIN_VALUE;
            stamp = t;
            if (!first && t != 0L) run(Files.readAllLines(p));
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().warn("[vision27] cinedev: {}", e.toString());
        }
    }

    private static void run(List<String> lines) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        for (String line : lines) {
            String[] w = line.trim().split("\\s+");
            if (w.length == 0 || w[0].isEmpty() || w[0].startsWith("#")) continue;
            switch (w[0]) {
                case "orbit" -> {
                    CinemaCam.radius = f(w[4]);
                    CinemaCam.height = f(w[5]);
                    CinemaCam.speed = f(w[6]);
                    CinemaCam.orbit(new Vec3(d(w[1]), d(w[2]), d(w[3])), w.length > 7 ? Integer.parseInt(w[7]) : -1, "");
                }
                case "heart" -> {
                    CinemaCam.radius = f(w[1]);
                    CinemaCam.height = f(w[2]);
                    CinemaCam.speed = f(w[3]);
                    Vec3 h = CameraClient.heart();
                    if (h != null) CinemaCam.orbit(h, -1, "");
                    else CameraClient.ask("info", 0, 0);
                }
                case "follow" -> {
                    int id = -1;
                    if (w[1].equals("nearest") && mc.level != null && mc.player != null) {
                        double best = Double.MAX_VALUE;
                        for (Entity e : mc.level.entitiesForRendering()) {
                            if (e instanceof VillageFolkEntity f && !f.isBaby() && !f.isSleeping() && f.distanceToSqr(mc.player) < best) {
                                best = f.distanceToSqr(mc.player);
                                id = f.getId();
                            }
                        }
                    } else {
                        id = Integer.parseInt(w[1]);
                    }
                    if (w.length > 2) CinemaCam.followDist = f(w[2]);
                    if (w.length > 3) CinemaCam.followHeight = f(w[3]);
                    CinemaCam.follow(id, "");
                }
                case "near" -> {
                    // near ACT R H SPEED [follow]: round (or behind) the nearest folk at this act ("walking": on the move;
                    // "any": anything but standing about), for the close-up review.
                    VillageFolkEntity f = nearestAt(mc, w[1]);
                    if (f == null) {
                        com.mojang.logging.LogUtils.getLogger().info("[vision27] cinedev: nobody at {}", w[1]);
                        continue;
                    }
                    com.mojang.logging.LogUtils.getLogger().info("[vision27] cinedev: near {} -> {} #{} at {}", w[1], f.clientName(), f.getId(),
                        com.jrpetty.mcassistant.entity.FolkAction.Act.of(f.animSeen().act));
                    if (w.length > 5 && w[5].equals("follow")) {
                        CinemaCam.followDist = f(w[2]);
                        CinemaCam.followHeight = f(w[3]);
                        CinemaCam.follow(f.getId(), "");
                    } else {
                        CinemaCam.radius = f(w[2]);
                        CinemaCam.height = f(w[3]);
                        CinemaCam.speed = f(w[4]);
                        CinemaCam.orbit(f.position(), f.getId(), "");
                    }
                }
                case "acts" -> {
                    java.util.Map<String, Integer> n = new java.util.TreeMap<>();
                    if (mc.level != null) for (Entity e : mc.level.entitiesForRendering()) {
                        if (e instanceof VillageFolkEntity f) n.merge(com.jrpetty.mcassistant.entity.FolkAction.Act.of(f.animSeen().act).name()
                            + (f.animSeen().gaitSpeed > 1.2F ? "+walk" : ""), 1, Integer::sum);
                    }
                    com.mojang.logging.LogUtils.getLogger().info("[vision27] cinedev: acts {}", n);
                }
                case "point" -> CinemaCam.POINTS.add(new CinemaCam.Point(new Vec3(d(w[1]), d(w[2]), d(w[3])), f(w[4]), f(w[5])));
                case "clear" -> CinemaCam.POINTS.clear();
                case "path" -> {
                    CinemaCam.pathSeconds = f(w[1]);
                    CinemaCam.loop = w.length > 2 && w[2].equals("loop");
                    CinemaCam.flyThrough();
                }
                case "happening" -> CameraClient.ask("happening", 0, 0);
                case "info" -> CameraClient.ask("info", 0, 0);
                case "lapse" -> CameraClient.ask("timelapse", Integer.parseInt(w[1]), Integer.parseInt(w[2]));
                case "stop" -> CinemaCam.stop();
                case "lens" -> CinemaCam.lens = f(w[1]);
                case "clean" -> {
                    CinemaCam.clean = w[1].equals("on");
                    if (CinemaCam.active()) mc.options.hideGui = CinemaCam.clean;
                }
                case "plates" -> AnimConfig.setPlates(AnimConfig.Plates.valueOf(w[1].toUpperCase(Locale.ROOT)));
                case "clock" -> CinemaCam.worldClock = w[1].equals("world");
                case "record" -> record(w[1].equals("off") ? null : Path.of(w[1]));
                case "screen" -> {
                    if (w[1].equals("camera")) CameraScreen.open(); else mc.setScreen(null);
                }
                case "hud" -> mc.options.hideGui = w[1].equals("off");
                case "hint" -> CinemaCam.hints(w[1].equals("on"));
                case "side" -> CinemaCam.followSide = w[1].equals("on");
                // [ui29] gui N (0 = auto) / size W H: the GUI scale and the window, for checking every screen at each
                case "gui" -> {
                    mc.options.guiScale().set(Integer.parseInt(w[1]));
                    mc.resizeDisplay();
                }
                case "size" -> mc.getWindow().setWindowed(Integer.parseInt(w[1]), Integer.parseInt(w[2]));
                case "close" -> mc.setScreen(null);
                case "page" -> LifeClient.open(w[1]);                               // a player page, as its button asks
                case "cmd" -> com.jrpetty.mcassistant.net.LifeNetwork.command(line.trim().substring(4));
                case "open" -> open(mc, w[1]);
                case "city" -> { if (mc.screen instanceof CityScreen c) c.devPage(line.trim().substring(5).replace('_', ' ')); }
                default -> com.mojang.logging.LogUtils.getLogger().warn("[vision27] cinedev: what is '{}'?", w[0]);
            }
        }
        com.mojang.logging.LogUtils.getLogger().info("[vision27] cinedev: ran {} lines", lines.size());
    }

    /** [ui29] Open a screen the way its key or button does, for the screen audit's pictures. */
    private static void open(Minecraft mc, String what) {
        switch (what) {
            case "city" -> CityScreen.request();
            case "village" -> VillageScreen.request();
            case "plots" -> PlotsClient.requestOpen();
            case "finance" -> FinanceClient.open(null);
            case "favourites" -> FavouritesClient.open(null, null);
            case "camera" -> CameraScreen.open();
            case "admin" -> AdminClient.open("");
            case "crew" -> mc.setScreen(new CrewScreen());
            case "map" -> mc.setScreen(new MapScreen());
            case "totals" -> mc.setScreen(new TotalsScreen());
            case "blockbook" -> mc.setScreen(new BlockBookScreen());
            case "emote" -> mc.setScreen(new EmoteWheelScreen());
            case "found" -> FoundingClient.ask("open", "board");
            case "use" -> { if (mc.player != null && mc.gameMode != null) mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND); }
            case "profile", "talk" -> {
                if (mc.player == null || mc.level == null || mc.gameMode == null) return;
                VillageFolkEntity best = null;
                double bestD = 36.0;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof VillageFolkEntity f && !f.isBaby() && !f.isSleeping() && f.distanceToSqr(mc.player) < bestD) {
                        bestD = f.distanceToSqr(mc.player);
                        best = f;
                    }
                }
                if (best == null) return;
                if (what.equals("profile")) FolkProfileScreen.openEntity(best.getId(), null, null);
                else mc.gameMode.interact(mc.player, best, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            default -> com.mojang.logging.LogUtils.getLogger().warn("[ui29] cinedev: no screen '{}'", what);
        }
    }

    private static float f(String s) { return Float.parseFloat(s); }

    @Nullable
    private static VillageFolkEntity nearestAt(Minecraft mc, String what) {
        if (mc.level == null || mc.player == null) return null;
        String want = what.toUpperCase(Locale.ROOT);
        Vec3 from = mc.gameRenderer.getMainCamera().getPosition();
        VillageFolkEntity best = null;
        double bestD = 96.0 * 96.0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            com.jrpetty.mcassistant.entity.FolkAction.Act a = com.jrpetty.mcassistant.entity.FolkAction.Act.of(f.animSeen().act);
            boolean ok = switch (want) {
                case "WALKING" -> f.animSeen().gaitSpeed > 1.2F && !f.isBaby();
                case "RUNNING" -> f.animSeen().gaitSpeed > 3.8F;
                case "CHILD" -> f.isBaby() && a != com.jrpetty.mcassistant.entity.FolkAction.Act.SLEEP;
                case "ANY" -> a != com.jrpetty.mcassistant.entity.FolkAction.Act.NONE && a != com.jrpetty.mcassistant.entity.FolkAction.Act.SLEEP;
                default -> a.name().equals(want);
            };
            if (!ok) continue;
            double d = f.distanceToSqr(from);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    private static double d(String s) { return Double.parseDouble(s); }

    private static void record(@Nullable Path dir) throws IOException {
        if (times != null) {
            times.close();
            times = null;
        }
        recordDir = dir;
        frame = 0;
        if (dir == null) return;
        Files.createDirectories(dir);
        times = Files.newBufferedWriter(dir.resolve("frames.txt"));
        if (writer == null) writer = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "cinedev-writer"); t.setDaemon(true); return t; });
    }

    /** Each frame drawn, as it is (the HUD and all), with the world's time it shows. */
    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        Path dir = recordDir;
        Minecraft mc = Minecraft.getInstance();
        if (dir == null || times == null || mc.level == null) return;
        if (PENDING.get() > 24) return;                                // the disk behind: skip rather than run out of memory
        double world = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int n = frame++;
        try {
            times.write(String.format(Locale.ROOT, "%06d %.4f %d%n", n, world, System.nanoTime() / 1_000_000L));
        } catch (IOException ignored) {
            // The list is a help, not the pictures.
        }
        NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        PENDING.incrementAndGet();
        writer.submit(() -> {
            try (img) {
                img.writeToFile(dir.resolve(String.format(Locale.ROOT, "f%06d.png", n)));
            } catch (IOException e) {
                com.mojang.logging.LogUtils.getLogger().warn("[vision27] cinedev frame {}: {}", n, e.toString());
            } finally {
                PENDING.decrementAndGet();
            }
        });
    }
}
