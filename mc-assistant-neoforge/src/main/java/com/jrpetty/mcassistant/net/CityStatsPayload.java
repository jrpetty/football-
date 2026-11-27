package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The town's books for the analytics screen (Annals.snapshot): the days as series, the trades, the folk, the leader.
 *
 * <p>[ui53] The books of a big town are big: a year of days, two hundred folk and every page's report. The game reads a
 * tag off the wire with a budget of 2 MiB of its own reckoning (FriendlyByteBuf.readNbt), which counts every compound,
 * list and string several times over its bytes, and a grown town's books went past it: the client could not read the
 * packet, and a packet that cannot be read takes the player off the server ("Internal Exception ... NBT tag that was too
 * big"). So these books are read with a budget of their own ({@link #READ_QUOTA}); a string too long for the wire's
 * modified UTF-8 (64 KiB, which would fail the write and drop the player just the same) is cut on the way out
 * ({@link #fit}); and books that still cannot be read come in as {@link #UNREADABLE} rather than as a dropped connection.
 */
public record CityStatsPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CityStatsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "city_stats"));

    /** What the client will spend reading one town's books, in the game's own reckoning of a tag's size (64 MiB). */
    public static final long READ_QUOTA = 64L << 20;
    /** The longest string sent: well inside the 65535 bytes of modified UTF-8 a tag's string may take on the wire. */
    public static final int MOST_CHARS = 21000;
    /** Set on the books the client could not read, for the screen to say so instead of opening empty. */
    public static final String UNREADABLE = "ui53_unreadable";

    public static final StreamCodec<RegistryFriendlyByteBuf, CityStatsPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(fit(p.data())),
        CityStatsPayload::read);

    /** Read the books off the wire; never throws (what cannot be read is skipped and marked {@link #UNREADABLE}). */
    static CityStatsPayload read(ByteBuf buf) {
        try {
            Tag t = FriendlyByteBuf.readNbt(buf, NbtAccounter.create(READ_QUOTA));
            return new CityStatsPayload(t instanceof CompoundTag c ? c : new CompoundTag());
        } catch (RuntimeException e) {
            // The rest of this payload is no good to anybody; the packet is this payload's alone, so skip it all.
            buf.skipBytes(buf.readableBytes());
            com.mojang.logging.LogUtils.getLogger().error("[ui53] the town's books could not be read; the board says so instead", e);
            CompoundTag bad = new CompoundTag();
            bad.putBoolean(UNREADABLE, true);
            bad.putString("why", String.valueOf(e.getMessage()));
            return new CityStatsPayload(bad);
        }
    }

    /**
     * [ui53] A tag off the wire with the books' budget ({@link #READ_QUOTA}) rather than the game's 2 MiB: for the screens
     * opened from the board (a folk's card, a firm's books, the property office, finance, favourites, the admin panel),
     * whose tags grow with the town as the books do. Null for an empty tag, as FriendlyByteBuf.readNbt.
     */
    @javax.annotation.Nullable
    public static CompoundTag readBig(ByteBuf buf) {
        Tag t = FriendlyByteBuf.readNbt(buf, NbtAccounter.create(READ_QUOTA));
        if (t != null && !(t instanceof CompoundTag)) throw new io.netty.handler.codec.DecoderException("Not a compound tag: " + t.getType().getName());
        return (CompoundTag) t;
    }

    /**
     * The books made fit for the wire: any string longer than {@link #MOST_CHARS} cut to it, in place. Returns the tag.
     * Walks the whole tag; cheap next to making it.
     */
    public static CompoundTag fit(CompoundTag tag) {
        fitIn(tag, 0);
        return tag;
    }

    private static void fitIn(Tag tag, int depth) {
        if (depth > 256) return;
        if (tag instanceof CompoundTag c) {
            for (String k : c.getAllKeys()) {
                Tag in = c.get(k);
                if (in instanceof StringTag s && s.getAsString().length() > MOST_CHARS) c.putString(k, cut(s.getAsString()));
                else if (in instanceof CompoundTag || in instanceof ListTag) fitIn(in, depth + 1);
            }
        } else if (tag instanceof ListTag l) {
            for (int i = 0; i < l.size(); i++) {
                Tag in = l.get(i);
                if (in instanceof StringTag s && s.getAsString().length() > MOST_CHARS) l.set(i, StringTag.valueOf(cut(s.getAsString())));
                else if (in instanceof CompoundTag || in instanceof ListTag) fitIn(in, depth + 1);
            }
        }
    }

    private static String cut(String s) {
        int end = MOST_CHARS - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;      // not half a letter
        return s.substring(0, end) + "…";
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
