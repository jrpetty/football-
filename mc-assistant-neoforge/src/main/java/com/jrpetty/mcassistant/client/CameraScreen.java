package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * [vision27] The camera screen: film the town. Four ways to shoot it (CinemaCam does the moving), and the look of the
 * picture:
 * <ul>
 * <li><b>Orbit</b>: round the town's heart, the folk you were looking at when you opened this, or where you stand;
 *     its radius, height and speed.</li>
 * <li><b>Fly-through</b>: stand where a shot should pass, look where it should look, and add the point (two to six);
 *     then play the flight between them, so many seconds long, once or round and round.</li>
 * <li><b>Follow</b>: one folk through its day, over its shoulder (your favourites near you first).</li>
 * <li><b>Happening</b>: the camera taken to something going on right now (a building going up, a match, the market,
 *     a festival); and for an operator, or the owner of a single-player world, a time-lapse of a building going up.</li>
 * </ul>
 * Underneath: a clean shot (no HUD, labels or name tags), the lens, and the folk's name tags for ordinary play.
 * Opened by the camera key (K) or the Camera button on the U page; it can be opened over a shot as it runs, to change it
 * live. Escape (here or during a shot) always gives you back your own eyes.
 */
public class CameraScreen extends Screen {

    private static final int W = 320, H = 238, PAD = 8, HEAD = 30, TAB_H = 16;
    private static final String[] TABS = {"Orbit", "Fly-through", "Follow", "Happening"};
    private static int tab;
    /** The orbit's middle: 0 the heart, 1 the folk looked at, 2 where you stand. */
    private static int round;
    private static int followed = -1;
    private static int lapseFactor = 16, lapseSeconds = 20;

    private int left, top;
    private final int looked;
    private final String lookedName;
    private String msg = "";
    private boolean msgOk = true;

    public CameraScreen() {
        super(Component.literal("Camera"));
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.crosshairPickEntity;
        if (e instanceof VillageFolkEntity f && !CinemaCam.active()) {
            looked = f.getId();
            lookedName = f.clientName();
        } else {
            looked = CinemaCam.active() ? CinemaCam.target() : -1;
            Entity t = looked >= 0 && mc.level != null ? mc.level.getEntity(looked) : null;
            lookedName = t instanceof VillageFolkEntity f ? f.clientName() : "";
        }
        if (looked >= 0 && followed < 0) followed = looked;
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new CameraScreen());
        CameraClient.ask("info", 0, 0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;                                     // the world goes on behind it, and a running shot with it
    }

    void refresh() {
        rebuildWidgets();
    }

    void message(String words, boolean ok) {
        msg = words;
        msgOk = ok;
    }

