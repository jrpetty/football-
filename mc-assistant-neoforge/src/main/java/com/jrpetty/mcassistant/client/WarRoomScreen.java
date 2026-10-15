package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.WarRoomAskPayload;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [borders-map] The war room's map table, opened: a live map of the region as the table's town knows it.
 *
 * <p>The terrain comes in tiles (256 blocks a side, four blocks a pixel, in a vanilla map's colours, the unknown left
 * the colour of the paper), each drawn once into a texture with the towns' lands tinted over it and their borders ruled
 * in their colours (contested ground striped gold). Over that, drawn each frame: the roads (brown) and trade routes
 * (dotted gold), armies on the march (their road in their colour, an arrow at the head), their camps, battles (crossed
 * swords), sieges (a ring round the town), caravans, envoys, heralds, wagons and scouts, the disputes (a flag), the ruins,
 * the border markers (zoomed in), every town known (its colour, its size, its name) and you.
 *
 * <p>Drag to pan, scroll (or + and -) to zoom, click a town for its card. The side panel has three faces: the town (its
 * folk, age, lands, how we stand with it, its strength as our town reckons it, its wars and trade), the legend, and
 * what's new. On a small window the panel folds away behind a button. The map asks the server again every second and a
 * half while it is open; the tiles only when they are missing or stale.
 */
public class WarRoomScreen extends Screen {

    private static final int PAPER = 0xFFE4D6B0, PAPER_DARK = 0xFFC9B88E, INK = 0xFF2B2418, MUTED = 0xFF5A4E38, GOLD = 0xFFC9A227,
        RED = 0xFFB83227, BROWN = 0xFF7A5230, GREEN = 0xFF2E7D32;
    private static final int TILE_BLOCKS = 256, TILE_PX = 64;
    /** Zoom: screen pixels a block, powers of two. */
    private static final double[] ZOOMS = { 1 / 16.0, 1 / 8.0, 1 / 4.0, 1 / 2.0, 1.0, 2.0 };

    // ------------------------------------------------------------------ what the client has (kept while the screen is open)

    private static CompoundTag data = new CompoundTag();
    private static long claimsVersion = Long.MIN_VALUE;
    /** Chunk to the index of its town in CLAIM_TOWNS (+1; 0 none). */
    private static final Long2IntOpenHashMap OWNER = new Long2IntOpenHashMap();
    private static final LongOpenHashSet CONTESTED = new LongOpenHashSet();
    private static final List<int[]> CLAIM_COLOURS = new ArrayList<>();
    private static final List<String> CLAIM_NAMES = new ArrayList<>();
    private static final Map<Long, Tile> TILES = new HashMap<>();
    private static long bakeVersion;

    private static final class Tile {
        long stamp, askedAt = -10000, bakedFor = -1;
        @Nullable byte[] px;
        @Nullable DynamicTexture tex;
        @Nullable ResourceLocation rl;
    }

    // ------------------------------------------------------------------ the view

    private BlockPos table = BlockPos.ZERO;
    private double cx, cz;
    private int zoom = 2;
    private int left, top, w, h, mapX, mapY, mapW, mapH, sideX, sideW;
    private boolean sideShown = true, narrow;
    private String face = "town";
    @Nullable private String selected;
    private int ticks;
    private boolean dragging;
    private double dragX, dragZ, dragMX, dragMY;
    private int newsScroll;

    public WarRoomScreen(CompoundTag first) {
        super(Component.literal("Map table"));
        int[] home = first.getIntArray("home");
        if (home.length == 2) {
            cx = home[0];
            cz = home[1];
        }
        table = BlockPos.of(first.getLong("tablePos"));
        selected = first.getString("town").isEmpty() ? null : first.getString("town");
    }

    /** The server's overlay: opens the screen if it asks to, or updates the one open. */
    public static void heard(CompoundTag d) {
        Minecraft mc = Minecraft.getInstance();
        boolean open = mc.screen instanceof WarRoomScreen;
        if (!open && !d.getBoolean("open")) return;
        if (!open) clearCache();
        data = d;
        if (d.contains("claims")) readClaims(d.getCompound("claims"), d.getLong("claimsVersion"));
        if (!open) mc.setScreen(new WarRoomScreen(d));
    }

    /** Terrain tiles from the server. */
    public static void tiles(List<long[]> heads, List<byte[]> bodies) {
        if (!(Minecraft.getInstance().screen instanceof WarRoomScreen)) return;
        for (int i = 0; i < heads.size(); i++) {
            Tile t = TILES.computeIfAbsent(heads.get(i)[0], k -> new Tile());
            t.stamp = heads.get(i)[1];
            byte[] b = bodies.get(i);
            t.px = b.length == TILE_PX * TILE_PX ? b : null;
            t.bakedFor = -1;
        }
    }

