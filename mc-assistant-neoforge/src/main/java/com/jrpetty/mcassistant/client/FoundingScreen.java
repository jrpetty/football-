package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.FoundingScreenPayload;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.VillageMath;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Found a village. [found23] One screen for every way in (the U page, a folk, a board's header, the Founding Kit, a
 * spawner or charter right-clicked in the air, a spawner's waiting board), in two steps:
 * <ol>
 *   <li><b>Where</b>: here, or up to three better spots near that the server found (entity/SiteFinder), each rated with
 *       why ("flat meadow by a river, oak wood 20 blocks"), on a map of the ground round about. The one picked is outlined
 *       on the real ground (FoundingGhost): its square, its first ring, the ground made level; "Look at it" puts the
 *       screen away to see it (U brings it back).</li>
 *   <li><b>How big</b>: a hamlet of 8, a village of 25, a town of 70, a city of 250, or any number on the slider, and what
 *       that means (ground levelled, the trades, the homes, what it costs a server); its name (suggested, editable) and
 *       look; "Take me there" if the server allows it and it is near enough. Confirm.</li>
 * </ol>
 * At a spawner's waiting board the spot is the board's, so it opens on the second step. The server checks it all again.
 */
public class FoundingScreen extends Screen {

    private static final int W = 340, H = 240, PAD = 10, MAP = 124;
    private static final int[] PRESETS = { 8, 25, 70, 250 };
    private static final String[] PRESET_NAMES = { "Hamlet", "Village", "Town", "City" };

    record Pick(int x, int y, int z, int score, String words, String no, boolean here, int dist, String bearing) {}

    private final CompoundTag data;
    private final boolean board;
    private final BlockPos boardPos;
    private final List<Pick> picks = new ArrayList<>();
    private final int most, worldFolk, worldCap, travelReach, bare, plaza;
    private final boolean travelAllowed;
    private final String no, need;
    private final List<String[]> themes = new ArrayList<>();
    private final int[] player;
    private int count, chosen, themeAt, step, nameAt;
    private String name;
    private boolean travel = true;
    private int left, top, h = H;
    private CountSlider slider;
    private EditBox nameBox;
    // the map, baked once
    private DynamicTexture mapTex;
    private ResourceLocation mapId;
    private int mapX0, mapZ0, mapSize, mapStep;
    /** The part of the map shown: the ground seen, squared (cells from the map's corner, and how many across). */
    private int viewI, viewJ, viewN;

    public FoundingScreen(CompoundTag t) {
        super(Component.literal(t.getString("mode").equals("board") ? "Found a village" : "Start a village"));
        this.data = t;
        this.board = t.getString("mode").equals("board");
        this.boardPos = BlockPos.of(t.getLong("board"));
        this.most = Math.max(FoundingPlan.MIN_FOLK, Math.min(FoundingPlan.MAX_FOLK, t.getInt("most") <= 0 ? FoundingPlan.MAX_FOLK : t.getInt("most")));
        this.worldFolk = t.getInt("worldFolk");
        this.worldCap = t.contains("worldCap") ? t.getInt("worldCap") : Integer.MAX_VALUE;
        this.travelAllowed = t.getBoolean("travel");
        this.travelReach = t.getInt("travelReach");
        this.bare = t.contains("bare") ? t.getInt("bare") : FoundingPlan.BARE;
        this.plaza = t.contains("plaza") ? t.getInt("plaza") : 13;
        this.no = t.getString("no");
        this.need = t.getString("need");
        this.name = t.getString("name");
        this.player = t.getIntArray("player").length >= 3 ? t.getIntArray("player") : new int[]{ 0, 64, 0 };
        for (Tag e : t.getList("picks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            int[] at = c.getIntArray("at");
            if (at.length < 3) continue;
            picks.add(new Pick(at[0], at[1], at[2], c.getInt("score"), c.getString("words"), c.getString("no"), c.getBoolean("here"),
                c.getInt("dist"), c.getString("bearing")));
        }
        for (Tag e : t.getList("themes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            themes.add(new String[]{ c.getString("id"), c.getString("name"), c.getString("about") });
            if (c.getString("id").equals(t.getString("theme"))) themeAt = themes.size() - 1;
        }
        this.count = clamp(t.getInt("start") > 0 ? t.getInt("start") : 25);
        // The best spot first, if here may not be founded on and another may.
        this.chosen = 0;
        if (!picks.isEmpty() && !picks.get(0).no().isEmpty() && picks.size() > 1) chosen = 1;
        this.step = board || picks.isEmpty() ? 2 : 1;
    }

    /** The old board payload (a server that did not send the new one), or the word to close. */
    public static void show(FoundingScreenPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!p.open()) {
            if (mc.screen instanceof FoundingScreen s && s.board && s.boardPos.equals(p.board())) s.onClose();
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putString("mode", "board");
        t.putLong("board", p.board().asLong());
        t.putInt("start", p.start());
        t.putInt("most", p.most());
        t.putInt("worldFolk", p.worldFolk());
        t.putInt("worldCap", p.worldCap());
        show(t);
    }

    /** The server's founding screen. */
    public static void show(CompoundTag t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        FoundingClient.forgetLook();
        mc.setScreen(new FoundingScreen(t));
    }

    private int clamp(int n) {
        return Math.max(FoundingPlan.MIN_FOLK, Math.min(most, n));
    }

    private Pick pick() {
        return picks.isEmpty() ? null : picks.get(Math.min(chosen, picks.size() - 1));
    }

    /** Why Confirm will not go, or empty. */
    private String blocked() {
        if (!no.isEmpty()) return no;
        if (!need.isEmpty()) return need;
        Pick p = pick();
        if (!board && p != null && !p.no().isEmpty()) return "Not there: " + p.no() + ".";
        if (!board && p == null) return "No spot to found on here.";
        return "";
    }

    // ------------------------------------------------------------------ the slider, on a lean

    private double toSlider(int n) {
        if (most <= FoundingPlan.MIN_FOLK) return 0;
        return (Math.log(n) - Math.log(FoundingPlan.MIN_FOLK)) / (Math.log(most) - Math.log(FoundingPlan.MIN_FOLK));
    }

    private int fromSlider(double v) {
        double n = Math.exp(Math.log(FoundingPlan.MIN_FOLK) + v * (Math.log(most) - Math.log(FoundingPlan.MIN_FOLK)));
        return clamp((int) Math.round(n));
    }

    private final class CountSlider extends AbstractSliderButton {
        CountSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), toSlider(count));
            updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            UiStyle.well(g, x, y, w, h, UiStyle.WELL);
            int hx = x + (int) (this.value * (w - 8));
            g.fill(x + 1, y + 1, hx + 4, y + h - 1, UiStyle.alpha(UiStyle.ACCENT_FILL, 0.45F));
            UiStyle.tile(g, hx, y, 8, h, UiStyle.State.of(true, false, isHoveredOrFocused()));
            String label = getMessage().getString();
            g.drawString(Minecraft.getInstance().font, label, x + (w - Minecraft.getInstance().font.width(label)) / 2, y + (h - 8) / 2, UiStyle.TEXT, false);
        }

        void set(int n) {
            this.value = toSlider(n);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(count + " folk"));
        }

        @Override
        protected void applyValue() {
            count = fromSlider(this.value);
            ghost();
        }
    }

    private void setCount(int n) {
        count = clamp(n);
        if (slider != null) slider.set(count);
        ghost();
    }

    /** The outline on the ground follows the spot and the size. */
    private void ghost() {
        Pick p = pick();
        if (p == null) return;
        FoundingGhost.set(p.x(), p.y(), p.z(), FoundingPlan.coreRadius(count), bare, plaza, board || p.no().isEmpty());
    }

    // ------------------------------------------------------------------ laying it out

    @Override
    protected void init() {
        this.h = Math.min(H, this.height - 8);
        this.left = (this.width - W) / 2;
        this.top = (this.height - h) / 2;
        if (step == 1) initWhere();
        else initHowBig();
        ghost();
    }

    private void initWhere() {
        bakeMap();
        int by = top + h - PAD - 20;
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose()).bounds(left + PAD, by, 70, 20).build(UiButton::new));
        Button look = Button.builder(Component.literal("Look at it"), b -> FoundingClient.look(this, where())).bounds(left + PAD + 74, by, 90, 20).build(UiButton::new);
        look.setTooltip(Tooltip.create(Component.literal("Put this away and see the spot outlined on the ground. Press U to come back.")));
        this.addRenderableWidget(look);
        Button next = UiButton.primary(Button.builder(Component.literal("Next: how big"), b -> { step = 2; rebuild(); })
            .bounds(left + W - PAD - 120, by, 120, 20));
        this.addRenderableWidget(next);
    }

    private void initHowBig() {
        int x = left + PAD, inner = W - PAD * 2;
        int y = top + 36;
        int gap = 3, bw = (inner - gap * (PRESETS.length - 1)) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) {
            int n = PRESETS[i];
            String label = PRESET_NAMES[i] + " of " + n;
            Button b = Button.builder(Component.literal(label), btn -> setCount(n))
                .bounds(x + i * (bw + gap), y, bw, 18).build(b2 -> new Preset(b2, n));
            b.active = n <= most;
            b.setTooltip(Tooltip.create(Component.literal(n <= most ? presetTip(i) : "More than this server lets a village be founded with (" + most + ")")));
            this.addRenderableWidget(b);
        }
        y += 22;
        Button less = Button.builder(Component.literal("−"), b -> setCount(count - (hasShiftDown() ? 10 : 1))).bounds(x, y, 20, 20).build(UiButton::new);
        less.setTooltip(Tooltip.create(Component.literal("One fewer (with Shift, ten)")));
        this.addRenderableWidget(less);
        slider = new CountSlider(x + 24, y, inner - 48, 20);
        this.addRenderableWidget(slider);
        Button more = Button.builder(Component.literal("+"), b -> setCount(count + (hasShiftDown() ? 10 : 1))).bounds(x + inner - 20, y, 20, 20).build(UiButton::new);
        more.setTooltip(Tooltip.create(Component.literal("One more (with Shift, ten)")));
        this.addRenderableWidget(more);
        y += 26;
        nameBox = new EditBox(this.font, x + 40, y, 120, 18, Component.literal("Name"));
        nameBox.setMaxLength(24);
        nameBox.setValue(name);
        nameBox.setResponder(s -> name = s);
        this.addRenderableWidget(nameBox);
        Button again = Button.builder(Component.literal("Another"), b -> {
            List<String> names = new ArrayList<>();
            for (Tag e : data.getList("names", Tag.TAG_STRING)) names.add(e.getAsString());
            if (!names.isEmpty()) name = names.get(nameAt++ % names.size());
            nameBox.setValue(name);
        }).bounds(x + 164, y - 1, 52, 20).build(UiButton::new);
        again.setTooltip(Tooltip.create(Component.literal("Suggest another name")));
        this.addRenderableWidget(again);
        if (themes.size() > 1) {
            Button look = Button.builder(Component.literal(themeLabel()), b -> {
                themeAt = (themeAt + 1) % themes.size();
                b.setMessage(Component.literal(themeLabel()));
                b.setTooltip(Tooltip.create(Component.literal(themes.get(themeAt)[2])));
            }).bounds(x + 220, y - 1, inner - 220, 20).build(UiButton::new);
            look.setTooltip(Tooltip.create(Component.literal(themes.get(themeAt)[2])));
            this.addRenderableWidget(look);
        }
        int by = top + h - PAD - 20;
        Pick p = pick();
        if (!board && p != null && !p.here()) {
            boolean near = travelAllowed && p.dist() <= travelReach;
            Button t = Button.builder(Component.literal(travelLabel(near)), b -> {
                travel = !travel;
                b.setMessage(Component.literal(travelLabel(true)));
            }).bounds(x, by - 24, inner, 18).build(UiButton::new);
            t.active = near;
            t.setTooltip(Tooltip.create(Component.literal(near ? "Carried to the edge of the town's ground once it is begun (falling softly)."
                : !travelAllowed ? "This server has you walk there." : "Too far to be carried (this server: " + travelReach + " blocks). Walk there.")));
            this.addRenderableWidget(t);
        }
        if (!board) {
            this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> { step = 1; rebuild(); }).bounds(left + PAD, by, 60, 20).build(UiButton::new));
        } else {
            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose()).bounds(left + PAD, by, 60, 20).build(UiButton::new));
        }
        Button look = Button.builder(Component.literal("Look at it"), b -> FoundingClient.look(this, where())).bounds(left + PAD + 64, by, 80, 20).build(UiButton::new);
        look.setTooltip(Tooltip.create(Component.literal("Put this away and see the town's ground outlined. Press U to come back.")));
        this.addRenderableWidget(look);
        String why = blocked();
        Button go = UiButton.primary(Button.builder(Component.literal("Confirm and found"), b -> confirm()).bounds(left + W - PAD - 130, by, 130, 20));
        go.active = why.isEmpty();
        go.setTooltip(Tooltip.create(Component.literal(why.isEmpty() ? "The ground is made level first; the folk come as soon as the heart of it is." : why)));
        this.addRenderableWidget(go);
    }

    /** Where the spot picked is, in words, for the line shown while looking at it. */
    private String where() {
        Pick p = pick();
        if (p == null || board || p.here()) return "The spot is where you stand";
        return "Spot " + (chosen + 1) + " is " + p.dist() + " blocks " + p.bearing() + " (the tall gold post)";
    }

    private String presetTip(int i) {
        return switch (i) {
            case 0 -> "A hamlet: a handful of homes and fields. Light on any server.";
            case 1 -> "A village: farms, a smithy, a store, a well on the square.";
            case 2 -> "A town: every trade, streets of houses. A busy town for a server.";
            default -> "A city: hundreds of folk. Heavy for a server.";
        };
    }

    private String themeLabel() {
        return "Look: " + themes.get(themeAt)[1];
    }

    private String travelLabel(boolean near) {
        Pick p = pick();
        String where = p == null ? "" : " (" + p.dist() + " blocks " + p.bearing() + ")";
        if (!near) return "It's" + where.replace("(", "").replace(")", "") + ": walk there";
        return (travel ? "[x] " : "[  ] ") + "Take me there" + where;
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private void confirm() {
        Pick p = pick();
        String n = nameBox == null ? name : nameBox.getValue();
        String theme = themes.isEmpty() ? "" : themes.get(themeAt)[0];
        CompoundTag a = new CompoundTag();
        a.putInt("count", count);
        a.putString("name", n);
        a.putString("theme", theme);
        if (board) {
            a.putLong("board", boardPos.asLong());
            FoundingClient.ask("board", a);
        } else if (p != null) {
            a.putInt("x", p.x());
            a.putInt("z", p.z());
            a.putBoolean("travel", travel && travelAllowed && p.dist() <= travelReach && !p.here());
            FoundingClient.ask("found", a);
        }
        FoundingGhost.keep();
        this.onClose();
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
        if (!FoundingClient.looking(this)) FoundingGhost.fade();
        if (mapId != null) {
            Minecraft.getInstance().getTextureManager().release(mapId);
            mapId = null;
            mapTex = null;
        }
    }

    // ------------------------------------------------------------------ the map

    private void bakeMap() {
        if (mapId != null) return;
        CompoundTag m = data.getCompound("map");
        int size = m.getInt("size");
        byte[] kinds = m.getByteArray("kinds"), heights = m.getByteArray("heights");
        if (size <= 0 || kinds.length < size * size) return;
        mapX0 = m.getInt("x0");
        mapZ0 = m.getInt("z0");
        mapSize = size;
        mapStep = Math.max(1, m.getInt("step"));
        mapTex = new DynamicTexture(size, size, true);
        NativeImage img = mapTex.getPixels();
        if (img == null) return;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int k = i * size + j;
                int rgb = switch (kinds[k]) {
                    case 1 -> 0x7FB238;      // grass
                    case 2 -> 0x4060D0;      // water
                    case 3 -> 0x2F6B1E;      // trees
                    case 4 -> 0xE0D69A;      // sand
                    case 5 -> 0x8A8A8A;      // stone
                    case 6 -> 0xF4F8FA;      // snow
                    case 7 -> 0xD85A10;      // lava
                    default -> 0x2A2A2A;     // not seen
                };
                if (kinds[k] != 0 && kinds[k] != 2) {
                    // a little light on the high ground and shade on the low, and a ridge lit from the north-west
                    int hk = heights.length > k ? heights[k] : 0;
                    int up = j > 0 ? (heights.length > k - 1 ? heights[k - 1] : hk) : hk;
                    float f = 1.0F + Math.max(-0.35F, Math.min(0.3F, hk * 0.012F + (hk - up) * 0.06F));
                    rgb = shade(rgb, f);
                }
                img.setPixelRGBA(i, j, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
            }
        }
        mapTex.upload();
        int i0 = size, j0 = size, i1 = -1, j1 = -1;
        for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) if (kinds[i * size + j] != 0) {
            i0 = Math.min(i0, i); i1 = Math.max(i1, i); j0 = Math.min(j0, j); j1 = Math.max(j1, j);
        }
        if (i1 < 0) { viewI = 0; viewJ = 0; viewN = size; }
        else {
            viewN = Math.min(size, Math.max(i1 - i0, j1 - j0) + 3);
            viewI = Math.max(0, Math.min(size - viewN, (i0 + i1) / 2 - viewN / 2));
            viewJ = Math.max(0, Math.min(size - viewN, (j0 + j1) / 2 - viewN / 2));
        }
        mapId = ResourceLocation.fromNamespaceAndPath("mc_assistant", "founding/map");
        Minecraft.getInstance().getTextureManager().register(mapId, mapTex);
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, (int) ((rgb >> 16 & 0xFF) * f)), g = Math.min(255, (int) ((rgb >> 8 & 0xFF) * f)), b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return r << 16 | g << 8 | b;
    }

    private int mapPx(int x) {
        return left + PAD + (int) Math.round(((x - mapX0) / (double) mapStep - viewI) / viewN * MAP);
    }

    private int mapPy(int z) {
        return top + 26 + (int) Math.round(((z - mapZ0) / (double) mapStep - viewJ) / viewN * MAP);
    }

    private int rowY(int i) {
        return top + 26 + i * 31;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (step == 1) {
            int rx = left + PAD + MAP + 8, rw = W - PAD * 2 - MAP - 8;
            for (int i = 0; i < picks.size(); i++) {
                if (mx >= rx && mx < rx + rw && my >= rowY(i) && my < rowY(i) + 29) {
                    choose(i);
                    return true;
                }
            }
            if (mapSize > 0 && mx >= left + PAD && mx < left + PAD + MAP && my >= top + 26 && my < top + 26 + MAP) {
                int best = -1;
                double bd = 10 * 10;
                for (int i = 0; i < picks.size(); i++) {
                    double dx = mapPx(picks.get(i).x()) - mx, dy = mapPy(picks.get(i).z()) - my;
                    if (dx * dx + dy * dy < bd) { bd = dx * dx + dy * dy; best = i; }
                }
                if (best >= 0) { choose(best); return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void choose(int i) {
        UiStyle.click();
        chosen = i;
        ghost();
    }

    // ------------------------------------------------------------------ painting

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Not blurred: the world, and the outline on it, stays to be seen round the panel.
        if (this.minecraft == null || this.minecraft.level == null) super.renderBackground(g, mouseX, mouseY, partialTick);
        else g.fill(0, 0, this.width, this.height, 0x30000000);
        UiStyle.shadow(g, left, top, W, h);
        UiStyle.panel(g, left, top, W, h);
        UiStyle.headerBand(g, left, top, W, 20);
        int x = left + PAD, inner = W - PAD * 2;
        UiStyle.title(g, this.font, this.title.getString(), x, top + 7);
        Ui.right(g, this.font, board ? "at the board" : step == 1 ? "step 1 of 2: where" : "step 2 of 2: how big",
            left + W - PAD, top + 7, UiStyle.TEXT_FAINT);
        if (step == 1) where(g, mouseX, mouseY);
        else howBig(g);
    }

    private void where(GuiGraphics g, int mx, int my) {
        int x = left + PAD, y = top + 26;
        UiStyle.well(g, x - 1, y - 1, MAP + 2, MAP + 2, UiStyle.WELL);
        if (mapId != null) g.blit(mapId, x, y, MAP, MAP, (float) viewI, (float) viewJ, viewN, viewN, mapSize, mapSize);
        g.enableScissor(x, y, x + MAP, y + MAP);
        Pick sel = pick();
        if (sel != null && mapSize > 0) {
            int r = FoundingPlan.coreRadius(count);
            outline(g, mapPx(sel.x() - r), mapPy(sel.z() - r), mapPx(sel.x() + r), mapPy(sel.z() + r), 0xFFFFFFFF);
        }
        String over = "";
        if (mapSize > 0) {
            for (Tag e : data.getCompound("map").getList("towns", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                int px = mapPx(c.getInt("x")), py = mapPy(c.getInt("z"));
                g.fill(px - 3, py - 3, px + 3, py + 3, 0xFF000000);
                g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFD03020);
                if (Math.abs(mx - px) <= 4 && Math.abs(my - py) <= 4) over = c.getString("name");
            }
            for (int i = picks.size() - 1; i >= 0; i--) {
                Pick p = picks.get(i);
                int px = mapPx(p.x()), py = mapPy(p.z());
                boolean on = i == chosen;
                int fill = !p.no().isEmpty() ? 0xFF9A9A9A : on ? 0xFFFFD040 : 0xFFF0F0F0;
                g.fill(px - 5, py - 5, px + 5, py + 5, 0xFF000000);
                g.fill(px - 4, py - 4, px + 4, py + 4, fill);
                String label = p.here() ? "H" : String.valueOf(i + 1);
                g.drawString(this.font, label, px - this.font.width(label) / 2 + 1, py - 3, 0xFF000000, false);
                if (Math.abs(mx - px) <= 5 && Math.abs(my - py) <= 5) over = (p.here() ? "Here" : "Spot " + (i + 1)) + ": " + p.words();
            }
            // you
            int px = mapPx(player[0]), py = mapPy(player[2]);
            g.fill(px - 2, py - 2, px + 2, py + 2, 0xFF000000);
            g.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
        }
        g.disableScissor();
        g.drawString(this.font, "N", x + MAP / 2 - 2, y + 2, 0xFFFFFFFF, true);
        if (mapSize == 0) g.drawString(this.font, "No map", x + 40, y + 58, UiStyle.TEXT_ON_DARK, false);
        // the spots
        int rx = x + MAP + 8, rw = W - PAD * 2 - MAP - 8;
        for (int i = 0; i < picks.size(); i++) {
            Pick p = picks.get(i);
            int ry = rowY(i);
            boolean hover = mx >= rx && mx < rx + rw && my >= ry && my < ry + 29;
            UiStyle.tile(g, rx, ry, rw, 29, UiStyle.State.of(true, i == chosen, hover));
            String head = p.here() ? "Here, where you stand" : (i + 1) + ". " + p.dist() + " blocks " + p.bearing();
            g.drawString(this.font, UiStyle.clip(this.font, head, rw - 44), rx + 4, ry + 4, UiStyle.TEXT, false);
            if (p.no().isEmpty()) {
                int colour = p.score() >= 70 ? UiStyle.SUCCESS : p.score() >= 45 ? UiStyle.WARNING : UiStyle.DANGER;
                Ui.right(g, this.font, p.score() + "/100", rx + rw - 4, ry + 4, colour);
                g.drawString(this.font, UiStyle.clip(this.font, p.words(), rw - 8), rx + 4, ry + 16, UiStyle.TEXT_MUTED, false);
            } else {
                g.drawString(this.font, UiStyle.clip(this.font, "Not here: " + p.no(), rw - 8), rx + 4, ry + 16, UiStyle.DANGER, false);
            }
        }
        if (picks.size() == 1 && picks.get(0).no().isEmpty()) {
            int ry = rowY(1);
            UiStyle.text(g, this.font, "Nothing near beats it.", rx + 4, ry + 6, rw - 8, UiStyle.TEXT_FAINT);
        }
        // underneath: why not, what it takes, or the chosen spot in full
        int ly = y + MAP + 6;
        String note = !no.isEmpty() ? no : !need.isEmpty() ? need : sel == null ? "" : (sel.here() ? "Here" : "Spot " + (chosen + 1)) + ": " + sel.words()
            + (sel.no().isEmpty() ? "." : ". Not here: " + sel.no() + ".");
        int colour = !no.isEmpty() || !need.isEmpty() ? UiStyle.DANGER : UiStyle.TEXT;
        var lines = TextCache.split(this.font, note, W - PAD * 2);
        for (int i = 0; i < Math.min(3, lines.size()); i++) g.drawString(this.font, lines.get(i), x, ly + i * 10, colour, false);
        if (!over.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            UiStyle.tooltip(g, this.font, over, mx, my, 200);
            g.pose().popPose();
        }
    }

    private static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int c) {
        g.fill(x0, y0, x1, y0 + 1, c);
        g.fill(x0, y1 - 1, x1, y1, c);
        g.fill(x0, y0, x0 + 1, y1, c);
        g.fill(x1 - 1, y0, x1, y1, c);
    }

    private void howBig(GuiGraphics g) {
        int x = left + PAD, inner = W - PAD * 2;
        UiStyle.heading(g, this.font, "How big" + (most < FoundingPlan.MAX_FOLK ? " (this server: up to " + most + ")" : ""), x, top + 25, inner);
        g.drawString(this.font, "Name", x, top + 36 + 22 + 26 + 5, UiStyle.TEXT_FAINT, false);
        int y = top + 112;
        UiStyle.heading(g, this.font, "What that means", x, y, inner);
        y += 12;
        int bottom = top + h - PAD - 20 - (!board && pick() != null && !pick().here() ? 28 : 4);
        UiStyle.well(g, x - 2, y - 2, inner + 4, bottom - (y - 2), UiStyle.PAGE);
        Pick p = pick();
        if (p != null) {
            y = line(g, "Where", (board ? "at the board: " : p.here() ? "here: " : p.dist() + " blocks " + p.bearing() + ": ") + p.words() + ".", x, y, inner, UiStyle.TEXT, 1);
        }
        int across = 2 * FoundingPlan.coreRadius(count) + 1;
        y = line(g, "Ground", "about " + across + " by " + across + " blocks made level, the edges sloped into the land.", x, y, inner, UiStyle.TEXT, 1);
        int kinds = FoundingPlan.tradeKinds(count);
        y = line(g, "Trades", kinds + (kinds == 1 ? " kind" : " kinds") + " of work: " + FoundingPlan.trades(count, 3) + ".", x, y, inner, UiStyle.TEXT, 1);
        int homes = VillageMath.housesWanted(count, true);
        y = line(g, "Homes", homes + (homes == 1 ? " house" : " houses") + " wanted; they camp till they are built.", x, y, inner, UiStyle.TEXT, 1);
        double ms = FoundingPlan.msPerTick(count);
        int load = ms >= 20 ? UiStyle.DANGER : ms >= 6 ? UiStyle.WARNING : UiStyle.TEXT;
        y = line(g, "Server", String.format(java.util.Locale.ROOT, "about %.1f ms of every 50 ms tick", ms)
            + (count >= 150 ? ". Hundreds of folk are heavy for a server." : count >= 60 ? ": a busy town." : "."), x, y, inner, load, 1);
        String why = blocked();
        if (!why.isEmpty()) y = line(g, "Wait", why, x, y, inner, UiStyle.DANGER, 2);
        else if (worldFolk + count > worldCap) line(g, "World", worldFolk + " folk live in the world and it may hold " + worldCap + ": past that, no children.", x, y, inner, UiStyle.WARNING, 1);
    }

    /** "Label: words", wrapped to {@code most} lines. Returns where the next goes. */
    private int line(GuiGraphics g, String label, String words, int x, int y, int w, int colour, int most) {
        String head = label + ": ";
        g.drawString(this.font, head, x + 2, y, UiStyle.TEXT_FAINT, false);
        int hw = this.font.width(head);
        var lines = TextCache.split(this.font, words, w - hw - 4);
        for (int i = 0; i < lines.size() && i < most; i++) {
            g.drawString(this.font, lines.get(i), x + 2 + hw, y, colour, false);
            y += this.font.lineHeight + 1;
        }
        return y + 1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A usual size: chosen when it is the count now. */
    private final class Preset extends Button {
        private final int n;

        Preset(Button.Builder b, int n) {
            super(b);
            this.n = n;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            UiStyle.button(g, Minecraft.getInstance().font, getMessage().getString(), getX(), getY(), getWidth(), getHeight(),
                UiStyle.State.of(this.active, count == n, isHoveredOrFocused()));
        }
    }

    /** For the client's tests and pictures: the spots offered. */
    List<Pick> picks() {
        return picks;
    }
}
