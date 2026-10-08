package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Words wrapped and cut to fit, kept from one frame to the next. The screens, the pages, the bubbles over the folk and
 * the boards are drawn afresh every frame, and wrapping a paragraph (Font.split) or cutting a line to its width
 * (Ui.clip, a width measured for every letter taken off) every frame for the same words came to most of what a page of
 * text cost to draw. The same words wrapped to the same width in the same font come out the same, so each is worked
 * out once and kept: the least lately asked for dropped first once there are more than a few thousand.
 *
 * <p>What is kept is only good for the font it was made with. All of it is let go when the font's options change
 * (Force Unicode Font, the Japanese glyphs) and after resources are reloaded (a resource pack, the language: the
 * loading overlay is up while that happens), and nothing is kept while the overlay is up, when the font may be half
 * changed. {@link #epoch} counts the letting go, for the caches kept elsewhere (the board's layout) to know theirs too
 * is stale.
 */
@EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TextCache {

    private TextCache() {}

    /** Told of every reload of resources, so what was kept is let go once it is over, whatever was drawn meanwhile. */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> reloading = true);
    }

    private static final int MOST_KEPT = 4096;

    /** Wrapped as Component.literal, wrapped as FormattedText.of, and cut to fit (Ui.clip): each its own answer. */
    private static final byte LITERAL = 0, PLAIN = 1, CLIP = 2;

    private record Key(Font font, String text, int width, byte how) {}

    private static final Map<Key, Object> KEPT = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
            return size() > MOST_KEPT;
        }
    };

    private static int epoch;
    private static int options = -1;
    private static boolean reloading;

    /**
     * May what is worked out now be kept (and what was kept be used)? Lets everything go first if the font has changed
     * since it was kept.
     */
    public static boolean fresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) {                // resources loading: the font may be changing under us
            reloading = true;
            return false;
        }
        int now = (mc.options.forceUnicodeFont().get() ? 1 : 0) | (mc.options.japaneseGlyphVariants().get() ? 2 : 0);
        if (reloading || now != options) {
            reloading = false;
            options = now;
            KEPT.clear();
            epoch++;
        }
        return true;
    }

    /** How many times what was kept has been let go; a cache elsewhere made at another epoch is stale. */
    public static int epoch() {
        return epoch;
    }

    /** font.split(Component.literal(text), width), kept. The list is not to be changed. */
    public static List<FormattedCharSequence> split(Font font, String text, int width) {
        return wrapped(font, text, width, LITERAL);
    }

    /** font.split(FormattedText.of(text), width), kept. The list is not to be changed. */
    public static List<FormattedCharSequence> splitPlain(Font font, String text, int width) {
        return wrapped(font, text, width, PLAIN);
    }

    @SuppressWarnings("unchecked")
    private static List<FormattedCharSequence> wrapped(Font font, String text, int width, byte how) {
        if (!fresh()) return wrap(font, text, width, how);
        Key key = new Key(font, text, width, how);
        Object got = KEPT.get(key);
        if (got != null) return (List<FormattedCharSequence>) got;
        List<FormattedCharSequence> made = Collections.unmodifiableList(wrap(font, text, width, how));
        KEPT.put(key, made);
        return made;
    }

    private static List<FormattedCharSequence> wrap(Font font, String text, int width, byte how) {
        return font.split(how == LITERAL ? Component.literal(text) : FormattedText.of(text), width);
    }

    /** Ui.clip, kept. */
    public static String clip(Font font, String text, int max) {
        if (!fresh()) return Ui.clipNow(font, text, max);
        Key key = new Key(font, text, max, CLIP);
        Object got = KEPT.get(key);
        if (got != null) return (String) got;
        String made = Ui.clipNow(font, text, max);
        KEPT.put(key, made);
        return made;
    }
}
