package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Individual;
import com.jrpetty.mcassistant.entity.Looks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [individual] Each folk's own picture, put together from the layers tools/folk_looks.py paints
 * (textures/entity/folk/look): its skin and the shape of its face, its nose, eyes and brows, its hair as it wears it,
 * its beard, the lines of its years, freckles, a scar, soot or sunburn, an eyepatch, spectacles, its plain clothes,
 * coloured from its own skin tone, hair, eyes and clothes (and greyed for its years, the temples first).
 *
 * <p>Built once for each look (its face and its marks, two numbers the server sends: Looks.pack, Individual.marks)
 * and kept: a town of a hundred is a hundred pictures, made the first time each is seen, a few a frame at most, and
 * dropped (the least lately seen first) when more than a few hundred are kept. Nothing is drawn per frame. Until a
 * folk's look is known, or while its picture waits its turn, it wears one of the ten old faces (FolkLooks).
 *
 * <p>The colours, the shading and the layer lists are folk_looks.py's, written below by it: the picture made here and
 * the one its contact sheet draws are the same picture.
 */
public final class FolkFaces {

    private FolkFaces() {}

    // BEGIN GENERATED FACES
    static final int[] TONES = {0xF4D6C4, 0xECC4A6, 0xE0B28C, 0xD0A276, 0xBE8E62, 0xAA7850, 0x926442, 0x7A5034, 0x62402A, 0x4C3224};
    static final int[] HAIRS = {0x282224, 0x422E22, 0x66462C, 0x804C2A, 0x924026, 0xB04224, 0xCE7034, 0xDEBA70, 0xB6A88C, 0xECD6A0};
    static final int[] EYES = {0x663E22, 0x3E281C, 0x806634, 0xB27A2A, 0x468048, 0x4874BA, 0x7A8692};
    static final int[] SHIRTS = {0xC4B696, 0xD6C8AA, 0x8298AC, 0xA46044, 0x707E56, 0xE2D8BE, 0x96928C, 0xB09670};
    static final int[] TROUSERS = {0x6E5C46, 0x544E48, 0x464458, 0x62543C, 0x3E3832, 0x7A6A50};
    static final int[] SHOES = {0x3E2C20, 0x28221E, 0x5C4028, 0x463C34};
    static final int GREY_HAIR = 0xACAAA6, WHITE_HAIR = 0xE8E6E0, EYE_WHITE = 0xF0ECE4;
    static final int DARK = 0x221816, SOOT = 0x302C2A;
    static final float[] SHADOW = {0.56F, 0.45F, 0.5F}, LIGHT_GAIN = {0.46F, 0.42F, 0.34F};
    static final int[] GREY_AT = {0, 55, 105, 160, 215, 256};
    static final double GREY_KEEP = 0.22, GREY_FADE = 0.13;
    static final int[][] MOLE_SPOTS = {{9, 15}, {14, 15}, {10, 17}, {13, 17}, {8, 13}, {15, 14}, {14, 10}, {9, 10}};
    // {from, its colour if fixed, scaled by (thousandths), toward, its colour if fixed, how far (thousandths)}
    static final int[][] DERIVED = {{0, 0x000000, 1000, 6, 0x000000, 0}, {1, 0x000000, 1000, 6, 0x000000, 0}, {1, 0x000000, 860, 6, 0x000000, 0}, {1, 0x000000, 920, 6, 0x000000, 0}, {2, 0x000000, 1000, 6, 0x000000, 0}, {6, 0xF0ECE4, 1000, 0, 0x000000, 200}, {0, 0x000000, 860, 6, 0xB04A4A, 240}, {0, 0x000000, 1000, 6, 0xE86868, 300}, {6, 0x221816, 1000, 0, 0x000000, 120}, {0, 0x000000, 860, 6, 0xA86036, 220}, {0, 0x000000, 1000, 6, 0xECB0A8, 420}, {6, 0x302C2A, 1000, 6, 0x000000, 0}, {0, 0x000000, 1000, 6, 0xE25C46, 300}, {3, 0x000000, 1000, 6, 0x000000, 0}, {4, 0x000000, 1000, 6, 0x000000, 0}, {5, 0x000000, 1000, 6, 0x000000, 0}, {0, 0x000000, 500, 6, 0x543224, 400}};
    static final int HAIR = 1, BROW = 2, BEARD = 3, MOLE = 16;
    static final String[] PLAIN = {"patch_1", "patch_2", "spectacles", "cane", "pipe"};
    static final int FACES = 5, NOSES = 4, EYE_SHAPES = 6, BROWS = 7, STYLES = 12, TEXTURES = 3, FACIAL = 7, SCARS = 4;
    // END GENERATED FACES

