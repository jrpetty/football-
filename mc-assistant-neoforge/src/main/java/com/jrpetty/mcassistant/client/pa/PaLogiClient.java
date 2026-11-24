package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.pa.PaPings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * [pa36] The client end of the logistics: which pinged blocks get the container wheel, the pickers that wheel opens
 * (put in, take out, fill with, keep stocked with, name it), the Storage view and the Craft view, and the server's
 * answers to each. Everything is sent as a "logi" action; nothing goes through the chat.
 */
public final class PaLogiClient {

    private PaLogiClient() {}

    @Nullable static CompoundTag storage, peek, craft, recipe;
    static int stamp;

    /** The wheel a container gets: shulker boxes, hoppers, dispensers and droppers, ender chests are chests here. */
    @Nullable
    public static PaPings.Target classify(BlockState s) {
        var b = s.getBlock();
        if (b instanceof ShulkerBoxBlock || b instanceof HopperBlock || b instanceof DispenserBlock || b instanceof EnderChestBlock) return PaPings.Target.CHEST;
        if (b instanceof ComposterBlock) return PaPings.Target.COMPOSTER;
        if (b instanceof BrewingStandBlock) return PaPings.Target.BREWING;
        return null;
    }

    /** A wheel choice that needs a picker. */
    public static void open(@Nullable BlockPos at, String id, String label) {
        if (at == null) return;
        Minecraft mc = Minecraft.getInstance();
        PaChestScreen.Mode m = switch (id) {
            case "ltake" -> PaChestScreen.Mode.TAKE;
            case "lfill" -> PaChestScreen.Mode.FILL;
            case "lstock" -> PaChestScreen.Mode.STOCK;
            case "lname" -> PaChestScreen.Mode.NAME;
            default -> PaChestScreen.Mode.PUT;
        };
        mc.setScreen(new PaChestScreen(at, m, label));
        if (m != PaChestScreen.Mode.NAME) {
            CompoundTag t = pos(at);
            send("peek", t);
        }
    }

    public static void storage() {
        Minecraft.getInstance().setScreen(new PaStorageScreen());
        send("storage", new CompoundTag());
    }

    public static void craft(String q) {
        Minecraft.getInstance().setScreen(new PaCraftScreen(q));
        CompoundTag t = new CompoundTag();
        t.putString("q", q);
        send("craftq", t);
    }

    static CompoundTag pos(BlockPos b) {
        CompoundTag t = new CompoundTag();
        t.putInt("x", b.getX());
        t.putInt("y", b.getY());
        t.putInt("z", b.getZ());
        return t;
    }

    static void send(String op, CompoundTag t) {
        t.putString("op", op);
        PaClient.send("logi", t);
    }

    /** An answer from the server. */
    public static void heard(CompoundTag d) {
        Minecraft mc = Minecraft.getInstance();
        stamp++;
        switch (d.getString("op")) {
            case "storage" -> {
                storage = d;
                if (!(mc.screen instanceof PaStorageScreen)) mc.setScreen(new PaStorageScreen());
            }
            case "peek" -> peek = d;
            case "craft" -> {
                craft = d;
                if (!(mc.screen instanceof PaCraftScreen)) mc.setScreen(new PaCraftScreen(d.getString("q")));
            }
            case "recipe" -> {
                recipe = d;
                if (!(mc.screen instanceof PaCraftScreen)) {
                    mc.setScreen(new PaCraftScreen(""));
                    CompoundTag t = new CompoundTag();
                    t.putString("q", "");
                    send("craftq", t);
                }
            }
            default -> { }
        }
    }
}