    private static void readClaims(CompoundTag c, long version) {
        OWNER.clear();
        CONTESTED.clear();
        CLAIM_COLOURS.clear();
        CLAIM_NAMES.clear();
        Map<String, CompoundTag> towns = new HashMap<>();
        ListTag ts = data.getList("towns", Tag.TAG_COMPOUND);
        for (int i = 0; i < ts.size(); i++) towns.put(ts.getCompound(i).getString("id"), ts.getCompound(i));
        ListTag l = c.getList("towns", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            CompoundTag info = towns.get(t.getString("id"));
            int colour = info == null ? 0x808080 : info.getInt("colour");
            CLAIM_COLOURS.add(new int[]{ colour });
            CLAIM_NAMES.add(info == null ? "?" : info.getString("name"));
            int[] runs = t.getIntArray("runs");
            for (int j = 0; j + 2 < runs.length; j += 3) {
                for (int x = runs[j + 1]; x < runs[j + 1] + runs[j + 2]; x++) OWNER.put(ChunkPos.asLong(x, runs[j]), i + 1);
            }
        }
        for (long k : c.getLongArray("contested")) CONTESTED.add(k);
        claimsVersion = version;
        bakeVersion++;
    }

    private static void clearCache() {
        Minecraft mc = Minecraft.getInstance();
        for (Tile t : TILES.values()) if (t.rl != null) mc.getTextureManager().release(t.rl);
        TILES.clear();
        OWNER.clear();
        CONTESTED.clear();
        CLAIM_COLOURS.clear();
        CLAIM_NAMES.clear();
        claimsVersion = Long.MIN_VALUE;
    }