    /** The marks that change the picture (not the stoop or a child's growth, which the model and the size show). */
    private static final int PICTURE_MARKS = 0b111_111_111 << 13 | 0b1111111;

    private static final int SIZE = 128;
    private static final int MOST_KEPT = 320;
    /** Pictures made in one stretch of frames, and how long the stretch is. */
    private static final int BUDGET = 4;
    private static final long STRETCH_NS = 12_000_000L;

    private record Key(long look, int marks) {}

    private static final Map<Key, ResourceLocation> KEPT = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, ResourceLocation> eldest) {
            if (size() <= MOST_KEPT) return false;
            Minecraft.getInstance().getTextureManager().release(eldest.getValue());
            return true;
        }
    };
    private static final Map<String, int[]> LAYERS = new HashMap<>();
    private static boolean broken;
    private static long stretchFrom;
    private static int madeInStretch;
    private static int made;

    /** The picture to draw this folk with. */
    public static ResourceLocation texture(VillageFolkEntity folk) {
        long look = folk.clientLook();
        if (!Looks.known(look) || broken) return FolkLooks.skinTexture(folk);
        Key key = new Key(look, folk.clientMarks() & PICTURE_MARKS);
        ResourceLocation got = KEPT.get(key);
        if (got != null) return got;
        long now = System.nanoTime();
        if (now - stretchFrom > STRETCH_NS) {
            stretchFrom = now;
            madeInStretch = 0;
        }
        if (madeInStretch >= BUDGET) return FolkLooks.skinTexture(folk);
        madeInStretch++;
        try {
            NativeImage img = new NativeImage(SIZE, SIZE, true);
            int[] px = compose(key.look(), key.marks());
            if (px == null) {
                img.close();
                return FolkLooks.skinTexture(folk);
            }
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) img.setPixelRGBA(x, y, abgr(px[y * SIZE + x]));
            }
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folkface/" + (made++));
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            KEPT.put(key, loc);
            return loc;
        } catch (RuntimeException e) {
            broken = true;
            return FolkLooks.skinTexture(folk);
        }
    }

    /** How many pictures are kept just now (for the debug overlay and the tests). */
    public static int kept() {
        return KEPT.size();
    }

    // ------------------------------------------------------------------ composed

    /** The layers of one folk's picture, bottom to top (folk_looks.stack). */
    static List<String> stack(long look, int marks) {
        List<String> names = new ArrayList<>();
        names.add("clothes");
        names.add("face_" + Looks.face(look));
        names.add("nose_" + Looks.nose(look));
        if (Looks.lines(look) > 0) names.add("lines_" + Looks.lines(look));
        if (Looks.freckles(look)) names.add("freckles");
        if (Looks.rosy(look)) names.add("rosy");
        if (Individual.sunOf(marks)) names.add("sun");
        if (Individual.scarOf(marks) > 0) names.add("scar_" + Individual.scarOf(marks));
        names.add("eyes_" + Looks.eyeShape(look));
        names.add("brows_" + Looks.brows(look));
        int facial = Looks.facial(look);
        if (facial == 1) names.add("facial_1");
        names.add("hair_" + Looks.style(look) + "_" + Looks.texture(look));
        if (facial > 1) names.add("facial_" + facial);
        if (Individual.sootOf(marks)) names.add("soot");
        if (Individual.patchOf(marks) > 0) names.add("patch_" + Individual.patchOf(marks));
        if (Individual.specsOf(marks)) names.add("spectacles");
        names.add("cane");
        names.add("pipe");
        return names;
    }

    /** The picture, as 0xAARRGGBB a pixel, or null if a layer cannot be had. */
    @Nullable
    static int[] compose(long look, int marks) {
        int[] colour = new int[DERIVED.length];
        int skin = TONES[clamp(Looks.tone(look), TONES.length)], hair = HAIRS[clamp(Looks.hair(look), HAIRS.length)];
        int eye = EYES[clamp(Looks.eye(look), EYES.length)];
        int[] have = {skin, hair, eye, SHIRTS[clamp(Individual.shirtOf(marks), SHIRTS.length)],
            TROUSERS[clamp(Individual.trousersOf(marks), TROUSERS.length)], SHOES[clamp(Individual.shoesOf(marks), SHOES.length)], 0};
        for (int p = 0; p < DERIVED.length; p++) {
            int[] d = DERIVED[p];
            int a = d[0] == 6 ? d[1] : have[d[0]];
            int b = d[3] == 6 ? d[4] : have[d[3]];
            colour[p] = mix(scale(a, d[2] / 1000.0), b, d[5] / 1000.0);
        }
        int grey = Looks.grey(look);
        int[] out = new int[SIZE * SIZE];
        for (String name : stack(look, marks)) {
            int[] layer = layer(name);
            if (layer == null) return null;
            boolean plain = isPlain(name);
            for (int i = 0; i < out.length; i++) {
                int p = layer[i];
                int a = p >>> 24;
                if (a == 0) continue;
                int col;
                if (plain) {
                    col = p & 0xFFFFFF;
                } else {
                    int v = p >>> 16 & 0xFF, pal = p >>> 8 & 0xFF, order = p & 0xFF;
                    int base = pal < colour.length ? colour[pal] : 0xFF00FF;
                    int level = grey - (pal == BROW ? 1 : 0);
                    if ((pal == HAIR || pal == BROW || pal == BEARD) && level > 0) {
                        if (level >= 5) base = WHITE_HAIR;                          // salt and pepper, as folk_looks has it
                        else if (order < GREY_AT[level]) base = mix(GREY_HAIR, base, GREY_KEEP);
                        else base = mix(base, GREY_HAIR, GREY_FADE * level);
                    }
                    col = ramp(base, v);
                }
                int under = out[i];
                if (a >= 255 || under >>> 24 == 0) out[i] = 0xFF000000 | col;
                else out[i] = 0xFF000000 | mix(under & 0xFFFFFF, col, a / 255.0);
            }
        }
        int mole = Looks.mole(look);
        if (mole > 0 && mole <= MOLE_SPOTS.length) {
            int[] at = MOLE_SPOTS[mole - 1];
            out[at[1] * SIZE + at[0]] = 0xFF000000 | ramp(colour[MOLE], 128);
        }
        return out;
    }

    private static boolean isPlain(String name) {
        for (String p : PLAIN) if (p.equals(name)) return true;
        return false;
    }

    /** One layer's pixels as 0xAARRGGBB, read once and kept. */
    @Nullable
    private static int[] layer(String name) {
        int[] got = LAYERS.get(name);
        if (got != null) return got;
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/look/" + name + ".png");
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
        if (res.isEmpty()) {
            broken = true;
            return null;
        }
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            int[] px = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE && y < img.getHeight(); y++) {
                for (int x = 0; x < SIZE && x < img.getWidth(); x++) px[y * SIZE + x] = argb(img.getPixelRGBA(x, y));
            }
            LAYERS.put(name, px);
            return px;
        } catch (Exception e) {
            broken = true;
            return null;
        }
    }

    // ------------------------------------------------------------------ colour sums (folk_looks.ramp and mixc)

    /** The shade v (0-255) of a colour: 128 the colour, down to its warm shadow, up to its highlight. */
    static int ramp(int base, int v) {
        int[] c = {base >>> 16 & 0xFF, base >>> 8 & 0xFF, base & 0xFF};
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            double k = c[i];
            double r;
            if (v == 128) r = k;
            else if (v < 128) r = k + (k * SHADOW[i] - k) * ((128 - v) / 128.0);
            else r = k + (255 - k) * LIGHT_GAIN[i] * ((v - 128) / 127.0);
            out[i] = Math.max(0, Math.min(255, (int) Math.floor(r + 0.5)));
        }
        return out[0] << 16 | out[1] << 8 | out[2];
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.floor((a >>> 16 & 0xFF) + ((b >>> 16 & 0xFF) - (a >>> 16 & 0xFF)) * t + 0.5);
        int g = (int) Math.floor((a >>> 8 & 0xFF) + ((b >>> 8 & 0xFF) - (a >>> 8 & 0xFF)) * t + 0.5);
        int bl = (int) Math.floor((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t + 0.5);
        return clamp255(r) << 16 | clamp255(g) << 8 | clamp255(bl);
    }

    private static int scale(int c, double k) {
        return clamp255((int) ((c >>> 16 & 0xFF) * k)) << 16 | clamp255((int) ((c >>> 8 & 0xFF) * k)) << 8 | clamp255((int) ((c & 0xFF) * k));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int clamp(int i, int n) {
        return Math.max(0, Math.min(n - 1, i));
    }

    /** NativeImage keeps its pixels as 0xAABBGGRR. */
    private static int argb(int abgr) {
        return abgr & 0xFF00FF00 | (abgr & 0xFF) << 16 | abgr >>> 16 & 0xFF;
    }

    private static int abgr(int argb) {
        return argb & 0xFF00FF00 | (argb & 0xFF) << 16 | argb >>> 16 & 0xFF;
    }
}
