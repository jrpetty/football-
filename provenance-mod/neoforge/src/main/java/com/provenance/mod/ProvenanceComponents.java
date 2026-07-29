package com.provenance.mod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.provenance.core.ItemStamp;
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

    private ProvenanceComponents() {
    }

    public static void register(IEventBus modEventBus) {
        REGISTRAR.register(modEventBus);
    }
}