    @Override
    public void removed() {
        super.removed();
        clearCache();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        left = 6;
        top = 6;
        w = width - 12;
        h = height - 12;
        narrow = width < 400;
        sideW = Math.max(110, Math.min(170, (int) (w * 0.3)));
        if (narrow) sideShown = false;
        layout();
        int bw = 18, by = top + 3;
        int bx = left + w - 4;
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose()).bounds(bx -= bw, by, bw, 14).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> zoomBy(-1, mapX + mapW / 2.0, mapY + mapH / 2.0)).bounds(bx -= bw + 2, by, bw, 14).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoomBy(1, mapX + mapW / 2.0, mapY + mapH / 2.0)).bounds(bx -= bw + 2, by, bw, 14).build());
        addRenderableWidget(Button.builder(Component.literal("⌂"), b -> home()).bounds(bx -= bw + 2, by, bw, 14)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Back to our town"))).build());
        int fw = narrow ? 34 : 44;
        addRenderableWidget(Button.builder(Component.literal("News"), b -> face("news")).bounds(bx -= fw + 6, by, fw, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Legend"), b -> face("legend")).bounds(bx -= fw + 2, by, fw, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Town"), b -> face("town")).bounds(bx -= fw + 2, by, fw, 14).build());
        if (narrow) {
            addRenderableWidget(Button.builder(Component.literal("Panel"), b -> { sideShown = !sideShown; layout(); })
                .bounds(bx -= fw + 2, by, fw, 14).build());
        }
    }

    private void layout() {
        mapX = left + 4;
        mapY = top + 20;
        mapH = h - 24;
        if (sideShown && !narrow) {
            mapW = w - 8 - sideW - 4;
            sideX = mapX + mapW + 4;
        } else {
            mapW = w - 8;
            sideX = left + w - 4 - sideW;
        }
    }

    private void face(String f) {
        face = f;
        if (narrow) {
            sideShown = true;
            layout();
        }
    }

    private void home() {
        int[] home = data.getIntArray("home");
        if (home.length == 2) {
            cx = home[0];
            cz = home[1];
        }
        selected = data.getString("town").isEmpty() ? null : data.getString("town");
    }

    private double ppb() {
        return ZOOMS[zoom];
    }

    private void zoomBy(int d, double mx, double my) {
        int z = Math.max(0, Math.min(ZOOMS.length - 1, zoom + d));
        if (z == zoom) return;
        double wx = worldX(mx), wz = worldZ(my);
        zoom = z;
        cx = wx - (mx - (mapX + mapW / 2.0)) / ppb();
        cz = wz - (my - (mapY + mapH / 2.0)) / ppb();
    }

    private double worldX(double sx) {
        return cx + (sx - (mapX + mapW / 2.0)) / ppb();
    }

    private double worldZ(double sy) {
        return cz + (sy - (mapY + mapH / 2.0)) / ppb();
    }

    private int sx(double x) {
        return (int) Math.floor(mapX + mapW / 2.0 + (x - cx) * ppb());
    }

    private int sy(double z) {
        return (int) Math.floor(mapY + mapH / 2.0 + (z - cz) * ppb());
    }

    // ------------------------------------------------------------------ asking

    @Override
    public void tick() {
        ticks++;
        boolean overlay = ticks % 30 == 1;
        List<Long> want = wantedTiles();
        if (overlay || !want.isEmpty() && ticks % 5 == 0) {
            long[] arr = new long[Math.min(want.size(), 12) * 2];          // a dozen at a time: what the server answers
            for (int i = 0; i < arr.length / 2; i++) {
                long k = want.get(i);
                Tile t = TILES.computeIfAbsent(k, x -> new Tile());
                t.askedAt = ticks;
                arr[i * 2] = k;
                arr[i * 2 + 1] = t.stamp;
            }
            PacketDistributor.sendToServer(new WarRoomAskPayload(table, overlay, claimsVersion, arr));
        }
    }

    /** The tiles in view not had, or not asked after for a while (missing first, nearest the middle first). */
    private List<Long> wantedTiles() {
        List<long[]> out = new ArrayList<>();
        int tx0 = Math.floorDiv((int) Math.floor(worldX(mapX)), TILE_BLOCKS), tx1 = Math.floorDiv((int) Math.ceil(worldX(mapX + mapW)), TILE_BLOCKS);
        int tz0 = Math.floorDiv((int) Math.floor(worldZ(mapY)), TILE_BLOCKS), tz1 = Math.floorDiv((int) Math.ceil(worldZ(mapY + mapH)), TILE_BLOCKS);
        int mx = Math.floorDiv((int) cx, TILE_BLOCKS), mz = Math.floorDiv((int) cz, TILE_BLOCKS);
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                long k = ChunkPos.asLong(tx, tz);
                Tile t = TILES.get(k);
                boolean missing = t == null || t.stamp == 0;
                if (t != null && ticks - t.askedAt < (missing ? 40 : 400)) continue;
                out.add(new long[]{ k, (missing ? 0 : 1000) + Math.abs(tx - mx) + Math.abs(tz - mz) });
            }
        }
        out.sort((a, b) -> Long.compare(a[1], b[1]));
        List<Long> keys = new ArrayList<>();
        for (long[] o : out) keys.add(o[0]);
        return keys;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (inMap(mx, my) && !(sideShown && narrow && mx >= sideX)) {
            if (button == 0) {
                String hit = townAt(mx, my);
                if (hit != null) {
                    selected = hit;
                    face("town");
                    return true;
                }
                dragging = true;
                dragMX = mx;
                dragMY = my;
                dragX = cx;
                dragZ = cz;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            cx = dragX - (mx - dragMX) / ppb();
            cz = dragZ - (my - dragMY) / ppb();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (sideShown && mx >= sideX && face.equals("news")) {
            newsScroll = Math.max(0, newsScroll - (int) Math.signum(dy));
            return true;
        }
        if (inMap(mx, my)) {
            zoomBy(dy > 0 ? 1 : -1, mx, my);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        double step = 48 / ppb();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> { cx -= step; return true; }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> { cx += step; return true; }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> { cz -= step; return true; }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> { cz += step; return true; }
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> { zoomBy(1, mapX + mapW / 2.0, mapY + mapH / 2.0); return true; }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> { zoomBy(-1, mapX + mapW / 2.0, mapY + mapH / 2.0); return true; }
            case GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_HOME -> { home(); return true; }
            default -> { return super.keyPressed(key, scan, mods); }
        }
    }

    private boolean inMap(double mx, double my) {
        return mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH;
    }

    @Nullable
    private String townAt(double mx, double my) {
        String best = null;
        double bd = 64;
        for (CompoundTag t : list("towns")) {
            double dx = sx(t.getInt("x")) - mx, dy = sy(t.getInt("z")) - my;
            double d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = t.getString("id");
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ drawing

    /** What the mouse is over on the map, for the tooltip drawn over the widgets. */
    @Nullable private List<Component> tip;

    /**
     * All of the screen's own painting, between the background (and the menu blur, which runs in
     * super.renderBackground) and the widgets: painted after super.render it would be blurred again (see MapScreen).
     */
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        Ui.panel(g, left, top, w, h, 18);
        String title = data.getString("title") + (data.getBoolean("all") ? " — the whole world" : "");
        g.drawString(font, Ui.clip(font, title + "  ·  day " + (data.getLong("day") + 1), Math.max(40, w - 230)), left + 6, top + 5, Ui.INK, false);
        g.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1, Ui.EDGE);
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, PAPER);
        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        drawTiles(g);
        tip = drawOverlay(g, mx, my);
        g.disableScissor();
        drawScale(g);
        boolean overSide = sideShown && mx >= sideX && mx < sideX + sideW;
        if (inMap(mx, my) && !overSide) drawHover(g, mx, my);
        else tip = null;
        if (sideShown) drawSide(g, mx, my);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        if (tip != null) g.renderComponentTooltip(font, tip, mx, my);
    }

    private void drawTiles(GuiGraphics g) {
        double ppb = ppb();
        int size = (int) Math.round(TILE_BLOCKS * ppb);
        int tx0 = Math.floorDiv((int) Math.floor(worldX(mapX)), TILE_BLOCKS), tx1 = Math.floorDiv((int) Math.ceil(worldX(mapX + mapW)), TILE_BLOCKS);
        int tz0 = Math.floorDiv((int) Math.floor(worldZ(mapY)), TILE_BLOCKS), tz1 = Math.floorDiv((int) Math.ceil(worldZ(mapY + mapH)), TILE_BLOCKS);
        int baked = 0;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                Tile t = TILES.get(ChunkPos.asLong(tx, tz));
                if (t == null) continue;
                if (t.bakedFor != bakeVersion * 31 + t.stamp) {
                    if (baked >= 6) continue;                            // a few a frame: no hitch when the borders change
                    bake(t, tx, tz);
                    baked++;
                }
                if (t.rl == null) continue;
                g.blit(t.rl, sx(tx * TILE_BLOCKS), sy(tz * TILE_BLOCKS), size, size, 0F, 0F, TILE_PX, TILE_PX, TILE_PX, TILE_PX);
            }
        }
    }

    /** The tile's terrain, with every town's lands tinted over it and their borders ruled, into its texture. */
    private static void bake(Tile t, int tx, int tz) {
        Minecraft mc = Minecraft.getInstance();
        if (t.tex == null) {
            t.tex = new DynamicTexture(TILE_PX, TILE_PX, true);
            t.rl = ResourceLocation.fromNamespaceAndPath("mc_assistant", "war_room/tile_" + (tx & 0xFFFFFFFFL) + "_" + (tz & 0xFFFFFFFFL));
            mc.getTextureManager().register(t.rl, t.tex);
        }
        NativeImage img = t.tex.getPixels();
        if (img == null) return;
        for (int pz = 0; pz < TILE_PX; pz++) {
            for (int px = 0; px < TILE_PX; px++) {
                int b = t.px == null ? 0 : t.px[pz * TILE_PX + px] & 0xFF;
                int argb = b == 0 ? 0 : 0xFF000000 | abgrToRgb(MapColor.getColorFromPackedId(b));
                int cxk = tx * 16 + px / 4, czk = tz * 16 + pz / 4;
                long chunk = ChunkPos.asLong(cxk, czk);
                int o = OWNER.getOrDefault(chunk, 0);
                if (o > 0) {
                    int colour = CLAIM_COLOURS.get(o - 1)[0];
                    boolean edge = px % 4 == 0 && OWNER.getOrDefault(ChunkPos.asLong(cxk - 1, czk), 0) != o
                        || px % 4 == 3 && OWNER.getOrDefault(ChunkPos.asLong(cxk + 1, czk), 0) != o
                        || pz % 4 == 0 && OWNER.getOrDefault(ChunkPos.asLong(cxk, czk - 1), 0) != o
                        || pz % 4 == 3 && OWNER.getOrDefault(ChunkPos.asLong(cxk, czk + 1), 0) != o;
                    int base = argb == 0 ? 0xFF000000 | (PAPER & 0xFFFFFF) : argb;
                    if (edge) argb = 0xFF000000 | darken(colour, 0.8F);
                    else if (CONTESTED.contains(chunk) && (px + pz) % 4 == 0) argb = 0xFF000000 | (GOLD & 0xFFFFFF);
                    else argb = 0xFF000000 | blend(base & 0xFFFFFF, colour, argb == 0 ? 0.22F : 0.28F);
                }
                img.setPixelRGBA(px, pz, rgbToAbgr(argb));
            }
        }
        t.tex.upload();
        t.bakedFor = bakeVersion * 31 + t.stamp;
    }

    private static int abgrToRgb(int abgr) {
        return (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16) & 0xFF;
    }

    private static int rgbToAbgr(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return a << 24 | b << 16 | gg << 8 | r;
    }

    private static int blend(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int gg = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bb = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return r << 16 | gg << 8 | bb;
    }

    private static int darken(int rgb, float f) {
        return ((int) (((rgb >> 16) & 255) * f)) << 16 | ((int) (((rgb >> 8) & 255) * f)) << 8 | (int) ((rgb & 255) * f);
    }

    /** Everything over the terrain. Returns the tooltip for what the mouse is over. */
    @Nullable
    private List<Component> drawOverlay(GuiGraphics g, int mx, int my) {
        List<Component> tip = null;
        double ppb = ppb();
        // Roads and trade routes.
        for (CompoundTag r : list("roads")) {
            int[] l = r.getIntArray("line");
            if (l.length < 4) continue;
            float done = r.getFloat("done");
            int ex = (int) (l[0] + (l[2] - l[0]) * done), ez = (int) (l[1] + (l[3] - l[1]) * done);
            line(g, sx(l[0]), sy(l[1]), sx(ex), sy(ez), 0xFF8B5A2B, 1, 0);
            if (done < 1) line(g, sx(ex), sy(ez), sx(l[2]), sy(l[3]), 0x808B5A2B, 1, 3);
        }
        for (CompoundTag r : list("routes")) {
            int[] l = r.getIntArray("line");
            if (l.length < 4) continue;
            line(g, sx(l[0]), sy(l[1]), sx(l[2]), sy(l[3]), r.getString("kind").equals("deal") ? 0xFFD4A017 : 0xC0D4A017, 1, 4);
            if (near(mx, my, (sx(l[0]) + sx(l[2])) / 2, (sy(l[1]) + sy(l[3])) / 2, 5)) {
                tip = List.of(Component.literal("Trade: " + r.getString("label")), Component.literal(r.getString("kind").equals("deal")
                    ? "a standing deal: caravans both ways" : "a trade pact"));
            }
        }
        // Border markers, close in.
        if (ppb >= 0.5) {
            for (Tag t : data.getList("markers", Tag.TAG_INT_ARRAY)) {
                int[] m = ((net.minecraft.nbt.IntArrayTag) t).getAsIntArray();
                if (m.length < 4) continue;
                int x = sx(m[0]), y = sy(m[1]);
                g.fill(x - 1, y - 2, x + 1, y + 1, 0xFF000000 | m[2]);
                if (m[3] == 0) g.fill(x - 1, y - 3, x + 2, y - 2, 0xFF000000 | m[2]);
            }
        }
        // Ruins.
        for (CompoundTag r : list("ruins")) {
            int x = sx(r.getInt("x")), y = sy(r.getInt("z"));
            g.fill(x - 3, y - 3, x + 3, y + 3, 0xFF6E6E6E);
            line(g, x - 3, y - 3, x + 2, y + 2, 0xFF2E2E2E, 1, 0);
            line(g, x - 3, y + 2, x + 2, y - 3, 0xFF2E2E2E, 1, 0);
            if (near(mx, my, x, y, 4)) tip = List.of(Component.literal(r.getString("label")));
        }
        // The armies: their roads, the head with an arrow, their camps; battles and sieges.
        for (CompoundTag a : list("armies")) {
            int colour = 0xFF000000 | a.getInt("colour");
            int[] path = a.getIntArray("path");
            for (int i = 0; i + 3 < path.length; i += 2) line(g, sx(path[i]), sy(path[i + 1]), sx(path[i + 2]), sy(path[i + 3]), colour & 0xA0FFFFFF, 1, 2);
            int x = sx(a.getInt("x")), y = sy(a.getInt("z"));
            double ang = 0;
            if (path.length >= 4) ang = Math.atan2(path[path.length - 1] - a.getInt("z"), path[path.length - 2] - a.getInt("x"));
            arrow(g, x, y, ang, colour);
            if (near(mx, my, x, y, 6)) {
                tip = List.of(Component.literal("The army of " + a.getString("town")), Component.literal(a.getInt("size") + " fighters — " + a.getString("stage")));
            }
        }
        for (CompoundTag c : list("camps")) {
            int x = sx(c.getInt("x")), y = sy(c.getInt("z"));
            int colour = 0xFF000000 | c.getInt("colour");
            for (int i = 0; i < 4; i++) g.fill(x - i, y - 3 + i, x + i + 1, y - 2 + i, colour);
            if (near(mx, my, x, y, 5)) tip = List.of(Component.literal(c.getString("label")));
        }
        for (CompoundTag b : list("battles")) {
            int x = sx(b.getInt("x")), y = sy(b.getInt("z"));
            boolean flash = (ticks / 10) % 2 == 0;
            line(g, x - 5, y - 5, x + 5, y + 5, flash ? RED : 0xFF202020, 2, 0);
            line(g, x - 5, y + 5, x + 5, y - 5, flash ? RED : 0xFF202020, 2, 0);
            if (near(mx, my, x, y, 7)) tip = List.of(Component.literal(b.getString("label")));
        }
        for (CompoundTag s : list("sieges")) {
            int x = sx(s.getInt("x")), y = sy(s.getInt("z"));
            ring(g, x, y, 11, RED);
            if (near(mx, my, x, y, 12)) tip = List.of(Component.literal(s.getString("label")));
        }
        // What is on the road.
        for (CompoundTag m : list("movers")) {
            int x = sx(m.getInt("x")), y = sy(m.getInt("z"));
            String kind = m.getString("kind");
            int c = switch (kind) {
                case "envoy" -> 0xFFFFFFFF;
                case "herald" -> RED;
                case "trade" -> GOLD;
                case "scout", "spy" -> GREEN;
                case "wagon" -> BROWN;
                default -> 0xFF9C6B3C;
            };
            int[] to = m.getIntArray("to");
            if (to.length == 2 && ppb >= 0.25) line(g, x, y, sx(to[0]), sy(to[1]), c & 0x50FFFFFF, 1, 3);
            if (kind.equals("wagon")) g.fill(x - 2, y - 2, x + 3, y + 2, c);
            else {
                g.fill(x - 2, y - 2, x + 2, y + 2, 0xFF000000);
                g.fill(x - 1, y - 1, x + 1, y + 1, c);
            }
            if (near(mx, my, x, y, 4)) tip = List.of(Component.literal(m.getString("label")));
        }
        // The disputes: a flag on the ground in dispute.
        for (CompoundTag d : list("disputes")) {
            int x = sx(d.getInt("x")), y = sy(d.getInt("z"));
            boolean live = d.getBoolean("live");
            g.fill(x, y - 8, x + 1, y + 1, 0xFF3A2A1A);
            g.fill(x + 1, y - 8, x + 6, y - 4, live ? 0xFFE0A000 : 0xFF9E9E9E);
            if (near(mx, my, x + 2, y - 4, 6)) tip = List.of(Component.literal("Dispute: " + d.getString("label")), Component.literal(d.getString("state")));
        }
        // The towns.
        for (CompoundTag t : list("towns")) {
            int x = sx(t.getInt("x")), y = sy(t.getInt("z"));
            int folk = t.getInt("folk");
            int r = 3 + Math.min(4, folk / 20);
            int colour = 0xFF000000 | t.getInt("colour");
            int field = t.contains("field") ? 0xFF000000 | t.getInt("field") : colour;
            boolean us = t.getBoolean("us"), sel = t.getString("id").equals(selected);
            if (us) g.fill(x - r - 2, y - r - 2, x + r + 3, y + r + 3, GOLD);
            if (sel) g.renderOutline(x - r - 4, y - r - 4, 2 * r + 9, 2 * r + 9, 0xFFFFFFFF);
            g.fill(x - r - 1, y - r - 1, x + r + 2, y + r + 2, 0xFF000000);
            g.fill(x - r, y - r, x + r + 1, y + r + 1, field);
            g.fill(x - r / 2, y - r / 2, x + r / 2 + 1, y + r / 2 + 1, colour);
            if (t.getBoolean("war")) {
                line(g, x - r - 3, y - r - 3, x + r + 3, y + r + 3, RED, 1, 0);
            }
            String name = t.getString("name");
            int tw = (int) (font.width(name) * 0.75F);
            small(g, name, x - tw / 2, y + r + 3, us ? 0xFF1A1A1A : INK);
            if (near(mx, my, x, y, r + 3)) {
                tip = new ArrayList<>(List.of(Component.literal(name + " — " + t.getString("age")),
                    Component.literal(folk + " folk, " + t.getLong("days") + " days old, " + t.getInt("area") + " chunks of land")));
                if (t.contains("terms")) tip.add(Component.literal("We are " + t.getString("terms") + " with them"));
                tip.add(Component.literal("Click for its card").withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }
        // You, and the table.
        int[] you = data.getIntArray("you");
        if (you.length == 2 && Minecraft.getInstance().player != null) {
            double px = Minecraft.getInstance().player.getX(), pz = Minecraft.getInstance().player.getZ();
            int x = sx(px), y = sy(pz);
            double yaw = Math.toRadians(Minecraft.getInstance().player.getYRot() + 90);
            arrow(g, x, y, yaw, 0xFFFFFFFF);
            if (near(mx, my, x, y, 5)) tip = List.of(Component.literal("You"));
        }
        return tip;
    }

    private void drawScale(GuiGraphics g) {
        double ppb = ppb();
        int blocks = ppb >= 1 ? 50 : ppb >= 0.25 ? 200 : ppb >= 0.125 ? 500 : 1000;
        int len = (int) (blocks * ppb);
        int x = mapX + 6, y = mapY + mapH - 8;
        g.fill(x - 2, y - 9, x + len + 30, y + 4, 0xA0E4D6B0);
        g.fill(x, y, x + len, y + 1, INK);
        g.fill(x, y - 3, x + 1, y + 1, INK);
        g.fill(x + len - 1, y - 3, x + len, y + 1, INK);
        small(g, blocks + " blocks", x + 2, y - 8, INK);
        small(g, "N", mapX + mapW - 10, mapY + 4, INK);
    }

    /** The ground under the mouse: where, and whose. */
    private void drawHover(GuiGraphics g, int mx, int my) {
        int x = (int) Math.floor(worldX(mx)), z = (int) Math.floor(worldZ(my));
        long c = ChunkPos.asLong(x >> 4, z >> 4);
        int o = OWNER.getOrDefault(c, 0);
        String whose = o == 0 ? "the wilds" : CONTESTED.contains(c) ? "contested (" + CLAIM_NAMES.get(o - 1) + " holds it)" : CLAIM_NAMES.get(o - 1) + "'s land";
        String s = x + ", " + z + " — " + whose;
        int tw = (int) (font.width(s) * 0.75F);
        int bx = mapX + mapW - tw - 8, by = mapY + mapH - 10;
        g.fill(bx - 2, by - 2, bx + tw + 3, by + 8, 0xA0E4D6B0);
        small(g, s, bx, by, INK);
    }

    // ------------------------------------------------------------------ the side panel

    private void drawSide(GuiGraphics g, int mx, int my) {
        int x = sideX, y = mapY, sw = sideW, sh = mapH;
        g.fill(x - 1, y - 1, x + sw + 1, y + sh + 1, Ui.EDGE);
        g.fill(x, y, x + sw, y + sh, 0xFFEFE6CC);
        int cy = y + 4;
        switch (face) {
            case "legend" -> legend(g, x + 4, cy, sw - 8);
            case "news" -> news(g, x + 4, cy, sw - 8, sh - 8);
            default -> card(g, x + 4, cy, sw - 8, sh - 8);
        }
    }

    private void card(GuiGraphics g, int x, int y, int sw, int sh) {
        CompoundTag t = null;
        for (CompoundTag c : list("towns")) if (c.getString("id").equals(selected)) t = c;
        if (t == null) {
            wrap(g, "Click a town on the map for its card. Drag to move about, scroll to zoom; ⌂ brings you home.", x, y, sw, MUTED);
            return;
        }
        int cy = y;
        g.fill(x, cy, x + 8, cy + 8, 0xFF000000 | (t.contains("field") ? t.getInt("field") : t.getInt("colour")));
        g.fill(x + 2, cy + 2, x + 6, cy + 6, 0xFF000000 | t.getInt("colour"));
        g.drawString(font, Ui.clip(font, t.getString("name"), sw - 12), x + 11, cy, INK, false);
        cy += 11;
        List<String> lines = new ArrayList<>();
        lines.add(t.getString("age") + ", " + t.getInt("folk") + " folk, " + t.getLong("days") + " days old");
        lines.add("Its lands: " + t.getInt("area") + " chunks");
        if (t.contains("motto")) lines.add("“" + t.getString("motto") + "”");
        if (t.contains("vassal")) lines.add("A vassal of " + t.getString("vassal"));
        if (t.getBoolean("us")) lines.add("Our town.");
        else if (t.contains("terms")) {
            String terms = "We are " + t.getString("terms") + " (" + t.getInt("rel") + ")";
            if (t.getBoolean("war")) terms += " — AT WAR";
            else if (t.getBoolean("allied")) terms += " — allies";
            lines.add(terms);
            if (t.getBoolean("pact")) lines.add("We trade with them under a pact.");
            lines.add(t.getBoolean("agreed") ? "Our border with them is agreed." : "No border agreed between us.");
            if (t.contains("dispute")) lines.add("In dispute over " + t.getString("dispute"));
        }
        if (t.contains("strength")) {
            lines.add("Strength " + (t.getBoolean("us") ? "(counted)" : "(as we reckon it)") + ": " + t.getInt("strength")
                + (t.getBoolean("us") ? "" : " — " + t.getString("basis") + (t.getBoolean("guessed") ? ", a guess" : "")));
        }
        ListTag wars = t.getList("wars", Tag.TAG_STRING), trade = t.getList("trade", Tag.TAG_STRING);
        if (!wars.isEmpty()) lines.add("At war with: " + join(wars));
        if (!trade.isEmpty()) lines.add("Trades with: " + join(trade));
        for (String s : lines) {
            if (cy > y + sh - 9) break;
            cy = wrap(g, s, x, cy, sw, s.contains("AT WAR") ? RED : MUTED) + 2;
        }
    }

    private void legend(GuiGraphics g, int x, int y, int sw) {
        int cy = y;
        g.drawString(font, "Legend", x, cy, INK, false);
        cy += 12;
        String[][] rows = {
            { "town", "a town: its banner, its colour, its size" }, { "us", "our town" }, { "land", "a town's lands, ruled in its colour" },
            { "contested", "ground two towns claim" }, { "road", "a road (dotted where unbuilt)" }, { "route", "a trade route" },
            { "army", "an army on the march, its road" }, { "camp", "an army's camp" }, { "battle", "a battle being fought" },
            { "siege", "a town under siege" }, { "caravan", "a caravan" }, { "envoy", "an envoy (a herald in red)" },
            { "scout", "a scout or spy of ours" }, { "wagon", "a trade wagon" }, { "flag", "a land dispute" }, { "ruin", "a ruin" },
            { "you", "you" } };
        for (String[] r : rows) {
            int gx = x + 4, gy = cy + 3;
            switch (r[0]) {
                case "town" -> { g.fill(gx - 3, gy - 3, gx + 4, gy + 4, 0xFF000000); g.fill(gx - 2, gy - 2, gx + 3, gy + 3, 0xFF324C9C); }
                case "us" -> { g.fill(gx - 4, gy - 4, gx + 5, gy + 5, GOLD); g.fill(gx - 2, gy - 2, gx + 3, gy + 3, 0xFF9C2A2E); }
                case "land" -> { g.fill(gx - 4, gy - 3, gx + 5, gy + 4, 0xFFB7C8A0); g.renderOutline(gx - 4, gy - 3, 9, 7, 0xFF324C9C); }
                case "contested" -> { g.fill(gx - 4, gy - 3, gx + 5, gy + 4, 0xFFB7C8A0); for (int i = -4; i < 5; i += 3) g.fill(gx + i, gy - 3, gx + i + 1, gy + 4, GOLD); }
                case "road" -> g.fill(gx - 4, gy, gx + 5, gy + 1, 0xFF8B5A2B);
                case "route" -> { for (int i = -4; i < 5; i += 3) g.fill(gx + i, gy, gx + i + 1, gy + 1, 0xFFD4A017); }
                case "army" -> arrow(g, gx, gy, 0, 0xFF9C2A2E);
                case "camp" -> { for (int i = 0; i < 4; i++) g.fill(gx - i, gy - 2 + i, gx + i + 1, gy - 1 + i, 0xFF9C2A2E); }
                case "battle" -> { line(g, gx - 3, gy - 3, gx + 3, gy + 3, RED, 1, 0); line(g, gx - 3, gy + 3, gx + 3, gy - 3, RED, 1, 0); }
                case "siege" -> ring(g, gx, gy, 4, RED);
                case "caravan" -> { g.fill(gx - 2, gy - 2, gx + 2, gy + 2, 0xFF000000); g.fill(gx - 1, gy - 1, gx + 1, gy + 1, 0xFF9C6B3C); }
                case "envoy" -> { g.fill(gx - 2, gy - 2, gx + 2, gy + 2, 0xFF000000); g.fill(gx - 1, gy - 1, gx + 1, gy + 1, 0xFFFFFFFF); }
                case "scout" -> { g.fill(gx - 2, gy - 2, gx + 2, gy + 2, 0xFF000000); g.fill(gx - 1, gy - 1, gx + 1, gy + 1, GREEN); }
                case "wagon" -> g.fill(gx - 2, gy - 2, gx + 3, gy + 2, BROWN);
                case "flag" -> { g.fill(gx - 2, gy - 4, gx - 1, gy + 4, 0xFF3A2A1A); g.fill(gx - 1, gy - 4, gx + 4, gy, 0xFFE0A000); }
                case "ruin" -> g.fill(gx - 3, gy - 3, gx + 3, gy + 3, 0xFF6E6E6E);
                case "you" -> arrow(g, gx, gy, -Math.PI / 2, 0xFFFFFFFF);
                default -> { }
            }
            small(g, Ui.clip(font, r[1], (int) ((sw - 16) / 0.75F)), x + 14, cy, MUTED);
            cy += 10;
        }
        cy += 4;
        wrap(g, data.getBoolean("all") ? "You see the whole world." : "The table shows only what its town knows: its own country, where its scouts have been, and the towns it has met.",
            x, cy, sw, 0xFF6A5E48);
    }

    private void news(GuiGraphics g, int x, int y, int sw, int sh) {
        int cy = y;
        g.drawString(font, "What's new", x, cy, INK, false);
        cy += 12;
        ListTag n = data.getList("news", Tag.TAG_STRING);
        if (n.isEmpty()) {
            wrap(g, "Nothing new.", x, cy, sw, MUTED);
            return;
        }
        for (int i = Math.min(newsScroll, n.size() - 1); i < n.size(); i++) {
            if (cy > y + sh - 9) break;
            cy = wrap(g, n.getString(i), x, cy, sw, MUTED) + 3;
        }
    }

    // ------------------------------------------------------------------ little drawing helpers

    private List<CompoundTag> list(String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = data.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    private static String join(ListTag l) {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) s.add(l.getString(i));
        return String.join(", ", s);
    }

    private static boolean near(int mx, int my, int x, int y, int r) {
        return Math.abs(mx - x) <= r && Math.abs(my - y) <= r;
    }

    private void small(GuiGraphics g, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    /** Text wrapped to the width at three-quarter size; returns the y after it. */
    private int wrap(GuiGraphics g, String s, int x, int y, int width, int colour) {
        for (FormattedCharSequence line : font.split(Component.literal(s), (int) (width / 0.75F))) {
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(0.75F, 0.75F, 1F);
            g.drawString(font, line, 0, 0, colour, false);
            g.pose().popPose();
            y += 8;
        }
        return y;
    }

    /** A line of small squares (dotted when {@code gap} > 0), clipped to a sane length. */
    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int colour, int thick, int gap) {
        int dx = x1 - x0, dy = y1 - y0;
        int n = Math.max(Math.abs(dx), Math.abs(dy));
        if (n == 0 || n > 4000) return;
        int step = 1;
        for (int i = 0; i <= n; i += step) {
            if (gap > 0 && (i / (gap + 1)) % 2 == 1) continue;
            int x = x0 + dx * i / n, y = y0 + dy * i / n;
            g.fill(x, y, x + thick, y + thick, colour);
        }
    }

    private static void ring(GuiGraphics g, int x, int y, int r, int colour) {
        for (int a = 0; a < 48; a++) {
            double t = a * Math.PI * 2 / 48;
            int px = x + (int) Math.round(Math.cos(t) * r), py = y + (int) Math.round(Math.sin(t) * r);
            g.fill(px, py, px + 1, py + 1, colour);
        }
    }

    /** A small arrowhead pointing the way {@code ang} (radians, 0 east, clockwise as the map is). */
    private static void arrow(GuiGraphics g, int x, int y, double ang, int colour) {
        double c = Math.cos(ang), s = Math.sin(ang);
        int tx = x + (int) Math.round(c * 5), ty = y + (int) Math.round(s * 5);
        int lx = x + (int) Math.round(-c * 3 - s * 3), ly = y + (int) Math.round(-s * 3 + c * 3);
        int rx = x + (int) Math.round(-c * 3 + s * 3), ry = y + (int) Math.round(-s * 3 - c * 3);
        line(g, lx, ly, tx, ty, 0xFF000000, 2, 0);
        line(g, rx, ry, tx, ty, 0xFF000000, 2, 0);
        line(g, lx, ly, tx, ty, colour, 1, 0);
        line(g, rx, ry, tx, ty, colour, 1, 0);
        g.fill(x - 1, y - 1, x + 2, y + 2, colour);
    }

    /** For the pictures and tests on the client: how many tiles are held. */
    public static int tilesHeld() {
        int n = 0;
        for (Iterator<Tile> it = TILES.values().iterator(); it.hasNext(); ) if (it.next().px != null) n++;
        return n;
    }

    @SuppressWarnings("unused")
    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