    // ------------------------------------------------------------------ the widgets

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int x = left + PAD, y = top + HEAD + TAB_H + 12, w = W - PAD * 2;
        switch (tab) {
            case 0 -> orbitTab(x, y, w);
            case 1 -> pathTab(x, y, w);
            case 2 -> followTab(x, y, w);
            default -> happeningTab(x, y, w);
        }
        // Underneath, every tab: the look of the picture.
        int fy = top + H - 26;
        add(button(clean() + "", b -> { CinemaCam.clean = !CinemaCam.clean; if (CinemaCam.active()) minecraft.options.hideGui = CinemaCam.clean; rebuildWidgets(); },
            x, fy, 92, "A clean shot: no HUD, no labels, no name tags (as F1). On by default."));
        add(new Slider(x + 96, fy, 86, 20, 20, 100, CinemaCam.lens, v -> "Lens " + Math.round(v) + "°", v -> CinemaCam.lens = (float) v));
        add(button("Name tags: " + plates(), b -> { AnimConfig.setPlates(AnimConfig.Plates.values()[(AnimConfig.plates().ordinal() + 1) % 4]); rebuildWidgets(); },
            x + 186, fy, w - 186, "The folk's name tags in ordinary play: the whole tag, the name, only the folk you look at, or none."));
    }

    private String clean() {
        return CinemaCam.clean ? "Clean shot: on" : "Clean shot: off";
    }

    private static String plates() {
        return switch (AnimConfig.plates()) {
            case FULL -> "all";
            case NAME -> "names";
            case LOOK -> "looked at";
            case OFF -> "off";
        };
    }

    private void orbitTab(int x, int y, int w) {
        String[] where = {"Town heart", lookedName.isEmpty() ? "A folk" : lookedName, "Where I stand"};
        int bw = (w - 8) / 3;
        for (int i = 0; i < 3; i++) {
            int k = i;
            Button b = choice(where[i], round == i, bt -> { round = k; rebuildWidgets(); }, x + i * (bw + 4), y, bw,
                i == 0 ? "Round the middle of the town." : i == 1 ? "Round the folk you were looking at (look at one, then open this)." : "Round the spot you stand on.");
            if (i == 0 && CameraClient.heart() == null) b.active = false;
            if (i == 1 && looked < 0) b.active = false;
            add(b);
        }
        if (round == 0 && CameraClient.heart() == null) round = 2;
        if (round == 1 && looked < 0) round = 2;
        y += 28;
        add(new Slider(x, y, w, 20, 4, 120, CinemaCam.radius, v -> "Radius: " + Math.round(v) + " blocks", v -> CinemaCam.radius = (float) v));
        add(new Slider(x, y + 24, w, 20, 1, 80, CinemaCam.height, v -> "Height: " + Math.round(v) + " blocks", v -> CinemaCam.height = (float) v));
        add(new Slider(x, y + 48, w, 20, -30, 30, CinemaCam.speed, v -> "Speed: " + (Math.round(v * 10) / 10.0) + "° a second" + (Math.abs(v) < 0.05 ? " (still)" : v < 0 ? " (the other way)" : ""),
            v -> CinemaCam.speed = (float) v));
        add(primary("Start the orbit", b -> startOrbit(), x, y + 78, w));
    }

    private void startOrbit() {
        Vec3 at;
        int ent = -1;
        String label;
        Minecraft mc = Minecraft.getInstance();
        if (round == 0 && CameraClient.heart() != null) {
            at = CameraClient.heart();
            label = info().getString("town").isEmpty() ? "Round the town" : "Round " + info().getString("town");
        } else if (round == 1 && looked >= 0 && mc.level != null && mc.level.getEntity(looked) != null) {
            at = mc.level.getEntity(looked).position();
            ent = looked;
            label = "Round " + lookedName;
        } else {
            at = mc.player.position();
            label = "Round this spot";
        }
        mc.setScreen(null);
        CinemaCam.orbit(at, ent, label);
    }

    private void pathTab(int x, int y, int w) {
        int n = CinemaCam.POINTS.size();
        for (int i = 0; i < n; i++) {
            int k = i;
            add(button("✕", b -> { CinemaCam.POINTS.remove(k); rebuildWidgets(); }, x + w - 16, y + i * 13 - 1, 16, "Take this point out."));
        }
        int by = y + Math.max(2, n) * 13 + 4;
        Button addB = primary(n == 0 ? "Add the first point: here, looking this way" : "Add point " + (n + 1) + ": here, looking this way", b -> {
            CinemaCam.POINTS.add(CinemaCam.here());
            message("Point " + CinemaCam.POINTS.size() + " set. Move and look, open this again (K), and add the next.", true);
            rebuildWidgets();
        }, x, by, w - 54);
        addB.active = n < CinemaCam.MOST_POINTS;
        add(addB);
        Button clear = button("Clear", b -> { CinemaCam.POINTS.clear(); rebuildWidgets(); }, x + w - 50, by, 50, "Take out every point.");
        clear.active = n > 0;
        add(clear);
        add(new Slider(x, by + 24, w - 96, 20, 4, 120, CinemaCam.pathSeconds, v -> "Length: " + Math.round(v) + " s", v -> CinemaCam.pathSeconds = (float) v));
        add(button(CinemaCam.loop ? "Round and round" : "Once through", b -> { CinemaCam.loop = !CinemaCam.loop; rebuildWidgets(); }, x + w - 92, by + 24, 92,
            "Once through (it eases in and out), or round and round, back to the first point and on."));
        Button play = primary("Play the fly-through", b -> { minecraft.setScreen(null); CinemaCam.flyThrough(); }, x, by + 50, w);
        play.active = n >= 2;
        if (n < 2) play.setTooltip(Tooltip.create(Component.literal("Set at least two points first.")));
        add(play);
    }

    private void followTab(int x, int y, int w) {
        ListTag who = info().getList("folk", Tag.TAG_COMPOUND);
        if (followed < 0 && !who.isEmpty()) followed = who.getCompound(0).getInt("id");   // the first (a favourite, or the nearest) ready
        int cols = 2, bw = (w - 4) / cols, shown = 0;
        // The folk looked at, if it is not on the server's list.
        boolean lookedListed = false;
        for (int i = 0; i < who.size(); i++) if (who.getCompound(i).getInt("id") == looked) lookedListed = true;
        if (looked >= 0 && !lookedListed) {
            add(choice(lookedName, followed == looked, b -> { followed = looked; rebuildWidgets(); }, x, y, bw, "The folk you were looking at."));
            shown++;
        }
        for (int i = 0; i < who.size() && shown < 8; i++) {
            CompoundTag c = who.getCompound(i);
            int id = c.getInt("id");
            String label = (c.getBoolean("fav") ? "★ " : "") + c.getString("name");
            add(choice(label, followed == id, b -> { followed = id; rebuildWidgets(); }, x + (shown % cols) * (bw + 4), y + (shown / cols) * 22, bw,
                c.getString("name") + (c.getString("job").isEmpty() ? "" : ", " + c.getString("job")) + (c.getBoolean("fav") ? " (a favourite)" : "")));
            shown++;
        }
        int sy = y + Math.max(1, (shown + 1) / 2) * 22 + 4;
        add(new Slider(x, sy, (w - 4) / 2, 20, 1.5, 24, CinemaCam.followDist, v -> "Behind: " + (Math.round(v * 10) / 10.0), v -> CinemaCam.followDist = (float) v));
        add(new Slider(x + (w + 4) / 2, sy, (w - 4) / 2, 20, 0, 12, CinemaCam.followHeight, v -> "Above: " + (Math.round(v * 10) / 10.0), v -> CinemaCam.followHeight = (float) v));
        Button go = primary("Follow them", b -> {
            String name = "";
            for (int i = 0; i < who.size(); i++) if (who.getCompound(i).getInt("id") == followed) name = who.getCompound(i).getString("name");
            if (name.isEmpty() && followed == looked) name = lookedName;
            minecraft.setScreen(null);
            CinemaCam.follow(followed, name.isEmpty() ? "Following" : "Following " + name);
        }, x, sy + 26, w);
        go.active = followed >= 0 && minecraft.level != null && minecraft.level.getEntity(followed) != null;
        add(go);
    }

    private void happeningTab(int x, int y, int w) {
        add(primary("Show me something happening", b -> { message("Looking round the town...", true); CameraClient.ask("happening", 0, 0); }, x, y, w));
        boolean may = info().getBoolean("mayLapse");
        boolean lapsing = info().getBoolean("lapsing");
        int ly = y + 52;
        add(new Slider(x, ly, (w - 4) / 2, 20, 4, 32, lapseFactor, v -> "Faster: " + (int) Math.round(v) + "×", v -> lapseFactor = (int) Math.round(v)));
        add(new Slider(x + (w + 4) / 2, ly, (w - 4) / 2, 20, 5, 60, lapseSeconds, v -> "For: " + (int) Math.round(v) + " s", v -> lapseSeconds = (int) Math.round(v)));
        Button lapse = primary(lapsing ? "End the time-lapse" : "Time-lapse: watch a building go up", b -> {
            if (lapsing) { CameraClient.ask("stop", 0, 0); message("Back to the normal pace.", true); CameraClient.ask("info", 0, 0); }
            else { message("Starting...", true); CameraClient.ask("timelapse", lapseFactor, lapseSeconds); }
        }, x, ly + 24, w);
        lapse.active = may;
        lapse.setTooltip(Tooltip.create(Component.literal(may
            ? "The whole world runs faster for a while (as the speed keys do), then back by itself; the camera goes round a building going up, if there is one near."
            : "Only an operator (or the owner of a single-player world) can run a time-lapse.")));
        add(lapse);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // A shot running behind: let it be seen; otherwise the house backdrop.
        if (!CinemaCam.active()) g.fill(0, 0, width, height, UiStyle.BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        UiStyle.shadow(g, left, top, W, H);
        UiStyle.panel(g, left, top, W, H);
        UiStyle.headerBand(g, left, top, W, HEAD);
        UiStyle.title(g, font, "Camera", left + PAD, top + 5);
        String town = info().getString("town");
        String sub = CinemaCam.active() ? "A shot is running: change it here, Escape to stop it." : town.isEmpty() ? "Film the town." : "Film " + town + ".";
        UiStyle.text(g, font, sub, left + PAD, top + 18, W - PAD * 2 - 8, UiStyle.TEXT_MUTED);
        int tx = left + PAD;
        for (int i = 0; i < TABS.length; i++) {
            int tw = font.width(TABS[i]) + 16;
            boolean over = mx >= tx && mx < tx + tw && my >= top + HEAD + 3 && my < top + HEAD + 3 + TAB_H;
            UiStyle.topTab(g, tx, top + HEAD + 3, tw, TAB_H, UiStyle.State.of(true, tab == i, over));
            g.drawString(font, TABS[i], tx + 8, top + HEAD + 7, UiStyle.TEXT, false);
            tx += tw + 2;
        }
        int y = top + HEAD + TAB_H + 12;
        if (tab == 1) {
            int n = CinemaCam.POINTS.size();
            if (n == 0) {
                UiStyle.text(g, font, "No points yet. Stand where the shot should pass,", left + PAD, y + 1, W - PAD * 2, UiStyle.TEXT_FAINT);
                UiStyle.text(g, font, "look where it should look, and add a point.", left + PAD, y + 14, W - PAD * 2, UiStyle.TEXT_FAINT);
            }
            for (int i = 0; i < n; i++) {
                CinemaCam.Point p = CinemaCam.POINTS.get(i);
                String line = (i + 1) + ".  " + Math.round(p.pos().x) + ", " + Math.round(p.pos().y) + ", " + Math.round(p.pos().z)
                    + "   looking " + facing(p.yaw()) + (p.pitch() > 20 ? ", down" : p.pitch() < -20 ? ", up" : "");
                UiStyle.text(g, font, line, left + PAD, y + i * 13 + 2, W - PAD * 2 - 22, UiStyle.TEXT);
            }
        } else if (tab == 2 && info().getList("folk", Tag.TAG_COMPOUND).isEmpty() && looked < 0) {
            UiStyle.text(g, font, "No folk near enough to follow.", left + PAD, y + 4, W - PAD * 2, UiStyle.TEXT_FAINT);
        } else if (tab == 3) {
            UiStyle.text(g, font, "A building going up, a match, the market, a festival:", left + PAD, y + 26, W - PAD * 2, UiStyle.TEXT_FAINT);
            UiStyle.text(g, font, "press again for another.", left + PAD, y + 37, W - PAD * 2, UiStyle.TEXT_FAINT);
        }
        if (!msg.isEmpty()) UiStyle.text(g, font, msg, left + PAD, top + H - 40, W - PAD * 2, msgOk ? UiStyle.SUCCESS : UiStyle.DANGER);
        for (var r : renderables) r.render(g, mx, my, pt);
    }

    private static String facing(float yaw) {
        String[] dirs = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        return dirs[Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / 45.0F), 8)];
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int tx = left + PAD;
        for (int i = 0; i < TABS.length; i++) {
            int tw = font.width(TABS[i]) + 16;
            if (mx >= tx && mx < tx + tw && my >= top + HEAD + 3 && my < top + HEAD + 3 + TAB_H) {
                if (tab != i) {
                    UiStyle.click();
                    tab = i;
                    msg = "";
                    rebuildWidgets();
                }
                return true;
            }
            tx += tw + 2;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            CinemaCam.stop();                              // Escape always gives you your own eyes back
            onClose();
            return true;
        }
        if (CameraClient.OPEN.matches(key, scan)) {        // the camera key again: put the screen away, the shot running on
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ helpers

    private static CompoundTag info() {
        return CameraClient.info == null ? new CompoundTag() : CameraClient.info;
    }

    private <T extends net.minecraft.client.gui.components.AbstractWidget> T add(T w) {
        return addRenderableWidget(w);
    }

    private static Button button(String label, Button.OnPress press, int x, int y, int w, String tip) {
        Button b = Button.builder(Component.literal(label), press).bounds(x, y, w, 20).build(UiButton::new);
        if (!tip.isEmpty()) b.setTooltip(Tooltip.create(Component.literal(tip)));
        return b;
    }

    private static Button primary(String label, Button.OnPress press, int x, int y, int w) {
        return UiButton.primary(Button.builder(Component.literal(label), press).bounds(x, y, w, 20));
    }

    private static Button choice(String label, boolean on, Button.OnPress press, int x, int y, int w, String tip) {
        Button b = on ? primary(label, press, x, y, w) : button(label, press, x, y, w, "");
        b.setTooltip(Tooltip.create(Component.literal(tip)));
        return b;
    }

    /** A slider in the house look, between two numbers. */
    private static final class Slider extends AbstractSliderButton {
        private final double min, max;
        private final DoubleFunction<String> label;
        private final DoubleConsumer set;

        Slider(int x, int y, int w, int h, double min, double max, double now, DoubleFunction<String> label, DoubleConsumer set) {
            super(x, y, w, h, Component.empty(), Mth.clamp((now - min) / (max - min), 0.0, 1.0));
            this.min = min;
            this.max = max;
            this.label = label;
            this.set = set;
            updateMessage();
        }

        private double now() {
            return min + (max - min) * this.value;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label.apply(now())));
        }

        @Override
        protected void applyValue() {
            set.accept(now());
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            UiStyle.well(g, x, y, w, h, UiStyle.WELL);
            int hx = x + (int) (this.value * (w - 8));
            g.fill(x + 1, y + 1, hx + 4, y + h - 1, UiStyle.alpha(UiStyle.ACCENT_FILL, 0.45F));
            UiStyle.tile(g, hx, y, 8, h, UiStyle.State.of(true, false, isHoveredOrFocused()));
            String s = getMessage().getString();
            var font = Minecraft.getInstance().font;
            g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, UiStyle.TEXT, false);
        }
    }
}
