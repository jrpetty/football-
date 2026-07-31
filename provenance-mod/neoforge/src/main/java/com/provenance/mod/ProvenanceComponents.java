package com.provenance.mod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.provenance.core.ItemStamp;
import com.provenance.core.ItemSummary;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.UUIDUtil;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * The only thing this system writes onto a physical item: its Item Record ID
 * and the current anti-duplication token.
 *
 * <p>Statistics, contributors and milestone dates are deliberately absent.
 * Keeping them server-side means item components never grow with an item's
 * history, a client can never edit a counter, and the Contributors list of a
 * thousand-player relic costs the item stack nothing.
 */
public final class ProvenanceComponents {

    public static final DeferredRegister<DataComponentType<?>> REGISTRAR =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Provenance.MOD_ID);

    /** Persisted on the stack and synced to the client so tooltips can render. */
    public static final Codec<ItemStamp> STAMP_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("record_id").forGetter(ItemStamp::recordId),
            Codec.LONG.fieldOf("token").forGetter(ItemStamp::bindingToken)
    ).apply(instance, ItemStamp::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemStamp> STAMP_STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ItemStamp::recordId,
                    ByteBufCodecs.VAR_LONG, ItemStamp::bindingToken,
                    ItemStamp::new);

    public static final Supplier<DataComponentType<ItemStamp>> STAMP = REGISTRAR.register("stamp",
            () -> DataComponentType.<ItemStamp>builder()
                    .persistent(STAMP_CODEC)
                    .networkSynchronized(STAMP_STREAM_CODEC)
                    .build());

    /**
     * A few display values, so the tooltip can show more than "press H".
     *
     * <p>Refreshed on a slow interval and only when it actually changed, never
     * per action — writing to a stack dirties its inventory slot and forces a
     * client sync, which is the cost this mod works hardest to avoid.
     */
    public static final Codec<ItemSummary> SUMMARY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("tier").forGetter(ItemSummary::tierIndex),
            Codec.STRING.fieldOf("crafter").forGetter(ItemSummary::crafterName),
            Codec.STRING.fieldOf("stat").forGetter(ItemSummary::primaryStatId),
            Codec.LONG.fieldOf("value").forGetter(ItemSummary::primaryStatValue)
    ).apply(instance, ItemSummary::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemSummary> SUMMARY_STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ItemSummary::tierIndex,
                    ByteBufCodecs.STRING_UTF8, ItemSummary::crafterName,
                    ByteBufCodecs.STRING_UTF8, ItemSummary::primaryStatId,
                    ByteBufCodecs.VAR_LONG, ItemSummary::primaryStatValue,
                    ItemSummary::new);

    public static final Supplier<DataComponentType<ItemSummary>> SUMMARY = REGISTRAR.register("summary",
            () -> DataComponentType.<ItemSummary>builder()
                    .persistent(SUMMARY_CODEC)
                    .networkSynchronized(SUMMARY_STREAM_CODEC)
                    .build());

    private ProvenanceComponents() {
    }

    public static void register(IEventBus modEventBus) {
        REGISTRAR.register(modEventBus);
    }
}
